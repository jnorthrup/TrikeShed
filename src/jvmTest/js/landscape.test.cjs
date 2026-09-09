"use strict";

const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const web = path.resolve(__dirname, "../../commonMain/resources/web");
const navigation = require(path.join(web, "landscape-navigation.js"));
const patchLayout = require(path.join(web, "patch-layout.js"));
const cameraSource = fs.readFileSync(path.join(web, "patch-camera.js"), "utf8");
const shakeSource = fs.readFileSync(path.join(web, "patch-shake.js"), "utf8");

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
    URL, URLSearchParams, AbortController, TextDecoder, TextEncoder, Uint8Array, setTimeout, clearTimeout, performance,
    PatchForces:require(path.join(web,"vendor/d3-force-3.0.0.js")), LandscapeNavigation:navigation,
    G:{nodes:[],wires:[]},
    fetch:async()=>{throw Error("unexpected fetch");},
  });
  vm.runInContext(fs.readFileSync(path.join(web,"patch-layout.js"),"utf8"),context);
  vm.runInContext(fs.readFileSync(path.join(web,"landscape.js"),"utf8")+"\nglobalThis.landscape=Landscape;",context);
  const harness = fs.readFileSync(path.join(web,"harness.js"),"utf8").split("\nAUTOSAVE=false;")[0];
  vm.runInContext(harness+"\nglobalThis.harness=Harness;",context);
  vm.runInContext(shakeSource.slice(0,shakeSource.indexOf("let STARVED=")),context);
  return {context, landscape:context.landscape, harness:context.harness, element};
}

test("Shake reuses verdict rendering with program-local identities and no-op preserves nodes",async()=>{
  const {context,harness,element}=fixture();
  const node={id:"scope-demo::arg",_program:"scope-demo",el:{classList:{add(){}}}};
  context.G={nodes:[node],wires:[]};context.VERDICTS=[];context.STARVED=new Set();context.SHAKE_REACH=340;
  context.clearVerdicts=()=>{context.VERDICTS=[];};context.markPort=()=>{};context.buildVerdicts=()=>{};
  let saves=0,report;
  context.save=()=>{saves++;};context.redraw=()=>{};
  harness.showConnections=(name,result)=>{report={name,result};};
  vm.runInContext(shakeSource.slice(shakeSource.indexOf("function applyServerTreeShake(")),context);
  harness.selected="scope-demo";harness.document=()=>({nodes:[{id:"arg"}],wires:[]});
  harness.replaceSelected=()=>assert.fail("no-op must not remount nodes");
  context.fetch=async()=>({ok:true,json:async()=>({ok:true,made:[],verdicts:[{nodeId:"arg",dir:"out",port:"value",status:"binding",label:"Default binding: hello"}],starved:[]})});
  await harness.shake();
  assert.equal(saves,0);assert.equal(context.G.nodes[0],node);
  assert.equal(report.name,"scope-demo");assert.equal(report.result.verdicts[0].nodeId,node.id);
  assert.match(element("status").textContent,/No cables changed/);
  assert.match(element("status").textContent,/No required cable gaps/);
  assert.equal(element("shakeBtn").disabled,false);
  context.document.querySelectorAll=()=>[];context.portCenter=()=>null;context.CSS={escape:value=>value};
  context.applyServerTreeShake({made:[{fromNode:"source",fromPort:"value",toNode:"arg",toPort:"text"}],verdicts:[],starved:[]},false,"scope-demo");
  assert.equal(saves,1);
  assert.equal(context.G.wires[0].from[0],"scope-demo::source");
  assert.equal(context.G.wires[0].to[0],"scope-demo::arg");
});

test("Shake refuses stale results after editing or changing the selected parent",async()=>{
  for(const change of ["edit","select","parent"]){
    const {context,harness,element}=fixture();
    harness.selected="a";let revision=1,release;
    harness.document=()=>({revision});
    context.fetch=()=>new Promise(resolve=>{release=resolve;});
    context.applyServerTreeShake=()=>assert.fail("stale result applied");
    const pending=harness.shake();
    if(change==="edit")revision++;else if(change==="parent")harness.parentRevision++;else harness.selected="b";
    release({ok:true,json:async()=>({ok:true,made:[]})});
    await pending;
    assert.match(element("status").textContent,/changed during the check/);
    assert.equal(harness.shaking,false);
  }
});

