#!/usr/bin/env python3
"""Item/gear catalog source for the content pack pipeline.

Deterministic (given the same --seed), stdlib-only generator for the "wardrobe item variety"
feature. Builds `--per-slot` items for each of the 9 EquipmentSlot values, with:
  - 8 procedurally-drawable styles per slot (styleIndex 0..7, order matters -- the renderer
    switches on `styleIndex % 8`).
  - ~16 named regular colorways + a handful of special legendary-only colorways.
  - Rarity split per slot of roughly 40/25/17/12/6% (COMMON..LEGENDARY), at least 1 of each.
  - Visual ladder by rarity: COMMON is plain (no pattern/ornament); UNCOMMON gets a TRIM outline
    plus a 50% chance of a stripes/dots pattern; RARE gets a pattern plus a GEM; EPIC gets a
    pattern, a GLOW halo and a gold/silver secondary accent colour (chosen to contrast with the
    item's own primary colour); LEGENDARY uses a legendary-only colorway, a pattern, a SPARKLE
    twinkle and a 1.15 scale multiplier (the renderer additionally always draws the GLOW halo
    behind every LEGENDARY item, see Avatar3DController). The gold/silver EPIC accent and any
    other purely-visual choice are drawn from a separate per-item RNG seeded
    `f"{seed}-visual-{item_id}"`, never from the main per-slot `rng`, so they can never shift the
    ids/names/rarities produced by the main rng stream (see `_rarity_visuals`).
  - No duplicate (styleIndex, colorway) within a slot.
  - A small subset of COMMON/UNCOMMON items per slot (STAR_UNLOCKABLE_COMMON_CAP/
    STAR_UNLOCKABLE_UNCOMMON_CAP) is star-unlockable, assigned one global, unique, widening-gap
    threshold curve from ~31 to 250 stars (see _global_star_unlock_thresholds), interleaved
    round-robin across slots with all COMMON items unlocking before any UNCOMMON item.
  - 44 achievement reward items (see ACHIEVEMENT_REWARDS) are reserved for the plan-014 achievement
    ladders (`unlockAchievementId = "ach_<chain>_<target>"`), round-robin across slots starting at
    SLOTS[i % len(SLOTS)] (see doc/plans/014; the 8 original star-collection ids keep SLOTS[0..7]).

Item ids are content-derived and stable: `item_<slot_id>_<styleKey>_<colorwayKey>`, where each key
is `slug_key()` of the Swedish display string (lowercase, å/ä/ö -> a/a/o, all non-[a-z0-9] removed),
e.g. "Krona" + "Gyllene" -> `item_hat_krona_gyllene`. meshNodeName uses the same keys
(`<socket_name>_<styleKey>_<colorwayKey>`).
Ids are persisted in user inventories/Firestore -- never rename existing colorway/style keys
(i.e. their Swedish display strings in SLOTS/COLORWAYS/LEGENDARY_COLORWAYS); only append.

Running this module directly prints per-slot rarity/style counts.

This is item/gear data, not curriculum content -- it is unrelated to
tools/content_pipeline/generate_content.py and seed_campaign_content.json, which this script
never touches.

This module no longer emits Kotlin (022): `generate_catalog()` is the item source for
`tools/content_pipeline/pack/gen_catalog.py`, which builds the bundled content pack. Importable
symbols other modules rely on: GeneratedItem, generate_catalog, SLOTS, ACHIEVEMENT_REWARDS,
HANDMADE_ITEM_IDS, bundled_item_ids.
"""
from __future__ import annotations

import argparse
import json
import random
import re
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[2]

RARITIES: List[str] = ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY"]
RARITY_SPLIT: Dict[str, float] = {
    "COMMON": 0.40,
    "UNCOMMON": 0.25,
    "RARE": 0.17,
    "EPIC": 0.12,
    "LEGENDARY": 0.06,
}

PATTERNS: List[str] = ["NONE", "STRIPES", "DOTS", "STARS", "ZIGZAG", "CHECKER"]
NON_NONE_PATTERNS: List[str] = [p for p in PATTERNS if p != "NONE"]
ORNAMENTS: List[str] = ["NONE", "TRIM", "GEM", "GLOW", "SPARKLE"]

