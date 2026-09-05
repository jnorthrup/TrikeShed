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
