'use strict';
// edenOS for Windows and Mac. One window: edenOS's own bar along the top and, under it, either Home and Settings
// (the shell, drawn by src/shell) or the web page being watched. Every page gets the phone app's own page script,
// which mutes cursing from the captions; this side answers its questions, blocks sites, and looks at the picture
// for nudity, covering it with a curtain when it is found.
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');
const electron = require('electron');
const { app, BrowserWindow, WebContentsView, ipcMain, session, protocol, net, Menu, shell, nativeImage } = electron;
const store = require('./store');
const model = require('./model');
const F = require('../shared/filters');
const B = require('../shared/blocking');
const SERVICES = require('../shared/services');

const ROOT = path.join(__dirname, '..', '..');
const BAR = 56;
const MAC = process.platform === 'darwin';
const LIVE_HOLD_MS = 1500;
const CHECK_EVERY_MS = 300;

protocol.registerSchemesAsPrivileged([
  { scheme: 'eden', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true } },
]);
app.setName('edenOS');
if (process.env.EDEN_SELFTEST) app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

let win = null;
let page = null;       // the web page being watched
let curtain = null;    // drawn over the page while nudity is hidden
let detector = null;   // a hidden window that runs the detector
let mode = 'home';     // home, browse or settings: what the window shows under the bar
let htmlFullScreen = false;
let version = 1;       // goes up whenever a setting changes, so the page script reads them again
let matcher = new F.ProfanityMatcher(store.get());
let adult = null;
let widevine = 'not needed';
const notes = [];
const pages = new Map(); // what each page's script has said, by web contents id

// ---- Small helpers ----

function note(text) {
  const line = new Date().toLocaleTimeString() + '  ' + text;
  notes.push(line);
  while (notes.length > 600) notes.shift();
}

function adultSites() {
  if (adult) return adult;
  try {
    adult = new Set(fs.readFileSync(path.join(ROOT, 'src', 'shared', 'blocklist-adult.txt'), 'utf8').split(/\r?\n/).filter((l) => l.trim()));
  } catch (e) { adult = new Set(); }
  return adult;
}

function blockedWhy(address) {
  const s = store.get();
  if (!s.blockAdult && !s.blockSocial) return null;
  let host;
  try { host = new URL(address).hostname; } catch (e) { return null; }
  return B.reason(host, s.blockAdult ? adultSites() : new Set(), s.blockAdult, s.blockSocial);
}

function stateOf(contents) {
  let st = pages.get(contents.id);
  if (!st) {
    st = { pending: '', beatAt: 0, width: 0, captions: '', captionLines: 0, captionsAt: 0, positionMs: 0, lengthMs: 0, paused: true,
      stateAt: 0, hideUntil: 0, checking: false, lastFrame: null, lastSkipAt: 0 };
    pages.set(contents.id, st);
    contents.once('destroyed', () => pages.delete(contents.id));
  }
  return st;
}

function playing(st) { return Date.now() - st.beatAt < 1200; }

// ---- The eden:// addresses: the app's own pages and files ----

function serveFile(file, extraHeaders) {
  return net.fetch(pathToFileURL(file).toString()).then((res) => {
    const headers = new Headers(res.headers);
    for (const [k, v] of Object.entries(extraHeaders || {})) headers.set(k, v);
    return new Response(res.body, { status: res.status, headers });
  });
}

function handleEden(request) {
  const url = new URL(request.url);
  if (url.hostname === 'model') return model.installed() ? serveFile(model.file()) : new Response('', { status: 404 });
  if (url.hostname !== 'app') return new Response('', { status: 404 });
  const file = path.normalize(path.join(ROOT, decodeURIComponent(url.pathname)));
  if (!file.startsWith(ROOT)) return new Response('', { status: 403 });
  // The detector's page is kept apart from other pages so its engine may use several of the computer's cores.
  const isolated = { 'Cross-Origin-Opener-Policy': 'same-origin', 'Cross-Origin-Embedder-Policy': 'require-corp', 'Cross-Origin-Resource-Policy': 'same-origin' };
  return serveFile(file, isolated);
}

// ---- The window ----

