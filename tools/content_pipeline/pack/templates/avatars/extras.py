"""Signature-part templates per avatar (doc/plans/026): 22 ids, each building one `_part(name,
layers)` authored in the *effective* box of its bone(s) -- see `body.Sizes`/`expand_bones`.

Face reference points, for layers placed relative to a HEAD w x h (from `LayerRenderer.drawFace`
scaled by `faceMatrix`): eyes at (0.25w, 0.4h) and (0.75w, 0.4h), about 0.11w x 0.16h; the smile
box spans x in [0.27w, 0.73w], y in [0.58h, 0.9h] (the arc sits in its lower half).

Z-bucket rule: `drawLayerList` draws the face just before the first FACE+ layer, so a PATCH
feature that must sit *under* the eyes/smile (muzzle, snout, tongue, cheeks, jaw shadow, eye
spot, stripes, chin) goes in z HEAD with `order >= 2` (the head block itself is order 1). A LINE
or NOSE feature that draws *over* the face (whiskers, nose, lashes, brows) goes in z FACE.
"""
from __future__ import annotations

from typing import Callable, Dict, List, Tuple

from tools.content_pipeline.pack.geometry import circle, ellipse, join, line, poly, rect, round_rect, star4
from tools.content_pipeline.pack.templates.avatars.body import Sizes, _part
from tools.content_pipeline.pack.templates.common import layer, line_layer

Point = Tuple[float, float]


def _mirror_pts(pts: List[Point], w: float) -> List[Point]:
    """Mirrors [pts] across the vertical centreline of a `w`-wide box: x -> w - x."""
    return [(w - x, y) for x, y in pts]


def _mirror_round_rect(x: float, y: float, w: float, h: float, r: float, box_w: float) -> str:
    return round_rect(box_w - x - w, y, w, h, r)


def _mirror_line(x1: float, y1: float, x2: float, y2: float, box_w: float) -> str:
    return line(box_w - x1, y1, box_w - x2, y2)


# --- cat ---------------------------------------------------------------------------------------

