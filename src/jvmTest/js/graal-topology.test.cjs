"use strict";
const {test}=require("node:test");
const assert=require("node:assert/strict");
const fs=require("node:fs");
const path=require("node:path");
const vm=require("node:vm");
const web=path.resolve(__dirname,"../../commonMain/resources/web");

function fixture(){
  const context=vm.createContext({Map,Set,console,AbortController,setTimeout,clearTimeout,TextDecoder,Uint8Array,
    document:{getElementById:()=>({})},requestAnimationFrame:()=>1,fetch:async()=>({ok:true,text:async()=>"preview"})});
  vm.runInContext(fs.readFileSync(path.join(web,"graal-terrain.js"),"utf8")+"\nglobalThis.topology=GraalTopology",context);
  const draw=[];
  const ctx=new Proxy({},{get:(_,key)=> (...args)=>draw.push([key,...args]),set:()=>true});
  const terrain=context.createGraalTerrain({canvas:{getContext:()=>ctx},invalidate(){}});
  return {context,model:context.topology,terrain,draw};
}

test("path categories are explicit and unknown blobs are not invented binaries",()=>{
  const {model}=fixture();
  for(const [id,want] of [["repo/.git/objects/a","git"],["repo/.git/a.class","git"],
    ["repos/a/Foo.CLASS","classes"],["app.jar","binaries"],["native/lib.so","binaries"],
    ["opaque/abcd","other"],["source/Foo.kt","other"],["heap-live/example","other"],
    ["index/catalog.idx","other"],["objects/pack-ab12.idx","git"]])
    assert.equal(model.category([id]),want,id);
});

test("heap projections preserve measured provenance and bound each lane",()=>{
  const {model}=fixture();
  const rows=model.heapRows({atMs:123,rows:[{class:"A",count:4,bytes:32}],allocation:[{class:"A",bytes:500}]},
    {gc:{lane:{atMs:456,pools:[{pool:"old",lastUsedBytes:90,lastCommittedBytes:120,samples:3}]}}});
  assert.equal(rows.length,3);
  assert.equal(rows[0][5].source,"GC.class_histogram");
  assert.equal(rows[0][5].count,4);
  assert.equal(rows[1][5].sampledBytes,500);
  assert.equal(rows[1][5].count,undefined);
  assert.equal(rows[2][5].usedBytes,90);
  assert.equal(rows[2][5].atMs,456);
  assert.equal(new Set(rows.map(r=>r[0])).size,3);
  assert.equal(model.heapRows({allocation:[{class:"A",bytes:500}]}).some(r=>r[5].category==="live"),false);
  assert.equal(model.heapRows({rows:Array.from({length:1000},(_,i)=>({class:"A"+i,bytes:1}))}).length,256);
  assert.equal(model.heapRows({rows:[{class:"A",bytes:NaN},{class:"B",bytes:-1}]}).length,0);
});

test("masking is reversible, preserves geometry, and excludes hidden hit targets",()=>{
  const {model,terrain,draw}=fixture();
  const rows=[["a/.git/config",400],["a/X.class",400],["a/lib.jar",400],["a/readme.md",400]];
  terrain.setRows(rows,{x:0,y:0,w:1600,h:1000});
  const paint=()=>terrain.draw({s:1,ox:0,oy:0},1600,1000);paint();
  const nodes=rows.map(r=>terrain.nodeFor(r[0]));
  const rects=nodes.map(n=>({...n.rect}));
  const center=r=>[r.x+r.w/2,r.y+r.h/2];
  assert.equal(terrain.hit(...center(rects[0])).id,rows[0][0]);
  terrain.setMask(model.all&~model.bit("git"));paint();
  assert.equal(terrain.hit(...center(rects[0])),null);
  assert.equal(terrain.nodeFor(rows[0][0]),nodes[0]);
  assert.deepEqual(nodes.map(n=>({...n.rect})),rects);
  assert.equal(terrain.hit(...center(rects[1])).id,rows[1][0]);
  terrain.setMask(0);draw.length=0;paint();assert.equal(draw.length,0);
  assert.equal(terrain.hit(...center(rects[1])),null);
  terrain.setMask(model.all);paint();
  assert.equal(terrain.hit(...center(rects[0])).id,rows[0][0]);
  terrain.setRows([],{x:0,y:0,w:1600,h:1000});
  assert.equal(terrain.nodeFor(rows[0][0]),undefined);
  assert.equal(terrain.hit(...center(rects[0])),null);
});

