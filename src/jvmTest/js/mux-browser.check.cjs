"use strict";
const {chromium}=require("playwright"),assert=require("node:assert/strict"),fs=require("node:fs/promises"),os=require("node:os"),path=require("node:path");
const base=process.env.MUX_BASE_URL||"http://127.0.0.1:8897";
(async()=>{
  const output=await fs.mkdtemp(path.join(os.tmpdir(),"trikeshed-mux-browser-"));
  console.log("Screenshots: "+output);
  const browser=await chromium.launch({headless:true,channel:process.env.MUX_BROWSER_CHANNEL||"chrome"});
  console.log("Browser ready");
  const errors=[];
  try{
    const page=await browser.newPage({viewport:{width:1440,height:1000}});page.on("pageerror",e=>errors.push(e.message));
    console.log("Page ready");
    // Live contract smoke check before the deterministic rendering fixtures.
    if (!process.env.MUX_FIXTURES_ONLY) {
    await page.goto(base+"/keymux");await page.waitForFunction(()=>document.querySelector("#connection").textContent.startsWith("Live"));
    assert.ok(await page.locator("#keyRows tr").count()>0);
    await page.screenshot({path:path.join(output,"keymux-live.png"),fullPage:true});
    await page.goto(base+"/modelmux");await page.waitForFunction(()=>document.querySelector("#modelRows input"));
    await page.screenshot({path:path.join(output,"modelmux-live.png"),fullPage:true});
    await page.goto(base+"/mux/stats");await page.waitForFunction(()=>document.querySelector("#connection").textContent.startsWith("Live"));
    await page.screenshot({path:path.join(output,"stats-live.png"),fullPage:true});
    }
    const now=Date.now(),calls=Array.from({length:36},(_,i)=>({id:i+1,conversationId:i%2?102:103,turnId:1,model:i%2?"alpha/code-model":"beta/reasoning-model",provider:i%2?"alpha":"beta",keyId:i%2?"llm.alpha.key":"llm.beta.key",startedAt:now-800000+i*20000,endedAt:now-799000+i*20000,status:i%7===0?"failed":"completed",httpStatus:i%7===0?429:200,inputTokens:300+i,outputTokens:100+i,cachedHit:i%5===0}));
    let sessions=[{id:101,title:"Review the provider retry policy",status:"completed",mode:"synthesize",parentId:null,model:"alpha/code-model",archived:false,createdAt:now-60000,updatedAt:now,turnId:1,children:[102,103],inputTokens:800,outputTokens:300},
      {id:102,title:"alpha/code-model",model:"alpha/code-model",status:"completed",mode:"chat",parentId:101,archived:false,createdAt:now-50000,updatedAt:now-500,children:[],inputTokens:300,outputTokens:100},
      {id:103,title:"beta/reasoning-model",model:"beta/reasoning-model",status:"failed",mode:"chat",parentId:101,archived:false,createdAt:now-50000,updatedAt:now-1000,children:[],inputTokens:0,outputTokens:0}];
    let endpoints=[],runBodies=[],quotaFailures=false;
    const roster=[{name:"alpha",base:"https://alpha.invalid/v1",model:"alpha/code-model",envVar:"ALPHA_API_KEY",keyPresent:true,discovered:true},{name:"beta",base:"https://beta.invalid/v1",model:"beta/reasoning-model",envVar:"BETA_API_KEY",keyPresent:true,discovered:true},{name:"missing",base:"https://missing.invalid/v1",model:"missing/very-long-model-name-that-must-fit-in-a-mobile-viewport",envVar:"MISSING_API_KEY",keyPresent:false,discovered:false}];
    const detail=id=>({...sessions.find(s=>s.id===id),messages:[{role:"user",content:"Review the retry policy and quota accounting.",at:now-10000},{role:"assistant",model:"alpha/code-model",content:"The pool records every attempt against its own session.\n\n```kotlin\nval receipt = runModel(request)\n```\n<html><img src=x onerror=alert(1)></html>",at:now}],calls:calls.filter(c=>c.conversationId===id)});
    await page.route("**/api/mux/**",async route=>{
      const url=new URL(route.request().url()),method=route.request().method(),body=route.request().postDataJSON();let data={},status=200;
      if(url.pathname.endsWith("/keys"))data={roster};
      else if(url.pathname.endsWith("/models")||url.pathname.endsWith("/catalog"))data={models:roster};
      else if(url.pathname.endsWith("/activity"))data={calls,atMs:Date.now()};
      else if(url.pathname.endsWith("/standings")){if(quotaFailures){status=503;data={error:"Quota source unavailable"};}else data={atMs:Date.now(),standings:[{provider:"alpha",keyId:"llm.alpha.key",limit:100000,spent:35600,remaining:64400,usable:true,exhausted:false,windowStartMs:now-20000,windowMs:86400000,accessCount:28},{provider:"beta",keyId:"llm.beta.key",limit:0,spent:4500,remaining:9223372036854775807,usable:false,exhausted:true,windowStartMs:now-10000,windowMs:60000,accessCount:12}]};}
      else if(url.pathname.endsWith("/endpoints")){if(method==="POST")endpoints=[body];data=method==="GET"?{user:endpoints,builtin:roster}:{verdict:"ok"};}
      else if(url.pathname.includes("/endpoints/")){endpoints=[];data={verdict:"removed"};}
      else if(url.pathname.endsWith("/sessions/run")){runBodies.push(body);data={id:body.id,status:"queued"};status=202;}
      else if(url.pathname.endsWith("/sessions/update")){sessions=sessions.map(s=>s.id===body.id?{...s,...body}:s);data={status:"saved"};}
      else if(url.pathname.endsWith("/sessions")){if(method==="POST"){data={...sessions[0],id:104,title:"New session",children:[]};sessions.push(data);status=201;}else data=url.searchParams.has("id")?detail(Number(url.searchParams.get("id"))):{sessions};}
      await route.fulfill({status,contentType:"application/json",body:JSON.stringify(data)});
    });
    await page.goto(base+"/keymux");await page.getByText("ALPHA_API_KEY",{exact:true}).waitFor();
    assert.equal(await page.getByText("Limit unknown",{exact:true}).count(),1);
    await page.getByRole("button",{name:"Add endpoint",exact:true}).click();
    const dialog=page.locator("#endpointDialog");await dialog.getByLabel("Name",{exact:true}).fill("test-provider");await dialog.getByLabel("API address",{exact:true}).fill("https://provider.invalid/v1");await dialog.getByLabel("Model",{exact:true}).fill("new/model");await dialog.getByLabel("Credential environment variable").fill("TEST_API_KEY");await dialog.getByRole("button",{name:"Save endpoint"}).click();await page.getByText("Registry only",{exact:true}).waitFor();
    await page.locator("#keySearch").fill("alpha");assert.equal(await page.locator("#keyRows tr").count(),1);await page.locator("#keySearch").fill("");
    await page.screenshot({path:path.join(output,"keymux-desktop.png"),fullPage:true});
    await page.goto(base+"/modelmux");await page.getByLabel("Select alpha/code-model",{exact:true}).waitFor();await page.locator("#runMode").selectOption("synthesize");await page.getByLabel("Select alpha/code-model",{exact:true}).check();await page.getByLabel("Select beta/reasoning-model",{exact:true}).check();
    await page.locator("#runPrompt").fill("Compare the retry implementations.");await page.locator("#runSession").selectOption("101");
    await page.screenshot({path:path.join(output,"modelmux-desktop.png"),fullPage:true});
    await page.locator("#runForm button[type=submit]").click();await page.waitForURL("**/mux/sessions?id=101");assert.equal(runBodies[0].mode,"synthesize");assert.deepEqual(runBodies[0].models,["alpha/code-model","beta/reasoning-model"]);
    await page.locator("#detailTitle").waitFor();assert.equal(await page.locator(".message-content img").count(),0);
    await page.locator("#followupPrompt").fill("Keep this unsent draft intact");await page.getByRole("button",{name:"Refresh",exact:true}).click();await page.waitForTimeout(2200);assert.equal(await page.locator("#followupPrompt").inputValue(),"Keep this unsent draft intact");
    await page.screenshot({path:path.join(output,"session-desktop.png"),fullPage:true});
    await page.getByRole("tab",{name:"Branches",exact:true}).click();assert.equal(await page.locator(".branch").count(),2);await page.screenshot({path:path.join(output,"branches-desktop.png"),fullPage:true});
    await page.getByRole("button",{name:"Rename session",exact:true}).click();await page.locator("#renameForm input").fill("Review retry budgets");await page.locator("#renameForm button[type=submit]").click();await page.getByRole("heading",{name:"Review retry budgets",exact:true}).waitFor();
    await page.goto(base+"/mux/stats");await page.waitForFunction(()=>document.querySelectorAll("#statsActivity tbody tr").length===36);
    assert.ok(await page.locator("#trafficChart").evaluate(canvas=>{const pixels=canvas.getContext("2d").getImageData(0,0,canvas.width,canvas.height).data;let colored=0;for(let i=0;i<pixels.length;i+=4)if(pixels[i+3]&&pixels[i+1]>pixels[i]+25)colored++;return colored>200;}));
    await page.screenshot({path:path.join(output,"stats-desktop.png"),fullPage:true});
    await page.getByRole("button",{name:"Pause live updates"}).click();assert.equal(await page.locator("#connection").textContent(),"Paused");await page.getByRole("button",{name:"Resume live updates"}).click();
    quotaFailures=true;await page.getByRole("button",{name:"Refresh",exact:true}).click();await page.getByText("Quota: Quota source unavailable",{exact:true}).waitFor();quotaFailures=false;await page.getByRole("button",{name:"Refresh",exact:true}).click();await page.locator("#errors").waitFor({state:"hidden"});
    for(const width of [390,768,1920]){
      await page.setViewportSize({width,height:width===390?844:1000});
      for(const route of ["keymux","modelmux","mux/sessions?id=101","mux/stats"]){
        await page.goto(base+"/"+route);await page.waitForFunction(()=>document.querySelector("#connection").textContent.startsWith("Live"));
        assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true,"No page overflow at "+width+" on "+route);
        await page.screenshot({path:path.join(output,route.split(/[/?]/).filter(Boolean).join("-")+"-"+width+".png"),fullPage:true});
      }
    }
    assert.deepEqual(errors,[]);console.log(JSON.stringify({output,checks:["live API contracts","endpoint CRUD","credential filtering","fan-out request","session rendering","safe message text","draft survives refresh","branch navigation","rename","nonblank chart","pause and reconnect","390/768/1920 layouts","no page errors"]},null,2));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
