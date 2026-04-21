module.exports = function(config) {
  const firefoxBin = process.env.FIREFOX_BIN;
  const useFirefox = typeof firefoxBin !== 'undefined' && firefoxBin !== '';

  const customLaunchers = {};

  if (useFirefox) {
    customLaunchers.FirefoxHeadless = {
      base: 'Firefox',
      flags: ['-headless']
    };
  } else {
    customLaunchers.ChromeHeadlessNoSandbox = {
      base: 'ChromeHeadless',
      flags: [
        '--no-sandbox',
        '--disable-gpu',
        '--disable-dev-shm-usage'
      ]
    };
  }

  config.set({
    // Use the Kotlin/JS plugin defaults for frameworks and files; only override browser launcher
    browsers: useFirefox ? ['FirefoxHeadless'] : ['ChromeHeadlessNoSandbox'],
    customLaunchers: customLaunchers,
    singleRun: true,
    reporters: ['progress']
  });
};
