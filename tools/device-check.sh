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
home() { adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity > /dev/null; }

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
: > "$OUT/summary.txt"

home;                     shot 01-home 5
tap "Language";           shot 02-filters
adb shell input swipe 500 1600 500 500 300; shot 03-filters-lower 2
home;                     sleep 3
tap "YouTube";            shot 04-browser 12
home;                     sleep 3
adb shell cmd uimode night yes
home;                     shot 05-home-night 5
adb shell cmd uimode night no

adb logcat -d -b crash > "$OUT/crash.txt"
adb logcat -d > "$OUT/logcat.txt"
echo "--- crash report ---"; cat "$OUT/crash.txt"
echo "--- summary ---"; cat "$OUT/summary.txt"
exit 0
