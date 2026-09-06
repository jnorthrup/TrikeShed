"use strict";
importScripts("/vendor/fflate-0.8.2.js", "/archive-core.js");
self.onmessage = event => {
  try {
    const bytes = event.data.demo ? ArchiveCore.demo() : new Uint8Array(event.data.bytes);
    const entries = ArchiveCore.unpack(bytes);
    self.postMessage({request:ArchiveCore.request(entries)});
  } catch (error) {
    self.postMessage({error:error.message || String(error)});
  }
};
