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
  // Stand-in for the Android side of the bridge.
  await page.addInitScript(() => {
    window.__beats = []; window.__ver = 1;
    window.__tags = [{ s: 8000, e: 12000, a: 'skip' }, { s: 11500, e: 14000, a: 'skip' }, { s: 16000, e: 17000, a: 'blur' }, { s: 18000, e: 19000, a: 'mute' }];
    window.SafeWatchBridge = {
      version: () => window.__ver,
      config: () => JSON.stringify({ version: window.__ver, language: true, tags: window.__tags }),
      muteWindows: (s, e, text) => JSON.stringify(/badword/i.test(text) ? [[s, e]] : []),
      profane: (text) => /badword/i.test(text),
      beat: (t) => window.__beats.push(t),
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
  const beats = await page.evaluate(() => window.__beats.length); check('reports playback position to the app', beats > 20, `beats=${beats}`);
  await browser.close(); server.close(); console.log(fail ? `${fail} FAILED` : 'all passed'); process.exit(fail ? 1 : 0);
});
