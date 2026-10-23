'use strict';
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('detectorLink', {
  onFrame: (fn) => ipcRenderer.on('detector-frame', (e, id, jpeg, faces) => fn(id, jpeg, faces)),
  level: (id, level) => ipcRenderer.send('detector-level', id, level),
  ready: (ok, info) => ipcRenderer.send('detector-ready', ok, info),
});
