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
# Taps something further down a long screen, scrolling until it is in view.
tap_scrolling() { for i in 1 2 3 4 5 6 7 8 9 10; do timeout 60 python3 tools/tap.py "$1" > /dev/null && { echo "tap: $1" | tee -a "$OUT/summary.txt"; return 0; }; swipe_up; sleep 1; done; echo "tap: '$1' not found on the screen" | tee -a "$OUT/summary.txt"; }

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

# The opening plays only with animations on, which this test phone has off: on for it, recorded, then off again.
animations() { for s in animator_duration_scale transition_animation_scale window_animation_scale; do adb shell settings put global $s "$1"; done; }
animations 1
adb shell screenrecord --time-limit 7 /sdcard/opening.mp4 & recording=$!
sleep 1
start;                    shot 00a-opening 0.9
shot 00b-opening 0.5
shot 00c-opening 0.4
wait $recording; adb pull /sdcard/opening.mp4 "$OUT/00-opening.mp4" > /dev/null 2>&1
animations 0
sleep 1;                  shot 00-welcome 2
tap_scrolling "Start watching"; shot 01-home 16
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
# Play on TV: the video starts here silently to read its captions, then goes to the TV's YouTube app.
# YouTube will not play for this test phone (below), so it ends at the question about captions.
tap "Play on TV";         shot 09c-play-on-tv 22
timeout 60 python3 tools/tap.py "Got it" > /dev/null   # the phone's own one-time note about full screen
back; back;               sleep 2
swipe_up; swipe_up;       sleep 1
tap "Show comments";      shot 09b-comments 8
# Playing a YouTube video is not tried here: YouTube asks visitors from data centres, such as
# this test phone, to sign in first. On a home or mobile connection it plays.

start;                    sleep 5
tap "Settings";           shot 11-settings 3
swipe_up;                 shot 11b-settings-language 2
tap_scrolling "Choose words"; shot 12-words 3
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
tap_scrolling "Filter report"; shot 14r-filter-report 3
back
tap_scrolling "Skip buttons"; shot 15-settings-player 2
swipe_up;                 shot 15b-settings-app 2

# ---- Clean copies for the TV, from pages served by the test computer (with captions) ----
make_copy() {  # $1: a name for the files, $2: the page
  adb logcat -c
  adb shell am start -a android.intent.action.VIEW -d "http://10.0.2.2:8765/$2" -n $PKG/.browser.BrowserActivity > /dev/null
  shot "$1-page" 10
  tap "More";               sleep 1
  tap "Send to TV";         shot "$1-send" 3
  tap "Clean copy to TV";   sleep 3
  timeout 60 python3 tools/tap.py "Allow" > /dev/null
  shot "$1-making" 5
  for i in $(seq 1 60); do
    if adb logcat -d -s SafeWatch:I | grep -q "clean copy made\|clean copy not made"; then break; fi
    sleep 10
  done
  adb logcat -d -s SafeWatch:I | grep -i "clean copy" | tee -a "$OUT/summary.txt"
  shot "$1-copies" 3
  timeout 60 adb exec-out run-as $PKG sh -c 'cat "$(ls -t files/clean/*.mp4 | head -1)"' > "$OUT/$1.mp4"
  ls -la "$OUT/$1.mp4" | tee -a "$OUT/summary.txt"
  [ -s "$OUT/$1.mp4" ] || rm -f "$OUT/$1.mp4"
  adb logcat -d -s SafeWatch:I > "$OUT/$1-log.txt"
}
if curl -s -o /dev/null -m 5 http://127.0.0.1:8765/page.html; then
  start;                  sleep 5
  tap "Settings";         sleep 2
  for i in 1 2 3 4 5 6; do timeout 60 python3 tools/tap.py "Test the blur" > /dev/null && { echo "tap: Test the blur (for the clean copy)" | tee -a "$OUT/summary.txt"; break; }; swipe_up; sleep 1; done
  make_copy 18-file-copy page.html
  tap "Test clip";        shot 18e-copy-options 2
  tap "Play on a TV…";    shot 18f-tv-search 8
  back
  tap_scrolling "Link with TV code"; shot 18g-link-youtube 2
  back; back
  make_copy 18-stream-copy stream.html
  back; back

  # ---- Superclean, with the test computer standing in for Anthropic (the app uses it only with this made-up key) ----
  start;                  sleep 5
  tap "Settings";         sleep 2
  tap_scrolling "Claude API key"; sleep 2
  timeout 60 python3 tools/tap.py "sk-ant-" contains > /dev/null
  adb shell input text test-key-for-the-device-check; sleep 1
  tap "SAVE";             sleep 2
  shot 22-settings-superclean 1
  tap "What it takes out"; shot 22a-superclean-choices 2
  swipe_up; swipe_up;     shot 22b-superclean-choices-lower 2
  tap "CANCEL";           sleep 1
  adb logcat -c
  adb shell am start -a android.intent.action.VIEW -d "http://10.0.2.2:8765/page.html" -n $PKG/.browser.BrowserActivity > /dev/null
  sleep 10
  tap "More";             sleep 1
  tap "Send to TV";       shot 22c-send-superclean 3
  tap "Superclean to TV"; shot 22d-superclean-looking 1
  shot 22e-superclean-guide 8
  swipe_up;               shot 22f-superclean-guide-lower 2
  swipe_up; swipe_up;     shot 22g-superclean-start 2
  tap_scrolling "Superclean and make the copy"; sleep 3
  timeout 60 python3 tools/tap.py "Allow" > /dev/null
  shot 22h-superclean-making 4
  for i in $(seq 1 60); do
    if adb logcat -d -s SafeWatch:I | grep -q "clean copy made\|clean copy not made"; then break; fi
    sleep 10
  done
  adb logcat -d -s SafeWatch:I | grep -i "clean copy\|superclean\|parents guide" | tee -a "$OUT/summary.txt"
  shot 22i-superclean-copies 3
  timeout 60 adb exec-out run-as $PKG sh -c 'cat "$(ls -t files/clean/*.mp4 | head -1)"' > "$OUT/22-superclean-copy.mp4"
  [ -s "$OUT/22-superclean-copy.mp4" ] || rm -f "$OUT/22-superclean-copy.mp4"
  tap "Test clip";        shot 22j-copy-options 2
  back
  adb logcat -d -s SafeWatch:I > "$OUT/22-superclean-log.txt"
  cp /tmp/tvsite/anthropic-requests.txt "$OUT/22-anthropic-requests.txt" 2>/dev/null || echo "the stand-in for Anthropic was sent nothing" | tee -a "$OUT/summary.txt"
  back; back
