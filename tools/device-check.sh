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
tap "Test the blur";      sleep 1
if [ -n "$VIDEO" ]; then
  adb shell am start -a android.intent.action.VIEW -d "$VIDEO" -n $PKG/.browser.BrowserActivity > /dev/null
  sleep 4; tap "Got it"
  shot 14-player 12
  adb shell input tap 1200 250
  shot 14b-player-controls 1
  shot 14c-player-later 10
  shot 14d-player-later 12
  shot 14e-player-later 12
fi
adb logcat -d -s SafeWatch:I > "$OUT/filter-log.txt"; echo "(end of filter log)" >> "$OUT/filter-log.txt"

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
