#!/usr/bin/env node
"use strict";
const fs = require("node:fs");
const core = require("../src/commonMain/resources/web/archive-core.js");
const [command, path] = process.argv.slice(2);
if (command === "create" && path) {
  fs.writeFileSync(path, core.demo(), {flag:"wx"});
  console.log("Created " + path);
} else if (command === "inspect" && path) {
  if (fs.statSync(path).size > core.limits.zipBytes) throw Error("ZIP compressed byte limit is 4 MiB");
  console.log(JSON.stringify(core.unpack(new Uint8Array(fs.readFileSync(path))).map(e => ({path:e.path,bytes:e.bytes.length,mediaType:e.mediaType})), null, 2));
} else {
  console.error("Usage: node bin/archive-demo.cjs create <new.zip> | inspect <archive.zip>");
  process.exitCode = 2;
}