# Plan 011 section 3: the rarity -> ornament ladder, and the EPIC metal-accent colours.
RARITY_ORNAMENT: Dict[str, str] = {
    "COMMON": "NONE",
    "UNCOMMON": "TRIM",
    "RARE": "GEM",
    "EPIC": "GLOW",
    "LEGENDARY": "SPARKLE",
}
EPIC_ACCENT_GOLD = 0xFFF4D03F
EPIC_ACCENT_SILVER = 0xFFD5DBDB
# Colorways whose own primary colour is already gold/silver-ish; pick the opposite metal so the
# EPIC accent always contrasts with the item's primary colour.
_EPIC_SILVER_ACCENT_COLORWAYS = {"Solgyllene", "Bärnstensgul"}
_EPIC_GOLD_ACCENT_COLORWAYS = {"Silverglans", "Vintervit"}


@dataclass(frozen=True)
class SlotDef:
    slot_id: str  # EquipmentSlot.id
    enum_name: str  # EquipmentSlot Kotlin enum constant name
    socket_name: str  # EquipmentSlot.socketName
    subject: str  # Subject Kotlin enum constant name (subjectTheme)
    styles: Tuple[str, ...]  # 8 Swedish nouns, in styleIndex order


# Subject theme per slot mirrors the previous handwritten `slotTemplates` in Repositories.kt.
SLOTS: List[SlotDef] = [
    SlotDef(
        "hat", "HAT", "socket_hat", "ENGLISH",
        ("Trollhatt", "Krona", "Riddarhjälm", "Pompomsmössa", "Hög Hatt", "Keps", "Huva", "Hornhjälm"),
    ),
    SlotDef(
        "shoulders_back", "SHOULDERS_BACK", "socket_shoulders_back", "SWEDISH",
        ("Cape", "Vingar", "Ryggsäck", "Halsduk", "Fjädermantel", "Snäckskal", "Pilkoger", "Ballong"),
    ),
    SlotDef(
        "arms_gloves", "ARMS_GLOVES", "socket_arms_gloves", "MATH",
        ("Handskar", "Pansarhandskar", "Armband", "Vantar", "Klor", "Handledslindor", "Ringar", "Taggarmskydd"),
    ),
    SlotDef(
        "armor_shirt", "ARMOR_SHIRT", "socket_armor_shirt", "SWEDISH",
        ("Tröja", "Plattrustning", "Kåpa", "Tunika med Bälte", "Väst", "Luvtröja", "Klänningsöverdel", "Ringbrynja"),
    ),
    SlotDef(
        "pants", "PANTS", "socket_pants", "SWEDISH",
        ("Byxor", "Shorts", "Benskenor", "Kjol", "Randiga Leggings", "Cargobyxor", "Kilt", "Pösiga Byxor"),
    ),
    SlotDef(
        "shoes", "SHOES", "socket_shoes", "SWEDISH",
        ("Stövlar", "Sneakers", "Tofflor", "Höga Stövlar", "Sandaler", "Raketskor", "Träskor", "Tåspetsskor"),
    ),
    SlotDef(
        "hand_right", "HAND_RIGHT", "socket_hand_right", "MATH",
        ("Svärd", "Trollstav", "Stav", "Yxa", "Hammare", "Spjut", "Pilbåge", "Magisk Sfär"),
    ),
    SlotDef(
        "hand_left", "HAND_LEFT", "socket_hand_left", "MATH",
        ("Rund Sköld", "Kitesköld", "Bok", "Lykta", "Kompass", "Timglas", "Fackla", "Trolldryck"),
    ),
    SlotDef(
        "tail", "TAIL", "socket_tail", "SWEDISH",
        ("Kattsvans", "Hundsvans", "Rävsvans", "Drakssvans", "Ödlesvans", "Regnbågssvans", "Komet-svans", "Sjöjungfrufena"),
    ),
]

