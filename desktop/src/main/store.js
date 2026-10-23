'use strict';
// The viewer's settings, kept in one file in the app's own folder.
const fs = require('fs');
const path = require('path');
const { app } = require('electron');

const DEFAULTS = {
  // Language, as on the phone.
  language: 'MEDIUM', blasphemy: true, wordChoices: {}, customWords: [], allowedWords: [],
  showCaptions: false, silentWithoutCaptions: false,
  // Nudity.
  nudity: 'MEDIUM', nudityAction: 'BLUR', hideStyle: 'blur', testBlurUntil: 0,
  // Browser.
  searchEngine: 'google', blockAdult: true, blockSocial: true, blockPopups: true,
  // Home.
  services: ['netflix', 'hbomax', 'prime', 'disney', 'hulu'],
  quickLinks: [['Google', 'https://www.google.com/'], ['YouTube', 'https://www.youtube.com/'], ['Wikipedia', 'https://en.wikipedia.org/'],
    ['Weather', 'https://weather.com/'], ['Pluto TV', 'https://pluto.tv/'], ['Tubi', 'https://tubitv.com/']],
  pin: '',
};

let file = null;
let values = null;
const listeners = [];

function load() {
  if (values) return values;
  file = path.join(app.getPath('userData'), 'settings.json');
  let saved = {};
  try { saved = JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { /* first run */ }
  values = { ...DEFAULTS, ...saved };
  return values;
}

function get() { return { ...load() }; }

function set(patch) {
  load();
  values = { ...values, ...patch };
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file + '.part', JSON.stringify(values, null, 2));
    fs.renameSync(file + '.part', file);
  } catch (e) { /* kept in memory until the next save */ }
  listeners.forEach((l) => { try { l(values, patch); } catch (e) { /* ignore */ } });
  return get();
}

function onChange(l) { listeners.push(l); }

module.exports = { get, set, onChange, DEFAULTS };
