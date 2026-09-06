"use strict";
const {chromium}=require("playwright");
const assert=require("node:assert/strict"),fs=require("node:fs/promises"),path=require("node:path"),os=require("node:os");
const base=process.env.PATCH_BASE_URL||"http://127.0.0.1:8897";
(async()=>{
  const output=await fs.mkdtemp(path.join(os.tmpdir(),"trikeshed-shake-browser-"));
  const browser=await chromium.launch({headless:true}),receipts=[];
  try{
    for(const viewport of [{width:1440,height:1000},{width:390,height:844}]){
      const page=await browser.newPage({viewport}),errors=[],requests=[];let refusal=false;
      page.on("pageerror",e=>{errors.push(e.message);console.error(e.message);});
      await page.route("**/*",async route=>{
        const req=route.request();
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
      assert.equal(requests.length,1);assert.equal(requests[0].program.nodes.length,10);
      const hermes=await page.locator("#status").textContent();
      // The user's program remains a local link-view; Shake never publishes it.
      assert.equal(await page.evaluate(()=>AUTOSAVE),false);
      const after=await page.evaluate(()=>JSON.stringify({nodes:serialize().nodes,wires:G.wires}));
      if(hermes.startsWith("No cables changed"))assert.equal(after,before);

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
      assert.deepEqual(errors,[]);receipts.push({viewport,hermes,nested});await page.close();
    }
    console.log(JSON.stringify({output,receipts},null,2));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
