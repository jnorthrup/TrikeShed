"use strict";
const {test}=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs"),path=require("node:path"),vm=require("node:vm");
const web=path.resolve(__dirname,"../../commonMain/resources/web"),navigation=require(path.join(web,"landscape-navigation.js"));
const patch=fs.readFileSync(path.join(web,"patch.js"),"utf8"),panels=fs.readFileSync(path.join(web,"panels.html"),"utf8");
const controller=source=>source.slice(source.indexOf("let BLIP="),source.indexOf("function blipRender("));
const overlaps=(p,size,r)=>p.x<r.right&&p.x+size.width>r.left&&p.y<r.bottom&&p.y+size.height>r.top;

test("callout stays inside canvas and outside the pointer keepout at all edges",()=>{
  for(const bounds of [{left:12,top:98,right:1138,bottom:963},{left:12,top:98,right:378,bottom:662}]){
    const size={width:Math.min(360,bounds.right-bounds.left),height:220};
    for(let x=bounds.left;x<=bounds.right;x+=31)for(let y=bounds.top;y<=bounds.bottom;y+=29){
      const p=navigation.callout({x,y},size,bounds,null);
      assert.ok(p,JSON.stringify({bounds,x,y}));
      assert.ok(p.x>=bounds.left&&p.y>=bounds.top&&p.x+size.width<=bounds.right&&p.y+size.height<=bounds.bottom);
      assert.equal(overlaps(p,size,{left:x-28,right:x+28,top:y-28,bottom:y+28}),false);
    }
  }
});

test("callout prefers clear space beside a node and hides when no safe position fits",()=>{
  const bounds={left:12,top:60,right:1188,bottom:788},size={width:360,height:220};
  const node={left:350,top:250,right:570,bottom:460},pointer={x:390,y:270};
  const p=navigation.callout(pointer,size,bounds,node);
  assert.equal(overlaps(p,size,node),false);
  assert.equal(navigation.callout({x:100,y:100},{width:180,height:180},{left:0,top:0,right:200,bottom:200},null),null);
});

function fixture(){
  let next=0,reads=0,requests=0;
  const frames=new Map(),timers=new Map(),listeners=new Map();
  const rect={left:350,top:250,right:570,bottom:460};
  const blip={style:{display:"none",setProperty(){}},dataset:{},setAttribute(){},
    get offsetWidth(){reads++;return 360;},get offsetHeight(){reads++;return 220;}};
  const node={id:"sample",type:"literal",el:{getBoundingClientRect(){reads++;return rect;}}};
  const context=vm.createContext({document:{getElementById:()=>blip},innerWidth:1440,innerHeight:1000,
    viewport:{getBoundingClientRect(){reads++;return {left:0,top:86,right:1150,bottom:975};},addEventListener(type,fn){listeners.set(type,fn);}},
    requestAnimationFrame:fn=>{frames.set(++next,fn);return next;},cancelAnimationFrame:id=>frames.delete(id),
    setTimeout:fn=>{timers.set(++next,fn);return next;},clearTimeout:id=>timers.delete(id),
    addEventListener:(type,fn)=>listeners.set(type,fn),LandscapeNavigation:navigation,BOARD:{name:"sample"},
    api:async()=>{requests++;return {};},blipRender:()=>context.blipShow("live evidence"),
  });
  vm.runInContext(controller(patch),context);
  vm.runInContext("globalThis.state=BLIP",context);
  const event=(x,y,extra={})=>({clientX:x,clientY:y,currentTarget:node.el,pointerType:"mouse",buttons:0,...extra});
  const flush=()=>{const pending=[...frames.values()];frames.clear();pending.forEach(fn=>fn());};
  return {context,node,blip,event,frames,timers,listeners,flush,reads:()=>reads,requests:()=>requests};
}

test("pointer travel is reduced, frame-coalesced and requires no repeated layout reads",()=>{
  const f=fixture(),c=f.context;c.blipEnter(f.node,f.event(390,270));c.blipShow("live evidence");
  const anchor={...c.state.anchor},reads=f.reads();
  for(let x=391;x<=430;x++)c.blipMove(f.event(x,270));
  assert.equal(f.frames.size,1);f.flush();
  assert.equal(f.frames.size,0);assert.equal(f.reads(),reads);
  assert.match(f.blip.style.transform,new RegExp(`translate3d\\(${anchor.left+8}px,${anchor.top}px,0\\)`));
  assert.equal(f.requests(),0);
});

test("leaving or dismissing cancels pending work and stale responses cannot reopen",async()=>{
  const f=fixture(),c=f.context;let release;
  c.api=()=>new Promise(resolve=>{release=resolve;});
  c.blipEnter(f.node,f.event(390,270));
  const pending=[...f.timers.values()][0]();
  c.blipLeave();release({});await pending;
  assert.equal(f.blip.style.display,"none");assert.equal(c.state.geometry,null);
  for(const action of [()=>f.listeners.get("keydown")({key:"Escape"}),()=>f.listeners.get("wheel")(),()=>f.listeners.get("pointerdown")(),()=>f.listeners.get("resize")(),()=>f.listeners.get("blur")()]){
    c.blipEnter(f.node,f.event(390,270));c.blipShow("live evidence");c.blipMove(f.event(400,270));
    action();assert.equal(f.frames.size,0);assert.equal(f.blip.style.display,"none");
  }
});

test("touch and dragging never summon a hover callout; nested bubbling does not move it",()=>{
  const f=fixture(),c=f.context;
  c.blipEnter(f.node,f.event(390,270,{pointerType:"touch"}));assert.equal(f.timers.size,0);
  c.blipEnter(f.node,f.event(390,270,{buttons:1}));assert.equal(f.timers.size,0);
  c.blipEnter(f.node,f.event(390,270));c.blipShow("live evidence");
  c.blipMove(f.event(500,400,{currentTarget:{}}));assert.equal(c.state.x,390);assert.equal(f.frames.size,0);
  c.blipMove(f.event(400,270,{buttons:1}));assert.equal(f.blip.style.display,"none");
});

test("both construction surfaces use identical controllers and shared callout styling",()=>{
  assert.equal(controller(patch),controller(panels));
  const css=fs.readFileSync(path.join(web,"patch-camera.css"),"utf8");
  assert.match(css,/#blip\{[^}]*pointer-events:none/);
  assert.match(css,/#blip::before/);
});
