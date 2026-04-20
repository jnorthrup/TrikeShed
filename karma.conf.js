module.exports = function(config) {
  config.set({
    // Use the Kotlin/JS plugin defaults for frameworks and files; only override browser launcher
    browsers: ['ChromeHeadlessNoSandbox'],
    customLaunchers: {
      ChromeHeadlessNoSandbox: {
        base: 'ChromeHeadless',
        flags: [
          '--no-sandbox',
          '--disable-gpu'
        ]
      }
    },
    singleRun: true,
    reporters: ['progress']
  });
};
