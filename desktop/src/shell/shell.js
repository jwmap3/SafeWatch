'use strict';
// edenOS's own screens: the bar along the top, Home, and Settings. The web page itself is shown by the app under the bar.
(function () {
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const LEVELS = ['OFF', 'LOW', 'MEDIUM', 'HIGH'];
  const LEVEL_NAMES = ['Off', 'Low', 'Medium', 'High'];

  let settings = {};
  let state = {};
  let mode = 'home';
  let section = 'language';
  let unlocked = false;

  if (eden.platform === 'darwin') document.body.classList.add('mac');

  // ---- Small pieces ----

  function toast(text) {
    const t = $('toast');
    t.textContent = text;
    t.classList.add('on');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => t.classList.remove('on'), 2600);
  }

  function seg(labels, selected, onPick) {
    const box = el('div', 'seg');
    labels.forEach((label, i) => {
      const b = el('button', i === selected ? 'on' : '', label);
      b.onclick = () => { [...box.children].forEach((c) => c.classList.remove('on')); b.classList.add('on'); onPick(i); };
      box.appendChild(b);
    });
    return box;
  }

  function toggle(on, onChange) {
    const b = el('button', 'switch' + (on ? ' on' : ''));
    b.setAttribute('role', 'switch');
    b.onclick = (e) => { e.stopPropagation(); b.classList.toggle('on'); onChange(b.classList.contains('on')); };
    return b;
  }

  function line(name, right, sub, onClick) {
    const row = el('div', 'line' + (onClick ? ' click' : ''));
    const n = el('div', 'name', name);
    if (sub) n.appendChild(el('small', '', sub));
    row.appendChild(n);
    if (right) row.appendChild(typeof right === 'string' ? el('span', 'value', right) : right);
    if (onClick) row.onclick = onClick;
    return row;
  }

  function switchLine(name, on, onChange, sub) {
    const sw = toggle(on, onChange);
    const row = line(name, sw, sub);
    row.classList.add('click');
    row.onclick = () => sw.click();
    return row;
  }

  function card(...rows) { const c = el('div', 'card'); rows.filter(Boolean).forEach((r) => c.appendChild(r)); return c; }

  async function save(patch) { settings = await eden.set(patch); }

  const ICONS = {
    mute: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z"/><path d="M15.5 9.5l5 5m0-5l-5 5"/>',
    eye: '<path d="M2.5 12C5 7.5 8.5 5.5 12 5.5s7 2 9.5 6.5c-2.5 4.5-6 6.5-9.5 6.5S5 16.5 2.5 12z"/><circle cx="12" cy="12" r="3"/><path d="M4 20L20 4"/>',
    globe: '<circle cx="12" cy="12" r="8"/><path d="M4 12h16M12 4c-3 3.2-3 12.8 0 16M12 4c3 3.2 3 12.8 0 16"/>',
    play: '<path d="M8 5.5L19 12 8 18.5z"/>',
    lock: '<rect x="4.5" y="10.5" width="15" height="10" rx="1.5"/><path d="M8 10.5v-3a4 4 0 0 1 8 0v3"/>',
    list: '<path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01"/>',
    info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5M12 8h.01"/>',
  };
  const svg = (name) => '<svg viewBox="0 0 24 24">' + ICONS[name] + '</svg>';

  // ---- The bar ----

  function show(next) {
    mode = next;
    $('home').classList.toggle('on', mode === 'home');
    $('settings').classList.toggle('on', mode === 'settings');
    if (mode === 'home') { eden.home(); renderHome(); }
    if (mode === 'settings') { eden.openSettings(); renderSettings(); }
    if (mode === 'browse') eden.browse();
  }

  function paintBar() {
    $('back').disabled = !state.canBack && mode === 'browse';
    $('forward').disabled = !state.canForward;
    const address = $('address');
    if (document.activeElement !== address) address.value = mode === 'browse' ? prettyUrl(state.url || '') : '';
    address.placeholder = mode === 'settings' ? 'Settings' : 'Search or type a web address';
    const progress = $('progress');
    if (state.loading && mode === 'browse') { progress.className = 'progress on'; }
    else if (progress.classList.contains('on')) { progress.className = 'progress done'; }
    $('reloadIcon').innerHTML = state.loading ? '<path d="M6 6l12 12M18 6L6 18"/>' : '<path d="M19 12a7 7 0 1 1-2.05-4.95M19 4v4h-4"/>';
    // What the filter is doing right now.
    const chip = $('chip');
    let text = '', kind = '';
    if (mode === 'browse' && state.url) {
      if (state.hidden) { text = 'Hidden'; kind = 'hide'; }
      else if (state.playing && state.language && state.captions === 'ahead') { text = 'Muting ahead'; kind = 'good'; }
      else if (state.playing && state.language && state.captions === 'screen') { text = 'Muting as spoken'; kind = 'good'; }
      else if (state.playing && state.language && state.captions === 'none') { text = 'No captions'; kind = 'warn'; }
      else if (state.playing) { text = 'Filtering'; kind = 'good'; }
    }
    chip.className = 'chip' + (text ? ' on ' + kind : '');
    chip.title = kind === 'warn' ? 'This video has no captions, so cursing cannot be found. Turn captions on in the player if it has them.' : '';
    chip.querySelector('span').textContent = text;
  }

  function prettyUrl(url) {
    try {
      const u = new URL(url);
      return (u.hostname.replace(/^www\./, '') + (u.pathname === '/' ? '' : u.pathname) + u.search).slice(0, 300);
    } catch (e) { return url; }
  }

  $('toHome').onclick = () => show('home');
  $('toSettings').onclick = () => show(mode === 'settings' ? (state.url ? 'browse' : 'home') : 'settings');
  $('back').onclick = () => { if (mode !== 'browse' && state.url) show('browse'); else eden.back(); };
  $('forward').onclick = () => { if (mode !== 'browse') show('browse'); eden.forward(); };
  $('reload').onclick = () => eden.reload();
  $('address').onfocus = () => { const a = $('address'); if (mode === 'browse' && state.url) a.value = state.url; setTimeout(() => a.select(), 0); };
  $('address').onblur = paintBar;
  $('addressForm').onsubmit = (e) => { e.preventDefault(); go($('address').value); $('address').blur(); };
  $('searchForm').onsubmit = (e) => { e.preventDefault(); go($('search').value); };

  function go(text) {
    if (!text.trim()) return;
    mode = 'browse';
    $('home').classList.remove('on');
    $('settings').classList.remove('on');
    eden.navigate(text);
  }

  eden.on('page-state', (s) => {
    state = s;
    if (s.mode && s.mode !== mode) {
      mode = s.mode;
      $('home').classList.toggle('on', mode === 'home');
      $('settings').classList.toggle('on', mode === 'settings');
    }
    paintBar();
  });
  eden.on('settings', (s) => { settings = s; });
  eden.on('model', () => { if (mode === 'settings' && section === 'nudity') renderSettings(); });
  eden.on('key', (what) => {
    if (what === 'home') show('home');
    if (what === 'settings') show('settings');
    if (what === 'address') { if (mode === 'home') $('search').focus(); else $('address').focus(); }
  });
  window.addEventListener('resize', () => document.body.classList.toggle('fullscreen', window.innerHeight >= screen.height - 2));

  // ---- Home ----

  async function renderHome() {
    const services = await eden.services();
    const apps = $('apps');
    apps.textContent = '';
    const chosen = services.filter((s) => (settings.services || []).includes(s.id));
    const all = [...chosen.map((s) => ({ name: s.name, url: s.home, host: new URL(s.home).hostname })),
      { name: 'YouTube', url: 'https://www.youtube.com/', host: 'www.youtube.com' },
      { name: 'Browser', url: 'https://www.google.com/', glyph: 'globe', color: '#2e5bba' }];
    all.forEach((a, i) => {
      const b = el('button', 'app');
      b.style.animationDelay = (60 + i * 45) + 'ms';
      const tile = el('div', 'tile');
      if (a.glyph) { tile.style.background = a.color; tile.innerHTML = svg(a.glyph); }
      else {
        tile.appendChild(el('b', '', a.name[0]));
        const img = el('img');
        tile.appendChild(img);
        eden.icon(a.host).then((src) => {
          if (!src) return;
          img.onload = () => {
            // A full square picture fills the tile; a small one sits in the middle of its own colour.
            const full = img.naturalWidth >= 120;
            if (!full) img.classList.add('small');
            img.classList.add('on');
            tile.querySelector('b').style.opacity = 0;
            tile.style.background = edgeColour(img) || 'var(--surface-2)';
          };
          img.src = src;
        });
      }
      b.appendChild(tile);
      b.appendChild(el('span', '', a.name));
      b.onclick = () => go(a.url);
      apps.appendChild(b);
    });
    const links = $('links');
    links.textContent = '';
    for (const [name, url] of settings.quickLinks || []) {
      const b = el('button', 'link');
      const img = el('img');
      img.alt = '';
      b.appendChild(img);
      b.appendChild(el('span', '', name));
      try { eden.icon(new URL(url).hostname).then((src) => { if (src) img.src = src; else img.style.visibility = 'hidden'; }); } catch (e) { /* a bad link */ }
      b.onclick = () => go(url);
      links.appendChild(b);
    }
    $('foot').textContent = footText();
  }

  function footText() {
    const parts = [];
    if (settings.language !== 'OFF') parts.push('Cursing muted');
    if (settings.nudity !== 'OFF') parts.push('nudity ' + (settings.nudityAction === 'SKIP' ? 'skipped' : 'hidden'));
    if (settings.blockAdult) parts.push('adult sites blocked');
    return parts.length ? parts.join(' · ') : 'Filters are off';
  }

  // The colour at an icon's edges, to fill its tile around it.
  function edgeColour(img) {
    try {
      const c = document.createElement('canvas');
      c.width = c.height = 16;
      const x = c.getContext('2d');
      x.drawImage(img, 0, 0, 16, 16);
      const d = x.getImageData(0, 0, 16, 16).data;
      let r = 0, g = 0, b = 0, n = 0, clear = 0;
      for (const [px, py] of [[0, 0], [15, 0], [0, 15], [15, 15], [8, 0], [8, 15], [0, 8], [15, 8]]) {
        const i = (py * 16 + px) * 4;
        if (d[i + 3] < 200) { clear++; continue; }
        r += d[i]; g += d[i + 1]; b += d[i + 2]; n++;
      }
      if (clear > 2 || !n) return '';
      return 'rgb(' + Math.round(r / n) + ',' + Math.round(g / n) + ',' + Math.round(b / n) + ')';
    } catch (e) { return ''; }
  }

  // ---- Settings ----

  const SECTIONS = [
    ['language', 'Language', 'mute', '#3d7df0'],
    ['nudity', 'Nudity', 'eye', '#8a5cf0'],
    ['browser', 'Browser', 'globe', '#2e8fd6'],
    ['services', 'Services', 'play', '#e0524a'],
    ['lock', 'PIN lock', 'lock', '#4b5563'],
    ['report', 'Filter report', 'list', '#1fa36b'],
    ['about', 'About', 'info', '#6b7280'],
  ];

  function summary(id) {
    if (id === 'language') return LEVEL_NAMES[LEVELS.indexOf(settings.language)];
    if (id === 'nudity') return settings.nudity === 'OFF' ? 'Off' : LEVEL_NAMES[LEVELS.indexOf(settings.nudity)];
    if (id === 'lock') return settings.pin ? 'On' : 'Off';
    if (id === 'services') return String((settings.services || []).length);
    return '';
  }

  async function renderSettings() {
    settings = await eden.settings();
    const side = $('side');
    const panel = $('panel');
    if (settings.pin && !unlocked) {
      side.style.display = 'none';
      panel.textContent = '';
      const box = el('div', 'pin');
      box.appendChild(el('h2', '', 'Settings are locked'));
      const input = el('input', 'field');
      input.type = 'password';
      input.inputMode = 'numeric';
      input.placeholder = 'PIN';
      box.appendChild(input);
      const b = el('button', 'btn', 'Unlock');
      const tryPin = async () => { if (await eden.checkPin(input.value)) { unlocked = true; renderSettings(); } else { input.value = ''; toast('That PIN is not right'); } };
      b.onclick = tryPin;
      input.onkeydown = (e) => { if (e.key === 'Enter') tryPin(); };
      box.appendChild(b);
      panel.appendChild(box);
      setTimeout(() => input.focus(), 50);
      return;
    }
    side.style.display = '';
    side.textContent = '';
    side.appendChild(el('h1', '', 'Settings'));
    for (const [id, name, icon, colour] of SECTIONS) {
      const b = el('button', 'entry' + (id === section ? ' on' : ''));
      const badge = el('span', 'badge');
      badge.style.background = colour;
      badge.innerHTML = svg(icon);
      b.appendChild(badge);
      b.appendChild(el('span', '', name));
      const s = summary(id);
      if (s) b.appendChild(el('small', '', s));
      b.onclick = () => { section = id; renderSettings(); };
      side.appendChild(b);
    }
    panel.textContent = '';
    const body = el('div');
    panel.appendChild(body);
    await ({ language, nudity, browser, services, lock, report, about })[section](body);
  }

  async function language(body) {
    body.appendChild(el('h2', '', 'Language'));
    const note = el('p', 'lead', languageText(settings.language));
    body.appendChild(note);
    body.appendChild(card(line('', seg(LEVEL_NAMES, LEVELS.indexOf(settings.language), async (i) => {
      await save({ language: LEVELS[i] }); note.textContent = languageText(LEVELS[i]); renderWords();
    })), switchLine('Blasphemy', settings.blasphemy, async (on) => { await save({ blasphemy: on }); renderWords(); })));
    body.lastChild.firstChild.classList.add('wide');

    body.appendChild(el('div', 'group', 'Words'));
    const words = el('div', 'card words');
    body.appendChild(words);
    const reset = el('p', 'note');
    body.appendChild(reset);
    async function renderWords() {
      const list = await eden.words();
      const muted = await eden.wordsMuted();
      words.textContent = '';
      for (const w of list) {
        words.appendChild(switchLine(w.label, muted[w.id], async (on) => {
          await save({ wordChoices: { ...settings.wordChoices, [w.id]: on } });
          paintReset();
        }));
      }
      paintReset();
    }
    function paintReset() {
      reset.textContent = '';
      if (!Object.keys(settings.wordChoices || {}).length) { reset.textContent = 'Set by the level above. Switch any word on or off.'; return; }
      const a = el('a', '', 'Go back to the level’s choices');
      a.href = '#';
      a.style.color = 'var(--green)';
      a.onclick = async (e) => { e.preventDefault(); await save({ wordChoices: {} }); renderWords(); };
      reset.appendChild(a);
    }
    renderWords();

    body.appendChild(el('div', 'group', 'Your own words'));
    const extra = el('textarea');
    extra.rows = 2;
    extra.placeholder = 'Extra words to mute, separated by commas';
    extra.value = (settings.customWords || []).join(', ');
    extra.onchange = () => save({ customWords: splitWords(extra.value) });
    const allow = el('textarea');
    allow.rows = 2;
    allow.placeholder = 'Words to allow, separated by commas';
    allow.value = (settings.allowedWords || []).join(', ');
    allow.onchange = () => save({ allowedWords: splitWords(allow.value) });
    body.appendChild(extra);
    body.appendChild(el('div', '', ' '));
    body.appendChild(allow);

    body.appendChild(el('div', 'group', 'Captions'));
    body.appendChild(card(
      switchLine('Show captions', settings.showCaptions, (on) => save({ showCaptions: on })),
      switchLine('No captions, no sound', settings.silentWithoutCaptions, (on) => save({ silentWithoutCaptions: on }))));
    body.appendChild(el('p', 'note', 'Cursing is found from captions, including words they blank out. A video without them can play silent.'));
  }

  function splitWords(text) { return text.split(/[,\n]/).map((w) => w.trim().toLowerCase()).filter((w) => w); }

  function languageText(level) {
    return { OFF: 'Nothing is muted.', LOW: 'Mutes the strongest profanity.', MEDIUM: 'Mutes strong profanity and common swearing.',
      HIGH: 'Also mutes mild words such as damn and hell.' }[level];
  }

  function nudityText(level) {
    return { OFF: 'Nothing is hidden.', LOW: 'Hides explicit nudity.', MEDIUM: 'Hides explicit nudity, bare breasts and bare buttocks.',
      HIGH: 'Also hides swimwear, underwear and shirtless scenes.' }[level];
  }

  async function nudity(body) {
    body.appendChild(el('h2', '', 'Nudity'));
    const note = el('p', 'lead', nudityText(settings.nudity));
    body.appendChild(note);
    const level = line('', seg(LEVEL_NAMES, LEVELS.indexOf(settings.nudity), async (i) => { await save({ nudity: LEVELS[i] }); note.textContent = nudityText(LEVELS[i]); }));
    level.classList.add('wide');
    body.appendChild(card(level,
      line('When found', seg(['Hide', 'Skip'], settings.nudityAction === 'SKIP' ? 1 : 0, (i) => save({ nudityAction: i ? 'SKIP' : 'BLUR' }))),
      line('Hidden pictures', seg(['Blurred', 'Black'], settings.hideStyle === 'black' ? 1 : 0, (i) => save({ hideStyle: i ? 'black' : 'blur' })))));
    const status = await eden.model();
    body.appendChild(el('div', 'group', 'Detection'));
    const getIt = el('button', 'btn', status === 'failed' ? 'Try again' : 'Set up');
    getIt.onclick = async () => { getIt.textContent = 'Setting up…'; await eden.getModel(); renderSettings(); };
    body.appendChild(card(
      line('Detection', status === 'ready' ? 'Ready' : status === 'downloading' ? 'Setting up…' : getIt,
        status === 'ready' ? '' : 'Downloads once, about 11 MB'),
      line('Test the blur', (() => { const b = el('button', 'btn quiet', 'Start'); b.onclick = () => { eden.testBlur(); toast('For two minutes, faces are hidden too. Play any video with people in it.'); }; return b; })(),
        'Faces count as nudity for two minutes')));
    body.appendChild(el('p', 'note', 'The paid services lock their picture, so on them only cursing can be filtered.'));
  }

  async function browser(body) {
    body.appendChild(el('h2', '', 'Browser'));
    body.appendChild(el('p', 'lead', 'Searches always use safe search.'));
    const engines = [['google', 'Google'], ['duckduckgo', 'DuckDuckGo'], ['bing', 'Bing']];
    const search = line('Search with', seg(engines.map((e) => e[1]), Math.max(0, engines.findIndex((e) => e[0] === settings.searchEngine)),
      (i) => save({ searchEngine: engines[i][0] })));
    body.appendChild(card(search));
    body.appendChild(el('div', 'group', 'Block'));
    body.appendChild(card(
      switchLine('Adult websites', settings.blockAdult, (on) => save({ blockAdult: on })),
      switchLine('Twitter, Reddit and Instagram', settings.blockSocial, (on) => save({ blockSocial: on })),
      switchLine('Pop-ups and redirects', settings.blockPopups, (on) => save({ blockPopups: on }))));
    body.appendChild(el('p', 'note', 'Set a PIN to keep these on.'));
  }

  async function services(body) {
    body.appendChild(el('h2', '', 'Services'));
    body.appendChild(el('p', 'lead', 'Shown on Home. Sign in on each service’s own page.'));
    const list = await eden.services();
    const grid = el('div', 'card services');
    for (const s of list) {
      grid.appendChild(switchLine(s.name, (settings.services || []).includes(s.id), (on) => {
        const now = new Set(settings.services || []);
        if (on) now.add(s.id); else now.delete(s.id);
        save({ services: list.filter((x) => now.has(x.id)).map((x) => x.id) });
      }));
    }
    body.appendChild(grid);
  }

  async function lock(body) {
    body.appendChild(el('h2', '', 'PIN lock'));
    body.appendChild(el('p', 'lead', 'Settings ask for the PIN before opening.'));
    const input = el('input', 'field');
    input.type = 'password';
    input.inputMode = 'numeric';
    input.placeholder = settings.pin ? 'New PIN' : 'At least four digits';
    const saveIt = el('button', 'btn', settings.pin ? 'Change PIN' : 'Set PIN');
    saveIt.onclick = async () => {
      if (!/^\d{4,8}$/.test(input.value)) { toast('Use four to eight digits'); return; }
      await save({ pin: input.value });
      unlocked = true;
      toast('Settings are locked with your PIN');
      renderSettings();
    };
    const row = el('div');
    row.style.cssText = 'display:flex;gap:10px;align-items:center';
    input.style.maxWidth = '220px';
    row.appendChild(input);
    row.appendChild(saveIt);
    if (settings.pin) {
      const off = el('button', 'btn danger', 'Remove PIN');
      off.onclick = async () => { await save({ pin: '' }); toast('PIN removed'); renderSettings(); };
      row.appendChild(off);
    }
    body.appendChild(row);
  }

  async function report(body) {
    body.appendChild(el('h2', '', 'Filter report'));
    body.appendChild(el('p', 'lead', 'What the filters did lately.'));
    const pre = el('pre', 'report', (await eden.report()) || 'Nothing yet. Play a video.');
    body.appendChild(card(pre));
    pre.scrollTop = pre.scrollHeight;
    const row = el('div');
    row.style.cssText = 'display:flex;gap:10px;margin-top:12px';
    const copy = el('button', 'btn quiet', 'Copy');
    copy.onclick = async () => { await navigator.clipboard.writeText(pre.textContent); toast('Report copied'); };
    const clear = el('button', 'btn quiet', 'Clear');
    clear.onclick = async () => { await eden.clearReport(); renderSettings(); };
    const again = el('button', 'btn quiet', 'Refresh');
    again.onclick = () => renderSettings();
    row.append(copy, again, clear);
    body.appendChild(row);
  }

  async function about(body) {
    const info = await eden.about();
    body.appendChild(el('h2', '', 'About'));
    body.appendChild(card(
      line('Version', info.version),
      line('Protected video', { ready: 'Ready', 'getting ready': 'Getting ready…', 'not available': 'Not available', 'not needed': 'Not included' }[info.widevine] || info.widevine,
        'What paid services need to play')));
    body.appendChild(el('p', 'note', 'edenOS for your computer. The same filters as the phone app.'));
  }

  // ---- Start ----

  (async () => {
    settings = await eden.settings();
    state = await eden.state();
    show('home');
    paintBar();
    $('search').focus();
  })();
})();
