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
    G:{nodes:[],wires:[]},
    fetch:async()=>{throw Error("unexpected fetch");},
  });
  vm.runInContext(fs.readFileSync(path.join(web,"landscape.js"),"utf8")+"\nglobalThis.landscape=Landscape;",context);
  const harness = fs.readFileSync(path.join(web,"harness.js"),"utf8").split("\nAUTOSAVE=false;")[0];
  vm.runInContext(harness+"\nglobalThis.harness=Harness;",context);
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
  const patch=fs.readFileSync(path.join(web,"patch.js"),"utf8");
  vm.runInContext(patch.slice(patch.indexOf("function applyServerTreeShake("),patch.indexOf("function localTreeshake(")),context);
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
  harness.focusElement=el=>{focused=el;};harness.fit(false);assert.equal(focused,inner.el);
  context.resizeParentFrames=node=>{resized=node;};context.save=()=>{saves++;};context.requestAnimationFrame=fn=>fn();context.fitToContent=()=>harness.fit(false);
  vm.runInContext(patch.slice(patch.indexOf("function fdLayout("),patch.indexOf("/* TREESHAKE")),context);
  const untouched=[outer,inner,other].map(n=>[n.x,n.y]);
  context.fdLayout();assert.equal(resized,inner);assert.equal(focused,inner.el);assert.equal(saves,1);
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
