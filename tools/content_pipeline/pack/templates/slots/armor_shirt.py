"""ARMOR_SHIRT slot: main on TORSO/z TORSO_OVER, sleeves on ARM_L (mirror x)/z ARM, belt line."""
from __future__ import annotations

from typing import List

from tools.content_pipeline.pack.geometry import checker, circle, join, line, rect, round_rect
from tools.content_pipeline.pack.templates.common import StyleTemplate, layer, line_layer, translucent

TORSO = "TORSO"
TORSO_Z = "TORSO_OVER"
ARM = "ARM_L"
ARM_Z = "ARM"


def _main() -> dict:
    return layer(TORSO, TORSO_Z, rect(0, 0, 80, 80), "primary", shading="block", depth=6, pattern_clip=True)


def _sleeves() -> dict:
    return layer(ARM, ARM_Z, rect(0, 0, 38, 48), "primary", order=1, shading="block", depth=4, mirror=True)


def _belt() -> dict:
    return line_layer(TORSO, TORSO_Z, line(4, 74, 76, 74), "outline", width=2, order=9)


def _plain(params: dict) -> List[dict]:
    return [_main(), _sleeves(), _belt()]


def _plate(params: dict) -> List[dict]:
    rivets = join(circle(10, 12, 2.2), circle(10, 36, 2.2), circle(70, 12, 2.2), circle(70, 36, 2.2))
    return [
        _main(),
        _sleeves(),
        _belt(),
        layer(TORSO, TORSO_Z, rivets, "secondary", shading="flat"),
        line_layer(TORSO, TORSO_Z, line(4, 40, 76, 40), "outline", width=2),
    ]


def _robe(params: dict) -> List[dict]:
    return [_main(), _sleeves(), _belt(), layer(TORSO, TORSO_Z, round_rect(-4, 80, 88, 80, 3), "primary")]


def _tunic(params: dict) -> List[dict]:
    return [_main(), _sleeves(), _belt(), layer(TORSO, TORSO_Z, rect(0, 72, 80, 6), "secondary", shading="flat")]


def _vest(params: dict) -> List[dict]:
    return [
        _main(),
        _sleeves(),
        _belt(),
        layer(TORSO, TORSO_Z, rect(25.6, 2, 28.8, 76), "secondary", shade=0.2, shading="flat"),
    ]


def _hoodie(params: dict) -> List[dict]:
    return [
        _main(),
        _sleeves(),
        _belt(),
        layer(TORSO, TORSO_Z, round_rect(20, 60, 40, 16, 4), "primary", shade=-0.25, shading="flat"),
    ]


def _dress(params: dict) -> List[dict]:
    return [_main(), _sleeves(), _belt(), layer(TORSO, TORSO_Z, round_rect(-14, 74, 108, 26, 3), "primary")]


def _chainmail(params: dict) -> List[dict]:
    return [
        _main(),
        _sleeves(),
        _belt(),
        layer(TORSO, TORSO_Z, checker(0, 0, 80, 80, 20), "accent"),
    ]


STYLES: List[StyleTemplate] = [
    StyleTemplate("plain", _plain),
    StyleTemplate("plate", _plate),
    StyleTemplate("robe", _robe),
    StyleTemplate("tunic", _tunic),
    StyleTemplate("vest", _vest),
    StyleTemplate("hoodie", _hoodie),
    StyleTemplate("dress", _dress),
    StyleTemplate("chainmail", _chainmail, accent=translucent("66")),
]
