'use strict';
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os');
const base=process.env.SPACEGRAPH_BASE_URL||'http://127.0.0.1:8888';
(async()=>{
  const output=await fs.mkdtemp(path.join(os.tmpdir(),'narchy-spacegraph-'));
  console.log(`Browser artifacts: ${output}`);
  const browser=await chromium.launch({headless:true,args:['--enable-webgl','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']}),receipts=[];
  try{
    for(const viewport of [{width:1440,height:960},{width:390,height:844},{width:2560,height:1440}]){
      const page=await browser.newPage({viewport}),errors=[];page.on('pageerror',e=>errors.push(e.message));
      await page.route('**/*',route=>{
        const req=route.request(),p=new URL(req.url()).pathname;
        if(!['GET','HEAD'].includes(req.method())&&!['/api/lcnc/spacegraph','/api/lcnc/rdf/align','/api/lcnc/typecheck'].includes(p))return route.fulfill({status:409,contentType:'application/json',body:'{"error":"read-only visual verification"}'});
        return route.continue();
      });
      await page.goto(base+'/panels?load=preset-scope',{waitUntil:'domcontentloaded'});
      await page.waitForFunction(()=>window.SpaceGraphWorkspace?.scene?.nodes.length>0,null,{timeout:45000}).catch(async error=>{
        console.error(JSON.stringify({output,errors,state:await page.evaluate(()=>({badge:document.querySelector('.sg-badge')?.textContent,status:document.getElementById('status')?.textContent,workspace:!!window.SpaceGraphWorkspace,nodes:typeof G==='undefined'?null:G.nodes.length}))}));
        await page.screenshot({path:path.join(output,'failure.png')});throw error;
      });
      await page.waitForTimeout(800);
      assert.equal(await page.evaluate(()=>SpaceGraphWorkspace.backend),'gl');
      const measure=await page.evaluate(()=>{
        const s=SpaceGraphWorkspace.scene,r=SpaceGraphWorkspace.renderer;r.render();
        const gl=r.gl,w=gl.drawingBufferWidth,h=gl.drawingBufferHeight,pixels=new Uint8Array(w*h*4);gl.readPixels(0,0,w,h,gl.RGBA,gl.UNSIGNED_BYTE,pixels);
        let colored=0,samples=0;for(let i=0;i<pixels.length;i+=4*31){samples++;if(Math.abs(pixels[i]-235)+Math.abs(pixels[i+1]-239)+Math.abs(pixels[i+2]-240)>35)colored++;}
        return {nodes:s.nodes.length,cables:s.cables.length,measured:s.nodes.every(n=>n.measured),depth:Math.max(...s.nodes.map(n=>n.position[2])),colored,samples,vertices:r.vertices,engine:typeof r.engine.project};
      });
      assert.ok(measure.measured);assert.ok(measure.depth>28);assert.ok(measure.colored>50,JSON.stringify(measure));assert.ok(measure.vertices>measure.nodes*36);assert.equal(measure.engine,'function');
      await page.screenshot({path:path.join(output,`spatial-${viewport.width}.png`)});
      const canvas=await page.locator('#sg-surface').boundingBox(),before=await page.evaluate(()=>SpaceGraphWorkspace.renderer.cameraValue());
      await page.mouse.move(canvas.x+canvas.width*.35,canvas.y+canvas.height*.35);await page.mouse.down();await page.mouse.move(canvas.x+canvas.width*.65,canvas.y+canvas.height*.45,{steps:12});await page.mouse.up();
      assert.notDeepEqual(await page.evaluate(()=>SpaceGraphWorkspace.renderer.cameraValue()),before,JSON.stringify({errors}));
      let remoteGeometry=0;page.on('request',r=>{if(new URL(r.url()).pathname==='/api/lcnc/spacegraph')remoteGeometry++;});
      const documentBefore=await page.evaluate(()=>JSON.stringify(serialize()));
      for(const provider of ['canvas','svg','gl']){
        await page.getByLabel('Rendering provider',{exact:true}).selectOption(provider);
        await page.waitForFunction(p=>SpaceGraphWorkspace.backend===p&&document.querySelector('.sg-badge').textContent.startsWith(p.toUpperCase()+' '),provider);
        assert.equal(await page.evaluate(()=>JSON.stringify(serialize())),documentBefore);
        assert.ok(await page.locator(provider==='svg'?'.sg-output svg':'.sg-output canvas').count());
        await page.screenshot({path:path.join(output,`${provider}-${viewport.width}.png`)});
      }
      assert.equal(remoteGeometry,0,'Camera and provider changes must not request remote geometry');
      const selected=await page.evaluate(()=>{
        const n=G.nodes.find(n=>n.el.querySelector(':scope > .params input,:scope > .params textarea'));window.sgTestNode=n;window.sgTestControl=n.el.querySelector(':scope > .params input,:scope > .params textarea');SpaceGraphWorkspace.select(n.id);return {id:n.id,value:sgTestControl.value};
      });
      assert.equal(await page.evaluate(()=>document.querySelector('.sg-inspector').contains(sgTestControl)),true);
      await page.locator('.sg-inspector .params input,.sg-inspector .params textarea').first().fill(selected.value+' verified');
      assert.equal(await page.evaluate(()=>G.nodes.find(n=>n.id===sgTestNode.id)===sgTestNode),true);
      await page.getByRole('button',{name:'Open editor',exact:true}).click();
      assert.equal(await page.evaluate(()=>sgTestNode.el.contains(sgTestControl)),true);
      assert.equal(await page.locator('#viewport').evaluate(el=>el.inert),false);
      await page.locator('#undoBtn').click();
      assert.equal(await page.evaluate(id=>Object.values(G.nodes.find(n=>n.id===id).params).some(v=>String(v).endsWith(' verified')),selected.id),false);
      await page.screenshot({path:path.join(output,`editor-${viewport.width}.png`)});
      assert.deepEqual(errors,[]);receipts.push({viewport,measure});await page.close();
    }
    console.log(JSON.stringify({output,receipts},null,2));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
