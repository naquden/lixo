"""ARMS_GLOVES slot (bone HAND_L, mirror x, z HAND)."""
from __future__ import annotations

from typing import List

from tools.content_pipeline.pack.geometry import circle, join, line, poly, rect
from tools.content_pipeline.pack.templates.common import StyleTemplate, layer, line_layer

BONE = "HAND_L"
Z = "HAND"

_MAIN_D = rect(0, 6, 38, 32)


def _main() -> dict:
    return layer(BONE, Z, _MAIN_D, "primary", shading="block", depth=4, mirror=True, pattern_clip=True)


def _gloves(params: dict) -> List[dict]:
    return [_main()]


def _gauntlet(params: dict) -> List[dict]:
    return [_main(), layer(BONE, Z, rect(-2, 6, 42, 11.2), "primary", shade=-0.4, order=1, mirror=True)]


def _bracelets(params: dict) -> List[dict]:
    return [
        _main(),
        line_layer(BONE, Z, line(0, 15.6, 38, 15.6), "secondary", width=1.5, order=1, mirror=True),
        line_layer(BONE, Z, line(0, 25.2, 38, 25.2), "secondary", width=1.5, order=1, mirror=True),
    ]


def _mittens(params: dict) -> List[dict]:
    return [_main()]


def _claws(params: dict) -> List[dict]:
    layers = [_main()]
    for x in (0, 12.67, 25.33):
        d = f"M {x} 38 L {x + 2} 44 L {x + 4} 38 Z"
        layers.append(layer(BONE, Z, d, "accent", order=1, mirror=True))
    return layers


def _wrist_wraps(params: dict) -> List[dict]:
    return [_main(), line_layer(BONE, Z, line(0, 6, 38, 38), "secondary", width=2, order=1, mirror=True)]


def _rings(params: dict) -> List[dict]:
    return [
        _main(),
        layer(BONE, Z, circle(11.4, 22, 2), "secondary", order=1, mirror=True),
        layer(BONE, Z, circle(26.6, 22, 2), "secondary", order=1, mirror=True),
    ]


def _spiked_bracers(params: dict) -> List[dict]:
    d = poly([(0, 6), (19, 0), (38, 6)])
    return [_main(), layer(BONE, Z, d, "secondary", order=1, mirror=True)]


STYLES: List[StyleTemplate] = [
    StyleTemplate("gloves", _gloves),
    StyleTemplate("gauntlet", _gauntlet),
    StyleTemplate("bracelets", _bracelets),
    StyleTemplate("mittens", _mittens),
    StyleTemplate("claws", _claws),
    StyleTemplate("wrist_wraps", _wrist_wraps),
    StyleTemplate("rings", _rings),
    StyleTemplate("spiked_bracers", _spiked_bracers),
]