function parentFixture(){
  const f=fixture(),{context,harness,element}=f;
  const node=(id,parent=null,ring=false)=>({id:"a::"+id,_localId:id,_program:"a",x:60,y:80,_parentScope:parent,
    _childHost:ring?{}:null,_view:{z:.5},children:[],el:{style:{},offsetWidth:200,offsetHeight:100,classList:{toggle(){}},}});
  const outer=node("outer",null,true),inner=node("inner",outer,true),leaf=node("leaf",inner),peer=node("peer",inner),other=node("other");
  outer.children=[inner];inner.children=[leaf,peer];context.G={nodes:[outer,inner,leaf,peer,other],wires:[]};
  harness.selected="a";harness.mounts.set("a",{x:10,y:20});harness.setParent(inner);
  context.LandscapeNavigation=navigation;context.view={x:0,y:0,z:2};context.redraw=()=>{};harness.schedule=()=>{};
  context.nodeDoc=n=>({id:n.id,type:n.type,x:n.x,y:n.y});
  const patch=fs.readFileSync(path.join(web,"patch.js"),"utf8");
  vm.runInContext(patch.slice(patch.indexOf("function ringScaleOf("),patch.indexOf("function resizeParentFrames(")),context);
  return {...f,outer,inner,leaf,peer,other,patch};
}

test("Fit, FD, Shake and drag resolve one stable parent handle",async()=>{
  const {context,harness,element,outer,inner,leaf,peer,other,patch}=parentFixture();
  assert.equal(harness.parentTarget().node,inner);
  for(const id of ["fitBtn","fdBtn","shakeBtn"])assert.match(element(id).title,/a \/ outer \/ inner/);
  let focused,saves=0,resized,request;
  harness.focusElement=el=>{focused=el;};harness.fit(false);assert.equal(focused,inner._childHost);
  context.resizeParentFrames=node=>{resized=node;};context.save=()=>{saves++;};context.requestAnimationFrame=fn=>fn();context.fitToContent=()=>harness.fit(false);
  vm.runInContext(patch.slice(patch.indexOf("async function fdLayout("),patch.indexOf("/* TREESHAKE")),context);
  harness.layoutHints=async()=>[];
  const untouched=[outer,inner,other].map(n=>[n.x,n.y]);
  await context.fdLayout();assert.equal(resized,inner);assert.equal(focused,inner._childHost);assert.equal(saves,1);
  assert.deepEqual([outer,inner,other].map(n=>[n.x,n.y]),untouched);
  harness.document=()=>({nodes:[{id:"outer",children:[{id:"inner",children:[{id:"leaf"}]}]}],wires:[{from:["other","value"],to:["leaf","x"]}]});
  context.fetch=async(url,options)=>{request=JSON.parse(options.body);return {ok:true,json:async()=>({ok:true,parentId:"inner"})};};
  let applied=false;context.applyServerTreeShake=()=>{applied=true;};await harness.shake();
  assert.equal(request.options.parentId,"inner");assert.equal(request.program.wires.length,1);assert.equal(applied,true);
  const listeners=new Map();context.addEventListener=(k,v)=>listeners.set(k,v);context.removeEventListener=k=>listeners.delete(k);
  const start={button:0,clientX:0,clientY:0,preventDefault(){},stopPropagation(){}};
  const before={x:inner.x,y:inner.y,leaf:leaf.x};harness.dragParent(start);
  listeners.get("pointermove")({clientX:20,clientY:10});listeners.get("pointerup")();
  assert.equal(inner.x,before.x+20);assert.equal(inner.y,before.y+10);assert.equal(leaf.x,before.leaf);
  assert.equal(saves,2);assert.equal(harness.parentTarget().node,inner);assert.equal(listeners.size,0);
  harness.dragParent(start,leaf);listeners.get("pointermove")({clientX:20,clientY:10});listeners.get("pointercancel")();
  assert.equal(leaf.x,before.leaf);assert.equal(saves,2);
  // Remounts replace node objects but not the selected identity.
  const replacement={...inner};context.G.nodes=context.G.nodes.map(n=>n===inner?replacement:n);
  assert.equal(harness.parentTarget().node,replacement);
  harness.selected="b";assert.equal(harness.parentTarget().handle.nodeId,null);
});

