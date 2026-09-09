"use strict";

// Both construction surfaces use this camera; navigation never edits a graph.
function scopeZoomCeiling(px,py,camera=view) {
    // Screen pixels of slop the pointer gets when aiming at a scope, so a deeply nested
    // one stays reachable after it shrinks below a pixel.
    const PICK_MIN_PX=12;
    const viewportBox=viewport.getBoundingClientRect(),wx=(px-camera.x)/camera.z,wy=(py-camera.y)/camera.z;
    const regions=new Map();
    const region=n=>{
      if(regions.has(n))return regions.get(n);
      const host=n._childHost,r=host?.getBoundingClientRect?.();
      const box=r&&host.offsetWidth>0&&r.width>0&&r.height>0&&!n.collapsed&&!n.el?.classList?.contains("collapsed")?{
        x:(r.left-viewportBox.left-view.x)/view.z,y:(r.top-viewportBox.top-view.y)/view.z,
        w:r.width/view.z,h:r.height/view.z,scale:r.width/host.offsetWidth/view.z*(n._view?.z||1),
      }:null;
      regions.set(n,box);return box;
    };
    // A scope authorises magnification only while the pointer is inside it, which is what
    // keeps the ceiling honest. But a scope four levels down measures a fraction of a pixel,
    // so nothing could ever be inside it: the ceiling it would authorise was unreachable and
    // the camera stopped at detailZoom with the recursion still a smear. Pointing is a
    // gesture with a finger's worth of slop, so a box narrower than PICK_MIN_PX is grown to
    // that width for the hit test alone. Boxes already bigger than the slop are untouched,
    // so a clipped descendant still cannot reach past its parent.
    const contains=b=>{
      if(!b)return false;
      const slop=PICK_MIN_PX/camera.z;
      const px=Math.max(0,(slop-b.w)/2),py=Math.max(0,(slop-b.h)/2);
      return wx>=b.x-px&&wy>=b.y-py&&wx<=b.x+b.w+px&&wy<=b.y+b.h+py;
    };
    let scale=1,depth=-1;
    for(const n of G.nodes){
      if(!n._childHost)continue;
      const box=region(n);if(!contains(box))continue;
      let level=0,visible=true;
      for(let p=n._parentScope;p;p=p._parentScope){level++;if(!contains(region(p))){visible=false;break;}}
        if(visible&&level>depth){scale=box.scale;depth=level;}
    }
    return LandscapeNavigation.maxZoom(scale);
}

function restoreCameraView(camera) {
  killMomentum();
  if(!camera||![camera.x,camera.y,camera.z].every(Number.isFinite)||camera.z<=0)return;
  const r=viewport.getBoundingClientRect(),anchor={x:r.width/2,y:r.height/2};
  Object.assign(view,LandscapeNavigation.zoomAt(camera,camera.z,anchor,scopeZoomCeiling(anchor.x,anchor.y,camera)));
}

