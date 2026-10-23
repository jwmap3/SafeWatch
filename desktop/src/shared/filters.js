'use strict';
// The language filter, the same as the phone app's (core/ProfanityMatcher.kt, CaptionFormats.kt, Subtitles.kt), written
// again for the computer. desktop/test/vectors.json holds the phone app's own answers for many lines and caption files,
// and test/filters.test.js checks that this gives exactly the same ones.

const WORDS = require('./wordlist.json');

const LEVELS = { OFF: Infinity, LOW: 3, MEDIUM: 2, HIGH: 1 };

/** The viewer's choices, with the same defaults as the phone app. */
function settingsFrom(s) {
  s = s || {};
  return {
    language: s.language || 'MEDIUM',
    blasphemy: s.blasphemy !== false,
    customWords: s.customWords || [],
    allowedWords: s.allowedWords || [],
    wordChoices: s.wordChoices || {},
  };
}

function filters(strictness, level) { return level >= LEVELS[strictness]; }

function mutesByDefault(settings, group) {
  return group.blasphemy ? settings.blasphemy : filters(settings.language, group.level);
}

/** Whether a built-in word is muted. Nothing is when the language filter is off. */
function mutes(settings, group) {
  if (settings.language === 'OFF') return false;
  const chosen = settings.wordChoices[group.id];
  return chosen === undefined ? mutesByDefault(settings, group) : !!chosen;
}

function normalize(word) { return word.toLowerCase().replace(/[^\p{L}\p{N}]/gu, ''); }

