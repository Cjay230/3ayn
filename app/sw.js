/* 3ayn service worker
 *
 * Two rules matter more than anything else here:
 *   1. Never intercept anything that is not a GET. Every backend call
 *      (/ask, /read, /find, /who, /enroll, /speak) is a POST carrying a
 *      camera frame. Touching those would break the app.
 *   2. Never cache the API host. Answers are about *right now* — a cached
 *      scene description handed to a blind user is worse than no answer.
 *
 * Bump VERSION whenever you change what gets precached.
 */

const VERSION = 'v1';
const SHELL   = `3ayn-shell-${VERSION}`;
const RUNTIME = `3ayn-runtime-${VERSION}`;

const PRECACHE = [
  '/',
  '/index.html',
  '/viewer.html',
  '/manifest.webmanifest',
  '/icon-192.png',
  '/icon-512.png',
  '/icon-maskable-512.png',
  '/apple-touch-icon.png'
];

// allSettled, not addAll: one missing file shouldn't fail the whole install.
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(SHELL).then(cache =>
      Promise.allSettled(PRECACHE.map(url => cache.add(url)))
    )
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys.filter(k => k !== SHELL && k !== RUNTIME)
            .map(k => caches.delete(k))
      ))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  const req = event.request;

  // Rule 1: POSTs pass straight through, untouched.
  if (req.method !== 'GET') return;

  const url = new URL(req.url);

  // Rule 2: the backend is never cached, not even GET /user/{id}.
  if (url.hostname.endsWith('.amazonaws.com')) return;

  // Page loads: network first, so a fresh deploy is picked up immediately.
  // Falls back to the cached shell when the network is gone.
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then(res => {
          const copy = res.clone();
          caches.open(SHELL).then(c => c.put(req, copy));
          return res;
        })
        .catch(() =>
          caches.match(req).then(hit => hit || caches.match('/index.html'))
        )
    );
    return;
  }

  // Google Fonts: serve cached instantly, refresh in the background.
  // Without this the UI falls back to a system font offline.
  if (url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com') {
    event.respondWith(
      caches.open(RUNTIME).then(cache =>
        cache.match(req).then(hit => {
          const network = fetch(req)
            .then(res => { cache.put(req, res.clone()); return res; })
            .catch(() => hit);
          return hit || network;
        })
      )
    );
    return;
  }

  // Everything else same-origin (icons, manifest): cache first.
  if (url.origin === self.location.origin) {
    event.respondWith(
      caches.match(req).then(hit =>
        hit || fetch(req).then(res => {
          const copy = res.clone();
          caches.open(RUNTIME).then(c => c.put(req, copy));
          return res;
        })
      )
    );
  }
});
