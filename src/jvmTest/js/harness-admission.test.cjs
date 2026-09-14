"use strict";
const {test}=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs"),vm=require("node:vm"),path=require("node:path");
const source=fs.readFileSync(path.resolve(__dirname,"../../commonMain/resources/web/harness.js"),"utf8");
const document={nodes:[],wires:[]};
const preset={document,sourceKind:"preset",programCid:"original",sourceCid:"original"};

function fixture(board={},query=""){
  const location=new URL("http://localhost:8888/blackboard"+query),requests=[],mounted=[],selected=[],elements=new Map();
  const element=()=>({value:"",textContent:"",classList:{add(){},remove(){}},setAttribute(){}});
  const context=vm.createContext({URL,URLSearchParams,encodeURIComponent,location,HarnessInitialHash:"",clearTimeout(){},
    history:{replaceState(_,__,url){location.href=String(url);}},
    $:key=>{if(!elements.has(key))elements.set(key,element());return elements.get(key);},
    EventSource:class{addEventListener(){} close(){}},
    LandscapeNavigation:{decode(){return null;}},Landscape:{async refresh(){},refreshActivity(){}},
    syncContracts:async()=>{},syncLanes:async()=>{},buildPalette(){},
    fetch:async url=>{requests.push(url);return {ok:true,json:async()=>url==="/blackboard/board"?{board,epoch:"one",revision:1}:{presets:[{name:"preset-shake",document}]}};},
  });
  vm.runInContext(source.split("\nAUTOSAVE=false;")[0]+"\nglobalThis.h=Harness;",context);
  const h=context.h;
  h.mount=name=>mounted.push(name);h.unmount=name=>h.mounts.delete(name);
  h.select=name=>{selected.push(name);h.selected=name;return true;};
  h.render=()=>{};h.setParent=()=>{};h.fit=()=>{};h.queueNeighbors=()=>{};
  h.reconnect=()=>{throw Error("Unexpected reconnect: "+elements.get("#status")?.textContent);};
  return {h,location,requests,mounted,selected,elements};
}

test("default connection mounts user programs and leaves example selection to the user",async()=>{
  const {h,mounted,selected}=fixture({
    "lcnc/program/preset-shake":preset,
    "lcnc/program/preset-curator":preset,
    "lcnc/program/preset-user-work":{document},
    "lcnc/program/application":{document},
    "lcnc/program/edited-example":{...preset,programCid:"user-edit"},
  });
  await h.connect();
  assert.deepEqual(mounted,["application","edited-example","preset-user-work"]);
  assert.deepEqual(selected,[]);assert.equal(h.selected,null);assert.equal(h.live,true);
});

test("a remembered load query does not admit an unmodified example",async()=>{
  const {h,location,mounted,selected}=fixture({"lcnc/program/preset-shake":preset},"?load=preset-shake#x=1&y=2&z=0.01");
  await h.connect();
  assert.deepEqual(mounted,[]);assert.deepEqual(selected,[]);
  assert.equal(location.search,"");assert.equal(location.hash,"");
});

test("explicit example selection is a local preview, including across reconnect",async()=>{
  const {h,requests,selected}=fixture({},"?example=preset-shake");
  await h.connect();
  assert.deepEqual(selected,["preset-shake"]);assert.equal(h.previews.has("preset-shake"),true);
  assert.equal(h.board["lcnc/program/preset-shake"],undefined);
  assert.deepEqual(requests,["/blackboard/board","/api/panels/presets"]);
  await h.connect();
  assert.equal(h.previews.has("preset-shake"),true);assert.equal(h.selected,"preset-shake");
  assert.ok(requests.every(url=>!url.includes("/run")));
});

test("opening an example cannot overwrite a user's program with the same name",()=>{
  const {h,selected}=fixture();h.board={"lcnc/program/preset-shake":{document}};
  h.openExample("preset-shake",{nodes:[{id:"fixture"}],wires:[]});
  assert.deepEqual(selected,["preset-shake"]);assert.equal(h.previews.size,0);
});

test("an empty program selector leaves Loading and changes an example label after saving",()=>{
  const select={options:[{value:"Loading programs",textContent:"Loading programs"}],replaceChildren(...xs){this.options=xs;}};
  const context=vm.createContext({$:()=>select,programKeys:[],previews:new Map(),el:(_,__,text)=>({textContent:text})});
  const start=source.indexOf('    const select = $("#programSelect");'),end=source.indexOf('    Landscape.refreshActivity();this.renderEvents();',start);
  const render=source.slice(start,end);
  vm.runInContext(render,context);
  assert.equal(select.options[0].textContent,"No saved programs");
  context.programKeys.push("lcnc/program/application");context.previews.set("application",{});
  vm.runInContext("(()=>{"+render+"})()",context);
  assert.equal(select.options[1].textContent,"Example: application");
  context.previews.clear();vm.runInContext("(()=>{"+render+"})()",context);
  assert.equal(select.options[1].textContent,"application");
});
