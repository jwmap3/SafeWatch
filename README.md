# SafeWatch

A personal Android app for watching your streaming services with the parts you
don't want removed. It mutes cursing and blurs or skips nudity, using filter
levels you set once.

## Download

[Download SafeWatch.apk](https://github.com/jwmap3/SafeWatch/releases/download/latest/SafeWatch.apk)
and open it on an Android phone (Android 9 or newer). The phone will ask you to
allow installing apps from your browser or file manager the first time. The file
is rebuilt automatically whenever the code changes, and a newer download
installs over the one already on the phone.

## How it works

**Home** shows a featured title and shelves of what is new on each of your
services. It refreshes every time the app is opened. Pick your services with
the Edit button in the row of service names.

**Search** finds shows by name and says which service each one is on. Titles on
your own services come first.

**A title's page** shows its description, a Watch button for the service that
has it, and every season and episode.

**Watching** happens inside the app. Watch opens the service's own page for
that title in SafeWatch's built-in browser, which uses the same engine as
Chrome. For Netflix it opens the player itself. Anything that plays in that
browser goes through your filters.

**Accounts** are under Settings. Each service has a Sign in row that opens its
own sign-in page. You sign in once; the sign-in is kept on the phone the way a
browser keeps it, and the app never sees your password. The row shows "Signed
in" afterwards.

**Words** are under Settings > Choose words. Every built-in word is listed
part-hidden ("Sh*t", "F*ck") with its own switch. The Low, Medium and High
levels decide which start switched on; anything you change stays as you set
it. Extra words to mute and Words to allow are still there for anything not
listed.

**Browser** is a tab of its own for any other website. It also appears in the
phone's "Open with" list, and other browsers can send a page to it with their
Share button.

**Send to TV** opens the phone's screen-casting panel. Casting the screen sends
the already filtered picture and sound.

**Video files** on the phone can be opened from the bottom of Home.

## What is filtered automatically

| | Cursing | Nudity |
|---|---|---|
| YouTube and ordinary websites | Yes, from captions | Yes, detected live |
| Video files on the phone | Yes, with the film's subtitle file | Yes, live or by scanning the whole film first |
| Netflix, HBO Max, Prime Video, Disney+, Hulu and similar | Yes, from captions (switch captions on in the player) | Only scenes that have been marked |

The last row is a hard limit, not a missing feature. Those services scramble
their picture so that only the screen can show it. No app or browser on the
phone can look at the frames, so nothing can detect what is in them. For those
services "Mark scene" in the browser lets you mark a scene's start and end
once, and the app remembers it for that title.

## Other limits

- **Playing inside the app.** Services want phone users in their own apps.
  SafeWatch asks them for their computer website instead, which does play in a
  browser. Whether each service then plays after you sign in has not been
  tested, because that needs a subscription.
- **Episodes.** Catalogs do not give web addresses for single episodes, so
  choosing an episode opens the show's page and you pick the episode there.
- **Straight to the player** is only set up for Netflix, whose addresses allow
  it. Other services open on the title's page, where you press play.
- **"Signed in"** is worked out from the service's sign-in cookie where its
  name is known (Netflix, Prime Video, Hulu, Paramount+, YouTube), and
  otherwise from having watched a sign-in from Settings go through. It can be
  wrong after signing out on the service's site.
- **Google sign-in.** Google often refuses sign-in inside in-app browsers.
  YouTube plays without signing in.
- **Live blur in the browser** is checked from the screen, so a blurred picture
  cannot be re-checked. The blur holds for a few seconds, lifts, and is
  re-applied if the scene is still going, which can let a brief glimpse through.
- **Detection makes mistakes.** Expect some misses and some false alarms.
- **Wireless casting** of the protected services usually shows a black
  picture. An HDMI adapter avoids that.

## Where the titles come from

Out of the box the app uses [TVmaze](https://www.tvmaze.com/api), which needs
no account. It lists what each service released in the last two weeks, with
artwork, episodes, and often a direct link to the show's page on the service.
It covers series, not movies.

Adding a free key from [The Movie Database](https://www.themoviedb.org/) under
Settings > Titles switches the app to TMDB, which adds movies and "most popular
on each service" shelves. That path has not been run against the live service
yet.

## Nudity detection

Detection uses the NudeNet model, which the app downloads by itself the first
time it runs (about 11 MB, from NudeNet's published package). Frames are
checked on the phone and never leave it.

## Status

Checked on an automatic test phone (an Android 14 emulator that GitHub runs
after every change, see `.github/workflows/device-check.yml`): the app opens
without crashing; Home loads live titles; search, title pages, episodes,
Settings, the word list, the Browser tab and day and night modes display correctly; a title's
Watch button opens the service's page for it; the detection model downloads
and loads.

Checked on a computer: the filter logic passes its 24 tests; the script that
controls a page's video player passes its test in desktop Chromium; the
detection model was run on sample pictures to confirm the app reads its output
correctly.

Not yet checked anywhere: playing a signed-in title on a paid service; muting
and blurring on a real phone during playback; casting to a TV.

## Layout

| Path | What is in it |
|---|---|
| `core/` | Filter logic with no Android in it: word matching, subtitle parsing, mute/skip/blur decisions, reading the detector's output. Has the tests. |
| `app/` | The Android app. |
| `app/.../HomeScreen.kt`, `SearchScreen.kt`, `FiltersScreen.kt` | The Home, Search and Settings tabs. `MainActivity.kt` holds them. |
| `app/.../WordsActivity.kt` | The list of words with a switch each. The words themselves are in `core/.../WordList.kt`. |
| `app/.../TitleActivity.kt` | A title's page. |
| `app/.../browser/` | The built-in browser and its live detection. |
| `app/src/main/assets/safewatch.js` | The script the browser adds to every page to control its video player. |
| `app/.../player/` | The video file player and the ahead-of-time scan. |
| `app/.../data/` | Saved settings, marked scenes, the list of services, the title catalog. |
| `app/.../detect/` | The nudity detector and its first-run download. |
| `app/.../ui/` | The design kit: colours, cards, rows, posters, controls. Colours are in `res/values` (day) and `res/values-night`. |
| `tools/` | The test-phone script and the browser script test. |

## Building

Open the folder in Android Studio and run the `app` configuration, or from a
terminal:

    ./gradlew :core:test          # run the logic tests
    ./gradlew :app:assembleDebug  # build the APK

Work happens on the `dev` branch, where every push runs the test phone. Pushes
to `main` rebuild the download.

To run the browser script test (needs Node, Playwright and ffmpeg):

    cd tools/browser-test
    ffmpeg -f lavfi -i testsrc=size=320x180:rate=15 -f lavfi -i sine=frequency=440 -t 30 -g 15 -c:v libvpx -b:v 200k -c:a libvorbis v.webm
    npm install playwright && node test.js

## Not built yet

- Gore filtering.
- Shared scene lists, so a title marked once does not need marking again by anyone else.
- Reading a service's caption file ahead of time, so muting lands exactly on the word instead of on the whole caption line.
- Speech recognition, for cursing in videos that have no captions.
- Blurring only the detected area instead of the whole picture.
- A Windows version.
