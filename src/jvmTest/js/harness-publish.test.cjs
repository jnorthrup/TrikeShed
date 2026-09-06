"use strict";
const {test}=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs"),vm=require("node:vm"),path=require("node:path");
const web=path.resolve(__dirname,"../../commonMain/resources/web");
// Slices Harness.publish() out of harness.js and runs it against a fixture, the way harness-arguments.test.cjs slices run().
function fixture(responses){
  const calls={inspected:[],mounted:[],selected:[],unmounted:[]};
  const harness={
    selected:"a",board:{"lcnc/program/a":{programCid:"cid-a",document:{nodes:[]}}},drafts:new Map([["a",{nodes:[]}]]),dirty:true,
    loadedCids:new Map([["a","cid-a"]]),document(){return {nodes:[],wires:[]};},
    inspect(key){calls.inspected.push(key);},mount(name){calls.mounted.push(name);},select(name){calls.selected.push(name);},unmount(name){calls.unmounted.push(name);},
    message(text){harness.messageText=text;},
  };
  const requests=[];
  const context=vm.createContext({Harness:harness,$:id=>({value:"a"}),encodeURIComponent,JSON,Date,String,Error,fetch:async(url,options)=>{
    requests.push({url,...options});const next=responses.shift()||{status:200,body:{verdict:"ok",cid:"cid-b"}};
    return {ok:next.status<300,status:next.status,json:async()=>next.body};
  }});
  const source=fs.readFileSync(path.join(web,"harness.js"),"utf8");
  const start=source.indexOf("  async publish("),end=source.indexOf("  async snapshot() {");
  assert.ok(start>0&&end>start,"publish() and snapshot() are where the slice expects them");
  vm.runInContext("globalThis.publish=({"+source.slice(start,end)+"}).publish;",context);
  harness.publish=context.publish;
  return {harness,requests,calls};
}

test("Publish names the version the editor loaded and keeps the draft when the board moved on",async()=>{
  const {harness,requests,calls}=fixture([{status:409,body:{verdict:"refused",error:"stale_base",name:"a",baseCid:"cid-a",currentCid:"cid-other-1234567890"}}]);
  await harness.publish();
  assert.equal(requests.length,1);assert.equal(requests[0].url,"/api/panels/a?baseCid=cid-a");
  assert.ok(harness.drafts.has("a"),"the draft is kept");assert.equal(harness.dirty,true);
  assert.match(harness.messageText,/Publish refused: a moved on the board/);
  assert.deepEqual(calls.inspected,["lcnc/publish/a"]);assert.equal(harness.board["lcnc/publish/a"].currentCid,"cid-other-1234567890");
  assert.deepEqual(calls.mounted,[],"nothing was remounted over the draft");
});

test("A matching base publishes, remounts the board version and clears the draft",async()=>{
  const {harness,requests,calls}=fixture([{status:200,body:{verdict:"ok",cid:"cid-b",previousCid:"cid-a",violations:[]}},{status:200,body:{programCid:"cid-b",document:{nodes:[]}}}]);
  await harness.publish();
  assert.equal(requests[0].url,"/api/panels/a?baseCid=cid-a");assert.equal(requests[1].url,"/api/panels/a?entry=1");
  assert.equal(harness.drafts.has("a"),false);assert.equal(harness.dirty,false);assert.deepEqual(calls.mounted,["a"]);
  assert.equal(harness.board["lcnc/program/a"].programCid,"cid-b");assert.match(harness.messageText,/Published a/);
});

test("Overwrite passes the board's current cid as the base; a new name sends no base",async()=>{
  const {harness,requests}=fixture([{status:200,body:{verdict:"ok",cid:"cid-c"}},{status:200,body:{programCid:"cid-c",document:{nodes:[]}}}]);
  await harness.publish("cid-other");
  assert.equal(requests[0].url,"/api/panels/a?baseCid=cid-other");
  const other=fixture([{status:200,body:{verdict:"ok",cid:"cid-d"}},{status:200,body:{programCid:"cid-d",document:{nodes:[]}}}]);
  other.harness.selected="preset-corpus";
  await other.harness.publish();
  assert.equal(other.requests[0].url,"/api/panels/a","publishing a preset under a new name names no base");
});
