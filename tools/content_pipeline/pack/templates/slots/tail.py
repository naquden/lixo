"""TAIL slot (bone TAIL, z TAIL). Every TAIL item hides the avatar's default tail part."""
from __future__ import annotations

from typing import List

from tools.content_pipeline.pack.geometry import bars, circle, ellipse, join, poly, round_rect
from tools.content_pipeline.pack.templates.common import StyleTemplate, layer, translucent

BONE = "TAIL"
Z = "TAIL"


def _main(d: str) -> dict:
    return layer(BONE, Z, d, "primary", pattern_clip=True)


def _cat(params: dict) -> List[dict]:
    return [_main(round_rect(0, 0, 50, 15, 7))]


def _dog(params: dict) -> List[dict]:
    return [_main(round_rect(0, 0, 56, 12, 6))]


def _fox(params: dict) -> List[dict]:
    return [
        _main(ellipse(30, 7, 30, 13)),
        layer(BONE, Z, circle(56, 7, 5), "accent", order=1, shading="flat"),
    ]


def _dragon(params: dict) -> List[dict]:
    spikes = join(circle(0, -10, 2.5), circle(14, -10, 2.5), circle(28, -10, 2.5))
    return [
        _main(poly([(0, 0), (50, -10), (50, 15), (0, 15)])),
        layer(BONE, Z, spikes, "secondary", order=1, shading="flat"),
    ]


def _lizard(params: dict) -> List[dict]:
    return [_main(round_rect(0, 0, 46, 10, 4))]


def _rainbow(params: dict) -> List[dict]:
    return [
        _main(round_rect(0, 0, 50, 15, 7)),
        layer(BONE, Z, bars(4, 1, 42, 13, 4), "accent", order=1, shading="flat"),
    ]


def _comet(params: dict) -> List[dict]:
    return [
        _main(ellipse(27.5, 7, 27.5, 11)),
        layer(BONE, Z, ellipse(27.5, 7, 32, 20), "accent", order=1, shading="flat"),
    ]


def _mermaid_fin(params: dict) -> List[dict]:
    return [_main(poly([(0, 0), (44, -14), (50, 7), (44, 22)]))]


STYLES: List[StyleTemplate] = [
    StyleTemplate("cat", _cat),
    StyleTemplate("dog", _dog),
    StyleTemplate("fox", _fox, accent=lambda p, s: "#FFFFFF"),
    StyleTemplate("dragon", _dragon),
    StyleTemplate("lizard", _lizard),
    StyleTemplate("rainbow", _rainbow, accent=translucent("66")),
    StyleTemplate("comet", _comet, accent=translucent("4D")),
    StyleTemplate("mermaid_fin", _mermaid_fin),
]
