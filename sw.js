// PDF 필기장 – offline cache and "share to app" receiver
const CACHE = 'pdf-pilgi-202611020537';
const CORE = [
  './', './index.html', './pdfdata.json', './manifest.webmanifest',
  './lib/pdf.min.js', './lib/pdf.worker.min.js', './lib/pdf-lib.min.js',
  './icons/icon-192.png', './icons/icon-512.png',
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(CORE)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', e => {
  const req = e.request, url = new URL(req.url);
  if (req.method === 'POST' && url.pathname.endsWith('/share-target')) { e.respondWith(receiveShare(req)); return; }
  if (req.method !== 'GET') return;
  // app files: network first so updates arrive, cached copy when offline
  if (url.origin === location.origin) {
    e.respondWith(fetch(req).then(res => {
      if (res.ok) { const copy = res.clone(); caches.open(CACHE).then(c => c.put(req, copy)); }
      return res;
    }).catch(() => caches.match(req, { ignoreSearch: true }).then(hit => hit || caches.match('./index.html'))));
    return;
  }
  // web fonts: cache first
  if (/^fonts\.(googleapis|gstatic)\.com$/.test(url.hostname)) {
    e.respondWith(caches.match(req).then(hit => hit || fetch(req).then(res => {
      const copy = res.clone(); caches.open(CACHE).then(c => c.put(req, copy)); return res;
    })));
  }
});

async function receiveShare(req) {
  try {
    const form = await req.formData();
    const file = form.get('pdf');
    if (file && file.arrayBuffer) await idbPut('shared', { name: file.name, bytes: await file.arrayBuffer() });
  } catch (err) {}
  return Response.redirect('./?shared=1', 303);
}
function idbPut(key, value) {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open('pdf-pilgi', 1);
    open.onupgradeneeded = () => open.result.createObjectStore('kv');
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const tx = open.result.transaction('kv', 'readwrite');
      tx.objectStore('kv').put(value, key);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    };
  });
}
