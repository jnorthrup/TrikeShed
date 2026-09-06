"use strict";
const {chromium}=require("playwright"),assert=require("node:assert/strict");
const fs=require("node:fs/promises"),os=require("node:os"),path=require("node:path");
const base=process.env.PATCH_BASE_URL||"http://127.0.0.1:8888";
(async()=>{
  const output=await fs.mkdtemp(path.join(os.tmpdir(),"trikeshed-allocation-ui-"));
  const browser=await chromium.launch({headless:true}),receipts=[];
  try{
    for(const surface of ["panels","harness","graal"])for(const viewport of [{width:1440,height:1000},{width:390,height:844}]){
      console.log(`Checking ${surface} ${viewport.width}`);
      const page=await browser.newPage({viewport}),errors=[];
      page.on("pageerror",e=>errors.push(e.message));
      await page.route("**/*",route=>["GET","HEAD"].includes(route.request().method())?route.continue():
        route.fulfill({status:409,contentType:"application/json",body:'{"error":"read-only allocation check"}'}));
      await page.goto(`${base}/${surface}?allocation=${encodeURIComponent("[B")}`,{waitUntil:"domcontentloaded"});
      const dialog=page.locator("#allocationInspector");await dialog.waitFor({state:"visible"});
      await page.locator(".alloc-site").first().waitFor();
      await page.locator(".alloc-frame").first().click();
      try{await page.locator(".alloc-bytecode").waitFor();}catch(e){
        console.error(await page.locator(".alloc-detail").innerText());console.error(errors);
        await page.screenshot({path:path.join(output,`${surface}-${viewport.width}-failure.png`)});throw e;
      }
      assert.match(await page.locator(".alloc-code").innerText(),/current classpath resource/);
      assert.match(await page.locator(".alloc-status").innerText(),/sampled \/ 30s/);
      const box=await dialog.boundingBox();assert.ok(box.x>=0&&box.y>=0);
      assert.ok(box.x+box.width<=viewport.width+1&&box.y+box.height<=viewport.height+1);
      assert.equal(await dialog.evaluate(d=>d.scrollWidth<=d.clientWidth+1),true);
      assert.equal(await page.locator(".alloc-code").evaluate(d=>d.scrollWidth<=d.clientWidth+1),true);
      await page.screenshot({path:path.join(output,`${surface}-${viewport.width}.png`)});
      await page.locator("#allocationClass").fill("java.lang.Integer");await page.locator("#allocationClass").press("Enter");
      await page.waitForFunction(()=>!document.querySelector(".alloc-status").textContent.startsWith("Loading"));
      assert.doesNotMatch(await page.locator(".alloc-status").innerText(),/unavailable/);
      const integerStatus=await page.locator(".alloc-status").innerText();
      await page.locator("#allocationClass").fill("missing.AllocationClass");await page.locator("#allocationClass").press("Enter");
      await page.getByText("No samples for this class in the recent window.",{exact:true}).waitFor();
      await page.keyboard.press("Escape");assert.equal(await dialog.isVisible(),false);
      assert.deepEqual(errors,[]);receipts.push({surface,viewport,integerStatus});await page.close();
    }
    await fs.writeFile(path.join(output,"receipts.json"),JSON.stringify(receipts,null,2));
    console.log(JSON.stringify({output,receipts},null,2));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
