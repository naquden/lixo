"""SHOULDERS_BACK slot (bone TORSO, z BACK)."""
from __future__ import annotations

from typing import List

from tools.content_pipeline.pack.geometry import ellipse, join, line, poly, round_rect
from tools.content_pipeline.pack.templates.common import StyleTemplate, layer, line_layer

BONE = "TORSO"
Z = "BACK"


def _cape(params: dict) -> List[dict]:
    outer = poly([(-10, 2), (90, 2), (110, 110), (-30, 110)])
    inner = poly([(15, 2), (65, 2), (75, 110), (5, 110)])
    return [
        layer(BONE, Z, outer, "primary", shading="flat", pattern_clip=True),
        layer(BONE, Z, inner, "primary", shade=-0.25, order=1, shading="flat"),
    ]


def _wings(params: dict) -> List[dict]:
    d = join(poly([(40, 8), (-30, -8), (-15, 56)]), poly([(40, 8), (110, -8), (95, 56)]))
    return [layer(BONE, Z, d, "primary", pattern_clip=True)]


def _backpack(params: dict) -> List[dict]:
    return [layer(BONE, Z, round_rect(14, 2, 52, 81, 8), "primary", pattern_clip=True)]


def _scarf(params: dict) -> List[dict]:
    return [layer(BONE, Z, round_rect(-4, 2, 88, 16, 6), "primary", pattern_clip=True)]


def _feather_mantle(params: dict) -> List[dict]:
    return [layer(BONE, Z, ellipse(40, 17, 48, 15), "primary", pattern_clip=True)]


def _shell(params: dict) -> List[dict]:
    return [layer(BONE, Z, ellipse(40, 56, 42, 54), "primary", pattern_clip=True)]


def _quiver(params: dict) -> List[dict]:
    main = round_rect(58, 6, 22, 46, 4)
    return [
        layer(BONE, Z, main, "primary", shading="flat", pattern_clip=True),
        line_layer(BONE, Z, line(62, -4, 66, 10), "secondary", width=2, order=1),
        line_layer(BONE, Z, line(69, -4, 73, 10), "secondary", width=2, order=1),
        line_layer(BONE, Z, line(76, -4, 80, 10), "secondary", width=2, order=1),
    ]


def _balloon(params: dict) -> List[dict]:
    string = line(79, 2, 40, 2)
    balloon = ellipse(79, -14, 13, 16)
    return [
        line_layer(BONE, Z, string, "outline", width=1.5, order=0),
        layer(BONE, Z, balloon, "primary", order=1, shading="flat", pattern_clip=True),
    ]


STYLES: List[StyleTemplate] = [
    StyleTemplate("cape", _cape),
    StyleTemplate("wings", _wings),
    StyleTemplate("backpack", _backpack),
    StyleTemplate("scarf", _scarf),
    StyleTemplate("feather_mantle", _feather_mantle),
    StyleTemplate("shell", _shell),
    StyleTemplate("quiver", _quiver),
    StyleTemplate("balloon", _balloon),
]
