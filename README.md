# SafeWatch

A personal Android app that plays video with the parts you don't want removed:
it mutes cursing and blurs or skips nudity, using filter levels you set once.

This first version covers **language** and **nudity**.

## What it does

**Streaming services.** The home screen lists your services (YouTube, Netflix,
Prime Video and others; you choose which). Tapping one opens its website in the
app's built-in browser. You sign in there, on the service's own page, and pick
what to watch. The app never sees your password; the sign-in is remembered the
way any browser remembers it.

**Built-in browser.** Anything that plays in the browser goes through the
filters:

- Cursing is muted using the video's captions. Where the player exposes its
  caption track, the app reads ahead and mutes on time. Where captions are only
  drawn on the page (YouTube, for example, with captions switched on), it mutes
  as the words appear, which is slightly late.
- Nudity is detected live from what is on screen and the picture is blurred.
- "Mark scene" lets you mark the start and end of a scene yourself and choose
  blur, skip or mute. The app remembers it for that page.

**Search.** Type a title on the home screen to get one-tap searches on each of
your services. A web address typed there opens directly.

**Video files.** "Open a video file" plays a file from the phone. Load the
film's subtitle file (.srt or .vtt) to mute cursing. "Scan" checks the whole
film for nudity ahead of time, so the blur or skip lands exactly on the scene.

**Send to TV.** Opens the phone's screen-casting panel. Casting the screen
sends the already filtered picture and sound, so the filters keep working on
the TV.

**Filters.** Language and nudity each have Off, Low, Medium and High. There is a
separate blasphemy switch, a list of extra words to mute and a list of words to
allow.

**Day and night.** The app follows the phone's light or dark setting, or you
can pick one under Filters > Appearance.

## Limits to know about

- **Protected streams.** Netflix-style services protect their video, so the app
  cannot see their frames: live nudity detection does not work on them, and they
  usually show black when cast wirelessly (an HDMI adapter avoids that). Caption
  muting and marked scenes still apply. Some services may refuse to play in an
  in-app browser at all; each one needs trying on a phone.
- **Google sign-in.** Google often refuses sign-in inside in-app browsers.
  YouTube plays without signing in.
- **Live blur in the browser** is checked from the screen, so a blurred picture
  cannot be re-checked. The blur holds for a few seconds, lifts, and is
  re-applied if the scene is still going, which can let a brief glimpse through.
- **Detection makes mistakes.** Expect some misses and some false alarms.
- **Service search links** were written from memory of each site's address
  format and need checking on a phone.

## Nudity detection model

Automatic detection uses the NudeNet detector (the `320n.onnx` file from the
[NudeNet project](https://github.com/notAI-tech/NudeNet)). The file is not in
this repository. Download it, then import it in the app under
Filters > Detection model. Check NudeNet's licence before sharing a build of
the app that includes it.

## Status

Not yet built into an APK or run on a phone. What has been checked so far:

- The filter logic in `core/` passes its 22 tests.
- The browser script was run in desktop Chromium against a test video
  (`tools/browser-test`): caption muting, skipping, blurring, on-page captions.
- The app code type-checks against the Android framework. The support
  libraries (AndroidX, Media3, ONNX Runtime) were not available for that check,
  so the first real build may still need small fixes.

## Layout

| Path | What is in it |
|---|---|
| `core/` | Filter logic with no Android in it: word matching, subtitle parsing, mute/skip/blur decisions, reading the detector's output. Has the tests. |
| `app/` | The Android app. |
| `app/src/main/assets/safewatch.js` | The script the browser adds to every page to control its video player. |
| `app/.../browser/` | The built-in browser and its live detection. |
| `app/.../player/` | The video file player and the ahead-of-time scan. |
| `app/.../ui/` | The design kit: colours, cards, rows, controls. Colours are in `res/values` (day) and `res/values-night`. |
| `tools/browser-test/` | The browser script test. |

## Building

Open the folder in Android Studio and run the `app` configuration, or from a
terminal:

    ./gradlew :core:test          # run the logic tests
    ./gradlew :app:assembleDebug  # build the APK

To run the browser script test (needs Node, Playwright and ffmpeg):

    cd tools/browser-test
    ffmpeg -f lavfi -i testsrc=size=320x180:rate=15 -f lavfi -i sine=frequency=440 -t 30 -g 15 -c:v libvpx -b:v 200k -c:a libvorbis v.webm
    npm install playwright && node test.js

## Not built yet

- Gore filtering.
- Speech recognition, for cursing in videos that have no captions.
- Blurring only the detected area instead of the whole picture.
- Shared scene lists, so a film marked once does not need marking again.
- Casting a file to the TV directly instead of mirroring the screen.
