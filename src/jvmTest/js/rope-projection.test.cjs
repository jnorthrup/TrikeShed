"use strict";
const {test}=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs"),vm=require("node:vm"),path=require("node:path");
const web=path.resolve(__dirname,"../../commonMain/resources/web");
const source=fs.readFileSync(path.join(web,"patch.js"),"utf8");
const navigation=require(path.join(web,"landscape-navigation.js"));
test("deep camera detail lifts one covering subtree and restores the same DOM on exit",()=>{
  const camera=fs.readFileSync(path.join(web,"patch-camera.js"),"utf8");
  let moves=0;
  const container=()=>({appendChild(el){el.parentElement=this;moves++;}});
  const viewport={...container(),getBoundingClientRect:()=>({left:0,top:0,right:1000,bottom:800})};
  const node=(id,parent,inset)=>{
    const host={...container(),offsetWidth:1000,getBoundingClientRect:()=>({left:-inset,top:-inset,right:1000+inset,bottom:800+inset,width:1000+2*inset})};
    return {id,_parentScope:parent,_program:"a",el:{isConnected:true},_childHost:host,_view:{x:0,y:0,z:.25},_ringWorld:{parentElement:host,style:{}}};
  };
  const outer=node("outer",null,100),inner=node("inner",outer,10),view={x:0,y:0,z:4000};
  const context=vm.createContext({viewport,view,Harness:{selected:"a"},G:{nodes:[outer,inner]}});
  vm.runInContext(camera.slice(camera.indexOf("let cameraDetailRoot="),camera.indexOf("function applyView(){"))+"\nglobalThis.detail=()=>cameraDetailRoot;",context);
  const original=inner._ringWorld,model=JSON.stringify(inner._view);
  context.projectCameraDetail();assert.equal(context.detail(),inner);assert.equal(original.parentElement,viewport);
  context.projectCameraDetail();assert.equal(moves,1,"steady frames must not remount the subtree");
  view.z=1;context.projectCameraDetail();assert.equal(context.detail(),null);assert.equal(original.parentElement,inner._childHost);
  assert.equal(original.style.transform,"translate(0px,0px) scale(0.25)");assert.equal(JSON.stringify(inner._view),model);
  view.z=4000;outer.collapsed=true;context.projectCameraDetail();assert.equal(context.detail(),null,"a collapsed ancestor cannot authorize a lifted subtree");
  outer.collapsed=false;context.projectCameraDetail();inner.el.isConnected=false;context.G.nodes=[];context.projectCameraDetail();
  assert.equal(context.detail(),null);assert.equal(original.parentElement,inner._childHost,"removing a program must not leave orphaned portal nodes");
});

test("fractal curves submit only viewport-bounded geometry, including crossing cables",()=>{
  const viewport={clientWidth:1200,clientHeight:800};
  for(const z of [.01,1,40,400,4000]){
    const camera={x:-21000*z,y:300,z},a={x:-1e8,y:0},b={x:1e8,y:0};
    const path={attrs:{},getAttribute(k){return this.attrs[k];},setAttribute(k,v){this.attrs[k]=v;}};
    navigation.wireCurve(path,a,b,viewport,camera);
    assert.ok(path.attrs.d.includes("C"),"both ends off-screen must not discard a crossing cable");
    const box={left:(-camera.x-32)/z,top:(-camera.y-32)/z,right:(1200-camera.x+32)/z,bottom:(800-camera.y+32)/z,pad:32/z};
    const pieces=navigation.clipCurve(path._wireCurve,box);
    assert.ok(pieces.length>0&&pieces.length<=128);
    for(const piece of pieces)for(const p of piece){
      const x=p.x*z+camera.x,y=p.y*z+camera.y;
      assert.ok(x>=-64.001&&x<=1264.001&&y>=-64.001&&y<=864.001,JSON.stringify({z,x,y}));
    }
    assert.deepEqual(path._wireCurve[0],a);assert.deepEqual(path._wireCurve[3],b);
  }
});

test("wire projection preserves exact visible curves and restores culled paths on return",()=>{
  const curve=[{x:10,y:20},{x:50,y:20},{x:150,y:80},{x:200,y:80}];
  const box={left:0,top:0,right:1200,bottom:800,pad:32};
  assert.deepEqual(navigation.clipCurve(curve,box),[curve]);
  assert.deepEqual(navigation.clipCurve(curve,{...box,left:3000,right:4000}),[]);
  assert.deepEqual(navigation.clipCurve([{x:Infinity,y:0},...curve.slice(1)],box),[]);
  let writes=0;
  const path={_wireCurve:curve,d:"",getAttribute(){return this.d;},setAttribute(k,v){this.d=v;writes++;}};
  const viewport={clientWidth:1200,clientHeight:800},camera={x:0,y:0,z:1};
  navigation.projectCurve(path,viewport,camera);const original=path.d;
  navigation.projectCurve(path,viewport,camera);assert.equal(writes,1,"unchanged projections do not dirty SVG layout");
  navigation.projectCurve(path,viewport,{...camera,x:10000});assert.equal(path.d,"");
  navigation.projectCurve(path,viewport,camera);assert.equal(path.d,original);assert.equal(path._wireCurve,curve);
});

test("viewport ropes preserve world endpoints and cap screen size across fractal depths",()=>{
  const viewport={clientWidth:1200,clientHeight:800};
  const svg=()=>({parentElement:viewport,attrs:{},style:{setProperty(k,v){this[k]=v;}},setAttribute(k,v){this.attrs[k]=v;}});
  const wiresSvg=svg(),channels=svg(),view={x:-2134,y:987,z:1};
  const context=vm.createContext({LandscapeNavigation:navigation,viewport,wiresSvg,view,document:{getElementById:()=>channels}});
  vm.runInContext(source.slice(source.indexOf("function applyWireBox(){"),source.indexOf("function bez(")),context);
  for(const z of [.025,1,8,128,4096]){
    view.z=z;context.applyWireBox();
    for(const layer of [wiresSvg,channels]){
      assert.equal(layer.attrs.width,1200);assert.equal(layer.attrs.height,800);
      const [x,y,w,h]=layer.attrs.viewBox.split(" ").map(Number);
      const project=(wx,wy)=>[(wx-x)*1200/w,(wy-y)*800/h];
      assert.deepEqual(project(-view.x/z,-view.y/z),[0,0]);
      const [sx,sy]=project(123,456);
      assert.ok(Math.abs(sx-(123*z+view.x))<1e-6);
      assert.ok(Math.abs(sy-(456*z+view.y))<1e-6);
      assert.equal(layer.style['--wire-scale'],Math.min(1,z));
      assert.equal(z*layer.style['--wire-label-scale'],Math.min(1,z));
    }
  }
});

test("camera movement leaves nested layout untouched and updates the rope projection",()=>{
  const source=fs.readFileSync(path.join(web,"patch-camera.js"),"utf8");
  let projections=0;
  const style={};
  Object.defineProperty(style,"zoom",{set(){throw Error("camera must not trigger layout zoom");}});
  const view={x:-250,y:80,z:128};
  const context=vm.createContext({view,world:{style},projectCameraDetail(){},applyWireBox(){projections++;},document:{body:{classList:{toggle(){}}}}});
  const start=source.indexOf("function applyView(){"),end=source.indexOf("/* momentum",start);
  vm.runInContext(source.slice(start,end),context);
  context.applyView();
  assert.equal(style.transform,"matrix(128,0,0,128,-250,80)");
  view.x=500;view.z=.025;context.applyView();
  assert.equal(style.transform,"matrix(0.025,0,0,0.025,500,80)");
  assert.equal(projections,2);
});
