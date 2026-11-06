#!/usr/bin/env python3
"""Generates the Kotlin wardrobe catalog (GeneratedWardrobeCatalog.kt).

Deterministic (given the same --seed), stdlib-only generator for the "wardrobe item variety"
feature. Builds `--per-slot` items for each of the 9 EquipmentSlot values, with:
  - 8 procedurally-drawable styles per slot (styleIndex 0..7, order matters -- the renderer
    switches on `styleIndex % 8`).
  - ~16 named regular colorways + a handful of special legendary-only colorways.
  - Rarity split per slot of roughly 40/25/17/12/6% (COMMON..LEGENDARY), at least 1 of each.
  - Pattern/ornament escalating with rarity.
  - No duplicate (styleIndex, colorway) within a slot.

Item ids are content-derived and stable: `item_<slot_id>_<styleKey>_<colorwayKey>`, where each key
is `slug_key()` of the Swedish display string (lowercase, å/ä/ö -> a/a/o, all non-[a-z0-9] removed),
e.g. "Krona" + "Gyllene" -> `item_hat_krona_gyllene`. meshNodeName uses the same keys
(`<socket_name>_<styleKey>_<colorwayKey>`).
Ids are persisted in user inventories/Firestore -- never rename existing colorway/style keys
(i.e. their Swedish display strings in SLOTS/COLORWAYS/LEGENDARY_COLORWAYS); only append.

Run with --dry-run to print per-slot rarity/style counts without writing the Kotlin file.

This is item/gear data, not curriculum content -- it is unrelated to
tools/content_pipeline/generate_content.py and seed_campaign_content.json, which this script
never touches.
"""
from __future__ import annotations

import argparse
import random
import re
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = (
    REPO_ROOT
    / "composeApp"
    / "src"
    / "commonMain"
    / "kotlin"
    / "se"
    / "lixo"
    / "app"
    / "data"
    / "repository"
    / "GeneratedWardrobeCatalog.kt"
)

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


# How many COMMON/UNCOMMON items per slot are star-unlockable at all (the rest -- all
# RARE/EPIC/LEGENDARY, plus any extra COMMON/UNCOMMON beyond these caps -- get
# unlockStarRequirement = 0, i.e. only obtainable via level rewards, chests, or gifts).
# Without this cap, every generated item star-unlocks once totalStars is high enough, which
# auto-unlocks the whole 576-item catalog and makes gift/chest rolls always hit duplicates.
STAR_UNLOCKABLE_COMMON_CAP = 6
STAR_UNLOCKABLE_UNCOMMON_CAP = 2


def _star_unlock_thresholds(count: int, slot_index: int) -> List[int]:
    """Spreads `count` star-unlock thresholds evenly over ~2..250 stars. The lowest threshold is
    staggered per `slot_index` (2..6) so different slots' first star-unlockable item doesn't all
    land on the same star count -- otherwise every slot would pop its first unlock simultaneously."""
    if count <= 0:
        return []
    first = 2 + (slot_index % (STAR_UNLOCKABLE_COMMON_CAP - 1))
    if count == 1:
        return [first]
    last = 250
    return [round(first + k * (last - first) / (count - 1)) for k in range(count)]