# name -> (primary ARGB, secondary ARGB)
COLORWAYS: Dict[str, Tuple[int, int]] = {
    "Glödande": (0xFFE74C3C, 0xFFFFC300),
    "Isblå": (0xFF5DADE2, 0xFFEAF2F8),
    "Skogsgrön": (0xFF27AE60, 0xFF1E8449),
    "Nattsvart": (0xFF212121, 0xFF616161),
    "Solgyllene": (0xFFF1C40F, 0xFFF39C12),
    "Kungablå": (0xFF2E4A9E, 0xFFC0C0C0),
    "Korallröd": (0xFFFF6F61, 0xFFFFD1CC),
    "Lavendellila": (0xFF9B59B6, 0xFFD2B4DE),
    "Myllergrön": (0xFF6AB04C, 0xFFBADC58),
    "Rostbrun": (0xFF935116, 0xFFBA6E2A),
    "Silverglans": (0xFFBDC3C7, 0xFF7F8C8D),
    "Korpsvart": (0xFF17202A, 0xFF4A235A),
    "Rosenrosa": (0xFFF78FB3, 0xFFFC427B),
    "Bärnstensgul": (0xFFE67E22, 0xFFF5CBA7),
    "Djuphavsblå": (0xFF154360, 0xFF1B4F72),
    "Vintervit": (0xFFFDFEFE, 0xFFAED6F1),
}

# Legendary-only special colorways.
LEGENDARY_COLORWAYS: Dict[str, Tuple[int, int]] = {
    "Gyllene": (0xFFFFD700, 0xFFFFF8DC),
    "Regnbågs": (0xFFFF0080, 0xFF00FFD0),
    "Galax": (0xFF2C0A5E, 0xFF8E44AD),
    "Kosmisk": (0xFF0B0033, 0xFF00E0FF),
}

ALL_COLORWAY_NAMES = list(COLORWAYS.keys())
ALL_LEGENDARY_COLORWAY_NAMES = list(LEGENDARY_COLORWAYS.keys())

# Handmade wardrobe items in LixoRepository.buildSeedWardrobeCatalog (Repositories.kt) that are
# not produced by generate_catalog() below. Must be kept in sync with that function -- see
# tools/tests/test_pipelines.py:test_handmade_ids_match_repositories_kt.
HANDMADE_ITEM_IDS = frozenset(
    {
        "item_hat_starter_explorer",
        "item_hand_right_math_sword_1",
        "item_shoulders_back_sv_cape_1",
        "item_hat_weekly_hero",
        "item_armor_shirt_crystal_voice",
        "item_hand_right_clock_money",
    }
)

_TRANSLITERATION = str.maketrans({"å": "a", "ä": "a", "ö": "o", "é": "e", "ü": "u"})


def slug_key(display: str) -> str:
    """ASCII id key from a Swedish display string: lowercase, å/ä/ö -> a/a/o, drop every
    non-[a-z0-9] char (spaces/hyphens removed, no separator). E.g. "Hög Hatt" -> "hoghatt".

    This key is persisted as part of wardrobe item ids (item_<slot>_<styleKey>_<colorwayKey>) in
    user inventories/Firestore -- never rename an existing colorway/style display string, only
    append new ones.
    """
    key = re.sub(r"[^a-z0-9]", "", display.lower().translate(_TRANSLITERATION))
    if not key:
        raise ValueError(f"Empty id key for {display!r}")
    return key


def item_identity(slot: "SlotDef", style_index: int, colorway_name: str) -> Tuple[str, str, str, str]:
    """Returns (item_id, name_sv, description_sv, mesh_node_name) for a (slot, style, colorway)
    combo, using the exact formulas generate_items_for_slot has always used. Output must stay
    byte-identical for the existing bundled catalog (test_ids_names_rarities_unchanged)."""
    style_name = slot.styles[style_index]
    style_key = slug_key(style_name)
    colorway_key = slug_key(colorway_name)
    item_id = f"item_{slot.slot_id}_{style_key}_{colorway_key}"
    name_sv = f"{colorway_name} {style_name}"
    description_sv = f"En {style_name.lower()} i {colorway_name.lower()} nyans, upplåsbar för alla avatarer i Lixo."
    mesh_node_name = f"{slot.socket_name}_{style_key}_{colorway_key}"
    return item_id, name_sv, description_sv, mesh_node_name


