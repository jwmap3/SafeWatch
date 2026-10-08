// Runs inside the hidden copy of YouTube's website (see YouTubeMirror). Hands the app what
// the website is given to draw, and keeps any video on the page silent and still.
(function () {
  'use strict';
  if (window.__mirror) return;
  window.__mirror = true;
  var M = window.MirrorBridge;
  if (!M) return;

  function send(kind, text) { try { M.answer(kind, text); } catch (e) { /* the app has gone */ } }
  function kindOf(url) { var m = /\/youtubei\/v1\/(browse|next|search)\b/.exec(url || ''); return m ? m[1] : ''; }

  // What the page asks YouTube for as it goes: more of a list, the comments, the next search page.
  var realFetch = window.fetch;
  if (realFetch) {
    window.fetch = function (input) {
      var answer = realFetch.apply(this, arguments);
      try {
        var kind = kindOf(typeof input === 'string' ? input : input && input.url);
        if (kind) answer.then(function (res) { res.clone().text().then(function (t) { send(kind, t); }, function () {}); }, function () {});
      } catch (e) { /* leave the page's request alone */ }
      return answer;
    };
  }
  var realOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    var xhr = this, kind = kindOf(String(url));
    if (kind) xhr.addEventListener('load', function () { try { send(kind, xhr.responseText); } catch (e) { /* not text */ } });
    return realOpen.apply(this, arguments);
  };

  // What the page was given when it loaded.
  var sent = false;
  setInterval(function () {
    if (!sent && window.ytInitialData) {
      sent = true;
      try { send('initial', JSON.stringify(window.ytInitialData)); } catch (e) { /* too odd to copy */ }
    }
    var videos = document.querySelectorAll('video');
    for (var i = 0; i < videos.length; i++) { videos[i].muted = true; if (!videos[i].paused) videos[i].pause(); }
  }, 300);

  window.__mirrorMore = function () { window.scrollTo(0, document.documentElement.scrollHeight); };
  window.__mirrorComments = function () {
    var box = document.querySelector('ytd-comments#comments, ytd-comments, #comments');
    if (box) box.scrollIntoView();
    window.scrollBy(0, 300);
  };
})();
