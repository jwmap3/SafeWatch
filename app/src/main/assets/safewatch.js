// SafeWatch page script. Runs inside every page (and every frame) the built-in
// browser opens. It never decides what is objectionable itself: it asks the app
// through SafeWatchBridge, then mutes, skips or blurs the page's own <video>.
(function () {
  'use strict';
  if (window.__safewatch) return;
  var B = window.SafeWatchBridge;
  if (!B) return;

  // Where well-known players draw their captions when they do not use text tracks.
  var CAPTION_SELECTORS = '.ytp-caption-segment, .captions-text, .player-timedtext, ' +
    '.vjs-text-track-cue, .jw-text-track-cue, .shaka-text-container span, .plyr__caption';
  var TICK_MS = 100;
  var DOM_MUTE_MS = 1500;

  var S = window.__safewatch = {
    version: -1, language: false, tags: [], domMuteUntil: 0, lastDomText: '', ticks: 0
  };
  var states = new WeakMap();

  function stateOf(video) {
    var st = states.get(video);
    if (!st) {
      st = { windows: [], seen: {}, cueCounts: new WeakMap(), mutedByUs: false, wasMuted: false,
             blurredByUs: false, oldFilter: '' };
      states.set(video, st);
    }
    return st;
  }

  function loadConfig() {
    try {
      var c = JSON.parse(B.config());
      S.version = c.version;
      S.language = !!c.language;
      S.tags = c.tags || [];
      // Word settings may have changed, so captions are checked again from scratch.
      states = new WeakMap();
      S.lastDomText = '';
    } catch (e) { /* keep the previous settings */ }
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
        if ((usable[i].language || '').toLowerCase().indexOf('en') === 0) { pick = usable[i]; break; }
      }
      pick.mode = 'hidden';
    }
    var rescan = S.ticks % 20 === 0;
    for (i = 0; i < usable.length; i++) {
      var track = usable[i], cues = track.cues;
      if (track.mode === 'disabled' || !cues) continue;
      if (!rescan && st.cueCounts.get(track) === cues.length) continue;
      st.cueCounts.set(track, cues.length);
      for (var j = 0; j < cues.length; j++) {
        var cue = cues[j], text = cue.text || '';
        var key = cue.startTime + '|' + cue.endTime + '|' + text;
        if (st.seen[key]) continue;
        st.seen[key] = true;
        try {
          var w = JSON.parse(B.muteWindows(cue.startTime * 1000, cue.endTime * 1000, text));
          for (var n = 0; n < w.length; n++) st.windows.push(w[n]);
        } catch (e) { /* skip this cue */ }
      }
    }
  }

  // Length of the longest ending of `before` that `after` begins with.
  function overlap(before, after) {
    var max = Math.min(before.length, after.length);
    for (var k = max; k > 0; k--) {
      if (before.slice(-k) === after.slice(0, k)) return k;
    }
    return 0;
  }

  // Captions drawn into the page only appear as the words are spoken, so this
  // path reacts a moment late. It checks just the newly added words.
  function checkDomCaptions() {
    var els = document.querySelectorAll(CAPTION_SELECTORS);
    if (!els.length) { S.lastDomText = ''; return; }
    var text = '';
    for (var i = 0; i < els.length; i++) text += ' ' + (els[i].textContent || '');
    text = text.replace(/\s+/g, ' ').trim();
    if (text === S.lastDomText) return;
    var k = overlap(S.lastDomText, text);
    S.lastDomText = text;
    var fresh = text.slice(Math.max(0, k - 12));
    if (!fresh) return;
    try {
      if (B.profane(fresh)) S.domMuteUntil = Date.now() + DOM_MUTE_MS;
    } catch (e) { /* ignore */ }
  }

  function setMuted(video, st, mute) {
    if (mute) {
      if (!st.mutedByUs) { st.wasMuted = video.muted; st.mutedByUs = true; }
      if (!video.muted) video.muted = true;
    } else if (st.mutedByUs) {
      st.mutedByUs = false;
      video.muted = st.wasMuted;
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

  function tick() {
    S.ticks++;
    try { if (B.version() !== S.version) loadConfig(); } catch (e) { return; }
    var videos = document.querySelectorAll('video');
    if (!videos.length) return;
    if (S.language) checkDomCaptions();
    var now = Date.now(), main = null, mainArea = -1;

    for (var i = 0; i < videos.length; i++) {
      var video = videos[i], st = stateOf(video);
      if (S.language) harvestCues(video, st);
      var t = video.currentTime * 1000;
      var mute = S.language && now < S.domMuteUntil, blur = false, skipTo = -1, n;

      for (n = 0; n < S.tags.length; n++) {
        var tag = S.tags[n];
        if (t < tag.s || t >= tag.e) continue;
        if (tag.a === 'skip') skipTo = Math.max(skipTo, tag.e);
        else if (tag.a === 'blur') blur = true;
        else mute = true;
      }
      if (!mute) {
        for (n = 0; n < st.windows.length; n++) {
          if (t >= st.windows[n][0] && t < st.windows[n][1]) { mute = true; break; }
        }
      }
      if (skipTo >= 0) {
        var target = skipTo / 1000;
        if (isFinite(video.duration)) target = Math.min(target, video.duration);
        if (target > video.currentTime) video.currentTime = target;
        mute = true; // stay silent until the jump lands
      }
      setMuted(video, st, mute);
      setBlurred(video, st, blur);

      if (!video.paused && !video.ended) {
        var area = video.clientWidth * video.clientHeight;
        if (area > mainArea) { mainArea = area; main = video; }
      }
    }
    // Tells the app a video is playing and where it is, for scene marking and live detection.
    if (main) { try { B.beat(main.currentTime * 1000); } catch (e) { /* ignore */ } }
  }

  setInterval(tick, TICK_MS);
})();
