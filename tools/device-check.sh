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
back; back; back
tap "Settings";           shot 07-settings 3
tap "Netflix";            shot 07b-signin-netflix 14
back; back; back
swipe_up;                 shot 07c-settings-language 2
tap "Choose words";       shot 07d-words 3
swipe_up;                 shot 07e-words-lower 2
back
swipe_up;                 sleep 1
tap "Test the blur";      sleep 1
# A talk with the speaker's face on screen, opened straight in the browser: the blur test should hide it.
adb shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/embed/iG9CE55wbtY?autoplay=1\&cc_load_policy=1\&start=60" -n $PKG/.browser.BrowserActivity > /dev/null
shot 07f-blur-test 25
shot 07g-blur-test-later 6
tap "Home";               sleep 2
tap "Home";               sleep 2
tap "Browser";            shot 08-browser 5
tap "Home";               sleep 2
adb shell input swipe 900 1650 200 1650 300; sleep 1
tap "YouTube";            shot 09-youtube 14
adb shell input tap 540 800
shot 09b-youtube-playing 25
back; back; back

adb shell cmd uimode night yes
start;                    shot 10-home-night 8
tap "Details";            shot 11-title-night 6
back
tap "Settings";           shot 12-settings-night 3
tap "Choose words";       shot 12b-words-night 3
back
tap "Browser";            shot 13-browser-night 6
adb shell cmd uimode night no

# Opening a Netflix title is left out. It starts Netflix's protected player, and the
# emulator's stand-in for that hardware takes the whole test phone offline, so that
# step can only be tried on a real phone.
start;                    sleep 6
tap "Hulu";               shot 14-hulu 16
echo "phone state at the end: $(timeout 20 adb get-state 2>&1)" | tee -a "$OUT/summary.txt"

echo "crash log:" > "$OUT/crash.txt"
timeout 30 adb logcat -d -b crash >> "$OUT/crash.txt"
echo "problems found in the log:" > "$OUT/problems.txt"
grep -n "FATAL EXCEPTION\|Fatal signal\|ANR in\|Process com.safewatch.app.*died\|has died" "$OUT/logcat.txt" | tail -30 >> "$OUT/problems.txt"
echo "--- problems ---"; cat "$OUT/problems.txt"
echo "--- summary ---"; cat "$OUT/summary.txt"
exit 0