test("main-handle drag is assembly placement, not a document edit",()=>{
  const {context,harness,outer,other}=parentFixture();harness.setParent(null);
  const listeners=new Map();context.addEventListener=(k,v)=>listeners.set(k,v);context.removeEventListener=k=>listeners.delete(k);
  context.save=()=>assert.fail("main placement must not create a draft");
  const origin=harness.mounts.get("a"),local=[outer.x-origin.x,other.y-origin.y];
  harness.dragParent({button:0,clientX:0,clientY:0,preventDefault(){},stopPropagation(){}});
  listeners.get("pointermove")({clientX:20,clientY:40});listeners.get("pointerup")();
  assert.deepEqual([outer.x-origin.x,other.y-origin.y],local);assert.equal(origin.x,20);assert.equal(origin.y,40);
  const document=JSON.stringify(harness.document());
  context.view.z=.203710215;
  for(let i=0;i<3;i++){
    harness.dragParent({button:0,clientX:0,clientY:0,preventDefault(){},stopPropagation(){}});
    listeners.get("pointermove")({clientX:37,clientY:19});listeners.get("pointerup")();
    assert.equal(JSON.stringify(harness.document()),document);
  }
  outer.x+=20;
  assert.notEqual(JSON.stringify(harness.document()),document,"a genuine local move must still be an edit");
});

test("FD uses legal hints without installing cables and ignores other parents",async()=>{
  const {context,harness,inner,leaf,peer,other,patch}=parentFixture();
  let saves=0,resized;
  context.save=()=>saves++;context.resizeParentFrames=n=>resized=n;
  context.requestAnimationFrame=()=>{};
  harness.layoutHints=async()=>[
    {fromNode:"leaf",fromPort:"value",toNode:"peer",toPort:"x"},
    {fromNode:"other",fromPort:"value",toNode:"leaf",toPort:"x"},
  ];
  const otherPosition=[other.x,other.y],wires=JSON.stringify(context.G.wires);
  vm.runInContext(patch.slice(patch.indexOf("async function fdLayout("),patch.indexOf("/* TREESHAKE")),context);
  await context.fdLayout();
  assert.equal(JSON.stringify(context.G.wires),wires);assert.equal(saves,1);assert.equal(resized,inner);
  assert.deepEqual([other.x,other.y],otherPosition);
  assert.ok(peer.x>leaf.x);assert.match(context.$("#status").textContent,/1 candidate pulls/);
});

test("FD rejects a changed parent, draft, or measured frame while checking hints",async()=>{
  for(const change of ["parent","draft","frame"]){
    const {context,harness,leaf,peer,patch}=parentFixture();
    let release,doc=1;
    harness.document=()=>({doc});harness.layoutHints=()=>new Promise(resolve=>release=resolve);
    context.save=()=>assert.fail("stale layout must not save");
    vm.runInContext(patch.slice(patch.indexOf("async function fdLayout("),patch.indexOf("/* TREESHAKE")),context);
    const positions=[leaf.x,leaf.y,peer.x,peer.y],pending=context.fdLayout();
    if(change==="parent")harness.parentRevision++;else if(change==="draft")doc++;else leaf.el.offsetWidth++;
    release([]);await pending;
    assert.deepEqual([leaf.x,leaf.y,peer.x,peer.y],positions);
    assert.match(context.$("#status").textContent,/discarded/);
    assert.equal(context.$("#fdBtn").disabled,false);
  }
});

test("layout hints use the shared matcher with bounded payloads and confirmed scope",async()=>{
  const {context,harness}=fixture();let request;
  context.fetch=async(url,options)=>{request={url,...JSON.parse(options.body)};return new Response(JSON.stringify({ok:true,parentId:"inner",made:[]}));};
  await harness.layoutHints({nodes:[]},"inner");
  assert.equal(request.url,"/api/lcnc/treeshake");assert.equal(request.options.parentId,"inner");
  assert.equal(request.options.reach,Number.MAX_SAFE_INTEGER);
  context.fetch=async()=>new Response(JSON.stringify({ok:true,made:[]}));
  await assert.rejects(harness.layoutHints({nodes:[]},"inner"),/confirm/);
  context.fetch=async()=>new Response("x".repeat(2097153));
  await assert.rejects(harness.layoutHints({nodes:[]},null),/payload_limit/);
  context.fetch=()=>assert.fail("oversized input must not dispatch");
  await assert.rejects(harness.layoutHints({nodes:Array.from({length:1501},()=>({}))},null),/size budget/);
});

