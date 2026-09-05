"use strict";

const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

function fixture() {
  const status = {textContent:""};
  const context = vm.createContext({
    $:()=>status,
    G:{nodes:[],wires:[]},
    fetch:async()=>assert.fail("inspection specimen must not dispatch"),
  });
  const source = fs.readFileSync(path.resolve(__dirname,"../../commonMain/resources/web/harness.js"),"utf8");
  vm.runInContext(source.split("\nAUTOSAVE=false;")[0]+"\nglobalThis.harness=Harness;",context);
  const harness = context.harness;
  harness.selected = "preset-shake";
  harness.board["lcnc/program/preset-shake"] = {document:{controls:{inspectionOnly:true},nodes:[],wires:[]}};
  return {harness,status,context};
}

test("Shake Demo remains inspection-only through document projection and rejects Run",async()=>{
  const {harness,status} = fixture();
  assert.equal(harness.inspectionOnly(),true);
  assert.equal(harness.document().controls.inspectionOnly,true);
  await harness.run();
  assert.match(status.textContent,/inspection.only/i);
  assert.equal(harness.running,false);
});

test("Shake reports actual socket coverage and does not label partial coverage as complete",()=>{
  const {status,context}=fixture();
  Object.assign(context,{
    clearVerdicts(){},redraw(){},save(){},markPort(){},buildVerdicts(){},portCenter:()=>null,
    document:{querySelectorAll:()=>[]},CSS:{escape:x=>x},VERDICTS:[],STARVED:new Set(),
  });
  const patch=fs.readFileSync(path.resolve(__dirname,"../../commonMain/resources/web/patch.js"),"utf8");
  vm.runInContext(patch.slice(patch.indexOf("function applyServerTreeShake("),patch.indexOf("function localTreeshake(")),context);
  context.applyServerTreeShake({made:[],coverage:{connected:741,total:742}});
  assert.match(status.textContent,/741\/742 sockets connected \(99%\)/);
  context.applyServerTreeShake({made:[{fromNode:"a",fromPort:"out",toNode:"b",toPort:"in"}],coverage:{connected:742,total:742}});
  assert.match(status.textContent,/742\/742 sockets connected \(100%\)/);
  assert.equal(context.G.wires.length,1);
});

test("inspection-only control is program-local and follows the selected draft",()=>{
  const {harness} = fixture();
  harness.board["lcnc/program/ordinary"] = {document:{nodes:[],wires:[]}};
  assert.equal(harness.inspectionOnly("ordinary"),false);
  harness.drafts.set("ordinary",{controls:{inspectionOnly:true}});
  assert.equal(harness.inspectionOnly("ordinary"),true);
  harness.drafts.delete("ordinary");
  assert.equal(harness.inspectionOnly("ordinary"),false);
});
