'use strict';
const {chromium}=require('playwright');
const {execFileSync}=require('node:child_process');
const assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os');
const base=process.env.SPACEGRAPH_BASE_URL||'http://127.0.0.1:8888';
const baseline='18c741c0f';
const html=execFileSync('git',['show',`${baseline}:src/commonMain/resources/web/panels.html`],{cwd:path.resolve(__dirname,'../../..'),encoding:'utf8',maxBuffer:1024*1024});
(async()=>{
  const output=await fs.mkdtemp(path.join(os.tmpdir(),'panels-refinement-'));
  const browser=await chromium.launch({headless:true}),receipts=[];
  try{
    for(const viewport of [{width:1440,height:960},{width:390,height:844}]){
      const snapshots=[];
      for(const version of ['before','after']){
        const page=await browser.newPage({viewport}),errors=[];
        page.on('pageerror',e=>errors.push(e.message));
        await page.route('**/*',route=>{
          const request=route.request(),url=new URL(request.url());
          if(!['GET','HEAD'].includes(request.method()))return route.fulfill({status:409,contentType:'application/json',body:'{"error":"read-only visual comparison"}'});
          if(version==='before'&&url.pathname==='/panels')return route.fulfill({contentType:'text/html',body:html});
          return route.continue();
        });
        await page.goto(base+'/panels?load=preset-scope',{waitUntil:'domcontentloaded'});
        await page.waitForFunction(()=>typeof G!=='undefined'&&G.nodes.length===11);
        await page.waitForTimeout(400);
        snapshots.push(await page.evaluate(()=>{
          const css=el=>{const s=getComputedStyle(el);return [s.backgroundColor,s.color,s.fontFamily,s.fontSize,s.borderRadius,s.boxShadow];};
          return {
            toolbar:[...document.getElementById('bar').children].filter(e=>e.id!=='spacegraphBtn').map(e=>[e.id,e.textContent]),
            viewport:css(document.getElementById('viewport')),
            nodes:G.nodes.map(n=>({id:n.id,parent:n._parentScope?.id||null,size:[n.el.offsetWidth,n.el.offsetHeight],style:css(n.el),
              controls:[...n.el.querySelectorAll(':scope > .params input,:scope > .params textarea,:scope > .params select')].map(e=>[e.tagName,e.value,css(e)])})),
            minimap:getComputedStyle(document.getElementById('minimap')).display,
            crumb:getComputedStyle(document.getElementById('crumb')).display,
            inert:document.getElementById('viewport').inert,
            transform:getComputedStyle(document.getElementById('world')).transform,
          };
        }));
        await page.screenshot({path:path.join(output,`${version}-${viewport.width}.png`)});
        assert.deepEqual(errors,[]);await page.close();
      }
      assert.deepEqual(snapshots[1],snapshots[0],`Default editor must retain the ${baseline} controls, style, geometry, and flat transform`);
      receipts.push({viewport,nodes:snapshots[1].nodes.length,baseline,matched:true});
    }
    console.log(JSON.stringify({output,receipts},null,2));
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
