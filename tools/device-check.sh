#!/bin/bash
# Installs the app on the test phone, walks through its screens, and saves a
# picture of each one plus any crash report into device-out/.
set -u
OUT=device-out
mkdir -p "$OUT"
PKG=com.safewatch.app

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; alive "$1"; }
alive() { if [ -z "$(adb shell pidof $PKG | tr -d '\r')" ]; then echo "NOT RUNNING after $1" | tee -a "$OUT/summary.txt"; fi; }
tap() { python3 tools/tap.py "$@" | tee -a "$OUT/summary.txt"; }
back() { adb shell input keyevent 4; sleep 1; }
swipe_up() { adb shell input swipe 540 1700 540 600 400; }
start() { adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity > /dev/null; }

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
: > "$OUT/summary.txt"

start;                    shot 01-home 16
swipe_up;                 shot 02-home-shelves 3
start;                    sleep 5
tap "Details";            shot 03-title 8
back
tap "Search";             sleep 2
adb shell input text "lanterns"; adb shell input keyevent 66
shot 04-search 8
tap "Lanterns";           shot 05-lanterns 8
swipe_up;                 shot 06-lanterns-episodes 3
adb shell input swipe 540 600 540 1700 300; sleep 1
tap "Watch on HBO Max";   shot 07-watch-hbomax 16
tap "Home";               sleep 2
tap "Filters";            shot 08-filters 3
tap "Browser";            sleep 3
tap "Home";               sleep 2
tap "Home";               sleep 2
tap "Watch";              shot 09-watch-netflix 16
tap "Home";               sleep 2
tap "YouTube";            shot 10-youtube 14
tap "Home";               sleep 2

adb shell cmd uimode night yes
start;                    shot 11-home-night 8
tap "Details";            shot 12-title-night 6
back
tap "Filters";            shot 13-filters-night 3
tap "Browser";            shot 14-browser-night 6
adb shell cmd uimode night no

adb logcat -d -b crash > "$OUT/crash.txt"
adb logcat -d > "$OUT/logcat.txt"
echo "--- crash report ---"; cat "$OUT/crash.txt"
echo "--- summary ---"; cat "$OUT/summary.txt"
exit 0
