'use strict';
// Copies what the computer version shares with the phone app into place: the page script that does the muting,
// the list of adult sites, the edenOS lettering, and the detector's engine. Run before starting or building (npm start does it).
const fs = require('fs');
const path = require('path');
const app = path.join(__dirname, '..', '..', 'app', 'src', 'main');
const shared = path.join(__dirname, '..', 'src', 'shared');
const ort = path.join(__dirname, '..', 'node_modules', 'onnxruntime-web', 'dist');
const detector = path.join(__dirname, '..', 'src', 'detector', 'ort');
const copies = [
  // The engine that runs the nudity detector.
  ...['ort.wasm.min.js', 'ort-wasm-simd-threaded.mjs', 'ort-wasm-simd-threaded.wasm'].map((f) => [path.join(ort, f), path.join(detector, f)]),
  [path.join(app, 'assets', 'safewatch.js'), path.join(shared, 'page', 'safewatch.js')],
  [path.join(app, 'assets', 'blocklist-adult.txt'), path.join(shared, 'blocklist-adult.txt')],
  [path.join(app, 'res', 'font', 'poppins_light.ttf'), path.join(shared, 'page', 'poppins-light.ttf')],
];
for (const [from, to] of copies) {
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
}
console.log('synced ' + copies.length + ' files from the phone app');