test("Meta-drag captures an occluded parent without retargeting or activating the covered control",()=>{
  const {context,harness,inner,leaf,other}=parentFixture();
  const viewportListeners=new Map(),windowListeners=new Map();
  context.viewport={addEventListener:(name,fn,capture)=>{assert.equal(capture,true);viewportListeners.set(name,fn);}};
  context.addEventListener=(name,fn)=>windowListeners.set(name,fn);context.removeEventListener=name=>windowListeners.delete(name);
  let saves=0,stoppedMomentum=0;
  context.save=()=>{saves++;};context.resizeParentFrames=()=>{};context.killMomentum=()=>{stoppedMomentum++;};
  const source=fs.readFileSync(path.join(web,"harness.js"),"utf8");
  vm.runInContext(source.slice(source.indexOf("let parentDragGesture="),source.indexOf("let landscapePress=")),context);
  const event=extra=>({button:0,metaKey:true,clientX:0,clientY:0,detail:1,
    target:{closest(){assert.fail("Meta-drag must not hit-test or retarget a covered node/control");}},
    preventDefault(){this.prevented=true;},stopPropagation(){},stopImmediatePropagation(){this.stopped=true;},...extra});
  const camera=JSON.stringify(context.view),positions=[inner.x,inner.y,leaf.x,other.x];
  const down=event();viewportListeners.get("pointerdown")(down);
  assert.equal(down.stopped,true);assert.equal(down.prevented,true);
  windowListeners.get("pointermove")({clientX:20,clientY:10});windowListeners.get("pointerup")();
  assert.equal(harness.parentTarget().node,inner);assert.equal(inner.x,positions[0]+20);assert.equal(inner.y,positions[1]+10);
  assert.equal(leaf.x,positions[2]);assert.equal(other.x,positions[3]);assert.equal(JSON.stringify(context.view),camera);
  assert.equal(saves,1);assert.equal(stoppedMomentum,1);
  for(const name of ["click","dblclick"]){
    const click=event({metaKey:false,detail:name==="click"?1:2});viewportListeners.get(name)(click);
    assert.equal(click.prevented,true,"releasing Meta before pointerup must not activate a covered control");assert.equal(click.stopped,true);
  }
  const keyboard=event({detail:0});viewportListeners.get("click")(keyboard);assert.equal(keyboard.stopped,undefined);
  const ordinary=event({metaKey:false,target:{closest:()=>null}});viewportListeners.get("pointerdown")(ordinary);
  assert.equal(ordinary.stopped,undefined);
  const click=event({metaKey:false});viewportListeners.get("click")(click);assert.equal(click.stopped,undefined);
  viewportListeners.get("pointerdown")(event());windowListeners.get("pointermove")({clientX:30,clientY:10});windowListeners.get("pointercancel")();
  assert.equal(inner.x,positions[0]+20);assert.equal(saves,1);assert.equal(windowListeners.size,0);
  for(const extra of [{button:2},{button:1}]){
    const down=event({...extra,target:{closest:()=>null}});viewportListeners.get("pointerdown")(down);assert.equal(down.stopped,undefined);
  }
  harness.selected=null;
  const unselected=event({target:{closest:()=>null}});viewportListeners.get("pointerdown")(unselected);
  assert.equal(unselected.stopped,undefined);
});

test("selected parent deletion is explicit and old servers cannot broaden a scoped Shake",async()=>{
  const {context,harness,element,inner}=parentFixture();
  harness.document=()=>({nodes:[]});context.fetch=async()=>({ok:true,json:async()=>({ok:true,made:[]})});
  context.applyServerTreeShake=()=>assert.fail("unconfirmed scope cannot apply");await harness.shake();
  assert.match(element("status").textContent,/Server did not confirm/);
  context.G.nodes=context.G.nodes.filter(n=>n!==inner);harness.refreshParent();
  assert.equal(harness.parentTarget().node,null);assert.match(element("status").textContent,/Selected scope removed/);
});

