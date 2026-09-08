"use strict";
const {chromium}=require("playwright");
const assert=require("node:assert/strict"),fs=require("node:fs/promises"),path=require("node:path"),os=require("node:os");
const base=process.env.PATCH_BASE_URL||"http://127.0.0.1:8897";
const shakeSource=process.env.PATCH_SHAKE_SOURCE;
(async()=>{
  const output=await fs.mkdtemp(path.join(os.tmpdir(),"trikeshed-shake-browser-"));
  const browser=await chromium.launch({headless:true}),receipts=[];
  try{
    for(const viewport of [{width:1440,height:1000},{width:390,height:844}]){
      const page=await browser.newPage({viewport}),errors=[],requests=[];let refusal=false;
      page.on("pageerror",e=>{errors.push(e.message);console.error(e.message);});
      await page.route("**/*",async route=>{
        const req=route.request();
        if(shakeSource&&req.method()==="GET"&&new URL(req.url()).pathname==="/patch-shake.js")
          return route.fulfill({path:path.resolve(shakeSource),contentType:"application/javascript"});
        if(req.method()==="POST"&&new URL(req.url()).pathname==="/api/lcnc/treeshake"){
          requests.push(req.postDataJSON());
          if(refusal)return route.fulfill({status:400,contentType:"application/json",body:'{"error":"test_refusal","detail":"Connection fixture refused"}'});
          return route.continue();
        }
        if(!["GET","HEAD"].includes(req.method()))return route.fulfill({status:409,contentType:"application/json",body:'{"error":"read-only browser test"}'});
        return route.continue();
      });
      await page.goto(base+"/panels?load=preset-hermes-train",{waitUntil:"domcontentloaded"});
      await page.waitForFunction(()=>typeof PanelsShake!=="undefined"&&G.nodes.length&&document.getElementById("status").textContent.startsWith("done "))
        .catch(async e=>{console.error(await page.locator("#status").textContent());throw e;});
      await page.locator("#stopBtn").click();
      const before=await page.evaluate(()=>JSON.stringify({nodes:serialize().nodes,wires:G.wires}));
      await page.locator("#shakeBtn").click();
      await page.waitForFunction(()=>!PanelsShake.shaking&&document.getElementById("status").textContent.includes("sockets connected"));
      assert.equal(requests.length,1);
      assert.equal(requests[0].program.nodes.length,await page.evaluate(()=>serialize().nodes.length));
      const hermes=await page.locator("#status").textContent();
      // The user's program remains a local link-view; Shake never publishes it.
      assert.equal(await page.evaluate(()=>AUTOSAVE),false);
      const after=await page.evaluate(()=>JSON.stringify({nodes:serialize().nodes,wires:G.wires}));
      if(hermes.startsWith("No cables changed"))assert.equal(after,before);

      const original=await page.evaluate(()=>JSON.stringify(serialize())),repairs=[];
      for(let i=0;i<JSON.parse(original).wires.length;i++){
        const removed=await page.evaluate(({original,i})=>{
          load(JSON.parse(original));
          const [wire]=G.wires.splice(i,1),target=G.nodes.find(n=>n.id===wire.to[0]);
          redraw();save();
          return {wire,optional:wire.to[1].endsWith("?"),effect:!!CONTRACTS[target.type].effect};
        },{original,i});
        const disconnected=await page.evaluate(()=>JSON.stringify(serialize()));
        await page.locator("#shakeBtn").click();
        await page.waitForFunction(()=>!PanelsShake.shaking&&document.getElementById("status").textContent.includes("sockets connected"));
        const status=await page.locator("#status").textContent();
        const repaired=await page.evaluate(wire=>G.wires.some(w=>JSON.stringify(w)===JSON.stringify(wire)),removed.wire);
        const distant=removed.wire.to[0]==="n4";
        if(removed.optional||removed.effect||distant){
          assert.equal(repaired,false);assert.equal(await page.evaluate(()=>JSON.stringify(serialize())),disconnected);
          if(removed.optional)assert.match(status,/optional inputs unchanged/);
          else if(removed.effect){
            assert.ok(status.includes(removed.wire.to.join(".")+": Effect input requires an explicit connection"),status);
            assert.doesNotMatch(status,/closer source/);
            await page.screenshot({path:path.join(output,`panels-${viewport.width}-effect.png`)});
          }else assert.ok(status.includes("n4.lines: Compatible output beyond 340 units"),status);
          // Exercise the explicit remedy with pointer input, without executing it.
          if(await page.locator("#palette").evaluate(el=>el.classList.contains("open")))await page.locator("#paletteBtn").click();
          await page.evaluate(wire=>{
            killMomentum();
            const a=portCenter(wire.from[0],"out",wire.from[1]),b=portCenter(wire.to[0],"in",wire.to[1]);
            view.z=Math.min(1,(viewport.clientWidth-80)/Math.max(80,Math.abs(b.x-a.x)),(viewport.clientHeight-80)/Math.max(80,Math.abs(b.y-a.y)));
            view.x=viewport.clientWidth/2-(a.x+b.x)*view.z/2;
            view.y=viewport.clientHeight/2-(a.y+b.y)*view.z/2;
            applyView();redraw();
          },removed.wire);
          const port=async (end,dir)=>page.locator(`.node[data-id="${end[0]}"] .port[data-dir="${dir}"][data-port="${end[1]}"]`).boundingBox();
          const source=await port(removed.wire.from,"out"),target=await port(removed.wire.to,"in");
          assert.ok(source&&target);
          await page.mouse.move(source.x+source.width/2,source.y+source.height/2);await page.mouse.down();
          await page.mouse.move(target.x+target.width/2,target.y+target.height/2,{steps:8});await page.mouse.up();
          assert.equal(await page.evaluate(wire=>G.wires.some(w=>JSON.stringify(w)===JSON.stringify(wire)),removed.wire),true);
        }else assert.equal(repaired,true,status);
        assert.equal(await page.evaluate(()=>G.wires.every(wireKindOk)),true);
        repairs.push({...removed,distant,repaired,status});
      }
      assert.equal(repairs.filter(r=>r.optional).length,4);
      assert.equal(repairs.filter(r=>!r.optional&&r.effect).length,1);
      assert.equal(repairs.filter(r=>r.distant).length,1);
      assert.equal(repairs.filter(r=>r.repaired).length,1);

      // Distance remains a real constraint for a non-effect input.
      const ordinary=repairs.find(r=>r.repaired).wire;
      await page.evaluate(({original,wire})=>{
        load(JSON.parse(original));G.wires=G.wires.filter(w=>JSON.stringify(w)!==JSON.stringify(wire));
        const source=G.nodes.find(n=>n.id===wire.from[0]),target=G.nodes.find(n=>n.id===wire.to[0]);
        target.x=source.x+1200;target.el.style.left=target.x+"px";redraw();save();fitToContent();
      },{original,wire:ordinary});
      await page.locator("#shakeBtn").click();
      await page.waitForFunction(()=>!PanelsShake.shaking&&document.getElementById("status").textContent.includes("sockets connected"));
      const distant=await page.locator("#status").textContent();
      assert.ok(distant.includes(ordinary.to.join(".")+": Compatible output beyond 340 units"),distant);
      assert.equal(await page.evaluate(wire=>G.wires.some(w=>JSON.stringify(w)===JSON.stringify(wire)),ordinary),false);
      await page.screenshot({path:path.join(output,`panels-${viewport.width}-distance.png`)});

      // Use declared scope ports to exercise the cyclic-renderer regression.
      await page.evaluate(()=>{
        load({controls:{inspectionOnly:true},nodes:[{id:"scope",type:"scope",x:0,y:0,params:{},children:[
          {id:"source",type:"scope.in",x:0,y:0,params:{name:"text",kind:"text",default:"hello"}},
          {id:"result",type:"scope.out",x:160,y:0,params:{name:"text",kind:"text"}},
        ]}],wires:[]});
        document.getElementById("panelName").value="camera-test-scope";fitToContent();
      });
      const identity=await page.evaluate(()=>{window.testNode=G.nodes[0];return {nodes:G.nodes.length,undo:UNDO.length};});
      await page.locator("#shakeBtn").click();
      await page.waitForFunction(()=>!PanelsShake.shaking&&document.getElementById("status").textContent.includes("sockets connected"));
      const nested=await page.evaluate(()=>({nodes:G.nodes.length,wires:G.wires.length,identity:G.nodes[0]===window.testNode,undo:UNDO.length,
        status:document.getElementById("status").textContent,controls:serialize().controls}));
      assert.equal(nested.nodes,identity.nodes);assert.equal(nested.identity,true);assert.equal(nested.wires,1);
      assert.equal(await page.evaluate(()=>G.wires.every(wireKindOk)),true);
      assert.equal(nested.undo,identity.undo+1);assert.equal(nested.controls.inspectionOnly,true);
      assert.equal(requests.at(-1).program.nodes.length,1);assert.equal(requests.at(-1).program.nodes[0].children.length,2);
      assert.equal(requests.at(-1).program.controls.inspectionOnly,true);
      assert.equal(await page.locator("#runBtn").isDisabled(),true);
      const wired=await page.evaluate(()=>JSON.stringify(serialize()));
      await page.locator("#shakeBtn").click();
      await page.waitForFunction(()=>!PanelsShake.shaking&&document.getElementById("status").textContent.startsWith("No cables changed"));
      assert.equal(await page.evaluate(()=>JSON.stringify(serialize())),wired);assert.equal(await page.evaluate(()=>UNDO.length),nested.undo);
      const statusBox=await page.locator("#status").boundingBox();
      assert.ok(statusBox.x>=0&&statusBox.x+statusBox.width<=viewport.width&&statusBox.y+statusBox.height<=viewport.height);
      await page.screenshot({path:path.join(output,`panels-${viewport.width}-connected.png`)});
      refusal=true;await page.locator("#shakeBtn").click();
      await page.waitForFunction(()=>!PanelsShake.shaking&&document.getElementById("status").textContent.startsWith("Connections refused"));
      assert.equal(await page.evaluate(()=>JSON.stringify(serialize())),wired);
      await page.screenshot({path:path.join(output,`panels-${viewport.width}-refused.png`)});
      refusal=false;
      const specimenNodes=await page.evaluate(async()=>{
        const data=await(await fetch("/api/panels/presets")).json();
        const doc=data.presets.find(p=>p.name==="preset-shake").document;
        const count=nodes=>nodes.reduce((total,n)=>total+1+count(n.children||[]),0);
        load(fromConfix(doc));document.getElementById("panelName").value="preset-shake";fitToContent();
        return count(doc.nodes);
      });
      const response=page.waitForResponse(r=>new URL(r.url()).pathname==="/api/lcnc/treeshake");
      await page.locator("#shakeBtn").click();
      const result=await(await response).json();
      await page.waitForFunction(()=>!PanelsShake.shaking&&document.getElementById("status").textContent.includes("sockets connected"));
      const specimen=await page.evaluate(()=>({nodes:G.nodes.length,wires:G.wires.length,valid:G.wires.every(wireKindOk),
        inspectionOnly:G.controls.inspectionOnly,status:document.getElementById("status").textContent}));
      assert.equal(specimen.nodes,specimenNodes);assert.equal(specimen.wires,result.made.length);
      assert.ok(specimen.wires>0);assert.equal(specimen.valid,true);assert.equal(specimen.inspectionOnly,true);
      assert.equal(await page.locator("#runBtn").isDisabled(),true);
      const specimenDoc=await page.evaluate(()=>JSON.stringify(serialize()));
      await page.locator("#shakeBtn").click();
      await page.waitForFunction(()=>!PanelsShake.shaking&&document.getElementById("status").textContent.startsWith("No cables changed"));
      assert.equal(await page.evaluate(()=>JSON.stringify(serialize())),specimenDoc);
      assert.deepEqual(errors,[]);receipts.push({viewport,hermes,repairs,distant,nested,specimen});await page.close();
    }
    console.log(JSON.stringify({base,shakeSource:shakeSource||null,output,receipts},null,2));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
