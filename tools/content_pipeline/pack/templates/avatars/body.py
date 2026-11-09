"""Per-avatar body parts (doc/plans/026): one `body_parts(sizes)` draws torso/arms/hands/legs/
pants/shoes/head as block shapes filling each bone's *effective* size, so one function serves
every avatar's own proportions. Avatar part layers may only use the skin/tint/eye/outline roles,
plus primary/secondary/accent when the avatar has a `palette` -- see
`ContentPackValidator.AVATAR_PART_ROLES`/`AVATAR_PALETTE_ROLES`.
"""
from __future__ import annotations

from typing import Callable, Dict, List, Tuple

from tools.content_pipeline.pack.geometry import line, rect, round_rect
from tools.content_pipeline.pack.templates.common import layer, line_layer

Sizes = Dict[str, Tuple[float, float]]


def _part(name: str, layers: List[dict]) -> dict:
    return {"part": name, "layers": layers}


# The standard (unscaled) size of each compact bone, in au -- matches `STANDARD_BONE_BOX`.
STANDARD_SIZES: Dict[str, Tuple[float, float]] = {
    "TORSO": (80.0, 80.0),
    "HEAD": (56.0, 56.0),
    "ARM": (38.0, 80.0),
    "LEG": (40.0, 80.0),
    "TAIL": (40.0, 60.0),
}

# Part names `body_parts` can produce; an avatar's `extras` may replace one of these in place
# (e.g. a skirt replacing "pants") but never duplicate it.
BODY_PART_NAMES: Tuple[str, ...] = ("torso", "arms", "hands", "legs", "pants", "shoes", "head")

# HAND = [armW, round(armH * HAND_ASPECT, 2)]; 38/80, so the standard hand stays 38x38.
HAND_ASPECT = 0.475


def expand_bones(compact: dict) -> Sizes:
    """Expands an avatar source's compact `bones` (HEAD/TORSO/ARM/LEG; missing keys mean
    standard) into every rig bone's effective (w, h), in au. ARM/LEG are mirrored into
    ARM_L/ARM_R and LEG_L/LEG_R; HAND_L/HAND_R are derived from the arm so sleeves, gloves and
    hand-held items always line up. TAIL is always the standard size -- it isn't part of an
    avatar's silhouette table."""
    head_w, head_h = compact.get("HEAD", STANDARD_SIZES["HEAD"])
    torso_w, torso_h = compact.get("TORSO", STANDARD_SIZES["TORSO"])
    arm_w, arm_h = compact.get("ARM", STANDARD_SIZES["ARM"])
    leg_w, leg_h = compact.get("LEG", STANDARD_SIZES["LEG"])
    hand_w, hand_h = arm_w, round(arm_h * HAND_ASPECT, 2)
    tail_w, tail_h = STANDARD_SIZES["TAIL"]
    return {
        "TORSO": (torso_w, torso_h),
        "HEAD": (head_w, head_h),
        "ARM_L": (arm_w, arm_h),
        "ARM_R": (arm_w, arm_h),
        "HAND_L": (hand_w, hand_h),
        "HAND_R": (hand_w, hand_h),
        "LEG_L": (leg_w, leg_h),
        "LEG_R": (leg_w, leg_h),
        "TAIL": (tail_w, tail_h),
    }


def body_parts(sizes: Sizes) -> List[dict]:
    """torso/arms/hands/legs/pants/shoes/head, each filling its bone's effective box. With
    `sizes == expand_bones({})`, every `d` string below is byte-identical to the legacy
    `STANDARD_BODY` (see `test_body_parts_standard_matches_legacy`)."""
    tw, th = sizes["TORSO"]
    aw, ah = sizes["ARM_L"]
    hw, hh = sizes["HAND_L"]
    lw, lh = sizes["LEG_L"]
    hdw, hdh = sizes["HEAD"]

    return [
        _part(
            "torso",
            [
                layer("TORSO", "TORSO", rect(0, 0, tw, th), "skin", shading="block", depth=6),
                line_layer(
                    "TORSO", "TORSO",
                    line(4 * tw / 80, 74 * th / 80, 76 * tw / 80, 74 * th / 80),
                    "outline", width=2, order=1,
                ),
            ],
        ),
        _part(
            "arms",
            [layer("ARM_L", "ARM", rect(0, 0, aw, 0.6 * ah), "skin", shading="block", depth=4, mirror=True)],
        ),
        _part(
            "hands",
            [
                layer(
                    "HAND_L", "HAND", rect(0, 6 * hh / 38, hw, 32 * hh / 38), "tint",
                    shading="block", depth=4, mirror=True,
                )
            ],
        ),
        _part(
            "legs",
            [layer("LEG_L", "LEGS", rect(0, 0, lw, lh), "tint", shading="block", depth=4, mirror=True)],
        ),
        _part(
            "pants",
            [
                layer(
                    "LEG_L", "LEGS", rect(0, 0, lw, 62 * lh / 80), "outline", shade=0.1, order=1,
                    shading="block", depth=4, mirror=True,
                )
            ],
        ),
        _part(
            "shoes",
            [
                layer(
                    "LEG_L", "FOOTWEAR",
                    rect(-3 * lw / 40, 62 * lh / 80, 46 * lw / 40, 18 * lh / 80),
                    "outline", shading="block", depth=4, mirror=True,
                )
            ],
        ),
        _part(
            "head",
            [
                layer(
                    "HEAD", "HEAD", round_rect(0, 0, hdw, hdh, 12 * min(hdw / 56, hdh / 56)),
                    "tint", order=1, shading="block", depth=6,
                )
            ],
        ),
    ]


def assemble_parts(sizes: Sizes, extra_ids: List[str]) -> List[dict]:
    """`body_parts(sizes)` plus [extra_ids]'s parts (from `extras.EXTRAS`), each extra replacing
    a same-named body part in place (e.g. a skirt or robe becomes "pants", stockings become
    "legs") or, if its name is new, appended at the end."""
    from tools.content_pipeline.pack.templates.avatars import extras as avatar_extras

    parts = body_parts(sizes)
    by_name = {p["part"]: i for i, p in enumerate(parts)}
    for extra_id in extra_ids:
        part = avatar_extras.EXTRAS[extra_id](sizes)
        name = part["part"]
        if name in by_name:
            parts[by_name[name]] = part
        else:
            by_name[name] = len(parts)
            parts.append(part)
    return parts
