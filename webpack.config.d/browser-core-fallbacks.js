config.resolve = config.resolve || {};
config.resolve.fallback = {
  ...(config.resolve.fallback || {}),
  fs: false,
  os: false,
  path: false,
  child_process: false,
};
