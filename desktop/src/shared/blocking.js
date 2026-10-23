'use strict';
// Which websites the browser will not open, as in the phone app (core/SiteBlock.kt): adult sites from a list of tens
// of thousands, and Twitter (X), Reddit and Instagram when chosen. A site is blocked with every address under it.
// Searches always use safe search.

const SOCIAL = new Set([
  'twitter.com', 'x.com', 't.co', 'twimg.com', 'twitpic.com',
  'reddit.com', 'redd.it', 'redditmedia.com', 'redditstatic.com', 'reddit.app.link',
  'instagram.com', 'cdninstagram.com', 'instagr.am', 'ig.me',
]);
const ADULT_EXTRA = new Set([
  'pornhub.com', 'xvideos.com', 'xnxx.com', 'xhamster.com', 'onlyfans.com', 'chaturbate.com', 'stripchat.com',
  'youporn.com', 'redtube.com', 'spankbang.com', 'eporner.com', 'tube8.com', 'brazzers.com', 'fansly.com',
  'rule34.xxx', 'e-hentai.org', 'nhentai.net', 'hanime.tv', 'bongacams.com', 'livejasmin.com', 'cam4.com',
  'motherless.com', 'porn.com', 'beeg.com', 'txxx.com', 'hqporner.com', 'daftsex.com', 'porntrex.com',
]);

/** Why host is blocked ("adult" or "social"), or null when it is not. */
function reason(host, adult, blockAdult, blockSocial) {
  let name = (host || '').toLowerCase().replace(/\.+$/, '');
  if (!name) return null;
  for (;;) {
    if (blockSocial && SOCIAL.has(name)) return 'social';
    if (blockAdult && (adult.has(name) || ADULT_EXTRA.has(name))) return 'adult';
    const dot = name.indexOf('.');
    if (dot < 0 || name.indexOf('.', dot + 1) < 0) return null;
    name = name.substring(dot + 1);
  }
}

/** The same address with safe search switched on, or null when it already is (or is not a search). */
function safeSearch(address) {
  let u;
  try { u = new URL(address); } catch (e) { return null; }
  const host = u.hostname.toLowerCase();
  const set = (key, value) => { if (u.searchParams.get(key) === value) return null; u.searchParams.set(key, value); return u.toString(); };
  if (/(^|\.)google\.[a-z.]+$/.test(host) && /^\/(search|images|webhp)?$/.test(u.pathname) && u.searchParams.has('q')) return set('safe', 'active');
  if (/(^|\.)bing\.com$/.test(host) && /^\/(search|images|videos)/.test(u.pathname)) return set('adlt', 'strict');
  if (/(^|\.)duckduckgo\.com$/.test(host) && u.searchParams.has('q')) return set('kp', '1');
  return null;
}

const SEARCH = {
  google: 'https://www.google.com/search?safe=active&q=',
  duckduckgo: 'https://duckduckgo.com/?kp=1&q=',
  bing: 'https://www.bing.com/search?adlt=strict&q=',
};

/** What was typed in the address bar, as an address: a search unless it looks like a web address. */
function toUrl(text, engine) {
  const t = (text || '').trim();
  if (!t) return 'https://www.google.com/';
  if (/^(https?|file):\/\//i.test(t)) return t;
  if (!/\s/.test(t) && /\.[a-z]{2,}(\/|:|$)/i.test(t)) return 'https://' + t;
  if (/^localhost(:\d+)?(\/|$)/i.test(t)) return 'http://' + t;
  return (SEARCH[engine] || SEARCH.google) + encodeURIComponent(t);
}

/** The page shown instead of a blocked site. */
function blockedPage(host, why) {
  const what = why === 'social' ? 'Social media sites are blocked in edenOS.' : 'This site is blocked in edenOS.';
  const safe = String(host).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
  return '<!doctype html><html><head><meta charset="utf-8"><title>Blocked</title><style>body{margin:0;height:100vh;display:flex;' +
    'align-items:center;justify-content:center;background:#0b0f0d;color:#e9f5ee;font-family:system-ui,sans-serif;text-align:center}' +
    'div{padding:32px;max-width:440px}h1{font-weight:500;font-size:24px;margin:0 0 10px}p{color:#9fb5a8;font-size:15px;line-height:1.5;margin:0}' +
    '</style></head><body><div><h1>' + what + '</h1><p>' + safe + ' cannot be opened here.</p></div></body></html>';
}

module.exports = { reason, safeSearch, toUrl, blockedPage, SEARCH };
