// Runs the page script (app/src/main/assets/safewatch.js) in a real browser against a test video.
// See the README for how to run it.
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const script = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/safewatch.js'), 'utf8');
const types = { '.html': 'text/html', '.webm': 'video/webm', '.vtt': 'text/vtt', '.xml': 'application/octet-stream' };
// A streaming service's caption track, cut into two-second pieces, as a DASH manifest and as an HLS list.
const stamp = (s) => { const m = Math.floor(s / 60), r = (s - m * 60).toFixed(3); return `00:${String(m).padStart(2, '0')}:${r.padStart(6, '0')}`; };
const made = (url) => {
  let m;
  if (url === '/film.mpd') return ['application/dash+xml', `<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT30S">
    <Period id="0" start="PT0S"><AdaptationSet contentType="video" mimeType="video/mp4"><Representation id="v1"/></AdaptationSet>
    <AdaptationSet contentType="text" mimeType="text/vtt" lang="es"><Representation id="es"><SegmentTemplate media="t/es-$Number$.vtt" timescale="1000" startNumber="1"><SegmentTimeline><S t="0" d="2000" r="14"/></SegmentTimeline></SegmentTemplate></Representation></AdaptationSet>
    <AdaptationSet contentType="text" mimeType="text/vtt" lang="en-US"><Role schemeIdUri="urn:mpeg:dash:role:2011" value="caption"/><Representation id="en"><SegmentTemplate media="t/$RepresentationID$-$Number%03d$.vtt" timescale="1000" startNumber="1"><SegmentTimeline><S t="0" d="2000" r="14"/></SegmentTimeline></SegmentTemplate></Representation></AdaptationSet>
    </Period></MPD>`];
  if ((m = /^\/t\/en-(\d+)\.vtt$/.exec(url))) { // times counted from the start of the film
    const n = +m[1], from = (n - 1) * 2;
    return ['text/vtt', `WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n${stamp(from + 0.3)} --> ${stamp(from + 1.3)}\n${n === 6 ? 'piece six has a BADWORD in it' : 'a clean line in piece ' + n}\n`];
  }
  if (url === '/youtubei/v1/browse') return ['application/json', '{"onResponseReceivedActions":[{"videoRenderer":{"videoId":"zzz999yyy88"}}]}'];
  if (url === '/film.m3u8') return ['application/vnd.apple.mpegurl', '#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",LANGUAGE="en",NAME="English",URI="h/en.m3u8"\n#EXT-X-STREAM-INF:BANDWIDTH=1,SUBTITLES="s"\nv.m3u8\n'];
  if (url === '/h/en.m3u8') return ['application/vnd.apple.mpegurl', '#EXTM3U\n#EXT-X-TARGETDURATION:2\n' + Array.from({ length: 15 }, (x, i) => `#EXTINF:2.0,\nen-${i + 1}.webvtt`).join('\n') + '\n#EXT-X-ENDLIST\n'];
  if ((m = /^\/h\/en-(\d+)\.webvtt$/.exec(url))) { // times counted from the start of each piece
    const n = +m[1];
    return ['application/octet-stream', `WEBVTT\n\n00:00:00.400 --> 00:00:01.400\n${n === 3 ? 'piece three has a BADWORD too' : 'another clean line ' + n}\n`];
  }
  return null;
};
const server = http.createServer((req, res) => {
  const dynamic = made(req.url.split('?')[0]);
  if (dynamic) { res.writeHead(200, { 'Content-Type': dynamic[0], 'Access-Control-Allow-Origin': '*' }); return res.end(dynamic[1]); }
  const f = path.join(__dirname, req.url.split('?')[0]);
  if (!fs.existsSync(f)) { res.writeHead(404); return res.end(); }
  const buf = fs.readFileSync(f), range = req.headers.range;
  const h = { 'Content-Type': types[path.extname(f)], 'Accept-Ranges': 'bytes' };
  if (range) { const [a, b] = range.replace('bytes=', '').split('-'); const s = +a, e = b ? +b : buf.length - 1;
    res.writeHead(206, { ...h, 'Content-Range': `bytes ${s}-${e}/${buf.length}`, 'Content-Length': e - s + 1 }); res.end(buf.subarray(s, e + 1)); }
  else { res.writeHead(200, { ...h, 'Content-Length': buf.length }); res.end(buf); }
}).listen(0, async () => {
  const port = server.address().port;
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const page = await browser.newPage();
  const pageErrors = []; page.on('pageerror', (e) => pageErrors.push(String(e)));
  // Stand-in for the Android side of the bridge.
  await page.addInitScript(() => {
    window.__beats = []; window.__ver = 1;
    window.__tags = [{ s: 8000, e: 12000, a: 'skip' }, { s: 11500, e: 14000, a: 'skip' }, { s: 16000, e: 17000, a: 'blur' }, { s: 18000, e: 19000, a: 'mute' }];
    window.SafeWatchBridge = {
      version: () => window.__ver,
      config: () => JSON.stringify({ version: window.__ver, language: true, showCaptions: false, strict: !!window.__strict, tags: window.__tags }),
      cueWindows: (lines) => JSON.stringify(JSON.parse(lines).filter((l) => /badword/i.test(l[2])).map((l) => [l[0], l[1]])),
      spans: (text) => { const out = [], re = /badword/gi; let m; while ((m = re.exec(text))) out.push([m.index, m.index + m[0].length]); return JSON.stringify(out); },
      captionState: (kind, lines) => { window.__caption = { kind, lines }; },
      beat: (t, share) => { window.__beats.push(t); window.__share = share; },
      fullPicture: () => !!window.__full,
      captionCues: (file) => {
        const ms = (t) => { const p = t.replace(',', '.').split(':'); return Math.round(((+p[0] * 60 + +p[1]) * 60 + parseFloat(p[2])) * 1000); };
        const out = [], vtt = /(\d\d:\d\d:\d\d[.,]\d+)\s*-->\s*(\d\d:\d\d:\d\d[.,]\d+)[^\n]*\n([^\n]+)/g, ttml = /<p begin="([^"]+)" end="([^"]+)">([^<]*)<\/p>/g;
        let m; while ((m = vtt.exec(file))) out.push([ms(m[1]), ms(m[2]), m[3]]);
        while ((m = ttml.exec(file))) out.push([ms(m[1]), ms(m[2]), m[3]]);
        return JSON.stringify(out);
      },
      state: (pos, dur, paused, ad) => { window.__state = { pos, dur, paused, ad }; },
      command: () => { const c = window.__command || ''; window.__command = ''; return c; },
      note: (text) => { (window.__notes = window.__notes || []).push(text); },
    };
  });
  await page.addInitScript(script);
  await page.goto(`http://127.0.0.1:${port}/page.html`);
  await page.evaluate(() => document.getElementById('v').play());
  let fail = 0; const check = (name, ok, extra = '') => { console.log((ok ? 'ok   ' : 'FAIL ') + name + ' ' + extra); if (!ok) fail++; };
  const at = async (sec) => { await page.waitForFunction((s) => document.getElementById('v').currentTime >= s, sec, { timeout: 20000 }); await page.waitForTimeout(250);
    return page.evaluate(() => { const v = document.getElementById('v'); return { t: v.currentTime, muted: v.muted, filter: v.style.filter }; }); };
  const seek = (s) => page.evaluate((s) => { document.getElementById('v').currentTime = s; }, s);

  let r = await at(0.3); check('plays unmuted before the cue', !r.muted, JSON.stringify(r));
  r = await at(1.2); check('muted during the caption with a filtered word', r.muted && r.t < 2, JSON.stringify(r));
  r = await at(2.3); check('unmuted after it', !r.muted, JSON.stringify(r));
  r = await at(4.2); check('clean caption is not muted', !r.muted, JSON.stringify(r));
  await seek(7.5); r = await at(8.0); check('skip jumps past both overlapping ranges', r.t >= 14 && r.t < 15.5, JSON.stringify(r));
  await page.waitForTimeout(400); r = await at(0); check('sound is back after the skip', !r.muted, JSON.stringify(r));
  await seek(15.8); r = await at(16.2); check('blurred inside a blur tag', /blur/.test(r.filter) && !r.muted, JSON.stringify(r));
  r = await at(17.2); check('blur removed after it', r.filter === '', JSON.stringify(r));
  await seek(17.9); r = await at(18.2); check('muted inside a mute tag', r.muted, JSON.stringify(r));
  r = await at(19.2); check('unmuted after it', !r.muted, JSON.stringify(r));

  // A user who had the video muted stays muted afterwards.
  await page.evaluate(() => { const v = document.getElementById('v'); v.muted = true; v.currentTime = 17.9; });
  r = await at(19.2); check('keeps the viewer\'s own mute', r.muted, JSON.stringify(r));
  await page.evaluate(() => { document.getElementById('v').muted = false; });

  // New tags arrive when the app bumps the version.
  await page.evaluate(() => { const v = document.getElementById('v'); window.__tags = [{ s: 0, e: 30000, a: 'blur' }]; window.__ver = 2; });
  await page.waitForTimeout(400); r = await at(0); check('picks up changed tags', /blur/.test(r.filter));
  await page.evaluate(() => { document.getElementById('caps').innerHTML = ''; const v = document.getElementById('v'); v.currentTime = 5; return v.play(); }); await page.waitForTimeout(2000);
  const share = await page.evaluate(() => window.__share); check('reports how much of the page the video fills', share > 0 && share <= 1, `share=${share}`);
  await page.evaluate(() => { document.getElementById('v').muted = true; }); await page.waitForTimeout(300);
  check('a video the page keeps silent is not treated as the feature', await page.evaluate(() => window.__share) === 0);
  await page.evaluate(() => { document.getElementById('v').muted = false; window.__full = true; }); await page.waitForTimeout(600);
  check('asking for the full picture causes no errors', pageErrors.length === 0, pageErrors.join(' | '));
  // Caption files the page downloads are read ahead of time.
  await page.evaluate(async () => {
    window.__tags = []; window.__ver = 3; window.__full = false; document.getElementById('caps').innerHTML = '';
    await fetch('ahead.vtt').then((r) => r.text());
    await new Promise((done) => { const x = new XMLHttpRequest(); x.open('GET', 'ahead.xml'); x.responseType = 'arraybuffer'; x.onload = done; x.send(); });
    const v = document.getElementById('v'); v.muted = false; v.currentTime = 20.3; return v.play();
  });
  await page.waitForTimeout(400);
  r = await at(0); check('not muted just before a line read ahead', !r.muted && r.t < 21, JSON.stringify(r));
  r = await at(21.2); check('muted on time from a caption file fetched by the page', r.muted, JSON.stringify(r));
  r = await at(22.8); check('unmuted after it', !r.muted, JSON.stringify(r));
  r = await at(24.2); check('muted on time from a caption file with an unhelpful name and type', r.muted, JSON.stringify(r));
  r = await at(25.8); check('unmuted after it', !r.muted, JSON.stringify(r));
  check('the page still got its own downloads', await page.evaluate(() => fetch('ahead.vtt').then((x) => x.text()).then((t) => t.startsWith('WEBVTT'))));

  // The app's own player controls.
  await page.evaluate(() => { window.__full = false; window.__command = 'pause'; }); await page.waitForTimeout(400);
  let st = await page.evaluate(() => ({ paused: document.getElementById('v').paused, state: window.__state }));
  check('the pause control pauses the video', st.paused && st.state.paused === true, JSON.stringify(st));
  check('reports the video length', Math.abs(st.state.dur - 30000) < 1500, `dur=${st.state.dur}`);
  await page.evaluate(() => { window.__command = 'seek:12'; }); await page.waitForTimeout(400);
  check('the scrub control moves the video', Math.abs(await page.evaluate(() => document.getElementById('v').currentTime) - 12) < 0.5);
  await page.evaluate(() => { window.__command = 'skip:-10'; }); await page.waitForTimeout(400);
  check('the back-10 control moves the video', Math.abs(await page.evaluate(() => document.getElementById('v').currentTime) - 2) < 0.5);
  await page.evaluate(() => { window.__command = 'play'; }); await page.waitForTimeout(500);
  check('the play control resumes the video', await page.evaluate(() => !document.getElementById('v').paused));
  // While a video is handed to the TV it plays here without sound, whatever the filter would do; play brings the sound back.
  await page.evaluate(() => { document.getElementById('v').muted = false; window.__command = 'quiet'; }); await page.waitForTimeout(700);
  check('quiet keeps the video silent while it goes to the TV', await page.evaluate(() => document.getElementById('v').muted));
  await page.evaluate(() => { document.getElementById('v').currentTime = 2; }); await page.waitForTimeout(700);
  check('quiet holds through the filter\'s own mutes', await page.evaluate(() => document.getElementById('v').muted));
  await page.evaluate(() => { document.getElementById('v').muted = false; window.__command = 'play'; }); await page.waitForTimeout(500);
  check('play after quiet lets the filter decide again', await page.evaluate(() => window.__safewatch.quiet === false));
  check('each mute is noted for the app', await page.evaluate(() => (window.__notes || []).length > 0));
  const beats = await page.evaluate(() => window.__beats.length); check('reports playback position to the app', beats > 20, `beats=${beats}`);

  // The whole caption track of a streaming service, read from its manifest without captions being switched on.
  await page.evaluate(async () => {
    window.__notes = []; const v = document.getElementById('v'); v.pause();
    await fetch('film.mpd').then((r) => r.text());
  });
  await page.waitForFunction(() => (window.__notes || []).some((n) => /read in full/.test(n)), null, { timeout: 15000 }).catch(() => {});
  let notes = await page.evaluate(() => window.__notes);
  check('manifest: the English caption track is found and read in full', notes.some((n) => /English caption track found, 15 pieces/.test(n)) && notes.some((n) => /read in full: 15 lines, 1 stretches/.test(n)), notes.join(' | '));
  await page.evaluate(() => { const v = document.getElementById('v'); v.currentTime = 9.6; return v.play(); });
  await page.waitForTimeout(300);
  r = await at(0); check('manifest: not muted before the line', !r.muted && r.t < 10.3, JSON.stringify(r));
  r = await at(10.5); check('manifest: muted on the line found in piece six', r.muted, JSON.stringify(r));
  r = await at(11.6); check('manifest: unmuted after it', !r.muted, JSON.stringify(r));
  // The same from an HLS list, whose pieces count their times from their own start.
  await page.evaluate(async () => { window.__notes = []; await fetch('film.m3u8').then((r) => r.text()); });
  await page.waitForFunction(() => (window.__notes || []).some((n) => /read in full/.test(n)), null, { timeout: 15000 }).catch(() => {});
  notes = await page.evaluate(() => window.__notes);
  check('caption list: read in full', notes.some((n) => /caption list read: 15 pieces/.test(n)) && notes.some((n) => /read in full: 15 lines, 1 stretches/.test(n)), notes.join(' | '));
  await seek(3.0); await page.waitForTimeout(400);
  r = await at(0); check('caption list: not muted before the line', !r.muted && r.t < 4.2, JSON.stringify(r));
  r = await at(4.7); check('caption list: muted on the line found in piece three, placed by its piece and not at the start', r.muted, JSON.stringify(r));
  r = await at(6.1); check('caption list: unmuted after it', !r.muted, JSON.stringify(r));
  check('says what it has to go on', await page.evaluate(() => window.__caption && window.__caption.kind === 'ahead' && window.__caption.lines >= 15), JSON.stringify(await page.evaluate(() => window.__caption)));
  check('captions are kept off the screen unless asked for', await page.evaluate(() => !!document.getElementById('safewatch-captions')));

  // Captions drawn on the page, on a video with nothing better to go on.
  const live = await browser.newPage();
  live.on('pageerror', (e) => pageErrors.push(String(e)));
  await live.addInitScript(() => {
    window.__ver = 1;
    window.SafeWatchBridge = {
      version: () => window.__ver, fullPicture: () => false, beat: () => {}, state: () => {}, command: () => '',
      config: () => JSON.stringify({ version: window.__ver, language: true, showCaptions: true, strict: !!window.__strict, tags: [] }),
      cueWindows: () => '[]', captionCues: () => '[]',
      spans: (text) => { const out = [], re = /badword/gi; let m; while ((m = re.exec(text))) out.push([m.index, m.index + m[0].length]); return JSON.stringify(out); },
      captionState: (kind) => { window.__caption = kind; },
      note: (text) => { (window.__notes = window.__notes || []).push(text); },
    };
  });
  await live.addInitScript(script);
  await live.goto(`http://127.0.0.1:${port}/screen.html`);
  await live.evaluate(() => document.getElementById('v').play());
  const muted = () => live.evaluate(() => document.getElementById('v').muted);
  const caps = (html, id = 'caps') => live.evaluate(([h, i]) => { document.getElementById(i).innerHTML = h; }, [html, id]);
  await live.waitForTimeout(700);
  // Scrolling in word by word, as YouTube's automatic captions do.
  await caps('<span class="ytp-caption-segment">hello there</span>');
  await live.waitForTimeout(300); check('on screen: a clean line does not mute', !(await muted()));
  let wordAt = Date.now();
  await caps('<span class="ytp-caption-segment">hello there you badword</span>');
  await live.waitForTimeout(300); check('on screen: a word scrolling in is muted as it arrives', await muted());
  // More words keep arriving for three seconds. The mute must not be stretched by them.
  const words = ['and', 'then', 'some', 'more', 'words', 'keep', 'coming', 'along', 'here', 'now'];
  let line = 'hello there you badword', soundBackAt = 0;
  for (const w of words) {
    line += ' ' + w; await caps(`<span class="ytp-caption-segment">${line}</span>`); await live.waitForTimeout(300);
    if (!soundBackAt && !(await muted())) soundBackAt = Date.now();
  }
  check('on screen: the mute lasts about a second however long the word stays up', soundBackAt > 0 && soundBackAt - wordAt < 2200, `sound back after ${soundBackAt - wordAt} ms`);
  // The first line scrolls away and the rest carries on: still not muted again.
  await caps('<span class="ytp-caption-segment">words keep coming along here now and on</span>');
  await live.waitForTimeout(400); check('on screen: scrolling the line up does not mute again', !(await muted()));
  // A whole line appearing at once, with the word near its end.
  await caps('');
  await live.waitForTimeout(700);
  const whole = 'this is a long line and the word comes late badword';
  await caps(`<span class="ytp-caption-segment">${whole}</span>`);
  await live.waitForTimeout(250); check('on screen: a word late in a line is not muted the moment the line appears', !(await muted()));
  await live.waitForTimeout(1500); check('on screen: it is muted when the line reaches it', await muted());
  await caps('');
  await live.waitForTimeout(1300); check('on screen: the mute ends once the line has gone', !(await muted()));
  // Captions on a player this script has no name for.
  await caps('<div class="some-unknown-caption-box"><span>badword right at the start</span></div>', 'odd');
  await live.waitForTimeout(400); check('on screen: captions on an unknown player are found by where they are', await muted());
  await caps('', 'odd');
  await live.waitForTimeout(1900);
  check('on screen: says captions are being read live', await live.evaluate(() => window.__caption) === 'screen');
  // Text that changes elsewhere on the page is not taken for captions.
  await live.evaluate(() => { document.getElementById('below').textContent = 'badword in an article under the player'; });
  await live.waitForTimeout(500); check('text outside the video is ignored', !(await muted()));
  let liveNotes = await live.evaluate(() => window.__notes || []);
  check('each mute is recorded with how long it lasted', liveNotes.some((n) => /^muted at .*captions on screen/.test(n)) && liveNotes.some((n) => /^sound back after \d+ ms/.test(n)), liveNotes.join(' | '));
  await live.close();

  // The strict choice: nothing to go on, so no sound.
  const strict = await browser.newPage();
  await strict.addInitScript(() => {
    window.SafeWatchBridge = {
      version: () => 1, fullPicture: () => false, beat: () => {}, state: () => {}, command: () => '', note: () => {},
      config: () => JSON.stringify({ version: 1, language: true, showCaptions: false, strict: true, tags: [] }),
      cueWindows: () => '[]', captionCues: () => '[]', spans: () => '[]', captionState: (kind) => { window.__caption = kind; },
    };
  });
  await strict.addInitScript(script);
  await strict.goto(`http://127.0.0.1:${port}/screen.html`);
  await strict.evaluate(() => document.getElementById('v').play());
  await strict.waitForTimeout(3000);
  check('strict: sound is left on for the first moments', !(await strict.evaluate(() => document.getElementById('v').muted)));
  await strict.waitForTimeout(6500);
  check('strict: a video with no captions plays without sound', await strict.evaluate(() => document.getElementById('v').muted && window.__caption === 'none'));
  await strict.evaluate(() => { document.getElementById('caps').innerHTML = '<span class="ytp-caption-segment">a clean line appears</span>'; });
  await strict.waitForTimeout(600);
  check('strict: sound returns once captions are found', !(await strict.evaluate(() => document.getElementById('v').muted)));
  await strict.close();

  // The hidden copy that looks ahead: silent, filling its screen, filtering nothing, going where it is told.
  const scout = await browser.newPage({ viewport: { width: 640, height: 360 } });
  scout.on('pageerror', (e) => pageErrors.push(String(e)));
  await scout.addInitScript(() => {
    window.SafeWatchBridge = {
      scout: () => true, version: () => 1, fullPicture: () => false, beat: () => {}, wanted: () => 30000,
      config: () => JSON.stringify({ version: 1, language: false, tags: [] }),
      state: (pos, dur, paused, ad) => { window.__state = { pos, dur, paused, ad }; },
      steady: (ok) => { window.__steady = ok; },
      command: () => { const c = window.__command || ''; window.__command = ''; return c; },
      note: () => {},
    };
  });
  await scout.addInitScript(script);
  await scout.goto(`http://127.0.0.1:${port}/scout.html`);
  const inner = () => scout.frames().find((f) => f.url().includes('scout-inner'));
  await scout.waitForFunction(() => document.getElementById('f') && document.getElementById('f').getBoundingClientRect().width >= 640, null, { timeout: 15000 }).catch(() => {});
  await scout.waitForTimeout(1500);
  let box = await scout.evaluate(() => { const b = document.getElementById('f').getBoundingClientRect(); return [b.left, b.top, b.width, b.height]; });
  check('look-ahead copy: the frame holding the player fills the screen', box[0] === 0 && box[1] === 0 && box[2] >= 640 && box[3] >= 360, JSON.stringify(box));
  let sv = await inner().evaluate(() => { const v = document.getElementById('v'), b = v.getBoundingClientRect(); return { muted: v.muted, paused: v.paused, w: b.width, h: b.height, iw: innerWidth, ih: innerHeight, state: window.__state, steady: window.__steady }; });
  check('look-ahead copy: the video fills its frame', sv.w >= sv.iw && sv.h >= sv.ih && sv.iw >= 640, JSON.stringify(sv));
  check('look-ahead copy: silent and playing', sv.muted && !sv.paused, JSON.stringify(sv));
  check('look-ahead copy: reports where it is', sv.state && sv.state.pos > 0 && Math.abs(sv.state.dur - 30000) < 1500 && sv.steady === true, JSON.stringify(sv));
  await inner().evaluate(() => { document.getElementById('v').muted = false; window.__command = 'seek:9'; }); await scout.waitForTimeout(700);
  sv = await inner().evaluate(() => { const v = document.getElementById('v'); return { t: v.currentTime, muted: v.muted }; });
  check('look-ahead copy: goes where it is told and stays silent', sv.t >= 9 && sv.t < 11 && sv.muted, JSON.stringify(sv));
  await inner().evaluate(() => { window.__command = 'rate:3'; }); await scout.waitForTimeout(1000);
  sv = await inner().evaluate(() => { const v = document.getElementById('v'); return { t: v.currentTime, rate: v.playbackRate }; });
  check('look-ahead copy: runs fast to get in front', sv.rate === 3 && sv.t > 11.5, JSON.stringify(sv));
  await inner().evaluate(() => { document.getElementById('v').playbackRate = 1; window.__command = 'pause'; }); await scout.waitForTimeout(500);
  sv = await inner().evaluate(() => { const v = document.getElementById('v'); return { paused: v.paused, rate: v.playbackRate, state: window.__state }; });
  check('look-ahead copy: waits when far enough in front, keeping its speed', sv.paused && sv.rate === 3 && sv.state.paused === true, JSON.stringify(sv));
  check('no script errors on any page', pageErrors.length === 0, pageErrors.join(' | '));
  // The hidden copy of YouTube's website: hands over what the page is given and keeps its video still.
  const mirrorScript = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/mirror.js'), 'utf8');
  const yt = await browser.newPage();
  yt.on('pageerror', (e) => pageErrors.push(String(e)));
  await yt.addInitScript(() => { window.__answers = []; window.MirrorBridge = { answer: (kind, text) => window.__answers.push([kind, text]) }; });
  await yt.addInitScript(mirrorScript);
  await yt.goto(`http://127.0.0.1:${port}/yt.html`);
  await yt.waitForTimeout(800);
  await yt.evaluate(() => fetch('/youtubei/v1/browse?prettyPrint=false', { method: 'POST', body: '{}' }).then((r) => r.json()));
  await yt.evaluate(() => fetch('/t.vtt').then((r) => r.text()));
  await yt.waitForTimeout(400);
  const answers = await yt.evaluate(() => window.__answers);
  check('youtube mirror: hands over what the page loaded with', answers.some((a) => a[0] === 'initial' && a[1].includes('abc123def45')), JSON.stringify(answers).slice(0, 200));
  check('youtube mirror: hands over what the page asks for later, and nothing else', answers.some((a) => a[0] === 'browse' && a[1].includes('zzz999yyy88')) && answers.length === 2, answers.map((a) => a[0]).join(','));
  check('youtube mirror: keeps the page\'s video still', await yt.evaluate(() => document.getElementById('v').paused));
  await yt.evaluate(() => window.__mirrorMore());
  check('youtube mirror: scrolls to the end to load more', await yt.evaluate(() => window.scrollY > 1000));
  await yt.close();
  check('no script errors anywhere', pageErrors.length === 0, pageErrors.join(' | '));
  await browser.close(); server.close(); console.log(fail ? `${fail} FAILED` : 'all passed'); process.exit(fail ? 1 : 0);
});
