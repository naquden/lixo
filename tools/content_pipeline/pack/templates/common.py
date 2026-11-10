"""Shared layer-template plumbing for the 72 generated-slot styles and the 6 avatars
(doc/plans/021-content-pack-authoring-pipeline.md).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Tuple


def white(primary: str, secondary: str) -> str:
    return "#FFFFFF"


def translucent(alpha_hex: str) -> Callable[[str, str], str]:
    """Returns an `accent(primary, secondary) -> "#AARRGGBB"` fn using `secondary`'s RGB with
    `alpha_hex` alpha (e.g. translucent("66")("#112233", "#445566") == "#66445566")."""

    def _accent(primary: str, secondary: str) -> str:
        return "#" + alpha_hex + secondary[1:]

    return _accent


@dataclass(frozen=True)
class StyleTemplate:
    key: str
    layers: Callable[[dict], List[dict]]
    accent: Callable[[str, str], str] = white


def layer(
    bone: str,
    z: str,
    d: str,
    role: str,
    shade: float = 0,
    order: int = 0,
    shading: str = "gradient",
    depth: Optional[float] = None,
    stroke: Optional[Tuple[str, float]] = None,
    mirror: bool = False,
    pattern_clip: bool = False,
    parallax: Optional[float] = None,
) -> dict:
    """Builds a layer dict, omitting every value equal to its schema default (order 0, shading
    "gradient", mirror "none", patternClip false, fill.shade 0, parallax unset), so the written
    JSON stays small and matches `ContentLayer`'s Kotlin defaults 1:1. [parallax] overrides the
    bone/z-bucket default horizontal parallax (see `DEFAULT_PARALLAX`; BACK-bucket layers without
    one follow their bone and peek out by `BACK_PEEK_AU` under yaw),
    e.g. BACK-bucket hair uses `parallax=0.9` so it stays attached to the head under yaw."""
    out: dict = {"bone": bone, "z": z, "d": d}
    fill: dict = {"role": role}
    if shade:
        fill["shade"] = shade
    out["fill"] = fill
    if order:
        out["order"] = order
    if shading != "gradient":
        out["shading"] = shading
    if depth is not None:
        out["depth"] = depth
    if stroke is not None:
        stroke_role, stroke_width = stroke
        out["stroke"] = {"role": stroke_role, "width": stroke_width}
    if mirror:
        out["mirror"] = "x"
    if pattern_clip:
        out["patternClip"] = True
    if parallax is not None:
        out["parallax"] = parallax
    return out


def line_layer(
    bone: str,
    z: str,
    d: str,
    role: str,
    width: float,
    order: int = 0,
    mirror: bool = False,
) -> dict:
    """A zero-area stroke-only layer: flat shading, fill == stroke role (so a degenerate fill
    never gets a pattern/gradient applied to it)."""
    return layer(
        bone=bone,
        z=z,
        d=d,
        role=role,
        order=order,
        shading="flat",
        stroke=(role, width),
        mirror=mirror,
    )
