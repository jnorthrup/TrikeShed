"use strict";

// A bookmark is a view projection, never an execution command or a document edit.
const LandscapeNavigation = {
  minZoom:.01, detailZoom:4, absoluteZoom:4000,
  maxZoom(scale=1) {
    return Math.min(this.absoluteZoom,this.detailZoom/(Number.isFinite(scale)&&scale>0?scale:1));
  },
  wheelFactor(delta) {
    return Number.isFinite(delta)?Math.exp(Math.max(-.35,Math.min(.35,-delta*.0025))):1;
  },
  zoomAt(camera,requested,anchor,ceiling=this.detailZoom) {
    const z=Math.max(this.minZoom,Math.min(ceiling,this.absoluteZoom,Number.isNaN(requested)?camera.z:requested));
    const ratio=z/camera.z;
    return {x:anchor.x-(anchor.x-camera.x)*ratio,y:anchor.y-(anchor.y-camera.y)*ratio,z};
  },
  projectWires(svg,viewport,camera) {
    const width=viewport.clientWidth,height=viewport.clientHeight,{x,y,z}=camera;
    svg.setAttribute("width",width);svg.setAttribute("height",height);
    svg.setAttribute("viewBox",`${-x/z} ${-y/z} ${width/z} ${height/z}`);
    svg.style.setProperty("--wire-scale",Math.min(1,z));
    svg.style.setProperty("--wire-label-scale",1/Math.max(1,z));
    const extent={clientWidth:width,clientHeight:height};
    for(const path of svg.querySelectorAll?.("path")||[])if(path._wireCurve)this.projectCurve(path,extent,camera);
  },
  wireCurve(path,a,b,viewport,camera,controls) {
    const dx=Math.max(40,Math.abs(b.x-a.x)/2);
    path._wireCurve=[a,...(controls||[{x:a.x+dx,y:a.y},{x:b.x-dx,y:b.y}]),b];
    this.projectCurve(path,viewport,camera);
  },
  clipCurve(curve,box) {
    // Exact de Casteljau subdivision: an SVG clip alone still lets the
    // stroker tessellate millions of off-screen dashes at fractal zoom.
    const pieces=[];let budget=256;
    const mid=(a,b)=>({x:(a.x+b.x)/2,y:(a.y+b.y)/2});
    const visit=(p,depth)=>{
      if(--budget<0)return;
      const xs=p.map(p=>p.x),ys=p.map(p=>p.y);
      const left=Math.min(...xs),right=Math.max(...xs),top=Math.min(...ys),bottom=Math.max(...ys);
      if(right<box.left||left>box.right||bottom<box.top||top>box.bottom)return;
      if(left>=box.left-box.pad&&right<=box.right+box.pad&&top>=box.top-box.pad&&bottom<=box.bottom+box.pad){pieces.push(p);return;}
      if(depth===32)return;
      const a=mid(p[0],p[1]),b=mid(p[1],p[2]),c=mid(p[2],p[3]),d=mid(a,b),e=mid(b,c),f=mid(d,e);
      visit([p[0],a,d,f],depth+1);visit([f,e,c,p[3]],depth+1);
    };
    if(curve.every(p=>Number.isFinite(p.x)&&Number.isFinite(p.y)))visit(curve,0);
    return pieces;
  },
  projectCurve(path,viewport,{x,y,z}) {
    const pad=32/z,box={left:(-x-32)/z,top:(-y-32)/z,right:(viewport.clientWidth-x+32)/z,bottom:(viewport.clientHeight-y+32)/z,pad};
    let d="",last;
    for(const p of this.clipCurve(path._wireCurve,box)){
      if(!last||last.x!==p[0].x||last.y!==p[0].y)d+=`M ${p[0].x} ${p[0].y} `;
      d+=`C ${p[1].x} ${p[1].y}, ${p[2].x} ${p[2].y}, ${p[3].x} ${p[3].y} `;last=p[3];
    }
    if(path.getAttribute("d")!==d)path.setAttribute("d",d);
  },
  encode(camera, focus = "") {
    const p = new URLSearchParams({x: String(camera.x), y: String(camera.y), z: String(camera.z)});
    if (focus) p.set("focus", focus);
    return "#" + p.toString();
  },
  decode(hash) {
    const p = new URLSearchParams(hash.replace(/^#/, ""));
    if (!["x", "y", "z"].every(k => p.has(k))) return null;
    const camera = {x: Number(p.get("x")), y: Number(p.get("y")), z: Number(p.get("z"))};
    if (!Object.values(camera).every(Number.isFinite) || camera.z < this.minZoom || camera.z > this.absoluteZoom) return null;
    return {camera, focus: p.get("focus") || ""};
  },
  program(name) { return "program:" + name; },
  node(program, id) { return "node:" + JSON.stringify([program, id]); },
  object(id) { return "object:" + id; },
};
if (typeof module !== "undefined") module.exports = LandscapeNavigation;
