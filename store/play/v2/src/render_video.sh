#!/bin/sh
# Builds the how-to video and its thumbnail:
#   ../video/applock-how-to-use-1920x1080.mp4
#   ../video/thumbnail-1280x720.png
# Needs Google Chrome and Xcode command-line tools (swiftc). No ffmpeg required.
#   ./src/render_video.sh
set -e
here=$(cd "$(dirname "$0")" && pwd)
out=$(cd "$here/.." && pwd)/video
chrome=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir -p "$out" "$work/png" "$work/taps"

python3 "$here/video/howto.py" "$work" >/dev/null
for f in "$work"/frames/*.html; do
  n=$(basename "$f" .html)
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
    --force-device-scale-factor=1 --window-size=1920,1080 \
    --screenshot="$work/png/$n.png" "file://$f" >/dev/null 2>&1
  "$chrome" --headless=new --disable-gpu --allow-file-access-from-files --virtual-time-budget=3000 \
    --window-size=1920,1080 --dump-dom "file://$f" 2>/dev/null > "$work/taps/$n.dom" || true
done

python3 - "$work" <<'PY'
import json, re, sys
from pathlib import Path
w = Path(sys.argv[1]); meta = json.loads((w / "meta.json").read_text()); out = []
for m in meta:
    dom = (w / "taps" / f"{m['name']}.dom").read_text()
    mt = re.search(r"@@(\{.*?\})@@", dom, re.S)
    pos = json.loads(mt.group(1)) if mt else {}
    evs = []
    for e in m["events"]:
        if e["target"] not in pos:
            raise SystemExit(f"{m['name']}: tap target {e['target']!r} not found in the frame")
        x, y = pos[e["target"]]
        ev = {"type": e["type"], "x": x, "y": y, "at": e["at"]}
        if e["type"] == "swipe": ev.update(x=x + 170, x2=x - 170, y2=y)
        evs.append(ev)
    out.append({"png": str(w / "png" / f"{m['name']}.png"), "dur": m["dur"], "events": evs})
(w / "timeline.json").write_text(json.dumps(out))
print(len(out), "states,", sum(len(o["events"]) for o in out), "gestures")
PY

swiftc -O "$here/video/encode.swift" -o "$work/encode" 2>/dev/null
"$work/encode" "$work/timeline.json" "$out/applock-how-to-use-1920x1080.mp4"
"$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --window-size=1280,720 --screenshot="$out/thumbnail-1280x720.png" "file://$here/video/thumbnail.html" >/dev/null 2>&1
echo "thumbnail-1280x720.png"
