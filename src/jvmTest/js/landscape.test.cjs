"use strict";

const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const web = path.resolve(__dirname, "../../commonMain/resources/web");
const navigation = require(path.join(web, "landscape-navigation.js"));

function fixture() {
  const elements = new Map();
  function element(id) {
    if (!elements.has(id)) elements.set(id, {
      textContent:"", open:false, children:[], classes:new Set(),
      querySelectorAll:()=>[], setAttribute(){},
      replaceChildren(...children){this.children=children;},
      append(...children){this.children.push(...children);},
      showModal(){this.open=true;},
      classList:{toggle(){}, contains(){return false;}},
    });
    return elements.get(id);
  }
  const context = vm.createContext({
    document:{getElementById:element}, $:selector=>element(selector.slice(1)),
    URL, URLSearchParams, AbortController, TextDecoder, Uint8Array,
    fetch:async()=>{throw Error("unexpected fetch");},
  });
  vm.runInContext(fs.readFileSync(path.join(web,"landscape.js"),"utf8")+"\nglobalThis.landscape=Landscape;",context);
  const harness = fs.readFileSync(path.join(web,"harness.js"),"utf8").split("\nAUTOSAVE=false;")[0];
  vm.runInContext(harness+"\nglobalThis.harness=Harness;",context);
  return {context, landscape:context.landscape, harness:context.harness, element};
}

test("bookmarks round-trip distinct program, object and local scope identities",()=>{
  const camera={x:-440.5,y:900,z:.012};
  const focus=navigation.node("a:b/one","n/1");
  assert.deepEqual(navigation.decode(navigation.encode(camera,focus)),{camera,focus});
  assert.notEqual(navigation.node("a","bc"),navigation.node("ab","c"));
  assert.notEqual(navigation.program("a"),navigation.object("a"));
});

test("invalid and incomplete cameras never become navigation state",()=>{
  for(const hash of ["", "#x=1", "#x=NaN&y=1&z=1", "#x=1&y=2&z=0", "#x=1&y=2&z=4001"])
    assert.equal(navigation.decode(hash),null);
});

test("collapsed closures retain the original node and re-expand reversibly",()=>{
  const {context}=fixture();
  let width=300;
  const scope={el:{getBoundingClientRect:()=>({width,height:300})}};
  const child={_parentScope:scope};
  assert.equal(context.visibleClosure(child),scope);
  width=700;
  assert.equal(context.visibleClosure(child),child);
  assert.equal(child._parentScope,scope);
});

test("interactive detail sticks through zoom-out until another main owns edits",()=>{
  const {landscape,harness}=fixture();
  const node={id:"a::one",_program:"a"};
  harness.selected="a";
  assert.equal(landscape.detailFor(node,{w:200,h:100},false),true);
  assert.equal(landscape.detailFor(node,{w:20,h:10},true),true);
  harness.selected="b";
  assert.equal(landscape.detailFor(node,{w:200,h:100},false),false);
  assert.equal(landscape.details.size,0);
  assert.equal(harness.prominent(),"b");
});

test("only a zoom into a dominant main transfers editing ownership",()=>{
  const {context,harness}=fixture();
  context.viewport={getBoundingClientRect:()=>({width:1000,height:800})};
  context.view={x:0,y:0,z:1};
  harness.selected="a";
  harness.positions.set("lcnc/program/b",{x:100,y:100,w:300,h:200});
  harness.select=name=>{harness.selected=name;};
  harness.observeZoom(150,150);
  assert.equal(harness.selected,"a");
  harness.positions.set("lcnc/program/b",{x:0,y:0,w:950,h:750});
  harness.observeZoom(990,790);
  assert.equal(harness.selected,"a");
  harness.observeZoom(400,300);
  assert.equal(harness.selected,"b");
});

test("the wheel routes ownership checks only on zoom-in",()=>{
  const {context,harness}=fixture();
  const patch=fs.readFileSync(path.join(web,"patch.js"),"utf8");
  let wheel,checks=0;
  context.viewport={addEventListener:(name,handler)=>{wheel=handler;},getBoundingClientRect:()=>({left:0,top:0,width:1000,height:800})};
  context.view={x:0,y:0,z:1};context.wheelPixels=e=>({dy:e.deltaY});
  context.applyView=()=>{};context.saveCameraSoon=()=>{};context.reducedMotion=true;
  harness.observeZoom=()=>{checks++;};
  const start=patch.indexOf('viewport.addEventListener("wheel",e=>{');
  vm.runInContext(patch.slice(start,patch.indexOf('},{passive:false});',start)+19),context);
  wheel({preventDefault(){},clientX:400,clientY:300,deltaY:100});
  assert.equal(checks,0);
  wheel({preventDefault(){},clientX:400,clientY:300,deltaY:-100});
  assert.equal(checks,1);
});

