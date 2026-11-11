#!/usr/bin/env python3
"""Builds `out/content_pack.json` (schema v1, doc/plans/019) from the pipeline's source files,
copies it byte-identical into the bundled compose resource, and writes the mini-pack test
fixture. See `tools/content_pipeline/pack/README.md` for the full generate -> build -> validate
-> preview -> upload flow.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Dict, List, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from tools.content_pipeline.pack.templates.avatars import body as avatar_body  # noqa: E402
from tools.content_pipeline.pack.templates.slots import SLOT_HIDES_PARTS, expand  # noqa: E402

PACK_DIR = Path(__file__).resolve().parent
CATALOG_DIR = PACK_DIR / "catalog"
STRINGS_DIR = PACK_DIR / "strings"
EVENTS_DIR = PACK_DIR / "events"
TEMPLATES_AVATARS_DIR = PACK_DIR / "templates" / "avatars"
PUBLISHED_DIR = PACK_DIR / "published"
OUT_DIR = PACK_DIR / "out"
REPO_ROOT = PACK_DIR.parents[2]

BUNDLED_RESOURCE_PATH = (
    REPO_ROOT / "composeApp" / "src" / "commonMain" / "composeResources" / "files" / "content_pack.json"
)
MINI_PACK_FIXTURE_PATH = REPO_ROOT / "tools" / "tests" / "fixtures" / "mini_pack.json"

SCHEMA_VERSION = 1

# append-only: new catalog files go at the END so adding one never shifts an existing item's
# index; unlisted files follow, sorted by filename.
EXTRA_CATALOG_FILES_ORDER = ("jul_2026.json", "geography.json")


def _round3(v: float) -> float:
    return round(float(v), 3)


def _round_layer_numbers(layer: dict) -> dict:
    out = dict(layer)
    if "fill" in out:
        fill = dict(out["fill"])
        if "shade" in fill:
            fill["shade"] = _round3(fill["shade"])
        out["fill"] = fill
    if "stroke" in out and out["stroke"] is not None:
        stroke = dict(out["stroke"])
        if "width" in stroke:
            stroke["width"] = _round3(stroke["width"])
        out["stroke"] = stroke
    if "depth" in out and out["depth"] is not None:
        out["depth"] = _round3(out["depth"])
    if "parallax" in out and out["parallax"] is not None:
        out["parallax"] = _round3(out["parallax"])
    return out


def _latest_published_version() -> int:
    best = 0
    if PUBLISHED_DIR.exists():
        for f in PUBLISHED_DIR.glob("pack_v*.json"):
            m = re.match(r"pack_v(\d+)\.json$", f.name)
            if m:
                best = max(best, int(m.group(1)))
    return best


def load_handmade_items() -> List[dict]:
    return json.loads((CATALOG_DIR / "handmade.json").read_text(encoding="utf-8"))


def load_generated_items() -> List[dict]:
    return json.loads((CATALOG_DIR / "generated.json").read_text(encoding="utf-8"))


def load_events() -> List[dict]:
    """`events/*.json`, sorted by filename; each file is a single event object."""
    return [json.loads(f.read_text(encoding="utf-8")) for f in sorted(EVENTS_DIR.glob("*.json"))]


def load_strings() -> Dict[str, str]:
    """Merges `sv.json`, `generated.sv.json`, and every other `strings/*.sv.json` (sorted),
    raising on any key collision across all of them."""
    sv = json.loads((STRINGS_DIR / "sv.json").read_text(encoding="utf-8"))
    generated_sv = json.loads((STRINGS_DIR / "generated.sv.json").read_text(encoding="utf-8"))
    extra_files = sorted(
        f for f in STRINGS_DIR.glob("*.sv.json") if f.name not in ("sv.json", "generated.sv.json")
    )
    tables: List[Tuple[str, Dict[str, str]]] = [("sv.json", sv), ("generated.sv.json", generated_sv)]
    tables += [(f.name, json.loads(f.read_text(encoding="utf-8"))) for f in extra_files]

    merged: Dict[str, str] = {}
    seen_in: Dict[str, str] = {}
    for name, table in tables:
        collisions = set(table) & set(merged)
        if collisions:
            raise ValueError(
                f"strings key collision between {seen_in.get(next(iter(collisions)))} and {name}: {sorted(collisions)}"
            )
        merged.update(table)
        for key in table:
            seen_in[key] = name
    return merged


def load_drop_rules() -> dict:
    return json.loads((PACK_DIR / "drop_rules.json").read_text(encoding="utf-8"))


def load_nickname_words() -> dict:
    """`nicknameWords` (doc/plans/030): Swedish adjective/animal word lists used to render a
    friend's fallback nickname (`adj + animal + num`), so no free text is ever stored in the
    cloud. Appending words is safe; see `validate_pack.stability_errors` for the append-only
    rule that keeps an existing index's word from ever changing."""
    return json.loads((PACK_DIR / "nickname_words.json").read_text(encoding="utf-8"))