test("runtime leaves never request blob previews or entropy content",()=>{
  const {context,model,terrain}=fixture();
  context.fetch=()=>assert.fail("runtime aggregate fetched as a blob");
  terrain.setRows(model.heapRows({rows:[{class:"Foo.java",count:1,bytes:100}]}),{x:0,y:0,w:1600,h:1000});
  terrain.setLayer(2);terrain.draw({s:1,ox:0,oy:0},1600,1000);
});

test("runtime inspection reads its measurement locally without a document request",async()=>{
  const {context,model,terrain}=fixture();
  const rows=model.heapRows({rows:[{class:"A",bytes:12,count:1}]});
  terrain.setRows(rows,{x:0,y:0,w:1600,h:1000});
  const body={};let inspection,raw;
  context.document.getElementById=()=>body;
  context.Harness={beginInspection(...args){inspection=args;},rawSheets(value){raw=value;}};
  context.fetch=()=>assert.fail("runtime measurement fetched as a document");
  vm.runInContext(fs.readFileSync(path.join(web,"landscape.js"),"utf8")+"\nglobalThis.landscape=Landscape",context);
  context.landscape.terrain=terrain;
  await context.landscape.inspect(rows[0][0]);
  assert.equal(inspection[1],"Actual heap");assert.equal(raw,true);
  assert.equal(JSON.parse(body.textContent).count,1);
});

test("heap refresh admits one request, preserves masks, and clears stale failed sources",async()=>{
  const {context}=fixture();
  context.Harness={schedule(){}};
  vm.runInContext(fs.readFileSync(path.join(web,"landscape.js"),"utf8")+"\nglobalThis.landscape=Landscape",context);
  const land=context.landscape;
  land.terrain={setRows(){},setMask(){}};land.mask=1;
  land.storeRows=[["a/.git/config",42]];
  land.readText=async r=>JSON.stringify(r.data);
  const calls=[];let release;
  context.fetch=path=>{calls.push(path);return path.endsWith("/heap")?new Promise(r=>{release=r;}):
    Promise.resolve({ok:true,data:{gc:{lane:{pools:[{pool:"old",lastUsedBytes:70}]}}}});};
  const pending=land.refreshHeap();await land.refreshHeap();assert.equal(calls.length,1);
  release({ok:true,data:{allocation:[{class:"A",bytes:2}]}});await pending;
  assert.equal(land.rows.length,3);assert.equal(land.mask,1);
  assert.match(land.heapStatus,/Live histogram unavailable/);
  context.fetch=async()=>({ok:false,status:503});await land.refreshHeap();
  assert.equal(land.runtimeRows.length,0);assert.equal(land.rows.length,1);
  assert.match(land.heapStatus,/503/);assert.match(land.poolStatus,/503/);
  assert.equal(land.heapBusy,false);
  context.fetch=async()=>({ok:true,data:null});await land.refreshHeap();
  assert.equal(land.runtimeRows.length,0);assert.equal(land.heapBusy,false);
  assert.match(land.heapStatus,/invalid measurement payload/);
});

test("slow runtime diagnostics do not hold up blackboard startup",async()=>{
  const {context}=fixture();
  context.Harness={schedule(){}};
  vm.runInContext(fs.readFileSync(path.join(web,"landscape.js"),"utf8")+"\nglobalThis.landscape=Landscape",context);
  const land=context.landscape;let started=false;
  land.initLegend=()=>{};land.positionObjects=()=>{};land.updateTerrain=()=>{};land.terrain={};
  land.refreshHeap=()=>{started=true;return new Promise(()=>{});};
  context.fetch=async()=>({ok:true,json:async()=>({rows:[]})});
  await land.refresh();assert.equal(started,true);
});
