<p align="center"><img src="docs/edenos.svg" width="140" alt="edenOS: two leaves, emerald and sunlit gold, that make a lowercase e"></p>

# edenOS

A personal Android app for watching your streaming services and YouTube with
the parts you don't want removed. It mutes cursing and blurs or skips nudity,
using filters you set once, and it keeps everything in one layout of its own.
It can put all of that on the TV too.

The mark is two leaves from the garden that make a lowercase e: an emerald
one, and a sunlit one that falls into place when the app opens. The name is
set in Poppins Light, with "OS" in gold. (The app was first called SafeWatch;
the code keeps that name inside.)

## Download

[Download EdenOS.apk](https://github.com/jwmap3/SafeWatch/releases/download/latest/EdenOS.apk)
and open it on an Android phone (Android 9 or newer). The phone will ask you to
allow installing apps from your browser or file manager the first time. The file
is rebuilt automatically whenever the code changes, and a newer download
installs over the one already on the phone, keeping settings and sign-ins.
(`SafeWatch.apk` in the same place is the same file under the old name.)

## How it works

**Opening.** The app opens with a few seconds of animation and music made for
it: on a dark ground the sunlit leaf drifts down, lands against the emerald
one, and the e lights up with a small orchestra, a harp falling with the leaf
and the strings and horns blooming as it lands. A tap skips it; it can be
switched off under Settings > Appearance.

**Welcome.** The first launch asks which services you use and lets you sign in
to each. You sign in on the service's own page; the sign-in is kept on the
phone the way a browser keeps it. edenOS has no account or server of its
own and never sees a password.

**Tabs.** Home, Browser, Search, YouTube and Settings run along the bottom.
Settings > Tabs puts them in any order, hides the ones you do not use
(Settings always stays) and can show icons only.

**Home** shows a featured title, your services, and shelves of what is popular
and new on each one. The TV button at the top starts TV Mode; the cast button
opens Send to TV.

**A service's page**, **Search** and **a title's page** show what each
service has, find shows by name, and play them with a Watch button.

**YouTube** has a tab of its own in the app's layout. Signed in to YouTube
(once, on Google's own page), it shows your own YouTube: your subscriptions,
your home feed and your searches, read from YouTube's website signed in as you
in a browser that runs out of sight. Each video has a page with its
description, what YouTube suggests next and the comments, with filtered words
part-hidden ("s***"), and Play and Play on TV buttons. Not signed in, the tab
shows shelves for subjects you choose and channels you follow here.

**The player.** Watch opens a full-screen player with a title bar instead of
an address bar. When the video plays the screen turns sideways and only the
picture is left, with controls that fade out.

**Browser** is a tab for any other website, filtered the same way. It opens
on six quick links of your choosing (press and hold one to change it). The
page fills the screen, with one slim bar at the bottom: Back goes back a page,
the address box searches or goes to a site, and the menu has Forward, Reload,
Send to TV, Desktop site and more. The bar slides away while
reading down a page and comes back on the way up, or it can be hidden until
the small button in the corner brings it back. Pop-ups, message boxes and
pages that send you to another site by themselves are blocked; while a video
plays nothing can take you off its page. The search engine is Google, or
DuckDuckGo or Bing in Settings.

**Send to TV** is on every player and on Home:

- *YouTube on TV* plays a YouTube video in the TV's own YouTube app, with
  nothing downloaded. Link the TV once (on the TV, YouTube > Settings > Link
  with TV code), then tap Play on TV on a video's page. edenOS reads the
  video's captions on the phone, starts it on the TV where you were, and works
  the TV like YouTube's own remote: it mutes the TV for each curse word,
  jumps past any scenes saved for the video, and turns the TV's captions off. The phone can
  be locked; it has to stay on the internet.
- *TV Mode* fills the TV with edenOS's own home screen and turns the phone
  into its remote (see below).
- *Mirror to TV* opens the phone's screen casting and shows the already
  filtered picture. It works for everything, but the phone has to stay on.
- *Superclean to TV* is a clean copy that Claude has been through as well,
  taking out what you choose (see below).
- *Clean copy to TV* makes a copy of the video with the filtering built in:
  the cursing silent and nudity blurred (or cut out, with Skip). A Roku or a smart TV (Samsung, LG and others) then
  plays it by itself, fetching it from the phone over the Wi-Fi, so the phone
  can be locked. Copies can be made of video files and of websites' videos,
  whether they come as one file or stream in pieces (HLS or DASH), as long as
  the site has not locked (encrypted) them. Each copy is for one viewing: it
  deletes itself once the TV has played it through, or after a day. Making a
  copy checks every picture and writes the video again, which takes a while;
  it carries on with the screen off.

**TV Mode.** With the phone connected to the TV as a second screen (Smart
View on a Samsung phone reaches Roku, Samsung and LG TVs; a USB-C to HDMI
cable works with most phones), edenOS shows its TV home there: the time, then
rows of large tiles for your services, YouTube and your quick links. The
phone becomes the remote: a big touchpad (swipe, or tap an edge, to move; tap
the middle to choose; or switch it to a pointer, with two fingers to scroll,
for web pages), Back, Home, typing, play/pause and skips. What you pick opens
on the TV, with all the filtering, while the phone shows the remote. The
phone dims itself when left alone. Connecting the phone to a TV while edenOS
is open offers TV Mode straight away.

**Superclean** (optional, with your own Anthropic API key). A clean copy for
the TV that Claude has also been through, so far more can come out than the
phone finds by itself. The list of what it can take out follows VidAngel's
filter categories:

- *Language:* profanity, God's name in vain (never sincere prayer), slurs,
  sexual references, crude talk, childish words, and swearing written on
  screen.
- *Sex and nudity:* suggestive moments, implied sex, sex scenes, sexual
  assault, nudity, implied nudity, and nude statues and paintings.
- *Kissing and immodesty:* kissing, passionate kissing, revealing clothing.
  Romance is treated alike for every couple.
- *Violence:* violent talk and threats, fighting, graphic violence, gore,
  disturbing images, animals being hurt.
- *Alcohol and drugs:* drinking, smoking and vaping, drug use, and talk that
  makes light of them.
- *Other:* vulgar gestures, self-harm and suicide, frightening scenes, bodily
  functions, graphic medical scenes, death and dying, and credits and recaps.

Your usual choices are set under Settings > Superclean, starting from
ready-made sets (Young children, Family, Teens). Choosing Superclean to TV
for a title opens its own page: Claude first finds the title's IMDb Parents
Guide on the web and reads it, and its warnings are listed by section (Sex &
Nudity, Violence & Gore, Profanity, Alcohol, Drugs & Smoking, Frightening &
Intense Scenes) with IMDb's strength for each, to tick the scenes you want
out. The usual choices can be changed there for that title, and scenes can be
cut out or blurred. Claude then looks at small frames from the whole video,
two seconds apart with the time printed on each, and reads the caption
lines: scenes come out (or are blurred), and words or whole lines are muted.
The phone's own player remembers what was found for that video. Superclean
is only done when you choose it, and costs about 40 cents per hour of video
with Claude Sonnet 5.5 (a few cents with the less careful Haiku 5.5), plus
about 10 cents to look up a Parents Guide, billed to your Anthropic account.
The key stays on the phone and is sent only to Anthropic.

**Settings** holds your accounts, the filters, the tabs, colours and sounds,
and can be locked with a PIN so children cannot switch the filters off.

- **Language:** Off, Low, Medium or High, a separate blasphemy switch, and
  "Choose words", which lists every built-in word part-hidden ("Sh*t",
  "F*ck") with its own switch. Extra words to mute and Words to allow cover
  anything not listed. "Show captions" is off by default, so a muted word is
  not printed on the screen instead. "No captions, no sound" plays a video the
  filter has nothing to go on for without sound. "Filter report" lists what
  the filter found and every mute, to copy and send on when something is
  missed.
- **Nudity:** Off, Low, Medium or High, and whether to blur or skip. "Look
  ahead" is described below. "Test the blur" blurs faces for two minutes, to
  see the blur working on any video with people in it.
- **Browser, Player, App, Tabs:** the search engine, hiding the bar while
  scrolling, pop-up blocking, the skip buttons (10, 15 or 30 seconds), hidden
  pictures blurred or black, which tab opens first, and the PIN.
- **Superclean:** the key, the model, what it takes out, cut or blur, and
  whether to look up the Parents Guide first.
- **Appearance:** dark, light or automatic, three colours to choose (primary,
  background and cards), and the opening animation.
- **Sounds:** a few sets of tap and play sounds, all made for this app, and
  their volume. A fourth set unlocks when the logo on Home is tapped seven
  times.

## What is filtered automatically

| | Cursing | Nudity |
|---|---|---|
| YouTube | Yes, from captions, read ahead of time (on the TV too) | Yes, looked at ahead of time, and live |
| Ordinary websites and video links | Yes, where the video has captions | Yes, looked at ahead of time where a second copy will play, and live |
| Video files on the phone | Yes, with the film's subtitle file | Yes, live or by scanning the whole film first |
| Netflix, HBO Max, Prime Video, Disney+, Hulu and similar | Yes, from captions (switch captions on in the player) | No: their pictures are locked (see below) |

Superclean goes further, for anything a clean copy can be made of: violence,
kissing, drinking, frightening scenes and the rest of its list, and the scenes
you tick from a title's Parents Guide.

### Knowing before it happens

**Nudity: looking ahead.** While you watch, the app plays a second copy of the
same video where you cannot see or hear it, a few seconds in front of yours.
Each picture from that copy is checked and the result is kept against its
place in the video, so the blur goes up two seconds before the scene and comes
down two seconds after its last flagged picture. The screen you are watching
is checked live as well. Looking ahead plays the video twice, so it uses about
twice the data; it can be switched off under Settings > Nudity.

**Cursing: reading ahead.** Cursing is found in a video's captions, best
source first: the whole caption track from the player's manifest (how HBO Max
and many services deliver captions); a caption file the player downloads, such
as YouTube's, which times every word; the video's own caption track; and last,
captions as they appear on screen. The player's bar says which the filter has:
"Captions read", "Captions live", or "No captions" in red.

### The paid services

The paid services scramble their picture so that only the screen can show it.
No app on the phone can look at the frames, so nothing can detect what is in
them, and their videos cannot be saved or Supercleaned. Their cursing is
still muted from the captions. They can be watched on the TV with Mirror to
TV or TV Mode.

## Other limits

- **YouTube on TV** uses YouTube's own remote-control connection, the one its
  phone app uses. YouTube does not document it for other apps, so a YouTube
  update could break it until edenOS is updated. Muting goes from the phone
  through YouTube to the TV, which takes a moment, so it starts early and lasts
  a little longer than on the phone. edenOS stops filtering if another video
  is started on the TV, since it has not read that video's captions. It cannot
  see the TV's picture, so nudity is not hidden there; the YouTube Kids TV app
  does not take commands at all.
- **TV Mode** needs the TV as a second screen. Samsung phones' Smart View
  gives that over Wi-Fi; Google Pixels can only cast to Chromecast, so they
  need a cable. The phone does all the work and stays on. The paid services
  may show a black picture over Wi-Fi, as with mirroring; a cable usually
  fixes that.
- **Clean copies** of locked (encrypted) videos and live broadcasts cannot be
  made, and YouTube's are not offered: use YouTube on TV, TV Mode or
  mirroring. Rokus are sent copies through "Play on Roku", which Roku does not
  document for other apps; smart TVs use the standard home-media protocol and
  may ask once whether to allow the phone.
- **Superclean** sends small pictures and the caption text to Anthropic under
  your key. Claude can miss things or take out too much, like any detector;
  it finds a scene from its pictures and lines, not from the plot. Parents
  Guides are written by IMDb's users and are missing for some titles, and the
  web search that finds them has to be allowed for your Anthropic account
  (it is unless an organisation turned it off). The price depends on
  Anthropic's published rates.
- **Playing a service inside the app.** Services want phone users in their own
  apps. edenOS asks them for their computer website instead, which plays in a
  browser. Whether each service then plays after you sign in has not been
  tested, because that needs a subscription.
- **YouTube's lists** are read from the same requests YouTube's website makes,
  which YouTube can change. YouTube on VPNs and data-centre connections asks
  visitors to sign in before it plays.
- **Cursing without captions** cannot be filtered. The player says so; "No
  captions, no sound" plays it silent instead.
- **Glimpses.** Where the look-ahead has not been, a scene is only caught once
  it is on screen, so a fraction of a second can show before the blur.
- **Detection makes mistakes.** Expect some misses and some false alarms.
- **Movies** in the catalog need a free key from
  [The Movie Database](https://www.themoviedb.org/) under Settings > Titles.

## Where the information comes from

- **Shows:** [TVmaze](https://www.tvmaze.com/api) (CC BY-SA), collected once a
  day by `tools/build-catalog.py` into `catalog.json`, which the app downloads.
- **Nudity detection:** the NudeNet model, which the app downloads by itself
  the first time it runs (about 11 MB). Frames are checked on the phone and
  never leave it, except in a Superclean you ask for.
- **Parents Guides:** [IMDb](https://www.imdb.com/), found and read by Claude
  when you Superclean a title.
- **The name's typeface:** [Poppins](https://github.com/itfoundry/Poppins),
  under the SIL Open Font License (`docs/fonts/Poppins-OFL.txt`).

## Status

Checked on an automatic test phone (an Android 14 emulator that GitHub runs
after every change, see `.github/workflows/device-check.yml`): every screen
opens and displays correctly, with the opening animation recorded; arranging
the tabs; the Browser tab's quick links, bottom bar, hiding it and its menu;
clean copies made from a video file and from an HLS stream, with the test
curse word silent and a blurred scene; a whole Superclean, against a stand-in
for Anthropic on the test computer (`tools/test-site.py`): the Parents Guide
looked up and listed, the copy made with the chosen scene cut and the chosen
line muted; TV Mode on a pretend second screen; the blur and the look-ahead
copy.

Checked on a computer: the filter logic passes its 60 tests, including talking
to a stand-in YouTube TV (linking with a code, renewing the link, muting ahead
of a curse word, jumping past a saved scene) and to a stand-in Claude (pictures,
captions, and a web search that pauses part way); the page script passes its
test in desktop Chromium.

Not yet checked anywhere: a real TV (Roku, Samsung or LG) for clean copies,
YouTube on TV and TV Mode over Smart View; YouTube playback and the signed-in
YouTube tab (YouTube turns the test phone away as a data-centre visitor);
muting on a real paid service's captions; Superclean with a real key and the
real IMDb.

## Layout

| Path | What is in it |
|---|---|
| `core/` | Logic with no Android in it: the word list, word matching, subtitle parsing, mute/skip/blur decisions, clean-copy plans, stream checks, the Claude call (`Claude.kt`) and Superclean's list of choices, Parents Guide and questions for Claude (`Superclean.kt`). Has the tests. |
| `core/.../tv/` | Talking to TVs: finding them, Roku's and smart TVs' protocols, the file server the TV fetches a copy from, and YouTube's TV remote connection (`YouTubeLounge.kt`). |
| `app/` | The Android app. `MainActivity.kt` holds the tabs; `HomeScreen.kt`, `SearchScreen.kt`, `YouTubeScreen.kt` and `FiltersScreen.kt` are the tabs. |
| `app/.../browser/` | The built-in browser (`BrowserActivity`), its quick links, the player built on it (`WatchActivity`), live detection, the hidden look-ahead copy (`Scout`) and the blur and controls layers. |
| `app/src/main/assets/safewatch.js` | The script added to every page to control its video. |
| `app/.../tv/` | Clean copies (`CleanCopy`, `CleanEffects`), Superclean (`SupercleanActivity`, `SupercleanRun`), YouTube on TV (`YouTubeTv`), TV Mode (`TvMode`, `TvModeActivity`, `RemotePad`), finding TVs, the service that keeps it all going with the screen off (`TvService`) and the TV screen (`TvActivity`). |
| `app/.../data/`, `detect/`, `ui/` | Saved settings and found scenes; the nudity detector; the design kit, logo and name (`Brand`), the opening (`Intro`) and sounds. |
| `tools/` | The test-phone script and its test site (`test-site.py`), the catalog builder, the opening's music (`make-intro-sound.py`) and the page script's test. `docs/edenos.svg` is the logo. |

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

- Shared scene lists for the paid services, whose pictures cannot be checked.
- Listening to the sound itself, for cursing in videos that have no captions.
- Blurring only the detected area instead of the whole picture.
- A Windows version.
