'use strict';
// A run-through for checking a build without anyone at the screen: EDEN_SELFTEST=<folder> EDEN_TESTPAGE=<address>.
// Takes pictures of each screen, records when a test video was muted, and writes the filter report.
const fs = require('fs');
const path = require('path');

module.exports = function selftest(get) {
  const out = process.env.EDEN_SELFTEST;
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const save = async (contents, name) => {
    try { fs.writeFileSync(path.join(out, name + '.png'), (await contents.capturePage()).toPNG()); } catch (e) { fs.appendFileSync(path.join(out, 'log.txt'), name + ': ' + e + '\n'); }
  };
  const log = (t) => fs.appendFileSync(path.join(out, 'log.txt'), t + '\n');
  (async () => {
    fs.mkdirSync(out, { recursive: true });
    await wait(3500);
    const { win, page, navigate, setMode, notes, shellKey } = get();
    await save(win.webContents, '1-home');
    navigate(process.env.EDEN_TESTPAGE);
    await wait(2500);
    const samples = [];
    for (let i = 0; i < 44; i++) {
      try {
        samples.push(await page().webContents.executeJavaScript(
          '(() => { const v = document.querySelector("video"); return v ? [Math.round(v.currentTime * 10) / 10, v.muted, v.paused] : null; })()'));
      } catch (e) { samples.push(String(e)); }
      if (i === 14) { await save(win.webContents, '2-bar'); await save(page().webContents, '2-page'); }
      await wait(250);
    }
    fs.writeFileSync(path.join(out, 'muting.json'), JSON.stringify(samples));
    // The picture is looked at several times a second while a video with sound plays.
    get().store.set({ testBlurUntil: Date.now() + 60000 });
    navigate(process.env.EDEN_TESTPAGE.replace('page.html', 'video.html'));
    for (let i = 0; i < 12; i++) {
      await wait(1000);
      if (get().pageState().hidden) { log('picture hidden after ' + (i + 1) + ' s'); await save(page().webContents, '2b-video'); await save(get().curtain().webContents, '2c-curtain'); break; }
    }
    log('pictures looked at: ' + get().checks());
    navigate('https://www.pornhub.com/');
    await wait(2500);
    await save(page().webContents, '3-blocked');
    log('blocked page address: ' + page().webContents.getURL().slice(0, 40));
    setMode('settings');
    shellKey('settings');
    await wait(1500);
    await save(win.webContents, '4-settings');
    fs.writeFileSync(path.join(out, 'report.txt'), notes.join('\n'));
    log('done');
    require('electron').app.quit();
  })().catch((e) => { log('selftest failed: ' + e.stack); require('electron').app.quit(); });
};