def cat_ears(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    outer_l = [(0.02 * w, 0.25 * h), (0.16 * w, -0.42 * h), (0.40 * w, 0.12 * h)]
    inner_l = [(0.09 * w, 0.2 * h), (0.17 * w, -0.25 * h), (0.32 * w, 0.12 * h)]
    return _part(
        "ears",
        [
            layer(
                "HEAD", "HEAD",
                join(poly(outer_l), poly(_mirror_pts(outer_l, w))),
                "tint", shade=-0.22, shading="flat",
            ),
            layer(
                "HEAD", "HEAD",
                join(poly(inner_l), poly(_mirror_pts(inner_l, w))),
                "secondary", shading="flat",
            ),
        ],
    )


def cat_muzzle(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    whisker_l = [(0.2 * w, 0.66 * h, -0.08 * w, 0.6 * h), (0.2 * w, 0.72 * h, -0.1 * w, 0.74 * h)]
    whiskers = join(
        *[line(*wl) for wl in whisker_l],
        *[_mirror_line(*wl, box_w=w) for wl in whisker_l],
    )
    return _part(
        "muzzle",
        [
            layer("HEAD", "HEAD", ellipse(0.5 * w, 0.72 * h, 0.24 * w, 0.16 * h), "primary", order=3, shading="flat"),
            layer(
                "HEAD", "FACE",
                poly([(0.44 * w, 0.56 * h), (0.56 * w, 0.56 * h), (0.5 * w, 0.64 * h)]),
                "secondary", shading="flat",
            ),
            line_layer("HEAD", "FACE", whiskers, "outline", width=1.5),
        ],
    )


def cat_stripes(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    return _part(
        "stripes",
        [
            layer(
                "HEAD", "HEAD",
                join(
                    poly([(0.42 * w, 0), (0.58 * w, 0), (0.5 * w, 0.2 * h)]),
                    poly([(0.22 * w, 0.02 * h), (0.32 * w, 0), (0.3 * w, 0.16 * h)]),
                    poly([(0.68 * w, 0), (0.78 * w, 0.02 * h), (0.7 * w, 0.16 * h)]),
                ),
                "accent", order=2, shading="flat",
            )
        ],
    )


def cat_tail(sizes: Sizes) -> dict:
    return _part(
        "tail",
        [
            layer(
                "TAIL", "TAIL",
                join(round_rect(0, 0, 70, 14, 7), round_rect(56, -30, 14, 44, 7)),
                "tint", shade=-0.1, shading="flat",
            ),
            layer(
                "TAIL", "TAIL",
                join(rect(14, 0, 6, 14), rect(30, 0, 6, 14), rect(46, 0, 6, 14), rect(56, -16, 14, 6)),
                "accent", order=1, shading="flat",
            ),
        ],
    )


# --- dog -----------------------------------------------------------------------------------------

def dog_ears(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    return _part(
        "ears",
        [
            layer(
                "HEAD", "HEAD_OVER",
                join(
                    round_rect(-0.16 * w, 0.05 * h, 0.26 * w, 0.78 * h, 0.1 * w),
                    round_rect(0.9 * w, 0.05 * h, 0.26 * w, 0.78 * h, 0.1 * w),
                ),
                "tint", shade=-0.3, shading="flat",
            )
        ],
    )


def dog_snout(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    return _part(
        "snout",
        [
            layer("HEAD", "HEAD", ellipse(0.5 * w, 0.74 * h, 0.22 * w, 0.17 * h), "primary", order=3, shading="flat"),
            layer(
                "HEAD", "HEAD", round_rect(0.43 * w, 0.82 * h, 0.14 * w, 0.16 * h, 0.06 * w),
                "accent", order=4, shading="flat",
            ),
            layer("HEAD", "FACE", ellipse(0.5 * w, 0.6 * h, 0.09 * w, 0.06 * h), "secondary", shading="flat"),
        ],
    )


def dog_spot(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    return _part(
        "spot",
        [layer("HEAD", "HEAD", ellipse(0.75 * w, 0.36 * h, 0.15 * w, 0.15 * h), "secondary", shade=0.3, order=2, shading="flat")],
    )


def dog_tail(sizes: Sizes) -> dict:
    return _part(
        "tail",
        [layer("TAIL", "TAIL", poly([(0, 6), (8, -24), (18, -22), (12, 8)]), "tint", shade=-0.22, shading="flat")],
    )


# --- knight --------------------------------------------------------------------------------------

def knight_pauldrons(sizes: Sizes) -> dict:
    aw, ah = sizes["ARM_L"]
    return _part(
        "pauldrons",
        [
            layer(
                "ARM_L", "ARM",
                round_rect(-0.2 * aw, -0.12 * ah, 1.4 * aw, 0.3 * ah, 0.2 * aw),
                "secondary", order=3, shading="block", depth=4, mirror=True,
            ),
            line_layer(
                "ARM_L", "ARM", line(-0.2 * aw, 0.18 * ah, 1.2 * aw, 0.18 * ah),
                "outline", width=1.5, order=4, mirror=True,
            ),
        ],
    )


def knight_breastplate(sizes: Sizes) -> dict:
    tw, th = sizes["TORSO"]
    return _part(
        "breastplate",
        [
            layer(
                "TORSO", "TORSO",
                join(
                    circle(0.15 * tw, 0.12 * th, 2.5),
                    circle(0.85 * tw, 0.12 * th, 2.5),
                    circle(0.15 * tw, 0.6 * th, 2.5),
                    circle(0.85 * tw, 0.6 * th, 2.5),
                ),
                "secondary", order=2, shading="flat",
            ),
            layer(
                "TORSO", "TORSO",
                poly([(0.1 * tw, 0), (0.28 * tw, 0), (0.9 * tw, 0.84 * th), (0.72 * tw, 0.84 * th)]),
                "accent", order=2, shading="flat",
            ),
        ],
    )


def knight_sideburns(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    return _part(
        "sideburns",
        [
            layer(
                "HEAD", "HEAD_OVER",
                join(
                    round_rect(-0.02 * w, 0.1 * h, 0.12 * w, 0.5 * h, 0.05 * w),
                    _mirror_round_rect(-0.02 * w, 0.1 * h, 0.12 * w, 0.5 * h, 0.05 * w, w),
                ),
                "primary", shading="flat",
            ),
            layer(
                "HEAD", "HEAD_OVER", round_rect(0, -0.04 * h, w, 0.18 * h, 0.08 * w),
                "primary", shading="flat",
            ),
            layer(
                "HEAD", "HEAD", round_rect(0.18 * w, 0.8 * h, 0.64 * w, 0.2 * h, 0.08 * w),
                "tint", shade=-0.15, order=2, shading="flat",
            ),
        ],
    )


# --- princess --------------------------------------------------------------------------------------

def princess_hair(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    return _part(
        "hair",
        [
            layer(
                "HEAD", "BACK", round_rect(-0.35 * w, -0.08 * h, 1.7 * w, 2.1 * h, 0.5 * w),
                "primary", shading="flat", parallax=0.9,
            ),
            layer(
                "HEAD", "HEAD_OVER",
                join(
                    round_rect(-0.12 * w, 0.05 * h, 0.2 * w, 0.95 * h, 0.1 * w),
                    _mirror_round_rect(-0.12 * w, 0.05 * h, 0.2 * w, 0.95 * h, 0.1 * w, w),
                ),
                "primary", shading="flat",
            ),
            layer(
                "HEAD", "HEAD_OVER", ellipse(0.5 * w, 0.06 * h, 0.56 * w, 0.16 * h),
                "primary", shading="flat",
            ),
        ],
    )


def princess_skirt(sizes: Sizes) -> dict:
    lw, lh = sizes["LEG_L"]
    return _part(
        "pants",
        [
            layer(
                "LEG_L", "LEGWEAR",
                poly([(0, 0), (1.05 * lw, 0), (1.9 * lw, 0.7 * lh), (0, 0.7 * lh)]),
                "secondary", mirror=True,
            ),
            line_layer(
                "LEG_L", "LEGWEAR", line(0, 0.7 * lh, 1.9 * lw, 0.7 * lh),
                "outline", width=1.5, order=1, mirror=True,
            ),
        ],
    )


def princess_face(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    lash_lines: List[str] = []
    for cx in (0.25 * w, 0.75 * w):
        lash_lines.append(line(cx - 0.08 * w, 0.31 * h, cx - 0.12 * w, 0.25 * h))
        lash_lines.append(line(cx, 0.29 * h, cx, 0.22 * h))
        lash_lines.append(line(cx + 0.08 * w, 0.31 * h, cx + 0.12 * w, 0.25 * h))
    return _part(
        "lashes",
        [
            line_layer("HEAD", "FACE", join(*lash_lines), "outline", width=1.2),
            layer(
                "HEAD", "HEAD",
                join(
                    ellipse(0.17 * w, 0.6 * h, 0.08 * w, 0.05 * h),
                    ellipse(0.83 * w, 0.6 * h, 0.08 * w, 0.05 * h),
                ),
                "secondary", shade=0.35, order=2, shading="flat",
            ),
        ],
    )


def princess_tiara(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    return _part(
        "tiara",
        [
            layer(
                "HEAD", "HEAD_OVER",
                poly(
                    [
                        (0.3 * w, 0.02 * h), (0.36 * w, -0.12 * h), (0.43 * w, 0),
                        (0.5 * w, -0.18 * h), (0.57 * w, 0), (0.64 * w, -0.12 * h), (0.7 * w, 0.02 * h),
                    ]
                ),
                "accent", order=1, shading="flat", stroke=("primary", 1),
            )
        ],
    )


# --- wizard --------------------------------------------------------------------------------------

def wizard_beard(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    return _part(
        "beard",
        [
            layer(
                "HEAD", "HEAD_OVER",
                poly(
                    [
                        (0.06 * w, 0.5 * h), (0.24 * w, 0.92 * h), (0.5 * w, 0.96 * h), (0.76 * w, 0.92 * h),
                        (0.94 * w, 0.5 * h), (1.05 * w, 1.2 * h), (0.85 * w, 1.85 * h), (0.5 * w, 2.3 * h),
                        (0.15 * w, 1.85 * h), (-0.05 * w, 1.2 * h),
                    ]
                ),
                "primary",
            )
        ],
    )


def wizard_brows(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    brow_l = [(0.1 * w, 0.24 * h), (0.4 * w, 0.2 * h), (0.4 * w, 0.27 * h), (0.12 * w, 0.3 * h)]
    return _part(
        "brows",
        [
            layer(
                "HEAD", "FACE",
                join(poly(brow_l), poly(_mirror_pts(brow_l, w))),
                "primary", shade=-0.15, shading="flat",
            ),
            layer(
                "HEAD", "FACE",
                poly([(0.47 * w, 0.38 * h), (0.53 * w, 0.38 * h), (0.6 * w, 0.66 * h), (0.44 * w, 0.66 * h)]),
                "tint", shade=-0.15, order=1, shading="flat",
            ),
        ],
    )


def wizard_robe(sizes: Sizes) -> dict:
    lw, lh = sizes["LEG_L"]
    return _part(
        "pants",
        [
            layer(
                "LEG_L", "LEGWEAR",
                poly([(0, 0), (1.1 * lw, 0), (1.45 * lw, 0.92 * lh), (0, 0.92 * lh)]),
                "skin", mirror=True,
            ),
            layer(
                "LEG_L", "LEGWEAR",
                poly([(0, 0.8 * lh), (1.4 * lw, 0.8 * lh), (1.45 * lw, 0.92 * lh), (0, 0.92 * lh)]),
                "accent", order=1, shading="flat", mirror=True,
            ),
            layer(
                "LEG_L", "LEGWEAR",
                join(star4(0.4 * lw, 0.86 * lh, 3), star4(1.0 * lw, 0.86 * lh, 3)),
                "secondary", order=2, shading="flat", mirror=True,
            ),
        ],
    )


# --- witch --------------------------------------------------------------------------------------

_WITCH_HAIR_FRACS: List[Point] = [
    (0.5, -0.15), (0.8, -0.08), (1.1, -0.12), (1.15, 0.1), (1.5, 0.15), (1.3, 0.4), (1.6, 0.6),
    (1.3, 0.8), (1.55, 1.1), (1.2, 1.15), (1.35, 1.5), (0.95, 1.3), (0.5, 1.2), (0.05, 1.3),
    (-0.35, 1.5), (-0.2, 1.15), (-0.55, 1.1), (-0.3, 0.8), (-0.6, 0.6), (-0.3, 0.4), (-0.5, 0.15),
    (-0.15, 0.1), (-0.1, -0.12), (0.2, -0.08),
]


def witch_hair(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    spiky = [(fx * w, fy * h) for fx, fy in _WITCH_HAIR_FRACS]
    return _part(
        "hair",
        [
            layer("HEAD", "BACK", poly(spiky), "primary", shading="flat", parallax=0.9),
            layer(
                "HEAD", "HEAD_OVER",
                join(
                    poly([(0, 0), (0.35 * w, 0), (0.1 * w, 0.35 * h)]),
                    poly([(0.65 * w, 0), (w, 0), (0.9 * w, 0.35 * h)]),
                    poly([(0.3 * w, -0.05 * h), (0.7 * w, -0.05 * h), (0.5 * w, 0.2 * h)]),
                ),
                "primary", shading="flat",
            ),
        ],
    )


def witch_face(sizes: Sizes) -> dict:
    w, h = sizes["HEAD"]
    return _part(
        "nose",
        [
            layer(
                "HEAD", "FACE",
                poly(
                    [
                        (0.48 * w, 0.36 * h), (0.56 * w, 0.36 * h), (0.72 * w, 0.6 * h),
                        (0.6 * w, 0.66 * h), (0.56 * w, 0.6 * h), (0.46 * w, 0.58 * h),
                    ]
                ),
                "tint", shade=-0.12,
            ),
            layer("HEAD", "FACE", circle(0.68 * w, 0.56 * h, 2), "tint", shade=-0.35, order=1, shading="flat"),
            layer(
                "HEAD", "HEAD",
                poly([(0.3 * w, 0.9 * h), (0.7 * w, 0.9 * h), (0.5 * w, 1.12 * h)]),
                "tint", order=2, shading="flat",
            ),
        ],
    )


def witch_stockings(sizes: Sizes) -> dict:
    lw, lh = sizes["LEG_L"]
    stripes: List[str] = []
    y = 0.1 * lh
    step = 0.14 * lh
    while y < 0.9 * lh:
        stripes.append(rect(0, y, lw, 0.06 * lh))
        y += step
    return _part(
        "legs",
        [
            layer("LEG_L", "LEGS", rect(0, 0, lw, lh), "accent", shading="block", depth=4, mirror=True),
            layer("LEG_L", "LEGS", join(*stripes), "primary", order=1, shading="flat", mirror=True),
        ],
    )


def witch_skirt(sizes: Sizes) -> dict:
    lw, lh = sizes["LEG_L"]
    return _part(
        "pants",
        [
            layer(
                "LEG_L", "LEGWEAR",
                poly(
                    [
                        (0, 0), (1.15 * lw, 0), (1.5 * lw, 0.42 * lh), (1.25 * lw, 0.36 * lh),
                        (1.1 * lw, 0.46 * lh), (0.85 * lw, 0.38 * lh), (0.6 * lw, 0.48 * lh),
                        (0.35 * lw, 0.38 * lh), (0, 0.46 * lh),
                    ]
                ),
                "secondary", mirror=True,
            )
        ],
    )


EXTRAS: Dict[str, Callable[[Sizes], dict]] = {
    "cat_ears": cat_ears,
    "cat_muzzle": cat_muzzle,
    "cat_stripes": cat_stripes,
    "cat_tail": cat_tail,
    "dog_ears": dog_ears,
    "dog_snout": dog_snout,
    "dog_spot": dog_spot,
    "dog_tail": dog_tail,
    "knight_pauldrons": knight_pauldrons,
    "knight_breastplate": knight_breastplate,
    "knight_sideburns": knight_sideburns,
    "princess_hair": princess_hair,
    "princess_skirt": princess_skirt,
    "princess_face": princess_face,
    "princess_tiara": princess_tiara,
    "wizard_beard": wizard_beard,
    "wizard_brows": wizard_brows,
    "wizard_robe": wizard_robe,
    "witch_hair": witch_hair,
    "witch_face": witch_face,
    "witch_stockings": witch_stockings,
    "witch_skirt": witch_skirt,
}
