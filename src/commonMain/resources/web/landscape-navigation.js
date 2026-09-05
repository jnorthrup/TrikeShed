"use strict";

// A bookmark is a view projection, never an execution command or a document edit.
const LandscapeNavigation = {
  encode(camera, focus = "") {
    const p = new URLSearchParams({x: String(camera.x), y: String(camera.y), z: String(camera.z)});
    if (focus) p.set("focus", focus);
    return "#" + p.toString();
  },
  decode(hash) {
    const p = new URLSearchParams(hash.replace(/^#/, ""));
    if (!["x", "y", "z"].every(k => p.has(k))) return null;
    const camera = {x: Number(p.get("x")), y: Number(p.get("y")), z: Number(p.get("z"))};
    if (!Object.values(camera).every(Number.isFinite) || camera.z < .01 || camera.z > 4000) return null;
    return {camera, focus: p.get("focus") || ""};
  },
  program(name) { return "program:" + name; },
  node(program, id) { return "node:" + JSON.stringify([program, id]); },
  object(id) { return "object:" + id; },
};
if (typeof module !== "undefined") module.exports = LandscapeNavigation;
