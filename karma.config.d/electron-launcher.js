// Appended to Kotlin/JS's auto-generated karma.conf.js via useConfigDirectory().
//
// Registers karma-electron as a launcher plugin and adds the "Electron" browser
// that the ElectronHostTest expects to detect via process.versions.electron.
//
// Kotlin/JS auto-writes:  basePath, frameworks:['jasmine'], reporters, port, etc.
// We just extend: plugins, customLaunchers, browsers.

module.exports = function (config) {
  // Tell Karma where to find karma-electron inside the workspace-aggregator node_modules
  // (Kotlin/JS hoists it via npm dependency declarations in the build script).
  config.set({
    plugins: [
      require('karma-electron'),
    ].concat(config.plugins || []),
    customLaunchers: Object.assign({}, config.customLaunchers, {
      ElectronHeadless: {
        base: 'Electron',
        flags: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'],
      },
    }),
    browsers: ['ElectronHeadless'],
    // Single-run so the test task exits when finished
    singleRun: true,
  });
};
