#!/usr/bin/env bash
# Compose the 2-minute, 1920x1080, 16:9 MP4 the challenge asks for, from a portrait phone recording.
#   1) Record the phone:  scrcpy --no-audio --record phone.mp4      (or Android's screen recorder)
#      Keep the phone part to ~110 s by following docs/DEMO_VIDEO_SCRIPT.md.
#   2) Optional voice-over recorded separately: voice.m4a (it replaces all audio).
#   3) ./make_demo.sh phone.mp4 [voice.m4a]   ->  PathShala_demo_1080p.mp4
# Layout: title card 5 s -> phone mirrored on the left + fact panel and timed captions on the right -> end card 5 s.
set -euo pipefail
PHONE=${1:?phone.mp4}; VOICE=${2:-}
REPO=${REPO:-github.com/jashpasad/PathShala}
OUT=${OUT:-PathShala_demo_1080p.mp4}
DIR=$(cd "$(dirname "$0")" && pwd)
FONT=${FONT:-/usr/share/fonts/opentype/inter/Inter-Medium.otf}
[[ -f "$FONT" ]] || FONT=$(fc-match -f '%{file}' sans)

# Timed captions (seconds from the start of the phone clip) - edit to match your recording.
CAPS=(
  "0:8:Airplane mode ON - everything runs on the phone"
  "8:26:Goal in Hinglish -> Llama 3.2 -> validated JSON"
  "26:52:Graph-CAT picks the most informative question"
  "52:64:Skill map: red = gap, green = mastered"
  "64:86:Plan ranked by value per hour, with reasons"
  "86:100:Hints never reveal the answer; plan adapts"
  "100:110:On-device benchmark - 0 permissions"
)
DT=""
for c in "${CAPS[@]}"; do
  a=${c%%:*}; r=${c#*:}; b=${r%%:*}; t=${r#*:}; t=${t//:/\\:}
  DT+=",drawtext=fontfile='$FONT':text='$t':x=760:y=660:fontsize=44:fontcolor=white:enable='between(t,$a,$b)'"
done

TMP=$(mktemp -d)
# Title and end cards as 5 s clips
ffmpeg -y -loglevel error -loop 1 -t 5 -i "$DIR/title.png" -f lavfi -t 5 -i anullsrc=r=48000:cl=stereo \
  -c:v libx264 -preset veryfast -crf 18 -r 30 -pix_fmt yuv420p -c:a aac -shortest "$TMP/a.mp4"
ffmpeg -y -loglevel error -loop 1 -t 5 -i "$DIR/end.png" -f lavfi -t 5 -i anullsrc=r=48000:cl=stereo \
  -vf "drawtext=fontfile='$FONT':text='Code, report and results\: $REPO':x=160:y=905:fontsize=36:fontcolor=0xC4CAEB" \
  -c:v libx264 -preset veryfast -crf 18 -r 30 -pix_fmt yuv420p -c:a aac -shortest "$TMP/c.mp4"
# Phone section: scale to 1040 px tall, overlay into the slot of frame.png, add captions
ffmpeg -y -loglevel error -loop 1 -i "$DIR/frame.png" -i "$PHONE" -f lavfi -i anullsrc=r=48000:cl=stereo \
  -filter_complex "[1:v]scale=-2:1040,setsar=1[p];[0:v][p]overlay=x=170+(500-overlay_w)/2:y=20:shortest=1${DT},format=yuv420p[v]" \
  -map "[v]" -map 2:a -c:v libx264 -preset veryfast -crf 20 -r 30 -c:a aac -shortest "$TMP/b.mp4"
printf "file '%s'\n" "$TMP/a.mp4" "$TMP/b.mp4" "$TMP/c.mp4" > "$TMP/list.txt"
if [[ -n "$VOICE" ]]; then
  ffmpeg -y -loglevel error -f concat -safe 0 -i "$TMP/list.txt" -i "$VOICE" -map 0:v -map 1:a -c:v copy -c:a aac -shortest "$OUT"
else
  ffmpeg -y -loglevel error -f concat -safe 0 -i "$TMP/list.txt" -c copy "$OUT"
fi
rm -rf "$TMP"
ffprobe -v error -show_entries stream=width,height,codec_name -show_entries format=duration -of compact "$OUT"
echo "Wrote $OUT (target: 1920x1080, 16:9, MP4, <= 2:00)"
