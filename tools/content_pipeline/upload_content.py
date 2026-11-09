#!/usr/bin/env python3
"""
Upload the bundled Lixo curriculum and lexicon to Cloud Firestore (`lixo-fd45d`).

Upload-only: reads composeApp/src/commonMain/composeResources/files/seed_campaign_content.json
(the source of truth for curriculum content) and tools/content_pipeline/lexicon/lexicon.json
(the source of truth for the dictionary/lexicon) and never writes or regenerates either file.
Uploads courses, levels and tasks documents, plus the chunked `lexicon/chunk_N` + `lexicon/meta`
documents, with batched `documents:commit`. Tasks must not carry `rewardItemId`: wardrobe item
ids are app-owned (see doc/plans/002-level-reward-item-ids.md).

Usage:
    python3 tools/content_pipeline/upload_content.py --validate-only  # offline-only validation
    python3 tools/content_pipeline/upload_content.py --dry-run        # validate + show counts only
    python3 tools/content_pipeline/upload_content.py                  # upload (requires `firebase login`)
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Tuple

FIREBASE_PROJECT_ID = "lixo-fd45d"
FIRESTORE_DATABASE_ID = "(default)"
SEED_BUNDLE_RELATIVE_PATH = "composeApp/src/commonMain/composeResources/files/seed_campaign_content.json"
LEXICON_RELATIVE_PATH = "tools/content_pipeline/lexicon/lexicon.json"

# Firestore batch commit supports up to 500 writes per request.
BATCH_SIZE = 400

# Keeps each `lexicon/chunk_N` document comfortably under Firestore's 1 MiB document size limit.
LEXICON_CHUNK_SIZE = 250

_LEXICON_LANGUAGES = {"sv-SE", "en-US"}
_LEXICON_STRING_FIELDS = (
    "wordNormalized",
    "displayWord",
    "syllables",
    "explanationSv",
    "exampleSentenceSv",
    "languageIso",
)


def load_bundle(path: Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def load_lexicon(path: Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def normalize_word(raw: str) -> str:
    """Mirrors Kotlin's ForcedAlignmentEngine.normalizeForAlignment: lowercase, then trim
    non-letter/digit characters from both ends only (NOT str.strip(string.punctuation), which
    misses characters like ” )."""
    s = raw.lower()
    start = 0
    end = len(s)
    while start < end and not s[start].isalnum():
        start += 1
    while end > start and not s[end - 1].isalnum():
        end -= 1
    return s[start:end]


def validate_bundle(bundle: Dict[str, Any]) -> List[str]:
    errors: List[str] = []
    levels = bundle.get("levels", [])
    tasks = bundle.get("tasks", [])
    task_ids = {t["id"] for t in tasks}

    orders = sorted(lvl["order"] for lvl in levels)
    if orders != list(range(1, len(levels) + 1)):
        errors.append("Level orders must be contiguous 1..N")
    for lvl in levels:
        if lvl["taskId"] not in task_ids:
            errors.append(f"{lvl['id']} references missing task {lvl['taskId']}")
    course_ids = {c["id"] for c in bundle.get("courses", [])}
    for lvl in levels:
        if lvl["courseId"] not in course_ids:
            errors.append(f"{lvl['id']} references missing course {lvl['courseId']}")
    for t in tasks:
        if t["type"] == "READING" and not t.get("textContent"):
            errors.append(f"{t['id']} is a reading task without textContent")
        if "rewardItemId" in t:
            errors.append(f"{t['id']} has rewardItemId; the app owns level reward item ids (LixoRepository.buildLevelRewardPool), remove the field from the bundle")
    return errors


def validate_lexicon(bundle: Dict[str, Any], lexicon: Dict[str, Any]) -> Tuple[List[str], List[str]]:
    """Returns (errors, missing_words). `errors` are schema/shape problems with the lexicon file
    itself; `missing_words` are story words (from every task's textContent in `bundle`) that have
    no matching lexicon entry, formatted as "missing lexicon word: <w> (task_x)"."""
    errors: List[str] = []

    version = lexicon.get("version")
    if not isinstance(version, int) or version < 1:
        errors.append("Lexicon version must be an int >= 1")

    entries = lexicon.get("entries", [])
    seen_keys: Dict[str, int] = {}
    for idx, entry in enumerate(entries):
        for field in _LEXICON_STRING_FIELDS:
            value = entry.get(field)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"Lexicon entry #{idx} ({entry.get('wordNormalized')!r}) has an empty/invalid {field}")
        language_iso = entry.get("languageIso")
        if language_iso not in _LEXICON_LANGUAGES:
            errors.append(f"Lexicon entry #{idx} ({entry.get('wordNormalized')!r}) has invalid languageIso {language_iso!r}")
        word = entry.get("wordNormalized", "")
        if isinstance(word, str) and word != normalize_word(word):
            errors.append(f"Lexicon entry wordNormalized {word!r} is not normalized (expected {normalize_word(word)!r})")
        explanation = entry.get("explanationSv", "")
        if isinstance(explanation, str) and explanation.startswith("I sagan dyker ordet"):
            errors.append(f"Lexicon entry {word!r} has a template explanationSv instead of a real definition")
        if word in seen_keys:
            errors.append(f"Duplicate lexicon key: {word!r}")
        else:
            seen_keys[word] = idx

    missing_words: List[str] = []
    if not errors:
        lexicon_keys = set(seen_keys.keys())
        missing: set[Tuple[str, str]] = set()
        for task in bundle.get("tasks", []):
            text = task.get("textContent")
            if not text:
                continue
            for raw in text.split():
                word = normalize_word(raw)
                if word and word not in lexicon_keys:
                    missing.add((word, task["id"]))
        for word, task_id in sorted(missing):
            missing_words.append(f"missing lexicon word: {word} ({task_id})")

    return errors, missing_words


def _to_firestore_value(val: Any) -> Dict[str, Any]:
    """Converts a Python value into a Firestore REST API Value object."""
    if val is None:
        return {"nullValue": None}
    if isinstance(val, bool):
        return {"booleanValue": val}
    if isinstance(val, int):
        return {"integerValue": str(val)}
    if isinstance(val, float):
        return {"doubleValue": val}
    if isinstance(val, str):
        return {"stringValue": val}
    if isinstance(val, list):
        return {"arrayValue": {"values": [_to_firestore_value(x) for x in val]}}
    if isinstance(val, dict):
        return {"mapValue": {"fields": {k: _to_firestore_value(v) for k, v in val.items()}}}
    raise TypeError(f"Unsupported Firestore value type: {type(val)}")


def _load_firebase_cli_access_token() -> str:
    cfg_path = Path.home() / ".config" / "configstore" / "firebase-tools.json"
    if not cfg_path.exists():
        raise RuntimeError(f"Firebase CLI config not found at {cfg_path}. Run `firebase login` first.")
    tokens = json.loads(cfg_path.read_text(encoding="utf-8")).get("tokens", {})
    token = tokens.get("access_token")
    if not token:
        raise RuntimeError("No access_token in firebase-tools.json. Run `firebase login --reauth`.")
    expires_at_ms = tokens.get("expires_at")
    if isinstance(expires_at_ms, (int, float)) and expires_at_ms < time.time() * 1000:
        raise RuntimeError(
            "Firebase CLI access token has expired. Run any firebase command "
            "(e.g. `firebase projects:list`) to refresh it, or `firebase login --reauth`."
        )
    return token


def build_writes(bundle: Dict[str, Any], doc_prefix: str) -> List[Dict[str, Any]]:
    collections = [
        ("courses", bundle.get("courses", []), "id"),
        ("levels", bundle.get("levels", []), "id"),
        ("tasks", bundle.get("tasks", []), "id"),
    ]
    writes: List[Dict[str, Any]] = []
    for collection, docs, id_field in collections:
        for doc in docs:
            writes.append(
                {
                    "update": {
                        "name": f"{doc_prefix}/{collection}/{doc[id_field]}",
                        "fields": {k: _to_firestore_value(v) for k, v in doc.items()},
                    }
                }
            )
    return writes


def build_lexicon_writes(lexicon: Dict[str, Any], doc_prefix: str) -> List[Dict[str, Any]]:
    """Chunks `lexicon/chunk_N` documents first, then writes `lexicon/meta` last so a reader
    that already has the previous version never observes a meta bump without its chunks."""
    entries = lexicon.get("entries", [])
    chunks = [entries[i : i + LEXICON_CHUNK_SIZE] for i in range(0, len(entries), LEXICON_CHUNK_SIZE)]
    writes: List[Dict[str, Any]] = []
    for i, chunk in enumerate(chunks):
        doc = {"entries": chunk}
        writes.append(
            {
                "update": {
                    "name": f"{doc_prefix}/lexicon/chunk_{i}",
                    "fields": {k: _to_firestore_value(v) for k, v in doc.items()},
                }
            }
        )
    meta = {"version": lexicon["version"], "chunkCount": len(chunks)}
    writes.append(
        {
            "update": {
                "name": f"{doc_prefix}/lexicon/meta",
                "fields": {k: _to_firestore_value(v) for k, v in meta.items()},
            }
        }
    )
    return writes


def firestore_doc_prefix(project_id: str, database_id: str) -> str:
    return f"projects/{project_id}/databases/{database_id}/documents"


def commit_writes(writes: List[Dict[str, Any]], project_id: str, database_id: str, token: str | None = None) -> int:
    """Batches `writes` into Firestore `documents:commit` POSTs of up to BATCH_SIZE each, in order."""
    token = token or _load_firebase_cli_access_token()
    doc_prefix = firestore_doc_prefix(project_id, database_id)
    commit_url = f"https://firestore.googleapis.com/v1/{doc_prefix}:commit"

    for start in range(0, len(writes), BATCH_SIZE):
        chunk = writes[start : start + BATCH_SIZE]
        req = urllib.request.Request(
            commit_url,
            data=json.dumps({"writes": chunk}, ensure_ascii=False).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json; charset=utf-8",
            },
            method="POST",
        )
        with urllib.request.urlopen(req) as resp:
            if resp.status not in (200, 201):
                raise RuntimeError(f"Firestore commit failed with HTTP {resp.status}")
    return len(writes)


def upload(bundle: Dict[str, Any], lexicon: Dict[str, Any], project_id: str, database_id: str) -> int:
    doc_prefix = firestore_doc_prefix(project_id, database_id)
    writes = build_writes(bundle, doc_prefix) + build_lexicon_writes(lexicon, doc_prefix)
    return commit_writes(writes, project_id, database_id)


def main(argv: List[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Upload the bundled Lixo curriculum and lexicon JSON to Firestore.")
    parser.add_argument("--input", default=SEED_BUNDLE_RELATIVE_PATH, help="Path to seed_campaign_content.json")
    parser.add_argument("--lexicon", default=LEXICON_RELATIVE_PATH, help="Path to lexicon.json")
    parser.add_argument("--project", default=FIREBASE_PROJECT_ID)
    parser.add_argument("--database", default=FIRESTORE_DATABASE_ID)
    parser.add_argument("--dry-run", action="store_true", help="Validate and print counts without uploading.")
    parser.add_argument(
        "--validate-only",
        action="store_true",
        help="Validate the bundle and lexicon offline (no network) and exit.",
    )
    args = parser.parse_args(argv)

    repo_root = Path(__file__).resolve().parents[2]
    bundle_path = Path(args.input) if Path(args.input).is_absolute() else repo_root / args.input
    lexicon_path = Path(args.lexicon) if Path(args.lexicon).is_absolute() else repo_root / args.lexicon
    bundle = load_bundle(bundle_path)
    lexicon = load_lexicon(lexicon_path)

    errors = validate_bundle(bundle)
    if errors:
        for err in errors:
            print(f"[ERROR] {err}", file=sys.stderr)
        return 1

    lexicon_errors, missing_words = validate_lexicon(bundle, lexicon)
    if lexicon_errors or missing_words:
        for err in lexicon_errors:
            print(f"[ERROR] {err}", file=sys.stderr)
        for word in missing_words:
            print(f"[ERROR] {word}", file=sys.stderr)
        return 1

    story_word_count = len({normalize_word(w) for t in bundle.get("tasks", []) if t.get("textContent") for w in t["textContent"].split()})

    if args.validate_only:
        print(
            f"[OK] Lexicon v{lexicon['version']}: {len(lexicon['entries'])} entries cover all "
            f"{story_word_count} story words (0 missing)."
        )
        return 0

    summary = (
        f"{len(bundle.get('courses', []))} courses, {len(bundle.get('levels', []))} levels, "
        f"{len(bundle.get('tasks', []))} tasks, "
        f"{len(lexicon['entries'])} lexicon entries in {(len(lexicon['entries']) + LEXICON_CHUNK_SIZE - 1) // LEXICON_CHUNK_SIZE} chunks"
    )
    if args.dry_run:
        print(f"[DRY RUN] {bundle_path} and {lexicon_path} are valid: {summary}. Nothing uploaded.")
        return 0

    try:
        total = upload(bundle, lexicon, args.project, args.database)
    except urllib.error.HTTPError as exc:
        print(f"[ERROR] Firestore upload failed ({exc.code}): {exc.read().decode('utf-8', 'replace')}", file=sys.stderr)
        return 1
    except RuntimeError as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1

    print(f"[OK] Uploaded to Firestore ({args.project}/{args.database}): {summary} ({total} documents).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
