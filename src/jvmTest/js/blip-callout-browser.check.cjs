"use strict";
const {chromium}=require("playwright"),assert=require("node:assert/strict");
const fs=require("node:fs/promises"),path=require("node:path"),os=require("node:os");
const base=process.env.PATCH_BASE_URL||"http://127.0.0.1:8888";

(async()=>{
  const output=await fs.mkdtemp(path.join(os.tmpdir(),"trikeshed-blip-callout-")),receipts=[];
  const browser=await chromium.launch({headless:true});
  try{
    for(const screen of [{width:1440,height:1000},{width:390,height:844}])for(const surface of ["harness","panels"]){
      console.log(`Checking ${surface} ${screen.width}`);
      const page=await browser.newPage({viewport:screen}),errors=[];let requests=0;
      page.on("pageerror",e=>errors.push(e.message));
      page.on("request",r=>{if(new URL(r.url()).pathname==="/api/lcnc/blip")requests++;});
      await page.route("**/*",route=>["GET","HEAD"].includes(route.request().method())?route.continue():
        route.fulfill({status:409,contentType:"application/json",body:'{"error":"read-only callout check"}'}));
      await page.goto(`${base}/${surface}?load=preset-curator`,{waitUntil:"domcontentloaded"});
      await page.waitForFunction(()=>typeof G!=="undefined"&&G.nodes.length&&typeof blipShow==="function"&&
        (typeof Harness==="undefined"||Harness.live));
      if(surface==="panels")await page.waitForFunction(()=>document.getElementById("status").textContent.startsWith("done "));
      await page.evaluate(()=>{
        document.getElementById("topologyLegend")?.removeAttribute("open");
        if(document.getElementById("palette")?.classList.contains("open"))document.getElementById("paletteBtn").click();
        document.getElementById("stopBtn").click();killMomentum();fitToContent();
      });
      await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
      const nodeId=await page.evaluate(()=>{
        const nodes=G.nodes.filter(n=>!n._childHost&&(typeof Harness==="undefined"||n._program===Harness.selected));
        const n=nodes.find(n=>n.type==="literal")||nodes[0];
        if(!n)throw Error("No leaf node in selected program");
        const r=n.el.getBoundingClientRect(),vp=viewport.getBoundingClientRect(),hd=n.el.querySelector(".hd").getBoundingClientRect();
        const wx=(r.left+r.width/2-vp.left-view.x)/view.z,wy=(hd.top+hd.height/2-vp.top-view.y)/view.z;
        const z=Math.min(4000,view.z*220/r.width);
        Object.assign(view,{z,x:vp.width/2-wx*z,y:vp.height*.4-wy*z});applyView();redraw();
        return n.id;
      });
      await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
      const pointer=await page.evaluate(id=>{
        const n=G.nodes.find(n=>n.id===id),r=n.el.getBoundingClientRect(),hd=n.el.querySelector(".hd").getBoundingClientRect();
        return {x:Math.round(r.left+50),y:Math.round(hd.top+hd.height/2)};
      },nodeId);
      await page.mouse.move(1,1);await page.mouse.move(pointer.x,pointer.y);
      try{
        await page.waitForFunction(id=>BLIP.node?.id===id&&blipEl().style.display==="block"&&blipEl().style.visibility==="visible",nodeId,{timeout:15000});
      }catch(e){
        console.error(await page.evaluate(({nodeId,pointer})=>({nodeId,pointer,actual:BLIP.node?.id,
          hit:document.elementFromPoint(pointer.x,pointer.y)?.outerHTML.slice(0,400),geometry:BLIP.geometry,
          display:blipEl().style.display,visibility:blipEl().style.visibility,text:blipEl().textContent}),{nodeId,pointer}));
        await page.screenshot({path:path.join(output,`${surface}-${screen.width}-failure.png`)});throw e;
      }
      const snapshot=()=>page.evaluate(()=>{
        const b=blipEl(),r=b.getBoundingClientRect(),vp=viewport.getBoundingClientRect();
        return {x:r.x,y:r.y,width:r.width,height:r.height,bounds:{left:vp.left,top:vp.top,right:vp.right,bottom:vp.bottom},
          pointerEvents:getComputedStyle(b).pointerEvents,side:b.dataset.side,frame:BLIP.frame,text:b.textContent};
      });
      const before=await snapshot(),count=requests;
      await page.mouse.move(pointer.x+40,pointer.y,{steps:8});
      await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
      const after=await snapshot();
      const travel=after.x-before.x;
      assert.ok(travel>=-1&&travel<=9,JSON.stringify({surface,screen,before,after}));
      if(screen.width>600)assert.ok(Math.abs(travel-8)<1);
      assert.ok(Math.abs(after.y-before.y)<1);assert.equal(requests,count,"movement must not refetch evidence");
      assert.equal(after.frame,0,"no animation loop after movement");assert.equal(after.pointerEvents,"none");
      assert.ok(after.x>=after.bounds.left+11&&after.y>=after.bounds.top+11);
      assert.ok(after.x+after.width<=after.bounds.right-11&&after.y+after.height<=after.bounds.bottom-11);
      assert.ok(pointer.x+40+28<=after.x||pointer.x+40-28>=after.x+after.width||pointer.y+28<=after.y||pointer.y-28>=after.y+after.height);
      assert.match(after.text,/asserted/);assert.match(after.text,/graal/);assert.doesNotMatch(after.text,/unreachable/);
      await page.screenshot({path:path.join(output,`${surface}-${screen.width}.png`)});
      await page.keyboard.press("Escape");assert.equal(await page.locator("#blip").isVisible(),false);
      await page.mouse.move(1,1);await page.mouse.move(pointer.x,pointer.y);
      await page.waitForFunction(()=>blipEl().style.display==="block");
      await page.mouse.wheel(0,-20);await page.waitForFunction(()=>blipEl().style.display==="none");
      assert.deepEqual(errors,[]);receipts.push({surface,screen,requests,before,after});await page.close();
    }
    await fs.writeFile(path.join(output,"receipts.json"),JSON.stringify(receipts,null,2));
    console.log(JSON.stringify({output,receipts},null,2));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
