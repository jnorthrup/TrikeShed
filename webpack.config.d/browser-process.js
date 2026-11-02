// The shared JS source also runs unbundled on Node, where child_process is real.
// Browser applications have no process capability; the provider reports that
// explicitly before accessing this empty fallback.
config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, { child_process: false });