def _assert_unique_keys() -> None:
    for _slot in SLOTS:
        _style_keys = [slug_key(s) for s in _slot.styles]
        assert len(set(_style_keys)) == len(_style_keys), f"Duplicate style key in slot {_slot.slot_id}"
    _colorway_keys = [slug_key(c) for c in list(COLORWAYS) + list(LEGENDARY_COLORWAYS)]
    assert len(set(_colorway_keys)) == len(_colorway_keys), "Duplicate colorway key"


_assert_unique_keys()


@dataclass
class GeneratedItem:
    item_id: str
    name_sv: str
    description_sv: str
    slot: SlotDef
    rarity: str
    style_index: int
    style_name: str
    colorway_name: str
    primary_color_hex: int
    secondary_color_hex: int
    pattern: str
    ornament: str
    scale_multiplier: float
    unlock_star_requirement: int
    unlock_achievement_id: Optional[str]
    is_weekly_streak_set_piece: bool
    mesh_node_name: str
    is_star_unlockable: bool = False


def _split_counts(total: int) -> Dict[str, int]:
    """Splits `total` across RARITIES by RARITY_SPLIT, each rarity getting at least 1."""
    if total < len(RARITIES):
        raise ValueError(f"per-slot must be >= {len(RARITIES)} to cover every rarity, got {total}")
    raw = {r: total * RARITY_SPLIT[r] for r in RARITIES}
    floored = {r: max(1, int(raw[r])) for r in RARITIES}
    remainder = total - sum(floored.values())
    # Distribute remainder (can be negative if flooring+min bumped us over) by fractional part.
    order = sorted(RARITIES, key=lambda r: raw[r] - int(raw[r]), reverse=True)
    i = 0
    while remainder > 0:
        r = order[i % len(order)]
        floored[r] += 1
        remainder -= 1
        i += 1
    while remainder < 0:
        # Take from the largest bucket that can afford to lose one and stay >= 1.
        r = max(floored, key=lambda k: floored[k])
        if floored[r] > 1:
            floored[r] -= 1
            remainder += 1
        else:
            break
    assert sum(floored.values()) == total, (floored, total)
    return floored


def _rarity_pattern_ornament(rarity: str, rng: random.Random) -> Tuple[str, str]:
    """legacy; only called to keep the main rng stream stable, its ornament is discarded"""
    if rarity == "COMMON":
        return "NONE", rng.choice(["NONE", "NONE", "NONE", "TRIM"])
    if rarity == "UNCOMMON":
        pattern = rng.choice(["NONE", "NONE", "STRIPES", "DOTS"])
        return pattern, "TRIM"
    if rarity == "RARE":
        return rng.choice(NON_NONE_PATTERNS), "TRIM"
    if rarity == "EPIC":
        return rng.choice(NON_NONE_PATTERNS), "GEM"
    # LEGENDARY
    pattern = rng.choice(NON_NONE_PATTERNS)
    ornament = rng.choice(["GLOW", "SPARKLE"])
    return pattern, ornament


def _rarity_visuals(
    rarity: str, legacy_pattern: str, colorway_name: str, visual_rng: random.Random
) -> Tuple[str, str, Optional[int]]:
    """Plan 011 section 3 visual ladder. Reuses `legacy_pattern` (from `_rarity_pattern_ornament`,
    which already follows the ladder exactly) instead of re-rolling patterns, so this never
    touches the main per-slot rng and never changes the (styleIndex, primaryColorHex, pattern)
    uniqueness the catalog relies on. Only the EPIC gold/silver secondary accent is a new random
    choice, and it is drawn from `visual_rng` (a per-item rng), never from the main rng.

    Returns (pattern, ornament, secondary_color_override). secondary_color_override is only set
    for EPIC (gold/silver accent); every other rarity returns None and keeps its colorway's own
    secondary colour.
    """
    ornament = RARITY_ORNAMENT[rarity]
    pattern = "NONE" if rarity == "COMMON" else legacy_pattern
    if rarity != "EPIC":
        return pattern, ornament, None
    if colorway_name in _EPIC_SILVER_ACCENT_COLORWAYS:
        secondary = EPIC_ACCENT_SILVER
    elif colorway_name in _EPIC_GOLD_ACCENT_COLORWAYS:
        secondary = EPIC_ACCENT_GOLD
    else:
        secondary = visual_rng.choice([EPIC_ACCENT_GOLD, EPIC_ACCENT_SILVER])
    return pattern, ornament, secondary


