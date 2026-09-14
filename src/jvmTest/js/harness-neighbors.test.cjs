"use strict";
const {test}=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs"),vm=require("node:vm"),path=require("node:path");
const source=fs.readFileSync(path.resolve(__dirname,"../../commonMain/resources/web/harness.js"),"utf8");

function fixture(){
  const elements=new Map(),requests=[],timers=[];
  const element=()=>({children:[],dataset:{},textContent:"",value:"middle",append(...xs){this.children.push(...xs);},replaceChildren(...xs){this.children=xs;},addEventListener(){}});
  const context=vm.createContext({AbortController,URLSearchParams,encodeURIComponent,
    $:key=>{if(!elements.has(key))elements.set(key,element());return elements.get(key);},
    setTimeout:fn=>{timers.push(fn);return timers.length;},clearTimeout(){},
    fetch:(url,options)=>new Promise(resolve=>requests.push({url,options,resolve})),
  });
  vm.runInContext(source.split("\nAUTOSAVE=false;")[0]+"\nglobalThis.harness=Harness;",context);
  const h=context.harness;h.el=(_,cls,text)=>Object.assign(element(),{className:cls,textContent:text||""});h.schedule=()=>{};
  h.epoch="board-one";h.seq=1;h.board={"listing/a":"Engineer","profile/b":"Developer"};h.neighborKey="listing/a";
  const payload=(key,revision=1,neighbors=[])=>({key,revision,epoch:h.epoch,corpus:"middle",indexedKeys:Object.keys(h.board).length,classifiedKeys:2,opaqueKeys:[],neighbors});
  const complete=(request,data,status=200)=>request.resolve({ok:status===200,status,json:async()=>data});
  return {h,elements,requests,timers,payload,complete};
}

test("a late response from the previous inspected node cannot replace the current neighborhood",async()=>{
  const {h,requests,payload,complete}=fixture();
  const old=h.loadNeighbors();h.neighborKey="profile/b";const current=h.loadNeighbors();
  assert.equal(requests[0].options.signal.aborted,true);
  complete(requests[1],payload("profile/b"));await current;
  complete(requests[0],payload("listing/a"));await old;
  assert.equal(h.neighborData.key,"profile/b");
});

test("an older snapshot is retried and deleted nodes disappear without reopening the inspector",async()=>{
  const {h,elements,requests,timers,payload,complete}=fixture();
  const first=h.loadNeighbors();h.seq=2;delete h.board["profile/b"];
  complete(requests[0],payload("listing/a",1));await first;
  assert.equal(h.neighborData,null);assert.equal(timers.length,1);
  const next=h.loadNeighbors();complete(requests[1],payload("listing/a",2));await next;
  assert.equal(h.neighborData.revision,2);assert.equal(h.neighborData.neighbors.length,0);
  delete h.board["listing/a"];h.seq=3;await h.loadNeighbors();
  assert.equal(requests.length,2);assert.equal(h.neighborData,null);
  assert.equal(elements.get("#neighborStatus").textContent,"This node was deleted.");
});

test("daemon epochs cannot reuse an unrelated result",async()=>{
  const {h,requests,payload,complete}=fixture();
  const pending=h.loadNeighbors();
  h.epoch="board-two";complete(requests[0],{...payload("listing/a"),epoch:"board-one"});await pending;
  assert.equal(h.neighborData,null);
});

test("request failures remain visible and refresh bursts are coalesced without starvation",async()=>{
  const {h,elements,requests,timers,complete}=fixture();
  const pending=h.loadNeighbors();complete(requests[0],null,503);await pending;
  assert.equal(elements.get("#neighborStatus").textContent,"Neighbors unavailable (503)");
  h.queueNeighbors();h.queueNeighbors();h.queueNeighbors();assert.equal(timers.length,1);
});
