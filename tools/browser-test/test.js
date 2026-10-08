// Runs the page script (app/src/main/assets/safewatch.js) in a real browser against a test video.
// See the README for how to run it.
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const script = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/safewatch.js'), 'utf8');
const types = { '.html': 'text/html', '.webm': 'video/webm', '.vtt': 'text/vtt' };
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
  await browser.close(); server.close(); console.log(fail ? `${fail} FAILED` : 'all passed'); process.exit(fail ? 1 : 0);
});