# How many COMMON/UNCOMMON items per slot are star-unlockable at all (the rest -- all
# RARE/EPIC/LEGENDARY, plus any extra COMMON/UNCOMMON beyond these caps -- get
# unlockStarRequirement = 0, i.e. only obtainable via level rewards, chests, or gifts).
# Without this cap, every generated item star-unlocks once totalStars is high enough, which
# auto-unlocks the whole 576-item catalog and makes gift/chest rolls always hit duplicates.
# With --per-slot 64 (default), this gives 6+2=8 star-unlockable items per slot x 9 slots = 72
# items total, assigned one global, widening-gap threshold curve from ~31 to 250 stars (see
# `_global_star_unlock_thresholds` / `_assign_global_star_thresholds`), interleaved round-robin
# across slots (COMMON first, then UNCOMMON) so consecutive unlocks never land on the same slot
# and rarer items unlock later.
STAR_UNLOCKABLE_COMMON_CAP = 6
STAR_UNLOCKABLE_UNCOMMON_CAP = 2

# The onboarding reward track (the content pack's dropRules.onboarding.levels) covers early
# levels, so the star curve only needs to start once that track has mostly run its course (`doc/plans/
# 001-reward-pacing-early-chests.md`). It starts just after the KNIGHT avatar unlock at 30 stars,
# so the two moments never coincide.
STAR_CURVE_FIRST = 31
STAR_CURVE_LAST = 250
STAR_CURVE_MIN_GAP = 2.0


def _global_star_unlock_thresholds(count: int) -> List[int]:
    """One global, strictly ascending sequence of `count` star-unlock thresholds from
    STAR_CURVE_FIRST to STAR_CURVE_LAST, with gaps growing linearly from STAR_CURVE_MIN_GAP."""
    if count <= 0:
        return []
    if count == 1:
        return [STAR_CURVE_FIRST]

    m = count - 1
    span = STAR_CURVE_LAST - STAR_CURVE_FIRST
    a = STAR_CURVE_MIN_GAP
    b = max(0.0, 2 * (span - a * m) / (m * m))
    if b <= 0:
        a = span / m
        b = 0.0

    thresholds = [round(STAR_CURVE_FIRST + a * k + b * k * k / 2) for k in range(count)]
    for i in range(1, len(thresholds)):
        assert thresholds[i] > thresholds[i - 1], (
            f"Star curve thresholds must be strictly ascending, got {thresholds}"
        )
    return thresholds


