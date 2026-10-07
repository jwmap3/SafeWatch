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

start;                    shot 01-home 16
swipe_up;                 shot 02-home-shelves 3
start;                    sleep 5
tap "Details";            shot 03-title 8
back
tap "Search";             sleep 2
adb shell input text "lanterns"; adb shell input keyevent 66
shot 04-search 8
tap "Lanterns";           shot 05-lanterns 8
tap "Watch on HBO Max";   shot 06-watch-hbomax 16
tap "Home";               sleep 2
tap "Filters";            shot 07-filters 3
tap "Browser";            shot 08-browser 5
tap "Home";               sleep 2
tap "YouTube";            shot 09-youtube 14
tap "Home";               sleep 2

adb shell cmd uimode night yes
start;                    shot 10-home-night 8
tap "Details";            shot 11-title-night 6
back
tap "Filters";            shot 12-filters-night 3
tap "Browser";            shot 13-browser-night 6
adb shell cmd uimode night no

# Netflix goes last: its site has stopped the test phone before.
start;                    sleep 6
tap "Netflix";            shot 14-netflix 20
echo "phone state after Netflix: $(timeout 20 adb get-state 2>&1)" | tee -a "$OUT/summary.txt"

timeout 30 adb logcat -d -b crash > "$OUT/crash.txt"
grep -n "FATAL EXCEPTION\|Fatal signal\|ANR in\|lowmemorykiller.*safewatch\|Process com.safewatch.app.*died" "$OUT/logcat.txt" | tail -20 > "$OUT/problems.txt"
echo "--- problems ---"; cat "$OUT/problems.txt"
echo "--- summary ---"; cat "$OUT/summary.txt"
exit 0
