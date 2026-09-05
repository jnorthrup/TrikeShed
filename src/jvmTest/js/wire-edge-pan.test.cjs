"use strict";
const {test}=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs"),path=require("node:path"),vm=require("node:vm");
const source=fs.readFileSync(path.resolve(__dirname,"../../commonMain/resources/web/patch.js"),"utf8");
function fixture(z=1){
  const listeners=new Map(),frames=new Map();let clock=0,id=0;
  const element=()=>({isConnected:true,dataset:{},classList:{add(){},remove(){},toggle(){},contains:()=>false},querySelectorAll:()=>[],setAttribute(k,v){this[k]=v;},remove(){this.removed=true;}});
  const rect={left:0,top:40,right:800,bottom:600},viewport={...element(),getBoundingClientRect:()=>rect,contains:t=>t===viewport};
  const n={id:"source",type:"text",el:element(),_program:"a"};
  const context=vm.createContext({view:{x:0,y:0,z},G:{nodes:[n],wires:[]},dragWire:null,Harness:{parentRevision:1},innerWidth:1000,innerHeight:700,
    performance:{now:()=>clock},viewport,world:{getBoundingClientRect:()=>({left:context.view.x,top:40+context.view.y})},
    document:{hidden:false,body:element(),createElementNS:element,querySelectorAll:()=>[],elementFromPoint:()=>viewport,
      addEventListener:(k,v)=>listeners.set("doc:"+k,v),removeEventListener:k=>listeners.delete("doc:"+k)},
    wiresSvg:{appendChild(){},insertBefore(){}},$:()=>({}),nodeKindOf:()=>"text",KINDCOLOR:{},
    portCenter:()=>({x:100,y:100}),bez:(a,b)=>JSON.stringify([a,b]),growWireBox:()=>false,applyWireBox(){},
    redraw(){},killMomentum(){context.killed++;},killed:0,applyView(){context.applied++;},applied:0,
    saveCameraSoon(){context.cameraSaves++;},cameraSaves:0,save(){context.saves++;},saves:0,
    scopeAtTarget:()=>null,showMateMenu(...args){context.menu=args;},
    addEventListener:(k,v)=>listeners.set(k,v),removeEventListener:k=>listeners.delete(k),
    requestAnimationFrame:fn=>{frames.set(++id,fn);return id;},cancelAnimationFrame:i=>frames.delete(i),
  });
  vm.runInContext(source.slice(source.indexOf("function wireEdgeVelocity("),source.indexOf("/* the mate popup:")),context);
  const event=(x=400,y=300,extra={})=>({clientX:x,clientY:y,button:0,buttons:1,pointerId:7,preventDefault(){},stopPropagation(){},...extra});
  const start=(...args)=>context.startWireDrag(n,"out","text",event(...args));
  const emit=(type,...args)=>listeners.get(type)?.(event(...args));
  const step=ms=>{clock+=ms;const pending=[...frames.values()];frames.clear();pending.forEach(fn=>fn(clock));};
  return {context,n,listeners,frames,rect,start,emit,step};
}
test("margin speed ramps smoothly, caps diagonals and ignores outside or invalid points",()=>{
  const {context:c,rect:r}=fixture(),v=(x,y)=>c.wireEdgeVelocity({x,y},r);
  assert.equal(v(400,300).x,0);assert.equal(v(744,300).x,0);
  assert.equal(v(772,300).x,-80);assert.equal(v(800,300).x,-320);
  assert.equal(v(0,300).x,320);assert.equal(v(400,40).y,320);assert.equal(v(400,600).y,-320);
  assert.ok(Math.hypot(v(800,600).x,v(800,600).y)<=320.000001);
  for(const p of [[801,300],[400,39],[NaN,300],[Infinity,300]])assert.equal(Math.hypot(v(...p).x,v(...p).y),0);
  assert.equal(c.wireEdgeVelocity({x:50,y:50},{left:0,right:100,top:0,bottom:100}).x,0);
});
test("stationary edge drag pans in screen pixels at low and high zoom and keeps endpoint anchored",()=>{
  for(const z of [.01,1,100]){
    const {context:c,start,step}=fixture(z);start(800,300);step(1000);
    assert.equal(c.view.x,-16);assert.equal(c.view.z,z);assert.equal(c.saves,0);
    const points=JSON.parse(c.dragWire.path.d);assert.equal(points[1].x*z+c.view.x,800);
    step(16);assert.ok(c.view.x<-16);c.dragWire.cancel();assert.equal(c.cameraSaves,1);
  }
});
test("center, outside and overlay margins pause without inertia",()=>{
  const {context:c,start,step,emit}=fixture();start();step(16);assert.equal(c.view.x,0);
  emit("pointermove",800,300);step(16);const x=c.view.x;assert.ok(x<0);
  emit("pointermove",400,300);step(16);assert.equal(c.view.x,x);
  emit("pointermove",801,300);step(16);assert.equal(c.view.x,x);
  emit("pointermove",800,300);c.document.elementFromPoint=()=>({});step(16);assert.equal(c.view.x,x);
});
test("cancel, Escape, blur, hidden page, remount and selection changes clean up without creating sockets",()=>{
  for(const reason of ["pointercancel","keydown","blur","hidden","remount","selection","buttons"]){
    const {context:c,n,start,step,emit,listeners,frames}=fixture();start(800,300);step(16);const p=c.dragWire.path;
    if(reason==="hidden"){c.document.hidden=true;listeners.get("doc:visibilitychange")();}
    else if(reason==="remount"){n.el.isConnected=false;step(16);}
    else if(reason==="selection"){c.Harness.parentRevision++;step(16);}
    else if(reason==="buttons")emit("pointermove",800,300,{buttons:0});
    else emit(reason,800,300,{key:"Escape"});
    assert.equal(c.dragWire,null,reason);assert.equal(frames.size,0,reason);assert.equal(listeners.size,0,reason);
    assert.equal(p.removed,true);assert.equal(c.saves,0);assert.equal(c.menu,undefined);
  }
});
test("only the initiating pointer can move or release; outside release never opens creation",()=>{
  const {context:c,start,step,emit}=fixture();start();emit("pointermove",800,300,{pointerId:8});step(16);assert.equal(c.view.x,0);
  emit("pointerup",400,300,{pointerId:8});assert.ok(c.dragWire);
  emit("pointercancel",400,300,{pointerId:8});assert.ok(c.dragWire);
  emit("pointerup",900,300);assert.equal(c.dragWire,null);assert.equal(c.menu,undefined);
});
test("empty canvas release uses the panned world location and stops the animation",()=>{
  const {context:c,start,step,emit,frames}=fixture(2);start(800,300);step(50);emit("pointerup",780,300);
  assert.equal(c.menu[4],(780-c.view.x)/2);assert.equal(c.menu[5],(300-40-c.view.y)/2);
  assert.equal(frames.size,0);assert.equal(c.dragWire,null);assert.equal(c.saves,0);
});
test("secondary buttons cannot start a wire gesture",()=>{
  const {context:c,start,frames}=fixture();start(400,300,{button:2});assert.equal(c.dragWire,null);assert.equal(frames.size,0);
});
test("creation chooser stays inside desktop and mobile window margins",()=>{
  for(const [width,height] of [[1280,720],[390,844]]){
    const c=vm.createContext({innerWidth:width,innerHeight:height,menu:{style:{},offsetWidth:300,offsetHeight:360}});
    vm.runInContext(source.slice(source.indexOf("function positionCreationMenu("),source.indexOf("function showMenu(")),c);
    c.positionCreationMenu(width-1,height-1);
    assert.equal(parseFloat(c.menu.style.left)+300,width-8);
    assert.equal(parseFloat(c.menu.style.top)+360,height-8);
    c.positionCreationMenu(-100,-100);assert.equal(c.menu.style.left,"8px");assert.equal(c.menu.style.top,"8px");
  }
});
