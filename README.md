# SafeWatch

A personal Android app for watching your streaming services and YouTube with
the parts you don't want removed. It mutes cursing and blurs or skips nudity,
using filters you set once, and it keeps everything in one layout of its own.

## Download

[Download SafeWatch.apk](https://github.com/jwmap3/SafeWatch/releases/download/latest/SafeWatch.apk)
and open it on an Android phone (Android 9 or newer). The phone will ask you to
allow installing apps from your browser or file manager the first time. The file
is rebuilt automatically whenever the code changes, and a newer download
installs over the one already on the phone.

## How it works

**Welcome.** The first launch asks which services you use and lets you sign in
to each. You sign in on the service's own page; the sign-in is kept on the
phone the way a browser keeps it. SafeWatch has no account or server of its
own and never sees a password.

**Home** shows a featured title, your services, and shelves of what is popular
and new on each one. It refreshes every time the app is opened.

**A service's page** (tap its name on Home) shows that service's popular
shows, new shows and biggest genres as shelves.

**Search** finds shows by name and says which service each is on. Titles on
your own services come first.

**A title's page** shows its description, a Watch button for the service that
has it, and every season and episode.

**YouTube** has a tab of its own in the app's layout. Signed in to YouTube
(once, on Google's own page, from the tab or from Settings), it shows your own
YouTube: your subscriptions, your home feed and your searches. These are read
from YouTube's website, signed in as you, in a browser that runs out of sight;
the app shows what that website is given in its own layout. Each video has a
page with its description, what YouTube suggests next and the comments, with
filtered words part-hidden ("s***"). Not signed in, the tab shows shelves for
subjects you choose and channels you follow here.

**The player.** Watch opens a full-screen player with a title bar instead of
an address bar. When the video plays the screen turns sideways and only the
picture is left, with controls that fade out. For YouTube and for links
straight to a video file, the controls are the app's own: play, back and
forward ten seconds, and a bar to scrub along. For the streaming services the
picture is the service's own web player, because the video can only come from
the service.

**Browser** is a tab for any other website, filtered the same way. Searches go
to Google. Pop-ups, message boxes and pages that send you to another site by
themselves are blocked; while a video plays nothing can take you off its page.
Outside a video a bar says what was blocked and offers to open it. It also
appears in the phone's "Open with" list, and other browsers can send a page to
it with their Share button.

**Send to TV** opens the phone's screen casting, which reaches Chromecast,
Roku, Fire TV and most smart TVs, and shows the already filtered picture.

**Settings** holds your accounts, the filters, colours and sounds.

- **Language:** Off, Low, Medium or High, a separate blasphemy switch, and
  "Choose words", which lists every built-in word part-hidden ("Sh*t",
  "F*ck") with its own switch. Extra words to mute and Words to allow cover
  anything not listed. Medium leaves the mild words (d*mn, h*ll, cr*p) alone;
  High mutes them too. "Show captions" is off by default, so a muted word is
  not printed on the screen instead. "No captions, no sound" plays a video the
  filter has nothing to go on for without sound. "Filter report" lists what
  the filter found and every mute with how long it lasted, to copy and send on
  when something is missed.
- **Nudity:** Off, Low, Medium or High, and whether to blur or skip. "Look
  ahead" (on by default) is described below. "Test the blur" blurs faces for
  two minutes, to see the blur working on any video with people in it.
- **Appearance:** dark by default, with light and automatic modes, and three
  colours to choose: primary, background and cards.
- **Sounds:** a few sets of tap and play sounds, all made for this app. A
  fourth set unlocks when the logo on Home is tapped seven times.

## What is filtered automatically

| | Cursing | Nudity |
|---|---|---|
| YouTube | Yes, from captions, read ahead of time | Yes, looked at ahead of time, and live |
| Ordinary websites and video links | Yes, where the video has captions | Yes, looked at ahead of time where a second copy will play, and live |
| Video files on the phone | Yes, with the film's subtitle file | Yes, live or by scanning the whole film first |
| Netflix, HBO Max, Prime Video, Disney+, Hulu and similar | Yes, from captions (switch captions on in the player) | Only scenes that have been marked |

### Knowing before it happens

**Nudity: looking ahead.** While you watch, the app plays a second copy of the
same video where you cannot see or hear it, a few seconds in front of yours.
Each picture from that copy is checked and the result is kept against its
place in the video. By the time your copy gets there the player already knows:
the blur goes up two seconds before the scene and comes down two seconds after
its last flagged picture, and one missed picture in the middle does not lift
it. On a phone that checks slowly the hidden copy runs further in front.

The screen you are watching is still checked live as well. That covers what
has not been looked at ahead (the first second or so of a video, the moments
after you jump somewhere, sites where a second copy will not play) and gives a
second opinion everywhere else. Each live look is also compared with what the
hidden copy showed at the same moment; if they keep differing, the hidden copy
is not seeing your video, so it is stopped and live checking carries on alone.

Looking ahead plays the video twice, so it uses about twice the data. It can
be switched off under Settings > Nudity.

**Cursing: reading ahead.** Cursing is found in a video's captions, so the
app works hard to get them, in this order:

1. *The whole caption track, from the player's manifest.* Streaming players
   are given a list of every piece of a film, captions included, cut into
   small files. The app reads that list and downloads every caption piece
   itself, nearest first, whether or not captions are switched on. This is
   how HBO Max, and many other services, deliver captions.
2. *A caption file the player downloads*, such as YouTube's, which times
   every word, so there the mute covers the word alone. The app switches
   YouTube's captions on (automatic ones included) so the file is fetched.
   YouTube writes swear words in automatic captions as "[ __ ]"; that, and
   other blanked forms ("sh*t", "[bleep]"), are muted at every setting.
3. *The video's own caption track*, where the page uses one.
4. *Captions as they appear on screen*, on players the app knows and on any
   player by where the text sits over the video. These only appear as the
   line is spoken, so a word arriving word by word is muted as it arrives,
   and a whole line is muted over the part where the word should fall.

Lines with times are checked against the captions on screen when both are
there, and corrected if they are early or late. The player's bar says which
the filter has: "Captions read", "Captions live", or "No captions" in red, in
which case cursing cannot be muted and you are told so.

### The paid services

The last row of the table is a hard limit, not a missing feature. Those services scramble
their picture so that only the screen can show it. No app or browser on the
phone can look at the frames, so nothing can detect what is in them. For those
services "Mark scene" in the player lets you mark a scene's start and end
once, and the app remembers it for that title.

## Other limits

- **Playing a service inside the app.** Services want phone users in their own
  apps. SafeWatch asks them for their computer website instead, which does play
  in a browser. Whether each service then plays after you sign in has not been
  tested, because that needs a subscription.
- **Personal rows.** Continue Watching, My List and recommendations made for
  you are kept private by each service, so the app's own pages cannot show
  them. They are on the service's site, one tap away with "Open Netflix".
- **YouTube's lists** (search, up next, comments) are read from the same
  requests YouTube's website makes. YouTube does not publish that format and
  can change it, which would break those lists until the app is updated.
- **YouTube on some networks** asks visitors to sign in to prove they are not
  a bot before it plays. That happens on VPNs and data-centre connections.
- **Episodes.** Catalogs do not give web addresses for single episodes, so
  choosing an episode opens the show's page and you pick the episode there.
  Straight-to-the-player links are only set up for Netflix.
- **"Signed in"** is worked out from the service's sign-in cookie where its
  name is known, and otherwise from having watched a sign-in go through. It can
  be wrong after signing out on the service's site.
- **Cursing without captions.** A video with no captions in English cannot be
  filtered for cursing. The player says so; "No captions, no sound" plays it
  silent instead. Listening to the sound itself would cover these, and is not
  built yet.
- **HBO Max and the other paid services** were built from how their players
  are known to deliver captions, and could not be tried here without a
  subscription. If a title is not muted, the Filter report says why.
- **YouTube sign-in** happens on Google's own page inside the app's browser.
  Google sometimes refuses sign-ins it does not recognise as a full browser; if
  it does, the tab stays on the signed-out shelves.
- **Glimpses.** Where the look-ahead has not been (see above), a scene is only
  caught once it is on screen, so a fraction of a second can show before the
  blur. The blur then stays until the scene ends: it sits in a layer of its own,
  and the app goes on checking the real picture underneath it.
- **Looking ahead on websites** needs the site to play the same video to a
  second visitor. Sites that need a tap to start, or hand out one-time links,
  will not, and the app then checks live only.
- **Detection makes mistakes.** Expect some misses and some false alarms.
- **Casting.** Apple TV cannot be reached from an Android phone's screen
  casting. The protected services usually show a black picture when cast
  wirelessly; an HDMI adapter avoids that.
- **Movies.** The built-in catalog covers series. Adding a free key from
  [The Movie Database](https://www.themoviedb.org/) under Settings > Titles
  adds movies; that path has not been run against the live service yet.

## Where the information comes from

- **Shows:** [TVmaze](https://www.tvmaze.com/api) (CC BY-SA). What is new comes
  straight from TVmaze. What is popular on each service is collected once a
  day by `tools/build-catalog.py`, run by GitHub, and published as
  `catalog.json`, which the app downloads.
- **Nudity detection:** the NudeNet model, which the app downloads by itself
  the first time it runs (about 11 MB, from NudeNet's published package).
  Frames are checked on the phone and never leave it.

## Status

Checked on an automatic test phone (an Android 14 emulator that GitHub runs
after every change, see `.github/workflows/device-check.yml`): the app opens
without crashing; the welcome screen, Home, service pages, search, title
pages, the YouTube tab with live search results, video pages, Settings, the
word list, colour changes and the Browser tab display correctly; Watch opens
the service's page for a title in the player; the detection model downloads
and loads.

Checked on a computer: the filter logic passes its 36 tests; the script that
controls a page's video passes its test in desktop Chromium, including muting
from captions, skipping, blurring, reading caption files and whole caption tracks from DASH and HLS
manifests ahead, captions on screen (word by word, whole lines and unknown
players), the strict no-captions choice, the player controls, the look-ahead
copy's behaviour and the hidden YouTube page; the detection model
was run on sample pictures to confirm the app reads its output correctly.

Also checked on the test phone, with a film that has no nudity and the blur
test switched on: the player plays the film full screen with its own controls;
the blur goes up, stays up while the app keeps checking the picture under it,
and comes down afterwards; the hidden look-ahead copy starts, finds the same
film, moves in front of the viewer and has its pictures checked.

Not yet checked anywhere: the look-ahead hiding a real scene before it arrives
on a real phone (the test phone takes several seconds per check, a real phone
a fraction of one); YouTube playback and the signed-in YouTube tab (YouTube
turns the test phone away as a data-centre visitor, and it has no account);
muting on a real service's captions, HBO Max included; playing a signed-in
title on a paid service; casting to a TV; how the sound sets sound.

## Layout

| Path | What is in it |
|---|---|
| `core/` | Filter logic with no Android in it: the word list, word matching, subtitle parsing, mute/skip/blur decisions, reading the detector's output. Has the tests. |
| `app/` | The Android app. |
| `app/.../HomeScreen.kt`, `SearchScreen.kt`, `YouTubeScreen.kt`, `FiltersScreen.kt` | The Home, Search, YouTube and Settings tabs. `MainActivity.kt` holds them. |
| `app/.../TitleActivity.kt`, `ServiceActivity.kt`, `VideoActivity.kt` | The pages for a title, a service and a YouTube video. |
| `app/.../WelcomeActivity.kt`, `WordsActivity.kt` | The first-launch screen and the word list. |
| `app/.../browser/` | The built-in browser (`BrowserActivity`), the player built on it (`WatchActivity`), live detection, the hidden look-ahead copy (`Scout`), and the blur and controls layers (`Layers`). |
| `app/src/main/assets/safewatch.js` | The script added to every page to control its video. |
| `app/.../player/` | The player for video files on the phone and the ahead-of-time scan. |
| `app/.../data/` | Saved settings, marked scenes, the services, and the readers for TVmaze, TMDB and YouTube. |
| `app/.../detect/` | The nudity detector and its first-run download. |
| `app/.../ui/` | The design kit: colours and the viewer's colour choices (`Palette`), cards, rows, posters, controls, sounds. |
| `tools/` | The test-phone script, the catalog builder and the page script's test. |

## Building

Open the folder in Android Studio and run the `app` configuration, or from a
terminal:

    ./gradlew :core:test          # run the logic tests
    ./gradlew :app:assembleDebug  # build the APK

Work happens on the `dev` branch, where every push runs the test phone. Pushes
to `main` rebuild the download.

To run the page script's test (needs Node, Playwright and ffmpeg):

    cd tools/browser-test
    ffmpeg -f lavfi -i testsrc=size=320x180:rate=15 -f lavfi -i sine=frequency=440 -t 30 -g 15 -c:v libvpx -b:v 200k -c:a libvorbis v.webm
    npm install playwright && node test.js

## Not built yet

- Gore filtering.
- Shared scene lists, so a title marked once does not need marking again by anyone else.
- Remembering what the look-ahead found, so a video watched once is known in full the next time.
- Listening to the sound itself, for cursing in videos that have no captions.
- Blurring only the detected area instead of the whole picture.
- A Windows version.
