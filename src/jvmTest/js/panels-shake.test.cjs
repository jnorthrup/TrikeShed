"use strict";
const {test}=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs"),vm=require("node:vm"),path=require("node:path");
const web=path.resolve(__dirname,"../../commonMain/resources/web"),panels=fs.readFileSync(path.join(web,"panels.html"),"utf8");
const shake=fs.readFileSync(path.join(web,"patch-shake.js"),"utf8");
const part=(source,start,end)=>source.slice(source.indexOf(start),source.indexOf(end,source.indexOf(start)));
function fixture(){
  const elements=new Map(),timers=new Map();let nextTimer=0,saves=0,applied=0,name="sample";
  const element=id=>{if(!elements.has(id))elements.set(id,{});return elements.get(id);};
  Object.defineProperty(element("#panelName"),"value",{get:()=>name});
  const context=vm.createContext({
    G:{nodes:[],wires:[]},view:{x:0,y:0,z:1},BOARD:{name:"sample"},CONTRACTS:{scope:{},literal:{},display:{}},
    $:element,AbortController,TextEncoder,
    setTimeout:fn=>{timers.set(++nextTimer,fn);return nextTimer;},clearTimeout:id=>timers.delete(id),
    buildNode:n=>{n.el={remove(){}};},wireKindOk:()=>true,restoreCameraView(){},applyView(){},redraw(){},save:()=>saves++,
    fetch:async()=>({ok:true,json:async()=>({ok:true,made:[],verdicts:[],starved:[]})}),
  });
  vm.runInContext(shake.slice(0,shake.indexOf("let STARVED=")),context);
  vm.runInContext(part(panels,"const PanelsShake=","function startWireDrag("),context);
  vm.runInContext(part(panels,"function nodeDoc(","let AUTOSAVE="),context);
  vm.runInContext(part(panels,"function fromConfix(","async function openGallery("),context);
  vm.runInContext(part(panels,"function load(data){","/* constructions are STORE DOCUMENTS"),context);
  context.mountChildren=()=>{for(const n of context.G.nodes)for(const c of n.children||[])c._parentScope=n;};
  context.applyServerTreeShake=()=>{applied++;};
  vm.runInContext("globalThis.owner=PanelsShake;",context);
  return {context,element,timers,saves:()=>saves,applied:()=>applied,rename:value=>{name=value;}};
}
const doc=()=>({name:"sample",controls:{inspectionOnly:true},nodes:[{id:"s",type:"scope",params:{},children:[{id:"a",type:"literal",x:0,y:0,params:{value:"hello"}},{id:"b",type:"display",x:100,y:0,params:{}}]}],wires:[]});

test("Panels serializes nested program values once, not cyclic renderer state",async()=>{
  const {context:c,applied}=fixture();c.load(c.fromConfix(doc()));
  assert.throws(()=>JSON.stringify(c.G),/circular/i,"the old request tried to send this cycle");
  let request;c.fetch=async(url,options)=>{request=JSON.parse(options.body);assert.equal(url,"/api/lcnc/treeshake");return {ok:true,json:async()=>({ok:true})};};
  await c.treeshake({optional:true});
  assert.equal(request.program.nodes.length,1);assert.equal(request.program.nodes[0].children.length,2);
  assert.equal(request.program.controls.inspectionOnly,true);assert.equal(request.options.optional,true);
  assert.equal(request.options.parentId,null);assert.ok(!JSON.stringify(request).includes("_parentScope"));
  assert.ok(!("el" in request.program.nodes[0]));assert.ok(!("view" in request.program));assert.equal(applied(),1);
});

