#!/usr/bin/env bash
# Test-only: downloads the Firebase iOS 11.15.0 Carthage xcframeworks so the
# `iosSimulatorArm64Test` binary can link against GitLive's Firebase cinterop klibs (each klib
# carries `-framework FirebaseX` linker options). These frameworks are never shipped in the app;
# the app target gets Firebase from SPM instead (see iosApp/project.yml, exactVersion 11.15.0 —
# keep that in sync with this version).
#
# Each zip is pinned to its SHA-256 below. Re-pin (recompute with `shasum -a 256`) when bumping
# the firebase-ios-test version.
set -euo pipefail

OUT="$1"

if [ -f "$OUT/.complete" ]; then
  exit 0
fi

mkdir -p "$OUT/zips" "$OUT/xc" "$OUT/simulator"

# bash 3.2 (macOS's /bin/bash) has no associative arrays, so use an indexed array of
# "<sha256> <url>" entries instead.
ZIPS=(
  "6cf557c25c7bcfa1150b33bc6301d2e122bed3728b19a511551b51d43d81a9a4 https://dl.google.com/dl/firebase/ios/carthage/11.15.0/FirebaseAnalytics-9a411c918346c8ea.zip"
  "a3fe77168de076ad21d40bd7f3cd98723605b8c86e2be0a68d597c6f2fc1c361 https://dl.google.com/dl/firebase/ios/carthage/11.15.0/FirebaseAuth-d6d3264758ea5270.zip"
  "f1ea672ac74dc5cd14c1a3516d48f78b74f70cd6c411cd8eeeec408e57369995 https://dl.google.com/dl/firebase/ios/carthage/11.15.0/FirebaseFirestore-f374bf8c4f273d0d.zip"
  "dedc83f526a93875b971687d7bdf9a2511a7ce4a9fe382c637a03816ecc4280c https://dl.google.com/dl/firebase/ios/carthage/11.15.0/FirebaseCrashlytics-6c0391d0cb3de2ad.zip"
)

for entry in "${ZIPS[@]}"; do
  read -r sha url <<< "$entry"
  name="$(basename "$url")"
  curl -fsSL -o "$OUT/zips/$name" "$url"
  if ! echo "$sha  $OUT/zips/$name" | shasum -a 256 -c - >/dev/null; then
    echo "SHA-256 mismatch for $name" >&2
    rm -rf "$OUT"
    exit 1
  fi
  unzip -q -o "$OUT/zips/$name" -d "$OUT/xc"
done

# Use the literal `ios-arm64_x86_64-simulator` slice directory: a glob like `*simulator*` also
# matches the tvOS-simulator slice, and linking against that fails with
# "built for tvOS-simulator".
while IFS= read -r -d '' xcframework; do
  name="$(basename "$xcframework" .xcframework)"
  dest="$OUT/simulator/$name.framework"
  if [ -e "$dest" ]; then
    continue
  fi
  src="$xcframework/ios-arm64_x86_64-simulator/$name.framework"
  if [ -d "$src" ]; then
    cp -R "$src" "$dest"
  fi
done < <(find "$OUT/xc" -maxdepth 2 -name "*.xcframework" -print0)

touch "$OUT/.complete"
