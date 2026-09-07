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
      if(viewport.width===1440){
        await page.waitForTimeout(650);
        const drag=await page.evaluate(()=>{const n=G.nodes.find(n=>!n._parentScope&&n.type!=='scope');const r=SpaceGraphWorkspace.renderer;r.focus(n.id);r.mode='move';return {id:n.id,x:n.x,y:n.y,undo:UNDO.length};});
        const r=await page.locator('#sg-surface').boundingBox();
        await page.mouse.move(r.x+r.width/2,r.y+r.height/2);await page.mouse.down();
        await page.mouse.move(r.x+r.width/2+45,r.y+r.height/2+25,{steps:10});await page.mouse.up();
        const moved=await page.evaluate(id=>{const n=G.nodes.find(n=>n.id===id);return {x:n.x,y:n.y,undo:UNDO.length};},drag.id);
        assert.notEqual(moved.x,drag.x);assert.equal(moved.undo,drag.undo+1,'One drag is one undo step');
        await page.locator('#undoBtn').click();
        assert.deepEqual(await page.evaluate(id=>{const n=G.nodes.find(n=>n.id===id);SpaceGraphWorkspace.renderer.mode='orbit';return {x:n.x,y:n.y};},drag.id),{x:drag.x,y:drag.y});
      }
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
      if(viewport.width===1440){
        await page.evaluate(()=>{
          const scope=d=>({id:`ring-${d}`,type:'scope',params:{},children:[
            {id:`input-${d}`,type:'scope.in',params:{name:'x',kind:'text'}},
            d<3?scope(d+1):{id:'tiny-leaf',type:'text.value',params:{value:'recursive leaf'}},
            {id:`output-${d}`,type:'scope.out',params:{name:'x',kind:'text'}}]});
          load({nodes:[scope(0)],wires:[]});
          for(const n of G.nodes.filter(n=>n.type==='scope')){n._view={x:0,y:0,z:.55};applyRingView(n);}
          layoutRing(G.nodes.find(n=>n.id==='ring-0'));SpaceGraphWorkspace.activate(true);SpaceGraphWorkspace.refresh();
        });
        await page.waitForFunction(()=>SpaceGraphWorkspace.scene?.nodes.some(n=>n.id==='tiny-leaf'&&n.scale<.1));
        const recursion=await page.evaluate(()=>{
          const scene=SpaceGraphWorkspace.scene,byId=new Map(scene.nodes.map(n=>[n.id,n]));
          return scene.nodes.filter(n=>n.parent).map(n=>({id:n.id,scale:n.scale,step:n.position[2]-n.size[2]/2-(byId.get(n.parent).position[2]-byId.get(n.parent).size[2]/2)}));
        });
        for(const n of recursion)assert.ok(Math.abs(n.step-110*n.scale)<1e-8,JSON.stringify(n));
        await page.evaluate(()=>SpaceGraphWorkspace.renderer.front());
        const frameBefore=await page.evaluate(()=>JSON.stringify(SpaceGraphWorkspace.renderer.engine.frame(false)));
        for(const provider of ['canvas','svg','gl']){
          await page.getByLabel('Rendering provider',{exact:true}).selectOption(provider);
          await page.waitForTimeout(100);
          assert.equal(await page.evaluate(()=>JSON.stringify(SpaceGraphWorkspace.renderer.engine.frame(false))),frameBefore,'Every provider must consume the identical common frame');
          await page.screenshot({path:path.join(output,`recursive-${provider}.png`)});
        }
        await page.evaluate(()=>SpaceGraphWorkspace.renderer.focus('tiny-leaf'));
        assert.equal(await page.evaluate(()=>{const r=SpaceGraphWorkspace.renderer;return r.engine.pick(r.width/2,r.height/2)?.nodeId;}),'tiny-leaf');
        assert.ok(await page.evaluate(()=>SpaceGraphWorkspace.renderer.engine.svg().includes('recursive leaf')),'Focused descendants retain their parameter previews');
        await page.screenshot({path:path.join(output,'recursive-focused.png')});
      }
      assert.deepEqual(errors,[]);receipts.push({viewport,measure});await page.close();
    }
    console.log(JSON.stringify({output,receipts},null,2));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