else
  echo "no test site for the clean copy" | tee -a "$OUT/summary.txt"
fi

# ---- The Browser tab: quick links, the bar at the bottom, hiding it, its menu ----
start;                    sleep 5
tap "Browser";            shot 19-browser-links 4
tap "Wikipedia";          shot 19b-browser-page 10
swipe_up;                 shot 19c-bar-hidden 2
adb shell input swipe 540 900 540 1500 300; shot 19d-bar-back 2
tap "More";               shot 19e-browser-menu 2
back
cp /tmp/tvsite/setup.log "$OUT/test-site-setup.txt" 2>/dev/null; ls -la /tmp/tvsite >> "$OUT/test-site-setup.txt" 2>&1

# ---- Arranging the tabs: move Home one place along, icons only, then put everything back ----
start;                    sleep 5
tap "Settings";           sleep 2
tap_scrolling "Arrange tabs"; shot 20-arrange-tabs 2
tap "Move down";          shot 20b-arrange-moved 1
tap "DONE";               shot 20c-tabs-moved 2
tap "Icons only";         shot 20d-icons-only 2
tap "Icons and names";    sleep 1
tap "Arrange tabs";       sleep 1
tap "RESET";              shot 20e-tabs-reset 2

# ---- TV Mode, on a pretend TV (a second screen drawn over this phone's own) ----
adb shell settings put global overlay_display_devices 720x405/160; sleep 4
adb shell input swipe 540 1200 540 260 700; sleep 1   # moves the pretend TV out of the remote's way
start;                    shot 21-tv-offer 6
timeout 60 python3 tools/tap.py "TV MODE" | tee -a "$OUT/summary.txt" | grep -q " at " || adb shell am start -n $PKG/.tv.TvModeActivity > /dev/null
shot 21b-tv-home 8
tap "Home";               sleep 1
pad() { timeout 60 python3 tools/tap.py Touchpad exact "$1" | tee -a "$OUT/summary.txt"; sleep 1; }
pad 0.5,0.9; pad 0.5,0.9; shot 21c-tv-moved 1
pad 0.9,0.5; pad 0.9,0.5; shot 21d-tv-moved 1
pad 0.5,0.5;              shot 21e-tv-page 12
adb shell input swipe 400 1500 700 1300 300; shot 21f-tv-pointer 2
tap "Back";               shot 21g-tv-back 4
tap "Exit TV Mode";       shot 21h-tv-exit 3
adb shell settings delete global overlay_display_devices; sleep 2
adb logcat -d -s SafeWatch:I | grep -i "TV Mode" | tee -a "$OUT/summary.txt"

start;                    sleep 5
tap "Settings";           sleep 2
tap_scrolling "Light";    shot 17-light 5
start;                    shot 17b-light-home 8
echo "phone state at the end: $(timeout 20 adb get-state 2>&1)" | tee -a "$OUT/summary.txt"

echo "crash log:" > "$OUT/crash.txt"
timeout 30 adb logcat -d -b crash >> "$OUT/crash.txt"
echo "problems found in the log:" > "$OUT/problems.txt"
grep -n "FATAL EXCEPTION\|Fatal signal\|ANR in\|Process com.safewatch.app.*died\|has died" "$OUT/logcat.txt" | tail -30 >> "$OUT/problems.txt"
echo "--- problems ---"; cat "$OUT/problems.txt"
echo "--- summary ---"; cat "$OUT/summary.txt"
exit 0
