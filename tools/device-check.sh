#!/bin/bash
# Installs the app on the test phone, walks through its screens, and saves a
# picture of each one plus any crash report into device-out/.
set -u
OUT=device-out
mkdir -p "$OUT"
PKG=com.safewatch.app

shot() { sleep "${2:-3}"; timeout 30 adb exec-out screencap -p > "$OUT/$1.png"; alive "$1"; }
alive() { if [ -z "$(timeout 20 adb shell pidof $PKG | tr -d '\r')" ]; then echo "NOT RUNNING after $1" | tee -a "$OUT/summary.txt"; fi; }
tap() { timeout 60 python3 tools/tap.py "$@" | tee -a "$OUT/summary.txt"; }
back() { adb shell input keyevent 4; sleep 1; }
swipe_up() { adb shell input swipe 540 1700 540 600 400; }
start() { adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity > /dev/null; }

# Shared scene lists: is the public service that offers them answering, and what does it send?
for u in "https://cleanstream.elfhosted.com/api/filters" "https://cleanstream.elfhosted.com/api/skips/tt0133093" "https://cleanstream.elfhosted.com/api/skips/tt0120338" "https://cleanstream.elfhosted.com/manifest.json"; do
  name=$(echo "$u" | sed 's|.*/api/||; s|.*/||; s|[^A-Za-z0-9]|-|g')
  code=$(curl -sL -m 25 -o "$OUT/scene-lists-$name.txt" -w '%{http_code} %{content_type} %{size_download}' "$u")
  echo "scene lists: $u answered $code" | tee -a "$OUT/scene-lists.txt"
  head -c 6000 "$OUT/scene-lists-$name.txt" > "$OUT/scene-lists-$name.cut" && mv "$OUT/scene-lists-$name.cut" "$OUT/scene-lists-$name.txt"
  [ -s "$OUT/scene-lists-$name.txt" ] || echo "(nothing)" > "$OUT/scene-lists-$name.txt"
done

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
: > "$OUT/summary.txt"
# Keeps a running copy of the phone's log, so there is a record even if the phone stops responding.
adb logcat -v time > "$OUT/logcat.txt" &

start;                    shot 00-welcome 6
swipe_up; swipe_up; sleep 1
tap "Start watching";     shot 01-home 16
swipe_up;                 shot 02-home-shelves 3

start;                    sleep 6
tap "Netflix";            shot 03-service 10
swipe_up;                 shot 03b-service-lower 3

start;                    sleep 5
tap "Search";             sleep 2
adb shell input text "lanterns"; adb shell input keyevent 66
shot 04-search 8
tap "Lanterns";           shot 05-title 8
tap "Watch on HBO Max";   shot 06-watch 16

start;                    sleep 5
tap "YouTube";            shot 07-youtube 16
adb shell input tap 540 400; sleep 1
adb shell input text "ted%screativity"; adb shell input keyevent 66
shot 08-youtube-search 10
adb shell input tap 300 760
shot 09-video 10
swipe_up; swipe_up;       sleep 1
tap "Show comments";      shot 09b-comments 8
# Playing a YouTube video is not tried here: YouTube asks visitors from data centres, such as
# this test phone, to sign in first. On a home or mobile connection it plays.

start;                    sleep 5
tap "Settings";           shot 11-settings 3
swipe_up;                 shot 11b-settings-language 2
tap "Choose words";       shot 12-words 3
start;                    sleep 5
tap "Settings";           sleep 2
swipe_up; swipe_up;       shot 13-settings-nudity 2
# The emulator's browser engine has no decoder for the usual MP4 video, so the film has to be WebM.
VIDEO=""
for u in \
  "https://media.xiph.org/tearsofsteel/tears_of_steel_1080p.webm" \
  "https://media.xiph.org/tearsofsteel/tears_of_steel_720p.webm" \
  "https://media.xiph.org/mango/tears_of_steel_1080p.webm" \
  "https://media.w3.org/2010/05/sintel/trailer.webm"; do
  code=$(curl -s -o /dev/null -r 0-2000 -m 20 -w '%{http_code}' "$u")
  echo "test film $u answered $code" | tee -a "$OUT/summary.txt"
  if [ -z "$VIDEO" ] && { [ "$code" = "200" ] || [ "$code" = "206" ]; }; then VIDEO="$u#t=40"; fi
