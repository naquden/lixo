"""Pure path-string helpers for content pack layer templates (doc/plans/021).

Every number that ends up in a `d` string is rounded to 2 decimals via `fmt()`. Only M/L/C/Q/Z
path commands are ever emitted -- no arcs -- because the conservative bounding-box check
(validate_pack.layer_bounds, a port of ContentPaths.kt) is easiest to keep correct for cubic/quad
control points, whose convex hull always contains the curve, versus arcs' rotated-ellipse
extents.
"""
from __future__ import annotations

from typing import Iterable, List, Tuple

Point = Tuple[float, float]

# Cubic bezier "kappa" constant for approximating a quarter circle/ellipse arc with k * r control
# point offsets from the tangent point.
KAPPA = 0.5523


def fmt(v: float) -> str:
    """Rounds `v` to 2 decimals, strips trailing zeros, and never emits "-0"."""
    r = round(float(v) + 0.0, 2)
    if r == 0:
        r = 0.0
    if r == int(r):
        s = str(int(r))
    else:
        s = f"{r:.2f}".rstrip("0").rstrip(".")
    if s == "-0":
        s = "0"
    return s


def _tok(*vals: float) -> str:
    return " ".join(fmt(v) for v in vals)


def rect(x: float, y: float, w: float, h: float) -> str:
    return f"M {_tok(x, y)} L {_tok(x + w, y)} L {_tok(x + w, y + h)} L {_tok(x, y + h)} Z"


def round_rect(x: float, y: float, w: float, h: float, r: float) -> str:
    """A rounded rect via cubic corners (k=KAPPA), so its conservative bounds equal the plain
    rect's bounds (every control point lies on the rect's own edges)."""
    r = min(r, w / 2, h / 2)
    k = r * KAPPA
    parts: List[str] = []
    parts.append(f"M {_tok(x + r, y)}")
    parts.append(f"L {_tok(x + w - r, y)}")
    parts.append(
        f"C {_tok(x + w - r + k, y)} {_tok(x + w, y + r - k)} {_tok(x + w, y + r)}"
    )
    parts.append(f"L {_tok(x + w, y + h - r)}")
    parts.append(
        f"C {_tok(x + w, y + h - r + k)} {_tok(x + w - r + k, y + h)} {_tok(x + w - r, y + h)}"
    )
    parts.append(f"L {_tok(x + r, y + h)}")
    parts.append(
        f"C {_tok(x + r - k, y + h)} {_tok(x, y + h - r + k)} {_tok(x, y + h - r)}"
    )
    parts.append(f"L {_tok(x, y + r)}")
    parts.append(
        f"C {_tok(x, y + r - k)} {_tok(x + r - k, y)} {_tok(x + r, y)}"
    )
    parts.append("Z")
    return " ".join(parts)


def ellipse(cx: float, cy: float, rx: float, ry: float) -> str:
    """4 cubic quarter-arcs; every control point lies on the bbox [cx-rx,cx+rx]x[cy-ry,cy+ry]."""
    k_x = rx * KAPPA
    k_y = ry * KAPPA
    top = (cx, cy - ry)
    right = (cx + rx, cy)
    bottom = (cx, cy + ry)
    left = (cx - rx, cy)
    d = (
        f"M {_tok(*top)} "
        f"C {_tok(top[0] + k_x, top[1])} {_tok(right[0], right[1] - k_y)} {_tok(*right)} "
        f"C {_tok(right[0], right[1] + k_y)} {_tok(bottom[0] + k_x, bottom[1])} {_tok(*bottom)} "
        f"C {_tok(bottom[0] - k_x, bottom[1])} {_tok(left[0], left[1] + k_y)} {_tok(*left)} "
        f"C {_tok(left[0], left[1] - k_y)} {_tok(top[0] - k_x, top[1])} {_tok(*top)} "
        "Z"
    )
    return d


def circle(cx: float, cy: float, r: float) -> str:
    return ellipse(cx, cy, r, r)


def poly(pts: Iterable[Point]) -> str:
    pts = list(pts)
    if not pts:
        raise ValueError("poly() needs at least one point")
    parts = [f"M {_tok(*pts[0])}"]
    for p in pts[1:]:
        parts.append(f"L {_tok(*p)}")
    parts.append("Z")
    return " ".join(parts)


def line(x1: float, y1: float, x2: float, y2: float) -> str:
    return f"M {_tok(x1, y1)} L {_tok(x2, y2)}"


def star4(cx: float, cy: float, r: float) -> str:
    """An 8-point star: r alternating with 0.4r, angle = pi*i/4 - pi/2."""
    import math

    pts: List[Point] = []
    for i in range(8):
        radius = r if i % 2 == 0 else r * 0.4
        angle = math.pi * i / 4 - math.pi / 2
        pts.append((cx + radius * math.cos(angle), cy + radius * math.sin(angle)))
    return poly(pts)


def bars(x: float, y: float, w: float, h: float, n: int) -> str:
    """`n` vertical stripe rects, as subpaths of one path."""
    stripe_w = w / (2 * n - 1) if n > 1 else w
    parts: List[str] = []
    for i in range(n):
        sx = x + i * 2 * stripe_w
        parts.append(rect(sx, y, stripe_w, h))
    return join(*parts)


def checker(x: float, y: float, w: float, h: float, step: float) -> str:
    """One path with the `(row+col) % 2 == 0` squares of a checkerboard."""
    cols = int(round(w / step))
    rows = int(round(h / step))
    parts: List[str] = []
    for row in range(rows):
        for col in range(cols):
            if (row + col) % 2 == 0:
                parts.append(rect(x + col * step, y + row * step, step, step))
    return join(*parts)


def join(*d: str) -> str:
    """Concatenates several path strings into one multi-subpath `d` string."""
    parts = [p for p in d if p]
    return " ".join(parts)
