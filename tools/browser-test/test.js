// Runs the page script (app/src/main/assets/safewatch.js) in a real browser against a test video.
// See the README for how to run it.
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const script = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/safewatch.js'), 'utf8');
const types = { '.html': 'text/html', '.webm': 'video/webm', '.vtt': 'text/vtt', '.xml': 'application/octet-stream' };
const server = http.createServer((req, res) => {
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
      config: () => JSON.stringify({ version: window.__ver, language: true, tags: window.__tags }),
      muteWindows: (s, e, text) => JSON.stringify(/badword/i.test(text) ? [[s, e]] : []),
      profane: (text) => /badword/i.test(text),
      beat: (t, share) => { window.__beats.push(t); window.__share = share; },
      fullPicture: () => !!window.__full,
      captionWindows: (file) => { const m = file.match(/(\d\d):(\d\d):(\d\d)[.,](\d+)\D+(\d\d):(\d\d):(\d\d)[.,](\d+)/); if (!m || !/badword/i.test(file)) return '[]'; const ms = (i) => ((+m[i] * 60 + +m[i + 1]) * 60 + +m[i + 2]) * 1000 + +m[i + 3]; return JSON.stringify([[ms(1), ms(5)]]); },
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

  // Captions drawn into the page (YouTube style, rolling).
  await page.evaluate(() => { document.getElementById('caps').innerHTML = '<span class="ytp-caption-segment">hello there</span>'; });
  await page.waitForTimeout(300); r = await at(0); check('clean on-page caption does not mute', !r.muted);
  await page.evaluate(() => { document.querySelector('.ytp-caption-segment').textContent = 'hello there you badword'; });
  await page.waitForTimeout(300); r = await at(0); check('on-page caption with a filtered word mutes', r.muted);
  await page.evaluate(() => { document.querySelector('.ytp-caption-segment').textContent = 'hello there you badword and more words'; });
  await page.waitForTimeout(2000); r = await at(0); check('mute ends even though the word is still on screen', !r.muted);

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
  check('each mute is noted for the app', await page.evaluate(() => (window.__notes || []).length > 0));
  const beats = await page.evaluate(() => window.__beats.length); check('reports playback position to the app', beats > 20, `beats=${beats}`);

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
  await browser.close(); server.close(); console.log(fail ? `${fail} FAILED` : 'all passed'); process.exit(fail ? 1 : 0);
});
