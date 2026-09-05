"use strict";
const {test}=require("node:test");
const assert=require("node:assert/strict");
const fs=require("node:fs");
const path=require("node:path");
const vm=require("node:vm");
const patch=fs.readFileSync(path.resolve(__dirname,"../../commonMain/resources/web/patch.js"),"utf8");

function fixture(scope=false){
  const listeners=new Map(),capture=new Map();
  const element=(w=200,h=100)=>({style:{cssText:"original"},offsetWidth:w,offsetHeight:h,classList:{toggle(){}}});
  const parent={_view:{z:.5},_parentScope:{_view:{z:.25}}};
  const n={id:"a::scope",type:scope?"scope":"text",x:20,y:30,el:element(),_parentScope:parent,_view:{x:0,y:0,z:1}};
  if(scope){n._childHost=element();n._ringWorld=element();n._ringWorld.style.width="400px";n._ringWorld.style.height="200px";}
  const context=vm.createContext({G:{nodes:[n]},view:{z:2},redraw(){},
    Harness:{parentRevision:0,selectParent(){},schedule(){}},save(){context.saves++;},saves:0,
    addEventListener:(type,fn)=>listeners.set(type,fn),removeEventListener:type=>listeners.delete(type)});
  vm.runInContext(patch.slice(patch.indexOf("const NODE_FRAMES="),patch.indexOf("function layoutRing(")),context);
  vm.runInContext(patch.slice(patch.indexOf("function ringScaleOf("),patch.indexOf("// Resize only")),context);
  context.resizeParentFrames=node=>{context.resized=node;};
  context.applyRingView=node=>{node._childHost.style.width=node._frame.w+"px";};
  const handle={setPointerCapture:id=>capture.set(id,true),hasPointerCapture:id=>capture.has(id),releasePointerCapture:id=>capture.delete(id),addEventListener:(type,fn)=>capture.set(type,fn),removeEventListener:type=>capture.delete(type)};
  const start={button:0,pointerId:7,currentTarget:handle,clientX:0,clientY:0,preventDefault(){},stopPropagation(){}};
  return {context,n,parent,listeners,capture,start};
}

test("corner resizing uses compounded zoom without moving nodes or editing programs",()=>{
  const {context,n,parent,listeners,capture,start}=fixture();
  context.resizeNode(start,n);
  listeners.get("pointermove")({pointerId:9,clientX:100,clientY:100});assert.equal(n._frame,undefined);
  listeners.get("pointermove")({pointerId:7,clientX:20,clientY:10});
  assert.equal(n._frame.w,280);assert.equal(n._frame.h,140);
  assert.equal(n.x,20);assert.equal(n.y,30);assert.equal(context.resized,undefined);
  listeners.get("pointerup")({pointerId:7});
  assert.equal(context.resized,parent);assert.equal(context.saves,0);
  assert.equal(listeners.size,0);assert.equal(capture.size,0);
  assert.equal(vm.runInContext('NODE_FRAMES.get("a::scope").w',context),280);
});

test("scope resizing fits its existing world and bounds presentation work",()=>{
  const {context,n,listeners,start}=fixture(true);
  context.resizeNode(start,n);
  listeners.get("pointermove")({pointerId:7,clientX:50,clientY:25});
  assert.equal(n._frame.w,400);assert.equal(n._frame.h,200);assert.equal(n._view.z,1);
  assert.equal(n._ringWorld.style.width,"400px");
  listeners.get("pointermove")({pointerId:7,clientX:1e9,clientY:-1e9});
  assert.equal(n._frame.w,1600);assert.equal(n._frame.h,80);assert.equal(n._view.z,.4);
  listeners.get("pointerup")({pointerId:7});assert.equal(context.saves,0);
});

test("Escape, capture loss, cancellation and stale ownership restore the prior frame",()=>{
  for(const reason of ["escape","lost","cancel","parent","remount"]){
    const {context,n,listeners,capture,start}=fixture(true);
    n._frame={w:220,h:110};const before=n._frame;
    context.resizeNode(start,n);listeners.get("pointermove")({pointerId:7,clientX:20,clientY:20});
    if(reason==="escape")listeners.get("keydown")({key:"Escape",preventDefault(){}});
    if(reason==="lost")capture.get("lostpointercapture")();
    if(reason==="cancel")listeners.get("pointercancel")();
    if(reason==="parent"){context.Harness.parentRevision++;listeners.get("pointerup")({pointerId:7});}
    if(reason==="remount"){context.G.nodes=[];listeners.get("pointerup")({pointerId:7});}
    assert.equal(n._frame,before);assert.equal(n.el.style.cssText,"original");assert.equal(n._view.z,1);
    assert.equal(context.saves,0);assert.equal(context.resized,undefined);
    assert.equal(listeners.size,0);assert.equal(capture.size,0);
    assert.equal(vm.runInContext("NODE_FRAMES.size",context),0);
  }
});

test("click without movement and nonprimary pointer cannot resize",()=>{
  const {context,n,listeners,start}=fixture();
  context.resizeNode({...start,button:2},n);assert.equal(listeners.size,0);
  context.resizeNode(start,n);listeners.get("pointermove")({pointerId:7,clientX:1,clientY:1});
  listeners.get("pointerup")({pointerId:7});assert.equal(n._frame,undefined);assert.equal(context.saves,0);
});

test("collapse is reversible document state with accurate accessible controls",()=>{
  const {context,n}=fixture();const attrs={};const button={setAttribute:(key,value)=>attrs[key]=value};
  context.toggleNodeCollapsed(n,button);
  assert.equal(n.collapsed,true);assert.equal(attrs["aria-expanded"],"false");assert.equal(attrs["aria-label"],"Expand node");
  context.toggleNodeCollapsed(n,button);
  assert.equal(n.collapsed,false);assert.equal(attrs["aria-expanded"],"true");assert.equal(attrs["aria-label"],"Collapse node");
  assert.equal(context.saves,2);
  assert.match(patch,/class="node-collapse"/);assert.match(patch,/class="node-delete"/);
  assert.doesNotMatch(patch,/title="collapse">▤/);
});
