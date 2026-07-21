const CACHE_NAME = 'forge-cache-v1';
const SYNC_STORE_NAME = 'sync-queue';
const DB_NAME = 'forge-db';

// Install event: cache assets
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll([
        './',
        './index.html',
        './styles.css',
        './script.js',
        './manifest.webmanifest'
      ]);
    })
  );
});

// Fetch event: serve from cache if offline
self.addEventListener('fetch', (event) => {
  if (event.request.url.includes('/api/invoke') && event.request.method === 'POST') {
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
  } else {
    event.respondWith(
      caches.match(event.request).then((response) => {
        return response || fetch(event.request);
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
          return fetch('/api/invoke', {
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
