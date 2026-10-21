#!/usr/bin/env bash
# Builds the app icons of both apps from branding/azwall-icon.png (1024x1024, opaque, background #0D0B16).
#   Android: adaptive icon (background colour + foreground layer) and legacy launcher PNGs, per density.
#   iOS: the single 1024 px AppIcon of ios/Azwall/Assets.xcassets.
#   Both: the logo alone on a transparent background, for the welcome and lock screens.
# Needs ImageMagick 7 (magick). Rerun after changing the source image.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC="$ROOT/branding/azwall-icon.png"
RES="$ROOT/android/app/src/main/res"

# density: launcher size (48 dp), adaptive layer size (108 dp)
for d in mdpi:48:108 hdpi:72:162 xhdpi:96:216 xxhdpi:144:324 xxxhdpi:192:432; do
  IFS=: read -r name legacy layer <<<"$d"
  mkdir -p "$RES/mipmap-$name"
  magick "$SRC" -resize "${legacy}x${legacy}" -strip "PNG24:$RES/mipmap-$name/ic_launcher.png"
  # The pyramid fills about 70% of the source: at 76% of the layer it stays well inside the 66 dp safe zone that every
  # launcher mask keeps. Its own background matches the adaptive background colour, so no edge shows.
  inner=$((layer * 76 / 100))
  magick "$SRC" -resize "${inner}x${inner}" -background none -gravity center -extent "${layer}x${layer}" -strip \
    "PNG32:$RES/mipmap-$name/ic_launcher_foreground.png"
done

ICON="$ROOT/ios/Azwall/Assets.xcassets/AppIcon.appiconset"
mkdir -p "$ICON"
magick "$SRC" -resize 1024x1024 -alpha off -strip "PNG24:$ICON/icon-1024.png"
# The logo: the icon's background removed from its corners inwards (the pyramid's dark outlines are kept), trimmed.
LOGO=$(mktemp --suffix=.png)
# iOS: a @3x image, 200 points wide on the launch screen.
magick "$SRC" -alpha set -fuzz 4% -fill none -draw "alpha 0,0 floodfill" -draw "alpha 1023,0 floodfill" \
  -draw "alpha 0,1023 floodfill" -draw "alpha 1023,1023 floodfill" -trim +repage "$LOGO"
mkdir -p "$RES/drawable-nodpi" "$ROOT/ios/Azwall/Assets.xcassets/Logo.imageset"
magick "$LOGO" -resize 512x -strip "PNG32:$RES/drawable-nodpi/azwall_logo.png"
magick "$LOGO" -resize 600x -strip "PNG32:$ROOT/ios/Azwall/Assets.xcassets/Logo.imageset/logo.png"
rm -f "$LOGO"
echo "icons written"