function layout() {
  if (!win) return;
  const [w, h] = win.getContentSize();
  const full = htmlFullScreen;
  const bounds = full ? { x: 0, y: 0, width: w, height: h } : { x: 0, y: BAR, width: w, height: Math.max(0, h - BAR) };
  if (page) {
    page.setBounds(bounds);
    page.setVisible(mode === 'browse' || full);
  }
  if (curtain) curtain.setBounds(bounds);
}

function sendShell(channel, value) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, value);
}

function pageState() {
  if (!page) return {};
  const wc = page.webContents;
  const st = stateOf(wc);
  const url = wc.getURL();
  return {
    mode, url: url.startsWith('data:') ? '' : url, title: wc.getTitle(), loading: wc.isLoading(),
    canBack: wc.navigationHistory.canGoBack(), canForward: wc.navigationHistory.canGoForward(),
    playing: playing(st), captions: Date.now() - st.captionsAt < 15000 ? st.captions : '', hidden: Date.now() < st.hideUntil,
    language: store.get().language !== 'OFF',
  };
}

let stateTimer = null;
function tellState() {
  if (stateTimer) return;
  stateTimer = setTimeout(() => { stateTimer = null; sendShell('page-state', pageState()); }, 60);
}

function setMode(next) {
  mode = next;
  layout();
  tellState();
  if (mode === 'browse' && page) page.webContents.focus();
}

function webPreferences() {
  return {
    partition: 'persist:web',
    preload: path.join(__dirname, '..', 'preload', 'page.js'),
    sandbox: true,
    contextIsolation: true,
    nodeIntegration: false,
    nodeIntegrationInSubFrames: true,
    backgroundThrottling: false,
  };
}

function navigate(text) {
  const address = B.toUrl(text, store.get().searchEngine);
  setMode('browse');
  page.webContents.loadURL(address).catch(() => { /* reported by the page itself */ });
}

/** Wires up a web page (the main one, or a pop-up allowed by Settings). */
function watchContents(wc) {
  wc.setWindowOpenHandler(({ url, disposition }) => {
    const s = store.get();
    if (blockedWhy(url)) { note('blocked a new window for ' + url); return { action: 'deny' }; }
    if (disposition === 'new-window' && s.blockPopups) { note('pop-up blocked: ' + url.slice(0, 100)); return { action: 'deny' }; }
    if (disposition === 'new-window') {
      return { action: 'allow', overrideBrowserWindowOptions: { width: 560, height: 720, autoHideMenuBar: true, webPreferences: webPreferences() } };
    }
    // Links meant for a new tab open here instead.
    setImmediate(() => page && page.webContents.loadURL(url).catch(() => {}));
    return { action: 'deny' };
  });
  wc.on('did-create-window', (child) => watchContents(child.webContents));
  // While a video plays, nothing can take the viewer off its page by itself.
  wc.on('will-navigate', (event) => {
    const st = stateOf(wc);
    if (!store.get().blockPopups || !playing(st) || event.isSameDocument) return;
    let from = '', to = '';
    try { from = new URL(wc.getURL()).hostname; to = new URL(event.url).hostname; } catch (e) { return; }
    if (from && to && from !== to) {
      event.preventDefault();
      note('a redirect to ' + to + ' was stopped while the video played');
    }
  });
  wc.on('context-menu', (e, params) => {
    const items = [];
    if (params.linkURL) items.push({ label: 'Open link', click: () => page.webContents.loadURL(params.linkURL) });
    if (params.isEditable) items.push({ role: 'cut' }, { role: 'copy' }, { role: 'paste' });
    else if (params.selectionText) items.push({ role: 'copy' });
    items.push({ type: 'separator' }, { label: 'Back', enabled: wc.navigationHistory.canGoBack(), click: () => wc.navigationHistory.goBack() },
      { label: 'Reload', click: () => wc.reload() });
    Menu.buildFromTemplate(items).popup();
  });
}

