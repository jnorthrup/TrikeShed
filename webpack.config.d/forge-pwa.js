const { GenerateSW } = require('workbox-webpack-plugin');

config.plugins = config.plugins || [];
config.plugins.push(
  new GenerateSW({
    swDest: 'sw.js',
    clientsClaim: true,
    skipWaiting: true,
    cleanupOutdatedCaches: true,
    navigateFallback: 'index.html',
    additionalManifestEntries: [
      { url: 'index.html', revision: Date.now().toString() }
    ],
    maximumFileSizeToCacheInBytes: 6 * 1024 * 1024,
    exclude: [/\.map$/],
    runtimeCaching: [
      {
        urlPattern: /manifest\.webmanifest$/,
        handler: 'StaleWhileRevalidate',
      },
      {
        urlPattern: /icons\/.*\.svg$/,
        handler: 'CacheFirst',
        options: {
          cacheName: 'forge-icons',
        },
      },
    ],
  })
);
