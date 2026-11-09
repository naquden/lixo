#!/usr/bin/env python3
"""Python port of `ContentPackValidator` + `ContentPaths` (doc/plans/019/021): validates a built
content pack and, if a previously-published pack is available, runs the stability diff.

Every skip/reject is reported as an error here (unlike the Kotlin validator, which can silently
skip individual entries and keep the rest of the pack) because the bundled pack must have 0
skips -- see `BundledPackValidTest`.

CLI: `validate_pack.py [PACK=out/content_pack.json] [--against FILE]`.
"""
from __future__ import annotations

import argparse
import datetime
import json
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

PACK_DIR = Path(__file__).resolve().parent
OUT_PATH = PACK_DIR / "out" / "content_pack.json"
PUBLISHED_DIR = PACK_DIR / "published"

MAX_BYTES = 6 * 1024 * 1024
MAX_ITEMS = 3000
MAX_AVATARS = 64
MAX_EVENTS = 64
MAX_LAYERS = 24
MAX_HIDES_PARTS = 8
MAX_PARTS = 16
MAX_PATH_CHARS = 4096

ID_REGEX = re.compile(r"^[a-z][a-z0-9_]{2,63}$")
PART_REGEX = re.compile(r"^[a-z][a-z0-9_]{1,31}$")
HEX_REGEX = re.compile(r"^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

ALLOWED_CHARS = re.compile(r"^[MmLlHhVvCcSsQqTtAaZz0-9.+\-eE\s,]*$")
TOKEN_REGEX = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")
SEPARATOR_REGEX = re.compile(r"^[\s,]*$")

AVATAR_PART_ROLES = {"skin", "tint", "eye", "outline"}

STANDARD_BONE_BOX = {
    "ROOT": (160.0, 240.0),
    "TORSO": (80.0, 80.0),
    "HEAD": (56.0, 56.0),
    "ARM_L": (38.0, 80.0),
    "ARM_R": (38.0, 80.0),
    "HAND_L": (38.0, 38.0),
    "HAND_R": (38.0, 38.0),
    "LEG_L": (40.0, 80.0),
    "LEG_R": (40.0, 80.0),
    "TAIL": (40.0, 60.0),
}
BONE_BOX_OVERHANG = 1.5

RARITIES = ("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY")


def parse_hex(value: str) -> Optional[int]:
    m = HEX_REGEX.match(value or "")
    if not m:
        return None
    digits = m.group(1)
    rgb = int(digits, 16)
    return rgb if len(digits) == 8 else (0xFF000000 | rgb)


def expanded_box(box: Tuple[float, float]) -> Tuple[float, float, float, float]:
    w, h = box
    return (-BONE_BOX_OVERHANG * w, -BONE_BOX_OVERHANG * h, (1 + BONE_BOX_OVERHANG) * w, (1 + BONE_BOX_OVERHANG) * h)


def is_inside(rect: Tuple[float, float, float, float], outer: Tuple[float, float, float, float]) -> bool:
    l, t, r, b = rect
    ol, ot, orr, ob = outer
    return l >= ol and t >= ot and r <= orr and b <= ob


# --- Path strict validation + bounds (port of ContentPaths.kt) ---

_ARITY = {"M": 2, "L": 2, "T": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "A": 7, "Z": 0}


def path_strict_error(d: str) -> Optional[str]:
    if not d or not d.strip():
        return "path data is blank"
    if len(d) > MAX_PATH_CHARS:
        return f"path data exceeds {MAX_PATH_CHARS} characters"
    if not ALLOWED_CHARS.match(d):
        return "path data contains an illegal character"

    tokens: List[str] = []
    last_end = 0
    for m in TOKEN_REGEX.finditer(d):
        between = d[last_end : m.start()]
        if not SEPARATOR_REGEX.match(between):
            return "path data has an illegal separator"
        tokens.append(m.group(0))
        last_end = m.end()
    if not SEPARATOR_REGEX.match(d[last_end:]):
        return "path data has an illegal separator"
    if not tokens:
        return "path data has no commands"
    if tokens[0] not in ("M", "m"):
        return "path data must start with M or m"

    i = 0
    while i < len(tokens):
        token = tokens[i]
        cmd = token[0]
        if not cmd.isalpha():
            return "expected a command, found a number"
        i += 1
        arity = _ARITY.get(cmd.upper())
        if arity is None:
            return f"unknown command '{cmd}'"
        if arity == 0:
            continue
        start = i
        while i < len(tokens) and not tokens[i][0].isalpha():
            i += 1
        count = i - start
        if count == 0 or count % arity != 0:
            return f"wrong argument count for '{cmd}'"
        group = start
        while group < start + count:
            for k in range(arity):
                num_tok = tokens[group + k]
                try:
                    value = float(num_tok)
                except ValueError:
                    return f"invalid number '{num_tok}'"
                if value != value or value in (float("inf"), float("-inf")):
                    return "non-finite number in path data"
                if cmd.upper() == "A" and k in (3, 4) and value not in (0.0, 1.0):
                    return "arc flag must be 0 or 1"
            group += arity
    return None


def parse_nodes(d: str) -> List[Tuple[str, List[float]]]:
    """Tokenizes already-`path_strict_error`-checked `d` into `(cmd, args)` pairs. Only
    M/L/H/V/C/S/Q/T/Z are produced by this pipeline's templates; A is accepted but its bounds
    contribution is approximated by its endpoints only (no templates emit arcs)."""
    tokens = [m.group(0) for m in TOKEN_REGEX.finditer(d)]
    nodes: List[Tuple[str, List[float]]] = []
    i = 0
    cmd = None
    while i < len(tokens):
        if tokens[i][0].isalpha():
            cmd = tokens[i]
            i += 1
            continue
        arity = _ARITY[cmd.upper()]
        args = [float(tokens[i + k]) for k in range(arity)]
        nodes.append((cmd, args))
        i += arity
    if cmd is not None and _ARITY[cmd.upper()] == 0:
        nodes.append((cmd, []))
    return nodes


def path_bounds(d: str) -> Optional[Tuple[float, float, float, float]]:
    nodes = parse_nodes(d)
    min_x = min_y = float("inf")
    max_x = max_y = float("-inf")
    any_seen = False
    cur = (0.0, 0.0)
    subpath_start = (0.0, 0.0)

    def include(x: float, y: float) -> None:
        nonlocal min_x, min_y, max_x, max_y, any_seen
        any_seen = True
        min_x, min_y = min(min_x, x), min(min_y, y)
        max_x, max_y = max(max_x, x), max(max_y, y)

    for cmd, args in nodes:
        rel = cmd.islower()
        c = cmd.upper()
        if c == "M":
            x, y = args
            cur = (cur[0] + x, cur[1] + y) if rel else (x, y)
            subpath_start = cur
        elif c == "Z":
            include(*cur)
            include(*subpath_start)
            cur = subpath_start
        elif c == "L":
            include(*cur)
            x, y = args
            cur = (cur[0] + x, cur[1] + y) if rel else (x, y)
            include(*cur)
        elif c == "H":
            include(*cur)
            x = args[0]
            cur = (cur[0] + x, cur[1]) if rel else (x, cur[1])
            include(*cur)
        elif c == "V":
            include(*cur)
            y = args[0]
            cur = (cur[0], cur[1] + y) if rel else (cur[0], y)
            include(*cur)
        elif c == "C":
            include(*cur)
            x1, y1, x2, y2, x3, y3 = args
            if rel:
                x1, y1, x2, y2, x3, y3 = (
                    cur[0] + x1, cur[1] + y1, cur[0] + x2, cur[1] + y2, cur[0] + x3, cur[1] + y3,
                )
            include(x1, y1)
            include(x2, y2)
            include(x3, y3)
            cur = (x3, y3)
        elif c == "S":
            include(*cur)
            x2, y2, x3, y3 = args
            if rel:
                x2, y2, x3, y3 = cur[0] + x2, cur[1] + y2, cur[0] + x3, cur[1] + y3
            include(x2, y2)
            include(x3, y3)
            cur = (x3, y3)
        elif c == "Q":
            include(*cur)
            x1, y1, x2, y2 = args
            if rel:
                x1, y1, x2, y2 = cur[0] + x1, cur[1] + y1, cur[0] + x2, cur[1] + y2
            include(x1, y1)
            include(x2, y2)
            cur = (x2, y2)
        elif c == "T":
            include(*cur)
            x, y = args
            cur = (cur[0] + x, cur[1] + y) if rel else (x, y)
            include(*cur)
        elif c == "A":
            include(*cur)
            x, y = args[5], args[6]
            cur = (cur[0] + x, cur[1] + y) if rel else (x, y)
            include(*cur)
    return (min_x, min_y, max_x, max_y) if any_seen else None


def layer_errors(layer: dict, box: Tuple[float, float], allowed_roles: Optional[set]) -> List[str]:
    errors: List[str] = []
    d = layer.get("d", "")
    err = path_strict_error(d)
    if err:
        errors.append(f"layer invalid: {err}")
        return errors
    bounds = path_bounds(d)
    if bounds is None:
        errors.append("layer invalid: empty path")
        return errors
    if not is_inside(bounds, expanded_box(box)):
        errors.append(f"layer invalid: out of bone box {bounds} vs {expanded_box(box)}")
    fill = layer.get("fill", {})
    shade = fill.get("shade", 0)
    if shade < -1 or shade > 1:
        errors.append("layer invalid: fill.shade out of range")
    stroke = layer.get("stroke")
    if stroke is not None:
        width = stroke.get("width")
        if width is None or width <= 0 or width > 16:
            errors.append("layer invalid: stroke.width out of range")
    shading = layer.get("shading", "gradient")
    if shading == "block":
        depth = layer.get("depth")
        if depth is None or depth <= 0 or depth > 40:
            errors.append("layer invalid: shading BLOCK requires depth in (0,40]")
    parallax = layer.get("parallax")
    if parallax is not None and (parallax < -2 or parallax > 2):
        errors.append("layer invalid: parallax out of range")
    mirror = layer.get("mirror", "none")
    bone = layer.get("bone")
    mirrorable = {"ARM_L", "ARM_R", "HAND_L", "HAND_R", "LEG_L", "LEG_R"}
    if mirror == "x" and bone not in mirrorable:
        errors.append("layer invalid: mirror x requires a mirrorable bone")
    if allowed_roles is not None:
        if fill.get("role") not in allowed_roles:
            errors.append("layer invalid: fill.role not allowed here")
        if stroke is not None and stroke.get("role") not in allowed_roles:
            errors.append("layer invalid: stroke.role not allowed here")
    return errors


def item_errors(item: dict, declared_event_ids: set) -> List[str]:
    errors: List[str] = []
    if not ID_REGEX.match(item.get("id", "")):
        errors.append(f"item {item.get('id')}: id invalid")
    if not item.get("nameKey"):
        errors.append(f"item {item.get('id')}: nameKey is blank")
    palette = item.get("palette", {})
    for key in ("primary", "secondary", "accent"):
        if parse_hex(palette.get(key, "")) is None:
            errors.append(f"item {item.get('id')}: palette.{key} invalid")
    scale = item.get("scale", 1.0)
    if scale < 0.5 or scale > 2:
        errors.append(f"item {item.get('id')}: scale out of range")
    hides = item.get("hidesParts", [])
    if len(hides) > MAX_HIDES_PARTS:
        errors.append(f"item {item.get('id')}: too many hidesParts")
    for h in hides:
        if not PART_REGEX.match(h):
            errors.append(f"item {item.get('id')}: hidesParts has an invalid part name")
    layers = item.get("layers", [])
    if not layers or len(layers) > MAX_LAYERS:
        errors.append(f"item {item.get('id')}: layers count out of range")
    pattern_clip_count = 0
    for layer in layers:
        if layer.get("patternClip"):
            pattern_clip_count += 1
        bone = layer.get("bone")
        box = STANDARD_BONE_BOX.get(bone)
        if box is None:
            errors.append(f"item {item.get('id')}: no bone box for {bone}")
            continue
        for e in layer_errors(layer, box, allowed_roles=None):
            errors.append(f"item {item.get('id')}: {e}")
    if pattern_clip_count != 1:
        errors.append(f"item {item.get('id')}: expected exactly one patternClip layer, got {pattern_clip_count}")
    if item.get("pool") == "event" and not item.get("eventId"):
        errors.append(f"item {item.get('id')}: pool EVENT requires eventId")
    event_id = item.get("eventId")
    if event_id is not None and event_id not in declared_event_ids:
        errors.append(f"item {item.get('id')}: bad eventId")
    return errors


def event_errors(event: dict, items_by_id: Dict[str, dict]) -> List[str]:
    """Port of the Kotlin `eventError` check: a bad event here must fail loudly, since the
    bundled pack must have 0 skips (see `BundledPackValidTest`)."""
    errors: List[str] = []
    event_id = event.get("id")
    if not ID_REGEX.match(event_id or ""):
        errors.append(f"event {event_id}: id invalid")
    if not event.get("nameKey"):
        errors.append(f"event {event_id}: nameKey is blank")
    if event.get("tz") != "local":
        errors.append(f"event {event_id}: tz must be 'local'")

    start_raw, end_raw = event.get("start"), event.get("endExclusive")
    start = end = None
    try:
        start = datetime.date.fromisoformat(start_raw)
    except (TypeError, ValueError):
        errors.append(f"event {event_id}: start invalid")
    try:
        end = datetime.date.fromisoformat(end_raw)
    except (TypeError, ValueError):
        errors.append(f"event {event_id}: endExclusive invalid")
    if start is not None and end is not None and start >= end:
        errors.append(f"event {event_id}: start must be before endExclusive")

    chest_theme = event.get("chestTheme")
    if chest_theme is not None:
        if parse_hex(chest_theme.get("bodyHex", "")) is None:
            errors.append(f"event {event_id}: chestTheme.bodyHex invalid")
        if parse_hex(chest_theme.get("trimHex", "")) is None:
            errors.append(f"event {event_id}: chestTheme.trimHex invalid")

    banner = event.get("banner")
    if banner is not None:
        if not banner.get("titleKey"):
            errors.append(f"event {event_id}: banner.titleKey is blank")
        if parse_hex(banner.get("bgHex", "")) is None:
            errors.append(f"event {event_id}: banner.bgHex invalid")
        if parse_hex(banner.get("fgHex", "")) is None:
            errors.append(f"event {event_id}: banner.fgHex invalid")

    drop_boost = event.get("dropBoost", {})
    for key in ("eventItemChance", "presentGiftChance"):
        value = drop_boost.get(key, 0)
        if value < 0 or value > 1:
            errors.append(f"event {event_id}: dropBoost.{key} out of range")

    if event.get("afterEnd", "retire") not in ("retire", "joinStandard"):
        errors.append(f"event {event_id}: afterEnd invalid")

    item_ids = event.get("itemIds", [])
    if not item_ids:
        errors.append(f"event {event_id}: itemIds is empty")
    for item_id in item_ids:
        item = items_by_id.get(item_id)
        if item is None:
            errors.append(f"event {event_id}: itemIds references unknown item {item_id}")
        elif item.get("pool") != "event" or item.get("eventId") != event_id:
            errors.append(f"event {event_id}: item {item_id} is not pool=event with eventId={event_id}")

    return errors


def avatar_errors(avatar: dict) -> List[str]:
    errors: List[str] = []
    aid = avatar.get("id")
    if not ID_REGEX.match(aid or ""):
        errors.append(f"avatar {aid}: id invalid")
    if not avatar.get("nameKey"):
        errors.append(f"avatar {aid}: nameKey is blank")
    if parse_hex(avatar.get("skinHex", "")) is None:
        errors.append(f"avatar {aid}: skinHex invalid")
    if parse_hex(avatar.get("defaultTintHex", "")) is None:
        errors.append(f"avatar {aid}: defaultTintHex invalid")
    unlock = avatar.get("unlock", {})
    if unlock.get("type") == "stars" and unlock.get("stars", 0) < 1:
        errors.append(f"avatar {aid}: unlock.stars must be >= 1")

    bones = avatar.get("bones", {})
    overrides: Dict[str, Tuple[float, float]] = {}
    for bone, override in bones.items():
        standard = STANDARD_BONE_BOX.get(bone)
        if standard is None:
            errors.append(f"avatar {aid}: no bone box for {bone}")
            continue
        size = override.get("size")
        if size is not None:
            if len(size) != 2:
                errors.append(f"avatar {aid}: bones.{bone}.size must have 2 values")
            else:
                if not (standard[0] * 0.25 <= size[0] <= standard[0] * 4):
                    errors.append(f"avatar {aid}: bones.{bone}.size[0] out of range")
                if not (standard[1] * 0.25 <= size[1] <= standard[1] * 4):
                    errors.append(f"avatar {aid}: bones.{bone}.size[1] out of range")
                overrides[bone] = (size[0], size[1])

    parts = avatar.get("parts", [])
    if len(parts) > MAX_PARTS:
        errors.append(f"avatar {aid}: too many parts")
    seen_names = set()
    for part in parts:
        name = part.get("part")
        if not PART_REGEX.match(name or ""):
            errors.append(f"avatar {aid}: part name '{name}' invalid")
        if name in seen_names:
            errors.append(f"avatar {aid}: duplicate part name '{name}'")
        seen_names.add(name)
        layers = part.get("layers", [])
        if not layers or len(layers) > MAX_LAYERS:
            errors.append(f"avatar {aid}: part '{name}' layers count out of range")
        for layer in layers:
            bone = layer.get("bone")
            standard = STANDARD_BONE_BOX.get(bone)
            if standard is None:
                errors.append(f"avatar {aid}: no bone box for {bone}")
                continue
            effective = overrides.get(bone, standard)
            for e in layer_errors(layer, effective, AVATAR_PART_ROLES):
                errors.append(f"avatar {aid}: part '{name}' {e}")
    return errors


def drop_rules_errors(rules: dict) -> List[str]:
    errors: List[str] = []
    weights = rules.get("rarityWeights", {})
    for rarity in RARITIES:
        w = weights.get(rarity)
        if w is None or w <= 0:
            errors.append(f"rarityWeights missing or <= 0 for {rarity}")
    pity = rules.get("pity", {})
    rare, epic, legendary = pity.get("RARE"), pity.get("EPIC"), pity.get("LEGENDARY")
    if rare is None or epic is None or legendary is None:
        errors.append("pity must have RARE, EPIC and LEGENDARY")
    else:
        if rare <= 0 or epic <= 0 or legendary <= 0:
            errors.append("pity values must be > 0")
        if not (rare < epic < legendary):
            errors.append("pity must be strictly increasing RARE < EPIC < LEGENDARY")
    if rules.get("chestCostHalfPoints", 0) <= 0:
        errors.append("chestCostHalfPoints must be > 0")
    if rules.get("progressRunHalfPoints", 0) <= 0:
        errors.append("progressRunHalfPoints must be > 0")
    if rules.get("replayRunHalfPoints", 0) <= 0:
        errors.append("replayRunHalfPoints must be > 0")
    levels = rules.get("onboarding", {}).get("levels", [])
    if any(l < 1 for l in levels):
        errors.append("onboarding.levels must be >= 1")
    for i in range(1, len(levels)):
        if levels[i] <= levels[i - 1]:
            errors.append("onboarding.levels must be strictly increasing")
            break
    if any(s.get("stars", 0) < 1 for s in rules.get("starUnlocks", [])):
        errors.append("starUnlocks.stars must be >= 1")
    level_rewards = rules.get("levelRewards", [])
    if any(r.get("level", 0) < 1 for r in level_rewards):
        errors.append("levelRewards.level must be >= 1")
    seen_levels = set()
    for r in level_rewards:
        lvl = r.get("level")
        if lvl in seen_levels:
            errors.append(f"levelRewards has duplicate level {lvl}")
        seen_levels.add(lvl)
    return errors


def validate(pack: dict) -> List[str]:
    """Every error, as a flat list; an empty list means the pack would be fully `Accepted` with
    0 skips by the Kotlin `ContentPackValidator`."""
    errors: List[str] = []

    if pack.get("schemaVersion") != 1:
        errors.append(f"schemaVersion {pack.get('schemaVersion')} unsupported")
        return errors
    if not isinstance(pack.get("version"), int):
        errors.append("version is missing or not an int")

    items = pack.get("items", [])
    avatars = pack.get("avatars", [])
    events = pack.get("events", [])
    if len(items) > MAX_ITEMS:
        errors.append(f"too many items (> {MAX_ITEMS})")
    if len(avatars) > MAX_AVATARS:
        errors.append(f"too many avatars (> {MAX_AVATARS})")
    if len(events) > MAX_EVENTS:
        errors.append(f"too many events (> {MAX_EVENTS})")

    drop_rules = pack.get("dropRules")
    if drop_rules is None:
        errors.append("dropRules invalid: missing")
        return errors
    errors.extend(drop_rules_errors(drop_rules))

    declared_event_ids = {e["id"] for e in events if ID_REGEX.match(e.get("id", ""))}

    seen_item_ids = set()
    items_by_id = {item.get("id"): item for item in items}
    for item in items:
        for e in item_errors(item, declared_event_ids):
            errors.append(e)
        if item.get("id") in seen_item_ids:
            errors.append(f"item {item.get('id')}: duplicate id")
        seen_item_ids.add(item.get("id"))

    seen_event_ids = set()
    for event in events:
        for e in event_errors(event, items_by_id):
            errors.append(e)
        if event.get("id") in seen_event_ids:
            errors.append(f"event {event.get('id')}: duplicate id")
        seen_event_ids.add(event.get("id"))

    # Every pool="event" item must be listed in its own event's itemIds (the reverse direction
    # of event_errors' itemIds -> item check), so an item can't silently fall out of its event's
    # pool/sheet.
    event_item_ids_by_event = {e.get("id"): set(e.get("itemIds", [])) for e in events}
    for item in items:
        if item.get("pool") == "event":
            event_id = item.get("eventId")
            if event_id not in event_item_ids_by_event or item.get("id") not in event_item_ids_by_event[event_id]:
                errors.append(f"item {item.get('id')}: pool=event but not listed in event {event_id}'s itemIds")

    seen_avatar_ids = set()
    has_default = False
    for avatar in avatars:
        for e in avatar_errors(avatar):
            errors.append(e)
        if avatar.get("id") in seen_avatar_ids:
            errors.append(f"avatar {avatar.get('id')}: duplicate id")
        seen_avatar_ids.add(avatar.get("id"))
        if avatar.get("unlock", {}).get("type") == "default":
            has_default = True
    if not has_default:
        errors.append("no default avatar")

    for achievement_id, item_id in drop_rules.get("achievementRewards", {}).items():
        if item_id not in seen_item_ids:
            errors.append(f"achievementRewards[{achievement_id}] references unknown item {item_id}")
    for r in drop_rules.get("levelRewards", []):
        if r.get("itemId") not in seen_item_ids:
            errors.append(f"levelRewards references unknown item {r.get('itemId')}")
    for s in drop_rules.get("starUnlocks", []):
        if s.get("itemId") not in seen_item_ids:
            errors.append(f"starUnlocks references unknown item {s.get('itemId')}")
    tutorial_id = drop_rules.get("tutorialRewardItemId")
    if tutorial_id not in seen_item_ids:
        errors.append(f"tutorialRewardItemId references unknown item {tutorial_id}")
    for item_id in drop_rules.get("weeklyStreakSetPieceItemIds", []):
        if item_id not in seen_item_ids:
            errors.append(f"weeklyStreakSetPieceItemIds references unknown item {item_id}")

    return errors


def stability_errors(new: dict, old: dict) -> List[str]:
    errors: List[str] = []
    old_item_ids = {i["id"] for i in old.get("items", [])}
    new_item_ids = {i["id"] for i in new.get("items", [])}
    for removed in old_item_ids - new_item_ids:
        errors.append(f"removed item id {removed}")

    old_avatar_ids = {a["id"] for a in old.get("avatars", [])}
    new_avatar_ids = {a["id"] for a in new.get("avatars", [])}
    for removed in old_avatar_ids - new_avatar_ids:
        errors.append(f"removed avatar id {removed}")

    old_event_ids = {e["id"] for e in old.get("events", [])}
    new_event_ids = {e["id"] for e in new.get("events", [])}
    for removed in old_event_ids - new_event_ids:
        errors.append(f"removed event id {removed}")

    old_level_rewards = {r["level"]: r["itemId"] for r in old.get("dropRules", {}).get("levelRewards", [])}
    new_level_rewards = {r["level"]: r["itemId"] for r in new.get("dropRules", {}).get("levelRewards", [])}
    for level, item_id in old_level_rewards.items():
        if new_level_rewards.get(level) != item_id:
            errors.append(f"levelRewards level {level} changed or missing (was {item_id})")

    old_achievements = old.get("dropRules", {}).get("achievementRewards", {})
    new_achievements = new.get("dropRules", {}).get("achievementRewards", {})
    for achievement_id, item_id in old_achievements.items():
        if new_achievements.get(achievement_id) != item_id:
            errors.append(f"achievementRewards[{achievement_id}] changed or missing (was {item_id})")

    old_star_unlocks = {(s["stars"], s["itemId"]) for s in old.get("dropRules", {}).get("starUnlocks", [])}
    new_star_unlocks = {(s["stars"], s["itemId"]) for s in new.get("dropRules", {}).get("starUnlocks", [])}
    for missing in old_star_unlocks - new_star_unlocks:
        errors.append(f"starUnlock {missing} missing")

    old_slots = {i["id"]: i["slot"] for i in old.get("items", [])}
    new_slots = {i["id"]: i["slot"] for i in new.get("items", [])}
    for item_id, slot in old_slots.items():
        if item_id in new_slots and new_slots[item_id] != slot:
            errors.append(f"item {item_id} slot changed from {slot} to {new_slots[item_id]}")

    if new.get("version", 0) <= old.get("version", 0):
        errors.append(f"version {new.get('version')} not greater than {old.get('version')}")

    return errors


def _latest_published() -> Optional[Path]:
    best_n = -1
    best: Optional[Path] = None
    if PUBLISHED_DIR.exists():
        for f in PUBLISHED_DIR.glob("pack_v*.json"):
            m = re.match(r"pack_v(\d+)\.json$", f.name)
            if m and int(m.group(1)) > best_n:
                best_n = int(m.group(1))
                best = f
    return best


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", nargs="?", default=str(OUT_PATH))
    parser.add_argument("--against", type=str, default=None)
    args = parser.parse_args(argv)

    pack_path = Path(args.pack)
    if not pack_path.exists():
        print(f"{pack_path} does not exist", file=sys.stderr)
        return 1
    data = pack_path.read_bytes()
    if len(data) > MAX_BYTES:
        print(f"pack exceeds {MAX_BYTES} bytes", file=sys.stderr)
        return 1
    pack = json.loads(data.decode("utf-8"))

    errors = validate(pack)

    against_path: Optional[Path] = Path(args.against) if args.against else _latest_published()
    if against_path is None:
        print("no published pack — stability diff skipped")
    elif against_path.exists():
        old = json.loads(against_path.read_text(encoding="utf-8"))
        errors.extend(stability_errors(pack, old))
    else:
        print(f"{against_path} does not exist", file=sys.stderr)
        return 1

    if errors:
        for e in errors:
            print(e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
