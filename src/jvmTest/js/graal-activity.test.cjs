"use strict";
const {test}=require("node:test");
const assert=require("node:assert/strict");
const fs=require("node:fs");
const path=require("node:path");
const vm=require("node:vm");
const html=fs.readFileSync(path.resolve(__dirname,"../../commonMain/resources/web/graal.html"),"utf8");
function section(start,end){return html.slice(html.indexOf(start),html.indexOf(end,html.indexOf(start)));}
function fixture(){
  const timers=new Map();let timerId=0,now=1000;
  const context=vm.createContext({Map,Set,console,performance:{now:()=>now},
    setTimeout(fn,ms){const id=++timerId;timers.set(id,{fn,ms});return id;},
    clearTimeout(id){timers.delete(id);},setInterval(){},
    localStorage:{getItem:()=>null},$(){return {firstChild:{},textContent:""};},
  });
  vm.runInContext("const W=1600,H=1000;let PROJECT_DBS=new Set();"+
    section("let root=null,byId=", "/* squarified-ish treemap:")+"\n"+
    section("const heat=new Map()", "function heatOf")+
    "\nfunction pathOf(n){const p=[];while(n&&n!==root){p.unshift(n.name);n=n.parent;}return p.join('/');}"+
    section("function nodeFor(id)", "/* Seismic coalescing:")+
    "\nconst bursts=[];function quake(e,n,kind){bursts.push({e,n,kind});}"+
    section("function touchDocument(e)", "function connectSse()")+
    section("let mapTimer=", "/* R1: the JVM heap continent as treemap rows.")+"\n"+
    "globalThis.api={buildTree,touchDocument,touchCompilation,loadMap,loadHeap,requestMapRefresh,"+
    "node:id=>byId.get(id),heat:id=>heat.get(id),root:()=>root,bursts};",context);
  return {context,api:context.api,timers,advance:ms=>now+=ms};
}
const id="projects/trikeshed/build/live/classes/p/Foo.class";
test("class writes heat the tile and ancestors without a Couch commit",()=>{
  const {api}=fixture();api.buildTree([[id,42,1,1]]);
  api.touchDocument({kind:"class-update",id,phase:"compiled"});
  assert.equal(api.heat(id),1000);
  assert.equal(api.heat(id.slice(0,id.lastIndexOf('/'))),1000);
  assert.equal(api.bursts[0].kind,"build");
  assert.equal(api.node(id).seq,1,"class activity is not a fabricated store revision");
});
test("JIT activity resolves the fully qualified declaring class, not a short name",()=>{
  const {api}=fixture();const other=id.replace('/p/','/q/');api.buildTree([[id,42],[other,42]]);
  api.touchCompilation({className:"p.Foo",method:"Foo.run"});
  assert.equal(api.heat(id),1000);assert.equal(api.heat(other),undefined);
});
test("a new class retains its heat when ingestion later adds the tile",()=>{
  const {api}=fixture();api.buildTree([[id,42]]);
  const fresh=id.replace('Foo','New');api.touchDocument({kind:"class-update",id:fresh,phase:"compiled"});
  api.buildTree([[id,42],[fresh,77]]);assert.equal(api.heat(fresh),1000);
});
test("commits update causal color immediately and coalesce missing-node refreshes",()=>{
  const {api,timers}=fixture();api.buildTree([[id,42,1]]);
  api.touchDocument({kind:"commit",id,seq:99});assert.equal(api.node(id).seq,99);
  assert.equal(api.root().maxSeq,99);
  api.touchDocument({kind:"commit",id:id+'new',seq:100});
  const first=[...timers.keys()];api.touchDocument({kind:"commit",id:id+'next',seq:101});
  assert.deepEqual([...timers.keys()],first,"a burst must not postpone refresh indefinitely");
  assert.equal([...timers.values()][0].ms,750);
});
test("a stalled heap request does not delay the store map",async()=>{
  const {context,api}=fixture();const calls=[];
  context.fetch=async url=>{calls.push(url);if(url.endsWith('/heap'))return new Promise(()=>{});
    return {json:async()=>({rows:[[id,42]]})};};
  context.productRows=async()=>[];
  api.loadHeap();await api.loadMap();assert.ok(api.node(id));
  assert.equal(calls.filter(x=>x.endsWith('/heap')).length,1);
});
test("the served event switch connects both build and JIT events to tile heat",()=>{
  assert.match(html,/case 'class-update':case 'commit':touchDocument\(e\)/);
  assert.match(html,/case 'compile':touchCompilation\(e\)/);
});