def _expand_item_row(row: dict) -> dict:
    if "layers" in row:
        # Hand-authored rows (e.g. a holiday special's catalog/*.json) ship their own layers
        # directly instead of a template, so there is no template to derive palette.accent from:
        # it must be present explicitly, or the Kotlin decode would fail (accent is non-default)
        # and the item would be silently skipped by the validator.
        layers = [_round_layer_numbers(l) for l in row["layers"]]
        palette = dict(row["palette"])
        if "accent" not in palette:
            raise ValueError(f"item {row['id']}: hand-authored rows require palette.accent")
    else:
        template = expand(row["template"])
        layers = [_round_layer_numbers(l) for l in template.layers(row)]
        palette = dict(row["palette"])
        palette.setdefault("accent", template.accent(palette["primary"], palette["secondary"]))

    item = {
        "id": row["id"],
        "slot": row["slot"],
        "rarity": row["rarity"],
        "subjectTheme": row["subjectTheme"],
        "nameKey": f"{row['id']}.name",
        "descKey": f"{row['id']}.desc",
        "palette": palette,
        "pattern": row["pattern"],
        "ornament": row["ornament"],
        "scale": _round3(row["scale"]),
        "layers": layers,
        "pool": row["pool"],
        "retired": False,
    }
    if row.get("eventId"):
        item["eventId"] = row["eventId"]
    # row["slot"] is the EquipmentSlot Kotlin enum constant name (e.g. "HAND_RIGHT"); SLOT_HIDES_PARTS
    # and the hat check below key by EquipmentSlot.id (slot_id, e.g. "hand_right"), the lowercase form.
    slot_id = row["slot"].lower()
    hides = SLOT_HIDES_PARTS.get(slot_id)
    if hides:
        item["hidesParts"] = hides
    if slot_id == "hat":
        item["preview"] = {"ghostBody": True}
    return item


def load_extra_catalog_items() -> List[dict]:
    """Every `catalog/*.json` except `handmade.json`/`generated.json` (e.g. a holiday special's
    catalog file), concatenated in [EXTRA_CATALOG_FILES_ORDER] order (append-only: new catalog
    files are listed at the end there, so adding one never shifts an existing item's index) with
    any unlisted file following, sorted by filename."""
    rank = {name: i for i, name in enumerate(EXTRA_CATALOG_FILES_ORDER)}
    extra_files = sorted(
        (f for f in CATALOG_DIR.glob("*.json") if f.name not in ("handmade.json", "generated.json")),
        key=lambda f: (rank.get(f.name, len(rank)), f.name),
    )
    rows: List[dict] = []
    for f in extra_files:
        rows += json.loads(f.read_text(encoding="utf-8"))
    return rows


def build_items() -> List[dict]:
    # Handmade + generated first, so their indices/mini_pack selection stay unchanged; any extra
    # catalog file (e.g. a holiday special, or plan 037's geography.json) is appended after, in
    # EXTRA_CATALOG_FILES_ORDER so existing files' indices never shift.
    items = [_expand_item_row(r) for r in load_handmade_items()]
    items += [_expand_item_row(r) for r in load_generated_items()]
    items += [_expand_item_row(r) for r in load_extra_catalog_items()]
    return items


def build_avatars() -> List[dict]:
    avatars = []
    for f in sorted(TEMPLATES_AVATARS_DIR.glob("avatar_*.json")):
        raw = json.loads(f.read_text(encoding="utf-8"))
        sizes = avatar_body.expand_bones(raw.get("bones", {}))
        parts = avatar_body.assemble_parts(sizes, raw.get("extras", []))

        standard_sizes = dict(avatar_body.STANDARD_SIZES)
        standard_sizes["HAND"] = (
            standard_sizes["ARM"][0], round(standard_sizes["ARM"][1] * avatar_body.HAND_ASPECT, 2)
        )
        bones_out = {}
        for bone, (w, h) in sizes.items():
            if bone == "TAIL":
                continue
            kind = (
                "ARM" if bone in ("ARM_L", "ARM_R") else
                "LEG" if bone in ("LEG_L", "LEG_R") else
                "HAND" if bone in ("HAND_L", "HAND_R") else bone
            )
            if (w, h) != standard_sizes.get(kind):
                bones_out[bone] = {"size": [_round3(w), _round3(h)]}

        avatar = {
            "id": raw["id"],
            "nameKey": raw["nameKey"],
            "order": raw["order"],
            "humanoid": raw["humanoid"],
            "skinHex": raw["skinHex"],
            "defaultTintHex": raw["defaultTintHex"],
            "unlock": raw["unlock"],
            "face": {},
            "bones": bones_out,
            "pose": {k: _round3(v) for k, v in raw.get("pose", {}).items()},
            "parts": [
                {"part": p["part"], "layers": [_round_layer_numbers(l) for l in p["layers"]]}
                for p in parts
            ],
        }
        if raw.get("palette") is not None:
            avatar["palette"] = dict(raw["palette"])
        avatars.append(avatar)
    avatars.sort(key=lambda a: a["order"])
    return avatars


