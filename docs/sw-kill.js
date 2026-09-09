// Self-destructing service worker — served at /sw.js on the APP port (8888) only.
// The PWA (and its cache-first sw.js) is the gh-pages public offering; on the app
// port any previously registered worker must die. Browsers fetch this on their SW
// update check; byte-different content installs it, and activation unregisters,
// wipes every cache, and reloads open tabs onto the live server.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.map((k) => caches.delete(k)));
    await self.registration.unregister();
    const clients = await self.clients.matchAll({ type: 'window' });
    clients.forEach((c) => c.navigate(c.url));
  })());
});
// No fetch handler: while momentarily active, every request passes straight through.
