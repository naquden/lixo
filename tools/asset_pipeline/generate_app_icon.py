#!/usr/bin/env python3
"""
Generates Lixo's app icons for both Android (Adaptive Icon VectorDrawables + legacy/round mipmap PNGs)
and iOS (AppIcon.appiconset 1024x1024 + standard iOS sizes).

Visual motif:
- Lixo's signature blocky 3D Low-Poly Cat-Adventurer (from Avatar3DController & Models.AvatarType.CAT)
- Wearing the Lixo Green (#58CC02) wizard hat with a gold band (#FFC800) and gleaming star emblem
- Resting paws on an open magical storybook with highlighted reading lines and a golden bookmark ribbon
- Set against a rich Lixo Sky-to-Royal Blue (#1CB0F6 -> #0958A8) backdrop with magic reading sparkles.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
ANDROID_RES = ROOT / "androidApp" / "src" / "main" / "res"
IOS_ASSETS = ROOT / "iosApp" / "iosApp" / "Assets.xcassets"


def hex_rgba(hex_str: str, alpha: int = 255) -> tuple[int, int, int, int]:
    h = hex_str.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), alpha)


def star_polygon(
    cx: float,
    cy: float,
    r_outer: float,
    r_inner: float,
    points: int = 5,
    angle_offset_deg: float = -90.0,
) -> list[tuple[float, float]]:
    pts: list[tuple[float, float]] = []
    for i in range(points * 2):
        r = r_outer if i % 2 == 0 else r_inner
        angle = math.radians(angle_offset_deg + i * (180.0 / points))
        pts.append((cx + math.cos(angle) * r, cy + math.sin(angle) * r))
    return pts


def sparkle_polygon(cx: float, cy: float, r_outer: float, r_inner: float = 0.0) -> list[tuple[float, float]]:
    if r_inner <= 0.0:
        r_inner = r_outer * 0.26
    return star_polygon(cx, cy, r_outer, r_inner, points=4, angle_offset_deg=-90.0)


def draw_background(size: int) -> Image.Image:
    """Renders the 108x108 coordinate-space background at `size x size` pixels."""
    img = Image.new("RGBA", (size, size), (11, 110, 196, 255))
    px = img.load()

    top_c = (28, 176, 246)     # Lixo Blue #1CB0F6
    mid_c = (17, 138, 218)     # #118ADA
    bot_c = (9, 88, 168)       # Deep Royal #0958A8
    glow_c = (120, 226, 255)   # Soft cyan-sky center glow

    cx = size * 0.5
    cy = size * 0.49
    max_r = size * 0.46

    for y in range(size):
        ty = y / max(1, size - 1)
        if ty < 0.5:
            u = ty * 2.0
            br = top_c[0] + (mid_c[0] - top_c[0]) * u
            bg = top_c[1] + (mid_c[1] - top_c[1]) * u
            bb = top_c[2] + (mid_c[2] - top_c[2]) * u
        else:
            u = (ty - 0.5) * 2.0
            br = mid_c[0] + (bot_c[0] - mid_c[0]) * u
            bg = mid_c[1] + (bot_c[1] - mid_c[1]) * u
            bb = mid_c[2] + (bot_c[2] - mid_c[2]) * u

        for x in range(size):
            dx = x - cx
            dy = y - cy
            d = math.hypot(dx, dy)
            if d < max_r:
                g = (1.0 - d / max_r) ** 2 * 0.34
                r = int(min(255, br * (1.0 - g) + glow_c[0] * g))
                gr = int(min(255, bg * (1.0 - g) + glow_c[1] * g))
                b = int(min(255, bb * (1.0 - g) + glow_c[2] * g))
            else:
                r, gr, b = int(br), int(bg), int(bb)
            px[x, y] = (r, gr, b, 255)

    # Soft concentric halo circles behind the character
    overlay = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    odraw = ImageDraw.Draw(overlay, "RGBA")
    s = size / 108.0

    odraw.ellipse(
        [(54 - 31) * s, (52 - 31) * s, (54 + 31) * s, (52 + 31) * s],
        fill=(255, 255, 255, 18),
    )
    odraw.ellipse(
        [(54 - 23) * s, (52 - 23) * s, (54 + 23) * s, (52 + 23) * s],
        fill=(255, 255, 255, 22),
    )

    return Image.alpha_composite(img, overlay)


def draw_foreground(size: int) -> Image.Image:
    """
    Renders the 108x108 coordinate-space foreground at `size x size` pixels (transparent background).
    All key artwork stays well inside the Android 66x66 safe zone (coords 21..87).
    """
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    s = size / 108.0

    def pts(coords: list[tuple[float, float]]) -> list[tuple[float, float]]:
        return [(x * s, y * s) for (x, y) in coords]

    def rect(x1: float, y1: float, x2: float, y2: float) -> list[float]:
        return [x1 * s, y1 * s, x2 * s, y2 * s]

    # 0. Drop shadow under the character & book for strong 3D pop
    shadow_layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sdraw = ImageDraw.Draw(shadow_layer, "RGBA")
    sdraw.ellipse(rect(24, 76.5, 84, 86.5), fill=(4, 38, 82, 115))
    sdraw.rounded_rectangle(rect(34.5, 39, 74.5, 73), radius=6 * s, fill=(4, 38, 82, 80))
    shadow_layer = shadow_layer.filter(ImageFilter.GaussianBlur(radius=2.2 * s))
    img = Image.alpha_composite(img, shadow_layer)

    draw = ImageDraw.Draw(img, "RGBA")

    # 1. Royal Purple Adventurer Cape behind torso (Lixo Purple #9B59B6 / #8E44AD)
    draw.polygon(
        pts([(37, 58), (71, 58), (77.5, 74), (30.5, 74)]),
        fill=hex_rgba("#8E44AD"),
    )
    draw.polygon(
        pts([(39, 58), (69, 58), (74, 73), (34, 73)]),
        fill=hex_rgba("#9B59B6"),
    )

    # 2. Blocky 3D Blue Adventurer Tunic / Shoulders (y = 59..73)
    draw.rounded_rectangle(rect(39, 59, 71, 73), radius=3.5 * s, fill=hex_rgba("#147EB5"))
    draw.rounded_rectangle(rect(37, 59, 68, 73), radius=3.5 * s, fill=hex_rgba("#1CB0F6"))
    draw.polygon(pts([(47.5, 59.5), (57.5, 59.5), (52.5, 65)]), fill=hex_rgba("#FFC800"))

    # 3. Pointed Cat Ears (peeking out above the wizard hat brim on left & right)
    # Left ear
    draw.polygon(pts([(33.5, 42), (37.5, 28.5), (46.5, 40)]), fill=hex_rgba("#C97800"))
    draw.polygon(pts([(32.5, 42), (36.5, 28.5), (45.5, 40)]), fill=hex_rgba("#F59E0B"))
    draw.polygon(pts([(34.8, 40.5), (37.2, 32.0), (42.8, 39.5)]), fill=hex_rgba("#FF7675"))

    # Right ear
    draw.polygon(pts([(62.5, 40), (71.5, 28.5), (75.5, 42)]), fill=hex_rgba("#B86B00"))
    draw.polygon(pts([(61.5, 40), (70.5, 28.5), (74.5, 42)]), fill=hex_rgba("#F59E0B"))
    draw.polygon(pts([(64.2, 39.5), (69.8, 32.0), (72.2, 40.5)]), fill=hex_rgba("#FF7675"))

    # 4. Main Roblox-Style 3D Blocky Cat Head (x = 35.5..72, y = 39..64)
    # Right-side 3D parallax bevel block (signature Lixo drawBlock style)
    draw.rounded_rectangle(rect(39, 39, 72.5, 64.2), radius=5.8 * s, fill=hex_rgba("#CC7A00"))
    # Bottom lip bevel
    draw.rounded_rectangle(rect(35.5, 41, 69.5, 65.0), radius=5.8 * s, fill=hex_rgba("#D98200"))
    # Front head face block
    draw.rounded_rectangle(rect(35.5, 39, 69.5, 63.2), radius=5.8 * s, fill=hex_rgba("#FFB01F"))
    # Inner lighter face highlight
    draw.rounded_rectangle(rect(36.8, 40.0, 68.2, 61.6), radius=5.0 * s, fill=hex_rgba("#FFC442"))
    # Top-edge blocky specular highlight strip (signature Lixo drawBlock highlight)
    draw.rounded_rectangle(rect(38.0, 40.0, 67.0, 42.8), radius=1.8 * s, fill=hex_rgba("#FFE082"))

    # Warm tiger/cat forehead stripes
    draw.rounded_rectangle(rect(51.0, 40.2, 54.0, 45.4), radius=1.3 * s, fill=hex_rgba("#E08600"))
    draw.rounded_rectangle(rect(46.2, 40.6, 48.8, 44.4), radius=1.1 * s, fill=hex_rgba("#E08600"))
    draw.rounded_rectangle(rect(56.2, 40.6, 58.8, 44.4), radius=1.1 * s, fill=hex_rgba("#E08600"))

    # 5. Expressive Face Details (Warm Coral Blushes, Eyes, Catchlights, Nose, Whiskers, Smile)
    draw.ellipse(rect(38.2, 52.2, 43.4, 55.8), fill=hex_rgba("#FF7E6B"))
    draw.ellipse(rect(61.6, 52.2, 66.8, 55.8), fill=hex_rgba("#FF7E6B"))

    # Left & Right oval eyes (#1E272E)
    draw.ellipse(rect(42.2, 45.8, 47.6, 53.0), fill=hex_rgba("#1E272E"))
    draw.ellipse(rect(57.4, 45.8, 62.8, 53.0), fill=hex_rgba("#1E272E"))

    # Crisp white eye catchlights (big top-left + small bottom-right)
    draw.ellipse(rect(43.2, 46.8, 45.6, 49.2), fill=hex_rgba("#FFFFFF"))
    draw.ellipse(rect(45.4, 50.4, 46.7, 51.7), fill=hex_rgba("#FFFFFF"))

    draw.ellipse(rect(58.4, 46.8, 60.8, 49.2), fill=hex_rgba("#FFFFFF"))
    draw.ellipse(rect(60.6, 50.4, 61.9, 51.7), fill=hex_rgba("#FFFFFF"))

    # Cute triangular cat nose
    draw.polygon(pts([(50.6, 51.8), (54.4, 51.8), (52.5, 54.2)]), fill=hex_rgba("#1E272E"))

    # Happy open cat mouth with cute pink tongue peek
    draw.chord(rect(48.8, 52.5, 56.2, 58.6), start=0, end=180, fill=hex_rgba("#1E272E"))
    draw.chord(rect(50.1, 54.5, 54.9, 58.5), start=0, end=180, fill=hex_rgba("#FF7675"))
    # Upper W-lip arcs
    draw.arc(rect(46.8, 51.4, 52.7, 56.8), start=15, end=165, fill=hex_rgba("#1E272E"), width=max(1, int(1.8 * s)))
    draw.arc(rect(52.3, 51.4, 58.2, 56.8), start=15, end=165, fill=hex_rgba("#1E272E"), width=max(1, int(1.8 * s)))

    # Whiskers on left and right cheeks
    draw.line(pts([(34.0, 51.2), (38.8, 52.3)]), fill=hex_rgba("#995200"), width=max(1, int(1.4 * s)))
    draw.line(pts([(34.0, 54.8), (38.8, 54.2)]), fill=hex_rgba("#995200"), width=max(1, int(1.4 * s)))
    draw.line(pts([(66.2, 52.3), (71.0, 51.2)]), fill=hex_rgba("#995200"), width=max(1, int(1.4 * s)))
    draw.line(pts([(66.2, 54.2), (71.0, 54.8)]), fill=hex_rgba("#995200"), width=max(1, int(1.4 * s)))

    # 6. Iconic Lixo Green Wizard / Adventurer Hat (y = 20.5..41.5)
    # Pointed wizard cone with playful right-leaning tip
    # 3D right shadow face (#46A302)
    draw.polygon(
        pts([(37.5, 38.0), (54.2, 20.5), (59.2, 24.5), (69.0, 38.0)]),
        fill=hex_rgba("#46A302"),
    )
    # Main front cone face (#58CC02)
    draw.polygon(
        pts([(37.5, 38.0), (54.2, 20.5), (65.5, 38.0)]),
        fill=hex_rgba("#58CC02"),
    )
    # Left cone specular highlight (#76DF20)
    draw.polygon(
        pts([(39.0, 38.0), (54.2, 20.5), (44.8, 38.0)]),
        fill=hex_rgba("#76DF20"),
    )

    # Golden hat band across the base of the cone
    draw.polygon(
        pts([(37.0, 34.2), (68.5, 34.2), (69.5, 38.2), (36.0, 38.2)]),
        fill=hex_rgba("#E5B400"),
    )
    draw.polygon(
        pts([(37.0, 34.2), (65.8, 34.2), (66.8, 38.2), (36.0, 38.2)]),
        fill=hex_rgba("#FFC800"),
    )

    # Tactile 3D Hat Brim (x = 29.5..77.5, y = 36.5..42.0)
    draw.rounded_rectangle(rect(30.5, 37.6, 77.5, 42.0), radius=2.6 * s, fill=hex_rgba("#3B8C00"))
    draw.rounded_rectangle(rect(29.5, 36.4, 75.8, 40.7), radius=2.6 * s, fill=hex_rgba("#58CC02"))
    draw.rounded_rectangle(rect(31.5, 36.8, 73.8, 38.2), radius=1.2 * s, fill=hex_rgba("#89E219"))

    # Gleaming Golden Star Badge on the Wizard Hat (#FFC800 + #FFF7D6 highlight)
    hat_star_shadow = star_polygon(53.4, 30.6, 5.6, 2.4)
    draw.polygon(pts(hat_star_shadow), fill=hex_rgba("#D99B00"))
    hat_star = star_polygon(52.8, 30.0, 5.6, 2.4)
    draw.polygon(pts(hat_star), fill=hex_rgba("#FFC800"))
    hat_star_inner = star_polygon(52.5, 29.7, 3.2, 1.4)
    draw.polygon(pts(hat_star_inner), fill=hex_rgba("#FFF7D6"))

    # 7. Open Magical Storybook at the bottom (x = 28.0..80.0, y = 65..83)
    # Book outer cover 3D bottom lip (#3B8C00 & #58CC02)
    draw.polygon(
        pts([(28.0, 71.0), (54.0, 75.4), (80.0, 71.0), (80.0, 78.6), (54.0, 83.0), (28.0, 78.6)]),
        fill=hex_rgba("#3B8C00"),
    )
    draw.polygon(
        pts([(28.0, 69.4), (54.0, 73.8), (80.0, 69.4), (80.0, 76.8), (54.0, 81.2), (28.0, 76.8)]),
        fill=hex_rgba("#58CC02"),
    )
    # Gold corner trims on the book cover
    draw.polygon(pts([(28.0, 69.4), (33.0, 70.3), (33.0, 77.7), (28.0, 76.8)]), fill=hex_rgba("#FFC800"))
    draw.polygon(pts([(75.0, 70.3), (80.0, 69.4), (80.0, 76.8), (75.0, 77.7)]), fill=hex_rgba("#FFC800"))

    # Book white pages (left page & right page angling into center spine)
    draw.polygon(
        pts([(30.0, 67.4), (54.0, 72.3), (78.0, 67.4), (78.0, 74.8), (54.0, 79.6), (30.0, 74.8)]),
        fill=hex_rgba("#D6EAF8"),
    )
    draw.polygon(
        pts([(30.0, 65.5), (54.0, 70.4), (54.0, 78.0), (30.0, 73.3)]),
        fill=hex_rgba("#FFFFFF"),
    )
    draw.polygon(
        pts([(54.0, 70.4), (78.0, 65.5), (78.0, 73.3), (54.0, 78.0)]),
        fill=hex_rgba("#F2F9FD"),
    )
    draw.line(pts([(54.0, 70.2), (54.0, 78.2)]), fill=hex_rgba("#A9CCE3"), width=max(1, int(1.5 * s)))

    # Highlighted reading lines on the pages (realtime word highlighting!)
    draw.line(pts([(33.8, 68.5), (42.5, 70.3)]), fill=hex_rgba("#58CC02"), width=max(1, int(2.1 * s)))
    draw.line(pts([(44.4, 70.7), (50.2, 71.8)]), fill=hex_rgba("#FFC800"), width=max(1, int(2.1 * s)))
    draw.line(pts([(33.8, 71.5), (48.5, 74.4)]), fill=hex_rgba("#84D8FF"), width=max(1, int(1.7 * s)))

    draw.line(pts([(57.8, 71.8), (65.6, 70.3)]), fill=hex_rgba("#58CC02"), width=max(1, int(2.1 * s)))
    draw.line(pts([(67.6, 69.9), (74.2, 68.5)]), fill=hex_rgba("#84D8FF"), width=max(1, int(1.7 * s)))
    draw.line(pts([(57.8, 74.4), (73.5, 71.3)]), fill=hex_rgba("#84D8FF"), width=max(1, int(1.7 * s)))

    # Golden Bookmark Ribbon hanging from the center spine (#FF9600 / #FFC800)
    draw.polygon(
        pts([(52.0, 77.2), (56.0, 77.2), (56.4, 84.8), (54.0, 82.8), (51.6, 84.8)]),
        fill=hex_rgba("#FF9600"),
    )
    draw.polygon(
        pts([(52.0, 77.2), (55.0, 77.2), (55.3, 83.8), (54.0, 82.8), (51.6, 84.8)]),
        fill=hex_rgba("#FFC800"),
    )

    # Cute 3D cat paws resting on the top edge of the open book
    draw.rounded_rectangle(rect(34.2, 63.4, 41.8, 68.6), radius=2.5 * s, fill=hex_rgba("#D98200"))
    draw.rounded_rectangle(rect(34.2, 62.8, 41.2, 67.8), radius=2.5 * s, fill=hex_rgba("#FFB01F"))
    draw.rounded_rectangle(rect(66.2, 63.4, 73.8, 68.6), radius=2.5 * s, fill=hex_rgba("#D98200"))
    draw.rounded_rectangle(rect(66.2, 62.8, 73.2, 67.8), radius=2.5 * s, fill=hex_rgba("#FFB01F"))

    # 8. Magic Reading Sparkles around the hat & character (strictly within r <= 32 circular safe zone)
    draw.polygon(pts(sparkle_polygon(41.5, 24.0, 3.6)), fill=hex_rgba("#FFC800"))
    draw.polygon(pts(sparkle_polygon(41.5, 24.0, 1.8)), fill=hex_rgba("#FFF7D6"))

    draw.polygon(pts(sparkle_polygon(66.8, 24.0, 4.0)), fill=hex_rgba("#FFC800"))
    draw.polygon(pts(sparkle_polygon(66.8, 24.0, 2.0)), fill=hex_rgba("#FFF7D6"))

    draw.polygon(pts(sparkle_polygon(26.5, 48.5, 3.4)), fill=hex_rgba("#FFC800"))
    draw.polygon(pts(sparkle_polygon(26.5, 48.5, 1.7)), fill=hex_rgba("#FFF7D6"))

    draw.polygon(pts(sparkle_polygon(81.0, 48.0, 3.4)), fill=hex_rgba("#FFC800"))
    draw.polygon(pts(sparkle_polygon(81.0, 48.0, 1.7)), fill=hex_rgba("#FFF7D6"))

    return img


def compose_framed_icon(size: int) -> Image.Image:
    """
    Composes the visible icon area (cropping the outer adaptive bleed margin so the
    character + storybook fill ~78% of the frame on iOS and legacy Android mipmaps
    with clean breathing room inside both squircle and circle masks)
    at 4x supersampling and downscales with Lanczos.
    """
    # Render full 108-unit space at high resolution, then crop the central 78x78 window (coords 15..93)
    full_res = int(size * 4 * (108.0 / 78.0))
    bg = draw_background(full_res)
    fg = draw_foreground(full_res)
    combined = Image.alpha_composite(bg, fg)

    margin = int(full_res * (15.0 / 108.0))
    span = full_res - 2 * margin
    cropped = combined.crop((margin, margin, margin + span, margin + span))
    return cropped.resize((size, size), Image.Resampling.LANCZOS)


def apply_rounded_mask(img: Image.Image, radius_fraction: float) -> Image.Image:
    size = img.width
    ss = size * 4
    mask = Image.new("L", (ss, ss), 0)
    mdraw = ImageDraw.Draw(mask)
    r = int(ss * radius_fraction)
    mdraw.rounded_rectangle([0, 0, ss - 1, ss - 1], radius=r, fill=255)
    mask = mask.resize((size, size), Image.Resampling.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def apply_circle_mask(img: Image.Image, inset_fraction: float = 0.03) -> Image.Image:
    size = img.width
    ss = size * 4
    mask = Image.new("L", (ss, ss), 0)
    mdraw = ImageDraw.Draw(mask)
    pad = int(ss * inset_fraction)
    mdraw.ellipse([pad, pad, ss - 1 - pad, ss - 1 - pad], fill=255)
    mask = mask.resize((size, size), Image.Resampling.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def write_android_vector_drawables() -> None:
    drawable_dir = ANDROID_RES / "drawable"
    drawable_dir.mkdir(parents=True, exist_ok=True)

    bg_xml = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- Lixo Sky-to-Royal Blue Gradient Backdrop -->
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:startX="54"
                android:startY="0"
                android:endX="54"
                android:endY="108"
                android:type="linear">
                <item android:offset="0.0" android:color="#FF1CB0F6" />
                <item android:offset="0.5" android:color="#FF118ADA" />
                <item android:offset="1.0" android:color="#FF0958A8" />
            </gradient>
        </aapt:attr>
    </path>

    <!-- Soft concentric glow rings behind the character -->
    <path
        android:fillColor="#14FFFFFF"
        android:pathData="M54,21A31,31 0 1,1 53.9,21z" />
    <path
        android:fillColor="#18FFFFFF"
        android:pathData="M54,29A23,23 0 1,1 53.9,29z" />
</vector>
"""
    (drawable_dir / "ic_launcher_background.xml").write_text(bg_xml, encoding="utf-8")

    fg_xml = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- Grounding Drop Shadow -->
    <path
        android:fillColor="#48042652"
        android:pathData="M24,81.5a30,5 0 1,0 60,0a30,5 0 1,0 -60,0z" />

    <!-- 1. Royal Purple Adventurer Cape -->
    <path
        android:fillColor="#FF8E44AD"
        android:pathData="M37,58L71,58L77.5,74L30.5,74z" />
    <path
        android:fillColor="#FF9B59B6"
        android:pathData="M39,58L69,58L74,73L34,73z" />

    <!-- 2. Blocky 3D Blue Tunic / Shoulders -->
    <path
        android:fillColor="#FF147EB5"
        android:pathData="M42.5,59L67.5,59A3.5,3.5 0 0,1 71,62.5L71,69.5A3.5,3.5 0 0,1 67.5,73L42.5,73A3.5,3.5 0 0,1 39,69.5L39,62.5A3.5,3.5 0 0,1 42.5,59z" />
    <path
        android:fillColor="#FF1CB0F6"
        android:pathData="M40.5,59L64.5,59A3.5,3.5 0 0,1 68,62.5L68,69.5A3.5,3.5 0 0,1 64.5,73L40.5,73A3.5,3.5 0 0,1 37,69.5L37,62.5A3.5,3.5 0 0,1 40.5,59z" />
    <path
        android:fillColor="#FFFFC800"
        android:pathData="M47.5,59.5L57.5,59.5L52.5,65z" />

    <!-- 3. Pointed Cat Ears -->
    <path
        android:fillColor="#FFC97800"
        android:pathData="M33.5,42L37.5,28.5L46.5,40z" />
    <path
        android:fillColor="#FFF59E0B"
        android:pathData="M32.5,42L36.5,28.5L45.5,40z" />
    <path
        android:fillColor="#FFFF7675"
        android:pathData="M34.8,40.5L37.2,32L42.8,39.5z" />

    <path
        android:fillColor="#FFB86B00"
        android:pathData="M62.5,40L71.5,28.5L75.5,42z" />
    <path
        android:fillColor="#FFF59E0B"
        android:pathData="M61.5,40L70.5,28.5L74.5,42z" />
    <path
        android:fillColor="#FFFF7675"
        android:pathData="M64.2,39.5L69.8,32L72.2,40.5z" />

    <!-- 4. Blocky 3D Cat Head (with right-side parallax bevel & top highlight strip) -->
    <path
        android:fillColor="#FFCC7A00"
        android:pathData="M44.8,39L66.7,39A5.8,5.8 0 0,1 72.5,44.8L72.5,58.4A5.8,5.8 0 0,1 66.7,64.2L44.8,64.2A5.8,5.8 0 0,1 39,58.4L39,44.8A5.8,5.8 0 0,1 44.8,39z" />
    <path
        android:fillColor="#FFD98200"
        android:pathData="M41.3,41L63.7,41A5.8,5.8 0 0,1 69.5,46.8L69.5,59.2A5.8,5.8 0 0,1 63.7,65L41.3,65A5.8,5.8 0 0,1 35.5,59.2L35.5,46.8A5.8,5.8 0 0,1 41.3,41z" />
    <path
        android:fillColor="#FFFFB01F"
        android:pathData="M41.3,39L63.7,39A5.8,5.8 0 0,1 69.5,44.8L69.5,57.4A5.8,5.8 0 0,1 63.7,63.2L41.3,63.2A5.8,5.8 0 0,1 35.5,57.4L35.5,44.8A5.8,5.8 0 0,1 41.3,39z" />
    <path
        android:fillColor="#FFFFC442"
        android:pathData="M41.8,40L63.2,40A5,5 0 0,1 68.2,45L68.2,56.6A5,5 0 0,1 63.2,61.6L41.8,61.6A5,5 0 0,1 36.8,56.6L36.8,45A5,5 0 0,1 41.8,40z" />
    <path
        android:fillColor="#FFFFE082"
        android:pathData="M39.8,40L65.2,40A1.8,1.8 0 0,1 67,41.8L67,41.8A1,1 0 0,1 66,42.8L39,42.8A1,1 0 0,1 38,41.8L38,41.8A1.8,1.8 0 0,1 39.8,40z" />

    <!-- Forehead Stripes -->
    <path
        android:fillColor="#FFE08600"
        android:pathData="M51,40.2h3v5.2h-3zM46.2,40.6h2.6v3.8h-2.6zM56.2,40.6h2.6v3.8h-2.6z" />

    <!-- 5. Expressive Cat Face (Blushes, Eyes, Catchlights, Nose, Smile, Whiskers) -->
    <path
        android:fillColor="#FFFF7E6B"
        android:pathData="M38.2,54a2.6,1.8 0 1,0 5.2,0a2.6,1.8 0 1,0 -5.2,0z" />
    <path
        android:fillColor="#FFFF7E6B"
        android:pathData="M61.6,54a2.6,1.8 0 1,0 5.2,0a2.6,1.8 0 1,0 -5.2,0z" />

    <!-- Left & Right Eyes -->
    <path
        android:fillColor="#FF1E272E"
        android:pathData="M42.2,49.4a2.7,3.6 0 1,0 5.4,0a2.7,3.6 0 1,0 -5.4,0z" />
    <path
        android:fillColor="#FF1E272E"
        android:pathData="M57.4,49.4a2.7,3.6 0 1,0 5.4,0a2.7,3.6 0 1,0 -5.4,0z" />

    <!-- White Eye Catchlights -->
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M43.2,48a1.2,1.2 0 1,0 2.4,0a1.2,1.2 0 1,0 -2.4,0zM45.4,51a0.65,0.65 0 1,0 1.3,0a0.65,0.65 0 1,0 -1.3,0z" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M58.4,48a1.2,1.2 0 1,0 2.4,0a1.2,1.2 0 1,0 -2.4,0zM60.6,51a0.65,0.65 0 1,0 1.3,0a0.65,0.65 0 1,0 -1.3,0z" />

    <!-- Cat Nose & Cheerful Open Smile -->
    <path
        android:fillColor="#FF1E272E"
        android:pathData="M50.6,51.8L54.4,51.8L52.5,54.2z" />
    <path
        android:fillColor="#FF1E272E"
        android:pathData="M48.8,55.5A3.7,3.1 0 0,0 56.2,55.5z" />
    <path
        android:fillColor="#FFFF7675"
        android:pathData="M50.1,56.2A2.4,2.2 0 0,0 54.9,56.2z" />
    <path
        android:strokeColor="#FF1E272E"
        android:strokeWidth="1.7"
        android:strokeLineCap="round"
        android:pathData="M47,54.2Q49.6,57.2 52.5,54.2Q55.4,57.2 58,54.2" />

    <!-- Whiskers -->
    <path
        android:strokeColor="#FF995200"
        android:strokeWidth="1.4"
        android:strokeLineCap="round"
        android:pathData="M34,51.2L38.8,52.3M34,54.8L38.8,54.2M66.2,52.3L71,51.2M66.2,54.2L71,54.8" />

    <!-- 6. Lixo Green Wizard Hat + Gold Band + Star Badge -->
    <path
        android:fillColor="#FF46A302"
        android:pathData="M37.5,38L54.2,20.5L59.2,24.5L69,38z" />
    <path
        android:fillColor="#FF58CC02"
        android:pathData="M37.5,38L54.2,20.5L65.5,38z" />
    <path
        android:fillColor="#FF76DF20"
        android:pathData="M39,38L54.2,20.5L44.8,38z" />

    <!-- Gold Hat Band -->
    <path
        android:fillColor="#FFE5B400"
        android:pathData="M37,34.2L68.5,34.2L69.5,38.2L36,38.2z" />
    <path
        android:fillColor="#FFFFC800"
        android:pathData="M37,34.2L65.8,34.2L66.8,38.2L36,38.2z" />

    <!-- Tactile 3D Hat Brim -->
    <path
        android:fillColor="#FF3B8C00"
        android:pathData="M33.1,37.6L74.9,37.6A2.6,2.6 0 0,1 77.5,40.2L77.5,40.2A1.8,1.8 0 0,1 75.7,42L32.3,42A1.8,1.8 0 0,1 30.5,40.2L30.5,40.2A2.6,2.6 0 0,1 33.1,37.6z" />
    <path
        android:fillColor="#FF58CC02"
        android:pathData="M32.1,36.4L73.2,36.4A2.6,2.6 0 0,1 75.8,39L75.8,39A1.7,1.7 0 0,1 74.1,40.7L31.2,40.7A1.7,1.7 0 0,1 29.5,39L29.5,39A2.6,2.6 0 0,1 32.1,36.4z" />
    <path
        android:fillColor="#FF89E219"
        android:pathData="M32.7,36.8L72.6,36.8A1.2,1.2 0 0,1 73.8,38L73.8,38.2L31.5,38.2L31.5,38A1.2,1.2 0 0,1 32.7,36.8z" />

    <!-- Gleaming Star Emblem on Wizard Hat -->
    <path
        android:fillColor="#FFD99B00"
        android:pathData="M53.4,25.0L54.8,28.6L58.7,28.9L55.7,31.3L56.7,35.1L53.4,33.0L50.1,35.1L51.1,31.3L48.1,28.9L52.0,28.6z" />
    <path
        android:fillColor="#FFFFC800"
        android:pathData="M52.8,24.4L54.2,28.0L58.1,28.3L55.1,30.7L56.1,34.5L52.8,32.4L49.5,34.5L50.5,30.7L47.5,28.3L51.4,28.0z" />
    <path
        android:fillColor="#FFFFF7D6"
        android:pathData="M52.5,26.5L53.3,28.6L55.5,28.7L53.8,30.1L54.4,32.3L52.5,31.1L50.6,32.3L51.2,30.1L49.5,28.7L51.7,28.6z" />

    <!-- 7. Open Magical Storybook at the Base -->
    <path
        android:fillColor="#FF3B8C00"
        android:pathData="M28,71L54,75.4L80,71L80,78.6L54,83L28,78.6z" />
    <path
        android:fillColor="#FF58CC02"
        android:pathData="M28,69.4L54,73.8L80,69.4L80,76.8L54,81.2L28,76.8z" />
    <path
        android:fillColor="#FFFFC800"
        android:pathData="M28,69.4L33,70.3L33,77.7L28,76.8zM75,70.3L80,69.4L80,76.8L75,77.7z" />

    <!-- Book Pages -->
    <path
        android:fillColor="#FFD6EAF8"
        android:pathData="M30,67.4L54,72.3L78,67.4L78,74.8L54,79.6L30,74.8z" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M30,65.5L54,70.4L54,78L30,73.3z" />
    <path
        android:fillColor="#FFF2F9FD"
        android:pathData="M54,70.4L78,65.5L78,73.3L54,78z" />

    <!-- Highlighted Reading Lines on Pages -->
    <path
        android:strokeColor="#FF58CC02"
        android:strokeWidth="2.1"
        android:strokeLineCap="round"
        android:pathData="M33.8,68.5L42.5,70.3M57.8,71.8L65.6,70.3" />
    <path
        android:strokeColor="#FFFFC800"
        android:strokeWidth="2.1"
        android:strokeLineCap="round"
        android:pathData="M44.4,70.7L50.2,71.8" />
    <path
        android:strokeColor="#FF84D8FF"
        android:strokeWidth="1.7"
        android:strokeLineCap="round"
        android:pathData="M33.8,71.5L48.5,74.4M67.6,69.9L74.2,68.5M57.8,74.4L73.5,71.3" />

    <!-- Golden Bookmark Ribbon -->
    <path
        android:fillColor="#FFFF9600"
        android:pathData="M52,77.2L56,77.2L56.4,84.8L54,82.8L51.6,84.8z" />
    <path
        android:fillColor="#FFFFC800"
        android:pathData="M52,77.2L55,77.2L55.3,83.8L54,82.8L51.6,84.8z" />

    <!-- Cat Paws on the Open Book -->
    <path
        android:fillColor="#FFD98200"
        android:pathData="M36.7,63.4L39.3,63.4A2.5,2.5 0 0,1 41.8,65.9L41.8,66.1A2.5,2.5 0 0,1 39.3,68.6L36.7,68.6A2.5,2.5 0 0,1 34.2,66.1L34.2,65.9A2.5,2.5 0 0,1 36.7,63.4z" />
    <path
        android:fillColor="#FFFFB01F"
        android:pathData="M36.7,62.8L38.7,62.8A2.5,2.5 0 0,1 41.2,65.3L41.2,65.3A2.5,2.5 0 0,1 38.7,67.8L36.7,67.8A2.5,2.5 0 0,1 34.2,65.3L34.2,65.3A2.5,2.5 0 0,1 36.7,62.8z" />
    <path
        android:fillColor="#FFD98200"
        android:pathData="M68.7,63.4L71.3,63.4A2.5,2.5 0 0,1 73.8,65.9L73.8,66.1A2.5,2.5 0 0,1 71.3,68.6L68.7,68.6A2.5,2.5 0 0,1 66.2,66.1L66.2,65.9A2.5,2.5 0 0,1 68.7,63.4z" />
    <path
        android:fillColor="#FFFFB01F"
        android:pathData="M68.7,62.8L70.7,62.8A2.5,2.5 0 0,1 73.2,65.3L73.2,65.3A2.5,2.5 0 0,1 70.7,67.8L68.7,67.8A2.5,2.5 0 0,1 66.2,65.3L66.2,65.3A2.5,2.5 0 0,1 68.7,62.8z" />

    <!-- 8. Magic Reading Sparkles -->
    <path
        android:fillColor="#FFFFC800"
        android:pathData="M41.5,20.4L42.4,23.1L45.1,24L42.4,24.9L41.5,27.6L40.6,24.9L37.9,24L40.6,23.1z" />
    <path
        android:fillColor="#FFFFC800"
        android:pathData="M66.8,20L67.8,23L70.8,24L67.8,25L66.8,28L65.8,25L62.8,24L65.8,23z" />
    <path
        android:fillColor="#FFFFC800"
        android:pathData="M26.5,45.1L27.4,47.6L29.9,48.5L27.4,49.4L26.5,51.9L25.6,49.4L23.1,48.5L25.6,47.6z" />
    <path
        android:fillColor="#FFFFC800"
        android:pathData="M81,44.6L81.9,47.1L84.4,48L81.9,48.9L81,51.4L80.1,48.9L77.6,48L80.1,47.1z" />