done
# The settings page is long: scroll until the row is in view.
for i in 1 2 3 4 5; do timeout 60 python3 tools/tap.py "Test the blur" > /dev/null && { echo "tap: Test the blur" | tee -a "$OUT/summary.txt"; break; }; swipe_up; sleep 1; done
sleep 1
if [ -n "$VIDEO" ]; then
  adb shell am start -a android.intent.action.VIEW -d "$VIDEO" -n $PKG/.browser.BrowserActivity > /dev/null
  sleep 4; tap "Got it"
  shot 14-player 12
  adb shell input tap 1200 250
  shot 14b-player-controls 1
  shot 14c-player-later 8
  shot 14d-player-later 8
  shot 14e-player-later 8
  shot 14f-player-later 8
  shot 14g-player-later 8
  shot 14h-player-later 8
  shot 14i-player-later 8
  shot 14j-player-later 8
  shot 14k-player-later 8
  # What the hidden look-ahead copy of the video saw, kept by the app while the blur test is on.
  timeout 30 adb exec-out run-as $PKG cat cache/look-ahead-test.png > "$OUT/14z-look-ahead-copy.png" 2>/dev/null
  [ -s "$OUT/14z-look-ahead-copy.png" ] || { rm -f "$OUT/14z-look-ahead-copy.png"; echo "no picture from the look-ahead copy" | tee -a "$OUT/summary.txt"; }
fi
adb logcat -d -s SafeWatch:I > "$OUT/filter-log.txt"; echo "(end of filter log)" >> "$OUT/filter-log.txt"

start;                    sleep 5
tap "Settings";           sleep 2
swipe_up;                 sleep 1
tap "Filter report";      shot 14r-filter-report 3

# ---- A clean copy for the TV, from a page's video (served by the test computer, with captions) ----
if curl -s -o /dev/null -m 5 http://127.0.0.1:8765/page.html; then
  start;                  sleep 5
  tap "Settings";         sleep 2
  for i in 1 2 3 4 5 6; do timeout 60 python3 tools/tap.py "Test the blur" > /dev/null && { echo "tap: Test the blur (for the clean copy)" | tee -a "$OUT/summary.txt"; break; }; swipe_up; sleep 1; done
  adb logcat -c
  adb shell am start -a android.intent.action.VIEW -d "http://10.0.2.2:8765/page.html" -n $PKG/.browser.BrowserActivity > /dev/null
  shot 18-clip-page 10
  tap "Send to TV";       shot 18b-send-to-tv 3
  tap "Clean copy to TV"; shot 18c-making 8
  for i in $(seq 1 60); do
    if adb logcat -d -s SafeWatch:I | grep -q "clean copy made\|clean copy not made"; then break; fi
    sleep 10
  done
  adb logcat -d -s SafeWatch:I | grep -i "clean copy" | tee -a "$OUT/summary.txt"
  shot 18d-clean-copies 3
  tap "Test clip";        shot 18e-copy-options 2
  tap "Play on a TV…";    shot 18f-tv-search 8
  timeout 60 adb exec-out run-as $PKG sh -c 'cat files/clean/*.mp4' > "$OUT/clean-copy.mp4"
  ls -la "$OUT/clean-copy.mp4" | tee -a "$OUT/summary.txt"
  [ -s "$OUT/clean-copy.mp4" ] || rm -f "$OUT/clean-copy.mp4"
  adb logcat -d -s SafeWatch:I > "$OUT/clean-log.txt"
  back; back
else
  echo "no test site for the clean copy" | tee -a "$OUT/summary.txt"
fi

start;                    sleep 5
tap "Settings";           sleep 2
swipe_up; swipe_up; swipe_up; swipe_up; swipe_up; shot 15-appearance 2
tap "Light";              shot 17-light 5
start;                    shot 17b-light-home 8
echo "phone state at the end: $(timeout 20 adb get-state 2>&1)" | tee -a "$OUT/summary.txt"

echo "crash log:" > "$OUT/crash.txt"
timeout 30 adb logcat -d -b crash >> "$OUT/crash.txt"
echo "problems found in the log:" > "$OUT/problems.txt"
grep -n "FATAL EXCEPTION\|Fatal signal\|ANR in\|Process com.safewatch.app.*died\|has died" "$OUT/logcat.txt" | tail -30 >> "$OUT/problems.txt"
echo "--- problems ---"; cat "$OUT/problems.txt"
echo "--- summary ---"; cat "$OUT/summary.txt"
exit 0
