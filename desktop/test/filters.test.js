'use strict';
// Checks the computer's language filter against the phone app's own answers (test/vectors.json, made by the phone
// app's code). Run with: node test/filters.test.js
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const F = require('../src/shared/filters');

const vectors = JSON.parse(fs.readFileSync(path.join(__dirname, 'vectors.json'), 'utf8'));
let checked = 0, failed = 0;
function same(actual, expected, what) {
  checked++;
  try { assert.deepStrictEqual(actual, expected); } catch (e) {
    failed++;
    if (failed <= 25) console.log('DIFFERENT ' + what + '\n  phone:    ' + JSON.stringify(expected) + '\n  computer: ' + JSON.stringify(actual));
  }
}

for (const variant of vectors.words) {
  const m = new F.ProfanityMatcher(variant.settings);
  for (const [line, found] of variant.found) {
    same(m.find(line).map((w) => [w.start, w.end, w.level]), found, JSON.stringify(variant.settings) + ' ' + JSON.stringify(line));
  }
}
const matcher = new F.ProfanityMatcher({});
for (const file of vectors.files) {
  const text = fs.readFileSync(path.join(__dirname, 'captions', file.name), 'utf8');
  const recognised = F.recognises(text);
  same(recognised, file.recognised, file.name + ' recognised');
  const cues = recognised ? F.parseCaptions(text) : [];
  same(cues.map((c) => [c.start, c.end, c.text]), file.cues, file.name + ' cues');
  same(F.muteRanges(cues, matcher).map((r) => [r.start, r.end, r.level]), file.mute, file.name + ' mute');
}
console.log((failed ? 'FAILED ' : 'passed ') + (checked - failed) + ' of ' + checked);
process.exit(failed ? 1 : 0);
