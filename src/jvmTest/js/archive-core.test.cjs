"use strict";
const test = require("node:test"), assert = require("node:assert/strict"), crypto = require("node:crypto");
const core = require("../../commonMain/resources/web/archive-core.js");
const zip = require("../../commonMain/resources/web/vendor/fflate-0.8.2.js");
const cid = bytes => "sha256:" + crypto.createHash("sha256").update(bytes).digest("hex");

test("Node and browser demo has nested, quoted, empty and binary entries", () => {
  const entries = core.unpack(core.demo());
  assert.equal(entries.length, 6);
  assert.deepEqual(entries.find(e => e.path.endsWith("bytes.bin")).bytes, Uint8Array.from({length:256}, (_, i) => i));
  assert.equal(entries.find(e => e.path.endsWith("empty/")).bytes.length, 0);
  assert.ok(entries.some(e => e.path.includes('"name"')));
  assert.deepEqual(core.request(entries).entries.map(e => Buffer.from(e.base64,"base64")), entries.map(e => Buffer.from(e.bytes)));
});
test("projection retains each immutable identity and marks containment inspection-only", () => {
  const entries = core.unpack(core.demo()).map((e, ordinal) => ({...e,ordinal,cid:cid(e.bytes)}));
  const manifest = {cid:cid(Buffer.from("manifest")),entries};
  const p = core.projection(manifest), nodes = [];
  const walk = list => list.forEach(n => {nodes.push(n);walk(n.children || []);}); walk(p.nodes);
  assert.equal(p.controls.inspectionOnly, true); assert.deepEqual(p.wires, []);
  assert.equal(nodes.filter(n => n.type === "note").length, 5);
  for (const n of nodes.filter(n => n.type === "note")) {
    assert.equal(n.params.contentCid, entries[Number(n.params.archiveEntry)].cid);
    assert.equal(n.params.archiveCid, manifest.cid);
  }
  assert.ok(nodes.some(n => n.params.archivePath === "archive-demo/empty/"));
});
for (const path of ["../escape", "/root", "a/../b", "a/./b", "a//b", "C:/x", "a\\b", "nul\x00x", "__proto__", "a/".repeat(13)]) {
  test("reject unsafe path " + JSON.stringify(path), () => assert.throws(() => core.unpack(zip.zipSync({[path]:new Uint8Array(1)}))));
}
test("reject file/directory collisions in either order", () => {
  for (const paths of [["a","a/b"],["a/b","a"],["a/","a"]]) {
    assert.throws(() => core.unpack(zip.zipSync(Object.fromEntries(paths.map(p => [p,new Uint8Array()])))), /conflict|Duplicate/);
  }
});
test("reject too many entries and expansion bombs before inflation", () => {
  assert.throws(() => core.unpack(zip.zipSync(Object.fromEntries(Array.from({length:129}, (_, i) => ["f" + i,new Uint8Array()])))), /entry limit/);
  assert.throws(() => core.unpack(zip.zipSync({bomb:new Uint8Array(core.limits.fileBytes + 1)})), /byte limit/);
  assert.throws(() => core.unpack(zip.zipSync({a:new Uint8Array(core.limits.fileBytes),b:new Uint8Array(core.limits.fileBytes),c:new Uint8Array(1)})), /byte limit/);
});
test("actual inflation cannot exceed a forged central-directory size", () => {
  const bytes = zip.zipSync({bomb:new Uint8Array(4096)});
  const view = new DataView(bytes.buffer);
  for (let i = 0; i < bytes.length - 24; i++) if (view.getUint32(i,true) === 0x02014b50) { view.setUint32(i + 24,1,true); break; }
  assert.throws(() => core.unpack(bytes), /byte limit/);
});
test("duplicate central-directory names are rejected before object-map overwrite", () => {
  const bytes = zip.zipSync({aa:new Uint8Array(),bb:new Uint8Array()});
  for (let i = 0; i < bytes.length - 1; i++) if (bytes[i] === 98 && bytes[i+1] === 98) {bytes[i]=97;bytes[i+1]=97;}
  assert.throws(() => core.unpack(bytes), /Duplicate/);
});
test("empty, truncated and oversized archives fail explicitly", () => {
  assert.throws(() => core.unpack(zip.zipSync({})), /empty/);
  assert.throws(() => core.unpack(core.demo().slice(0,100)));
  assert.throws(() => core.unpack(new Uint8Array(core.limits.zipBytes + 1)), /4 MiB/);
});