test("assembly bounds are content-tight without rewriting authored coordinates",()=>{
  const {context,harness}=fixture();
  const nodes=[{_program:"a",x:10000,y:-700,el:{offsetWidth:190,offsetHeight:100}},
    {_program:"a",x:10300,y:-650,el:{offsetWidth:200,offsetHeight:80}}];
  context.G={nodes};harness.selected="a";
  const bounds=harness.bounds();
  assert.equal(bounds.left,10000);assert.equal(bounds.top,-700);
  assert.equal(bounds.w,500);assert.equal(bounds.h,130);
  assert.equal(nodes[0].x,10000);
});

test("object terrain stays outside the complete program region",()=>{
  const {landscape,harness}=fixture();
  harness.mounts.set("small",{x:0,left:0,w:300});
  harness.mounts.set("large",{x:1800,left:500,w:2400});
  landscape.positionObjects();
  assert.equal(harness.programRight(),4700);
  assert.ok(landscape.objectBox.x>4700);
});

test("scope interiors compound their scales instead of expanding every ancestor",()=>{
  const {context}=fixture();
  const patch=fs.readFileSync(path.join(web,"patch.js"),"utf8");
  vm.runInContext(patch.slice(patch.indexOf("const RING_EDGE="),patch.indexOf("/* ring chrome rides")),context);
  const leaf={type:"note",children:[],el:{offsetWidth:1000,offsetHeight:500,style:{},classList:{add(){}}}};
  const scope=child=>{
    const host={style:{}},world={style:{},appendChild(){}};
    const n={type:"scope",children:[child],_childHost:host,_ringWorld:world,_view:{x:0,y:0,z:1}};
    n.el={style:{},classList:{add(){}},get offsetWidth(){return parseFloat(host.style.width)||0;},get offsetHeight(){return (parseFloat(host.style.height)||0)+100;}};
    child._parentScope=n;return n;
  };
  const inner=scope(leaf),outer=scope(inner),root=scope(outer);
  context.layoutRing(root);
  for(const n of [root,outer,inner]){
    assert.ok(parseFloat(n._childHost.style.width)<=560);
    assert.ok(parseFloat(n._childHost.style.height)<=360);
    assert.ok(n._view.z<1);
  }
  assert.equal(context.ringScaleOf(leaf),root._view.z*outer._view.z*inner._view.z);
  assert.equal(leaf.el.offsetWidth,1000);
});

test("bounded readers cancel oversized chunked responses without content-length",async()=>{
  const {landscape}=fixture();
  let cancelled=false;
  const stream=new ReadableStream({
    start(controller){controller.enqueue(new Uint8Array(8));controller.enqueue(new Uint8Array(8));},
    cancel(){cancelled=true;},
  });
  await assert.rejects(landscape.readBytes(new Response(stream),10),/payload_limit/);
  assert.equal(cancelled,true);
  assert.equal(stream.locked,false);
  assert.equal(await landscape.readText(new Response("{}")),"{}");
});

test("version inspection reads the execution store and reuses sheet projection",async()=>{
  const {context,landscape,harness,element}=fixture();
  const cid="sha256:"+"a".repeat(64), calls=[];
  context.fetch=async(url)=>{calls.push(url);return new Response('{"nodes":[]}');};
  harness.loadSheets=async(sources,current)=>{calls.push(sources[0].url);assert.equal(current,cid);};
  await landscape.inspectCid(cid);
  assert.equal(element("factValue").textContent,'{"nodes":[]}');
  assert.deepEqual(calls,["/api/lcnc/content?cid="+encodeURIComponent(cid),"/api/lcnc/content?cid="+encodeURIComponent(cid)+"&view=sheet"]);
});

test("late version content cannot replace a newer inspection",async()=>{
  const {context,landscape,harness,element}=fixture();
  let release;
  context.fetch=()=>new Promise(resolve=>{release=resolve;});
  harness.loadSheets=async()=>assert.fail("stale content must not hydrate sheets");
  const pending=landscape.inspectCid("sha256:"+"b".repeat(64));
  harness.beginInspection("newer","test","new content");
  release(new Response('{"old":true}'));
  await pending;
  assert.equal(element("factKey").textContent,"newer");
  assert.equal(element("factValue").textContent,"new content");
});
