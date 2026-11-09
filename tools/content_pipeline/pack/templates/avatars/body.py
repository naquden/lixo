"""STANDARD_BODY avatar parts, ears and the default tail (doc/plans/021). Avatar parts may only
use the skin/tint/eye/outline roles -- see ContentPackValidator.AVATAR_PART_ROLES."""
from __future__ import annotations

from typing import Dict, List

from tools.content_pipeline.pack.geometry import join, line, poly, rect, round_rect
from tools.content_pipeline.pack.templates.common import layer, line_layer


def _part(name: str, layers: List[dict]) -> dict:
    return {"part": name, "layers": layers}


STANDARD_BODY: List[dict] = [
    _part(
        "torso",
        [
            layer("TORSO", "TORSO", rect(0, 0, 80, 80), "skin", shading="block", depth=6),
            line_layer("TORSO", "TORSO", line(4, 74, 76, 74), "outline", width=2, order=1),
        ],
    ),
    _part(
        "arms",
        [layer("ARM_L", "ARM", rect(0, 0, 38, 48), "skin", shading="block", depth=4, mirror=True)],
    ),
    _part(
        "hands",
        [layer("HAND_L", "HAND", rect(0, 6, 38, 32), "tint", shading="block", depth=4, mirror=True)],
    ),
    _part(
        "legs",
        [layer("LEG_L", "LEGS", rect(0, 0, 40, 80), "tint", shading="block", depth=4, mirror=True)],
    ),
    _part(
        "pants",
        [
            layer(
                "LEG_L", "LEGS", rect(0, 0, 40, 62), "outline", shade=0.1, order=1,
                shading="block", depth=4, mirror=True,
            )
        ],
    ),
    _part(
        "shoes",
        [layer("LEG_L", "FOOTWEAR", rect(-3, 62, 46, 18), "outline", shading="block", depth=4, mirror=True)],
    ),
    _part(
        "head",
        [layer("HEAD", "HEAD", round_rect(0, 0, 56, 56, 12), "tint", order=1, shading="block", depth=6)],
    ),
]

EARS: Dict[str, dict] = {
    "cat": _part(
        "ears",
        [
            layer(
                "HEAD", "HEAD",
                join(poly([(2, 10), (12, -16), (22, 10)]), poly([(34, 10), (44, -16), (54, 10)])),
                "tint", shade=-0.22, shading="flat",
            )
        ],
    ),
    "dog": _part(
        "ears",
        [
            layer(
                "HEAD", "HEAD",
                join(round_rect(-4, 8, 10, 28, 4), round_rect(50, 8, 10, 28, 4)),
                "tint", shade=-0.22, shading="flat",
            )
        ],
    ),
}

DEFAULT_TAIL: dict = _part(
    "tail",
    [layer("TAIL", "TAIL", round_rect(0, 0, 50, 15, 7), "tint", shade=-0.22, shading="flat")],
)
