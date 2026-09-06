"use strict";
const {chromium}=require("playwright");
const {PNG}=require("pngjs");
const assert=require("node:assert/strict"),fs=require("node:fs/promises"),path=require("node:path"),os=require("node:os");
const base=process.env.PATCH_BASE_URL||"http://127.0.0.1:8888";

(async()=>{
  const output=await fs.mkdtemp(path.join(os.tmpdir(),"trikeshed-shake-zoom-")),receipts=[];
  const browser=await chromium.launch({headless:true});
  const watchdog=setTimeout(()=>browser.close(),120000);
  try{
    for(const size of [{width:1440,height:1000},{width:390,height:844}]){
      const page=await browser.newPage({viewport:size,reducedMotion:"reduce"}),errors=[];
      page.on("pageerror",e=>errors.push(e.message));
      // The pure matcher may run; program execution and publishing may not.
      await page.route("**/*",route=>["GET","HEAD"].includes(route.request().method())||new URL(route.request().url()).pathname==="/api/lcnc/treeshake"?route.continue():route.fulfill({status:409,body:"read-only camera check"}));
      await page.goto(base+"/harness?load=preset-shake",{waitUntil:"domcontentloaded"});
      await page.waitForFunction(()=>typeof Harness!=="undefined"&&Harness.live&&Harness.selected==="preset-shake");
      await page.evaluate(()=>document.fonts.ready);
      const legend=page.locator("#topologyLegend[open] > summary");
      if(await legend.count())await legend.click();
      await page.evaluate(()=>Harness.shake());
      assert.ok(await page.evaluate(()=>VERDICTS.length>500),"post-Shake verdict workload required");
      const graph=()=>page.evaluate(()=>JSON.stringify(G.nodes.filter(n=>n._program==="preset-shake").map(n=>[n.id,n.x,n.y,n._view,n.params])));
      const before=await graph();
      await page.evaluate(()=>{
        globalThis.target=G.nodes.find(n=>n.id==="preset-shake::depth.7.scope");
        if(!target)throw Error("eight-level Shake specimen missing");
        globalThis.targetElement=target.el;
        Harness.focusNode(target);
      });
      const center=await page.evaluate(()=>{const r=viewport.getBoundingClientRect();return{x:r.left+r.width/2,y:r.top+r.height/2};});
      await page.mouse.move(center.x,center.y);
      for(let i=0;i<6;i++)await page.mouse.wheel(0,-1000);
      await page.waitForFunction(()=>view.z===4000);
      const client=await page.context().newCDPSession(page);await client.send("Performance.enable");
      await page.evaluate(async()=>{
        for(let i=0;i<4;i++){view.x++;applyView();await new Promise(requestAnimationFrame);}
        globalThis.samples=[];globalThis.rectReads=0;
        const original=Element.prototype.getBoundingClientRect,draw=Landscape.draw;
        Element.prototype.getBoundingClientRect=function(){rectReads++;return original.call(this);};
        Landscape.draw=function(){const reads=rectReads,t=performance.now();draw.call(this);samples.push({ms:performance.now()-t,reads:rectReads-reads});};
      });
      const metrics=async()=>Object.fromEntries((await client.send("Performance.getMetrics")).metrics.map(m=>[m.name,m.value]));
      const start=await metrics();
      const result=await page.evaluate(async()=>{
        const t=performance.now();
        for(let i=0;i<24;i++){view.x+=i%2?1:-1;applyView();await new Promise(requestAnimationFrame);}
        const r=target.el.getBoundingClientRect(),vp=viewport.getBoundingClientRect();
        return {ms:performance.now()-t,samples,nodes:G.nodes.length,scopes:G.nodes.filter(n=>n._childHost).length,z:view.z,
          sameElement:target.el===targetElement,rect:r.toJSON(),viewport:vp.toJSON(),paths:[...document.querySelectorAll("#wires path,#channels path")].filter(p=>p.getAttribute("d")).length,
          badges:VERDICTS.length,paintedBadges:VERDICTS.filter(v=>v.el&&v.el.style.display!=="none").map(v=>{const r=v.el.getBoundingClientRect();return {w:r.width,h:r.height};})};
      });
      const end=await metrics();result.taskMs=(end.TaskDuration-start.TaskDuration)*1000;
      assert.ok(result.nodes>500);assert.equal(result.sameElement,true);assert.ok(result.paths>0);
      assert.ok(result.paintedBadges.every(b=>b.w<=40&&b.h<=40),"verdict glows must not magnify with the world");
      assert.ok(result.samples.every(s=>s.reads<=result.nodes+result.scopes+1),JSON.stringify(result.samples));
      assert.ok(result.taskMs<600,"24 steady camera frames exceeded 600ms of browser main-thread work: "+result.taskMs);
      assert.equal(await graph(),before,"zoom must not alter graph coordinates, ring cameras or parameters");
      const input=await page.evaluate(()=>{
        for(const el of target.el.querySelectorAll("input,select")){
          const r=el.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;
          if(document.elementFromPoint(x,y)===el){globalThis.testInput=el;return{x,y};}
        }
      });
      assert.ok(input,"deep scope controls must be hit-testable at width "+size.width);
      await page.mouse.click(input.x,input.y);
      assert.equal(await page.evaluate(()=>document.activeElement===testInput),true);
      assert.equal(await graph(),before);
      // On mobile the 4000x ring is larger than the viewport. Fit it before
      // checking its outline pixels, without changing the selected graph.
      const paint=await page.evaluate(async()=>{
        testInput.blur();Harness.focusNode(target);
        await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
        return {rect:target.el.getBoundingClientRect().toJSON(),z:view.z};
      });
      const png=PNG.sync.read(await page.screenshot({path:path.join(output,`shake-${size.width}.png`)}));
      // The selected ring's gold top edge must be painted near its DOM box,
      // not displaced by thousands of pixels of compounded label rounding.
      let gold=0;
      for(let y=Math.max(0,Math.floor(paint.rect.top-24));y<Math.min(png.height,paint.rect.top+24);y++){
        for(let x=Math.max(0,Math.ceil(paint.rect.left));x<Math.min(result.viewport.right,paint.rect.right);x++){
          const i=(y*png.width+x)*4,[r,g,b]=png.data.subarray(i,i+3);
          if(r>160&&g>120&&g<220&&b<140&&r>g)gold++;
        }
      }
      assert.ok(gold>40,"deep scope outline is not painted at its measured position: "+gold);
      assert.equal(await page.evaluate(()=>{Harness.fit(true);return cameraDetailRoot===null&&targetElement===target.el&&!viewport.querySelector(":scope > .ringworld");}),true);
      assert.equal(await graph(),before);
      assert.deepEqual(errors,[]);
      receipts.push({size,...result,paint,gold});await page.close();
    }
    await fs.writeFile(path.join(output,"receipts.json"),JSON.stringify(receipts,null,2));
    console.log(JSON.stringify({output,receipts},null,2));
  }finally{clearTimeout(watchdog);await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
