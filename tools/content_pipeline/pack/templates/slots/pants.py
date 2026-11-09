"""PANTS slot (bone LEG_L, mirror x, z LEGWEAR)."""
from __future__ import annotations

from typing import List

from tools.content_pipeline.pack.geometry import bars, checker, ellipse, line, rect, round_rect
from tools.content_pipeline.pack.templates.common import StyleTemplate, layer, line_layer, translucent

BONE = "LEG_L"
Z = "LEGWEAR"
H_FULL = 62
H_SHORTS = 27.9


def _main(h: float = H_FULL) -> dict:
    return layer(BONE, Z, rect(0, 0, 40, h), "primary", shading="block", depth=4, mirror=True, pattern_clip=True)


def _trousers(params: dict) -> List[dict]:
    return [_main()]


def _shorts(params: dict) -> List[dict]:
    return [_main(H_SHORTS)]


def _greaves(params: dict) -> List[dict]:
    return [_main(), line_layer(BONE, Z, line(0, 24.8, 40, 24.8), "secondary", width=2, mirror=True)]


def _skirt(params: dict) -> List[dict]:
    return [_main(), layer(BONE, Z, round_rect(-44, 0, 88, 49.6, 3), "primary", order=1)]


def _striped_leggings(params: dict) -> List[dict]:
    return [_main(), layer(BONE, Z, bars(2, 2, 36, 58, 4), "accent", mirror=True)]


def _cargo(params: dict) -> List[dict]:
    return [_main(), layer(BONE, Z, rect(8, 18.6, 12, 12), "accent", mirror=True)]


def _kilt(params: dict) -> List[dict]:
    return [_main(), layer(BONE, Z, checker(0, 0, 40, 62, 10), "accent", mirror=True)]


def _puffy(params: dict) -> List[dict]:
    return [_main(), layer(BONE, Z, ellipse(20, 15.5, 26, 15.5), "primary", order=1, shading="flat", mirror=True)]


STYLES: List[StyleTemplate] = [
    StyleTemplate("trousers", _trousers),
    StyleTemplate("shorts", _shorts),
    StyleTemplate("greaves", _greaves),
    StyleTemplate("skirt", _skirt),
    StyleTemplate("striped_leggings", _striped_leggings, accent=translucent("66")),
    StyleTemplate("cargo", _cargo, accent=translucent("80")),
    StyleTemplate("kilt", _kilt, accent=translucent("66")),
    StyleTemplate("puffy", _puffy),
]