const TOKEN = /[\p{L}\p{N}]+(?:['’][\p{L}]+)*/gu;
const SPACES = '\\s\\u00A0\\u2000-\\u200B\\u202F\\u3000';
const CENSORED = [
  /\p{L}+\*+\p{L}+|\p{L}+\*{2,}/gu,                                          // f**k, sh*t, f***
  new RegExp('\\[[' + SPACES + ']*_+[' + SPACES + ']*\\]', 'gu'),            // [ __ ], as YouTube writes it
  /(?<![\p{L}_])\p{L}{1,2}_{2,}\p{L}{0,2}(?![\p{L}_])/gu,                     // f___, s__t
  /[\[(<]\s*(?:bleep\w*|beep\w*|expletive\w*|censor\w*|profanit\w*|obscenit\w*|swear\w*|curs(?:e|es|ing)|explicit(?: language)?|bad word\w*)\s*[\])>]/giu,
  /[*-]\s*bleep\w*\s*[*-]/giu,                                                // *bleep*, -bleep-
  /(?=[@#$%&!*]*[@#$%&*])[@#$%&!*]{4,}/gu,                                    // @#$%!, ****
  /[\[(]?[■▪▮█▇▆▅▄▓▒░]{2,}[\])]?/gu,                                          // ■■■■
  /(?<![\p{L}_])_{3,}(?![\p{L}_])/gu,                                         // "you ___"
];
const GRAWLIX = /(?<![@#$%&!*])[@#$%&!*]{3}(?![@#$%&!*])/gu;
const MASKED = /[\p{L}\p{N}@$!#%&*_-]*[\p{L}\p{N}][\p{L}\p{N}@$!#%&*_-]*/gu;
const BLANKS = /[*_#%&]+|-+/g;

/** Every built-in word in its usual forms, to test a blanked-out word against. Blasphemy is left out ("G-d" is reverent). */
const SPELLINGS = (() => {
  const endings = ['', 's', 'ed', 'er', 'ers', 'ing', 'in', 'y', 'ies', 'head', 'hole', 'face', 'ass'];
  const starts = ['', 'bull', 'mother', 'dip', 'horse', 'chicken', 'holy', 'dumb', 'jack', 'smart'];
  const out = new Map();
  const put = (k, g) => { if (!out.has(k)) out.set(k, g); };
  for (const group of WORDS) {
    if (group.blasphemy) continue;
    for (const p of group.patterns) {
      const t = p.trim().toLowerCase();
      if (t.includes(' ')) continue;
      const stem = normalize(t);
      if (stem.length < 3 && !t.endsWith('*')) { put(stem, group); continue; }
      if (t.startsWith('*') && t.endsWith('*')) { for (const a of starts) for (const b of endings) put(a + stem + b, group); }
      else if (t.endsWith('*')) { for (const b of endings) put(stem + b, group); }
      else put(stem, group);
    }
  }
  return [...out.entries()];
})();

const isLetter = (c) => /\p{L}/u.test(c);
const isDigit = (c) => /\p{Nd}/u.test(c);
const isLetterOrDigit = (c) => isLetter(c) || isDigit(c);

/** Finds the words in a piece of text that the given settings want muted, as [{start, end, level}]. */
class ProfanityMatcher {
  constructor(raw) {
    const settings = this.settings = settingsFrom(raw);
    this.exact = new Map();
    this.prefixes = [];
    this.contains = [];
    this.phrases = [];
    this.allowed = new Set(settings.allowedWords.map(normalize).filter((w) => w));
    this.censoredLevel = settings.language !== 'OFF' ? 3 : 0;
    if (settings.language !== 'OFF') {
      for (const group of WORDS) if (mutes(settings, group)) group.patterns.forEach((p) => this.add(p, group.level));
      settings.customWords.forEach((w) => this.add(w, 3));
    }
  }

  add(pattern, level) {
    const p = pattern.trim().toLowerCase();
    const words = p.split(/\s+/).map(normalize).filter((w) => w);
    if (!words.length) return;
    if (words.length > 1) this.phrases.push([words, level]);
    else if (p.startsWith('*') && p.endsWith('*')) this.contains.push([words[0], level]);
    else if (p.endsWith('*')) this.prefixes.push([words[0], level]);
    else this.exact.set(words[0], Math.max(this.exact.get(words[0]) || 0, level));
  }

  find(text) {
    if (this.censoredLevel === 0 && !this.exact.size && !this.prefixes.length && !this.contains.length && !this.phrases.length) return [];
    const tokens = [...text.matchAll(TOKEN)].map((m) => ({ text: normalize(m[0]), start: m.index, end: m.index + m[0].length }));
    const out = [];
    for (const t of tokens) {
      if (this.allowed.has(t.text)) continue;
      let level = this.exact.get(t.text) || 0;
      for (const [p, l] of this.prefixes) if (l > level && t.text.startsWith(p)) level = l;
      for (const [p, l] of this.contains) if (l > level && t.text.includes(p)) level = l;
      if (level > 0) out.push({ start: t.start, end: t.end, level });
    }
    for (const [words, level] of this.phrases) {
      for (let i = 0; i <= tokens.length - words.length; i++) {
        if (words.every((w, k) => tokens[i + k].text === w)) out.push({ start: tokens[i].start, end: tokens[i + words.length - 1].end, level });
      }
    }
    if (this.censoredLevel > 0) {
      for (const pattern of CENSORED) {
        for (const m of text.matchAll(pattern)) out.push({ start: m.index, end: m.index + m[0].length, level: this.censoredLevel });
      }
      for (const m of text.matchAll(GRAWLIX)) {
        const kinds = new Set([...m[0]].filter((c) => '@#$%&*'.includes(c)));
        if (m[0].length === 3 && kinds.size >= 2) out.push({ start: m.index, end: m.index + 3, level: this.censoredLevel });
      }
      for (const m of text.matchAll(MASKED)) {
        const found = this.masked(m[0]);
        if (found) out.push({ start: m.index + found[0], end: m.index + found[1], level: found[2] });
      }
    }
    return out.sort((a, b) => a.start - b.start);
  }

  containsProfanity(text) { return this.find(text).length > 0; }

  /** Whether a run of letters with blanks or symbols in it is a listed word written that way: [from, to, level] or null. */
  masked(raw) {
    let from = 0, to = raw.length;
    while (from < to && '-!'.includes(raw[from])) from++;
    while (to > from && raw[to - 1] === '!') to--;
    let dashes = 0;
    while (to - dashes > from && raw[to - dashes - 1] === '-') dashes++;
    const before = raw.substring(from, to - dashes);
    const keepDashes = dashes >= 3 || (dashes === 2 && before.length === 1 && !['a', 'i'].includes(before.toLowerCase()));
    if (!keepDashes) to -= dashes;
    const word = raw.substring(from, to);
    if (![...word].some(isLetter)) return null;
    const blanks = [...word.matchAll(BLANKS)];
    const swaps = [...word].some((c) => '@$!'.includes(c) || isDigit(c));
    if (!blanks.length && !swaps) return null;
    const chars = word.split('');
    if (chars.every(isLetterOrDigit) && chars.filter(isDigit).length > chars.filter(isLetter).length) return null;
    let pattern = '';
    let i = 0;
    while (i < word.length) {
      const c = word[i];
      const blank = blanks.find((b) => b.index === i);
      if (blank) { pattern += '\\p{L}{1,' + (blank[0].length + 2) + '}'; i = blank.index + blank[0].length; continue; }
      const lc = c.toLowerCase();
      if (lc === '@' || lc === '4') pattern += 'a';
      else if (lc === '$' || lc === '5') pattern += 's';
      else if (lc === '!' || lc === '1') pattern += '[il]';
      else if (lc === '0') pattern += 'o';
      else if (lc === '3') pattern += 'e';
      else if (lc === '7') pattern += 't';
      else if (lc >= 'a' && lc <= 'z') pattern += lc;
      else if (isLetter(c)) pattern += lc.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      else return null;
      i++;
    }
    const regex = new RegExp('^(?:' + pattern + ')$', 'u');
    let level = 0;
    for (const [spelled, group] of SPELLINGS) {
      if (this.allowed.has(spelled) || !regex.test(spelled)) continue;
      level = blanks.length ? this.censoredLevel : (mutes(this.settings, group) ? group.level : 0);
      if (level > 0) break;
    }
    return level > 0 ? [from, to, level] : null;
  }
}

// ---- Caption files ----

const MARKUP = /<[^>]*>|\{[^}]*\}/g;
const TIME = '(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})';
const TIMING = new RegExp(TIME + '\\s*-->\\s*' + TIME);

function cleanText(text) { return text.replace(MARKUP, '').replace(/\s+/g, ' ').trim(); }

function toMs(g, at) {
  const h = Number(g[at] || '0');
  const millis = Number((g[at + 3] || '').padEnd(3, '0'));
  return ((h * 60 + Number(g[at + 1])) * 60 + Number(g[at + 2])) * 1000 + millis;
}

/** SRT and WebVTT. */
function parseSubtitles(content) {
  const cues = [];
  const lines = content.replace(/\uFEFF/g, '').replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
  let i = 0;
  while (i < lines.length) {
    const m = lines[i].match(TIMING);
    i++;
    if (!m) continue;
    const start = toMs(m, 1), end = toMs(m, 5);
    let text = '';
    while (i < lines.length && lines[i].trim() && !TIMING.test(lines[i])) {
      if (text) text += ' ';
      text += lines[i].trim();
      i++;
    }
    const clean = cleanText(text);
    if (clean && end > start) cues.push({ start, end, text: clean });
  }
  return cues;
}

const TAGS = /<[^>]*>/g;

function clean(s) {
  return s.replace(TAGS, '')
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&apos;/g, "'")
    .replace(/&nbsp;/g, ' ')
    .replace(/&#(x[0-9a-fA-F]{1,6}|[0-9]{1,7});/g, (all, v) => {
      const code = v.startsWith('x') ? parseInt(v.slice(1), 16) : parseInt(v, 10);
      return Number.isFinite(code) && code >= 0 && code <= 0x10FFFF ? String.fromCodePoint(code) : all;
    })
    .replace(/\s+/g, ' ').trim();
}

function unescapeJson(s) {
  return s.replace(/\\n/g, ' ').replace(/\\"/g, '"').replace(/\\\//g, '/')
    .replace(/\\u([0-9a-fA-F]{4})/g, (all, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/\\\\/g, '\\');
}

function attribute(attributes, name) {
  const m = attributes.match(new RegExp('(?:^|\\s)' + name + '="([^"]*)"'));
  return m ? m[1] : null;
}

function ttmlTime(value, tickRate, frameRate) {
  const v = value.trim();
  let m = v.match(/^(\d+):(\d\d):(\d\d)(?:[.,](\d+)|:(\d+))?$/);
  if (m) {
    const whole = (Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3])) * 1000;
    const fraction = m[4] ? Number('0.' + m[4]) * 1000 : m[5] ? Number(m[5]) / frameRate * 1000 : 0;
    return whole + Math.trunc(fraction);
  }
  m = v.match(/^([\d.]+)(h|m|s|ms|f|t)$/);
  if (m) {
    const n = Number(m[1]);
    if (!Number.isFinite(n)) return null;
    const ms = { h: n * 3600000, m: n * 60000, s: n * 1000, ms: n, f: n / frameRate * 1000 }[m[2]];
    return Math.trunc(ms === undefined ? n / tickRate * 1000 : ms);
  }
  return null;
}

function ttml(text) {
  const tick = text.match(/tickRate="(\d+)"/), frame = text.match(/frameRate="(\d+)"/);
  const tickRate = tick ? Number(tick[1]) : 10000000, frameRate = frame ? Number(frame[1]) : 30;
  const cues = [];
  for (const p of text.matchAll(/<(?:\w+:)?p\b([^>]*)>(.*?)<\/(?:\w+:)?p>/gs)) {
    const a = p[1];
    const b = attribute(a, 'begin');
    const begin = b == null ? null : ttmlTime(b, tickRate, frameRate);
    if (begin == null) continue;
    let end = null;
    const e = attribute(a, 'end');
    if (e != null) end = ttmlTime(e, tickRate, frameRate);
    if (end == null) {
      const d = attribute(a, 'dur');
      const dur = d == null ? null : ttmlTime(d, tickRate, frameRate);
      if (dur != null) end = begin + dur;
    }
    if (end == null) continue;
    const words = clean(p[2].replace(/<(?:\w+:)?br\s*\/?>/g, ' '));
    if (words && end > begin) cues.push({ start: begin, end, text: words });
  }
  return cues;
}

function youtubeJson(text) {
  const cues = [];
  const events = /\{[^{}]*"tStartMs"\s*:\s*(\d+)[^{}]*?(?:"segs"\s*:\s*\[(.*?)\])?[^{}]*\}/gs;
  for (const event of text.matchAll(events)) {
    const start = Number(event[1]);
    const d = event[0].match(/"dDurationMs"\s*:\s*(\d+)/);
    const length = d ? Number(d[1]) : 2000;
    const segs = [...(event[2] || '').matchAll(/\{[^{}]*\}/g)].map((seg) => {
      const w = seg[0].match(/"utf8"\s*:\s*"((?:[^"\\]|\\.)*)"/);
      const o = seg[0].match(/"tOffsetMs"\s*:\s*(\d+)/);
      return [unescapeJson(w ? w[1] : ''), o ? Number(o[1]) : null];
    });
    if (!segs.length) continue;
    if (segs.some((s) => s[1] != null)) {
      segs.forEach(([words, offset], i) => {
        const from = start + (offset || 0);
        const next = segs[i + 1];
        const to = next && next[1] != null ? start + next[1] : Math.min(from + 900, start + length);
        const c = clean(words);
        if (c && to > from) cues.push({ start: from, end: to, text: c });
      });
    } else {
      const line = clean(segs.map((s) => s[0]).join(''));
      if (line) cues.push({ start, end: start + length, text: line });
    }
  }
  return cues;
}

function youtubeXml(text) {
  const cues = [];
  for (const p of text.matchAll(/<p\b([^>]*)>(.*?)<\/p>/gs)) {
    const t = attribute(p[1], 't');
    const start = t != null && /^-?\d+$/.test(t) ? Number(t) : null;
    if (start == null) continue;
    const dv = attribute(p[1], 'd');
    const length = dv != null && /^-?\d+$/.test(dv) ? Number(dv) : 2000;
    const line = clean(p[2]);
    if (line) cues.push({ start, end: start + length, text: line });
  }
  return cues;
}

/** True when text looks like a caption file this can read. Only the start of the text is examined. */
function recognises(text) {
  const head = text.slice(0, 1500);
  return head.replace(/^[\uFEFF \n\r]+/, '').startsWith('WEBVTT') ||
    /<tt[\s:>]/.test(head) ||
    head.includes('<timedtext') ||
    (head.includes('"events"') && (text.includes('"segs"') || text.includes('tStartMs'))) ||
    /\d\d:\d\d:\d\d[,.]\d{3}\s*-->/.test(head);
}

function parseCaptions(text) {
  const head = text.slice(0, 1500);
  if (head.includes('"events"') && text.includes('tStartMs')) return youtubeJson(text);
  if (head.includes('<timedtext')) return youtubeXml(text);
  if (/<tt[\s:>]/.test(head)) return ttml(text);
  return parseSubtitles(text);
}

// ---- When to mute ----

const PAD_BEFORE_MS = 600, PAD_AFTER_MS = 700, SHORT_CUE_MS = 1200, EDGE_MS = 250;

function merge(ranges) {
  const out = [];
  for (const t of [...ranges].sort((a, b) => a.start - b.start)) {
    const last = out[out.length - 1];
    if (last && t.start <= last.end) { last.end = Math.max(last.end, t.end); last.level = Math.max(last.level, t.level); }
    else out.push({ ...t });
  }
  return out;
}

/** The stretches of time to mute for caption lines [{start, end, text}], as [{start, end, level}]. */
function muteRanges(cues, matcher) {
  const all = [];
  for (const cue of cues) {
    const text = cleanText(cue.text);
    const matches = matcher.find(text);
    if (!matches.length) continue;
    const length = cue.end - cue.start;
    const one = matches.map((m) => {
      let from, to;
      if (length <= SHORT_CUE_MS || !text.length) { from = cue.start - EDGE_MS; to = cue.end + EDGE_MS; }
      else {
        from = Math.max(cue.start + Math.trunc(length * m.start / text.length) - PAD_BEFORE_MS, cue.start - EDGE_MS);
        to = Math.min(cue.start + Math.trunc(length * m.end / text.length) + PAD_AFTER_MS, cue.end + EDGE_MS);
      }
      return { start: Math.max(0, from), end: to, level: m.level };
    });
    all.push(...merge(one));
  }
  return merge(all);
}

module.exports = { WORDS, ProfanityMatcher, settingsFrom, mutes, normalize, recognises, parseCaptions, parseSubtitles, cleanText, muteRanges };
