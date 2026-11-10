#!/usr/bin/env bash
# 📸 Records a sandbox scene into docs/img/: a GIF of it moving and a PNG of its last frame.
#
#   scripts/capture.sh <scene> <name> [--from S] [--to S] [--details] [--clean] [--width W]
#
#   <scene>     the scene's number, as the 1-9 keys pick it
#   <name>      the file name: docs/img/<name>.gif and docs/img/<name>.png
#   --from S    start the GIF S seconds in (default 0), so the blobs can land first
#   --to S      stop after S seconds (default 4)
#   --details   show the particles, cores and contacts, as F1 does
#   --clean     only the scene's title on screen, no stats or controls
#   --width W   the GIF's width in pixels (default 800); the PNG stays 1280 wide
#
# The physics steps once per frame while recording, so the same command makes the same pictures.
# Needs ffmpeg.
set -euo pipefail

usage() { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
[[ $# -ge 2 ]] || usage
scene=$1 name=$2; shift 2
from=0 to=4 width=800 details=0 clean=0
while [[ $# -gt 0 ]]; do
  case $1 in
    --from) from=$2; shift 2 ;;
    --to) to=$2; shift 2 ;;
    --width) width=$2; shift 2 ;;
    --details) details=1; shift ;;
    --clean) clean=1; shift ;;
    *) usage ;;
  esac
done
command -v ffmpeg > /dev/null || { echo "📸 needs ffmpeg (brew install ffmpeg)"; exit 1; }

root=$(cd "$(dirname "$0")/.." && pwd)
out="$root/docs/img"
frames=$(mktemp -d)
trap 'rm -rf "$frames"' EXIT
mkdir -p "$out"

# 60 steps a second, a picture every 2nd: a 30 fps GIF.
steps=$(awk "BEGIN { print int($to * 60) }")
first=$(awk "BEGIN { print int($from * 30) + 1 }")

echo "📸 scene $scene, ${from}s to ${to}s -> docs/img/$name.{gif,png}"
cd "$root"
SANDBOX_SCENE=$scene SANDBOX_DETAILS=$details SANDBOX_CLEAN=$clean SANDBOX_RECORD="2:$frames" \
  KLYPH_FRAMES=$steps KLYPH_OFFSCREEN=1 \
  ./gradlew -q :demo:run -Pklyph.frames="$steps" 2>&1 | grep -v "stdlib-per-file-cache\|^ld: warning\|^warning:" || true

last=$(ls "$frames" | sort | tail -1)
[[ -n "$last" ]] || { echo "📸 no frames were saved"; exit 1; }
cp "$frames/$last" "$out/$name.png"
ffmpeg -loglevel error -y -framerate 30 -start_number "$first" -i "$frames/%04d.png" \
  -vf "scale=$width:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" \
  -loop 0 "$out/$name.gif"
echo "📸 $(ls "$frames" | wc -l | tr -d ' ') frames: $(du -h "$out/$name.gif" | cut -f1) GIF, $(du -h "$out/$name.png" | cut -f1) PNG"
