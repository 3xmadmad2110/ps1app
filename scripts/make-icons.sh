#!/usr/bin/env bash
set -euo pipefail

# Turns the uploaded cover into launcher icons and the in-app cover image.
# The launcher logo is ALWAYS generated from the cover - never a placeholder.
COVER="$(find build/cover -maxdepth 1 -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.webp' \) 2>/dev/null | head -n 1 || true)"

if [[ -z "$COVER" ]]; then
  echo "No cover asset found; refusing to build with a placeholder logo." >&2
  exit 1
fi
if ! command -v convert >/dev/null 2>&1; then
  echo "ImageMagick is required to generate the cover-based logo." >&2
  exit 1
fi

RES="android/app/src/main/res"

# In-app cover (keeps the original aspect ratio, capped at 800px).
mkdir -p "$RES/drawable-nodpi"
convert "$COVER" -auto-orient -resize '800x800>' -strip "PNG32:$RES/drawable-nodpi/ps1_cover.png"

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

test -s "$RES/drawable-nodpi/ps1_cover.png"
test -s "$RES/mipmap-xxxhdpi/ic_launcher.png"
test -s "$RES/mipmap-xxxhdpi/ic_launcher_round.png"

echo "Launcher icons and in-app cover generated from cover."
