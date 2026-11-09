"""HAND_LEFT slot (bone HAND_L, no mirror, z HAND)."""
from __future__ import annotations

from typing import List, Optional, Tuple

from tools.content_pipeline.pack.geometry import circle, ellipse, poly, rect, round_rect
from tools.content_pipeline.pack.templates.common import StyleTemplate, layer

BONE = "HAND_L"
Z = "HAND"


def _main(d: str, stroke: Optional[Tuple[str, float]] = None) -> dict:
    return layer(BONE, Z, d, "primary", order=10, shading="flat", stroke=stroke, pattern_clip=True)


def _round_shield(params: dict) -> List[dict]:
    return [
        _main(round_rect(-2, 4, 40, 48, 6)),
        layer(BONE, Z, circle(18, 28, 5), "secondary", order=11),
    ]


def _kite(params: dict) -> List[dict]:
    return [
        _main(poly([(18, 4), (38, 18), (18, 52), (-2, 18)])),
        layer(BONE, Z, circle(18, 28, 5), "secondary", order=11),
    ]


def _book(params: dict) -> List[dict]:
    return [_main(rect(-2, 4, 36, 44), stroke=("outline", 2))]


def _lantern(params: dict) -> List[dict]:
    return [_main(ellipse(16, 26, 18, 22), stroke=("outline", 2))]


def _compass(params: dict) -> List[dict]:
    return [_main(circle(18, 24, 20), stroke=("outline", 2))]


def _hourglass(params: dict) -> List[dict]:
    return [_main(round_rect(-2, 4, 30, 46, 10), stroke=("outline", 2))]


def _torch(params: dict) -> List[dict]:
    return [
        _main(rect(12, 4, 8, 44), stroke=("outline", 2)),
        layer(BONE, Z, ellipse(16, -1, 7, 9), "accent", order=11),
    ]


def _potion(params: dict) -> List[dict]:
    return [_main(round_rect(-2, 4, 26, 36, 8), stroke=("outline", 2))]


STYLES: List[StyleTemplate] = [
    StyleTemplate("round_shield", _round_shield),
    StyleTemplate("kite", _kite),
    StyleTemplate("book", _book),
    StyleTemplate("lantern", _lantern),
    StyleTemplate("compass", _compass),
    StyleTemplate("hourglass", _hourglass),
    StyleTemplate("torch", _torch, accent=lambda p, s: "#FF7043"),
    StyleTemplate("potion", _potion),
]
