#!/bin/sh
# Renders every Play Store graphic asset from the HTML sources in this directory.
#
# Headless Chrome is the renderer because it is the only thing on a stock macOS box that can
# turn HTML/SVG into a PNG of exact pixel dimensions. Output size = window-size x device scale
# factor, which is how the 1440x2560 tablet variants come out of the same 1080x1920 layout.
#
#   ./src/render.sh
#
# Set CHROME to override the browser path.
set -e

here=$(cd "$(dirname "$0")" && pwd)
out=$(cd "$here/.." && pwd)
chrome=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}

shoot() { # shoot <width> <height> <scale> <output> <input>
  "$chrome" --headless=new --disable-gpu --hide-scrollbars \
    --force-device-scale-factor="$3" --window-size="$1,$2" \
    --screenshot="$4" "file://$5" >/dev/null 2>&1
}

# ---- store graphics -------------------------------------------------------------
shoot 512 512 1 "$out/icon-512.png" "$here/icon.html"
shoot 1024 500 1 "$out/feature-graphic-1024x500.png" "$here/feature.html"
echo "icon-512.png, feature-graphic-1024x500.png"

# ---- screenshots ----------------------------------------------------------------
# 1080x1920 (9:16) satisfies the phone slot and the 7-inch tablet slot; the 10-inch slot
# wants each side >= 1080px, so it gets the same layout at 1.3333x -> 1440x2560.
mkdir -p "$out/phone" "$out/tablet-10"
for f in "$here"/shots/*.html; do
  name=$(basename "$f" .html)
  shoot 1080 1920 1      "$out/phone/$name.png"     "$f"
  shoot 1080 1920 1.3333 "$out/tablet-10/$name.png" "$f"
  echo "phone/$name.png, tablet-10/$name.png"
done
