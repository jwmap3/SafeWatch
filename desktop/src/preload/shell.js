'use strict';
// What edenOS's own screens (Home, the bar, Settings) may ask of the app.
const { contextBridge, ipcRenderer } = require('electron');
const ask = (channel) => (...args) => ipcRenderer.invoke(channel, ...args);
contextBridge.exposeInMainWorld('eden', {
  home: ask('home'), openSettings: ask('settings-open'), browse: ask('browse'), navigate: ask('navigate'),
  back: ask('back'), forward: ask('forward'), reload: ask('reload'), state: ask('state'),
  settings: ask('settings-get'), set: ask('settings-set'), words: ask('words'), wordsMuted: ask('words-muted'),
  services: ask('services'), icon: ask('icon'), model: ask('model'), getModel: ask('model-get'),
  report: ask('report'), clearReport: ask('report-clear'), checkPin: ask('check-pin'), about: ask('about'),
  testBlur: ask('test-blur'), openOutside: ask('open-outside'),
  platform: process.platform,
  on: (channel, fn) => {
    if (!['page-state', 'settings', 'model', 'key'].includes(channel)) return;
    ipcRenderer.on(channel, (e, value) => fn(value));
  },
});
