// Forge service worker — hand-written, no Workbox/NPM. Registered with a RELATIVE url from
// index.html, so its scope is the directory the page is served from (never the whole origin).
const CACHE_NAME = 'forge-cache-v3';
const SYNC_STORE_NAME = 'sync-queue';
const DB_NAME = 'forge-db';
const INVOKE_PATH = 'api/invoke';

// Install: precache the shell. index.html carries styles + script inline (server-baked), but the
// standalone files are cached too for the GitHub Pages / static layout.
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll([
        './',
        './index.html',
        './styles.css',
        './script.js',
        './manifest.webmanifest',
        './icons/forge-icon.svg',
        './icons/forge-icon-maskable.svg'/*FORGE_PRECACHE_EXTRA*/
      ]);
    }).then(() => self.skipWaiting())
  );
});

// Activate: drop previous cache generations, take over open clients.
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

// Fetch: queue reactor POSTs while offline; shell assets cache-first with background refresh;
// everything else (API GETs) network-first falling back to cache.
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  const isInvoke = url.pathname.endsWith('/' + INVOKE_PATH) && event.request.method === 'POST';
  const isApi = url.pathname.includes('/api/');
  if (isInvoke) {
    event.respondWith(
      fetch(event.request.clone()).catch(() => {
        // If offline, queue the action
        return new Promise((resolve) => {
          event.request.clone().json().then(payload => {
            queueSyncAction(payload);
            resolve(new Response(JSON.stringify({ status: 'queued' }), { headers: { 'Content-Type': 'application/json' } }));
          });
        });
      })
    );
  } else if (isApi || event.request.method !== 'GET') {
    // Live data: network first, cached copy only when offline.
    event.respondWith(
      fetch(event.request).then((response) => {
        if (event.request.method === 'GET' && response.ok) {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy));
        }
        return response;
      }).catch(() => caches.match(event.request))
    );
  } else {
    // Shell: cache first, refresh in the background (stale-while-revalidate).
    event.respondWith(
      caches.match(event.request).then((cached) => {
        const refresh = fetch(event.request).then((response) => {
          if (response && response.ok) {
            const copy = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy));
          }
          return response;
        }).catch(() => cached);
        return cached || refresh;
      })
    );
  }
});

// Background sync event
self.addEventListener('sync', (event) => {
  if (event.tag === 'sync-blackboard') {
    event.waitUntil(flushSyncQueue());
  }
});

function openDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = (e) => {
      e.target.result.createObjectStore(SYNC_STORE_NAME, { autoIncrement: true });
    };
    req.onsuccess = (e) => resolve(e.target.result);
    req.onerror = (e) => reject(e.target.error);
  });
}

function queueSyncAction(payload) {
  openDb().then(db => {
    const tx = db.transaction(SYNC_STORE_NAME, 'readwrite');
    tx.objectStore(SYNC_STORE_NAME).add(payload);
    // register background sync if supported
    if ('sync' in self.registration) {
      self.registration.sync.register('sync-blackboard');
    }
  });
}

function flushSyncQueue() {
  return openDb().then(db => {
    return new Promise((resolve) => {
      const tx = db.transaction(SYNC_STORE_NAME, 'readwrite');
      const store = tx.objectStore(SYNC_STORE_NAME);
      const req = store.getAll();
      
      req.onsuccess = () => {
        const items = req.result;
        if (items.length === 0) return resolve();
        
        const promises = items.map(item => {
          // Relative to the worker's own location — same directory index.html registered it from.
          return fetch(new URL(INVOKE_PATH, self.location.href).toString(), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(item)
          });
        });

        Promise.all(promises).then(() => {
          const clearTx = db.transaction(SYNC_STORE_NAME, 'readwrite');
          clearTx.objectStore(SYNC_STORE_NAME).clear();
          resolve();
        }).catch(resolve); // if fail, try again later
      };
    });
  });
}