let cameraDetailRoot=null;
// Zoom past which #world's single raster is visibly magnified and a covering scope is
// re-projected at its own scale instead. Below this the one world raster is still sharp.
const DETAIL_LIFT_ZOOM=8;
function restoreCameraDetail() {
  if(!cameraDetailRoot)return;
  const n=cameraDetailRoot,v=n._view;
  n._childHost.appendChild(n._ringWorld);
  n._ringWorld.style.transform=`translate(${v.x}px,${v.y}px) scale(${v.z})`;
  n._ringWorld.style.zIndex="";cameraDetailRoot=null;
}
function projectCameraDetail() {
  if(typeof Harness==="undefined")return;
  if(cameraDetailRoot&&(!cameraDetailRoot.el.isConnected||cameraDetailRoot.collapsed||cameraDetailRoot._program!==Harness.selected))restoreCameraDetail();
  const vr=viewport.getBoundingClientRect();
  const project=n=>{
    const host=n._childHost,r=host.getBoundingClientRect(),scale=r.width/host.offsetWidth,v=n._view;
    if(!Number.isFinite(scale)||scale<=0)return false;
    n._ringWorld.style.transform=`matrix(${scale*v.z},0,0,${scale*v.z},${r.left-vr.left+v.x*scale},${r.top-vr.top+v.y*scale})`;
    return true;
  };
  if(cameraDetailRoot&&!project(cameraDetailRoot))restoreCameraDetail();
  let next=null,depth=-1;
  // Chrome rasterises #world ONCE for the whole matrix and magnifies that texture, so
  // past a few multiples every glyph inside is a blown-up bitmap however modest a nested
  // scope's own scale is (the camera ceiling keeps that at detailZoom). The lift is the
  // only escape: it re-projects the scope at ITS scale, which the ceiling holds low, so
  // the raster is crisp. It used to wait for 64x, which left the whole 8x-64x band fuzzy.
  if(view.z>DETAIL_LIFT_ZOOM)for(const n of G.nodes){
    if(!n._childHost||!n.el.isConnected||n.collapsed||n._program!==Harness.selected)continue;
    const r=n._childHost.getBoundingClientRect();
    if(r.left>vr.left||r.top>vr.top||r.right<vr.right||r.bottom<vr.bottom)continue;
    let level=0,visible=true;
    for(let p=n._parentScope;p;p=p._parentScope){
      level++;const b=p._childHost.getBoundingClientRect();
      if(p.collapsed||b.left>vr.left||b.top>vr.top||b.right<vr.right||b.bottom<vr.bottom){visible=false;break;}
    }
    if(visible&&level>depth){next=n;depth=level;}
  }
  if(next===cameraDetailRoot)return;
  restoreCameraDetail();
  if(next){
    // Avoid inverse-hit-test rounding through thousands-fold magnification.
    // Only lift a window covering the entire viewport, so clipping is intact.
    cameraDetailRoot=next;viewport.appendChild(next._ringWorld);
    next._ringWorld.style.zIndex="3";project(next);
  }
}

function applyView(){
  if(typeof blipLeave==="function")blipLeave();
  // Keep geometry in the original flat world. CSS layout zoom invalidates
  // nested panel layout on every camera frame; the camera only transforms.
  // Matrix translations are numbers, not CSS lengths (which clamp around
  // 33 million px and send deeply zoomed scopes off-screen).
  world.style.transform=`matrix(${view.z},0,0,${view.z},${view.x},${view.y})`;
  projectCameraDetail();
  applyWireBox();
  if(typeof projectVerdicts==="function")projectVerdicts();
  document.body.classList.toggle("zoomed-out",view.z<.45);
  if(typeof Landscape!=="undefined")Landscape.schedule();
  if(typeof Harness!=="undefined"){
    Harness.rememberView();
    const breakout=document.getElementById("panelsBreakout");
    if(breakout)breakout.href="/panels"+(Harness.selected?"?load="+encodeURIComponent(Harness.selected):"");
  }
}
/* momentum — ported from graal.html's kinetic camera. Pan velocity in SCREEN
   px/ms (the harness camera IS a screen translate, so it applies directly);
   zoom velocity in log-scale per 16.7ms, anchored at the last wheel point so
   the glide keeps diving into the point the wheel math holds fixed. A drag
   records a low-passed flick velocity; a release within 80ms turns it into a
   glide. Frame-rate independent decay. Off under prefers-reduced-motion. */
const reducedMotion=matchMedia("(prefers-reduced-motion: reduce)").matches;
const mom={vx:0,vy:0,zv:0,ax:0,ay:0};
let momT=0,momFrame=0,panning=false;
function killMomentum(){ mom.vx=0; mom.vy=0; mom.zv=0; }
function tickMomentum(){
  momFrame=0;
  const now=performance.now(),dt=Math.min(50,now-momT); momT=now;
  if(reducedMotion){ killMomentum(); return; }
  const k=dt/16.7;
  let live=false;
  if(!panning&&(Math.abs(mom.vx)>0.002||Math.abs(mom.vy)>0.002)){
    view.x+=mom.vx*dt; view.y+=mom.vy*dt;
    const fr=Math.pow(0.93,k); mom.vx*=fr; mom.vy*=fr;
    if(Math.abs(mom.vx)<=0.002&&Math.abs(mom.vy)<=0.002){ mom.vx=0; mom.vy=0; } else live=true;
  }
  if(Math.abs(mom.zv)>0.0008){
    const f=Math.exp(mom.zv*k);
    const ceiling=scopeZoomCeiling(mom.ax,mom.ay);
    const before=view.z;
    Object.assign(view,LandscapeNavigation.zoomAt(view,view.z*f,{x:mom.ax,y:mom.ay},ceiling));
    if(view.z>before&&typeof Harness!=="undefined")Harness.observeZoom(mom.ax,mom.ay);
    if(view.z===before||view.z===ceiling||view.z===LandscapeNavigation.minZoom)mom.zv=0;
    else mom.zv*=Math.pow(0.88,k);
    if(Math.abs(mom.zv)<=0.0008) mom.zv=0; else live=true;
  }
  applyView();
  if(live) glide(); else saveCameraSoon();
}
function glide(){ if(!momFrame) momFrame=requestAnimationFrame(tickMomentum); }

