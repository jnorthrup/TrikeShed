"use strict";
/**
 * The dive: zoom into a territory you are pointing at and reach its interior.
 *
 * Two gates used to stand between an operator and the lower layer, and this check holds
 * both of them open:
 *
 *   1. Harness.observeZoom would not select the territory under the pointer until it
 *      covered 55% of the viewport AREA. Area is aspect-blind, so a tall program that
 *      already filled the view top to bottom covered only 43% of a wide viewport and
 *      demanded another 28% of zoom. Until the selection happened, nothing below it
 *      could resolve.
 *   2. Landscape.detailFor granted DOM detail only to the SELECTED program's nodes, so
 *      every other territory stayed a flat canvas fill however far you dove into it.
 *
 *   BASE_URL=http://127.0.0.1:8897 node harness-dive.check.cjs
 */
const {chromium}=require("playwright");
const assert=require("node:assert/strict"), path=require("node:path");
const base=process.env.BASE_URL||"http://127.0.0.1:8897";
const TARGET=process.env.TARGET||"preset-council";
const out=process.env.OUT_DIR||".";
const launch=process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{};

(async()=>{
  const b=await chromium.launch({headless:true,...launch});
  const p=await b.newPage({viewport:{width:1600,height:1000}});
  const errs=[]; p.on("pageerror",e=>errs.push(e.message));
  try{
    await p.goto(base+"/harness",{waitUntil:"domcontentloaded"});
    await p.waitForFunction(()=>typeof Harness!=="undefined"&&Harness.ready,null,{timeout:60000});
    await p.waitForFunction(()=>typeof G!=="undefined"&&G.nodes.length>0,null,{timeout:30000});
    // The page performs its own opening fit a few frames after ready. Driving the camera
    // before that settles measures the fit, not the dive.
    await p.waitForFunction(()=>{
      const z=view.z; if(window.__lastZ===z){window.__still=(window.__still||0)+1;} else {window.__still=0;window.__lastZ=z;}
      return window.__still>12;
    },null,{timeout:30000});

    const res=await p.evaluate(async TARGET=>{
      const frame=()=>new Promise(r=>requestAnimationFrame(()=>r()));
      const r=viewport.getBoundingClientRect();
      const a=Harness.positions.get("lcnc/program/"+TARGET);
      if(!a)throw Error("no territory "+TARGET);
      const zFill=Math.min(r.width/a.w,r.height/a.h);
      const startSelected=Harness.selected;
      view.z=zFill*0.5; view.x=r.width/2-(a.x+a.w/2)*view.z; view.y=r.height/2-(a.y+a.h/2)*view.z;
      applyView(); await frame();
      const cx=r.width/2, cy=r.height/2;
      let snappedAt=null;
      for(let i=0;i<50;i++){
        const c=scopeZoomCeiling(cx,cy);
        Object.assign(view,LandscapeNavigation.zoomAt(view,view.z*1.06,{x:cx,y:cy},c));
        Harness.observeZoom(cx,cy); applyView();
        if(!snappedAt&&Harness.selected===TARGET)snappedAt=view.z;
        if(snappedAt&&view.z>zFill*2.2)break;
        await frame();
      }
      Landscape.draw(); await frame(); Landscape.draw();
      const vr=viewport.getBoundingClientRect();
      const shown=n=>{const q=n.el.getBoundingClientRect();
        return q.width>=115&&q.height>=38&&q.right>vr.left&&q.left<vr.right&&q.bottom>vr.top&&q.top<vr.bottom;};
      const visible=G.nodes.filter(shown);
      const t0=performance.now(); for(let i=0;i<20;i++)Landscape.draw();
      return {startSelected, target:TARGET, selectedNow:Harness.selected,
        zToFillScreen:+zFill.toFixed(3), snappedAtZ:snappedAt&&+snappedAt.toFixed(3),
        overshootPastFit:snappedAt&&+(snappedAt/zFill).toFixed(2),
        onScreenAndReadable:visible.length,
        resolvedToDom:visible.filter(n=>Landscape.details.has(n.id)).length,
        fromOtherPrograms:visible.filter(n=>n._program!==Harness.selected).length,
        othersResolved:visible.filter(n=>n._program!==Harness.selected&&Landscape.details.has(n.id)).length,
        drawMsAvg:+((performance.now()-t0)/20).toFixed(2)};
    },TARGET);

    await p.screenshot({path:path.join(out,"harness-dive.png")});
    console.log(JSON.stringify({...res,pageErrors:errs},null,2));
    assert.equal(res.selectedNow,TARGET,"the dive must land on the territory under the pointer");
    assert.ok(res.snappedAtZ!==null,"it must snap at all");
    assert.ok(res.overshootPastFit<=1.05,"it must not demand zoom past the point it fills the view: "+res.overshootPastFit);
    assert.ok(res.onScreenAndReadable>0,"something must be readable after the dive");
    assert.equal(res.resolvedToDom,res.onScreenAndReadable,"every readable node on screen must resolve out of the flat fill");
    assert.ok(res.drawMsAvg<12,"the draw must stay cheap: "+res.drawMsAvg+"ms");
    assert.equal(errs.length,0,"page errors: "+errs.join(" | "));
    console.log("\nPASS — the dive lands on the pointed-at territory and its interior resolves.");
  } finally { await b.close(); }
})().catch(e=>{console.error("FAIL:",e.message);process.exit(1);});