test("a failed Shake reports failure and releases its control",async()=>{
  const {context,harness,element}=fixture();
  harness.selected="a";harness.document=()=>({nodes:[]});
  context.fetch=async()=>({ok:false,status:409,json:async()=>({error:"validation conflict"})});
  await harness.shake();
  assert.match(element("status").textContent,/Connections refused: validation conflict/);
  assert.equal(element("shakeBtn").disabled,false);
});

test("scope sockets follow direct child declarations and retain working drag handlers",()=>{
  const {context}=fixture();
  const patch=fs.readFileSync(path.join(web,"patch.js"),"utf8");
  vm.runInContext(patch.slice(patch.indexOf("function nodeParams("),patch.indexOf("function buildNode(")),context);
  context.CONTRACTS={scope:{ins:["args?","when?"],outs:["returns"]},"scope.in":{params:{name:{v:""},default:{v:""},kind:{v:""}}}};
  const authored={name:"text"};
  assert.equal("default" in context.nodeParams("scope.in",authored),false);
  assert.equal(context.nodeParams("scope.in",{name:"text",default:""}).default,"");
  assert.equal("kind" in authored,false);
  const dom=()=>({children:[],dataset:{},classList:{add(){}},listeners:{},
    replaceChildren(){this.children=[];},append(...children){this.children.push(...children);},
    addEventListener(event,fn){this.listeners[event]=fn;}});
  context.document.createElement=dom;context.nodeKindOf=()=>"*";context.portClass=()=>"";
  const body=dom(),node={type:"scope",children:[{type:"scope.in",params:{name:"text"}},
    {type:"scope.out",params:{name:"result"}}, {type:"scope",children:[{type:"scope.in",params:{name:"private"}}]}],el:{querySelector:()=>body}};
  context.renderNodePorts(node);
  const ports=body.children.flatMap(row=>row.children.filter(child=>typeof child!=="string"));
  assert.deepEqual(ports.map(p=>p.dataset.port),["args?","when?","text","returns","result"]);
  let dragged;
  context.startWireDrag=(...args)=>{dragged=args;};
  ports[2].listeners.pointerdown({preventDefault(){},stopPropagation(){}});
  assert.equal(dragged[0],node);assert.equal(dragged[1],"in");assert.equal(dragged[2],"text");
  node.children[0].params.name="renamed";context.renderNodePorts(node);
  assert.equal(body.children.length,5);
  assert.equal(body.children[2].children[0].dataset.port,"renamed");
});

test("bookmarks round-trip distinct program, object and local scope identities",()=>{
  const camera={x:-440.5,y:900,z:.012};
  const focus=navigation.node("a:b/one","n/1");
  assert.deepEqual(navigation.decode(navigation.encode(camera,focus)),{camera,focus});
  assert.notEqual(navigation.node("a","bc"),navigation.node("ab","c"));
  assert.notEqual(navigation.program("a"),navigation.object("a"));
});