function createPage() {
  page = new WebContentsView({ webPreferences: webPreferences() });
  page.setBackgroundColor('#0B0F0D');
  win.contentView.addChildView(page);
  const wc = page.webContents;
  watchContents(wc);
  for (const event of ['did-navigate', 'did-navigate-in-page', 'page-title-updated', 'did-start-loading', 'did-stop-loading']) wc.on(event, tellState);
  wc.on('did-navigate', () => { const st = stateOf(wc); st.captions = ''; st.hideUntil = 0; showCurtain(false); });
  wc.on('enter-html-full-screen', () => { htmlFullScreen = true; win.setFullScreen(true); layout(); });
  wc.on('leave-html-full-screen', () => { htmlFullScreen = false; win.setFullScreen(false); layout(); });
  wc.on('render-process-gone', (e, details) => note('the page stopped (' + details.reason + ')'));

  curtain = new WebContentsView({ webPreferences: { sandbox: true, contextIsolation: true } });
  curtain.setBackgroundColor('#000000');
  curtain.webContents.loadURL('eden://app/src/shell/curtain.html');
  curtain.setVisible(false);
  win.contentView.addChildView(curtain);
  layout();
}

function createWindow() {
  win = new BrowserWindow({
    width: 1280, height: 800, minWidth: 720, minHeight: 480,
    backgroundColor: '#0B0F0D', title: 'edenOS', show: false,
    titleBarStyle: MAC ? 'hiddenInset' : 'default',
    trafficLightPosition: { x: 16, y: 20 },
    autoHideMenuBar: true,
    icon: path.join(ROOT, 'build', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, '..', 'preload', 'shell.js'), contextIsolation: true, sandbox: true },
  });
  win.loadURL('eden://app/src/shell/index.html');
  win.once('ready-to-show', () => win.show());
  win.on('resize', layout);
  win.on('enter-full-screen', layout);
  win.on('leave-full-screen', () => { if (htmlFullScreen && page) page.webContents.executeJavaScript('document.exitFullscreen && document.exitFullscreen()').catch(() => {}); });
  win.on('closed', () => { win = null; page = null; curtain = null; if (detector) detector.destroy(); detector = null; });
  createPage();
}

// ---- Nudity: a look at the picture several times a second ----

let curtainShown = false;
function showCurtain(on, frame) {
  if (!curtain) return;
  on = on && (mode === 'browse' || htmlFullScreen);
  if (on) {
    const style = store.get().hideStyle;
    const image = style === 'blur' && frame ? 'data:image/jpeg;base64,' + frame.toString('base64') : '';
    curtain.webContents.executeJavaScript('window.show && show(' + JSON.stringify(image) + ')').catch(() => {});
  }
  if (curtainShown === on) return;
  curtainShown = on;
  curtain.setVisible(on);
  tellState();
}

let detectorReady = null;
let checks = 0; // pictures looked at, for the self-test
const waiting = new Map();
let frameId = 0;

function startDetector() {
  if (detector || !model.installed()) return detectorReady;
  detector = new BrowserWindow({
    show: false, width: 400, height: 400,
    webPreferences: { preload: path.join(__dirname, '..', 'preload', 'detector.js'), contextIsolation: true, sandbox: true, backgroundThrottling: false },
  });
  detectorReady = new Promise((resolve) => {
    const done = (ok) => { ipcMain.removeAllListeners('detector-ready'); resolve(ok); };
    ipcMain.on('detector-ready', (e, ok, info) => { note('nudity detection: ' + (ok ? 'ready' : 'could not start') + (info ? ' (' + info + ')' : '')); done(ok); });
    setTimeout(() => done(false), 60000);
  });
  detector.loadURL('eden://app/src/detector/detector.html');
  detector.on('closed', () => { detector = null; detectorReady = null; });
  return detectorReady;
}

ipcMain.on('detector-level', (e, id, level) => {
  const w = waiting.get(id);
  if (w) { waiting.delete(id); w(level); }
});

function detect(jpeg, faces) {
  return new Promise((resolve) => {
    const id = ++frameId;
    waiting.set(id, resolve);
    detector.webContents.send('detector-frame', id, jpeg, faces);
    setTimeout(() => { if (waiting.delete(id)) resolve(0); }, 3000);
  });
}

