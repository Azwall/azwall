#!/usr/bin/env bash
# Builds the app icons of both apps from branding/azwall-logo.png (cream nested diamonds, a pyramid seen from above, on
# the purple #5631CB, ios/... and android/.../values/ic_launcher_background.xml): the motif is cut out of it, then set
# on that purple again, at the size each use needs.
#   Android: adaptive icon (background colour + foreground layer) and legacy launcher PNGs, per density.
#   iOS: the single 1024 px AppIcon of ios/Azwall/Assets.xcassets.
#   Both: the logo as a rounded purple tile, for the welcome, lock and proving screens (readable on light and dark).
#   Google Play: store/play/icon-512.png.
# Needs ImageMagick 7 (magick). Rerun after changing the source image.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC="$ROOT/branding/azwall-logo.png"
RES="$ROOT/android/app/src/main/res"
BG='#5631CB'
FG='#FDEECA'
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
LOGO="$WORK/motif.png"

# The motif's opacity follows the green channel (49 on the purple, 238 on the cream), cut below the background's noise;
# trimmed, then centred on a square so every size below keeps its proportions.
magick "$SRC" -channel G -separate +channel -fx 'min(1,max(0,(u-0.26)/0.66))' "$WORK/alpha.png"
magick -size "$(magick identify -format '%wx%h' "$SRC")" "xc:$FG" "$WORK/alpha.png" -alpha off -compose CopyOpacity \
  -composite -compose over -trim +repage -background none -gravity center -extent "%[fx:max(w,h)]x%[fx:max(w,h)]" "$LOGO"

# An opaque square icon: the motif at `percent` of `size`, on the background colour.
icon() { magick -size "$1x$1" "xc:$BG" \( "$LOGO" -resize "$(($1 * $2 / 100))x" \) -gravity center -composite -alpha off -strip "PNG24:$3"; }

# The motif on a rounded tile of the background colour, `size` px, transparent around.
tile() {
  magick -size "$1x$1" xc:none -fill "$BG" -draw "roundrectangle 0,0 $(($1 - 1)),$(($1 - 1)) $(($1 * 22 / 100)),$(($1 * 22 / 100))" \
    \( "$LOGO" -resize "$(($1 * 66 / 100))x" \) -gravity center -composite -strip "PNG32:$2"
}

# density: launcher size (48 dp), adaptive layer size (108 dp)
for d in mdpi:48:108 hdpi:72:162 xhdpi:96:216 xxhdpi:144:324 xxxhdpi:192:432; do
  IFS=: read -r name legacy layer <<<"$d"
  mkdir -p "$RES/mipmap-$name"
  icon "$legacy" 70 "$RES/mipmap-$name/ic_launcher.png"
  # At 56% of the layer the diamond's tips stay inside the 66 dp circle that every launcher mask keeps.
  magick "$LOGO" -resize "$((layer * 56 / 100))x" -background none -gravity center -extent "${layer}x${layer}" -strip \
    "PNG32:$RES/mipmap-$name/ic_launcher_foreground.png"
done

ICON="$ROOT/ios/Azwall/Assets.xcassets/AppIcon.appiconset"
mkdir -p "$ICON"
icon 1024 72 "$ICON/icon-1024.png"
icon 512 72 "$ROOT/store/play/icon-512.png"

# The logo tile. iOS: a @3x image, 200 points wide on the launch screen.
mkdir -p "$RES/drawable-nodpi" "$ROOT/ios/Azwall/Assets.xcassets/Logo.imageset"
tile 512 "$RES/drawable-nodpi/azwall_logo.png"
tile 600 "$ROOT/ios/Azwall/Assets.xcassets/Logo.imageset/logo.png"
echo "icons written"