test("invalid and incomplete cameras never become navigation state",()=>{
  // The out-of-range sample tracks the ceiling instead of restating it: absoluteZoom is
  // the reach of the dive, and pinning a literal here made a wider reach look like a bug.
  const tooDeep="#x=1&y=2&z="+(navigation.absoluteZoom+1);
  for(const hash of ["", "#x=1", "#x=NaN&y=1&z=1", "#x=1&y=2&z=0", tooDeep])
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

test("detail resolves what can be read, holds through the boundary, and releases what cannot",()=>{
  const {landscape,harness}=fixture();
  const node={id:"a::one",_program:"a"};
  harness.selected="a";
  assert.equal(landscape.detailFor(node,{w:79,h:25},false),false,"distant nodes stay the flat fill");
  assert.equal(landscape.detailFor(node,{w:200,h:100},false),true,"a readable box resolves into the document");
  assert.equal(landscape.detailFor(node,{w:84,h:28},false),true,"it holds just under the acquire floor, so the boundary cannot flicker");
  // Detail used to be granted and never taken back, so a dive that resolved a ring's
  // interior left those panels in the document all the way out: 200px of chrome and 11px
  // of type painted into 20px of screen, the smear the whole board wore after one dive.
  assert.equal(landscape.detailFor(node,{w:20,h:10},false),false,"a 20px smear returns to the fill it replaced");
  assert.equal(landscape.details.size,0);
  assert.equal(landscape.detailFor(node,{w:200,h:100},true),false,"an absorbed node never resolves");
});

test("strategy detail arrives at half scale and yields to sparse overview on zoom-out",()=>{
  const {landscape,harness}=fixture();harness.selected="a";
  const node={id:"a::one",_program:"a"};
  for(const zoom of [.1,.48,.5,.48,1.7,.5,.48,.1]){
    const box={w:190*zoom,h:135*zoom};
    const detail=landscape.detailFor(node,box,false);
    assert.equal(detail,zoom!==.1,"normal nodes need not wait for close-up editing");
    assert.equal(landscape.labelFor(box,detail,false,true),false,"neither tiny overview nor resolved detail gets a duplicate label");
  }
  const bridge={w:70,h:32};
  assert.equal(landscape.labelFor(bridge,false,false,true),true,"unresolved mid-distance boxes retain orientation");
  assert.equal(landscape.labelFor(bridge,false,true,true),false,"absorbed children do not flood scope labels");
  assert.equal(landscape.labelFor(bridge,false,false,false),false,"offscreen labels stay absent");
  assert.equal(landscape.labelBudget,48);
});

test("overview label overlap and count are bounded without enabling node details",()=>{
  const {landscape,harness}=fixture();harness.selected="a";
  landscape.labelBudget=8;
  const labels=landscape.labelLayout(Array.from({length:1000},(_,i)=>({
    node:{id:"a::"+i,_program:"a",type:"node"},
    box:{x:(i%10)*80,y:40+Math.floor(i/10)*30,w:20,h:10},
    clip:{left:0,top:0,right:1000,bottom:800},
  })),text=>text.length*7);
  assert.equal(labels.length,8);assert.equal(landscape.details.size,0);
  for(let i=0;i<labels.length;i++)for(let j=i+1;j<labels.length;j++){
    const a=labels[i],b=labels[j];
    assert.equal(a.x<b.x+b.w&&a.x+a.w>b.x&&a.y<b.y+b.h&&a.y+a.h>b.y,false);
  }
});

test("Detail preference changes resolution at fixed zoom without editing the graph",()=>{
  const {context,landscape,harness,element}=fixture();harness.selected="a";
  const node={id:"a::one",_program:"a"},box={w:95,h:67};
  const camera={x:250,y:180,z:.5};context.view=camera;
  context.G.nodes=[node];let frames=0;
  landscape.schedule=()=>frames++;
  context.save=()=>assert.fail("viewer preference must not save a program");
  const preferences=new Map();context.localStorage={setItem:(key,value)=>preferences.set(key,value)};
  landscape.setDetail(0);
  assert.equal(landscape.detailFor(node,box,false),false);
  landscape.setDetail(50,false);
  assert.equal(landscape.detailFor(node,box,false),true);
  assert.equal(preferences.get("blackboard.detail"),"0","drag updates defer preference writes until change");
  landscape.setDetail(100);
  assert.equal(landscape.detailFor(node,{w:50,h:30},false),true);
  assert.equal(landscape.detailFor(node,{w:19,h:13.5},false),false,"even maximum detail leaves pixel-sized nodes in overview");
  assert.equal(element("detailLevel").value,"100");
  assert.equal(element("detailValue").textContent,"100%");
  assert.equal(preferences.get("blackboard.detail"),"100");
  assert.deepEqual(context.view,{x:250,y:180,z:.5});
  assert.equal(context.G.nodes[0],node);assert.equal(frames,3);
  for(const invalid of [null,"",NaN,Infinity,"no"]){landscape.setDetail(invalid);}
  assert.equal(frames,3);
  landscape.setDetail(500);assert.equal(landscape.detailLevel,100);
  landscape.setDetail(-10);assert.equal(landscape.detailLevel,0);
});

test("label collisions preserve an existing label and stay inside scope clipping",()=>{
  const {landscape,harness}=fixture();harness.selected="a";
  landscape.labels.set("a::held",{});
  const candidate=id=>({node:{id,_program:"a",type:"scope"},box:{x:80,y:20,w:20,h:10},clip:{left:0,top:0,right:100,bottom:18}});
  const labels=landscape.labelLayout([candidate("a::new"),candidate("a::held")],text=>text.length*7);
  assert.equal(labels.length,1);assert.equal(labels[0].id,"a::held");
  assert.ok(labels[0].x>=0&&labels[0].x+labels[0].w<=100);
  assert.equal(labels[0].y,0);
});

test("an edit in progress outlives the zoom-out that would release it",()=>{
  const {context,landscape,harness}=fixture();
  const field={};
  const node={id:"a::one",_program:"a",el:{contains:element=>element===field}};
  harness.selected="a";
  assert.equal(landscape.detailFor(node,{w:200,h:100},false),true);
  context.document.activeElement=field;
  assert.equal(landscape.detailFor(node,{w:20,h:10},false),true,"the caret keeps its panel alive however small it is drawn");
  context.document.activeElement=null;
  assert.equal(landscape.detailFor(node,{w:20,h:10},false),false,"and it leaves with the caret");
});

test("another main owning edits drops the detail the last one held",()=>{
  const {landscape,harness}=fixture();
  const node={id:"a::one",_program:"a"};
  harness.selected="a";
  assert.equal(landscape.detailFor(node,{w:200,h:100},false),true);
  harness.selected="b";
  landscape.detailFor({id:"b::one",_program:"b"},{w:200,h:100},false);
  assert.equal(landscape.details.has("a::one"),false,"the previous main's detail does not survive the handover");
  assert.equal(harness.prominent(),"b");
});

test("a deep landscape frame reads geometry once before writing visibility",()=>{
  const {context,landscape,harness}=fixture();
  let reads=0,writes=0;
  const rect=(left=0,right=600)=>({left,right,top:0,bottom:400,width:right-left,height:400});
  const read=r=>{assert.equal(writes,0,"layout read after a visibility write");reads++;return r;};
  let parent=null;
  for(let i=0;i<1000;i++){
    const node={id:"a::"+i,_program:"a",type:"scope",_parentScope:i<16?parent:null,children:[]};
    let visibility="",inert=false;
    node.el={getBoundingClientRect:()=>read(rect()),style:{
      get visibility(){return visibility;},set visibility(v){writes++;visibility=v;},
    },get inert(){return inert;},set inert(v){writes++;inert=v;}};
    if(i<16){node._childHost={getBoundingClientRect:()=>read(rect(0,100))};parent=node;}
    context.G.nodes.push(node);
  }
  const child=context.G.nodes[16];child._parentScope=parent;
  child.el.getBoundingClientRect=()=>read(rect(200,300));
  harness.selected="a";harness.ready=true;
  context.viewport={clientWidth:1000,clientHeight:800,getBoundingClientRect:()=>read({...rect(0,1000),bottom:800,height:800})};
  context.view={x:0,y:0,z:1};context.devicePixelRatio=1;context.redraw=()=>{};
  landscape.canvas={width:1000,height:800,getContext:()=>new Proxy({},{get:()=>()=>{},set:()=>true})};
  landscape.draw();
  assert.equal(reads,1017,"one read per node, scope window and viewport, independent of depth");
  assert.equal(child.el.style.visibility,"hidden","clipped children cannot override parent visibility");
  assert.equal(landscape.drawingGeometry,null,"frame geometry must not leak into later graph edits");
  reads=0;writes=0;landscape.draw();
  assert.equal(reads,1017);assert.equal(writes,0,"steady frames do not rewrite inert or visibility");
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
  const patch=cameraSource;
  let wheel,checks=0;
  context.viewport={addEventListener:(name,handler)=>{wheel=handler;},getBoundingClientRect:()=>({left:0,top:0,width:1000,height:800})};
  context.view={x:0,y:0,z:1};context.wheelPixels=e=>({dy:e.deltaY});
  context.applyView=()=>{};context.saveCameraSoon=()=>{};context.reducedMotion=true;
  vm.runInContext(patch.slice(patch.indexOf("function scopeZoomCeiling"),patch.indexOf("function applyView")),context);
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
  leaf.x=450;leaf.y=180;inner.x=360;inner.y=240;
  context.layoutRing(root,true);
  assert.deepEqual([leaf.x,leaf.y,inner.x,inner.y],[450,180,360,240]);
  for(const n of [root,outer,inner]){
    assert.ok(parseFloat(n._childHost.style.width)<=560);
    assert.ok(parseFloat(n._childHost.style.height)<=360);
  }
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