def generate_items_for_slot(
    slot: SlotDef, per_slot: int, rng: random.Random, slot_index: int = 0
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
    # STAR_UNLOCKABLE_COMMON_CAP/STAR_UNLOCKABLE_UNCOMMON_CAP. Thresholds are precomputed here
    # (count is known up-front from rarity_counts) and handed out in generation order.
    common_unlockable_cap = min(STAR_UNLOCKABLE_COMMON_CAP, rarity_counts["COMMON"])
    uncommon_unlockable_cap = min(STAR_UNLOCKABLE_UNCOMMON_CAP, rarity_counts["UNCOMMON"])
    star_unlock_thresholds = _star_unlock_thresholds(
        common_unlockable_cap + uncommon_unlockable_cap, slot_index
    )
    next_threshold_index = 0

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
        pattern, ornament = _rarity_pattern_ornament(rarity, rng)

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
        if is_star_unlockable:
            unlock_star_requirement = star_unlock_thresholds[next_threshold_index]
            next_threshold_index += 1
        else:
            unlock_star_requirement = 0

        is_weekly_streak_set_piece = False
        if rarity == "LEGENDARY" and not legendary_set_piece_assigned:
            is_weekly_streak_set_piece = True
            legendary_set_piece_assigned = True

        style_name = slot.styles[style_index]
        style_key = slug_key(style_name)
        colorway_key = slug_key(colorway_name)
        item_id = f"item_{slot.slot_id}_{style_key}_{colorway_key}"
        name_sv = f"{colorway_name} {style_name}"
        description_sv = f"En {style_name.lower()} i {colorway_name.lower()} nyans, upplåsbar för alla avatarer i Lixo."
        mesh_node_name = f"{slot.socket_name}_{style_key}_{colorway_key}"

        items.append(
            GeneratedItem(
                item_id=item_id,
                name_sv=name_sv,
                description_sv=description_sv,
                slot=slot,
                rarity=rarity,
                style_index=style_index,
                style_name=style_name,
                colorway_name=colorway_name,
                primary_color_hex=primary_hex,
                secondary_color_hex=secondary_hex,
                pattern=pattern,
                ornament=ornament,
                scale_multiplier=scale_multiplier,
                unlock_star_requirement=unlock_star_requirement,
                # No per-generated-item achievement exists in buildSeedAchievements(), so we never
                # set unlockAchievementId here (the only valid achievement ids are the 4 handwritten
                # ones, each already tied to a specific handmade or generated-by-id reward item).
                unlock_achievement_id=None,
                is_weekly_streak_set_piece=is_weekly_streak_set_piece,
                mesh_node_name=mesh_node_name,
            )
        )

    return items


def generate_catalog(per_slot: int = 64, seed: int = 42) -> Dict[str, List[GeneratedItem]]:
    rng = random.Random(seed)
    catalog: Dict[str, List[GeneratedItem]] = {}
    for slot_index, slot in enumerate(SLOTS):
        # Give each slot its own sub-seed derived deterministically from the base seed so that
        # changing --per-slot doesn't ripple unrelated changes into other slots' RNG streams.
        slot_rng = random.Random(f"{seed}:{slot.slot_id}")
        catalog[slot.slot_id] = generate_items_for_slot(slot, per_slot, slot_rng, slot_index)
    return catalog


def _kt_string(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
    return f'"{escaped}"'


def _kt_hex(value: int) -> str:
    return f"0x{value:08X}"


def _kt_item_literal(item: GeneratedItem) -> str:
    return (
        "        WardrobeItem(\n"
        f"            id = {_kt_string(item.item_id)},\n"
        f"            nameSv = {_kt_string(item.name_sv)},\n"
        f"            descriptionSv = {_kt_string(item.description_sv)},\n"
        f"            slot = EquipmentSlot.{item.slot.enum_name},\n"
        f"            rarity = Rarity.{item.rarity},\n"
        f"            subjectTheme = Subject.{item.slot.subject},\n"
        "            meshAssetPath = \"3d_sample.glb\",\n"
        f"            meshNodeName = {_kt_string(item.mesh_node_name)},\n"
        f"            scaleMultiplier = {item.scale_multiplier}f,\n"
        f"            unlockStarRequirement = {item.unlock_star_requirement},\n"
        f"            unlockAchievementId = {('null' if item.unlock_achievement_id is None else _kt_string(item.unlock_achievement_id))},\n"
        f"            isWeeklyStreakSetPiece = {str(item.is_weekly_streak_set_piece).lower()},\n"
        f"            primaryColorHex = {_kt_hex(item.primary_color_hex)},\n"
        f"            styleIndex = {item.style_index},\n"
        f"            secondaryColorHex = {_kt_hex(item.secondary_color_hex)},\n"
        f"            pattern = ItemPattern.{item.pattern},\n"
        f"            ornament = ItemOrnament.{item.ornament}\n"
        "        )"
    )


def _slot_function_name(slot: SlotDef) -> str:
    # e.g. "hat" -> "hatItems", "shoulders_back" -> "shouldersBackItems"
    parts = slot.slot_id.split("_")
    camel = parts[0] + "".join(p.capitalize() for p in parts[1:])
    return f"{camel}Items"


def render_kotlin(catalog: Dict[str, List[GeneratedItem]]) -> str:
    lines: List[str] = []
    lines.append("// GENERATED by tools/item_pipeline/generate_wardrobe_items.py — do not edit")
    lines.append("package se.lixo.app.data.repository")
    lines.append("")
    lines.append("import se.lixo.app.domain.model.EquipmentSlot")
    lines.append("import se.lixo.app.domain.model.ItemOrnament")
    lines.append("import se.lixo.app.domain.model.ItemPattern")
    lines.append("import se.lixo.app.domain.model.Rarity")
    lines.append("import se.lixo.app.domain.model.Subject")
    lines.append("import se.lixo.app.domain.model.WardrobeItem")
    lines.append("")

    for slot in SLOTS:
        fn_name = _slot_function_name(slot)
        lines.append(f"private fun {fn_name}(): List<WardrobeItem> = listOf(")
        item_literals = [_kt_item_literal(item) for item in catalog[slot.slot_id]]
        lines.append(",\n".join(item_literals))
        lines.append(")")
        lines.append("")

    lines.append("internal val GENERATED_WARDROBE_ITEMS: List<WardrobeItem> =")
    fn_calls = " +\n    ".join(f"{_slot_function_name(slot)}()" for slot in SLOTS)
    lines.append(f"    {fn_calls}")
    lines.append("")
    return "\n".join(lines)


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
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="Output .kt file path")
    parser.add_argument("--dry-run", action="store_true", help="Print per-slot stats; do not write the file")
    args = parser.parse_args(argv)

    catalog = generate_catalog(per_slot=args.per_slot, seed=args.seed)

    if args.dry_run:
        _print_dry_run_stats(catalog)
        return 0

    kotlin_source = render_kotlin(catalog)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(kotlin_source, encoding="utf-8")
    total = sum(len(v) for v in catalog.values())
    print(f"Wrote {total} items ({args.per_slot} per slot x {len(SLOTS)} slots) to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