</vector>
"""
    (drawable_dir / "ic_launcher_foreground.xml").write_text(fg_xml, encoding="utf-8")

    mono_xml = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- Clean monochrome silhouette for Android 13+ themed icons -->
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M37.5,36L54.2,20.5L67.5,36z" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M32.1,36.4L73.2,36.4A2.6,2.6 0 0,1 75.8,39L75.8,39A1.7,1.7 0 0,1 74.1,40.7L31.2,40.7A1.7,1.7 0 0,1 29.5,39L29.5,39A2.6,2.6 0 0,1 32.1,36.4z" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M32.5,42L36.5,28.5L45.5,40zM61.5,40L70.5,28.5L74.5,42z" />
    <path
        android:fillType="evenOdd"
        android:fillColor="#FFFFFFFF"
        android:pathData="M41.3,40L63.7,40A5.8,5.8 0 0,1 69.5,45.8L69.5,57.4A5.8,5.8 0 0,1 63.7,63.2L41.3,63.2A5.8,5.8 0 0,1 35.5,57.4L35.5,45.8A5.8,5.8 0 0,1 41.3,40z
                         M42.2,49.4a2.7,3.6 0 1,0 5.4,0a2.7,3.6 0 1,0 -5.4,0z
                         M57.4,49.4a2.7,3.6 0 1,0 5.4,0a2.7,3.6 0 1,0 -5.4,0z
                         M50.6,51.8L54.4,51.8L52.5,54.2z" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M30,66.4L53,71.4L53,79L30,74.2zM55,71.4L78,66.4L78,74.2L55,79z" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M52,79L56,79L56.4,84.8L54,82.8L51.6,84.8z" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M30,23.5L31,26.5L34,27.5L31,28.5L30,31.5L29,28.5L26,27.5L29,26.5z
                         M77.5,22.1L78.6,25.4L81.9,26.5L78.6,27.6L77.5,30.9L76.4,27.6L73.1,26.5L76.4,25.4z" />
</vector>
"""
    (drawable_dir / "ic_launcher_monochrome.xml").write_text(mono_xml, encoding="utf-8")

    anydpi_dir = ANDROID_RES / "mipmap-anydpi-v26"
    anydpi_dir.mkdir(parents=True, exist_ok=True)
    adaptive_xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""
    (anydpi_dir / "ic_launcher.xml").write_text(adaptive_xml, encoding="utf-8")
    (anydpi_dir / "ic_launcher_round.xml").write_text(adaptive_xml, encoding="utf-8")


