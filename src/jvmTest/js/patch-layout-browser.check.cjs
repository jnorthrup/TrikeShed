"use strict";
const {chromium}=require("playwright");
const assert=require("node:assert/strict"),fs=require("node:fs/promises"),path=require("node:path"),os=require("node:os");
const base=process.env.PATCH_BASE_URL||"http://127.0.0.1:8898";

(async()=>{
  const output=await fs.mkdtemp(path.join(os.tmpdir(),"trikeshed-patch-layout-browser-"));
  const browser=await chromium.launch({headless:true}),receipts=[];
  try{
    for(const viewport of [{width:1440,height:1000},{width:390,height:844}]){
      const page=await browser.newPage({viewport}),errors=[];
      page.on("pageerror",e=>errors.push(e.message));
      await page.goto(base+"/harness?load=preset-curator",{waitUntil:"domcontentloaded"});
      await page.waitForFunction(()=>typeof Harness!=="undefined"&&Harness.ready&&Harness.selected==="preset-curator");
      await page.evaluate(()=>{
        const original=PatchLayout.layout;
        PatchLayout.layout=async(boxes,links,options)=>{
          const length=positions=>{
            const byId=new Map(boxes.map((b,i)=>[b.id,{...b,...positions[i]}]));
            return links.reduce((s,l)=>{const a=byId.get(l.from),b=byId.get(l.to);
              const out=l.out||{x:a.w/2,y:0},input=l.in||{x:-b.w/2,y:0};
              return s+Math.hypot(b.x+b.w/2+input.x-a.x-a.w/2-out.x,b.y+b.h/2+input.y-a.y-a.h/2-out.y);},0);
          };
          const result=await original(boxes,links,options);
          window.layoutReceipt={...result,beforeLength:length(boxes),afterLength:length(result.positions)};
          return result;
        };
      });
      const run=async(label)=>{
        await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
        const before=await page.evaluate(()=>({wires:JSON.stringify(G.wires),other:G.nodes.filter(n=>n._program!==Harness.selected).map(n=>[n.id,n.x,n.y])}));
        await page.locator("#fdBtn").click();
        await page.waitForFunction(()=>!fdLayout.busy&&document.querySelector("#status").textContent.startsWith("fd:"),{},{timeout:15000});
        const result=await page.evaluate(()=>{
          const nodes=Harness.parentTarget().nodes;
          const overlaps=[],territoryOverlaps=[];
          for(let i=0;i<nodes.length;i++)for(let j=i+1;j<nodes.length;j++){
            const a=nodes[i],b=nodes[j],gap=31.9;
            if(!(a.x+a.el.offsetWidth+gap<=b.x||b.x+b.el.offsetWidth+gap<=a.x||a.y+a.el.offsetHeight+gap<=b.y||b.y+b.el.offsetHeight+gap<=a.y))overlaps.push([a.id,b.id]);
          }
          const canvas=document.querySelector("#landscape"),pixels=canvas.getContext("2d").getImageData(0,0,canvas.width,canvas.height).data;
          if(!Harness.parentTarget().node){
            const rect=name=>{const a=Harness.mounts.get(name),b=Harness.bounds(name);return {x:a.x+b.left-24,y:a.y+b.top-64,w:b.w+48,h:b.h+88};};
            const a=rect(Harness.selected);
            for(const name of Harness.mounts.keys()){if(name===Harness.selected)continue;const b=rect(name);
              if(a.x<b.x+b.w&&b.x<a.x+a.w&&a.y<b.y+b.h&&b.y<a.y+a.h)territoryOverlaps.push(name);}
          }
          let painted=0;for(let i=3;i<pixels.length;i+=4)if(pixels[i])painted++;
          return {wires:JSON.stringify(G.wires),other:G.nodes.filter(n=>n._program!==Harness.selected).map(n=>[n.id,n.x,n.y]),
            overlaps,territoryOverlaps,painted,status:document.querySelector("#status").textContent,receipt:window.layoutReceipt};
        });
        assert.equal(result.wires,before.wires);assert.deepEqual(result.other,before.other);
        assert.deepEqual(result.overlaps,[]);assert.deepEqual(result.territoryOverlaps,[]);assert.ok(result.painted>500);assert.ok(!result.status.includes("existing cables only"),result.status);
        await page.screenshot({path:path.join(output,`${viewport.width}-${label}.png`)});
        receipts.push({viewport,label,...result.receipt,positions:undefined,status:result.status});
      };
      await run("curator");
      if(process.env.SHAKE_ENTRY){
        const entry=JSON.parse(await fs.readFile(process.env.SHAKE_ENTRY,"utf8"));
        await page.evaluate(entry=>{Harness.board["lcnc/program/preset-shake"]=entry;Harness.mount("preset-shake");Harness.select("preset-shake",true);},entry);
        await run("shake-specimen");
        assert.ok(receipts.at(-1).afterLength<receipts.at(-1).beforeLength);
        assert.equal(await page.locator("#runBtn").isDisabled(),true);
        await page.evaluate(()=>{const scope=G.nodes.find(n=>n._program===Harness.selected&&n._childHost&&n.children.length>1);Harness.setParent(scope);Harness.fit(false);});
        await run("nested-scope");
        await page.locator("#shakeBtn").click();
        await page.waitForFunction(()=>!Harness.shaking&&document.querySelector("#status").textContent.includes("cables connected"));
        assert.ok(await page.locator("svg#wires path.live").count()>0);
        await page.locator("#topologyLegend > summary").click();
        await page.screenshot({path:path.join(output,`${viewport.width}-nested-wired.png`)});
      }
      assert.deepEqual(errors,[]);await page.close();
    }
    console.log(JSON.stringify({output,receipts},null,2));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