async function checkPicture() {
  if (!page || !win) return;
  const wc = page.webContents;
  const st = stateOf(wc);
  const s = store.get();
  const now = Date.now();
  if (st.hideUntil && now >= st.hideUntil) { st.hideUntil = 0; showCurtain(false); }
  if (s.nudity === 'OFF' || !model.installed() || !playing(st) || st.width < 0.15 || st.checking || wc.isDestroyed()) return;
  if (!detector) { startDetector(); return; }
  if (!(await detectorReady)) return;
  st.checking = true;
  try {
    const image = await wc.capturePage();
    if (image.isEmpty()) return;
    const small = image.resize({ width: 320, quality: 'good' });
    const jpeg = small.toJPEG(75);
    st.lastFrame = jpeg;
    const level = await detect(jpeg, s.testBlurUntil > now);
    checks++;
    const strict = { OFF: Infinity, LOW: 3, MEDIUM: 2, HIGH: 1 }[s.nudity];
    if (level >= strict) {
      if (!st.hideUntil) note('nudity found (level ' + level + ') at ' + Math.round(st.positionMs / 1000) + ' s: hidden');
      st.hideUntil = Date.now() + LIVE_HOLD_MS;
      if (s.nudityAction === 'SKIP' && Date.now() - st.lastSkipAt > 1200) { st.pending = 'skip:3'; st.lastSkipAt = Date.now(); }
    }
    if (Date.now() < st.hideUntil) showCurtain(true, jpeg);
  } catch (e) {
    // The page went away while it was being looked at.
  } finally {
    st.checking = false;
  }
}

// ---- What the page script asks (the same questions as on the phone) ----

const bridge = {
  version: () => version,
  scout: () => false,
  config: () => {
    const s = store.get();
    return JSON.stringify({ version, language: s.language !== 'OFF', showCaptions: s.showCaptions, strict: s.silentWithoutCaptions, tags: [] });
  },
  cueWindows: (wc, lines) => {
    try {
      const cues = JSON.parse(lines).map((l) => ({ start: Number(l[0]), end: Number(l[1]), text: String(l[2]) }));
      return JSON.stringify(F.muteRanges(cues, matcher).map((r) => [r.start, r.end]));
    } catch (e) { note('could not work out when to mute: ' + e); return '[]'; }
  },
  captionCues: (wc, file) => {
    if (!F.recognises(file)) return '[]';
    try { return JSON.stringify(F.parseCaptions(file).map((c) => [c.start, c.end, c.text])); } catch (e) { note('could not read a caption file: ' + e); return '[]'; }
  },
  spans: (wc, text) => JSON.stringify(matcher.find(text).map((m) => [m.start, m.end])),
  captionState: (wc, kind, lines) => {
    const st = stateOf(wc);
    if (kind !== st.captions) note('captions for this video: ' + kind + (lines > 0 ? ' (' + lines + ' lines so far)' : ''));
    st.captions = kind; st.captionLines = lines; st.captionsAt = Date.now();
    tellState();
  },
  beat: (wc, positionMs, width) => {
    const st = stateOf(wc);
    const was = playing(st);
    st.beatAt = Date.now(); st.width = width; st.positionMs = positionMs;
    if (!was) tellState();
  },
  state: (wc, positionMs, lengthMs, paused) => {
    const st = stateOf(wc);
    st.positionMs = positionMs; st.lengthMs = lengthMs; st.paused = paused; st.stateAt = Date.now();
  },
  command: (wc) => { const st = stateOf(wc); const c = st.pending; st.pending = ''; return c; },
  bestQuality: () => true,
  fullPicture: () => false,
  note: (wc, text) => { note(String(text)); },
  source: () => {},
  manifest: () => {},
  copyCues: () => {},
};

ipcMain.on('bridge', (event, method, ...args) => {
  const fn = bridge[method];
  let value = '';
  try {
    if (fn) value = ['version', 'scout', 'config', 'bestQuality', 'fullPicture'].includes(method) ? fn() : fn(event.sender, ...args);
  } catch (e) { note('the filter hit a problem: ' + e); }
  event.returnValue = value === undefined ? '' : value;
});

