"use strict";
const {test}=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs"),vm=require("node:vm"),path=require("node:path");
const web=path.resolve(__dirname,"../../commonMain/resources/web");
const source=fs.readFileSync(path.join(web,"patch.js"),"utf8");
test("viewport ropes preserve world endpoints and cap screen size across fractal depths",()=>{
  const viewport={clientWidth:1200,clientHeight:800};
  const svg=()=>({parentElement:viewport,attrs:{},style:{setProperty(k,v){this[k]=v;}},setAttribute(k,v){this.attrs[k]=v;}});
  const wiresSvg=svg(),channels=svg(),view={x:-2134,y:987,z:1};
  const context=vm.createContext({viewport,wiresSvg,view,document:{getElementById:()=>channels}});
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
  let projections=0;
  const style={};
  Object.defineProperty(style,"zoom",{set(){throw Error("camera must not trigger layout zoom");}});
  const view={x:-250,y:80,z:128};
  const context=vm.createContext({view,world:{style},applyWireBox(){projections++;},document:{body:{classList:{toggle(){}}}}});
  const start=source.indexOf("function applyView(){"),end=source.indexOf("/* momentum",start);
  vm.runInContext(source.slice(start,end),context);
  context.applyView();
  assert.equal(style.transform,"translate(-250px,80px) scale(128)");
  view.x=500;view.z=.025;context.applyView();
  assert.equal(style.transform,"translate(500px,80px) scale(0.025)");
  assert.equal(projections,2);
});
