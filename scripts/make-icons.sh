#!/usr/bin/env bash
set -euo pipefail

# Turns the uploaded cover into launcher icons. Falls back to the bundled
# placeholder icons when ImageMagick or a cover is not available.
COVER="$(find build/cover -maxdepth 1 -type f 2>/dev/null | head -n 1 || true)"

if [[ -z "$COVER" ]]; then
  echo "No cover asset found, keeping placeholder icons."
  exit 0
fi
if ! command -v convert >/dev/null 2>&1; then
  echo "ImageMagick not available, keeping placeholder icons."
  exit 0
fi

RES="android/app/src/main/res"
declare -A DENSITIES=( [mdpi]=48 [hdpi]=72 [xhdpi]=96 [xxhdpi]=144 [xxxhdpi]=192 )

for DENSITY in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  SIZE=${DENSITIES[$DENSITY]}
  OUT="$RES/mipmap-$DENSITY"
  mkdir -p "$OUT"

  convert "$COVER" -auto-orient -resize "${SIZE}x${SIZE}^" -gravity center \
    -extent "${SIZE}x${SIZE}" -strip "PNG24:$OUT/ic_launcher.png"

  MASK="$(mktemp --suffix=.png)"
  convert -size "${SIZE}x${SIZE}" xc:none -fill white \
    -draw "circle $((SIZE / 2)),$((SIZE / 2)) $((SIZE / 2)),0" "$MASK"
  convert "$COVER" -auto-orient -resize "${SIZE}x${SIZE}^" -gravity center \
    -extent "${SIZE}x${SIZE}" -strip \( "$MASK" \) -alpha off \
    -compose CopyOpacity -composite "PNG32:$OUT/ic_launcher_round.png"
  rm -f "$MASK"
done

echo "Launcher icons generated from cover."