let pageScript = null;
ipcMain.on('page-script', (event) => {
  if (pageScript == null) {
    try { pageScript = fs.readFileSync(path.join(ROOT, 'src', 'shared', 'page', 'safewatch.js'), 'utf8'); } catch (e) { pageScript = ''; }
  }
  event.returnValue = pageScript;
});

// ---- What the shell asks ----

const iconCache = new Map();
async function iconFor(host) {
  if (iconCache.has(host)) return iconCache.get(host);
  const folder = path.join(app.getPath('userData'), 'icons');
  const saved = path.join(folder, host.replace(/[^a-z0-9.-]/gi, '_') + '.png');
  try {
    const stat = fs.statSync(saved);
    if (Date.now() - stat.mtimeMs < 30 * 86400000) {
      const url = 'data:image/png;base64,' + fs.readFileSync(saved).toString('base64');
      iconCache.set(host, url);
      return url;
    }
  } catch (e) { /* not saved yet */ }
  // The picture each site offers for saving it to a phone's home screen, then the site's largest icon.
  const tries = ['https://' + host + '/apple-touch-icon.png', 'https://www.google.com/s2/favicons?domain=' + host + '&sz=256'];
  for (const address of tries) {
    try {
      const res = await net.fetch(address);
      if (!res.ok || !(res.headers.get('content-type') || 'image').startsWith('image')) continue;
      const bytes = Buffer.from(await res.arrayBuffer());
      const image = nativeImage.createFromBuffer(bytes);
      if (image.isEmpty() || image.getSize().width < 48) continue;
      const png = image.toPNG();
      fs.mkdirSync(folder, { recursive: true });
      fs.writeFileSync(saved, png);
      const url = 'data:image/png;base64,' + png.toString('base64');
      iconCache.set(host, url);
      return url;
    } catch (e) { /* try the next */ }
  }
  iconCache.set(host, '');
  return '';
}

function settingsChanged(values, patch) {
  matcher = new F.ProfanityMatcher(values);
  version++;
  sendShell('settings', values);
  if ('nudity' in patch && values.nudity !== 'OFF') model.ensure();
}

function registerShell() {
  ipcMain.handle('home', () => setMode('home'));
  ipcMain.handle('settings-open', () => setMode('settings'));
  ipcMain.handle('browse', () => setMode('browse'));
  ipcMain.handle('navigate', (e, text) => navigate(text));
  ipcMain.handle('back', () => { const h = page.webContents.navigationHistory; if (h.canGoBack()) h.goBack(); });
  ipcMain.handle('forward', () => { const h = page.webContents.navigationHistory; if (h.canGoForward()) h.goForward(); });
  ipcMain.handle('reload', () => (page.webContents.isLoading() ? page.webContents.stop() : page.webContents.reload()));
  ipcMain.handle('state', () => pageState());
  ipcMain.handle('settings-get', () => store.get());
  ipcMain.handle('settings-set', (e, patch) => store.set(patch));
  ipcMain.handle('words', () => F.WORDS.map((g) => ({ id: g.id, label: g.label, level: g.level, blasphemy: !!g.blasphemy })));
  ipcMain.handle('words-muted', () => {
    const s = F.settingsFrom(store.get());
    return Object.fromEntries(F.WORDS.map((g) => [g.id, F.mutes(s, g)]));
  });
  ipcMain.handle('services', () => SERVICES);
  ipcMain.handle('icon', (e, host) => iconFor(host));
  ipcMain.handle('model', () => model.status());
  ipcMain.handle('model-get', () => model.ensure());
  ipcMain.handle('report', () => notes.join('\n'));
  ipcMain.handle('report-clear', () => { notes.length = 0; });
  ipcMain.handle('check-pin', (e, pin) => pin === store.get().pin);
  ipcMain.handle('about', () => ({ version: app.getVersion(), widevine, platform: process.platform }));
  ipcMain.handle('test-blur', () => { store.set({ testBlurUntil: Date.now() + 120000 }); });
  ipcMain.handle('open-outside', (e, url) => { if (/^https:\/\//.test(url)) shell.openExternal(url); });
}

// ---- The web session: blocking, safe search, permissions ----

function setUpWebSession() {
  const web = session.fromPartition('persist:web');
  // Sites see an ordinary Chrome, as on the phone they see the phone's browser.
  web.setUserAgent(web.getUserAgent().replace(/\s(Electron|edenOS|edenos-desktop)\/\S+/g, ''));
  web.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*'] }, (details, callback) => {
    const why = blockedWhy(details.url);
    if (why) {
      callback({ cancel: true });
      if (details.resourceType === 'mainFrame' && details.webContents) {
        let host = '';
        try { host = new URL(details.url).hostname; } catch (e) { /* shown blank */ }
        note('blocked ' + host + ' (' + why + ')');
        const wc = details.webContents;
        setImmediate(() => { if (!wc.isDestroyed()) wc.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(B.blockedPage(host, why))); });
      }
      return;
    }
    if (details.resourceType === 'mainFrame' || details.resourceType === 'subFrame') {
      const safe = B.safeSearch(details.url);
      if (safe) return callback({ redirectURL: safe });
    }
    callback({});
  });
  const allowed = new Set(['fullscreen', 'mediaKeySystem', 'clipboard-sanitized-write', 'pointerLock', 'keyboardLock', 'speaker-selection']);
  web.setPermissionRequestHandler((wc, permission, callback) => callback(allowed.has(permission)));
  web.setPermissionCheckHandler((wc, permission) => allowed.has(permission));
}

