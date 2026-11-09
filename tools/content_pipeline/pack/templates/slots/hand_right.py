"""HAND_RIGHT slot: rendered on bone ARM_R (not HAND_R), z HAND, grip at (19,64).

ARM_R, not HAND_R: the spear/sword/staff/bow/orb reach y=-68..-58 on HAND_R, outside its
expanded (1.5x) box of -57; ARM_R shares the same rotation pivot and has enough headroom.
"""
from __future__ import annotations

from typing import List

from tools.content_pipeline.pack.geometry import circle, poly, rect, round_rect, star4
from tools.content_pipeline.pack.templates.common import StyleTemplate, layer

BONE = "ARM_R"
Z = "HAND"


def _handle() -> dict:
    return layer(BONE, Z, round_rect(16, 54, 6, 20, 2), "outline", order=10, shading="flat")


def _main(d: str, shade: float = 0.2) -> dict:
    return layer(BONE, Z, d, "primary", shade=shade, order=11, pattern_clip=True)


def _sword(params: dict) -> List[dict]:
    from tools.content_pipeline.pack.geometry import join

    d = join(rect(5, 50, 28, 7), rect(14, -20, 10, 70))
    return [_handle(), _main(d)]


def _wand(params: dict) -> List[dict]:
    return [
        _handle(),
        _main(rect(16, -6, 6, 60)),
        layer(BONE, Z, star4(19, -6, 6), "secondary", order=12, shading="flat"),
    ]


def _staff(params: dict) -> List[dict]:
    return [
        _handle(),
        _main(rect(15, -16, 8, 70)),
        layer(BONE, Z, circle(19, -16, 8), "secondary", order=12, shading="flat"),
    ]


def _axe(params: dict) -> List[dict]:
    return [_handle(), _main(poly([(15, 4), (39, -6), (39, 24), (15, 14)]))]


def _hammer(params: dict) -> List[dict]:
    return [_handle(), _main(rect(5, -10, 28, 20))]


def _spear(params: dict) -> List[dict]:
    return [_handle(), _main(rect(16, -26, 6, 80))]


def _bow(params: dict) -> List[dict]:
    d = "M 19 -16 Q 37 24 19 64 Q 13 24 19 -16 Z"
    return [
        _handle(),
        layer(BONE, Z, d, "accent", order=11, stroke=("primary", 3), pattern_clip=True),
    ]


def _orb(params: dict) -> List[dict]:
    return [_handle(), _main(circle(19, -10, 10))]


STYLES: List[StyleTemplate] = [
    StyleTemplate("sword", _sword),
    StyleTemplate("wand", _wand),
    StyleTemplate("staff", _staff),
    StyleTemplate("axe", _axe),
    StyleTemplate("hammer", _hammer),
    StyleTemplate("spear", _spear),
    StyleTemplate("bow", _bow, accent=lambda p, s: "#00FFFFFF"),
    StyleTemplate("orb", _orb),
]
