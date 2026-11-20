// Neon Coast's web app, opening without the network: its own files kept (the page, its look and code, the
// stations, the fonts and icons). Its own files are asked for fresh first and the kept ones used when that fails, so
// a new version shows at the next open; everything else (stations, shows, streams) goes to the network as asked.
const KEPT = 'neon-coast-app-2';
const OWN = ['./', 'index.html', 'app.css', 'app.js', 'stations.json', 'manifest.webmanifest',
  '../fonts/anton.woff2', '../fonts/chakra-400.woff2', '../fonts/chakra-600.woff2',
  '../img/icon.svg', '../img/icon-192.png', '../img/icon-512.png', '../img/apple-touch-icon.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(KEPT).then((c) => c.addAll(OWN)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((ks) => Promise.all(ks.filter((k) => k.startsWith('neon-coast-app-') && k !== KEPT).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const r = e.request;
  if (r.method !== 'GET' || new URL(r.url).origin !== self.location.origin) return;
  // audio from the phone (blob:) never comes here; a range ask (seeking) left to the browser
  if (r.headers.has('range')) return;
  e.respondWith(fetch(r).then((res) => {
    if (res.ok) { const copy = res.clone(); caches.open(KEPT).then((c) => c.put(r, copy)); }
    return res;
  }).catch(() => caches.match(r, { ignoreSearch: true }).then((hit) => hit || Response.error())));
});
