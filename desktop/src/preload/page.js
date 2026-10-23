'use strict';
// Runs in every web page and frame before the page's own code. It gives the phone app's page script the same
// SafeWatchBridge it has on the phone (each question answered at once by the app), then starts that script.
const { contextBridge, ipcRenderer, webFrame } = require('electron');

const METHODS = ['version', 'scout', 'config', 'cueWindows', 'captionCues', 'spans', 'captionState', 'beat', 'state', 'command',
  'bestQuality', 'fullPicture', 'note', 'source', 'manifest', 'copyCues'];
const bridge = {};
for (const m of METHODS) bridge[m] = (...args) => ipcRenderer.sendSync('bridge', m, ...args.map((a) => (typeof a === 'object' ? String(a) : a)));
contextBridge.exposeInMainWorld('SafeWatchBridge', bridge);

const script = ipcRenderer.sendSync('page-script');
if (script) webFrame.executeJavaScript(script).catch(() => { /* a page that refuses scripts */ });
