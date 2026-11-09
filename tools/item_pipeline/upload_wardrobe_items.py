#!/usr/bin/env python3
"""
Upload remote wardrobe items to Cloud Firestore (`lixo-fd45d`), without an app release
(doc/plans/015-remote-wardrobe-items.md).

Adding items without a release:
  1. Generate new items (`generate_wardrobe_items.py --remote-legendaries N`) or hand-edit
     tools/item_pipeline/remote_items/remote_wardrobe_items.json directly. Never remove an item --
     set `retired: true` on it instead (it stays owned/renderable for anyone who already has it,
     but drops out of every chest/onboarding/gift/star-curve pool). Bump `version`.
  2. `python3 tools/item_pipeline/upload_wardrobe_items.py --validate-only`
  3. `python3 tools/item_pipeline/upload_wardrobe_items.py --dry-run`
  4. `python3 tools/item_pipeline/upload_wardrobe_items.py` (requires `firebase login`)
  5. The app picks up the new items on its next launch (`LixoRepository.syncWardrobeFromCloud`).

Deploy the Firestore rules once, before the first upload:
  `firebase deploy --only firestore:rules`
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
from pathlib import Path
from typing import Any, Dict, List

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from tools.content_pipeline.upload_content import (  # noqa: E402
    FIREBASE_PROJECT_ID,
    FIRESTORE_DATABASE_ID,
    _load_firebase_cli_access_token,
    _to_firestore_value,
    commit_writes,
    firestore_doc_prefix,
)
from tools.item_pipeline.generate_wardrobe_items import (  # noqa: E402
    ORNAMENTS,
    PATTERNS,
    RARITIES,
    REMOTE_ITEMS_PATH,
    SLOTS,
    bundled_item_ids,
)

WARDROBE_CHUNK_SIZE = 200
ID_RE = re.compile(r"^item_[a-z0-9_]+$")
SUBJECTS = {"SWEDISH", "MATH", "ENGLISH"}
_SLOT_ENUM_NAMES = {s.enum_name for s in SLOTS}


def validate_remote_items(remote: Dict[str, Any], bundled_ids: set) -> List[str]:
    """Returns one error string per problem found, each containing the offending item's id
    (or '<item #i>' if the id itself is missing/unusable)."""
    errors: List[str] = []

    version = remote.get("version")
    if not isinstance(version, int) or isinstance(version, bool) or version < 1:
        errors.append(f"version must be an int >= 1, got {version!r}")

    items = remote.get("items")
    if not isinstance(items, list):
        errors.append(f"items must be a list, got {type(items).__name__}")
        return errors

    seen_ids: set = set()
    for i, item in enumerate(items):
        if not isinstance(item, dict):
            errors.append(f"<item #{i}>: not an object")
            continue
        raw_id = item.get("id")
        label = raw_id if isinstance(raw_id, str) and raw_id else f"<item #{i}>"

        if not isinstance(raw_id, str) or not ID_RE.match(raw_id):
            errors.append(f"{label}: id does not match {ID_RE.pattern}")
        else:
            if raw_id in bundled_ids:
                errors.append(f"{label}: clashes with bundled item")
            if raw_id in seen_ids:
                errors.append(f"{label}: duplicate id")
            seen_ids.add(raw_id)

        name_sv = item.get("nameSv")
        if not isinstance(name_sv, str) or not name_sv.strip():
            errors.append(f"{label}: nameSv must be a non-empty string")

        description_sv = item.get("descriptionSv")
        if not isinstance(description_sv, str):
            errors.append(f"{label}: descriptionSv must be a string")

        slot = item.get("slot")
        if slot not in _SLOT_ENUM_NAMES:
            errors.append(f"{label}: slot {slot!r} is not a known EquipmentSlot")

        rarity = item.get("rarity")
        if rarity not in RARITIES:
            errors.append(f"{label}: rarity {rarity!r} is not a known Rarity")

        subject = item.get("subjectTheme")
        if subject not in SUBJECTS:
            errors.append(f"{label}: subjectTheme {subject!r} is not a known Subject")

        pattern = item.get("pattern")
        if pattern not in PATTERNS:
            errors.append(f"{label}: pattern {pattern!r} is not a known ItemPattern")

        ornament = item.get("ornament")
        if ornament not in ORNAMENTS:
            errors.append(f"{label}: ornament {ornament!r} is not a known ItemOrnament")

        style_index = item.get("styleIndex")
        if isinstance(style_index, bool) or not isinstance(style_index, int) or not (0 <= style_index <= 7):
            errors.append(f"{label}: styleIndex must be an int in 0..7, got {style_index!r}")

        mesh_asset_path = item.get("meshAssetPath")
        if mesh_asset_path != "3d_sample.glb":
            errors.append(f"{label}: meshAssetPath must be '3d_sample.glb', got {mesh_asset_path!r}")

        scale_multiplier = item.get("scaleMultiplier")
        if isinstance(scale_multiplier, bool) or not isinstance(scale_multiplier, (int, float)) or not (
            0.5 <= scale_multiplier <= 2.0
        ):
            errors.append(f"{label}: scaleMultiplier must be in 0.5..2.0, got {scale_multiplier!r}")

        if item.get("unlockAchievementId") is not None:
            errors.append(f"{label}: unlockAchievementId must be null")

        if item.get("unlockStarRequirement") != 0:
            errors.append(f"{label}: unlockStarRequirement must be 0")

        if item.get("isWeeklyStreakSetPiece") is not False:
            errors.append(f"{label}: isWeeklyStreakSetPiece must be false")

        for color_field in ("primaryColorHex", "secondaryColorHex"):
            color_value = item.get(color_field)
            if isinstance(color_value, bool) or not isinstance(color_value, int) or not (
                0 <= color_value <= 0xFFFFFFFF
            ):
                errors.append(f"{label}: {color_field} must be an int in 0..0xFFFFFFFF, got {color_value!r}")

        if not isinstance(item.get("retired"), bool):
            errors.append(f"{label}: retired must be a bool")

        min_app_version_code = item.get("minAppVersionCode")
        if isinstance(min_app_version_code, bool) or not isinstance(min_app_version_code, int) or min_app_version_code < 0:
            errors.append(f"{label}: minAppVersionCode must be an int >= 0, got {min_app_version_code!r}")

    return errors


def build_writes(remote: Dict[str, Any], doc_prefix: str) -> List[Dict[str, Any]]:
    """Chunks `wardrobe/chunk_N` documents first (each item encoded as a compact JSON string, so
    GitLive's FirebaseDecoder -- which can't decode JsonElement -- never has to decode a structured
    doc, and one bad item can't fail the whole chunk's decode), then writes `wardrobe/meta` last so
    a client never observes a meta bump without its chunks."""
    items = remote.get("items", [])
    chunks = [items[i : i + WARDROBE_CHUNK_SIZE] for i in range(0, len(items), WARDROBE_CHUNK_SIZE)]
    writes: List[Dict[str, Any]] = []
    for i, chunk in enumerate(chunks):
        encoded = [json.dumps(item, ensure_ascii=False, separators=(",", ":"), sort_keys=True) for item in chunk]
        doc = {"items": encoded}
        writes.append(
            {
                "update": {
                    "name": f"{doc_prefix}/wardrobe/chunk_{i}",
                    "fields": {k: _to_firestore_value(v) for k, v in doc.items()},
                }
            }
        )
    meta = {"version": remote["version"], "chunkCount": len(chunks)}
    writes.append(
        {
            "update": {
                "name": f"{doc_prefix}/wardrobe/meta",
                "fields": {k: _to_firestore_value(v) for k, v in meta.items()},
            }
        }
    )
    return writes


def fetch_remote_version(project: str, database: str, token: str) -> int | None:
    import urllib.request

    doc_prefix = firestore_doc_prefix(project, database)
    url = f"https://firestore.googleapis.com/v1/{doc_prefix}/wardrobe/meta"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"}, method="GET")
    try:
        with urllib.request.urlopen(req) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None
        raise
    return int(body["fields"]["version"]["integerValue"])


def main(argv: List[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Upload remote wardrobe items JSON to Firestore.")
    parser.add_argument("--input", type=Path, default=REMOTE_ITEMS_PATH, help="Path to remote_wardrobe_items.json")
    parser.add_argument("--project", default=FIREBASE_PROJECT_ID)
    parser.add_argument("--database", default=FIRESTORE_DATABASE_ID)
    parser.add_argument("--validate-only", action="store_true", help="Validate offline (no network) and exit.")
    parser.add_argument("--dry-run", action="store_true", help="Validate and print counts without uploading.")
    args = parser.parse_args(argv)

    remote = json.loads(args.input.read_text(encoding="utf-8"))
    bundled_ids = bundled_item_ids()
    errors = validate_remote_items(remote, bundled_ids)
    if errors:
        for err in errors:
            print(f"[ERROR] {err}", file=sys.stderr)
        return 1

    if args.validate_only:
        print(f"[OK] {args.input}: version {remote['version']} with {len(remote['items'])} valid items.")
        return 0

    items = remote.get("items", [])
    if not items:
        print("[ERROR] items is empty; nothing to upload.", file=sys.stderr)
        return 1

    chunk_count = (len(items) + WARDROBE_CHUNK_SIZE - 1) // WARDROBE_CHUNK_SIZE
    if args.dry_run:
        print(f"[DRY RUN] {len(items)} items in {chunk_count} chunk(s), version {remote['version']}. Nothing uploaded.")
        return 0

    try:
        token = _load_firebase_cli_access_token()
        remote_version = fetch_remote_version(args.project, args.database, token)
        if remote_version is not None and remote_version >= remote["version"]:
            print(
                f"[ERROR] Remote wardrobe/meta is already at version {remote_version} >= local "
                f"version {remote['version']}. Bump 'version' in {args.input} before uploading.",
                file=sys.stderr,
            )
            return 1
        doc_prefix = firestore_doc_prefix(args.project, args.database)
        total = commit_writes(build_writes(remote, doc_prefix), args.project, args.database, token)
    except urllib.error.HTTPError as exc:
        print(f"[ERROR] Firestore upload failed ({exc.code}): {exc.read().decode('utf-8', 'replace')}", file=sys.stderr)
        return 1
    except RuntimeError as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1

    print(
        f"[OK] Uploaded to Firestore ({args.project}/{args.database}): version {remote['version']}, "
        f"{len(items)} items in {chunk_count} chunk(s) ({total} documents)."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
