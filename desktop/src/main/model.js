'use strict';
// The nudity-detection file: the same small NudeNet detector the phone app uses, downloaded once from its published
// package on the Python Package Index (a zip file with the model inside), about 11 MB.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { app, net } = require('electron');

const PACKAGE_INFO = 'https://pypi.org/pypi/nudenet/json';

let state = 'missing'; // missing, downloading, ready, failed
const listeners = [];

function file() { return path.join(app.getPath('userData'), 'models', 'nudenet.onnx'); }

function installed() {
  try { return fs.statSync(file()).size > 1000000; } catch (e) { return false; }
}

function status() { return installed() ? 'ready' : state; }

function tell() { listeners.forEach((l) => { try { l(status()); } catch (e) { /* ignore */ } }); }

function onChange(l) { listeners.push(l); }

/** Reads one file out of a zip held in memory, by the end of its name. */
function unzipOne(zip, nameEnd) {
  let end = -1;
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 70000); i--) {
    if (zip.readUInt32LE(i) === 0x06054b50) { end = i; break; }
  }
  if (end < 0) throw new Error('not a zip file');
  const count = zip.readUInt16LE(end + 10);
  let at = zip.readUInt32LE(end + 16);
  for (let n = 0; n < count; n++) {
    if (zip.readUInt32LE(at) !== 0x02014b50) throw new Error('damaged zip file');
    const method = zip.readUInt16LE(at + 10);
    const size = zip.readUInt32LE(at + 20);
    const nameLength = zip.readUInt16LE(at + 28);
    const extraLength = zip.readUInt16LE(at + 30);
    const commentLength = zip.readUInt16LE(at + 32);
    const local = zip.readUInt32LE(at + 42);
    const name = zip.toString('utf8', at + 46, at + 46 + nameLength);
    if (name.endsWith(nameEnd)) {
      const start = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28);
      const data = zip.subarray(start, start + size);
      return method === 0 ? Buffer.from(data) : zlib.inflateRawSync(data);
    }
    at += 46 + nameLength + extraLength + commentLength;
  }
  throw new Error('the detector was not found in the package');
}

/** Starts the download in the background unless the file is here or already on its way. */
async function ensure() {
  if (installed() || state === 'downloading') return status();
  state = 'downloading';
  tell();
  try {
    const info = await (await net.fetch(PACKAGE_INFO)).json();
    const wheel = (info.urls || []).find((f) => f.packagetype === 'bdist_wheel');
    if (!wheel || !wheel.url.startsWith('https://files.pythonhosted.org/')) throw new Error('the package was not found');
    const zip = Buffer.from(await (await net.fetch(wheel.url)).arrayBuffer());
    const model = unzipOne(zip, '320n.onnx');
    fs.mkdirSync(path.dirname(file()), { recursive: true });
    fs.writeFileSync(file() + '.part', model);
    fs.renameSync(file() + '.part', file());
    state = 'ready';
  } catch (e) {
    state = 'failed';
  }
  tell();
  return status();
}

module.exports = { file, installed, status, ensure, onChange, unzipOne };