viewport.addEventListener("pointerdown",e=>{
  if(e.button!==0)return;
  if(typeof Harness!=="undefined")Harness.terrainBookmark=null;
  killMomentum(); panning=true;
  viewport.classList.add("panning");
  const sx=e.clientX,sy=e.clientY,ox=view.x,oy=view.y;
  let lx=sx,ly=sy,velX=0,velY=0,velT=performance.now();
  const mv=ev=>{
    view.x=ox+ev.clientX-sx; view.y=oy+ev.clientY-sy; applyView();
    const now=performance.now(),dt=Math.max(1,now-velT);
    velX=0.75*velX+0.25*((ev.clientX-lx)/dt); velY=0.75*velY+0.25*((ev.clientY-ly)/dt); // low-passed flick velocity
    lx=ev.clientX; ly=ev.clientY; velT=now;
  };
  const up=()=>{
    panning=false; viewport.classList.remove("panning"); removeEventListener("pointermove",mv); removeEventListener("pointerup",up); saveCameraSoon();
    if(!reducedMotion&&performance.now()-velT<80){ mom.vx=velX; mom.vy=velY; momT=performance.now(); glide(); } // recent flick → glide
  };
  addEventListener("pointermove",mv); addEventListener("pointerup",up);
});
// Blackboard navigation saves only its bookmark. save() signals a graph edit,
// serializes every node and clears Shake evidence even when only the view moved.
let camSaveT=0;
function saveCameraSoon(){
  clearTimeout(camSaveT);
  if(typeof Harness!=="undefined"){Harness.rememberView();return;}
  camSaveT=setTimeout(()=>{
    save();
    const url=new URL(location.href);url.hash=LandscapeNavigation.encode(view);history.replaceState(null,"",url);
  },250);
}

/* deltaMode: 0=pixel, 1=line, 2=page. Firefox sends lines. */
function wheelPixels(e,rect){ const k=e.deltaMode===1?16:e.deltaMode===2?rect.height:1; return {dx:e.deltaX*k,dy:e.deltaY*k}; }

viewport.addEventListener("wheel",e=>{
  e.preventDefault();
  const r=viewport.getBoundingClientRect();
  // Anchor on the viewport's own box rather than a hardcoded 40px bar height,
  // so the point under the cursor stays under the cursor.
  const px=e.clientX-r.left, py=e.clientY-r.top;
  const {dy}=wheelPixels(e,r);
  // Wheel zooms, as it always has. Only the STEP changed: proportional to the
  // gesture instead of a fixed 1.1 per event, because a trackpad sends many
  // small deltas where a mouse sends few large ones — a constant step made the
  // trackpad rocket and the wheel crawl.
  const f=LandscapeNavigation.wheelFactor(dy),before=view.z;
  const ceiling=scopeZoomCeiling(px,py);
  Object.assign(view,LandscapeNavigation.zoomAt(view,view.z*f,{x:px,y:py},ceiling));
  const zoomingIn=view.z>before;
  if(zoomingIn&&typeof Harness!=="undefined")Harness.observeZoom(px,py);
  applyView(); saveCameraSoon();
  if(!reducedMotion){ // glide anchor = wheel point; the zoom keeps diving there after the gesture ends
    mom.ax=px; mom.ay=py; mom.vx=0; mom.vy=0;
    if(view.z===before||view.z===ceiling||view.z===LandscapeNavigation.minZoom)mom.zv=0;
    else {
      if(Math.sign(mom.zv)!==Math.sign(Math.log(f)))mom.zv=0;
      mom.zv=Math.max(-0.12,Math.min(0.12,mom.zv+Math.log(f)*0.28));
    }
    momT=performance.now();if(mom.zv)glide();
  }
},{passive:false});
