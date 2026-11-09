"""HAT slot (bone HEAD, z HAT). 8 styles ported from Avatar3DController's hat `when` branch."""
from __future__ import annotations

from typing import List

from tools.content_pipeline.pack.geometry import circle, ellipse, join, poly, rect, round_rect
from tools.content_pipeline.pack.templates.common import StyleTemplate, layer

BONE = "HEAD"
Z = "HAT"

_BRIM = round_rect(-10, -10, 76, 12, 3)


def _brim() -> dict:
    return layer(BONE, Z, _BRIM, "primary", shading="block", depth=4)


def _crown(d: str) -> dict:
    return layer(BONE, Z, d, "primary", order=1, stroke=("outline", 1), pattern_clip=True)


def _cone(params: dict) -> List[dict]:
    return [_brim(), _crown(poly([(28, -56), (8, -10), (48, -10)]))]


def _crown_zigzag(params: dict) -> List[dict]:
    pts = [
        (6, -10), (11.5, -36), (17, -10), (22.5, -36), (28, -10),
        (33.5, -36), (39, -10), (44.5, -36), (50, -10),
    ]
    return [_brim(), _crown(poly(pts))]


def _knight_dome(params: dict) -> List[dict]:
    return [_brim(), _crown(ellipse(28, -32, 30, 24))]


def _beanie(params: dict) -> List[dict]:
    return [
        _brim(),
        _crown(ellipse(28, -25, 24, 19)),
        layer(BONE, Z, circle(28, -44, 7), "secondary", order=2, shading="flat"),
    ]


def _top_hat(params: dict) -> List[dict]:
    return [
        _brim(),
        _crown(rect(10, -58, 36, 48)),
        layer(BONE, Z, rect(-2, -14, 60, 6), "primary", order=2, shading="flat"),
    ]


def _cap(params: dict) -> List[dict]:
    return [
        _brim(),
        _crown(ellipse(28, -24, 26, 16)),
        layer(BONE, Z, ellipse(39, -19, 13, 5), "primary", order=2, shading="flat"),
    ]


def _hood(params: dict) -> List[dict]:
    d = "M -4 -10 C -4 -50 60 -50 60 -10 L 36 -2 L 20 -2 Z"
    return [_brim(), _crown(d)]


def _horned_dome(params: dict) -> List[dict]:
    horns = join(ellipse(-5, -18, 5, 10), ellipse(61, -18, 5, 10))
    return [
        _brim(),
        _crown(ellipse(28, -29, 28, 21)),
        layer(BONE, Z, horns, "secondary", shade=0.1, order=2, shading="flat"),
    ]


STYLES: List[StyleTemplate] = [
    StyleTemplate("cone", _cone),
    StyleTemplate("crown", _crown_zigzag),
    StyleTemplate("knight_dome", _knight_dome),
    StyleTemplate("beanie", _beanie),
    StyleTemplate("top_hat", _top_hat),
    StyleTemplate("cap", _cap),
    StyleTemplate("hood", _hood),
    StyleTemplate("horned_dome", _horned_dome),
]
