"use strict";
/**
 * A node is moved by its body, not by its topline.
 *
 * The header was the only handle a node had. A header is a thin strip: at half zoom a
 * scope's is a dozen screen pixels, and once the camera is inside a ring the header has
 * left the top of the screen while the interior fills it — the reported "fiddly to move,
 * the drag is ambiguous to a narrow topline". This check holds the new division open on
 * both construction surfaces:
 *
 *   body (rails, port labels, parameter margins, the band beneath)  → moves the node
 *   the ring WINDOW, and bare canvas                                → pans the camera
 *   a control (input, button, port, resize grip)                    → neither
 *
 *   PATCH_BASE_URL=http://127.0.0.1:8888 node node-drag-browser.check.cjs
 */
const {chromium}=require("playwright");
const assert=require("node:assert/strict"),fs=require("node:fs/promises"),path=require("node:path"),os=require("node:os");
const base=process.env.PATCH_BASE_URL||"http://127.0.0.1:8899";

const drag=async(page,from,dx,dy)=>{
  await page.mouse.move(from.x,from.y);
  await page.mouse.down();
  for(let i=1;i<=5;i++)await page.mouse.move(from.x+dx*i/5,from.y+dy*i/5);
  await page.mouse.up();
};

(async()=>{
  const output=await fs.mkdtemp(path.join(os.tmpdir(),"trikeshed-node-drag-"));
  const browser=await chromium.launch({headless:true}),receipts=[];
  try{
    for(const surface of ["panels","harness"]){
      // No kinetic glide: a flick that keeps sliding after pointerup would be read as
      // the next gesture's doing.
      const page=await browser.newPage({viewport:{width:1440,height:1000},reducedMotion:"reduce"}),errors=[];
      page.on("pageerror",e=>errors.push(e.message));
      // A gesture test reads fixtures; it never publishes what it dragged.
      await page.route("**/*",route=>["GET","HEAD"].includes(route.request().method())?route.continue():route.fulfill({status:409,contentType:"application/json",body:'{"error":"read-only drag test"}'}));
      const name=surface==="panels"?"preset-council":"preset-shake";
      await page.goto(`${base}/${surface}?load=${name}`,{waitUntil:"domcontentloaded"});
      await page.waitForFunction(()=>typeof G!=="undefined"&&G.nodes.length>0&&(typeof Harness==="undefined"||Harness.ready));
      if(surface==="panels")await page.waitForFunction(()=>document.getElementById("status").textContent.startsWith("done "));
      await page.waitForTimeout(1500);
      // Chrome that floats over the world would take the gesture before the node does.
      const legend=page.locator("#topologyLegend[open] > summary");
      if(await legend.count())await legend.click();
      if(await page.locator("#palette.open").count())await page.evaluate(()=>document.getElementById("paletteBtn").click());
      await page.waitForTimeout(300);

      // Frame a top-level ring so its own chrome and its window are both on screen.
      const framed=await page.evaluate(()=>{
        killMomentum();
        const n=G.nodes.find(x=>x._childHost&&!x._parentScope&&x.el.offsetWidth>0);
        if(!n)return null;
        const vp=viewport.getBoundingClientRect(),r=n.el.getBoundingClientRect();
        const wx=(r.left-vp.left-view.x)/view.z,wy=(r.top-vp.top-view.y)/view.z;
        const z=Math.min(1,(vp.width-80)/(r.width/view.z),(vp.height-80)/(r.height/view.z));
        Object.assign(view,{z,x:40-wx*z,y:40-wy*z});applyView();
        if(typeof Harness!=="undefined"){Harness.selectParent(n);Landscape.draw();}
        return {id:n.id};
      });
      assert.ok(framed,`${surface}: no top-level ring to drag`);
      await page.waitForTimeout(400);

      const state=()=>page.evaluate(id=>{
        const n=G.nodes.find(x=>x.id===id);
        return {x:n.x,y:n.y,view:{x:view.x,y:view.y,z:view.z}};
      },framed.id);
      const spot=(where,who=framed.id)=>page.evaluate(([id,where])=>{
        const n=G.nodes.find(x=>x.id===id),el=n.el;
        if(where==="body"){
          // Ask the page what a person would find: sweep the node and count the points
          // that actually reach it and are not a control, a port or the ring window.
          // The point used is the one FURTHEST from the header, so passing this cannot
          // mean "the topline still works".
          const off="button,input,textarea,select,option,a,s,.port,.node-resize,.childgrid,.result,.csheet,.ctree,.kboard,.dagchip,.ref-dive,[contenteditable]";
          const r=el.getBoundingClientRect(),head=el.querySelector(":scope > .hd").getBoundingClientRect();
          let hits=0,total=0,pick=null;
          for(let y=r.top+2;y<r.bottom-2;y+=5)for(let x=r.left+2;x<r.right-2;x+=5){
            if(x<0||y<0||x>innerWidth||y>innerHeight)continue;
            total++;
            const hit=document.elementFromPoint(x,y);
            if(!hit||!el.contains(hit))continue;
            const exempt=hit.closest(off);
            if(exempt&&el.contains(exempt))continue;
            hits++;
            if(!pick||y>pick.y)pick={x,y};
          }
          return pick&&{...pick,scale:view.z*ringScaleOf(n),sampled:total,
            // 5px sweep, so each hit stands for 25px² of reachable surface
            grabbablePx:hits*25,headerPx:+(head.width*head.height).toFixed(0),
            belowHeader:+(pick.y-head.bottom).toFixed(1)};
        }
        if(where==="window"){ // bare window: the pointer must land on the ring's own glass
          const g=n._childHost.getBoundingClientRect();
          for(let dy=6;dy<g.height-6;dy+=7)for(let dx=6;dx<g.width-6;dx+=7){
            const x=g.left+dx,y=g.top+dy;
            if(x<0||y<0||x>innerWidth||y>innerHeight)continue;
            const hit=document.elementFromPoint(x,y);
            if(hit===n._childHost||hit===n._ringWorld)return {x,y,hit:hit.className};
          }
          return null;
        }
        // Only a point that actually reaches the field proves anything about the field.
        for(const input of el.querySelectorAll(".params input,.params textarea,.params select")){
          const r=input.getBoundingClientRect();
          for(let y=r.top+2;y<r.bottom-1;y+=3)for(let x=r.left+2;x<r.right-1;x+=3){
            if(x<0||y<0||x>innerWidth||y>innerHeight)continue;
            if(document.elementFromPoint(x,y)===input)return {x,y,field:input.tagName};
          }
        }
        return null;
      },[who,where]);

      const before=await state();
      const body=await spot("body");
      assert.ok(body&&body.scale>0,`${surface}: the ring draws no chrome to grab`);
      // The point of the change: the handle is no longer the topline's worth of pixels.
      assert.ok(body.grabbablePx>=2*body.headerPx,
        `${surface}: the handle did not grow past the header — ${JSON.stringify(body)}`);
      assert.ok(body.belowHeader>0,`${surface}: the point used is still the topline — ${JSON.stringify(body)}`);
      await drag(page,body,70,45);
      const moved=await state();
      assert.ok(Math.abs(moved.x-before.x-70/body.scale)<2&&Math.abs(moved.y-before.y-45/body.scale)<2,
        `${surface}: the body must move the node — ${JSON.stringify({before,moved,scale:body.scale})}`);
      assert.deepEqual(moved.view,before.view,`${surface}: moving a node must not pan the camera`);

      const window_=await spot("window");
      assert.ok(window_,`${surface}: the ring window is covered edge to edge`);
      await drag(page,window_,-60,-35);
      const panned=await state();
      assert.ok(Math.abs(panned.view.x-moved.view.x+60)<3&&Math.abs(panned.view.y-moved.view.y+35)<3,
        `${surface}: the ring window must still pan the camera — ${JSON.stringify({moved,panned})}`);
      assert.equal(panned.x,moved.x,`${surface}: panning must not move the node`);
      assert.equal(panned.y,moved.y,`${surface}: panning must not move the node`);

      const field=await spot("input");
      if(field){
        await drag(page,field,50,30);
        const typed=await state();
        assert.equal(typed.x,panned.x,`${surface}: a parameter field must not drag its node`);
        assert.equal(typed.y,panned.y,`${surface}: a parameter field must not drag its node`);
        assert.deepEqual(typed.view,panned.view,`${surface}: a parameter field must not pan the camera`);
      }

      // A child inside a ring must still drag itself: closest() walks past the child into
      // the RING's window, and reading that as "hands off" would freeze every nested node.
      const child=await page.evaluate(id=>{
        const n=G.nodes.find(x=>x.id===id);
        killMomentum();
        const kid=(n.children||[]).filter(c=>c.el&&c.el.offsetWidth>0)
          .sort((a,b)=>b.el.offsetWidth-a.el.offsetWidth)[0];
        if(!kid)return null;
        // Dive until the child is big enough to hold DOM at all — under the readable
        // floor it is a flat fill with nothing to grab, which is a different subject.
        const vp=viewport.getBoundingClientRect();
        const z=Math.max(view.z,150/(kid.el.offsetWidth*ringScaleOf(kid)));
        const r=kid.el.getBoundingClientRect();
        const wx=(r.left-vp.left-view.x)/view.z,wy=(r.top-vp.top-view.y)/view.z;
        const w=r.width/view.z,h=r.height/view.z;
        Object.assign(view,{z,x:vp.width/2-(wx+w/2)*z,y:vp.height/2-(wy+h/2)*z});applyView();
        if(typeof Landscape!=="undefined")Landscape.draw();
        if(kid.el.style.visibility==="hidden")return null;
        return {id:kid.id,x:kid.x,y:kid.y,ring:{x:n.x,y:n.y},scale:view.z*ringScaleOf(kid)};
      },framed.id);
      if(child){
        await page.waitForTimeout(300);
        const kidBody=await spot("body",child.id);
        assert.ok(kidBody,`${surface}: nothing of ${child.id} is reachable inside its ring`);
        await drag(page,kidBody,40,25);
        const after=await page.evaluate(([kid,id])=>{
          const c=G.nodes.find(x=>x.id===kid),n=G.nodes.find(x=>x.id===id);
          return {x:c.x,y:c.y,ring:{x:n.x,y:n.y}};
        },[child.id,framed.id]);
        assert.ok(Math.abs(after.x-child.x-40/child.scale)<2&&Math.abs(after.y-child.y-25/child.scale)<2,
          `${surface}: a child inside a ring must drag itself — ${JSON.stringify({child,after})}`);
        assert.deepEqual(after.ring,child.ring,`${surface}: dragging a child must not move the ring`);
      }

      await page.screenshot({path:path.join(output,`${surface}-node-drag.png`)});
      assert.deepEqual(errors,[],`${surface}: page errors`);
      receipts.push({surface,node:framed.id,scale:+body.scale.toFixed(3),fieldTested:!!field,
        grabbablePx:body.grabbablePx,headerPx:body.headerPx,draggedFromPxBelowHeader:body.belowHeader,
        childInRingDragged:child?child.id:null});
      await page.close();
    }
    console.log(JSON.stringify({output,receipts},null,2));
    console.log("\nPASS — the body moves the node, the ring window still pans, controls do neither.");
  }finally{await browser.close();}
})().catch(e=>{console.error("FAIL:",e.message);process.exitCode=1;});
