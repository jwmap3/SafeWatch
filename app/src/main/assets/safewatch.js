// edenOS page script (the file keeps its first name, safewatch.js). Runs inside every page (and every frame) the built-in
// browser opens. It never decides what is objectionable itself: it asks the app
// through SafeWatchBridge, then mutes, skips or blurs the page's own <video>.
//
// How it knows when to mute, best source first:
//   1. Caption data with times, known ahead: the film's caption track read from
//      the player's manifest, a caption file the player downloaded, or the
//      video's own text tracks.
//   2. Captions drawn on the page, which only appear as the line is spoken.
// The second stops being used once the first has been checked against the screen.
(function () {
  'use strict';
  if (window.__safewatch) return;
  var B = window.SafeWatchBridge;
  if (!B) return;

  // Where well-known players draw their captions when they do not use text tracks.
  var CAPTION_SELECTORS = [
    '.ytp-caption-segment',                 // YouTube
    '.player-timedtext-text-container',     // Netflix
    '[data-testid="CueBoxContainer"]',      // HBO Max
    '[class*="CaptionWindow"]', '[class*="TextCue"]',
    '.atvwebplayersdk-captions-text',       // Prime Video
    '.dss-subtitle-renderer-cue', '.dss-subtitle-renderer-line', // Disney+
    '.hive-subtitle-renderer-cue',
    '.CaptionBox', '.caption-text-box',     // Hulu
    '.video-player__subtitles',             // Peacock
    '.captions-text',
    '.vjs-text-track-cue',                  // players built on video.js, JW Player, Shaka, Plyr, Bitmovin, THEOplayer
    '.jw-text-track-cue',
    '.shaka-text-container span',
    '.plyr__caption',
    '.bmpui-ui-subtitle-label',
    '.theoplayer-texttracks span',
    '.vp-captions-line',                    // Vimeo
    '.ttr-cue'
  ].join(', ');
  var TICK_MS = 100;
  // True in the hidden second copy of a video that the app plays a few seconds ahead of the viewer
  // to see what is coming. That copy filters nothing: it stays silent, fills its screen with the
  // video, reports where it is and goes where it is told.
  var SCOUT = false;
  try { SCOUT = !!(B.scout && B.scout()); } catch (e) { /* an ordinary page */ }

  var S = window.__safewatch = {
    version: -1, language: false, showCaptions: true, strict: false, tags: [], ticks: 0, filledAt: 0,
    sources: [],        // caption data with times, known ahead
    screen: [],         // mute stretches worked out from captions drawn on the page, in video time
    screenSeenAt: 0,    // when captions were last seen drawn on the page
    lastState: '', lastStateAt: 0, playingSince: 0, address: ''
  };
  var states = new WeakMap();
  var realFetch = window.fetch;

  function say(text) { try { if (B.note) B.note(text); } catch (e) { /* ignore */ } }

  function stateOf(video) {
    var st = states.get(video);
    if (!st) {
      st = { windows: [], starts: [], seen: {}, cueCounts: new WeakMap(), mutedByUs: false, wasMuted: false,
             blurredByUs: false, oldFilter: '' };
      states.set(video, st);
      watchForTrouble(video);
    }
    return st;
  }

  // ---- Caption data with times ----
  //
  // A source is one set of caption lines with their times. `windows` are the stretches to mute,
  // worked out by the app from the lines. `offset` moves them if the screen shows they are early or late.

  function squash(text) { return text.toLowerCase().replace(/[^a-z0-9]+/g, ''); }

  function sourceFor(key, kind) {
    for (var i = 0; i < S.sources.length; i++) if (S.sources[i].key === key) return S.sources[i];
    var source = { key: key, kind: kind, made: Date.now(), cues: [], windows: [], starts: [], seen: {}, index: {},
                   offset: 0, deltas: [], checked: kind === 'file', length: 0, segs: null, busy: 0, failed: 0, fetched: 0, dead: false };
    S.sources.push(source);
    if (S.sources.length > 6) S.sources.shift();
    return source;
  }

  function windowsFor(cues) {
    var out = [];
    if (!S.language || !cues.length || !B.cueWindows) return out;
    for (var i = 0; i < cues.length; i += 400) {
      try { out = out.concat(JSON.parse(B.cueWindows(JSON.stringify(cues.slice(i, i + 400))))); } catch (e) { /* skip this batch */ }
    }
    return out;
  }

  // Adds caption lines, each [startMs, endMs, text], to a source. Returns how many were new.
  function addCues(source, cues) {
    var fresh = [];
    for (var i = 0; i < cues.length; i++) {
      var c = cues[i], id = c[0] + '|' + c[2];
      if (source.seen[id] || !c[2]) continue;
      source.seen[id] = true;
      fresh.push(c);
      source.cues.push(c);
      source.starts.push(c[0]);
      var words = squash(c[2]);
      if (words.length >= 12) source.index[words] = (words in source.index) ? -1 : c[0];
    }
    var found = windowsFor(fresh);
    for (i = 0; i < found.length; i++) source.windows.push(found[i]);
    shareCues(fresh, source.offset);
    return fresh.length;
  }

  // Caption lines with times are also passed to the app, for a clean copy made for the TV.
  function shareCues(cues, offset) {
    if (!B.copyCues || !cues.length) return;
    var out = [];
    for (var i = 0; i < cues.length; i++) out.push([cues[i][0] + offset, cues[i][1] + offset, cues[i][2]]);
    for (var j = 0; j < out.length; j += 500) {
      try { B.copyCues(JSON.stringify(out.slice(j, j + 500))); } catch (e) { /* ignore */ }
    }
  }

  // Whether a source belongs to the video as it is now. A source read for a film does not apply
  // while an advert of a different length is playing in the same player.
  function applies(source, video, advert) {
    if (advert) return false;
    var length = isFinite(video.duration) ? video.duration : 0;
    if (!source.length) {
      if (length > 0 && Date.now() - source.made > 1000) source.length = length;
      return true;
    }
    return !length || Math.abs(length - source.length) < 5;
  }

  // True when caption data whose times can be trusted has lines around this moment.
  function trustedHere(video, st, t, advert) {
    var n, k, source;
    for (k = 0; k < st.starts.length; k++) if (Math.abs(st.starts[k] - t) < 45000) return true;
    for (n = 0; n < S.sources.length; n++) {
      source = S.sources[n];
      if (!source.checked || !applies(source, video, advert)) continue;
      for (k = 0; k < source.starts.length; k++) if (Math.abs(source.starts[k] + source.offset - t) < 45000) return true;
    }
    return false;
  }

  // ---- Reading caption files ----

  var TIME = '((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})';
  var TIMING = new RegExp('^\\s*' + TIME + '\\s*-->\\s*' + TIME);

  function clock(text) {
    var p = text.replace(',', '.').split(':'), s = 0;
    for (var i = 0; i < p.length; i++) s = s * 60 + parseFloat(p[i]);
    return Math.round(s * 1000);
  }

  function plain(text) {
    return text.replace(/<[^>]*>/g, '').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/&nbsp;/g, ' ').replace(/&#39;|&apos;/g, "'").replace(/&quot;/g, '"').replace(/\s+/g, ' ').trim();
  }

  // Reads WebVTT (or SRT) text into [startMs, endMs, text] lines.
  function readVtt(text) {
    var lines = text.replace(/\r\n?/g, '\n').split('\n'), cues = [], i = 0;
    while (i < lines.length) {
      var m = TIMING.exec(lines[i]);
      i++;
      if (!m) continue;
      var words = [];
      while (i < lines.length && lines[i].trim() !== '' && !TIMING.test(lines[i])) { words.push(lines[i]); i++; }
      var said = plain(words.join(' ')), from = clock(m[1]), to = clock(m[2]);
      if (said && to > from) cues.push([from, to, said]);
    }
    return cues;
  }

  // Caption pieces wrapped in a video container: pulls out the text they carry.
  function readWrapped(bytes, text, length) {
    var cues = [], at = text.indexOf('<tt'), end = text.lastIndexOf('</tt>');
    if (at >= 0 && end > at && B.captionCues) {
      try { return JSON.parse(B.captionCues(new TextDecoder('utf-8').decode(bytes.subarray(at, end + 5)))); } catch (e) { return cues; }
    }
    // Lines stored one per box, with their times kept elsewhere in the container: each is taken to be
    // spoken somewhere within this piece.
    for (at = text.indexOf('payl'); at >= 4; at = text.indexOf('payl', at + 4)) {
      var size = (bytes[at - 4] << 24 | bytes[at - 3] << 16 | bytes[at - 2] << 8 | bytes[at - 1]) >>> 0;
      if (size < 9 || size > 2000 || at - 4 + size > bytes.length) continue;
      var said = plain(new TextDecoder('utf-8').decode(bytes.subarray(at + 4, at - 4 + size)));
      if (said) cues.push([0, length, said, 'whole piece']);
    }
    return cues;
  }

  // A whole caption file the page's player downloaded.
  function offerCaptions(text) {
    if (!text || text.length < 20 || text.length > 6000000 || !B.captionCues) return;
    if (text.slice(0, 1500).indexOf('X-TIMESTAMP-MAP') >= 0) { offerPiece(text); return; }
    var key = 'file:' + text.length + ':' + text.slice(200, 260);
    for (var i = 0; i < S.sources.length; i++) if (S.sources[i].key === key) return;
    var cues;
    try { cues = JSON.parse(B.captionCues(text)); } catch (e) { return; }
    if (!cues.length) return;
    var source = sourceFor(key, 'file'), added = addCues(source, cues);
    say('caption file read ahead: ' + added + ' lines, ' + source.windows.length + ' stretches to mute');
  }

  // One small piece of a caption track that the player downloaded for itself (it does this only while
  // captions are switched on). Used when the whole track could not be read from the manifest.
  function offerPiece(text) {
    for (var i = 0; i < S.sources.length; i++) if (S.sources[i].segs && !S.sources[i].dead) return; // already reading the whole track
    var cues = readVtt(text);
    if (!cues.length) return;
    var source = sourceFor('pieces', 'pieces');
    if (addCues(source, cues) && source.cues.length === cues.length) say('reading caption pieces as the player downloads them');
  }

  function looksLikeCaptions(head) {
    return /^\uFEFF?\s*WEBVTT/.test(head) || /<tt[\s:>]/.test(head) || head.indexOf('<timedtext') >= 0 ||
      (head.indexOf('"events"') >= 0 && head.indexOf('tStartMs') >= 0) || /\d\d:\d\d:\d\d[,.]\d{3}\s*-->/.test(head);
  }

  // ---- Reading the whole caption track from the player's manifest ----
  //
  // Streaming players are handed a manifest: a list of every piece of the film, including its
  // caption tracks, each cut into small files. The player only downloads caption pieces if captions
  // are switched on, and only a little ahead. Reading the manifest ourselves gives the whole film's
  // captions, with times, whether or not the viewer has captions showing.

  function absolute(url, base) { try { return new URL(url, base).href; } catch (e) { return url; } }

  function seconds(iso) { // PT1H2M3.5S
    var m = /^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?$/.exec(iso || '');
    if (!m) return NaN;
    return (+m[1] || 0) * 86400 + (+m[2] || 0) * 3600 + (+m[3] || 0) * 60 + (parseFloat(m[4]) || 0);
  }

  function childrenNamed(el, name) {
    var out = [];
    for (var i = 0; i < el.children.length; i++) if (el.children[i].localName === name) out.push(el.children[i]);
    return out;
  }

  function english(lang) { return /^en/i.test(lang || ''); }

  function readDash(text, address) {
    var doc;
    try { doc = new DOMParser().parseFromString(text, 'application/xml'); } catch (e) { return; }
    var mpd = doc.documentElement;
    if (!mpd || mpd.localName !== 'MPD' || mpd.getAttribute('type') === 'dynamic') return;
    var key = 'dash:' + address.split('?')[0];
    for (var i = 0; i < S.sources.length; i++) if (S.sources[i].key === key) return;
    var base = address, top = childrenNamed(mpd, 'BaseURL')[0];
    if (top) base = absolute(top.textContent.trim(), base);
    var total = seconds(mpd.getAttribute('mediaPresentationDuration')), periods = childrenNamed(mpd, 'Period');
    var segs = [], at = 0, tracks = 0, other = 0;

    for (var p = 0; p < periods.length; p++) {
      var period = periods[p], start = seconds(period.getAttribute('start'));
      if (isNaN(start)) start = at;
      var length = seconds(period.getAttribute('duration'));
      if (isNaN(length)) {
        var next = periods[p + 1] ? seconds(periods[p + 1].getAttribute('start')) : total;
        length = isNaN(next) ? 0 : Math.max(0, next - start);
      }
      at = start + length;
      var periodBase = base, pb = childrenNamed(period, 'BaseURL')[0];
      if (pb) periodBase = absolute(pb.textContent.trim(), base);

      var sets = childrenNamed(period, 'AdaptationSet'), pick = null;
      for (var a = 0; a < sets.length; a++) {
        var set = sets[a], rep = childrenNamed(set, 'Representation')[0];
        var kind = (set.getAttribute('contentType') || '') + ' ' + (set.getAttribute('mimeType') || '') + ' ' +
          (rep ? (rep.getAttribute('mimeType') || '') + ' ' + (rep.getAttribute('codecs') || '') : '') + ' ' + (set.getAttribute('codecs') || '');
        if (!/text|vtt|ttml|stpp/i.test(kind)) continue;
        if (!english(set.getAttribute('lang'))) { other++; continue; }
        var roles = childrenNamed(set, 'Role').map(function (r) { return r.getAttribute('value') || ''; }).join(' ');
        if (/forced/i.test(roles)) continue; // only the odd line in another language
        if (!pick || /caption/i.test(roles)) pick = set;
      }
      if (!pick) continue;
      tracks++;
      var rep0 = childrenNamed(pick, 'Representation')[0];
      if (!rep0) continue;
      var repBase = periodBase, sb = childrenNamed(pick, 'BaseURL')[0], rb = childrenNamed(rep0, 'BaseURL')[0];
      if (sb) repBase = absolute(sb.textContent.trim(), repBase);
      if (rb) repBase = absolute(rb.textContent.trim(), repBase);
      var template = childrenNamed(rep0, 'SegmentTemplate')[0] || childrenNamed(pick, 'SegmentTemplate')[0];
      if (!template) {
        // The whole track as one file.
        if (rb || sb) segs.push({ url: repBase, from: start, to: start + (length || 36000), shifts: [start, 0], whole: true });
        continue;
      }
      var scale = parseFloat(template.getAttribute('timescale')) || 1, media = template.getAttribute('media') || '';
      var number = parseInt(template.getAttribute('startNumber') || '1', 10), pto = parseFloat(template.getAttribute('presentationTimeOffset')) || 0;
      var fill = function (n, time) {
        return absolute(media.replace(/\$RepresentationID\$/g, rep0.getAttribute('id') || '')
          .replace(/\$Bandwidth\$/g, rep0.getAttribute('bandwidth') || '')
          .replace(/\$Number(?:%0(\d+)d)?\$/g, function (all, width) { var s = String(n); while (width && s.length < +width) s = '0' + s; return s; })
          .replace(/\$Time\$/g, String(time)).replace(/\$\$/g, '$'), repBase);
      };
      var add = function (n, time, d) {
        var from = start + (time - pto) / scale, to = from + d / scale;
        // The times written inside a piece can be counted from different starting points. Each is tried
        // against where the manifest says the piece belongs: see fits().
        segs.push({ url: fill(n, time), from: from, to: to, shifts: [start - pto / scale, start, 0, from] });
      };
      var timeline = childrenNamed(template, 'SegmentTimeline')[0];
      if (timeline) {
        var time = 0, steps = childrenNamed(timeline, 'S');
        for (var s = 0; s < steps.length && segs.length < 9000; s++) {
          var t = steps[s].getAttribute('t'), d = parseFloat(steps[s].getAttribute('d')), r = parseInt(steps[s].getAttribute('r') || '0', 10);
          if (t !== null) time = parseFloat(t);
          if (r < 0) r = length ? Math.ceil((length * scale + pto - time) / d) - 1 : 0;
          for (var k = 0; k <= r && segs.length < 9000; k++) { add(number++, time, d); time += d; }
        }
      } else {
        var each = parseFloat(template.getAttribute('duration'));
        if (each > 0 && length > 0) {
          for (var n = 0, count = Math.ceil(length * scale / each); n < count && segs.length < 9000; n++) add(number + n, pto + n * each, each);
        }
      }
    }
    if (!segs.length) {
      say('manifest read: ' + (tracks ? 'caption track found but its pieces are not listed' : other ? 'no English caption track (' + other + ' in other languages)' : 'it lists no caption track'));
      return;
    }
    var source = sourceFor(key, 'manifest');
    source.segs = segs;
    say('manifest read: English caption track found, ' + segs.length + ' pieces over ' + Math.round(at / 60) + ' minutes; reading them ahead');
  }

  function readHls(text, address) {
    if (text.indexOf('#EXT-X-STREAM-INF') >= 0 || text.indexOf('TYPE=SUBTITLES') >= 0) {
      // The main list: find the English caption list it points to and fetch that.
      var lines = text.split('\n'), pick = null;
      for (var i = 0; i < lines.length; i++) {
        if (lines[i].indexOf('#EXT-X-MEDIA') !== 0 || !/TYPE=SUBTITLES/.test(lines[i])) continue;
        var lang = (/LANGUAGE="([^"]*)"/.exec(lines[i]) || [])[1], uri = (/URI="([^"]*)"/.exec(lines[i]) || [])[1];
        if (!uri || !english(lang) || /FORCED=YES/.test(lines[i])) continue;
        if (!pick) pick = uri;
      }
      if (!pick || !realFetch) return;
      var list = absolute(pick, address), key = 'hls:' + list.split('?')[0];
      for (i = 0; i < S.sources.length; i++) if (S.sources[i].key === key) return;
      sourceFor(key, 'manifest');
      realFetch.call(window, list).then(function (res) { return res.text(); }).then(function (body) { readHlsList(body, list, key); }, function () {});
      return;
    }
    if (/\.(web)?vtt(\?|\s|$)/im.test(text)) readHlsList(text, address, 'hls:' + address.split('?')[0]);
  }

  function readHlsList(text, address, key) {
    if (text.indexOf('#EXT-X-ENDLIST') < 0) return; // a live broadcast: there is no "ahead" to read
    var lines = text.split('\n'), segs = [], at = 0, length = 0;
    for (var i = 0; i < lines.length && segs.length < 9000; i++) {
      var line = lines[i].trim();
      if (line.indexOf('#EXTINF:') === 0) { length = parseFloat(line.slice(8)) || 0; continue; }
      if (!line || line.charAt(0) === '#') continue;
      segs.push({ url: absolute(line, address), from: at, to: at + length, shifts: [0, at], hls: true });
      at += length;
    }
    if (!segs.length) return;
    var source = sourceFor(key, 'manifest');
    if (source.segs) return;
    source.segs = segs;
    say('caption list read: ' + segs.length + ' pieces over ' + Math.round(at / 60) + ' minutes; reading them ahead');
  }

  // Which of a piece's possible starting points make its first line fall where the piece belongs.
  // Near the start of a film several can, so a piece only settles the question when they agree.
  function fits(seg, cues) {
    var first = cues[0][0] / 1000, slack = (seg.to - seg.from) + 4, out = [];
    for (var i = 0; i < seg.shifts.length; i++) {
      var at = first + seg.shifts[i];
      if (at >= seg.from - slack && at <= seg.to + 4) out.push(i);
    }
    return out;
  }

  function place(source, seg, cues) {
    var shift = Math.round(seg.shifts[Math.min(source.shiftBy, seg.shifts.length - 1)] * 1000);
    if (!source.saidTimes) {
      source.saidTimes = true;
      say('caption times: first line read at ' + Math.round(cues[0][0] / 1000) + 's, placed at ' + Math.round((cues[0][0] + shift) / 1000) +
        's; its piece belongs at ' + Math.round(seg.from) + 's');
    }
    for (var i = 0; i < cues.length; i++) { cues[i][0] += shift; cues[i][1] += shift; }
    addCues(source, cues);
  }

  // Pieces that could not settle how times are counted wait here until one does, or the track ends.
  function placeWaiting(source) {
    if (source.shiftBy === undefined) source.shiftBy = 0;
    var waiting = source.waiting || [];
    source.waiting = [];
    for (var i = 0; i < waiting.length; i++) place(source, waiting[i][0], waiting[i][1]);
  }

  function takePiece(source, seg, bytes) {
    var text = new TextDecoder('utf-8').decode(bytes), cues;
    if (seg.whole) { offerCaptions(text); return; }
    if (/^\uFEFF?\s*WEBVTT/.test(text.slice(0, 40)) || /-->/.test(text.slice(0, 4000))) {
      cues = readVtt(text);
      if (seg.hls) {
        // "X-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000" ties the written times to the video's own clock.
        var map = /X-TIMESTAMP-MAP=([^\n]*)/.exec(text);
        if (map) {
          var ts = /MPEGTS:(\d+)/.exec(map[1]), local = new RegExp('LOCAL:' + TIME).exec(map[1]);
          var lead = (ts ? +ts[1] / 90000 : 0) - (local ? clock(local[1]) / 1000 : 0);
          seg.shifts = [0, lead - 10, lead, seg.from];
        }
      }
    } else {
      cues = readWrapped(bytes, new TextDecoder('latin1').decode(bytes), Math.round((seg.to - seg.from) * 1000));
      if (cues.length && cues[0][3]) seg.shifts = [seg.from];
    }
    if (!cues || !cues.length) return;
    if (source.shiftBy !== undefined) { place(source, seg, cues); return; }
    var good = fits(seg, cues), agreed = good.length > 0;
    for (var i = 1; i < good.length; i++) if (Math.abs(seg.shifts[good[i]] - seg.shifts[good[0]]) > 0.5) agreed = false;
    source.waiting = source.waiting || [];
    source.waiting.push([seg, cues]);
    if (agreed) { source.shiftBy = good[0]; placeWaiting(source); }
    else if (source.waiting.length > 60) placeWaiting(source);
  }

  // Downloads the pieces of a caption track, a few at a time, those nearest the viewer first.
  function readAhead(t) {
    if (!realFetch) return;
    for (var n = 0; n < S.sources.length; n++) {
      var source = S.sources[n];
      if (!source.segs || source.dead || source.finished) continue;
      while (source.busy < 4) {
        var pick = null, i;
        for (i = 0; i < source.segs.length; i++) {
          var seg = source.segs[i];
          if (seg.done) continue;
          if (seg.to >= t / 1000 - 5) { pick = seg; break; }
          if (!pick) pick = seg;
        }
        if (!pick) {
          if (source.busy === 0) {
            source.finished = true;
            placeWaiting(source);
            say('caption track read in full: ' + source.cues.length + ' lines, ' + source.windows.length + ' stretches to mute');
          }
          break;
        }
        fetchPiece(source, pick);
      }
    }
  }

  function fetchPiece(source, seg) {
    seg.done = true;
    source.busy++;
    var settle = function (ok) {
      source.busy--;
      if (ok) { source.fetched++; return; }
      source.failed++;
      if (source.failed >= 6 && source.fetched === 0 && !source.dead) {
        source.dead = true;
        say('caption pieces could not be downloaded; falling back to captions as they appear');
      }
    };
    var get = function (options) {
      return realFetch.call(window, seg.url, options).then(function (res) {
        if (!res.ok) throw new Error('status ' + res.status);
        return res.arrayBuffer();
      });
    };
    get(undefined).catch(function () { return get({ credentials: 'include' }); }).then(function (buffer) {
      try { takePiece(source, seg, new Uint8Array(buffer)); } catch (e) { /* an unreadable piece */ }
      settle(true);
    }, function () { settle(false); });
  }

  // ---- Watching what the page downloads ----

  function interesting(head) {
    if (head.indexOf('<MPD') >= 0) return 'dash';
    if (/^\uFEFF?\s*#EXTM3U/.test(head)) return 'hls';
    return looksLikeCaptions(head) ? 'captions' : '';
  }

  function take(kind, text, address) {
    // A stream's manifest is also passed to the app, so Send to TV can make a clean copy of the stream.
    if ((kind === 'dash' || kind === 'hls') && B.manifest && /^https?:/.test(address)) {
      try { B.manifest(kind, address, text.length > 300000 ? text.slice(0, 300000) : text, location.href); } catch (e) { /* ignore */ }
    }
    try {
      if (kind === 'dash') readDash(text, address);
      else if (kind === 'hls') readHls(text, address);
      else offerCaptions(text);
    } catch (e) { say('could not read a ' + kind + ' download: ' + e.message); }
  }

  function skippable(type) { return /^(video|audio|image|font)\/|javascript|css|text\/html|wasm/.test(type); }

  (function watchDownloads() {
    if (SCOUT || !B.captionCues) return;
    if (realFetch) {
      window.fetch = function () {
        var answer = realFetch.apply(this, arguments);
        try {
          answer.then(function (res) {
            try {
              if (!res.ok || !res.body || skippable(res.headers.get('content-type') || '')) return;
              if ((parseInt(res.headers.get('content-length') || '0', 10) || 0) > 6000000) return;
              // Looks at the first part of a copy of the download, and reads on only if it is a manifest or captions.
              var reader = res.clone().body.getReader(), parts = [], size = 0, kind = null;
              var pump = function () {
                reader.read().then(function (step) {
                  if (step.value) { parts.push(step.value); size += step.value.byteLength; }
                  if (kind === null && (size >= 300 || step.done)) {
                    kind = parts.length ? interesting(new TextDecoder('utf-8').decode(parts[0].subarray(0, 2000))) : '';
                    if (!kind) { reader.cancel().catch(function () {}); return; }
                  }
                  if (size > 6000000) { reader.cancel().catch(function () {}); return; }
                  if (!step.done) { pump(); return; }
                  var all = new Uint8Array(size), at = 0;
                  for (var i = 0; i < parts.length; i++) { all.set(parts[i], at); at += parts[i].byteLength; }
                  take(kind, new TextDecoder('utf-8').decode(all), res.url || '');
                }, function () {});
              };
              pump();
            } catch (e) { /* leave the page's download alone */ }
          }, function () {});
        } catch (e) { /* leave the page's download alone */ }
        return answer;
      };
    }
    var Request = window.XMLHttpRequest;
    if (Request && Request.prototype) {
      var realOpen = Request.prototype.open;
      Request.prototype.open = function (method, url) {
        var xhr = this, address = String(url || '');
        xhr.addEventListener('load', function () {
          try {
            if (skippable(xhr.getResponseHeader('content-type') || '')) return;
            var how = xhr.responseType, text = null;
            if (how === '' || how === 'text') text = xhr.responseText;
            else if (how === 'arraybuffer' && xhr.response && xhr.response.byteLength <= 6000000) {
              if (!interesting(new TextDecoder('utf-8').decode(new Uint8Array(xhr.response, 0, Math.min(2000, xhr.response.byteLength))))) return;
              text = new TextDecoder('utf-8').decode(xhr.response);
            } else if (how === 'json' && xhr.response) text = JSON.stringify(xhr.response);
            else if (how === 'document' && xhr.response) text = new XMLSerializer().serializeToString(xhr.response);
            if (!text || text.length > 6000000) return;
            var kind = interesting(text.slice(0, 2000));
            if (kind) take(kind, text, absolute(xhr.responseURL || address, location.href));
          } catch (e) { /* leave the page's download alone */ }
        });
        return realOpen.apply(this, arguments);
      };
    }
  })();

  // Notes, once per video, when it starts and if it fails, so a video that will not play can be explained.
  var watched = new WeakSet();
  function watchForTrouble(video) {
    if (watched.has(video) || !B.note) return;
    watched.add(video);
    video.addEventListener('playing', function () { say('video playing'); }, { once: true });
    video.addEventListener('error', function () {
      var err = video.error;
      say('video failed: ' + (err ? 'code ' + err.code + ' ' + (err.message || '') : 'unknown reason'));
    });
    // A video meant to start by itself that has not: try once more and note why it was refused.
    setTimeout(function () {
      if (!video.autoplay || !video.paused || video.currentTime > 0) return;
      var p = video.play();
      if (p && p.catch) p.catch(function (e) { say('video did not start by itself: ' + e.name + ' ' + e.message); });
    }, 2500);
  }

  // Moving to another title on the same site: what was read ahead belonged to the last one.
  // (What arrived in the last few seconds is the new title's, fetched as the page changed.)
  function noticeNewPage() {
    var address = location.href.split('#')[0];
    if (address === S.address) return;
    var first = !S.address, now = Date.now();
    S.address = address;
    if (first) return;
    S.sources = S.sources.filter(function (source) { return now - source.made < 20000; });
    S.screen = [];
    S.youtubeDone = false;
    S.youtubeTries = 0;
  }

  function loadConfig() {
    try {
      var c = JSON.parse(B.config());
      S.version = c.version;
      S.language = !!c.language;
      S.showCaptions = c.showCaptions !== false;
      S.strict = !!c.strict;
      S.tags = c.tags || [];
      // Word settings may have changed, so every caption line is checked again from scratch.
      states = new WeakMap();
      blocks = new WeakMap();
      S.screen = [];
      for (var i = 0; i < S.sources.length; i++) S.sources[i].windows = windowsFor(S.sources[i].cues);
      styleCaptions();
    } catch (e) { /* keep the previous settings */ }
  }

  // The words being muted should not be printed on the screen either. Unless the viewer asks for
  // captions, they are kept in the page (the filter reads them) but not shown.
  function styleCaptions() {
    var id = 'safewatch-captions', old = document.getElementById(id), hide = S.language && !S.showCaptions && !SCOUT;
    if (!hide) { if (old) old.remove(); return; }
    if (old || !document.documentElement) return;
    var style = document.createElement('style');
    style.id = id;
    style.textContent = CAPTION_SELECTORS + ', .caption-window, .ytp-caption-window-container, .player-timedtext' +
      '{opacity:0 !important}\nvideo::cue{color:transparent !important;background:transparent !important;text-shadow:none !important;opacity:0 !important}';
    document.documentElement.appendChild(style);
  }

  // YouTube only downloads a video's captions once they are switched on, so they are switched on,
  // automatic ones included. Those carry a time for every word.
  function wakeYouTubeCaptions() {
    if (S.youtubeDone || !/(^|\.)youtube(-nocookie)?\.com$/.test(location.hostname)) return;
    var player = document.getElementById('movie_player');
    if (!player || !player.getOption) return;
    S.youtubeTries = (S.youtubeTries || 0) + 1;
    try {
      if (player.loadModule) player.loadModule('captions');
      var list = player.getOption('captions', 'tracklist', { includeAsr: true }) || [], pick = null;
      for (var i = 0; i < list.length; i++) {
        if (!english(list[i].languageCode)) continue;
        if (!pick || (pick.kind === 'asr' && list[i].kind !== 'asr')) pick = list[i];
      }
      var now = player.getOption('captions', 'track');
      if (now && now.languageCode && english(now.languageCode)) { S.youtubeDone = true; say('YouTube captions are on'); return; }
      if (pick) {
        player.setOption('captions', 'track', pick);
        say('YouTube captions switched on (' + (pick.kind === 'asr' ? 'automatic' : 'written') + ')');
        S.youtubeDone = true;
      } else if (list.length) {
        say('YouTube: this video has captions but none in English');
        S.youtubeDone = true;
      } else if (S.youtubeTries > 10) {
        if (player.toggleSubtitlesOn) player.toggleSubtitlesOn();
        say('YouTube: no caption list offered for this video');
        S.youtubeDone = true;
      }
    } catch (e) { if (S.youtubeTries > 10) { S.youtubeDone = true; say('YouTube captions could not be switched on: ' + e.message); } }
  }

  // Reads the video's caption tracks ahead of time and turns them into mute windows.
  function harvestCues(video, st) {
    var tracks = video.textTracks;
    if (!tracks || !tracks.length) return;
    var usable = [], active = false, i;
    for (i = 0; i < tracks.length; i++) {
      var k = tracks[i].kind;
      if (k !== 'subtitles' && k !== 'captions') continue;
      usable.push(tracks[i]);
      if (tracks[i].mode !== 'disabled') active = true;
    }
    if (!usable.length) return;
    if (!active) {
      // Nothing is switched on: load one track silently, preferring English.
      var pick = usable[0];
      for (i = 0; i < usable.length; i++) {
        if (english(usable[i].language)) { pick = usable[i]; break; }
      }
      pick.mode = 'hidden';
    }
    var rescan = S.ticks % 20 === 0;
    for (i = 0; i < usable.length; i++) {
      var track = usable[i], cues = track.cues;
      if (track.mode === 'disabled' || !cues) continue;
      if (!rescan && st.cueCounts.get(track) === cues.length) continue;
      st.cueCounts.set(track, cues.length);
      var fresh = [];
      for (var j = 0; j < cues.length; j++) {
        var cue = cues[j], text = plain(cue.text || '');
        var key = cue.startTime + '|' + cue.endTime + '|' + text;
        if (st.seen[key] || !text) continue;
        st.seen[key] = true;
        st.starts.push(cue.startTime * 1000);
        fresh.push([Math.round(cue.startTime * 1000), Math.round(cue.endTime * 1000), text]);
      }
      var found = windowsFor(fresh);
      for (var n = 0; n < found.length; n++) st.windows.push(found[n]);
      shareCues(fresh, 0);
      if (fresh.length && !st.saidTrack) { st.saidTrack = true; say('reading the video\'s own caption track'); }
    }
  }

  // ---- Captions drawn on the page ----
  //
  // These only appear as the line is spoken, with no times attached. A line that scrolls in word by
  // word (YouTube's automatic captions) is muted as each flagged word arrives. A line that appears all
  // at once is muted over the part of it where the word should fall, judged by where the word sits in
  // the line and how fast people speak, and no longer than the line stays up.

  var blocks = new WeakMap();
  var changed = [];
  var FAST = 24, SLOW = 9; // letters spoken per second, at the quickest and the slowest

  function spansIn(text) {
    try { return JSON.parse(B.spans(text)); } catch (e) { return []; }
  }

  // Length of the longest ending of `before` that `after` begins with.
  function overlap(before, after) {
    var max = Math.min(before.length, after.length);
    for (var k = max; k > 0; k--) {
      if (before.slice(-k) === after.slice(0, k)) return k;
    }
    return 0;
  }

  function cut(st, t) {
    for (var i = 0; i < st.mine.length; i++) st.mine[i][1] = Math.min(st.mine[i][1], t + 500);
    st.mine = [];
  }

  function readBlock(block, text, t) {
    var st = blocks.get(block);
    if (!st) { st = { text: '', shown: '', goneAt: -1, mine: [] }; blocks.set(block, st); }
    if (!text) {
      // The line has gone. Players sometimes redraw a line, so it is given a moment to come back
      // before what was planned for it is cut short.
      if (st.text) { st.text = ''; st.goneAt = t; }
      else if (st.goneAt >= 0 && Math.abs(t - st.goneAt) > 400) { cut(st, st.goneAt); st.goneAt = -1; st.shown = ''; }
      return;
    }
    if (text === st.text) return;
    S.screenSeenAt = Date.now();
    var before = st.text || (st.goneAt >= 0 ? st.shown : '');
    st.text = text;
    st.goneAt = -1;
    if (text === st.shown) return; // the same line, redrawn
    st.shown = text;
    var kept = before ? (text.indexOf(before) === 0 ? before.length : overlap(before, text)) : 0;
    var rolling = kept >= Math.min(8, before.length) && kept > 0;
    if (!rolling) {
      cut(st, t);
      kept = 0;
      calibrate(text, t);
    }
    var spans = spansIn(text);
    for (var i = 0; i < spans.length; i++) {
      if (spans[i][1] <= kept) continue; // already dealt with when it first appeared
      var from, to;
      if (rolling) {
        from = t - 250;
        to = t + 1100;
      } else {
        from = t + Math.max(0, spans[i][0] / FAST * 1000 - 600);
        to = t + Math.min(spans[i][1] / SLOW * 1000 + 900, 7000);
      }
      var stretch = [from, to];
      st.mine.push(stretch);
      S.screen.push(stretch);
      if (S.screen.length > 400) S.screen.shift();
    }
  }

  // A line on screen that also appears in the caption data shows whether that data's times are right.
  // Several lines agreeing on the same difference move the data to match.
  function calibrate(text, t) {
    var words = squash(text);
    if (words.length < 12) return;
    for (var n = 0; n < S.sources.length; n++) {
      var source = S.sources[n], at = source.index[words];
      if (at === undefined || at < 0) continue;
      source.deltas.push(t - (at + source.offset));
      if (source.deltas.length > 5) source.deltas.shift();
      if (source.deltas.length < 3) continue;
      var sorted = source.deltas.slice().sort(function (a, b) { return a - b; }), middle = sorted[sorted.length >> 1];
      if (sorted[sorted.length - 1] - sorted[0] > 800) continue; // they do not agree
      if (Math.abs(middle) > 600) {
        source.offset += middle;
        source.deltas = [];
        say('caption times moved by ' + Math.round(middle) + ' ms to match the screen');
      } else if (!source.checked || !source.saidChecked) {
        source.saidChecked = true;
        say('caption times checked against the screen: within ' + Math.round(Math.abs(middle)) + ' ms');
      }
      source.checked = true;
    }
  }

  // Text that changes on top of the video, on players this script has no name for, is very likely captions.
  var observer = null;
  function watchPageText() {
    if (observer || SCOUT || !window.MutationObserver || !document.documentElement) return;
    observer = new MutationObserver(function (records) {
      for (var i = 0; i < records.length && changed.length < 40; i++) {
        var node = records[i].target;
        if (node.nodeType === 3) node = node.parentElement;
        if (node && node.nodeType === 1 && changed.indexOf(node) < 0) changed.push(node);
      }
    });
    observer.observe(document.documentElement, { subtree: true, childList: true, characterData: true });
  }

  function overVideo(el, box) {
    var r = el.getBoundingClientRect();
    if (!r.width || !r.height) return false;
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    return x >= box.left && x <= box.right && y >= box.top && y <= box.bottom && r.height < box.height * 0.45;
  }

  function checkScreenCaptions(video, t) {
    var els = document.querySelectorAll(CAPTION_SELECTORS), named = '', i;
    for (i = 0; i < els.length; i++) named += ' ' + (els[i].textContent || '');
    named = named.replace(/\s+/g, ' ').trim();
    readBlock(document.documentElement, named, t);

    var list = changed;
    changed = [];
    if (named || !list.length) return;
    var box = video.getBoundingClientRect();
    for (i = 0; i < list.length; i++) {
      var el = list[i];
      if (!el.isConnected || el.closest('button, a, input, select, textarea, nav, [role=button], [role=slider], [role=menu], [role=dialog]')) continue;
      // The whole caption, not one word of it: the largest piece of the page around it that is still a line or two of text.
      while (el.parentElement && el.parentElement !== document.body && el.parentElement !== document.documentElement &&
             (el.parentElement.textContent || '').length <= 220 && !el.parentElement.querySelector('video, button, input')) el = el.parentElement;
      var text = (el.textContent || '').replace(/\s+/g, ' ').trim();
      if (text.length > 220 || (text && !overVideo(el, box))) continue;
      readBlock(el, text, t);
    }
  }

  function setMuted(video, st, mute, why) {
    // While the video is being handed to the TV, the phone plays it silently.
    if (S.quiet) { if (!video.muted) video.muted = true; return; }
    if (mute) {
      if (!st.mutedByUs) {
        st.wasMuted = video.muted; st.mutedByUs = true; st.mutedAt = Date.now();
        say('muted at ' + (Math.round(video.currentTime * 10) / 10) + 's (' + why + ')');
      }
      if (!video.muted) video.muted = true;
    } else if (st.mutedByUs) {
      st.mutedByUs = false;
      video.muted = st.wasMuted;
      say('sound back after ' + (Date.now() - st.mutedAt) + ' ms');
    }
  }

  function setBlurred(video, st, blur) {
    if (blur && !st.blurredByUs) {
      st.oldFilter = video.style.getPropertyValue('filter');
      video.style.setProperty('filter', 'blur(40px)', 'important');
      st.blurredByUs = true;
    } else if (!blur && st.blurredByUs) {
      if (st.oldFilter) video.style.setProperty('filter', st.oldFilter);
      else video.style.removeProperty('filter');
      st.blurredByUs = false;
    }
  }

  // Netflix's player stops with an error if its video is moved directly, so on Netflix
  // skipping goes through the player's own controls. Everywhere else the video is moved itself.
  function seekTo(video, seconds) {
    try {
      if (/(^|\.)netflix\.com$/.test(location.hostname) && window.netflix) {
        var players = window.netflix.appContext.state.playerApp.getAPI().videoPlayer;
        var ids = players.getAllPlayerSessionIds();
        if (ids.length) { players.getVideoPlayerBySessionId(ids[0]).seek(Math.round(seconds * 1000)); return; }
      }
    } catch (e) { /* fall through to the ordinary way */ }
    video.currentTime = seconds;
  }

  // The box around a video that also holds its controls and captions: the furthest
  // ancestor that is still the same size as the video.
  function playerBox(video) {
    var box = video, w = video.clientWidth, h = video.clientHeight;
    for (var el = video.parentElement; el && el !== document.body && el !== document.documentElement; el = el.parentElement) {
      if (Math.abs(el.clientWidth - w) > w * 0.06 || Math.abs(el.clientHeight - h) > h * 0.06) break;
      box = el;
    }
    return box;
  }

  // Asks the page to show its player full screen. Browsers only allow this just after
  // the viewer has touched the page, so it is tried now and then and simply does
  // nothing when it is not allowed.
  function fillScreen(video) {
    var now = Date.now();
    if (document.fullscreenElement || now - S.filledAt < 2500) return;
    S.filledAt = now;
    if (video.clientWidth >= window.innerWidth * 0.94 && video.clientHeight >= window.innerHeight * 0.94) return;
    try {
      var box = playerBox(video);
      var asked = box.requestFullscreen ? box.requestFullscreen() : null;
      if (asked && asked.catch) asked.catch(function () {});
    } catch (e) { /* not allowed right now */ }
  }

  // Carries out a press on the app's own player controls.
  function obey(video, command) {
    if (command === 'play') {
      // Watching here again after handing the video to the TV: the sound is as it was before.
      if (S.quiet) { S.quiet = false; video.muted = !!S.quietWas; }
      var p = video.play(); if (p && p.catch) p.catch(function () {});
    }
    else if (command === 'pause') video.pause();
    else if (command.indexOf('seek:') === 0) seekTo(video, parseFloat(command.slice(5)));
    else if (command.indexOf('skip:') === 0) seekTo(video, Math.max(0, video.currentTime + parseFloat(command.slice(5))));
    else if (command.indexOf('rate:') === 0) S.rate = parseFloat(command.slice(5)) || 1;
    else if (command === 'quiet') { if (!S.quiet) S.quietWas = video.muted; S.quiet = true; video.muted = true; }
  }

  // ---- The hidden copy that looks ahead ----

  var FILL = [['position', 'fixed'], ['top', '0'], ['left', '0'], ['width', '100vw'], ['height', '100vh'],
    ['max-width', 'none'], ['max-height', 'none'], ['margin', '0'], ['transform', 'none'], ['z-index', '2147483647'],
    ['border', '0'], ['object-fit', 'contain'], ['background', '#000'], ['opacity', '1'], ['visibility', 'visible']];

  // Stretches an element over its whole frame, and asks the frame around it to do the same.
  function fill(el) {
    for (var i = 0; i < FILL.length; i++) el.style.setProperty(FILL[i][0], FILL[i][1], 'important');
    if (window.parent !== window) {
      try { window.parent.postMessage({ safewatchFill: 1 }, '*'); } catch (e) { /* ignore */ }
    }
  }

  if (SCOUT) {
    // No sound from this copy, ever: every video is silenced the moment it starts.
    document.addEventListener('play', function (e) { if (e.target && 'muted' in e.target) e.target.muted = true; }, true);
    window.addEventListener('message', function (e) {
      if (!e.data || e.data.safewatchFill !== 1) return;
      var frames = document.querySelectorAll('iframe');
      for (var i = 0; i < frames.length; i++) if (frames[i].contentWindow === e.source) fill(frames[i]);
    });
  }

  function scoutTick(videos) {
    var wanted = 0, target = null, biggest = null, biggestArea = -1, i;
    try { wanted = B.wanted(); } catch (e) { /* not known yet */ }
    for (i = 0; i < videos.length; i++) {
      var v = videos[i];
      if (!v.muted) v.muted = true;
      var area = v.clientWidth * v.clientHeight;
      if (area > biggestArea) { biggestArea = area; biggest = v; }
      // The video the viewer is watching is picked out by its length.
      if (!target && wanted > 0 && isFinite(v.duration) && Math.abs(v.duration * 1000 - wanted) < 2500) target = v;
    }
    if (!target) {
      // Not found yet. Starting the largest video gets a player to load it, or to get through what comes first.
      target = biggest;
      if (target && target.paused && S.ticks % 20 === 0) { var p = target.play(); if (p && p.catch) p.catch(function () {}); }
    }
    if (!target) return;
    stateOf(target);
    if (S.ticks % 10 === 1) fill(target);
    if (S.rate && target.playbackRate !== S.rate) { try { target.playbackRate = S.rate; } catch (e) { /* keep its own speed */ } }
    try {
      B.steady(!target.seeking && target.readyState >= 3);
      B.state(target.currentTime * 1000, isFinite(target.duration) ? target.duration * 1000 : 0, target.paused,
        !!document.querySelector('.ad-showing, .ad-interrupting'));
      var command = B.command();
      if (command) obey(target, command);
    } catch (e) { /* ignore */ }
  }

  // Why the sound should be off at this moment of this video, or '' when it should be on.
  function muteReason(video, st, t, advert, live) {
    var n, k, source, at;
    for (n = 0; n < st.windows.length; n++) if (t >= st.windows[n][0] && t < st.windows[n][1]) return 'caption track';
    for (n = 0; n < S.sources.length; n++) {
      source = S.sources[n];
      if (!applies(source, video, advert)) continue;
      at = t - source.offset;
      for (k = 0; k < source.windows.length; k++) if (at >= source.windows[k][0] && at < source.windows[k][1]) return 'captions read ahead';
    }
    if (live) for (n = 0; n < S.screen.length; n++) if (t >= S.screen[n][0] && t < S.screen[n][1]) return 'captions on screen';
    return '';
  }

  // Tells the app, for the video being watched, what the language filter has to go on.
  function report(video, st, advert) {
    var now = Date.now(), lines = st.starts.length, kind = 'none', n;
    for (n = 0; n < S.sources.length; n++) if (applies(S.sources[n], video, advert)) lines += S.sources[n].cues.length;
    if (lines > 0) kind = 'ahead';
    else if (now - S.screenSeenAt < 300000) kind = 'screen';
    S.covered = kind !== 'none';
    if (!B.captionState) return;
    var state = kind + ':' + (lines > 0 ? Math.ceil(lines / 50) : 0);
    if (state === S.lastState && now - S.lastStateAt < 5000) return;
    S.lastState = state;
    S.lastStateAt = now;
    try { B.captionState(kind, lines); } catch (e) { /* ignore */ }
  }

  function tick() {
    S.ticks++;
    try { if (B.version() !== S.version) loadConfig(); } catch (e) { return; }
    if (!SCOUT) noticeNewPage();
    var videos = document.querySelectorAll('video');
    if (!videos.length) return;
    if (SCOUT) { scoutTick(videos); return; }
    if (S.ticks % 20 === 1) { styleCaptions(); watchPageText(); if (S.language) wakeYouTubeCaptions(); }
    var main = null, mainArea = -1, biggest = null, biggestArea = -1, i, video, st, area;

    for (i = 0; i < videos.length; i++) {
      video = videos[i];
      area = video.clientWidth * video.clientHeight;
      if (area > biggestArea) { biggestArea = area; biggest = video; }
      if (!video.paused && !video.ended && area > mainArea) { mainArea = area; main = video; }
    }
    var watching = main || biggest, watchingAt = watching.currentTime * 1000;
    var advert = !!document.querySelector('.ad-showing, .ad-interrupting');
    if (S.language) {
      for (i = 0; i < videos.length; i++) harvestCues(videos[i], stateOf(videos[i]));
      readAhead(watchingAt);
      // Captions drawn on the page are read for the video being watched.
      checkScreenCaptions(watching, watchingAt);
    }

    for (i = 0; i < videos.length; i++) {
      video = videos[i];
      st = stateOf(video);
      var t = video.currentTime * 1000, why = '', blur = false, skipTo = -1, n;

      for (n = 0; n < S.tags.length; n++) {
        var tag = S.tags[n];
        if (t < tag.s || t >= tag.e) continue;
        if (tag.a === 'skip') skipTo = Math.max(skipTo, tag.e);
        else if (tag.a === 'blur') blur = true;
        else why = 'saved scene';
      }
      // Captions on screen are acted on until better data has proved itself for this part of the video.
      if (!why && S.language) why = muteReason(video, st, t, advert, video === watching && !trustedHere(video, st, t, advert));
      if (skipTo >= 0) {
        var target = skipTo / 1000;
        if (isFinite(video.duration)) target = Math.min(target, video.duration);
        if (target > video.currentTime) seekTo(video, target);
        why = 'skipping'; // stay silent until the jump lands
      }
      if (video === main && S.language) {
        report(video, st, advert);
        // The strict choice: with nothing to go on, the video being watched plays without sound.
        if (!S.playingSince) S.playingSince = Date.now();
        if (!why && S.strict && !S.covered && Date.now() - S.playingSince > 8000 && !(video.muted && !st.mutedByUs)) why = 'no captions found';
      }
      setMuted(video, st, !!why, why);
      setBlurred(video, st, blur);
    }
    if (!main) S.playingSince = 0;

    // On the TV, YouTube is asked for its best picture, once for each video.
    if (B.bestQuality && /(^|\.)youtube\.com$/.test(location.hostname)) {
      try {
        var yt = document.getElementById('movie_player');
        var vid = yt && yt.getVideoData ? (yt.getVideoData() || {}).video_id : '';
        if (vid && S.bestFor !== vid && B.bestQuality()) {
          var levels = yt.getAvailableQualityLevels ? yt.getAvailableQualityLevels() : [];
          if (levels && levels.length && levels[0] !== 'auto') {
            if (yt.setPlaybackQualityRange) yt.setPlaybackQualityRange(levels[0], levels[0]);
            if (yt.setPlaybackQuality) yt.setPlaybackQuality(levels[0]);
            S.bestFor = vid;
            say('YouTube on the TV at ' + levels[0]);
          }
        }
      } catch (e) { /* the page may not offer it */ }
    }

    // Tells the app which video is being watched, so Send to TV can offer a clean copy of a whole video file.
    if (B.source) {
      var src = watching.currentSrc || watching.src || '';
      if (src !== S.reportedSrc) {
        S.reportedSrc = src;
        var tracks = [], els = watching.querySelectorAll('track[src]');
        for (i = 0; i < els.length; i++) if (!els[i].srclang || english(els[i].srclang)) tracks.push(els[i].src);
        try { B.source(src, JSON.stringify(tracks), location.href); } catch (e) { /* ignore */ }
      }
    }

    // Tells the app a video is playing and where it is, for scene marking and live detection.
    // The app's player controls work on the playing video, or the largest one when nothing is playing.
    if (B.state) {
      try {
        B.state(watching.currentTime * 1000, isFinite(watching.duration) ? watching.duration * 1000 : 0, watching.paused, advert);
        var command = B.command();
        if (command) obey(watching, command);
      } catch (e) { /* ignore */ }
    }
    if (main) {
      try {
        // A video the page itself keeps silent (a preview behind a title's page) is not the feature.
        var silent = main.muted && !stateOf(main).mutedByUs;
        B.beat(main.currentTime * 1000, silent ? 0 : main.clientWidth / Math.max(1, window.innerWidth));
        if (B.fullPicture && B.fullPicture()) fillScreen(main);
      } catch (e) { /* ignore */ }
    }
  }

  setInterval(tick, TICK_MS);
})();
