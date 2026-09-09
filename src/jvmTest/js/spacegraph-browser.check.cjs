'use strict';
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os');
const base=process.env.SPACEGRAPH_BASE_URL||'http://127.0.0.1:8888';
if(process.env.SPACEGRAPH_OFFLINE==='true')require('./spacegraph-offline.check.cjs');else(async()=>{
  if(process.env.SPACEGRAPH_LOCAL_ASSETS!=='true'){
    for(const p of ['/api/health','/panels','/harness'])assert.equal((await fetch(base+p)).status,200,p);
    const assets={'/panels':'src/commonMain/resources/web/panels.html','/patch-layout.js':'src/commonMain/resources/web/patch-layout.js',
      '/harness.js':'src/commonMain/resources/web/harness.js','/patch.js':'src/commonMain/resources/web/patch.js',
      '/spacegraph-shadow.js':'src/commonMain/resources/web/spacegraph-shadow.js','/spacegraph-shadow.css':'src/commonMain/resources/web/spacegraph-shadow.css',
      '/kotlin/TrikeShed.js':'build/processedResources/jvm/main/web/kotlin/TrikeShed.js'};
    for(const [url,file] of Object.entries(assets))assert.equal(await(await fetch(base+url)).text(),await fs.readFile(file,'utf8'),'Live asset mismatch: '+url);
    console.log('Live health, Panels, Harness and seven exact asset matches verified');
  }
  const output=await fs.mkdtemp(path.join(os.tmpdir(),'narchy-spacegraph-'));
  console.log(`Browser artifacts: ${output}`);
  const browser=await chromium.launch({headless:true,args:['--enable-webgl','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']}),receipts=[];
  try{
    for(const viewport of [{width:1440,height:960},{width:390,height:844},{width:2560,height:1440}]){
      const page=await browser.newPage({viewport}),errors=[];page.on('pageerror',e=>errors.push(e.message));
      await page.route('**/*',async route=>{
        const req=route.request(),p=new URL(req.url()).pathname;
        if(process.env.SPACEGRAPH_LOCAL_ASSETS==='true'&&req.method()==='GET'){
          const files={'/spacegraph-shadow.js':['src/commonMain/resources/web/spacegraph-shadow.js','application/javascript'],
            '/panels':['src/commonMain/resources/web/panels.html','text/html'],
            '/patch-layout.js':['src/commonMain/resources/web/patch-layout.js','application/javascript'],
            '/spacegraph-shadow.css':['src/commonMain/resources/web/spacegraph-shadow.css','text/css'],
            '/kotlin/TrikeShed.js':['build/processedResources/jvm/main/web/kotlin/TrikeShed.js','application/javascript']};
          if(files[p])return route.fulfill({status:200,contentType:files[p][1],body:await fs.readFile(files[p][0])});
        }
        if(!['GET','HEAD'].includes(req.method())&&!['/api/lcnc/spacegraph','/api/lcnc/rdf/align','/api/lcnc/typecheck','/api/lcnc/treeshake'].includes(p))return route.fulfill({status:409,contentType:'application/json',body:'{"error":"read-only visual verification"}'});
        return route.continue();
      });
      await page.goto(base+'/panels?load=preset-scope',{waitUntil:'domcontentloaded'});
      await page.waitForFunction(()=>typeof G!=='undefined'&&G.nodes.length>0);
      assert.equal(await page.evaluate(()=>document.body.classList.contains('sg-spatial')),false,'The real editor is the default');
      assert.equal(await page.evaluate(()=>document.body.classList.contains('sg-workbench')),false,'Do not restyle the existing editor');
      assert.equal(await page.locator('#viewport').evaluate(el=>el.inert),false);
      assert.equal(await page.locator('#runBtn').evaluate(el=>el.parentElement.id),'bar');
      assert.equal(await page.locator('#rdfBtn').evaluate(el=>el.parentElement.id),'bar');
      await page.screenshot({path:path.join(output,`default-editor-${viewport.width}.png`)});
      await page.locator('#spacegraphBtn').click();
      await page.waitForFunction(()=>window.SpaceGraphWorkspace?.scene?.nodes.length>0,null,{timeout:45000}).catch(async error=>{
        console.error(JSON.stringify({output,errors,state:await page.evaluate(()=>({badge:document.querySelector('.sg-badge')?.textContent,status:document.getElementById('status')?.textContent,workspace:!!window.SpaceGraphWorkspace,nodes:typeof G==='undefined'?null:G.nodes.length}))}));
        await page.screenshot({path:path.join(output,'failure.png')});throw error;
      });
      await page.waitForTimeout(800);
      assert.equal(await page.evaluate(()=>SpaceGraphWorkspace.backend),'svg');
      await page.screenshot({path:path.join(output,`svg-default-${viewport.width}.png`)});
      await page.getByLabel('Rendering provider',{exact:true}).selectOption('gl');
      const measure=await page.evaluate(()=>{
        const s=SpaceGraphWorkspace.scene,r=SpaceGraphWorkspace.renderer;r.render();
        const gl=r.gl,w=gl.drawingBufferWidth,h=gl.drawingBufferHeight,pixels=new Uint8Array(w*h*4);gl.readPixels(0,0,w,h,gl.RGBA,gl.UNSIGNED_BYTE,pixels);
        let colored=0,samples=0;for(let i=0;i<pixels.length;i+=4*31){samples++;if(Math.abs(pixels[i]-16)+Math.abs(pixels[i+1]-20)+Math.abs(pixels[i+2]-27)>35)colored++;}
        return {nodes:s.nodes.length,cables:s.cables.length,measured:s.nodes.every(n=>n.measured),flat:s.nodes.every(n=>Math.abs(n.position[2]-n.size[2]/2)<1e-10),colored,samples,vertices:r.vertices,engine:typeof r.engine.project,mode:r.cameraValue().mode};
      });
      assert.ok(measure.measured);assert.ok(measure.flat);assert.equal(measure.mode,'ORTHOGRAPHIC');assert.ok(measure.colored>50,JSON.stringify(measure));assert.ok(measure.vertices>measure.nodes*36);assert.equal(measure.engine,'function');
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
        const cdp=await page.context().newCDPSession(page);
        await cdp.send('HeapProfiler.collectGarbage');
        const heapBefore=await cdp.send('Runtime.getHeapUsage');
        const allocations=await page.evaluate(async()=>{
          const r=SpaceGraphWorkspace.renderer,e=r.engine,gl=r.gl;
          let uploads=0,stores=0,projects=0,dom=0;
          const upload=gl.bufferSubData.bind(gl),store=gl.bufferData.bind(gl),project=e.project.bind(e);
          gl.bufferSubData=(...a)=>{uploads++;return upload(...a);};gl.bufferData=(...a)=>{stores++;return store(...a);};
          e.project=(...a)=>{projects++;return project(...a);};
          const observer=new MutationObserver(rows=>dom+=rows.length);
          observer.observe(document.querySelector('.sg-tree'),{childList:true,subtree:true});
          r.render();const first=e.frame(true),baseUploads=uploads,t0=performance.now();
          for(let i=0;i<100;i++){r.render();if(e.frame(true)!==first)throw Error('unchanged frame rebuilt');}
          const unchangedMs=performance.now()-t0,unchangedUploads=uploads-baseUploads;
          const panStart=performance.now();
          for(let i=0;i<40;i++){e.pan(i%2?2:-2,0);r.render();}
          const panMs=performance.now()-panStart;
          const zoomStart=performance.now();
          for(let i=0;i<20;i++){e.zoom(i%2?1/1.02:1.02,r.width/2,r.height/2);r.render();}
          const zoomMs=performance.now()-zoomStart;
          const program=serialize(),geometry=SpaceGraphWorkspace.measurements(),projectStart=performance.now();
          for(let i=0;i<10;i++)r.project('allocation-check',program,geometry,0,false);
          const projectMs=performance.now()-projectStart;projects=0;
          for(const value of ['canvas','svg','gl']){const p=document.querySelector('[aria-label="Rendering provider"]');p.value=value;p.dispatchEvent(new Event('change'));}
          await new Promise(resolve=>setTimeout(resolve,100));observer.disconnect();
          const text=e.frame(false).items.filter(i=>i.type==='text');
          const result={unchangedMs,unchangedUploads,panMs,zoomMs,projectMs,uploads,stores,projects,treeMutations:dom,textCache:r.textCache.size,
            labels:text.length,minTitleSize:Math.min(...text.filter(i=>i.weight===600).map(i=>i.size))};
          gl.bufferSubData=upload;gl.bufferData=store;e.project=project;return result;
        });
        await cdp.send('HeapProfiler.collectGarbage');
        const heapAfter=await cdp.send('Runtime.getHeapUsage');
        allocations.retainedHeapDelta=heapAfter.usedSize-heapBefore.usedSize;
        assert.equal(allocations.unchangedUploads,0);assert.equal(allocations.projects,0);assert.equal(allocations.treeMutations,0);
        assert.ok(allocations.textCache<=2048);assert.ok(allocations.minTitleSize>=11);
        receipts.push({allocations});console.log(JSON.stringify({allocations}));
        await page.evaluate(()=>{window.sgContextLoss=SpaceGraphWorkspace.renderer.gl.getExtension('WEBGL_lose_context');sgContextLoss.loseContext();});
        await page.waitForFunction(()=>SpaceGraphWorkspace.backend==='canvas'&&document.querySelector('.sg-provider').dataset.fallback==='true');
        await page.screenshot({path:path.join(output,'canvas-fallback.png')});
        await page.evaluate(()=>sgContextLoss.restoreContext());
        await page.waitForTimeout(100);
        await page.getByLabel('Rendering provider',{exact:true}).selectOption('gl');
        assert.equal(await page.evaluate(()=>SpaceGraphWorkspace.backend),'gl');
        await page.waitForTimeout(650);
        const drag=await page.evaluate(()=>{const n=G.nodes.find(n=>!n._parentScope&&n.type!=='scope');const r=SpaceGraphWorkspace.renderer;r.focus(n.id);r.mode='move';return {id:n.id,x:n.x,y:n.y,undo:UNDO.length};});
        const r=await page.locator('#sg-surface').boundingBox();
        await page.mouse.move(r.x+r.width/2,r.y+r.height/2);await page.mouse.down();
        await page.mouse.move(r.x+r.width/2+45,r.y+r.height/2+25,{steps:10});await page.mouse.up();
        const moved=await page.evaluate(id=>{const n=G.nodes.find(n=>n.id===id);return {x:n.x,y:n.y,undo:UNDO.length};},drag.id);
        assert.notEqual(moved.x,drag.x);assert.equal(moved.undo,drag.undo+1,'One drag is one undo step');
        await page.locator('#undoBtn').click();
        assert.deepEqual(await page.evaluate(id=>{const n=G.nodes.find(n=>n.id===id);SpaceGraphWorkspace.renderer.mode='pan';return {x:n.x,y:n.y};},drag.id),{x:drag.x,y:drag.y});
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
        const layouts=await page.evaluate(async()=>{
          const receipts=[];
          for(const nested of [false,true])for(const wired of [false,true]){
            SpaceGraphWorkspace.activate(false);
            const kids=[{id:'source',type:'json.value',params:{value:'[]'},x:40,y:40},
              {id:'sink',type:'gauge',params:{label:'Result'},x:40,y:40}];
            const wires=wired?[{from:['source','value'],to:['sink','x']}]:[];
            load({nodes:nested?[{id:'container',type:'scope',x:40,y:40,params:{},children:kids}]:kids,wires});
            PanelsLayout.parentId=nested?'container':null;
            if(nested){const p=G.nodes.find(n=>n.id==='container');p._view={x:0,y:0,z:.5};applyRingView(p);}
            const before=JSON.stringify(G.wires),semantic=JSON.stringify(G.nodes.map(n=>[n.id,n.type,n.params]));
            await fdLayout();
            const status=document.querySelector('#status').textContent;
            if(!status.startsWith('fd: 2 boxes')||status.includes('existing cables only'))throw Error(status);
            if(!wired&&!/[1-9]\d* candidate pulls/.test(status))throw Error('Unconnected typed graph received no hint: '+status);
            if(JSON.stringify(G.wires)!==before||JSON.stringify(G.nodes.map(n=>[n.id,n.type,n.params]))!==semantic)throw Error('Layout changed semantics');
            const a=G.nodes.find(n=>n.id==='source'),b=G.nodes.find(n=>n.id==='sink');
            if(!(a.x+a.el.offsetWidth<=b.x||b.x+b.el.offsetWidth<=a.x||a.y+a.el.offsetHeight<=b.y||b.y+b.el.offsetHeight<=a.y))throw Error('Layout overlap');
            receipts.push({nested,wired,status});
          }
          return receipts;
        });
        receipts.push({layouts});console.log(JSON.stringify({layouts}));
        await page.evaluate(()=>{
          const scope=d=>({id:`ring-${d}`,type:'scope',x:40,y:40,params:{},children:[
            {id:`input-${d}`,type:'scope.in',params:{name:'x',kind:'text'}},
            d<3?scope(d+1):{id:'tiny-leaf',type:'text.value',params:{value:'recursive leaf'}},
            {id:`output-${d}`,type:'scope.out',params:{name:'x',kind:'text'}}]});
          load({nodes:[scope(0)],wires:[]});
          for(const n of G.nodes.filter(n=>n.type==='scope')){n._view={x:0,y:0,z:.55};applyRingView(n);}
          layoutRing(G.nodes.find(n=>n.id==='ring-0'));SpaceGraphWorkspace.activate(true);SpaceGraphWorkspace.refresh();
        });
        await page.waitForFunction(()=>SpaceGraphWorkspace.scene?.nodes.some(n=>n.id==='tiny-leaf'&&n.scale<.1),null,{timeout:5000}).catch(async error=>{
          console.error(JSON.stringify(await page.evaluate(()=>({badge:document.querySelector('.sg-badge').textContent,nodes:SpaceGraphWorkspace.scene?.nodes.map(n=>({id:n.id,scale:n.scale})),geometry:SpaceGraphWorkspace.measurements()}))));
          await page.screenshot({path:path.join(output,'recursive-failure.png')});throw error;
        });
        const recursion=await page.evaluate(()=>{
          const scene=SpaceGraphWorkspace.scene,byId=new Map(scene.nodes.map(n=>[n.id,n]));
          return scene.nodes.filter(n=>n.parent).map(n=>({id:n.id,scale:n.scale,step:n.position[2]-n.size[2]/2-(byId.get(n.parent).position[2]-byId.get(n.parent).size[2]/2)}));
        });
        for(const n of recursion)assert.ok(Math.abs(n.step)<1e-8,JSON.stringify(n));
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
      assert.deepEqual(errors,[]);receipts.push({viewport,measure});
      if(viewport.width===390){
        await page.evaluate(()=>{SpaceGraphWorkspace.activate(true);const p=document.querySelector('[aria-label="Rendering provider"]');p.value='canvas';p.dispatchEvent(new Event('change'));});
        await page.goto(base+'/panels?load=preset-scope&spacegraph=1',{waitUntil:'domcontentloaded'});
        await page.waitForFunction(()=>SpaceGraphWorkspace.scene?.nodes.length>0&&SpaceGraphWorkspace.backend==='canvas');
        assert.equal(await page.locator('.sg-provider').textContent(),'CANVAS');
      }
      await page.close();
    }
    console.log(JSON.stringify({output,receipts},null,2));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