// ---- Menus and keys ----

function setUpMenu() {
  const send = (what) => () => sendShell('key', what);
  const template = [
    ...(MAC ? [{ role: 'appMenu' }] : []),
    { label: 'File', submenu: [{ label: 'Home', accelerator: 'CmdOrCtrl+H', click: send('home') },
      { label: 'Open address', accelerator: 'CmdOrCtrl+L', click: send('address') },
      { label: 'Settings', accelerator: 'CmdOrCtrl+,', click: send('settings') }, { type: 'separator' }, MAC ? { role: 'close' } : { role: 'quit' }] },
    { role: 'editMenu' },
    { label: 'View', submenu: [
      { label: 'Back', accelerator: MAC ? 'Cmd+[' : 'Alt+Left', click: () => page && page.webContents.navigationHistory.canGoBack() && page.webContents.navigationHistory.goBack() },
      { label: 'Forward', accelerator: MAC ? 'Cmd+]' : 'Alt+Right', click: () => page && page.webContents.navigationHistory.canGoForward() && page.webContents.navigationHistory.goForward() },
      { label: 'Reload', accelerator: 'CmdOrCtrl+R', click: () => page && page.webContents.reload() },
      { type: 'separator' }, { role: 'togglefullscreen' }, { role: 'zoomIn' }, { role: 'zoomOut' }, { role: 'resetZoom' }] },
    { role: 'windowMenu' },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

// ---- Start ----

app.whenReady().then(async () => {
  protocol.handle('eden', handleEden);
  setUpWebSession();
  setUpMenu();
  registerShell();
  store.onChange(settingsChanged);
  model.onChange((s) => { sendShell('model', s); if (s === 'ready') startDetector(); });
  createWindow();
  setInterval(checkPicture, CHECK_EVERY_MS);
  setInterval(tellState, 2000);
  if (store.get().nudity !== 'OFF') model.ensure();
  if (process.env.EDEN_SELFTEST) {
    require('./selftest')(() => ({ win, page: () => page, navigate, setMode, notes, shellKey: (k) => sendShell('key', k), checks: () => checks, store, curtain: () => curtain, pageState }));
  }
  // The paid services' videos are locked (DRM): Widevine, the module that plays them, is fetched by Electron for
  // Content Security the first time. It plays them as any browser would; nothing is unlocked by edenOS.
  if (electron.components) {
    widevine = 'getting ready';
    electron.components.whenReady()
      .then(() => { widevine = 'ready'; note('protected video: ready'); })
      .catch((e) => { widevine = 'not available'; note('protected video could not be set up: ' + e); });
  }
});

app.on('window-all-closed', () => app.quit());
app.on('activate', () => { if (!win) createWindow(); });