def generate_items_for_slot(
    slot: SlotDef, per_slot: int, rng: random.Random, seed: int = 42
) -> List[GeneratedItem]:
    rarity_counts = _split_counts(per_slot)
    rarity_sequence: List[str] = []
    for r in RARITIES:
        rarity_sequence.extend([r] * rarity_counts[r])
    rng.shuffle(rarity_sequence)

    # Spread styles as evenly as possible across the slot's items.
    style_sequence: List[int] = []
    while len(style_sequence) < per_slot:
        block = list(range(len(slot.styles)))
        rng.shuffle(block)
        style_sequence.extend(block)
    style_sequence = style_sequence[:per_slot]

    # Only a small subset of COMMON/UNCOMMON items are star-unlockable; see
    # STAR_UNLOCKABLE_COMMON_CAP/STAR_UNLOCKABLE_UNCOMMON_CAP. Thresholds themselves are assigned
    # later, across the whole catalog, by `_assign_global_star_thresholds`.
    common_unlockable_cap = min(STAR_UNLOCKABLE_COMMON_CAP, rarity_counts["COMMON"])
    uncommon_unlockable_cap = min(STAR_UNLOCKABLE_UNCOMMON_CAP, rarity_counts["UNCOMMON"])

    # Dedupe on (style, colorway) only (not pattern) -- nameSv is derived purely from colorway +
    # style noun, so deduping on the full (style, colorway, pattern) combo previously let two
    # items with the same style/colorway but different patterns collide on name.
    used_combos: set = set()
    items: List[GeneratedItem] = []
    bucket_position = Counter()
    legendary_set_piece_assigned = False

    for n in range(per_slot):
        rarity = rarity_sequence[n]
        style_index = style_sequence[n]
        # keep: consumes main rng so ids/names/rarities stay stable (plan 011 section 3)
        legacy_pattern, _legacy_ornament = _rarity_pattern_ornament(rarity, rng)

        colorway_pool = ALL_LEGENDARY_COLORWAY_NAMES if rarity == "LEGENDARY" else ALL_COLORWAY_NAMES
        colorway_table = LEGENDARY_COLORWAYS if rarity == "LEGENDARY" else COLORWAYS

        combo: Optional[Tuple[int, str]] = None
        # The combination space (8 styles x 16+ colorways) is far larger than per_slot needs
        # per rarity pool, so this always terminates quickly.
        shuffled_colorways = colorway_pool[:]
        rng.shuffle(shuffled_colorways)
        for colorway_name in shuffled_colorways:
            key = (style_index, colorway_name)
            if key not in used_combos:
                combo = key
                break
        if combo is None:
            raise RuntimeError(f"Could not find a free (style, colorway) combo for slot {slot.slot_id}")
        used_combos.add(combo)
        colorway_name = combo[1]
        primary_hex, secondary_hex = colorway_table[colorway_name]

        scale_multiplier = 1.15 if rarity == "LEGENDARY" else 1.0
        position_in_bucket = bucket_position[rarity]
        bucket_position[rarity] += 1

        is_star_unlockable = (
            (rarity == "COMMON" and position_in_bucket < common_unlockable_cap)
            or (rarity == "UNCOMMON" and position_in_bucket < uncommon_unlockable_cap)
        )
        unlock_star_requirement = 0

        is_weekly_streak_set_piece = False
        if rarity == "LEGENDARY" and not legendary_set_piece_assigned:
            is_weekly_streak_set_piece = True
            legendary_set_piece_assigned = True

        item_id, name_sv, description_sv, mesh_node_name = item_identity(slot, style_index, colorway_name)

        # Per-item rng for purely-visual choices (e.g. the EPIC gold/silver accent) -- seeded from
        # a string so it is deterministic across runs regardless of PYTHONHASHSEED, and never
        # shares draws with the main per-slot `rng` so ids/names/rarities never shift.
        visual_rng = random.Random(f"{seed}-visual-{item_id}")
        pattern, ornament, epic_accent = _rarity_visuals(rarity, legacy_pattern, colorway_name, visual_rng)
        if epic_accent is not None:
            secondary_hex = epic_accent

        items.append(
            GeneratedItem(
                item_id=item_id,
                name_sv=name_sv,
                description_sv=description_sv,
                slot=slot,
                rarity=rarity,
                style_index=style_index,
                style_name=slot.styles[style_index],
                colorway_name=colorway_name,
                primary_color_hex=primary_hex,
                secondary_color_hex=secondary_hex,
                pattern=pattern,
                ornament=ornament,
                scale_multiplier=scale_multiplier,
                unlock_star_requirement=unlock_star_requirement,
                # unlock_achievement_id defaults to None here; `_assign_achievement_items`
                # (post-pass, like `_assign_global_star_thresholds`) reserves exactly 44 of these
                # items -- one per ACHIEVEMENT_REWARDS entry -- for the plan-014 achievement
                # ladders (doc/plans/014). The other 4 achievement ids are handwritten and
                # already tied to a specific handmade reward item.
                unlock_achievement_id=None,
                is_weekly_streak_set_piece=is_weekly_streak_set_piece,
                mesh_node_name=mesh_node_name,
                is_star_unlockable=is_star_unlockable,
            )
        )

    return items