def build(version: int, published_at: str = "") -> bytes:
    """Pure: loads the sources, expands templates, and returns the canonical pack JSON bytes."""
    pack = {
        "schemaVersion": SCHEMA_VERSION,
        "version": version,
        "publishedAt": published_at,
        "strings": {"sv": load_strings()},
        "avatars": build_avatars(),
        "items": build_items(),
        "events": load_events(),
        "dropRules": load_drop_rules(),
        "nicknameWords": load_nickname_words(),
    }
    return json.dumps(pack, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def mini_pack(full: dict) -> dict:
    """All 6 avatars; the starter hat plus the first generated pool-"standard" item for every
    other slot; dropRules/strings filtered down to the items actually included."""
    items_by_slot: Dict[str, dict] = {}
    starter = next(i for i in full["items"] if i["id"] == "item_hat_starter_explorer")
    items_by_slot["hat"] = starter
    for item in full["items"]:
        slot = item["slot"]
        if slot in items_by_slot:
            continue
        if item["pool"] != "standard":
            continue
        items_by_slot[slot] = item

    included_items = list(items_by_slot.values())
    included_ids = {i["id"] for i in included_items}

    drop_rules = full["dropRules"]
    filtered_drop_rules = dict(drop_rules)
    filtered_drop_rules["starUnlocks"] = [s for s in drop_rules["starUnlocks"] if s["itemId"] in included_ids]
    filtered_drop_rules["levelRewards"] = [
        r for r in drop_rules["levelRewards"] if r["itemId"] in included_ids
    ]
    filtered_drop_rules["achievementRewards"] = {
        k: v for k, v in drop_rules["achievementRewards"].items() if v in included_ids
    }
    filtered_drop_rules["weeklyStreakSetPieceItemIds"] = [
        i for i in drop_rules["weeklyStreakSetPieceItemIds"] if i in included_ids
    ]
    filtered_drop_rules["tutorialRewardItemId"] = drop_rules["tutorialRewardItemId"]

    used_keys = set()
    for item in included_items:
        used_keys.add(item["nameKey"])
        if item.get("descKey"):
            used_keys.add(item["descKey"])
    for avatar in full["avatars"]:
        used_keys.add(avatar["nameKey"])

    strings = {
        lang: {k: v for k, v in table.items() if k in used_keys}
        for lang, table in full["strings"].items()
    }

    return {
        "schemaVersion": full["schemaVersion"],
        "version": full["version"],
        "publishedAt": full["publishedAt"],
        "strings": strings,
        "avatars": full["avatars"],
        "items": included_items,
        "events": [],
        "dropRules": filtered_drop_rules,
        "nicknameWords": full.get("nicknameWords", {"adjectives": [], "animals": []}),
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", type=int, default=None)
    parser.add_argument("--published-at", type=str, default="")
    args = parser.parse_args(argv)

    version = args.version if args.version is not None else (_latest_published_version() + 1 or 1)

    pack_bytes = build(version, args.published_at)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out_path = OUT_DIR / "content_pack.json"
    out_path.write_bytes(pack_bytes)

    BUNDLED_RESOURCE_PATH.parent.mkdir(parents=True, exist_ok=True)
    BUNDLED_RESOURCE_PATH.write_bytes(pack_bytes)

    full = json.loads(pack_bytes.decode("utf-8"))
    mini = mini_pack(full)
    MINI_PACK_FIXTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    MINI_PACK_FIXTURE_PATH.write_text(
        json.dumps(mini, sort_keys=True, separators=(",", ":"), ensure_ascii=False), encoding="utf-8"
    )

    print(f"Wrote version {version} ({len(full['items'])} items, {len(full['avatars'])} avatars) to {out_path}")
    print(f"Copied to {BUNDLED_RESOURCE_PATH}")
    print(f"Wrote mini pack fixture to {MINI_PACK_FIXTURE_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
