#!/usr/bin/env python3
"""Generates `catalog/generated.json` and `strings/generated.sv.json` from
`tools/item_pipeline/generate_wardrobe_items.py` (reused, not re-implemented, so ids/names/
rarities stay byte-identical), and (once, with `--init-drop-rules`) the frozen `drop_rules.json`.

Run order (doc/plans/021-content-pack-authoring-pipeline.md):
  python3 tools/content_pipeline/pack/gen_catalog.py --init-drop-rules
  python3 tools/content_pipeline/pack/build_pack.py --version 1
  python3 tools/content_pipeline/pack/validate_pack.py
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Dict, List

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from tools.item_pipeline.generate_wardrobe_items import (  # noqa: E402
    ACHIEVEMENT_REWARDS,
    SLOTS,
    GeneratedItem,
    generate_catalog,
)

PACK_DIR = Path(__file__).resolve().parent
CATALOG_DIR = PACK_DIR / "catalog"
STRINGS_DIR = PACK_DIR / "strings"
DROP_RULES_PATH = PACK_DIR / "drop_rules.json"
HANDMADE_PATH = CATALOG_DIR / "handmade.json"

RARITY_WEIGHTS = {"COMMON": 40, "UNCOMMON": 28, "RARE": 18, "EPIC": 10, "LEGENDARY": 4}
PITY = {"RARE": 4, "EPIC": 10, "LEGENDARY": 20}
CHEST_COST_HALF_POINTS = 20
PROGRESS_RUN_HALF_POINTS = 2
REPLAY_RUN_HALF_POINTS = 1
ONBOARDING_LEVELS = sorted(set(range(1, 7)) | set(range(8, 21, 2)) | set(range(23, 41, 3)))
TUTORIAL_REWARD_ITEM_ID = "item_hat_starter_explorer"

# Handmade items that star-unlock (the 6 handmade rows have no unlockStarRequirement field of
# their own; everything not listed here defaults to 0, i.e. only obtainable via chests/level
# rewards/gifts).
HANDMADE_STAR_UNLOCKS: Dict[str, int] = {"item_hand_right_math_sword_1": 6}

# Handmade items whose `pool` is "achievement" or "setpiece" in handmade.json; maps the
# achievement id (doc/plans/014) to the handmade reward item id.
HANDMADE_ACHIEVEMENT_REWARDS: Dict[str, str] = {
    "ach_speed_reader_5": "item_shoulders_back_sv_cape_1",
    "ach_pronunciation_master_10": "item_armor_shirt_crystal_voice",
    "ach_math_clock_money": "item_hand_right_clock_money",
    "ach_weekly_streak_7": "item_hat_weekly_hero",
}
HANDMADE_SETPIECE_ITEM_ID = "item_hat_weekly_hero"

# Plan 037: hand-authored catalog/geography.json items, maps ach_explore_world_<n> to the reward
# item id. Appended to the frozen drop_rules.json by a textual edit, never by re-running this
# script; kept here only so _achievement_rewards() recomputes the same mapping for
# test_pack_ids_names_rarities_unchanged.
GEOGRAPHY_ACHIEVEMENT_REWARDS: Dict[str, str] = {
    "ach_explore_world_1": "item_hand_right_geo_sverigeflagga",
    "ach_explore_world_3": "item_hand_left_geo_norgeflagga",
    "ach_explore_world_6": "item_hat_geo_upptackarhatt",
    "ach_explore_world_10": "item_shoulders_back_geo_resryggsack",
    "ach_explore_world_15": "item_shoulders_back_geo_nordenmantel",
    "ach_explore_world_20": "item_hand_left_geo_kompass",
    "ach_explore_world_25": "item_armor_shirt_geo_flaggtroja",
    "ach_explore_world_30": "item_hand_right_geo_jordglob",
}


def _hex6(argb: int) -> str:
    return f"#{argb & 0xFFFFFF:06X}"


def _pool_for(item: GeneratedItem) -> str:
    if item.unlock_achievement_id is not None:
        return "achievement"
    if item.is_weekly_streak_set_piece:
        return "setpiece"
    return "standard"


def _generated_rows(catalog: Dict[str, List[GeneratedItem]]) -> List[dict]:
    rows: List[dict] = []
    for slot in SLOTS:
        for item in catalog[slot.slot_id]:
            rows.append(
                {
                    "id": item.item_id,
                    "slot": slot.enum_name,
                    "rarity": item.rarity,
                    "subjectTheme": slot.subject,
                    "template": f"{slot.slot_id}:{item.style_index}",
                    "palette": {
                        "primary": _hex6(item.primary_color_hex),
                        "secondary": _hex6(item.secondary_color_hex),
                    },
                    "pattern": item.pattern,
                    "ornament": item.ornament,
                    "scale": item.scale_multiplier,
                    "pool": _pool_for(item),
                }
            )
    return rows


def write_generated(catalog: Dict[str, List[GeneratedItem]]) -> None:
    CATALOG_DIR.mkdir(parents=True, exist_ok=True)
    STRINGS_DIR.mkdir(parents=True, exist_ok=True)

    rows = _generated_rows(catalog)
    (CATALOG_DIR / "generated.json").write_text(
        json.dumps(rows, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    strings: Dict[str, str] = {}
    for slot in SLOTS:
        for item in catalog[slot.slot_id]:
            strings[f"{item.item_id}.name"] = item.name_sv
            strings[f"{item.item_id}.desc"] = item.description_sv
    (STRINGS_DIR / "generated.sv.json").write_text(
        json.dumps(strings, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )


def _load_handmade() -> List[dict]:
    return json.loads(HANDMADE_PATH.read_text(encoding="utf-8"))


def _handmade_eligibility(row: dict) -> dict:
    """Derives the non-schema `unlockStarRequirement`/`unlockAchievementId`/
    `isWeeklyStreakSetPiece` facts for a handmade.json row from its `pool`."""
    item_id = row["id"]
    pool = row["pool"]
    return {
        "id": item_id,
        # row["slot"] is the EquipmentSlot Kotlin enum constant name (e.g. "HAND_RIGHT"); the
        # eligibility rows below key by `EquipmentSlot.id` (slot_id, e.g. "hand_right"), which is
        # always the lowercase form of the enum constant name for every slot in SLOTS.
        "slot": row["slot"].lower(),
        "rarity": row["rarity"],
        "unlockStarRequirement": HANDMADE_STAR_UNLOCKS.get(item_id, 0),
        "unlockAchievementId": next(
            (aid for aid, iid in HANDMADE_ACHIEVEMENT_REWARDS.items() if iid == item_id), None
        ),
        "isWeeklyStreakSetPiece": item_id == HANDMADE_SETPIECE_ITEM_ID,
        "pool": pool,
    }


def _round_robin_by_slot(items: List[dict]) -> List[dict]:
    slot_order = [s.slot_id for s in SLOTS]
    by_slot: Dict[str, List[dict]] = {s: [] for s in slot_order}
    for item in items:
        by_slot[item["slot"]].append(item)
    max_len = max((len(v) for v in by_slot.values()), default=0)
    out: List[dict] = []
    for i in range(max_len):
        for slot_id in slot_order:
            lst = by_slot[slot_id]
            if i < len(lst):
                out.append(lst[i])
    return out


def _generated_eligibility_rows(catalog: Dict[str, List[GeneratedItem]]) -> List[dict]:
    return [
        {
            "id": item.item_id,
            "slot": slot.slot_id,
            "rarity": item.rarity,
            "unlockStarRequirement": item.unlock_star_requirement,
            "unlockAchievementId": item.unlock_achievement_id,
            "isWeeklyStreakSetPiece": item.is_weekly_streak_set_piece,
        }
        for slot in SLOTS
        for item in catalog[slot.slot_id]
    ]


def replay_level_rewards(catalog: Dict[str, List[GeneratedItem]], max_order: int = 160) -> List[dict]:
    """Ports `LixoRepository.buildLevelRewardPool` + `levelRewardItemIdFor` over the catalog in
    `buildSeedWardrobeCatalog` order (handmade rows in file order, then generated, SLOTS order)."""
    handmade_rows = [_handmade_eligibility(r) for r in _load_handmade()]
    generated_rows = _generated_eligibility_rows(catalog)
    all_rows = handmade_rows + generated_rows

    eligible = [
        r
        for r in all_rows
        if not r.get("isWeeklyStreakSetPiece")
        and r.get("unlockAchievementId") is None
        and r["id"] != TUTORIAL_REWARD_ITEM_ID
    ]
    preferred = [
        r
        for r in eligible
        if r["rarity"] in ("COMMON", "UNCOMMON") and r.get("unlockStarRequirement", 0) == 0
    ]
    rest = [r for r in eligible if r not in preferred]

    pool = _round_robin_by_slot(preferred) + _round_robin_by_slot(rest)
    if not pool:
        return []
    return [
        {"level": level, "itemId": pool[(level - 1) % len(pool)]["id"]}
        for level in range(1, max_order + 1)
    ]


def _star_unlocks(catalog: Dict[str, List[GeneratedItem]]) -> List[dict]:
    entries = [
        {"stars": item.unlock_star_requirement, "itemId": item.item_id}
        for slot in SLOTS
        for item in catalog[slot.slot_id]
        if item.unlock_star_requirement > 0
    ]
    for item_id, stars in HANDMADE_STAR_UNLOCKS.items():
        entries.append({"stars": stars, "itemId": item_id})
    entries.sort(key=lambda e: (e["stars"], e["itemId"]))
    return entries


def _achievement_rewards(catalog: Dict[str, List[GeneratedItem]]) -> Dict[str, str]:
    rewards: Dict[str, str] = dict(HANDMADE_ACHIEVEMENT_REWARDS)
    for achievement_id, _rarity in ACHIEVEMENT_REWARDS:
        for slot in SLOTS:
            found = next(
                (item for item in catalog[slot.slot_id] if item.unlock_achievement_id == achievement_id),
                None,
            )
            if found is not None:
                rewards[achievement_id] = found.item_id
                break
    rewards.update(GEOGRAPHY_ACHIEVEMENT_REWARDS)
    return rewards


def _weekly_streak_set_piece_ids(catalog: Dict[str, List[GeneratedItem]]) -> List[str]:
    ids = [HANDMADE_SETPIECE_ITEM_ID]
    for slot in SLOTS:
        for item in catalog[slot.slot_id]:
            if item.is_weekly_streak_set_piece:
                ids.append(item.item_id)
    return ids


def init_drop_rules(catalog: Dict[str, List[GeneratedItem]]) -> None:
    if DROP_RULES_PATH.exists():
        print(f"{DROP_RULES_PATH} already exists -- frozen, refusing to overwrite", file=sys.stderr)
        sys.exit(1)

    drop_rules = {
        "rarityWeights": RARITY_WEIGHTS,
        "chestCostHalfPoints": CHEST_COST_HALF_POINTS,
        "progressRunHalfPoints": PROGRESS_RUN_HALF_POINTS,
        "replayRunHalfPoints": REPLAY_RUN_HALF_POINTS,
        "pity": PITY,
        "onboarding": {"levels": ONBOARDING_LEVELS},
        "starUnlocks": _star_unlocks(catalog),
        "levelRewards": replay_level_rewards(catalog),
        "achievementRewards": _achievement_rewards(catalog),
        "tutorialRewardItemId": TUTORIAL_REWARD_ITEM_ID,
        "weeklyStreakSetPieceItemIds": _weekly_streak_set_piece_ids(catalog),
    }
    DROP_RULES_PATH.write_text(json.dumps(drop_rules, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {DROP_RULES_PATH}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--per-slot", type=int, default=64)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--init-drop-rules", action="store_true")
    args = parser.parse_args(argv)

    catalog = generate_catalog(per_slot=args.per_slot, seed=args.seed)
    write_generated(catalog)
    total = sum(len(v) for v in catalog.values())
    print(f"Wrote {total} generated items to {CATALOG_DIR / 'generated.json'}")

    if args.init_drop_rules:
        init_drop_rules(catalog)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