test("server refusal, offline, and malformed responses never fall back to local matching",async()=>{
  for(const reply of [()=>{throw Error("offline");},()=>({ok:false,status:400,json:async()=>({error:"bad_program"})}),()=>({ok:true,json:async()=>{throw Error("bad JSON");}})]){
    const {context:c,element,applied,timers}=fixture();c.load(c.fromConfix(doc()));const before=JSON.stringify(c.serialize());
    c.fetch=async()=>reply();await c.treeshake();
    assert.equal(applied(),0);assert.equal(JSON.stringify(c.serialize()),before);
    assert.match(element("#status").textContent,/Connections refused/);assert.equal(element("#shakeBtn").disabled,false);assert.equal(timers.size,0);
  }
});

test("pending Shake is single-flight and discards edits, renames, and identical remounts",async()=>{
  for(const change of ["edit","rename","remount"]){
    const {context:c,rename,applied,element}=fixture();c.load(c.fromConfix(doc()));let release,requests=0;
    c.fetch=()=>{requests++;return new Promise(resolve=>{release=resolve;});};
    const pending=c.treeshake();await c.treeshake();assert.equal(requests,1);assert.equal(element("#shakeBtn").disabled,true);
    if(change==="edit")c.G.nodes[1].params.value="changed";
    if(change==="rename")rename("another");
    if(change==="remount")c.load(c.fromConfix(doc()));
    release({ok:true,json:async()=>({ok:true,made:[]})});await pending;
    assert.equal(applied(),0);assert.match(element("#status").textContent,/changed during the check/);
  }
});

test("camera movement does not invalidate a matching request",async()=>{
  const {context:c,applied}=fixture();c.load(c.fromConfix(doc()));let release;
  c.fetch=()=>new Promise(resolve=>{release=resolve;});const pending=c.treeshake();
  Object.assign(c.view,{x:1000,y:-300,z:4});release({ok:true,json:async()=>({ok:true})});await pending;assert.equal(applied(),1);
});

test("Shake times out, releases its button, and preserves the graph",async()=>{
  const {context:c,element,timers,applied}=fixture();c.load(c.fromConfix(doc()));
  c.fetch=async(url,{signal})=>new Promise((resolve,reject)=>signal.addEventListener("abort",()=>{const e=Error("aborted");e.name="AbortError";reject(e);}));
  const pending=c.treeshake();for(const timeout of timers.values())timeout();await pending;
  assert.equal(applied(),0);assert.match(element("#status").textContent,/timed out/);assert.equal(element("#shakeBtn").disabled,false);
});

test("request serialization and size errors are visible refusals",async()=>{
  const {context:c,element,applied}=fixture();c.load(c.fromConfix(doc()));
  c.G.nodes[1].params.self=c.G.nodes[1].params;await c.treeshake();assert.match(element("#status").textContent,/Connections refused/);
  delete c.G.nodes[1].params.self;c.G.nodes[1].params.value="x".repeat(1048577);await c.treeshake();
  assert.match(element("#status").textContent,/payload limit/);assert.equal(applied(),0);
});

test("inspection-only survives loading and export; neither Run nor sources can execute it",async()=>{
  const {context:c,element}=fixture();c.load(c.fromConfix(doc()));
  vm.runInContext(part(panels,"async function runAll(fromId){","/* arm sources"),c);
  vm.runInContext(part(panels,"function armSources(){",'$("#runBtn").addEventListener'),c);
  assert.equal(c.serialize().controls.inspectionOnly,true);assert.equal(element("#runBtn").disabled,true);
  await c.runAll();c.armSources();assert.match(element("#status").textContent,/Inspection-only/);
  c.load({nodes:[],wires:[]});assert.equal(element("#runBtn").disabled,false);assert.equal(c.serialize().controls,undefined);
});

test("both pages consume one Shake implementation without a dormant local matcher",()=>{
  const patch=fs.readFileSync(path.join(web,"patch.js"),"utf8"),harness=fs.readFileSync(path.join(web,"harness.html"),"utf8");
  for(const source of [panels,patch])assert.doesNotMatch(source,/localTreeshake|server treeshake fallback|program:G/);
  for(const source of [panels,harness])assert.equal((source.match(/src="\/patch-shake.js"/g)||[]).length,1);
});
