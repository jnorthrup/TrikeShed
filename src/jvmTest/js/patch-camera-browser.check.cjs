"use strict";
const {chromium}=require("playwright");
const assert=require("node:assert/strict"),fs=require("node:fs/promises"),path=require("node:path"),os=require("node:os");
const base=process.env.PATCH_BASE_URL||"http://127.0.0.1:8899";

(async()=>{
  const output=await fs.mkdtemp(path.join(os.tmpdir(),"trikeshed-camera-browser-"));
  const browser=await chromium.launch({headless:true}),receipts=[];
  try{
    for(const viewport of [{width:1440,height:1000},{width:390,height:844}]){
      for(const surface of ["panels","harness"]){
        const page=await browser.newPage({viewport}),errors=[];
        page.on("pageerror",e=>errors.push(e.message));
        // Camera tests may read fixtures, never execute programs or publish edits.
        await page.route("**/*",route=>["GET","HEAD"].includes(route.request().method())?route.continue():route.fulfill({status:409,contentType:"application/json",body:'{"error":"read-only camera test"}'}));
        const name=surface==="panels"?"preset-hermes-train":"preset-curator";
        await page.goto(`${base}/${surface}?load=${name}`,{waitUntil:"domcontentloaded"});
        await page.waitForFunction(()=>typeof G!=="undefined"&&G.nodes.length>0&&typeof scopeZoomCeiling==="function"&&(typeof Harness==="undefined"||Harness.live));
        if(surface==="panels")await page.waitForFunction(()=>document.getElementById("status").textContent.startsWith("done "));
        await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
        assert.equal(await page.locator('script[src="/patch-camera.js"]').count(),1);
        assert.equal(await page.evaluate(()=>typeof Harness),surface==="panels"?"undefined":"object");
        await page.evaluate(()=>document.getElementById("stopBtn").click());
        const graph=()=>page.evaluate(()=>JSON.stringify({nodes:G.nodes.map(n=>[n.id,n.x,n.y,n._view]),wires:G.wires}));
        const before=await graph();
        const anchor=await page.evaluate(()=>{
          killMomentum();const r=viewport.getBoundingClientRect();
          const px=Math.round(r.width/2),py=Math.round(r.height/2);
          Object.assign(view,{x:px+1000,y:py+1000,z:1});applyView();
          return {x:r.left+px,y:r.top+py};
        });
        await page.mouse.move(anchor.x,anchor.y);
        for(let i=0;i<18;i++)await page.mouse.wheel(0,-1000);
        await page.waitForFunction(()=>view.z===4&&mom.zv===0);
        const root=await page.evaluate(()=>{
          const r=viewport.getBoundingClientRect();return {z:view.z,wx:(Math.round(r.width/2)-view.x)/view.z,wy:(Math.round(r.height/2)-view.y)/view.z};
        });
        assert.ok(Math.abs(root.wx+1000)<1e-6&&Math.abs(root.wy+1000)<1e-6,JSON.stringify({surface,viewport,root}));
        await page.mouse.wheel(0,100);
        await page.waitForFunction(()=>view.z<4&&mom.zv<=0);
        assert.equal(await graph(),before,"camera must not rewrite nodes, ring layouts or cables");

        const nested=await page.evaluate(()=>{
          killMomentum();fitToContent();
          const n=G.nodes.find(n=>n._childHost&&n._childHost.offsetWidth&&n._view.z<1);
          if(!n)return null;
          const r=n._childHost.getBoundingClientRect(),vp=viewport.getBoundingClientRect();
          const wx=(r.left+r.width/2-vp.left-view.x)/view.z,wy=(r.top+r.height/2-vp.top-view.y)/view.z;
          const ceiling=scopeZoomCeiling(r.left+r.width/2-vp.left,r.top+r.height/2-vp.top);
          const z=Math.min(ceiling,Math.max(4,view.z));
          Object.assign(view,{x:vp.width/2-wx*z,y:vp.height/2-wy*z,z});applyView();
          return {ceiling,wx,wy};
        });
        if(nested){
          const original=await graph();
          for(let i=0;i<18;i++)await page.mouse.wheel(0,-1000);
          await page.waitForFunction(()=>mom.zv===0);
          const z=await page.evaluate(()=>view.z);
          assert.ok(z>4,"nested scopes must remain reachable past root magnification");
          assert.ok(Math.abs(z-nested.ceiling)<1e-6);
          assert.equal(await graph(),original);
        }
        const projection=await page.evaluate(()=>{
          killMomentum();fitToContent();applyView();redraw();
          const svg=document.getElementById("wires"),p=svg.querySelector("path");
          const r=svg.getBoundingClientRect(),vp=viewport.getBoundingClientRect();
          return {width:r.width,height:r.height,viewportWidth:vp.width,viewportHeight:vp.height,
            effect:p&&getComputedStyle(p).vectorEffect,wires:svg.querySelectorAll("path").length,nodes:G.nodes.length};
        });
        assert.equal(projection.width,projection.viewportWidth);assert.equal(projection.height,projection.viewportHeight);
        if(projection.wires)assert.equal(projection.effect,"non-scaling-stroke");
        await page.screenshot({path:path.join(output,`${surface}-${viewport.width}.png`)});
        assert.deepEqual(errors,[]);
        receipts.push({surface,viewport,root,nested,projection});await page.close();
      }
    }
    console.log(JSON.stringify({output,receipts},null,2));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
