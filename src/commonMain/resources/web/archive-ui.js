"use strict";

const ArchiveUI = (() => {
  const $ = id => document.getElementById(id);
  let manifest = null, active = null, worker = null, busy = false;
  const status = text => { $("archiveStatus").textContent = text; };
  const contentURL = (cid, ordinal) => "/api/archives/content?cid=" + encodeURIComponent(cid) + "&entry=" + ordinal;
  function controls() {
    for (const id of ["archiveUpload","archiveDemo","archiveOpen","archiveDownload","archiveLandscape"]) $(id).disabled = busy || (!manifest && ["archiveDownload","archiveLandscape"].includes(id));
    $("archiveCancel").disabled = !busy;
  }
  async function run(action) {
    if (busy) return;
    busy = true; active = new AbortController(); controls();
    const timeout = setTimeout(() => active.abort(), 30000);
    try { await action(active.signal); }
    catch (error) { status(active.signal.aborted ? "Cancelled locally. An accepted import may already be stored." : error.message); }
    finally { clearTimeout(timeout); worker?.terminate(); worker = null; busy = false; controls(); }
  }
  async function json(url, options) {
    const response = await fetch(url, options), value = await response.json();
    if (!response.ok) throw Error(value.error || "Archive request failed: " + response.status);
    return value;
  }
  function decode(data, signal) {
    return new Promise((resolve, reject) => {
      const decoder = new Worker("/archive-worker.js"); worker = decoder;
      let settled = false;
      const timeout = setTimeout(() => finish(new Error("ZIP decoding exceeded 10 seconds")), ArchiveCore.limits.elapsedMs);
      const abort = () => finish(new DOMException("Cancelled", "AbortError"));
      function finish(error, value) {
        if (settled) return; settled = true;
        clearTimeout(timeout); signal.removeEventListener("abort", abort); decoder.terminate(); if (worker === decoder) worker = null;
        if (error) reject(error); else resolve(value);
      }
      signal.addEventListener("abort", abort, {once:true});
      decoder.onmessage = event => event.data.error ? finish(new Error(event.data.error)) : finish(null, event.data.request);
      decoder.onerror = event => finish(new Error(event.message || "ZIP worker failed"));
      decoder.postMessage(data, data.bytes ? [data.bytes] : []);
      if (signal.aborted) abort();
    });
  }
  function show(value) {
    ArchiveCore.projection(value);
    manifest = value; $("archiveCid").value = value.cid;
    const list = $("archiveEntries"); list.replaceChildren();
    for (const entry of value.entries) {
      const row = document.createElement("li"), box = document.createElement("input"), button = document.createElement("button");
      box.type = "checkbox"; box.value = entry.ordinal; box.checked = !entry.path.endsWith("/"); box.disabled = entry.path.endsWith("/");
      box.setAttribute("aria-label", "Select " + entry.path);
      button.textContent = entry.path; button.title = entry.cid; button.disabled = box.disabled;
      button.addEventListener("click", () => preview(entry));
      row.append(box, button); list.append(row);
    }
    GraalFileViewer.dispose($("archivePreview")); $("archivePreview").replaceChildren();
    $("archivePreviewTitle").textContent = "";
    status(value.entries.length + " entries stored · " + value.cid); controls();
  }
  function mount() {
    if (!manifest || !Harness.ready) { status("Waiting for the blackboard vocabulary"); return; }
    const name = "archive-" + manifest.cid.slice(7);
    const fresh = !Harness.board["lcnc/program/" + name] && !Harness.drafts.has(name);
    if (fresh) Harness.drafts.set(name, ArchiveCore.projection(manifest));
    Harness.select(name, false);
    if (fresh) {
      for (const node of G.nodes.filter(n => n._program === name && !n._parentScope)) layoutRing(node);
      Harness.changed();
    }
    Harness.fit(false); Harness.render(); $("archiveInspector").close();
    Harness.message("Archive mounted · " + manifest.cid);
  }
  async function preview(entry) {
    if (busy || !manifest) return;
    const cid = manifest.cid;
    $("archivePreviewTitle").textContent = entry.path;
    await GraalFileViewer.render($("archivePreview"), {id:entry.path,cid:entry.cid,type:entry.mediaType,url:contentURL(cid,entry.ordinal)});
  }
  async function importArchive(file) {
    await run(async signal => {
      if (file && file.size > ArchiveCore.limits.zipBytes) throw Error("ZIP compressed byte limit is 4 MiB");
      status("Decoding ZIP");
      const bytes = file ? await file.arrayBuffer() : null;
      signal.throwIfAborted();
      const request = await decode(bytes ? {bytes} : {demo:true}, signal);
      status("Storing archive");
      show(await json("/api/archives/import", {method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(request),signal}));
    });
  }
  async function open(cid, ordinal) {
    if (!$("archiveInspector").open) $("archiveInspector").showModal();
    let loaded = false;
    await run(async signal => {
      status("Opening archive");
      show(await json("/api/archives/manifest?cid=" + encodeURIComponent(cid), {signal})); loaded = true;
    });
    if (loaded && ordinal != null) {
      const entry = manifest.entries.find(e => e.ordinal === Number(ordinal));
      if (entry) await preview(entry);
    }
  }
  function download(bytes, name, type) {
    const url = URL.createObjectURL(new Blob([bytes], {type})), link = document.createElement("a");
    link.href = url; link.download = name; document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  }
  async function restore() {
    if (!manifest) return;
    const selected = new Set([...$("archiveEntries").querySelectorAll("input:checked")].map(box => Number(box.value)));
    const entries = manifest.entries.filter(e => selected.has(e.ordinal)), cid = manifest.cid;
    if (!entries.length) { status("Select at least one file"); return; }
    await run(async signal => {
      const files = Object.create(null); let total = 0;
      for (const entry of entries) {
        status("Restoring " + entry.path);
        const response = await fetch(contentURL(cid,entry.ordinal), {signal});
        if (!response.ok) throw Error((await response.json()).error || "Restore failed");
        const bytes = await GraalFileViewer.readBytes(response, ArchiveCore.limits.fileBytes);
        total += bytes.length; if (total > ArchiveCore.limits.totalBytes) throw Error("Restore byte limit exceeded");
        const digest = "sha256:" + [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))].map(b => b.toString(16).padStart(2,"0")).join("");
        if (digest !== entry.cid) throw Error("Restored CID mismatch: " + entry.path);
        files[entry.path] = bytes;
      }
      signal.throwIfAborted();
      if (entries.length === 1) download(files[entries[0].path], entries[0].path.split("/").pop(), "application/octet-stream");
      else download(fflate.zipSync(files, {level:0}), "archive-selection.zip", "application/zip");
      status("Verified and downloaded " + entries.length + " file(s)");
    });
  }
  function decorate(node) {
    if (!node.params?.archiveCid) return;
    node.el.classList.add("archive-node");
    const title = node.el.querySelector(".hd b");
    title.textContent = node.params.archivePath === "/" ? "Archive" : node.params.archivePath.replace(/\/$/, "").split("/").pop();
    title.title = node.params.archivePath;
    const label = node.el.querySelector(".ringlbl"); if (label) {label.textContent = "Directory"; label.title = node.params.archivePath;}
    if (node._childHost && !node.children?.length) {
      node._ringWorld.style.width = "160px"; node._ringWorld.style.height = "64px"; syncRingFrame(node);
    }
    const button = document.createElement("button"); button.className = "archive-inspect"; button.textContent = "↗";
    button.title = "Inspect archive bytes"; button.setAttribute("aria-label", "Inspect " + node.params.archivePath);
    button.addEventListener("pointerdown", event => event.stopPropagation());
    button.addEventListener("click", event => { event.stopPropagation(); open(node.params.archiveCid, node.params.archiveEntry); });
    node.el.append(button);
  }
  $("archiveBtn").addEventListener("click", () => $("archiveInspector").showModal());
  $("archiveUpload").addEventListener("click", () => $("archiveFile").click());
  $("archiveFile").addEventListener("change", event => { const file = event.target.files[0]; event.target.value = ""; if (file) importArchive(file); });
  $("archiveDemo").addEventListener("click", () => importArchive(null));
  $("archiveOpen").addEventListener("click", () => open($("archiveCid").value.trim()));
  $("archiveLandscape").addEventListener("click", mount);
  $("archiveDownload").addEventListener("click", restore);
  $("archiveCancel").addEventListener("click", () => active?.abort());
  $("archiveInspector").addEventListener("close", () => { active?.abort(); GraalFileViewer.dispose($("archivePreview")); });
  controls();
  return {open,decorate};
})();
