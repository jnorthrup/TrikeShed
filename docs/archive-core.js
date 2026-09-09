"use strict";

// The same bounded ZIP adapter runs in Node and in the browser worker.
(function(root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory(require("./vendor/fflate-0.8.2.js"));
  else root.ArchiveCore = factory(root.fflate);
})(globalThis, function(zip) {
  const limits = Object.freeze({zipBytes: 4 * 1024 * 1024, entries: 128, fileBytes: 262144, totalBytes: 524288, depth: 12, path: 512, elapsedMs: 10000});
  const directoryType = "application/x-directory";
  const check = (ok, message) => { if (!ok) throw new Error(message); };
  function pathParts(path) {
    check(typeof path === "string" && path.length > 0 && path.length <= limits.path, "Invalid archive path length");
    const parts = path.replace(/\/$/, "").split("/");
    check(parts.length <= limits.depth && !/[\x00-\x1f\x7f\\:]/.test(path) &&
      parts.every(p => p && p !== "." && p !== ".." && p !== "__proto__"), "Unsafe archive path: " + path);
    return parts;
  }
  function checkPaths(paths) {
    const seen = new Set(), files = new Set(paths.filter(p => !p.endsWith("/")));
    for (const path of paths) {
      const parts = pathParts(path), key = parts.join("/");
      check(!seen.has(key), "Duplicate archive path: " + path); seen.add(key);
      for (let i = 1; i < parts.length; i++) check(!files.has(parts.slice(0, i).join("/")), "File/directory conflict: " + path);
    }
  }
  function mediaType(path) {
    if (path.endsWith("/")) return directoryType;
    const ext = path.split(".").pop().toLowerCase();
    return ({txt:"text/plain", md:"text/markdown", json:"application/json", js:"text/javascript", cjs:"text/javascript",
      kt:"text/plain", html:"text/html", css:"text/css", csv:"text/csv", png:"image/png", jpg:"image/jpeg", jpeg:"image/jpeg",
      gif:"image/gif", svg:"image/svg+xml", class:"application/java-vm"})[ext] || "application/octet-stream";
  }
  function unpack(bytes) {
    check(bytes instanceof Uint8Array && bytes.length >= 22 && bytes.length <= limits.zipBytes, "ZIP must be 22 bytes to 4 MiB");
    const deadline = Date.now() + limits.elapsedMs;
    const metadata = new Map(); let declared = 0;
    // Enumerate the central directory without inflating anything, including duplicate entries.
    zip.unzipSync(bytes, {filter(file) {
      check(Date.now() <= deadline, "ZIP decoding exceeded 10 seconds");
      pathParts(file.name);
      check(!metadata.has(file.name), "Duplicate archive path: " + file.name);
      check(metadata.size < limits.entries, "Archive entry limit is " + limits.entries);
      check([0, 8].includes(file.compression), "Unsupported ZIP compression");
      check(Number.isSafeInteger(file.originalSize) && file.originalSize >= 0 && file.originalSize <= limits.fileBytes, "Entry byte limit exceeded");
      declared += file.originalSize;
      check(declared <= limits.totalBytes, "Archive expanded byte limit exceeded");
      check(!file.name.endsWith("/") || file.originalSize === 0, "Directory contains payload");
      metadata.set(file.name, file); return false;
    }});
    check(metadata.size > 0, "Archive is empty"); checkPaths([...metadata.keys()]);
    const entries = new Map(); let actual = 0;
    // Streaming inflation measures actual output, not just untrusted ZIP size declarations.
    const reader = new zip.Unzip(file => {
      const meta = metadata.get(file.name);
      check(meta && !entries.has(file.name), "ZIP headers disagree: " + file.name);
      const entry = {path:file.name, mediaType:mediaType(file.name), chunks:[], length:0, complete:false};
      entries.set(file.name, entry);
      file.ondata = (error, chunk, final) => {
        if (error) throw error;
        actual += chunk.length; entry.length += chunk.length;
        check(actual <= limits.totalBytes && entry.length <= limits.fileBytes && entry.length <= meta.originalSize, "ZIP expanded byte limit exceeded");
        entry.chunks.push(chunk);
        if (final) { check(entry.length === meta.originalSize, "ZIP entry size mismatch"); entry.complete = true; }
      };
      file.start();
    });
    reader.register(zip.UnzipInflate);
    for (let offset = 0; offset < bytes.length; offset += 1024) {
      check(Date.now() <= deadline, "ZIP decoding exceeded 10 seconds");
      reader.push(bytes.subarray(offset, offset + 1024), offset + 1024 >= bytes.length);
    }
    check(entries.size === metadata.size, "ZIP entries missing");
    return [...metadata.keys()].map(path => {
      const entry = entries.get(path); check(entry.complete, "Truncated ZIP entry: " + path);
      const bytes = new Uint8Array(entry.length); let offset = 0;
      for (const chunk of entry.chunks) { bytes.set(chunk, offset); offset += chunk.length; }
      return {path, mediaType:entry.mediaType, bytes};
    });
  }
  function encode(bytes) {
    if (typeof Buffer !== "undefined") return Buffer.from(bytes).toString("base64");
    let text = "";
    for (let i = 0; i < bytes.length; i += 8192) text += String.fromCharCode(...bytes.subarray(i, i + 8192));
    return btoa(text);
  }
  function request(entries) {
    return {entries:entries.map(e => ({path:e.path, mediaType:e.mediaType, base64:encode(e.bytes)}))};
  }
  function demo() {
    const text = zip.strToU8;
    return zip.zipSync({
      "archive-demo/README.md": text("# Archive specimen\n\nNested source, structured data, an empty directory, and binary bytes.\n"),
      "archive-demo/src/message.js": text('export const message = "browser and Node share archive-core.js";\n'),
      "archive-demo/data/models.json": text(JSON.stringify({models:[{id:"offline-fixture",status:"fixture",live:false}]}, null, 2)),
      "archive-demo/data/bytes.bin": Uint8Array.from({length:256}, (_, i) => i),
      "archive-demo/empty/": new Uint8Array(),
      'archive-demo/quoted "name".txt': text("Filename escaping survives manifest storage and restore.\n")
    }, {level:6, mtime:new Date(2020, 0, 1)});
  }
  function projection(manifest) {
    check(/^sha256:[0-9a-f]{64}$/.test(manifest.cid), "Invalid archive CID");
    check(Array.isArray(manifest.entries) && manifest.entries.length > 0 && manifest.entries.length <= limits.entries, "Invalid manifest entries");
    checkPaths(manifest.entries.map(e => e.path));
    const root = {id:"archive",type:"scope",params:{title:"Archive " + manifest.cid.slice(7,19), archiveCid:manifest.cid, archivePath:"/"},children:[],x:0,y:0};
    const directories = new Map([["",root]]);
    function directory(parts) {
      const key = parts.join("/"); if (directories.has(key)) return directories.get(key);
      const parent = directory(parts.slice(0,-1));
      const node = {id:"directory:" + key,type:"scope",params:{title:parts.at(-1),archiveCid:manifest.cid,archivePath:key + "/"},children:[],x:0,y:0};
      parent.children.push(node); directories.set(key,node); return node;
    }
    for (const entry of manifest.entries) {
      const parts = pathParts(entry.path);
      check(Number.isInteger(entry.ordinal) && entry.ordinal >= 0 && /^sha256:[0-9a-f]{64}$/.test(entry.cid), "Invalid archive entry identity");
      if (entry.path.endsWith("/")) { directory(parts); continue; }
      directory(parts.slice(0,-1)).children.push({id:"entry:" + entry.ordinal,type:"note",x:0,y:0,params:{
        text:parts.at(-1),archiveCid:manifest.cid,archiveEntry:String(entry.ordinal),archivePath:entry.path,contentCid:entry.cid,mediaType:entry.mediaType
      }});
    }
    return {nodes:[root],wires:[],controls:{inspectionOnly:true,humanOversight:true},seq:1};
  }
  return {limits,directoryType,pathParts,checkPaths,unpack,request,demo,projection};
});
