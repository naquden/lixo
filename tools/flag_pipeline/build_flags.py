#!/usr/bin/env python3
"""Downloads the pinned lipis/flag-icons (MIT) npm release, rasterizes the `flags/4x3/<iso>.svg`
for every country in `countries.txt` to a 240x180 WebP, and writes
`composeApp/src/commonMain/composeResources/drawable/flag_<iso>.webp` (plan 037).

Run with `python3 -I tools/flag_pipeline/build_flags.py` from the repo root. Requires
`rsvg-convert` and `cwebp` on PATH (both from Homebrew on macOS: `brew install librsvg webp`).

The downloaded tarball is untrusted data: it is fetched into a fresh `tempfile.mkdtemp()`
directory, its sha512 is checked against [SHA512_B64] before anything is extracted, and only the
exact `package/flags/4x3/<code>.svg` members for the known country codes are read via
`tarfile.extractfile` (never `extractall`); any member whose name contains `..` is rejected.
"""
from __future__ import annotations

import base64
import hashlib
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

FLAG_ICONS_VERSION = "7.6.0"
TARBALL_URL = f"https://registry.npmjs.org/flag-icons/-/flag-icons-{FLAG_ICONS_VERSION}.tgz"
SHA512_B64 = "hir/PsXwr0EESyizKrXJzmkzpLIMf+1vlNKLqAfvdh+bsU94a7LYWTiubkHKqptGoCPzOUGcm6c4PfC9/BaGSQ=="

REPO_ROOT = Path(__file__).resolve().parents[2]
COUNTRIES_FILE = REPO_ROOT / "tools" / "flag_pipeline" / "countries.txt"
OUT_DIR = REPO_ROOT / "composeApp" / "src" / "commonMain" / "composeResources" / "drawable"
MAX_TOTAL_BYTES = 1_000_000


def load_codes() -> list[str]:
    lines = COUNTRIES_FILE.read_text(encoding="utf-8").splitlines()
    codes = [ln.strip().lower() for ln in lines if ln.strip() and not ln.strip().startswith("#")]
    if len(codes) != len(set(codes)):
        dupes = sorted({c for c in codes if codes.count(c) > 1})
        print(f"ERROR: duplicate codes in {COUNTRIES_FILE}: {dupes}", file=sys.stderr)
        sys.exit(1)
    if not codes:
        print(f"ERROR: no codes found in {COUNTRIES_FILE}", file=sys.stderr)
        sys.exit(1)
    return codes


def download_tarball(tmp_dir: Path) -> Path:
    tarball_path = tmp_dir / "flag-icons.tgz"
    print(f"Downloading {TARBALL_URL} ...")
    urllib.request.urlretrieve(TARBALL_URL, tarball_path)

    digest = hashlib.sha512(tarball_path.read_bytes()).digest()
    actual_b64 = base64.b64encode(digest).decode("ascii")
    if actual_b64 != SHA512_B64:
        print(
            f"ERROR: sha512 mismatch for {TARBALL_URL}\n  expected: {SHA512_B64}\n  actual:   {actual_b64}",
            file=sys.stderr,
        )
        sys.exit(1)
    return tarball_path


def extract_svgs(tarball_path: Path, codes: list[str], tmp_dir: Path) -> dict[str, Path]:
    wanted = {f"package/flags/4x3/{code}.svg": code for code in codes}
    extracted: dict[str, Path] = {}
    with tarfile.open(tarball_path, "r:gz") as tar:
        for member in tar.getmembers():
            if ".." in member.name:
                continue
            code = wanted.get(member.name)
            if code is None:
                continue
            fileobj = tar.extractfile(member)
            if fileobj is None:
                continue
            svg_path = tmp_dir / f"{code}.svg"
            svg_path.write_bytes(fileobj.read())
            extracted[code] = svg_path

    missing = sorted(set(codes) - set(extracted))
    if missing:
        print(f"ERROR: missing flag svg for codes: {missing}", file=sys.stderr)
        sys.exit(1)
    return extracted


def rasterize(code: str, svg_path: Path, tmp_dir: Path) -> Path:
    png_path = tmp_dir / f"{code}.png"
    subprocess.run(
        ["rsvg-convert", "-w", "240", "-h", "180", str(svg_path), "-o", str(png_path)],
        check=True,
    )
    webp_path = OUT_DIR / f"flag_{code}.webp"
    subprocess.run(
        ["cwebp", "-quiet", "-q", "85", str(png_path), "-o", str(webp_path)],
        check=True,
    )
    return webp_path


def main() -> int:
    codes = load_codes()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="lixo_flags_") as tmp:
        tmp_dir = Path(tmp)
        tarball_path = download_tarball(tmp_dir)
        svgs = extract_svgs(tarball_path, codes, tmp_dir)

        total_bytes = 0
        for code in codes:
            webp_path = rasterize(code, svgs[code], tmp_dir)
            total_bytes += webp_path.stat().st_size

    print(f"Wrote {len(codes)} flag_*.webp files to {OUT_DIR}, {total_bytes} bytes total.")
    if total_bytes > MAX_TOTAL_BYTES:
        print(f"ERROR: total size {total_bytes} exceeds MAX_TOTAL_BYTES={MAX_TOTAL_BYTES}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
