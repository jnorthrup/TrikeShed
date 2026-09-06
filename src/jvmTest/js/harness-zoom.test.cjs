"use strict";
const {test}=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs"),vm=require("node:vm"),path=require("node:path");
const web=path.resolve(__dirname,"../../commonMain/resources/web");
const navigation=require(path.join(web,"landscape-navigation.js"));
const patch=fs.readFileSync(path.join(web,"patch-camera.js"),"utf8");

test("blackboard camera persistence never signals a document edit or clears Shake evidence",()=>{
  let remembered=0;
  const context=vm.createContext({Harness:{rememberView(){remembered++;}},clearTimeout(){},
    save(){assert.fail("camera persisted the document");},setTimeout(){assert.fail("camera scheduled a document save");}});
  vm.runInContext(patch.slice(patch.indexOf("let camSaveT="),patch.indexOf("function wheelPixels(")),context);
  context.saveCameraSoon();assert.equal(remembered,1);
});

function fixture(){
  const handlers=new Map(),frames=[],elements=new Map();let time=0,kills=0;
  const element=id=>{if(!elements.has(id))elements.set(id,{});return elements.get(id);};
  const context=vm.createContext({
    LandscapeNavigation:navigation,G:{nodes:[],wires:[]},view:{x:0,y:0,z:1},
    viewport:{getBoundingClientRect:()=>({left:0,top:40,width:1000,height:800}),addEventListener:(name,fn)=>handlers.set(name,fn)},
    $:selector=>element(selector),URL,URLSearchParams,
    killMomentum:()=>kills++,applyView(){},redraw(){},saveCameraSoon(){},
    performance:{now:()=>time},matchMedia:()=>({matches:false}),requestAnimationFrame:fn=>{frames.push(fn);return frames.length;},
  });
  const source=fs.readFileSync(path.join(web,"harness.js"),"utf8");
  vm.runInContext(patch.slice(patch.indexOf("function scopeZoomCeiling"),patch.indexOf("function applyView")),context);
  vm.runInContext(source.split("\nAUTOSAVE=false;")[0]+"\nglobalThis.harness=Harness;",context);
  context.harness.rememberView=()=>{};
  const host=(x,y,w,h,offsetWidth)=>({offsetWidth,getBoundingClientRect:()=>({
    left:x*context.view.z+context.view.x,top:y*context.view.z+context.view.y+40,
    width:w*context.view.z,height:h*context.view.z,
  })});
  const outer={id:"outer",_view:{z:.2},_childHost:host(100,100,400,300,400)};
  const inner={id:"inner",_view:{z:.1},_parentScope:outer,_childHost:host(150,150,80,60,400)};
  const installWheel=()=>{
    vm.runInContext(patch.slice(patch.indexOf("const reducedMotion="),patch.indexOf('viewport.addEventListener("pointerdown",e=>{',patch.indexOf("const reducedMotion=")))+"\nglobalThis.momentum=mom;",context);
    const start=patch.indexOf("function wheelPixels(");
    vm.runInContext(patch.slice(start),context);
  };
  const wheel=delta=>handlers.get("wheel")({preventDefault(){},clientX:500,clientY:440,deltaY:delta,deltaX:0,deltaMode:0});
  return {context,harness:context.harness,outer,inner,frames,installWheel,wheel,kills:()=>kills,tick:()=>{time+=16.7;context.tickMomentum();}};
}

test("detail magnification is bounded without flattening nested scope reach",()=>{
  const {context:c,harness,outer,inner}=fixture();c.G.nodes=[outer,inner];
  assert.equal(harness.zoomCeiling(50,50),4);
  assert.equal(harness.zoomCeiling(120,120),20);
  assert.ok(Math.abs(harness.zoomCeiling(180,180)-200)<1e-9);
  inner.collapsed=true;assert.equal(harness.zoomCeiling(180,180),20);
  outer.collapsed=true;assert.equal(harness.zoomCeiling(180,180),4);
  assert.equal(navigation.maxZoom(.000001),4000);
});