def _assign_global_star_thresholds(catalog: Dict[str, List[GeneratedItem]]) -> None:
    """Assigns one global, widening-gap star-threshold curve (STAR_CURVE_FIRST..STAR_CURVE_LAST)
    across every star-unlockable item in the catalog. Items are ordered round-robin across slots
    (in SLOTS order) so consecutive unlocks are always different slots, with all COMMON items
    assigned first and all UNCOMMON items last, so rarer items unlock later."""
    common_by_slot = [
        [item for item in catalog[slot.slot_id] if item.is_star_unlockable and item.rarity == "COMMON"]
        for slot in SLOTS
    ]
    uncommon_by_slot = [
        [item for item in catalog[slot.slot_id] if item.is_star_unlockable and item.rarity == "UNCOMMON"]
        for slot in SLOTS
    ]

    ordered: List[GeneratedItem] = []
    for bucket in (common_by_slot, uncommon_by_slot):
        max_len = max((len(lst) for lst in bucket), default=0)
        for j in range(max_len):
            for lst in bucket:
                if j < len(lst):
                    ordered.append(lst[j])

    thresholds = _global_star_unlock_thresholds(len(ordered))
    for item, threshold in zip(ordered, thresholds):
        item.unlock_star_requirement = threshold


# The 44 non-handmade achievement ladder tiers (doc/plans/014-achievement-ladders.md), in ladder
# order, each reserving one generated catalog item. Must match AchievementLadders.kt's
# ACHIEVEMENT_LADDERS minus HANDMADE_ACHIEVEMENT_IDS (ach_speed_reader_5, ach_pronunciation_master_10,
# ach_math_clock_money, ach_weekly_streak_7 are handmade and excluded here). The first 8 entries are
# the pre-existing ach_stars_20..480 rewards and must stay in this exact order/rarity so their items
# stay byte-identical.
ACHIEVEMENT_REWARDS: List[Tuple[str, str]] = [
    ("ach_stars_20", "RARE"),
    ("ach_stars_45", "RARE"),
    ("ach_stars_75", "RARE"),
    ("ach_stars_120", "EPIC"),
    ("ach_stars_175", "EPIC"),
    ("ach_stars_250", "EPIC"),
    ("ach_stars_350", "LEGENDARY"),
    ("ach_stars_480", "LEGENDARY"),
    ("ach_read_story_1", "COMMON"),
    ("ach_read_story_3", "UNCOMMON"),
    ("ach_read_story_8", "RARE"),
    ("ach_read_story_20", "EPIC"),
    ("ach_read_story_50", "LEGENDARY"),
    ("ach_solve_math_1", "COMMON"),
    ("ach_solve_math_3", "UNCOMMON"),
    ("ach_solve_math_8", "RARE"),
    ("ach_solve_math_20", "EPIC"),
    ("ach_solve_math_50", "LEGENDARY"),
    ("ach_stars_5", "COMMON"),
    ("ach_stars_10", "UNCOMMON"),
    ("ach_perfect_level_1", "COMMON"),
    ("ach_perfect_level_5", "UNCOMMON"),
    ("ach_perfect_level_15", "RARE"),
    ("ach_perfect_level_40", "EPIC"),
    ("ach_perfect_level_80", "LEGENDARY"),
    ("ach_first_try_10", "COMMON"),
    ("ach_first_try_30", "UNCOMMON"),
    ("ach_first_try_75", "RARE"),
    ("ach_first_try_150", "EPIC"),
    ("ach_first_try_300", "LEGENDARY"),
    ("ach_streak_day_2", "COMMON"),
    ("ach_streak_day_3", "UNCOMMON"),
    ("ach_streak_day_5", "RARE"),
    ("ach_streak_day_14", "LEGENDARY"),
    ("ach_streak_day_30", "LEGENDARY"),
    ("ach_clock_money_3", "COMMON"),
    ("ach_clock_money_8", "UNCOMMON"),
    ("ach_clock_money_40", "LEGENDARY"),
    ("ach_read_fast_1", "UNCOMMON"),
    ("ach_read_fast_3", "RARE"),
    ("ach_read_fast_12", "LEGENDARY"),
    ("ach_read_aloud_1", "UNCOMMON"),
    ("ach_read_aloud_4", "RARE"),
    ("ach_read_aloud_20", "LEGENDARY"),
]


