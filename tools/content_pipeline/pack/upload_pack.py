#!/usr/bin/env python3
"""Uploads a built, validated content pack (`doc/plans/024-cloud-content-sync.md`) to Firestore:
`content_chunks/{schema}_{version}_{i}` documents first, then the `content/meta` doc last, so a
reader never observes a meta bump without its chunks. Re-validates the pack and runs the
stability diff against the last `published/pack_v<N>.json` before touching the network.

Usage:
    python3 tools/content_pipeline/pack/upload_pack.py --validate-only  # offline-only validation
    python3 tools/content_pipeline/pack/upload_pack.py --dry-run        # show the writes, upload nothing
    python3 tools/content_pipeline/pack/upload_pack.py                  # upload (requires `firebase login`)
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from tools.content_pipeline.pack import validate_pack  # noqa: E402
from tools.content_pipeline.upload_content import (  # noqa: E402
    FIREBASE_PROJECT_ID,
    FIRESTORE_DATABASE_ID,
    _load_firebase_cli_access_token,
    _to_firestore_value,
    commit_writes,
    firestore_doc_prefix,
)

# Keeps each `content_chunks/{schema}_{version}_{i}` document comfortably under Firestore's
# 1 MiB document size limit; must match `ContentPackChunks.MAX_CHUNK_BYTES` in Kotlin.
CHUNK_MAX_BYTES = 900_000

# How many of the most recent published versions' chunk docs are kept around; older ones are pruned.
KEEP_VERSIONS = 3

SCHEMA_VERSION = 1

_PUBLISHED_FILE_REGEX = re.compile(r"pack_v(\d+)\.json$")


def split_chunks(text: str, max_bytes: int = CHUNK_MAX_BYTES) -> List[str]:
    """Splits `text` into chunks of at most `max_bytes` UTF-8 bytes each, cutting only between
    code points (never splitting a multi-byte UTF-8 sequence), matching Kotlin's
    `ContentPackChunks.split` byte-for-byte."""
    if not text:
        return [""]
    chunks: List[str] = []
    current: List[str] = []
    current_bytes = 0
    for ch in text:
        byte_len = len(ch.encode("utf-8"))
        if current_bytes + byte_len > max_bytes and current:
            chunks.append("".join(current))
            current = []
            current_bytes = 0
        current.append(ch)
        current_bytes += byte_len
    if current or not chunks:
        chunks.append("".join(current))
    return chunks


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def chunk_doc_id(schema: int, version: int, i: int) -> str:
    return f"{schema}_{version}_{i}"


def latest_published(published_dir: Path) -> Optional[Path]:
    best_n = -1
    best: Optional[Path] = None
    if published_dir.exists():
        for f in published_dir.glob("pack_v*.json"):
            m = _PUBLISHED_FILE_REGEX.match(f.name)
            if m and int(m.group(1)) > best_n:
                best_n = int(m.group(1))
                best = f
    return best


def build_pack_writes(pack_bytes: bytes, doc_prefix: str, published_at: str) -> List[Dict[str, Any]]:
    """Chunk writes first, then one `content/meta` write last with an `updateMask` limited to
    this schema's entry, so a future schema-2 app's entry is never clobbered."""
    import json

    pack = json.loads(pack_bytes.decode("utf-8"))
    schema = pack["schemaVersion"]
    version = pack["version"]
    text = pack_bytes.decode("utf-8")
    chunks = split_chunks(text)

    writes: List[Dict[str, Any]] = []
    for i, chunk in enumerate(chunks):
        writes.append(
            {
                "update": {
                    "name": f"{doc_prefix}/content_chunks/{chunk_doc_id(schema, version, i)}",
                    "fields": {"data": {"stringValue": chunk}},
                }
            }
        )

    entry = {
        "version": version,
        "chunkCount": len(chunks),
        "sha256": sha256_hex(pack_bytes),
        "bytes": len(pack_bytes),
        "publishedAt": published_at,
    }
    writes.append(
        {
            "update": {
                "name": f"{doc_prefix}/content/meta",
                "fields": {"packs": {"mapValue": {"fields": {str(schema): _to_firestore_value(entry)}}}},
            },
            "updateMask": {"fieldPaths": [f"packs.`{schema}`"]},
        }
    )
    return writes


def build_prune_writes(published_dir: Path, schema: int, doc_prefix: str, keep: int = KEEP_VERSIONS) -> List[Dict[str, Any]]:
    """Deletes chunk docs for every published version except the `keep` highest ones."""
    import json

    versions: Dict[int, Path] = {}
    if published_dir.exists():
        for f in published_dir.glob("pack_v*.json"):
            m = _PUBLISHED_FILE_REGEX.match(f.name)
            if m:
                versions[int(m.group(1))] = f

    kept = set(sorted(versions.keys(), reverse=True)[:keep])
    writes: List[Dict[str, Any]] = []
    for version, path in versions.items():
        if version in kept:
            continue
        text = path.read_text(encoding="utf-8")
        chunk_count = len(split_chunks(text))
        for i in range(chunk_count):
            writes.append({"delete": f"{doc_prefix}/content_chunks/{chunk_doc_id(schema, version, i)}"})
    return writes


def _published_at_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main(argv: List[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", default=str(validate_pack.OUT_PATH), help="Path to the built content pack JSON")
    parser.add_argument(
        "--published-dir", default=str(validate_pack.PUBLISHED_DIR), help="Directory of previously-published packs"
    )
    parser.add_argument("--project", default=FIREBASE_PROJECT_ID)
    parser.add_argument("--database", default=FIRESTORE_DATABASE_ID)
    parser.add_argument("--dry-run", action="store_true", help="Print the writes; upload nothing.")
    parser.add_argument("--validate-only", action="store_true", help="Validate + stability diff only; no writes, no network.")
    args = parser.parse_args(argv)

    pack_path = Path(args.pack)
    published_dir = Path(args.published_dir)

    if not pack_path.exists():
        print(f"{pack_path} does not exist", file=sys.stderr)
        return 1
    pack_bytes = pack_path.read_bytes()
    if len(pack_bytes) > validate_pack.MAX_BYTES:
        print(f"pack exceeds {validate_pack.MAX_BYTES} bytes", file=sys.stderr)
        return 1

    import json

    pack = json.loads(pack_bytes.decode("utf-8"))

    errors = validate_pack.validate(pack)

    old_path = latest_published(published_dir)
    if old_path is None:
        print("no published pack — stability diff skipped")
    else:
        old = json.loads(old_path.read_text(encoding="utf-8"))
        errors.extend(validate_pack.stability_errors(pack, old))

    if errors:
        for e in errors:
            print(e, file=sys.stderr)
        return 1

    if args.validate_only:
        print("OK")
        return 0

    doc_prefix = firestore_doc_prefix(args.project, args.database)
    writes = build_pack_writes(pack_bytes, doc_prefix, _published_at_now())

    if args.dry_run:
        for w in writes:
            if "delete" in w:
                print(f"delete {w['delete']}")
                continue
            name = w["update"]["name"]
            if name.endswith("/content/meta"):
                size = len(json.dumps(w).encode("utf-8"))
                print(f"{name} (~{size} bytes)")
            else:
                size = len(w["update"]["fields"]["data"]["stringValue"].encode("utf-8"))
                print(f"{name} (~{size} bytes)")
        return 0

    try:
        token = _load_firebase_cli_access_token()
        for write in writes[:-1]:
            commit_writes([write], args.project, args.database, token)
        commit_writes([writes[-1]], args.project, args.database, token)
    except Exception as exc:  # noqa: BLE001 - mirrors upload_content.main's HTTPError/RuntimeError handling
        import urllib.error

        if isinstance(exc, urllib.error.HTTPError):
            print(f"[ERROR] Firestore upload failed ({exc.code}): {exc.read().decode('utf-8', 'replace')}", file=sys.stderr)
        elif isinstance(exc, RuntimeError):
            print(f"[ERROR] {exc}", file=sys.stderr)
        else:
            raise
        return 1

    version = pack["version"]
    published_dir.mkdir(exist_ok=True, parents=True)
    (published_dir / f"pack_v{version}.json").write_bytes(pack_bytes)

    try:
        prune_writes = build_prune_writes(published_dir, pack["schemaVersion"], doc_prefix)
        if prune_writes:
            commit_writes(prune_writes, args.project, args.database, token)
    except Exception as exc:  # noqa: BLE001
        print(f"[WARN] pruning old chunk docs failed: {exc}", file=sys.stderr)

    print(f"[OK] Uploaded content pack v{version} ({len(writes) - 1} chunks + meta).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
