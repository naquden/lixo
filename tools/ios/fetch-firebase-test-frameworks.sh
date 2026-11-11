#!/usr/bin/env bash
# Test-only: downloads the Firebase iOS 11.15.0 Carthage xcframeworks so the
# `iosSimulatorArm64Test` binary can link against GitLive's Firebase cinterop klibs (each klib
# carries `-framework FirebaseX` linker options). These frameworks are never shipped in the app;
# the app target gets Firebase from SPM instead (see iosApp/project.yml, exactVersion 11.15.0 —
# keep that in sync with this version).
set -euo pipefail

OUT="$1"

if [ -f "$OUT/.complete" ]; then
  exit 0
fi

mkdir -p "$OUT/zips" "$OUT/xc" "$OUT/simulator"

declare -a URLS=(
  "https://dl.google.com/dl/firebase/ios/carthage/11.15.0/FirebaseAnalytics-9a411c918346c8ea.zip"
  "https://dl.google.com/dl/firebase/ios/carthage/11.15.0/FirebaseAuth-d6d3264758ea5270.zip"
  "https://dl.google.com/dl/firebase/ios/carthage/11.15.0/FirebaseFirestore-f374bf8c4f273d0d.zip"
  "https://dl.google.com/dl/firebase/ios/carthage/11.15.0/FirebaseCrashlytics-6c0391d0cb3de2ad.zip"
)

for url in "${URLS[@]}"; do
  name="$(basename "$url")"
  curl -fsSL -o "$OUT/zips/$name" "$url"
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