def _assign_achievement_items(catalog: Dict[str, List[GeneratedItem]]) -> None:
    """Reserves one item per ACHIEVEMENT_REWARDS entry, setting its unlock_achievement_id. Reward i
    starts at slot SLOTS[i % len(SLOTS)] (so the first 8 keep SLOTS[0..7] and stay byte-identical);
    if that slot has no eligible item, tries the next slots in round-robin order. Picks the first
    item (in generation order) of the chosen slot/rarity that isn't already a set piece,
    star-unlockable, or achievement-gated."""
    for i, (achievement_id, rarity) in enumerate(ACHIEVEMENT_REWARDS):
        candidate = None
        chosen_slot = None
        for off in range(len(SLOTS)):
            slot = SLOTS[(i + off) % len(SLOTS)]
            found = next(
                (
                    item
                    for item in catalog[slot.slot_id]
                    if item.rarity == rarity
                    and not item.is_weekly_streak_set_piece
                    and not item.is_star_unlockable
                    and item.unlock_star_requirement == 0
                    and item.unlock_achievement_id is None
                ),
                None,
            )
            if found is not None:
                candidate = found
                chosen_slot = slot
                break
        if candidate is None:
            raise RuntimeError(
                f"No eligible {rarity} item in any slot for achievement {achievement_id}"
            )
        candidate.unlock_achievement_id = achievement_id


def generate_catalog(per_slot: int = 64, seed: int = 42) -> Dict[str, List[GeneratedItem]]:
    rng = random.Random(seed)
    catalog: Dict[str, List[GeneratedItem]] = {}
    for slot in SLOTS:
        # Give each slot its own sub-seed derived deterministically from the base seed so that
        # changing --per-slot doesn't ripple unrelated changes into other slots' RNG streams.
        slot_rng = random.Random(f"{seed}:{slot.slot_id}")
        catalog[slot.slot_id] = generate_items_for_slot(slot, per_slot, slot_rng, seed=seed)
    _assign_global_star_thresholds(catalog)
    _assign_achievement_items(catalog)
    return catalog


def bundled_item_ids(seed: int = 42, per_slot: int = 64) -> set:
    """Every item id in the bundled catalog: generate_catalog()'s ids plus HANDMADE_ITEM_IDS.
    Used by the remote-item validator to reject any id clash with the bundled catalog."""
    catalog = generate_catalog(per_slot=per_slot, seed=seed)
    ids = {item.item_id for items in catalog.values() for item in items}
    return ids | HANDMADE_ITEM_IDS


def _print_dry_run_stats(catalog: Dict[str, List[GeneratedItem]]) -> None:
    for slot in SLOTS:
        items = catalog[slot.slot_id]
        rarity_counts = Counter(i.rarity for i in items)
        style_counts = Counter(i.style_index for i in items)
        print(f"[{slot.slot_id}] total={len(items)}")
        print(f"  rarity: {{{', '.join(f'{r}={rarity_counts.get(r, 0)}' for r in RARITIES)}}}")
        print(f"  styles: {{{', '.join(f'{s}={style_counts.get(s, 0)}' for s in range(len(slot.styles)))}}}")


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--per-slot", type=int, default=64, help="Items to generate per slot (default 64)")
    parser.add_argument("--seed", type=int, default=42, help="RNG seed for deterministic output (default 42)")
    args = parser.parse_args(argv)

    catalog = generate_catalog(per_slot=args.per_slot, seed=args.seed)
    _print_dry_run_stats(catalog)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