test("clipped descendants cannot authorize magnification outside their parent",()=>{
  const {context:c,harness,outer,inner}=fixture();
  inner._childHost.getBoundingClientRect=()=>({left:600,top:640,width:80,height:60});
  c.G.nodes=[outer,inner];assert.equal(harness.zoomCeiling(620,620),4);
});

test("oversized bookmarks and history preserve their center while reducing zoom",()=>{
  const {context:c,harness,kills}=fixture();
  const camera={x:-14222101.25549823,y:-5518811.140093142,z:624.1395720201809};
  const center={x:(500-camera.x)/camera.z,y:(400-camera.y)/camera.z};
  harness.restoreCamera(camera);assert.equal(c.view.z,4);assert.equal(kills(),1);
  assert.ok(Math.abs((500-c.view.x)/c.view.z-center.x)<1e-8);
  assert.ok(Math.abs((400-c.view.y)/c.view.z-center.y)<1e-8);
  harness.viewHistory.push({camera,focus:"program:retained",node:null});harness.previousView();
  assert.equal(c.view.z,4);assert.equal(harness.focusKey,"program:retained");assert.equal(kills(),2);
});

test("bookmarks inside a microscopic scope retain useful magnification",()=>{
  const {context:c,harness,outer,inner}=fixture();c.G.nodes=[outer,inner];
  const camera={x:500-180*100,y:400-180*100,z:100};
  harness.restoreCamera(camera);assert.deepEqual({...c.view},camera);
});

test("fit respects the inspected element scale and the same detail cap",()=>{
  const {context:c,harness}=fixture();
  harness.focus({x:10,y:20,w:1,h:1},"object:tiny");assert.equal(c.view.z,4);
  harness.focus({x:10,y:20,w:1,h:1},"node:deep",true,.02);assert.equal(c.view.z,200);
});

test("large wheel deltas cannot launch the camera past a bounded step or ceiling",()=>{
  const {context:c,installWheel,wheel}=fixture();installWheel();
  const graph=JSON.stringify(c.G),point={x:500,y:400};
  wheel(-100000);assert.ok(c.view.z>1&&c.view.z<=Math.exp(.35));
  assert.ok(Math.abs((500-c.view.x)/c.view.z-point.x)<1e-9);
  for(let i=0;i<100;i++)wheel(-100000);
  assert.equal(c.view.z,4);assert.equal(c.momentum.zv,0);
  const atLimit={...c.view};wheel(-100000);assert.deepEqual({...c.view},atLimit);
  for(let i=0;i<100;i++)wheel(100000);
  assert.equal(c.view.z,.01);assert.equal(c.momentum.zv,0);
  assert.equal(JSON.stringify(c.G),graph);
});

test("momentum stops at the boundary and reverse gestures immediately reverse",()=>{
  const {context:c,frames,installWheel,wheel,tick}=fixture();installWheel();
  c.view.z=3.99;Object.assign(c.momentum,{zv:.12,ax:500,ay:400});tick();
  assert.equal(c.view.z,4);assert.equal(c.momentum.zv,0);assert.equal(frames.length,0);
  c.view.z=2;c.momentum.zv=.12;wheel(20);assert.ok(c.view.z<2);assert.ok(c.momentum.zv<0);
});

test("nonfinite wheel input is inert and clamped zoom remains finite",()=>{
  for(const value of [NaN,Infinity,-Infinity])assert.equal(navigation.wheelFactor(value),1);
  const camera={x:20,y:30,z:1},anchor={x:500,y:400};
  assert.equal(navigation.zoomAt(camera,Infinity,anchor).z,4);
  assert.equal(navigation.zoomAt(camera,0,anchor).z,.01);
  assert.equal(navigation.zoomAt(camera,NaN,anchor).z,1);
});