def write_android_mipmaps(master_1024: Image.Image) -> None:
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, px_size in densities.items():
        out_dir = ANDROID_RES / folder
        out_dir.mkdir(parents=True, exist_ok=True)
        scaled = master_1024.resize((px_size, px_size), Image.Resampling.LANCZOS)
        square_icon = apply_rounded_mask(scaled, radius_fraction=0.22)
        round_icon = apply_circle_mask(scaled, inset_fraction=0.03)
        square_icon.save(out_dir / "ic_launcher.png", format="PNG", optimize=True)
        round_icon.save(out_dir / "ic_launcher_round.png", format="PNG", optimize=True)


def write_ios_appiconset(master_1024: Image.Image) -> None:
    appicon_dir = IOS_ASSETS / "AppIcon.appiconset"
    appicon_dir.mkdir(parents=True, exist_ok=True)

    # Apple requires RGB (no alpha channel) for App Store / iOS AppIcon PNGs
    rgb_1024 = master_1024.convert("RGB")
    rgb_1024.save(appicon_dir / "AppIcon-1024.png", format="PNG", optimize=True)

    ios_sizes = [
        ("AppIcon-20@2x.png", 40),
        ("AppIcon-20@3x.png", 60),
        ("AppIcon-29@2x.png", 58),
        ("AppIcon-29@3x.png", 87),
        ("AppIcon-40@2x.png", 80),
        ("AppIcon-40@3x.png", 120),
        ("AppIcon-60@2x.png", 120),
        ("AppIcon-60@3x.png", 180),
        ("AppIcon-76.png", 76),
        ("AppIcon-76@2x.png", 152),
        ("AppIcon-83.5@2x.png", 167),
    ]
    for filename, px_size in ios_sizes:
        rgb_1024.resize((px_size, px_size), Image.Resampling.LANCZOS).save(
            appicon_dir / filename, format="PNG", optimize=True
        )

    contents = {
        "images": [
            {"idiom": "iphone", "scale": "2x", "size": "20x20", "filename": "AppIcon-20@2x.png"},
            {"idiom": "iphone", "scale": "3x", "size": "20x20", "filename": "AppIcon-20@3x.png"},
            {"idiom": "iphone", "scale": "2x", "size": "29x29", "filename": "AppIcon-29@2x.png"},
            {"idiom": "iphone", "scale": "3x", "size": "29x29", "filename": "AppIcon-29@3x.png"},
            {"idiom": "iphone", "scale": "2x", "size": "40x40", "filename": "AppIcon-40@2x.png"},
            {"idiom": "iphone", "scale": "3x", "size": "40x40", "filename": "AppIcon-40@3x.png"},
            {"idiom": "iphone", "scale": "2x", "size": "60x60", "filename": "AppIcon-60@2x.png"},
            {"idiom": "iphone", "scale": "3x", "size": "60x60", "filename": "AppIcon-60@3x.png"},
            {"idiom": "ipad", "scale": "1x", "size": "76x76", "filename": "AppIcon-76.png"},
            {"idiom": "ipad", "scale": "2x", "size": "76x76", "filename": "AppIcon-76@2x.png"},
            {"idiom": "ipad", "scale": "2x", "size": "83.5x83.5", "filename": "AppIcon-83.5@2x.png"},
            {"idiom": "ios-marketing", "scale": "1x", "size": "1024x1024", "filename": "AppIcon-1024.png"},
        ],
        "info": {"author": "xcode", "version": 1},
    }
    (appicon_dir / "Contents.json").write_text(json.dumps(contents, indent=2) + "\n", encoding="utf-8")
    (IOS_ASSETS / "Contents.json").write_text(
        json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    master_1024 = compose_framed_icon(1024)
    write_android_vector_drawables()
    write_android_mipmaps(master_1024)
    write_ios_appiconset(master_1024)
    print("Generated Android adaptive vectors, mipmap PNGs, and iOS AppIcon.appiconset.")


if __name__ == "__main__":
    main()
