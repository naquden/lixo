"""SHOES slot (bone LEG_L, mirror x, z FOOTWEAR)."""
from __future__ import annotations

from typing import List

from tools.content_pipeline.pack.geometry import circle, ellipse, line, rect, round_rect
from tools.content_pipeline.pack.templates.common import StyleTemplate, layer, line_layer

BONE = "LEG_L"
Z = "FOOTWEAR"


def _main() -> dict:
    return layer(BONE, Z, rect(-3, 62, 46, 18), "primary", shading="block", depth=4, mirror=True, pattern_clip=True)


def _plain(params: dict) -> List[dict]:
    return [_main()]


def _sneakers(params: dict) -> List[dict]:
    return [
        _main(),
        layer(BONE, Z, rect(-3, 74.6, 46, 5.4), "accent", mirror=True),
        line_layer(BONE, Z, line(17, 62, 23, 71), "secondary", width=1.5, mirror=True),
    ]


def _sandals(params: dict) -> List[dict]:
    return [_main(), line_layer(BONE, Z, line(-3, 62, 43, 69.2), "secondary", width=2.5, mirror=True)]


def _rocket_boots(params: dict) -> List[dict]:
    return [
        _main(),
        layer(BONE, Z, ellipse(20, 82, 12, 5), "accent", order=-1, shading="flat", mirror=True),
    ]


def _clogs(params: dict) -> List[dict]:
    return [
        _main(),
        layer(BONE, Z, round_rect(-3, 72.8, 46, 7.2, 2), "primary", shade=-0.4, shading="flat", mirror=True),
    ]


def _curled_toe(params: dict) -> List[dict]:
    return [_main(), layer(BONE, Z, circle(43, 62, 3), "secondary", mirror=True)]


STYLES: List[StyleTemplate] = [
    StyleTemplate("boots", _plain),
    StyleTemplate("sneakers", _sneakers),
    StyleTemplate("slippers", _plain),
    StyleTemplate("tall_boots", _plain),
    StyleTemplate("sandals", _sandals),
    StyleTemplate("rocket_boots", _rocket_boots, accent=lambda p, s: "#FF7043"),
    StyleTemplate("clogs", _clogs),
    StyleTemplate("curled_toe", _curled_toe),
]
