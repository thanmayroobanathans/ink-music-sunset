// Neon Coast in the browser (for iPhones above all, where the app can't go): ink radio's hand-picked stations and any
// other by name (radio-browser.info), podcasts (Apple's directory), and the songs on the phone, kept in the app
// (IndexedDB). One player, with the lock screen's controls (Media Session). Nothing leaves the phone but the asks for
// stations and shows.
'use strict';
(() => {
  const $ = (s) => document.querySelector(s);
  /** An element: h('li', {class: 'x', onclick: f, text: 'words'}, ...children). Words always as text, never as markup. */
  const h = (tag, props, ...kids) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(props || {})) {
      if (v == null || v === false) continue;
      if (k === 'class') e.className = v;
      else if (k === 'text') e.textContent = v;
      else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v === true ? '' : v);
    }
    for (const c of kids.flat()) if (c != null && c !== false) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
    return e;
  };
  /** Light things kept in this browser (what you follow, where you were in an episode); never throws. */
  const keep = {
    get(k, d) { try { const v = localStorage.getItem('nc.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem('nc.' + k, JSON.stringify(v)); } catch { /* private mode, full: kept for now only */ } },
  };
  const clock = (s) => {
    if (!isFinite(s) || s < 0) return '0:00';
    s = Math.floor(s);
    const hr = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, sec = String(s % 60).padStart(2, '0');
    return hr ? `${hr}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
  };
  let toastTimer = 0;
  const toast = (words) => {
    const t = $('#toast'); t.textContent = words; t.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, 3800);
  };
  const debounce = (f, ms) => { let t = 0; return (...a) => { clearTimeout(t); t = setTimeout(() => f(...a), ms); }; };

  // ---------------------------------------------------------------- sleeves: a picture for what has none
  // The app's station sleeves: rings from a corner in dithered dots, the name big over them, in one of the neons.
  const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5];
  const NEONS = ['#FF6FB5', '#4FF0E0', '#FFC94D', '#FF7A59'];
  const sleeves = new Map();
  function sleeve(name, size = 240) {
    const key = `${name}|${size}`;
    if (sleeves.has(key)) return sleeves.get(key);
    const c = document.createElement('canvas');
    c.width = c.height = size;
    const g = c.getContext('2d');
    let hash = 7;
    for (const ch of name) hash = (hash * 31 + ch.codePointAt(0)) >>> 0;
    g.fillStyle = '#0F0820';
    g.fillRect(0, 0, size, size);
    const dot = Math.max(3, Math.round(size / 46));
    const cx = hash & 8 ? 0 : size, cy = size, reach = Math.hypot(size, size);
    g.fillStyle = NEONS[hash % NEONS.length];
    for (let y = 0; y < size; y += dot) {
      for (let x = 0; x < size; x += dot) {
        const r = Math.hypot(x - cx, y - cy);
        if ((r / (dot * 6)) % 1 > 0.55) continue;
        const level = Math.pow(Math.max(0, 1 - r / reach), 0.85);
        if (level * 16 > BAYER[((y / dot) & 3) * 4 + ((x / dot) & 3)]) g.fillRect(x, y, dot - 1, dot - 1);
      }
    }
    // the name, as big as fits in three lines at the most
    const words = name.toUpperCase().split(/\s+/).filter(Boolean);
    const pad = size * 0.08, room = size - pad * 2;
    let lines = [], fs = size * 0.3;
    for (; fs > size * 0.09; fs -= Math.max(1, size / 120)) {
      g.font = `${fs}px Anton, Impact, sans-serif`;
      lines = [];
      let line = '';
      for (const w of words) {
        const t = line ? `${line} ${w}` : w;
        if (g.measureText(t).width <= room) line = t;
        else { if (line) lines.push(line); line = w; }
      }
      if (line) lines.push(line);
      if (lines.length <= 3 && lines.every((l) => g.measureText(l).width <= room)) break;
    }
    g.fillStyle = '#F6E6F0';
    g.textBaseline = 'top';
    g.shadowColor = 'rgba(15, 8, 32, .95)';
    g.shadowBlur = size * 0.05;
    lines.slice(0, 3).forEach((l, i) => g.fillText(l, pad, pad + i * fs * 1.02));
    const url = c.toDataURL('image/png');
    sleeves.set(key, url);
    return url;
  }

  // ---------------------------------------------------------------- the player
  const audio = new Audio();
  audio.preload = 'auto';
  document.body.append(audio);
  // the iPhone's own kind of sound for music: plays with the silent switch on (Safari 16.4 and on)
  try { if (navigator.audioSession) navigator.audioSession.type = 'playback'; } catch { /* older Safari */ }
  let queue = [], at = -1, failedOnce = false;

  /** Plays [list] from [i]. Called straight from a tap: the iPhone lets sound start only there. */
  function playList(list, i) {
    queue = list;
    at = i;
    start();
  }
  function start() {
    const it = queue[at];
    if (!it) return;
    failedOnce = false;
    audio.src = it.src;
    const resume = it.kind === 'episode' ? keep.get(`at.${it.id}`, 0) : 0;
    if (resume > 5) audio.addEventListener('loadedmetadata', () => { try { audio.currentTime = resume; } catch { /* not seekable yet */ } }, { once: true });
    said(it.live ? 'tuning in…' : '');
    shown(it);
    const p = audio.play();
    if (p) p.catch((e) => { if (e && e.name === 'NotAllowedError') said('tap play'); });
    if (it.uuid) fetch(`${radioBase || RADIO[0]}/json/url/${it.uuid}`).catch(() => {});   // radio-browser's count of plays
  }
  const next = () => { if (at < queue.length - 1) { at++; start(); } };
  const prev = () => {
    if (isFinite(audio.duration) && audio.currentTime > 5) { audio.currentTime = 0; return; }
    if (at > 0) { at--; start(); }
  };
  const toggle = () => {
    if (!queue[at]) return;
    if (audio.paused) {
      // a station paused a while is far behind live: back to now
      if (queue[at].live) { audio.src = queue[at].src; said('tuning in…'); }
      audio.play().catch(() => {});
    } else audio.pause();
  };
  const said = (w) => { $('#player-said').textContent = w; };

  function shown(it) {
    const art = it.art || sleeve(it.title, 112);
    $('#mini').hidden = false;
    document.body.classList.add('playing-bar');
    $('#mini-art').src = art;
    $('#mini-title').textContent = it.title;
    $('#mini-by').textContent = it.by || '';
    $('#player-art').src = it.artBig || it.art || sleeve(it.title, 480);
    $('#player-title').textContent = it.title;
    $('#player-by').textContent = it.by || '';
    $('#player-live').hidden = !it.live;
    $('#player-line').hidden = !!it.live;
    $('#c-back').hidden = $('#c-fwd').hidden = it.kind !== 'episode';
    $('#c-prev').disabled = at <= 0;
    $('#c-next').disabled = at >= queue.length - 1;
    document.querySelectorAll('.row.on').forEach((r) => r.classList.remove('on'));
    document.querySelectorAll(`.row[data-key="${CSS.escape(it.key)}"]`).forEach((r) => r.classList.add('on'));
    if ('mediaSession' in navigator) {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: it.title,
        artist: it.by || '',
        album: it.kind === 'radio' ? 'Neon Coast radio' : it.kind === 'episode' ? 'Neon Coast talk radio' : 'Neon Coast',
        artwork: [{ src: it.artBig || it.art || sleeve(it.title, 512), sizes: '512x512' }],
      });
    }
    playing();
  }
  function playing() {
    const on = !audio.paused;
    $('#mini-play').textContent = on ? 'pause' : 'play';
    $('#c-play').textContent = on ? 'PAUSE' : 'PLAY';
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = on ? 'playing' : 'paused';
  }
  audio.addEventListener('play', playing);
  audio.addEventListener('pause', playing);
  audio.addEventListener('playing', () => { said(''); playing(); });
  audio.addEventListener('waiting', () => said(queue[at]?.live ? 'tuning in…' : 'loading…'));
  audio.addEventListener('ended', () => { const it = queue[at]; if (it?.kind === 'episode') keep.set(`at.${it.id}`, 0); next(); });
  audio.addEventListener('error', () => {
    const it = queue[at];
    if (!it || !audio.src) return;
    // a station's other address (radio-browser gives two), once
    if (it.alt && !failedOnce) { failedOnce = true; audio.src = it.alt; audio.play().catch(() => {}); return; }
    said('');
    playing();
    toast(it.live ? `${it.title} isn't answering. try another station` : `${it.title} won't play here`);
  });
  let lastKept = 0;
  audio.addEventListener('timeupdate', () => {
    const d = audio.duration;
    if (isFinite(d) && d > 0) {
      if (!seeking) $('#seek').value = String(Math.round((audio.currentTime / d) * 1000));
      $('#t-at').textContent = clock(audio.currentTime);
      $('#t-all').textContent = clock(d);
      if ('mediaSession' in navigator && navigator.mediaSession.setPositionState) {
        try { navigator.mediaSession.setPositionState({ duration: d, position: Math.min(audio.currentTime, d), playbackRate: 1 }); } catch { /* */ }
      }
    }
    const it = queue[at];
    if (it?.kind === 'episode' && Date.now() - lastKept > 5000) { lastKept = Date.now(); keep.set(`at.${it.id}`, Math.floor(audio.currentTime)); }
  });
  let seeking = false;
  $('#seek').addEventListener('input', () => {
    seeking = true;
    const d = audio.duration;
    if (isFinite(d)) $('#t-at').textContent = clock((+$('#seek').value / 1000) * d);
  });
  $('#seek').addEventListener('change', () => {
    const d = audio.duration;
    if (isFinite(d)) audio.currentTime = (+$('#seek').value / 1000) * d;
    seeking = false;
  });
  $('#mini-play').addEventListener('click', toggle);
  $('#c-play').addEventListener('click', toggle);
  $('#c-next').addEventListener('click', next);
  $('#c-prev').addEventListener('click', prev);
  $('#c-back').addEventListener('click', () => { audio.currentTime = Math.max(0, audio.currentTime - 15); });
  $('#c-fwd').addEventListener('click', () => { audio.currentTime = Math.min(audio.duration || 0, audio.currentTime + 30); });
  const openPlayer = () => { $('#player').hidden = false; $('#player-close').focus(); };
  const closePlayer = () => { $('#player').hidden = true; $('#mini-open').focus(); };
  $('#mini-open').addEventListener('click', openPlayer);
  $('#player-close').addEventListener('click', closePlayer);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('#player').hidden) closePlayer(); });
  if ('mediaSession' in navigator) {
    const on = (a, f) => { try { navigator.mediaSession.setActionHandler(a, f); } catch { /* not this one */ } };
    on('play', () => audio.play().catch(() => {}));
    on('pause', () => audio.pause());
    on('previoustrack', prev);
    on('nexttrack', next);
    on('seekbackward', (d) => { audio.currentTime = Math.max(0, audio.currentTime - (d.seekOffset || 15)); });
    on('seekforward', (d) => { audio.currentTime = audio.currentTime + (d.seekOffset || 30); });
    on('seekto', (d) => { if (isFinite(audio.duration)) audio.currentTime = d.seekTime; });
  }

  /** A row: its picture, its name big, what it is small; [key] marks it lit while it plays. */
  function row({ key, art, name, sub, meta, side, onclick, extra }) {
    const img = h('img', { alt: '', loading: 'lazy', src: art });
    return h('li', null,
      h('div', { style: 'display:flex;align-items:center' },
        h('button', { class: 'row' + (queue[at]?.key === key ? ' on' : ''), type: 'button', 'data-key': key, onclick, style: 'flex:1;min-width:0' },
          img,
          h('span', { style: 'min-width:0' },
            h('span', { class: 'name', text: name }),
            sub ? h('span', { class: 'sub', text: sub }) : null,
            meta ? h('span', { class: 'meta', text: meta }) : null),
          side ? h('span', { class: 'side', text: side }) : h('span')),
        extra || null));
  }

  // ---------------------------------------------------------------- AIRWAVES
  // radio-browser.info's servers, the first that answers (it asks that apps spread over them)
  const RADIO = ['https://de1.api.radio-browser.info', 'https://de2.api.radio-browser.info', 'https://fi1.api.radio-browser.info', 'https://nl1.api.radio-browser.info'];
  let radioBase = null;
  async function radio(path) {
    const order = radioBase ? [radioBase, ...RADIO.filter((r) => r !== radioBase)] : RADIO;
    for (const base of order) {
      try {
        const r = await fetch(base + path, { headers: { Accept: 'application/json' } });
        if (!r.ok) continue;
        radioBase = base;
        return await r.json();
      } catch { /* the next server */ }
    }
    throw new Error('radio-browser.info is not answering');
  }
  /** What an iPhone plays: https (a secure page can't play plain http), and mp3, aac or HLS. */
  const playable = (s) => {
    const u = s.url_resolved || s.url || '';
    return u.startsWith('https://') && (/^(mp3|aac\+?|aac)$/i.test(s.codec || '') || /\.m3u8(\?|$)/i.test(u));
  };
  const fromBrowser = (s) => ({
    kind: 'radio', live: true, key: `rb:${s.stationuuid}`, uuid: s.stationuuid, title: s.name.trim(),
    by: [s.state || s.country, (s.tags || '').split(',').slice(0, 2).join(', ')].filter(Boolean).join(', ').toLowerCase(),
    art: s.favicon && s.favicon.startsWith('https://') ? s.favicon : null,
    src: s.url_resolved || s.url, alt: s.url_resolved && s.url && s.url !== s.url_resolved && s.url.startsWith('https://') ? s.url : null,
  });
  function radioRows(list, el) {
    el.replaceChildren(...list.map((it, i) => {
      const li = row({ key: it.key, art: it.art || sleeve(it.title, 112), name: it.title, sub: it.line || it.by, meta: it.where, onclick: () => playList(list, i) });
      const img = li.querySelector('img');
      img.addEventListener('error', () => { img.src = sleeve(it.title, 112); }, { once: true });
      return li;
    }));
  }
  let inkRadio = [];
  async function airwaves() {
    try {
      const d = await (await fetch('stations.json')).json();
      inkRadio = d.stations.map((s) => ({
        kind: 'radio', live: true, key: `ink:${s.id}`, title: s.name, by: `${s.city}, ${s.genre}`, line: s.line,
        where: `${s.city}, ${s.country}`, src: s.url, art: null, find: `${s.name} ${s.city} ${s.genre} ${s.line}`.toLowerCase(),
      }));
      radioRows(inkRadio, $('#ink-radio'));
    } catch { $('#ink-radio').replaceChildren(h('li', { class: 'quiet', text: "the stations didn't load. pull down to try again" })); }
    try {
      const india = (await radio('/json/stations/search?countrycode=IN&order=clickcount&reverse=true&hidebroken=true&limit=80'))
        .filter(playable).slice(0, 24).map(fromBrowser);
      if (india.length) radioRows(india, $('#india'));
      else $('#india').replaceChildren(h('li', { class: 'quiet', text: 'none answering right now' }));
    } catch { $('#india').replaceChildren(h('li', { class: 'quiet', text: "radio-browser isn't answering right now" })); }
  }
  let radioAsk = 0;
  const findStation = debounce(async (q) => {
    const el = $('#radio-found');
    q = q.trim();
    if (q.length < 2) { el.hidden = true; return; }
    const n = ++radioAsk;
    el.hidden = false;
    el.replaceChildren(h('p', { class: 'quiet', text: 'looking…' }));
    const ours = inkRadio.filter((s) => s.find.includes(q.toLowerCase()));
    let theirs = [];
    try {
      const enc = encodeURIComponent(q);
      const [byName, byTag] = await Promise.all([
        radio(`/json/stations/search?name=${enc}&order=clickcount&reverse=true&hidebroken=true&limit=60`).catch(() => []),
        radio(`/json/stations/search?tag=${enc}&order=clickcount&reverse=true&hidebroken=true&limit=60`).catch(() => []),
      ]);
      const seen = new Set();
      theirs = [...byName, ...byTag].filter((s) => playable(s) && !seen.has(s.stationuuid) && seen.add(s.stationuuid)).slice(0, 30).map(fromBrowser);
    } catch { /* ours alone */ }
    if (n !== radioAsk) return;
    const all = [...ours, ...theirs];
    if (!all.length) { el.replaceChildren(h('p', { class: 'quiet', text: `no station for "${q}" that plays here` })); return; }
    const ol = h('ol', { class: 'rows' });
    radioRows(all, ol);
    el.replaceChildren(h('h2', { class: 'label', text: `${all.length} found` }), ol);
  }, 450);
  $('#radio-q').addEventListener('input', (e) => findStation(e.target.value));

  // ---------------------------------------------------------------- TALK RADIO
  // Apple's podcast directory answers with a script (JSONP), so any browser can ask it
  let jsonpN = 0;
  function jsonp(url, ms = 12000) {
    return new Promise((ok, no) => {
      const cb = `__nc${++jsonpN}`;
      const s = document.createElement('script');
      const t = setTimeout(() => { done(); no(new Error('timed out')); }, ms);
      function done() { clearTimeout(t); delete window[cb]; s.remove(); }
      window[cb] = (d) => { done(); ok(d); };
      s.onerror = () => { done(); no(new Error('failed')); };
      s.src = `${url}${url.includes('?') ? '&' : '?'}callback=${cb}`;
      document.head.append(s);
    });
  }
  const https = (u) => (u || '').replace(/^http:\/\//, 'https://');
  const showRow = (s) => row({
    key: `show:${s.id}`, art: https(s.art) || sleeve(s.name, 112), name: s.name, sub: s.by,
    onclick: () => openShow(s),
  });
  const asShow = (r) => ({ id: String(r.collectionId || r.id), name: r.collectionName || r.name, by: r.artistName || '', art: https(r.artworkUrl600 || r.artworkUrl100 || '') });
  function following() {
    const f = keep.get('following', []);
    $('#following-label').hidden = !f.length;
    $('#following').replaceChildren(...f.map(showRow));
  }
  async function talk() {
    following();
    try {
      const r = await fetch('https://rss.applemarketingtools.com/api/v2/in/podcasts/top/25/podcasts.json');
      const d = await r.json();
      $('#top-shows').replaceChildren(...d.feed.results.map((x) => showRow(asShow({ id: x.id, name: x.name, artistName: x.artistName, artworkUrl100: (x.artworkUrl100 || '').replace('100x100', '600x600') }))));
    } catch {
      // Apple's charts can't always be read from a page: its directory's answer to "india" instead
      try {
        const d = await jsonp('https://itunes.apple.com/search?media=podcast&entity=podcast&limit=20&country=IN&term=india');
        $('#top-shows').replaceChildren(...d.results.map((x) => showRow(asShow(x))));
      } catch { $('#top-shows').replaceChildren(h('li', { class: 'quiet', text: 'the charts are not answering. find a show by name above' })); }
    }
  }
  let talkAsk = 0;
  const findShow = debounce(async (q) => {
    const el = $('#talk-found');
    q = q.trim();
    if (q.length < 2) { el.hidden = true; return; }
    const n = ++talkAsk;
    el.hidden = false;
    el.replaceChildren(h('p', { class: 'quiet', text: 'looking…' }));
    try {
      const d = await jsonp(`https://itunes.apple.com/search?media=podcast&entity=podcast&limit=25&country=IN&term=${encodeURIComponent(q)}`);
      if (n !== talkAsk) return;
      if (!d.results.length) { el.replaceChildren(h('p', { class: 'quiet', text: `no show called "${q}"` })); return; }
      el.replaceChildren(h('ol', { class: 'rows' }, d.results.map((x) => showRow(asShow(x)))));
    } catch { if (n === talkAsk) el.replaceChildren(h('p', { class: 'quiet', text: "apple's directory isn't answering. try again" })); }
  }, 450);
  $('#talk-q').addEventListener('input', (e) => findShow(e.target.value));

  async function openShow(s) {
    const page = $('#show');
    $('#talk-home').hidden = true;
    page.hidden = false;
    const f = () => keep.get('following', []);
    const isFollowed = () => f().some((x) => x.id === s.id);
    const follow = h('button', { class: 'word', type: 'button', text: isFollowed() ? 'following' : 'follow' });
    follow.addEventListener('click', () => {
      const now = isFollowed() ? f().filter((x) => x.id !== s.id) : [s, ...f()];
      keep.set('following', now);
      follow.textContent = isFollowed() ? 'following' : 'follow';
      following();
    });
    const eps = h('ol', { class: 'rows' }, h('li', { class: 'quiet', text: 'reading its episodes…' }));
    page.replaceChildren(
      h('button', { class: 'word back', type: 'button', text: 'back to talk radio', onclick: () => { page.hidden = true; $('#talk-home').hidden = false; } }),
      h('div', { class: 'show-head' },
        h('img', { alt: '', src: s.art || sleeve(s.name, 224) }),
        h('div', null, h('h2', { class: 'show-title', text: s.name }), h('p', { class: 'show-by', text: s.by }), follow)),
      eps);
    window.scrollTo(0, 0);
    try {
      const d = await jsonp(`https://itunes.apple.com/lookup?id=${encodeURIComponent(s.id)}&entity=podcastEpisode&limit=60&country=IN`);
      const list = d.results.filter((r) => r.wrapperType === 'podcastEpisode' && r.episodeUrl).map((r) => ({
        kind: 'episode', key: `ep:${r.trackId}`, id: String(r.trackId), title: r.trackName, by: s.name,
        art: https(r.artworkUrl160 || s.art), artBig: https(r.artworkUrl600 || s.art), src: https(r.episodeUrl),
        when: r.releaseDate ? new Date(r.releaseDate).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }).toLowerCase() : '',
        length: r.trackTimeMillis ? clock(r.trackTimeMillis / 1000) : '',
      }));
      if (!list.length) { eps.replaceChildren(h('li', { class: 'quiet', text: "apple doesn't list this show's episodes" })); return; }
      eps.replaceChildren(...list.map((it, i) => {
        const was = keep.get(`at.${it.id}`, 0);
        return row({ key: it.key, art: it.art || sleeve(it.title, 112), name: it.title, sub: [it.when, it.length].filter(Boolean).join(', '),
          meta: was > 30 ? `left at ${clock(was)}` : null, onclick: () => playList(list, i) });
      }));
    } catch { eps.replaceChildren(h('li', { class: 'quiet', text: "its episodes didn't come. try again" })); }
  }

  // ---------------------------------------------------------------- TRACKS: the phone's songs, kept in the app
  const db = {
    open() {
      return (this.p ||= new Promise((ok, no) => {
        const r = indexedDB.open('neon-coast', 1);
        r.onupgradeneeded = () => r.result.createObjectStore('tracks', { keyPath: 'id' });
        r.onsuccess = () => ok(r.result);
        r.onerror = () => no(r.error);
      }));
    },
    async run(mode, f) {
      const d = await this.open();
      return new Promise((ok, no) => {
        const t = d.transaction('tracks', mode);
        const r = f(t.objectStore('tracks'));
        t.oncomplete = () => ok(r && r.result);
        t.onerror = () => no(t.error);
        t.onabort = () => no(t.error);
      });
    },
    all() { return this.run('readonly', (s) => s.getAll()); },
    put(rec) { return this.run('readwrite', (s) => s.put(rec)); },
    del(id) { return this.run('readwrite', (s) => s.delete(id)); },
  };
  // a song's words and cover from its ID3 tag (mp3), else from its file's name ("Artist - Title.mp3")
  const syncsafe = (b, i) => ((b[i] & 127) << 21) | ((b[i + 1] & 127) << 14) | ((b[i + 2] & 127) << 7) | (b[i + 3] & 127);
  function tagText(b) {
    const enc = b[0];
    let d = b.subarray(1), s;
    if (enc === 0) s = new TextDecoder('latin1').decode(d);
    else if (enc === 1) {
      const be = d[0] === 0xfe && d[1] === 0xff;
      if ((d[0] === 0xff && d[1] === 0xfe) || be) d = d.subarray(2);
      s = new TextDecoder(be ? 'utf-16be' : 'utf-16le').decode(d);
    } else if (enc === 2) s = new TextDecoder('utf-16be').decode(d);
    else s = new TextDecoder('utf-8').decode(d);
    return s.split('\0')[0].trim();
  }
  function tagPicture(b) {
    const enc = b[0];
    let i = 1;
    while (i < b.length && b[i] !== 0) i++;
    const mime = new TextDecoder('latin1').decode(b.subarray(1, i)) || 'image/jpeg';
    i += 2;   // the 0, the picture's kind
    if (enc === 1 || enc === 2) { while (i + 1 < b.length && (b[i] !== 0 || b[i + 1] !== 0)) i += 2; i += 2; }
    else { while (i < b.length && b[i] !== 0) i++; i += 1; }
    if (mime === '-->' || i >= b.length) return null;
    return new Blob([b.slice(i)], { type: mime.includes('/') ? mime : `image/${mime.toLowerCase()}` });
  }
  async function readTags(file) {
    const out = { title: '', artist: '', album: '', art: null };
    try {
      const head = new Uint8Array(await file.slice(0, 10).arrayBuffer());
      if (head[0] === 0x49 && head[1] === 0x44 && head[2] === 0x33 && head[3] >= 3) {
        const v4 = head[3] === 4;
        const size = syncsafe(head, 6);
        const b = new Uint8Array(await file.slice(10, 10 + Math.min(size, 12 * 1024 * 1024)).arrayBuffer());
        let p = head[5] & 0x40 ? (v4 ? syncsafe(b, 0) : ((b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]) + 4) : 0;
        while (p + 10 <= b.length) {
          const id = String.fromCharCode(b[p], b[p + 1], b[p + 2], b[p + 3]);
          if (!/^[A-Z0-9]{4}$/.test(id)) break;
          const len = v4 ? syncsafe(b, p + 4) : ((b[p + 4] << 24) | (b[p + 5] << 16) | (b[p + 6] << 8) | b[p + 7]) >>> 0;
          if (len <= 0 || p + 10 + len > b.length) break;
          const body = b.subarray(p + 10, p + 10 + len);
          if (id === 'TIT2') out.title = tagText(body);
          else if (id === 'TPE1') out.artist = tagText(body);
          else if (id === 'TALB') out.album = tagText(body);
          else if (id === 'APIC' && !out.art) out.art = tagPicture(body);
          p += 10 + len;
        }
      }
    } catch { /* the file's name, then */ }
    if (!out.title) {
      const base = file.name.replace(/\.[^.]+$/, '').replace(/_/g, ' ').trim();
      const m = base.match(/^(.+?)\s+-\s+(.+)$/);
      if (m && !out.artist) { out.artist = m[1]; out.title = m[2]; } else out.title = base;
    }
    return out;
  }
  let tracks = [];          // the songs as the player plays them
  const urls = new Map();   // each song's file and cover as addresses, made once (one playing is never let go)
  let editing = false;
  async function showTracks() {
    let recs = [];
    try { recs = await db.all(); } catch { $('#tracks-note').textContent = "this browser won't keep songs (a private window?). they play till you close it."; }
    recs.sort((a, b) => a.added - b.added);
    const ids = new Set(recs.map((r) => r.id));
    for (const [id, u] of urls) {
      if (ids.has(id) || queue[at]?.id === id) continue;
      URL.revokeObjectURL(u.src);
      if (u.art) URL.revokeObjectURL(u.art);
      urls.delete(id);
    }
    tracks = recs.map((r) => {
      const blob = (x, type) => (x instanceof Blob ? x : new Blob([x], { type: type || '' }));
      if (!urls.has(r.id)) urls.set(r.id, { src: URL.createObjectURL(blob(r.file, r.type || 'audio/mpeg')), art: r.art ? URL.createObjectURL(blob(r.art, r.artType || 'image/jpeg')) : null });
      const u = urls.get(r.id);
      return { kind: 'track', key: `t:${r.id}`, id: r.id, title: r.title, by: [r.artist, r.album].filter(Boolean).join(', '), src: u.src, art: u.art };
    });
    const any = tracks.length > 0;
    $('#play-all').hidden = $('#shuffle').hidden = $('#edit').hidden = !any;
    $('#edit').textContent = editing ? 'done' : 'edit';
    $('#track-list').replaceChildren(...tracks.map((t, i) => row({
      key: t.key, art: t.art || sleeve(t.title, 112), name: t.title, sub: t.by, onclick: () => playList(tracks, i),
      extra: editing ? h('button', { class: 'remove', type: 'button', text: 'remove', 'aria-label': `remove ${t.title}`,
        onclick: async () => { await db.del(t.id).catch(() => {}); showTracks(); } }) : null,
    })));
  }
  $('#add-files').addEventListener('change', async (e) => {
    const files = [...e.target.files];
    e.target.value = '';
    if (!files.length) return;
    try { if (navigator.storage && navigator.storage.persist) await navigator.storage.persist(); } catch { /* */ }
    let added = 0;
    for (const [i, file] of files.entries()) {
      toast(`adding ${i + 1} of ${files.length}…`);
      const t = await readTags(file);
      const rec = { id: `${Date.now()}-${i}-${Math.random().toString(36).slice(2, 7)}`, ...t, file, added: Date.now() + i };
      try {
        await db.put(rec);
        added++;
      } catch {
        // Safari's private windows keep no files: the song's bytes instead (read whole when the list is)
        try {
          await db.put({ ...rec, file: await file.arrayBuffer(), type: file.type || 'audio/mpeg',
            art: t.art ? await t.art.arrayBuffer() : null, artType: t.art ? t.art.type : null });
          added++;
        } catch { toast("this phone's storage for the app is full"); break; }
      }
    }
    if (added) toast(`${added} ${added === 1 ? 'song' : 'songs'} added`);
    showTracks();
  });
  $('#play-all').addEventListener('click', () => playList(tracks, 0));
  $('#shuffle').addEventListener('click', () => {
    const s = [...tracks];
    for (let i = s.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [s[i], s[j]] = [s[j], s[i]]; }
    playList(s, 0);
  });
  $('#edit').addEventListener('click', () => { editing = !editing; showTracks(); });

  // ---------------------------------------------------------------- the places
  const PLACES = { airwaves: 'AIRWAVES', talk: 'TALK RADIO', tracks: 'TRACKS' };
  const loaded = new Set();
  function go() {
    const p = (location.hash || '#airwaves').slice(1);
    const place = PLACES[p] ? p : 'airwaves';
    for (const k of Object.keys(PLACES)) $(`#${k}`).hidden = k !== place;
    document.querySelectorAll('.places a').forEach((a) => a.toggleAttribute('aria-current', a.dataset.place === place));
    document.querySelectorAll('.places a[aria-current]').forEach((a) => a.setAttribute('aria-current', 'page'));
    $('#place').textContent = PLACES[place];
    document.title = `Neon Coast: ${PLACES[place].toLowerCase()}`;
    if (!loaded.has(place)) {
      loaded.add(place);
      if (place === 'airwaves') airwaves();
      if (place === 'talk') talk();
      if (place === 'tracks') showTracks();
    }
  }
  window.addEventListener('hashchange', () => { go(); window.scrollTo(0, 0); });

  // on an iPhone in Safari (not yet on the home screen): how to put it there, once
  const apple = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  if (apple && !navigator.standalone && !keep.get('install-hint-off', false)) $('#install').hidden = false;
  $('#install-off').addEventListener('click', () => { keep.set('install-hint-off', true); $('#install').hidden = true; });

  // the sleeves want Anton drawn into them: the fonts first (a second at the most)
  const fonts = document.fonts ? Promise.race([document.fonts.load('40px Anton'), new Promise((r) => setTimeout(r, 1200))]) : Promise.resolve();
  fonts.then(go, go);
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
})();
