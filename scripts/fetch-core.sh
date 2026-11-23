#!/usr/bin/env bash
set -euo pipefail

# Downloads the official SwanStation (DuckStation-based) libretro nightly core
# for Android from the libretro buildbot.
BASE="https://buildbot.libretro.com/nightly/android/latest"
TARGET_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/android/app/src/main/jniLibs"

for ABI in arm64-v8a armeabi-v7a; do
  URL="$BASE/$ABI/swanstation_libretro_android.so.zip"
  TMP="$(mktemp)"
  echo "Downloading $URL"
  curl -fsSL "$URL" -o "$TMP"
  TARGET="$TARGET_ROOT/$ABI"
  mkdir -p "$TARGET"
  unzip -o -q -j "$TMP" -d "$TARGET"
  for SO in "$TARGET"/*.so; do
    [ -e "$SO" ] || continue
    mv -f "$SO" "$TARGET/libswanstation.so"
  done
  rm -f "$TMP"
  echo "Core ready: $TARGET/libswanstation.so"
done
