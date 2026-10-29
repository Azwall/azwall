#!/usr/bin/env bash
# Builds the app icons of both apps from branding/azwall-logo.png (the wallet with its pyramid, on a transparent
# background), set on the icon background colour (#0D0B16, android/.../values/ic_launcher_background.xml).
#   Android: adaptive icon (background colour + foreground layer) and legacy launcher PNGs, per density.
#   iOS: the single 1024 px AppIcon of ios/Azwall/Assets.xcassets.
#   Both: the logo alone on a transparent background, for the welcome and lock screens.
#   Google Play: store/play/icon-512.png.
# Needs ImageMagick 7 (magick). Rerun after changing the source image.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC="$ROOT/branding/azwall-logo.png"
RES="$ROOT/android/app/src/main/res"
BG='#0D0B16'
LOGO=$(mktemp --suffix=.png)
trap 'rm -f "$LOGO"' EXIT

# The source's alpha is nearly opaque inside (252-254) with a faint halo around: snapped to clean edges, trimmed, then
# centred on a square so every size below keeps its proportions.
magick "$SRC" -channel A -fx 'a<0.1?0:(a>0.96?1:a)' +channel -trim +repage -background none -gravity center \
  -extent "%[fx:max(w,h)]x%[fx:max(w,h)]" "$LOGO"

# An opaque square icon: the logo at `percent` of `size`, on the background colour.
icon() { magick -size "$1x$1" "xc:$BG" \( "$LOGO" -resize "$(($1 * $2 / 100))x" \) -gravity center -composite -alpha off -strip "PNG24:$3"; }

# density: launcher size (48 dp), adaptive layer size (108 dp)
for d in mdpi:48:108 hdpi:72:162 xhdpi:96:216 xxhdpi:144:324 xxxhdpi:192:432; do
  IFS=: read -r name legacy layer <<<"$d"
  mkdir -p "$RES/mipmap-$name"
  icon "$legacy" 82 "$RES/mipmap-$name/ic_launcher.png"
  # At 48% of the layer the wallet's corners stay inside the 66 dp circle that every launcher mask keeps.
  magick "$LOGO" -resize "$((layer * 48 / 100))x" -background none -gravity center -extent "${layer}x${layer}" -strip \
    "PNG32:$RES/mipmap-$name/ic_launcher_foreground.png"
done

ICON="$ROOT/ios/Azwall/Assets.xcassets/AppIcon.appiconset"
mkdir -p "$ICON"
icon 1024 80 "$ICON/icon-1024.png"
icon 512 80 "$ROOT/store/play/icon-512.png"

# The logo alone. iOS: a @3x image, 200 points wide on the launch screen.
mkdir -p "$RES/drawable-nodpi" "$ROOT/ios/Azwall/Assets.xcassets/Logo.imageset"
magick "$LOGO" -trim +repage -resize 512x -strip "PNG32:$RES/drawable-nodpi/azwall_logo.png"
magick "$LOGO" -trim +repage -resize 600x -strip "PNG32:$ROOT/ios/Azwall/Assets.xcassets/Logo.imageset/logo.png"
echo "icons written"
