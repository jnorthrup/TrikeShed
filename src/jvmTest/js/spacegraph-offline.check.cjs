'use strict';
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os');
(async()=>{
  const fixture=JSON.parse(await fs.readFile('build/reports/spacegraph/generated-shake.json','utf8'));
  assert.ok(fixture.concentric,'Regenerate the complete Kotlin fixture before running this check');
  const live=process.env.SPACEGRAPH_LIVE==='true',base=live?process.env.SPACEGRAPH_BASE_URL:'http://spacegraph.invalid';
  if(live){
    for(const p of ['/api/health','/harness','/panels'])assert.equal((await fetch(base+p)).status,200,p);
    for(const asset of ['harness.html','harness.css','harness.js','panels.html','patch.js','patch-layout.js','spacegraph-shadow.js','spacegraph-shadow.css']){
      const url=asset==='harness.html'?'/harness':asset==='panels.html'?'/panels':'/'+asset;
      assert.equal(await(await fetch(base+url)).text(),await fs.readFile('src/commonMain/resources/web/'+asset,'utf8'),'Live asset mismatch: '+asset);
    }
    assert.equal(await(await fetch(base+'/kotlin/TrikeShed.js')).text(),await fs.readFile('build/processedResources/jvm/main/web/kotlin/TrikeShed.js','utf8'));
    console.log('Live health and nine exact served assets verified; persistent writes remain blocked');
  }
  const count=nodes=>nodes.reduce((sum,n)=>sum+1+count(n.children||[]),0),expected=count(fixture.entry.document.nodes);
  assert.ok(expected>600);assert.equal(fixture.entry.document.wires.length,0);
  const output=await fs.mkdtemp(path.join(os.tmpdir(),'spacegraph-offline-'));
  console.log(JSON.stringify({output,generatedNodes:expected,installedWires:0}));
  const browser=await chromium.launch({headless:true,args:['--enable-webgl','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
  try{
    for(const width of [1440,390]){
      const page=await browser.newPage({viewport:{width,height:960}}),errors=[];
      let matching=false;
      let matchResult=fixture.shake;
      page.on('pageerror',error=>errors.push(error.message));
      await page.addInitScript(()=>{window.EventSource=class {addEventListener(){}close(){}};});
      await page.route('**/*',async route=>{
        const req=route.request(),url=new URL(req.url()),p=url.pathname;
        const json=value=>route.fulfill({contentType:'application/json',body:JSON.stringify(value)});
        if(p==='/blackboard/board')return json({epoch:'isolated-fixture',revision:0,board:{'lcnc/vocabulary':fixture.vocabulary,'lcnc/program/preset-shake':fixture.entry}});
        if(p==='/api/lcnc/contracts')return json(fixture.vocabulary);
        if(p==='/api/lcnc/concentric')return json(fixture.concentric);
        if(p==='/api/panels/presets')return json({presets:[fixture.entry]});
        if(p==='/api/panels/preset-shake')return json(url.searchParams.has('entry')?fixture.entry:fixture.entry.document);
        if(p==='/api/lcnc/treeshake'){
          if(!matching)return route.abort('connectionrefused');
          if(!live)return json(fixture.shake);
          const response=await route.fetch();matchResult=await response.json();
          return route.fulfill({response});
        }
        if(p==='/api/lcnc/rdf/align')return json({nodes:[]});
        if(p.startsWith('/api/')||p.startsWith('/blackboard/'))return json({});
        if(req.method()!=='GET')return route.abort();
        if(live)return route.continue();
        const asset=p==='/harness'?'harness.html':p==='/panels'?'panels.html':p.slice(1);
        const root=path.resolve('src/commonMain/resources/web'),file=path.resolve(root,asset);
        if(!file.startsWith(root+path.sep))return route.abort();
        try{return route.fulfill({body:await fs.readFile(asset==='kotlin/TrikeShed.js'?'build/processedResources/jvm/main/web/kotlin/TrikeShed.js':file),
          contentType:asset.endsWith('.css')?'text/css':asset.endsWith('.js')?'application/javascript':asset.endsWith('.html')?'text/html':'application/octet-stream'});}
        catch{return route.fulfill({status:404,body:''});}
      });
      await page.goto(base+'/harness?load=preset-shake');
      await page.waitForFunction(()=>typeof Harness!=='undefined'&&Harness.ready&&Harness.selected==='preset-shake');
      assert.equal(await page.evaluate(()=>G.nodes.length),expected);
      const harnessRings=await page.evaluate(()=>G.nodes.filter(n=>n._ringWorld).map(n=>({id:n.id,w:parseFloat(n._ringWorld.style.width)*n._view.z,h:parseFloat(n._ringWorld.style.height)*n._view.z})));
      assert.equal(harnessRings.length,9);
      for(const ring of harnessRings)assert.ok(ring.w<=560.01&&ring.h<=360.01,JSON.stringify(ring));
      const toolbar=await page.evaluate(()=>{
        const rows=[];
        for(const status of ['Live','Syncing','Reconnecting','Refreshing snapshot'])for(const label of ['preset-shake','preset-shake / depth.0.scope / depth.1.scope / depth.2.scope']){
          Harness.connectionStatus(status);document.querySelector('#parentHandle').textContent=label;
          const r=document.querySelector('#shakeBtn').getBoundingClientRect();rows.push({status,label,x:r.x,y:r.y,w:r.width,h:r.height});
        }
        return rows;
      });
      assert.equal(new Set(toolbar.map(r=>JSON.stringify([r.x,r.y,r.w,r.h]))).size,1,'Toolbar moves with status or scope label');
      const failure=await page.evaluate(async()=>{
        const wires=JSON.stringify(G.wires);await Harness.shake();
        return {wiresUnchanged:JSON.stringify(G.wires)===wires,shaking:Harness.shaking,disabled:document.querySelector('#shakeBtn').disabled,status:document.querySelector('#status').textContent};
      });
      assert.ok(failure.wiresUnchanged);assert.equal(failure.shaking,false);assert.equal(failure.disabled,false);assert.match(failure.status,/Connections refused/);
      await page.screenshot({path:path.join(output,`harness-${width}.png`)});
      console.log(JSON.stringify({width,toolbarStable:true,shakeFailure:failure}));
      matching=true;
      const success=await page.evaluate(async()=>{
        await Harness.shake();
        return {wires:G.wires.map(w=>[w.from[0].replace(/^preset-shake::/,''),w.from[1],w.to[0].replace(/^preset-shake::/,''),w.to[1]]),disabled:document.querySelector('#shakeBtn').disabled};
      });
      assert.equal(success.disabled,false);
      assert.equal(matchResult.ok,true);assert.ok(matchResult.made.length>0);
      assert.deepEqual(success.wires,matchResult.made.map(m=>[m.fromNode,m.fromPort,m.toNode,m.toPort]));
      console.log(JSON.stringify({width,shakeConnected:success.wires.length}));
      if(live){
        const scoped=await page.evaluate(async()=>{
          Harness.setParent(G.nodes.find(n=>n.id==='preset-shake::depth.0.scope'));
          const before=JSON.stringify(G.wires);await Harness.shake();
          return {unchanged:JSON.stringify(G.wires)===before,status:document.querySelector('#status').textContent,parent:Harness.parentTarget().handle.nodeId};
        });
        assert.equal(scoped.parent,'depth.0.scope');assert.ok(scoped.unchanged);
        const fd=[];
        for(let i=0;i<2;i++){
          const result=await page.evaluate(async()=>{
            const wires=JSON.stringify(G.wires);await fdLayout();await new Promise(requestAnimationFrame);
            const n=Harness.parentTarget().node,r=n.el.getBoundingClientRect(),v=viewport.getBoundingClientRect();
            return {unchanged:JSON.stringify(G.wires)===wires,status:document.querySelector('#status').textContent,
              scope:{x:r.x-v.x,y:r.y-v.y,w:r.width,h:r.height},viewport:{w:v.width,h:v.height},
              children:n.children.map(c=>({id:c.id,x:c.x,y:c.y,w:c.el.offsetWidth,h:c.el.offsetHeight}))};
          });
          assert.ok(result.unchanged);assert.match(result.status,/^fd:/);fd.push(result);
          await page.screenshot({path:path.join(output,`harness-scope-fd-${width}-${i}.png`)});
        }
        console.log(JSON.stringify({width,scoped,fd}));
      }
      matching=false;
      await page.goto(base+'/panels?load=preset-shake');
      await page.waitForFunction(n=>typeof G!=='undefined'&&G.nodes.length===n,expected);
      await page.getByRole('button',{name:'SpaceGraph projection',exact:true}).click();
      await page.waitForFunction(n=>SpaceGraphWorkspace.scene?.nodes.length===n,expected,{timeout:30000});
      assert.equal(await page.evaluate(()=>SpaceGraphWorkspace.backend),'svg');
      const wires=await page.evaluate(()=>JSON.stringify(G.wires));
      const switches=[];
      for(const provider of ['svg','canvas','gl','svg']){
        const started=Date.now();await page.getByLabel('Rendering provider',{exact:true}).selectOption(provider);
        await page.waitForFunction(p=>SpaceGraphWorkspace.backend===p,provider);
        assert.equal(await page.getByLabel('Active renderer',{exact:true}).textContent(),provider.toUpperCase());
        assert.equal(await page.evaluate(()=>JSON.stringify(G.wires)),wires);
        const painted=await page.evaluate(p=>{
          const r=SpaceGraphWorkspace.renderer;
          if(p==='svg')return r.output.querySelectorAll('svg path').length;
          let data;
          if(p==='canvas')data=r.context.getImageData(0,0,r.canvas.width,r.canvas.height).data;
          else {const gl=r.gl;data=new Uint8Array(gl.drawingBufferWidth*gl.drawingBufferHeight*4);gl.readPixels(0,0,gl.drawingBufferWidth,gl.drawingBufferHeight,gl.RGBA,gl.UNSIGNED_BYTE,data);}
          let count=0;for(let i=0;i<data.length;i+=4)if(data[i+3]>0&&(p!=='gl'||data[i]!==16||data[i+1]!==20||data[i+2]!==27))count++;return count;
        },provider);
        assert.ok(painted>0,provider+' has no rendered geometry');
        switches.push({provider,elapsedMs:Date.now()-started});
      }
      await page.screenshot({path:path.join(output,`panels-overview-${width}.png`)});
      const nested=await page.evaluate(()=>{
        const n=G.nodes.find(n=>n.id==='depth.0.scope');SpaceGraphWorkspace.select(n.id);SpaceGraphWorkspace.renderer.focus(n.id);
        return {scopes:G.nodes.filter(n=>n.type==='scope').length,measured:SpaceGraphWorkspace.scene.nodes.length};
      });
      await page.waitForTimeout(100);
      const viewportSize=await page.evaluate(()=>{const r=SpaceGraphWorkspace.renderer;return {w:r.width,h:r.height,hostW:r.host.clientWidth,hostH:r.host.clientHeight};});
      assert.equal(viewportSize.w,viewportSize.hostW);assert.equal(viewportSize.h,viewportSize.hostH);
      const focused=await page.evaluate(()=>{
        const r=SpaceGraphWorkspace.renderer,host=r.host.getBoundingClientRect();
        const boxes=[...r.output.querySelectorAll('[data-entity="depth.0.scope"]')].map(el=>el.getBoundingClientRect());
        return {count:boxes.length,left:Math.min(...boxes.map(b=>b.left))-host.left,top:Math.min(...boxes.map(b=>b.top))-host.top,
          right:Math.max(...boxes.map(b=>b.right))-host.left,bottom:Math.max(...boxes.map(b=>b.bottom))-host.top,w:host.width,h:host.height};
      });
      assert.ok(focused.count>0&&focused.left>=-1&&focused.top>=-1&&focused.right<=focused.w+1&&focused.bottom<=focused.h+1,JSON.stringify(focused));
      await page.screenshot({path:path.join(output,`panels-depth-${width}.png`)});
      console.log(JSON.stringify({width,switches,nested,errors}));assert.deepEqual(errors,[]);
      const rings=await page.evaluate(()=>G.nodes.filter(n=>n._ringWorld).map(n=>({id:n.id,z:n._view.z,w:parseFloat(n._ringWorld.style.width)*n._view.z,h:parseFloat(n._ringWorld.style.height)*n._view.z})));
      assert.equal(rings.length,9);
      for(const ring of rings){assert.ok(ring.w<=560.01&&ring.h<=360.01,JSON.stringify(ring));assert.ok(ring.z>0&&ring.z<=1);}
      for(const provider of ['canvas','gl']){
        await page.getByLabel('Rendering provider',{exact:true}).selectOption(provider);
        await page.reload();
        await page.waitForFunction(p=>typeof SpaceGraphWorkspace!=='undefined'&&SpaceGraphWorkspace.backend===p,provider);
        await page.getByRole('button',{name:'SpaceGraph projection',exact:true}).click();
      }
      await page.evaluate(()=>SpaceGraphWorkspace.renderer.gl.getExtension('WEBGL_lose_context').loseContext());
      await page.waitForFunction(()=>SpaceGraphWorkspace.backend==='canvas'&&document.querySelector('.sg-provider').dataset.fallback==='true');
      assert.equal(await page.getByLabel('Active renderer',{exact:true}).textContent(),'CANVAS');
      console.log(JSON.stringify({width,persistence:['canvas','gl'],contextLossFallback:'canvas',rings}));
      assert.deepEqual(errors,[]);
      await page.close();
    }
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
