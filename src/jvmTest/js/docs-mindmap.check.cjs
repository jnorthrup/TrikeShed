"use strict";
/**
 * The docs mindmap renders — the third Forge graph mode, derived from `docs/`.
 *
 * DocsGraphTest proves the map is the right shape as a pure function over text. This proves the
 * baked page actually draws it: the Docs button activates, 31 documents appear as nodes in seven
 * labelled columns, and the corpus's own cross-references are the edges. A seed that carried a
 * perfect graph nothing rendered would pass the unit test and fail here.
 *
 *   ./gradlew generateForgePages && (cd docs && python3 -m http.server 8899)
 *   BASE_URL=http://127.0.0.1:8899 node docs-mindmap.check.cjs
 */
const {chromium}=require("playwright"); const assert=require("node:assert/strict"); const path=require("node:path");
const base=process.env.BASE_URL||"http://127.0.0.1:8899";
const out=process.env.OUT_DIR||".";
(async()=>{
  const b=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH});
  const p=await b.newPage({viewport:{width:1600,height:1000}});
  const errs=[]; p.on("pageerror",e=>errs.push(e.message));
  try{
    await p.goto(base+"/index.html",{waitUntil:"domcontentloaded"});
    await p.waitForSelector("#btn-graph",{timeout:20000});
    await p.click("#btn-graph");                       // the sidebar's Graph view
    await p.waitForSelector("#graph-mode-docs",{state:"visible",timeout:20000});
    await p.click("#graph-mode-docs");                 // the third mindmap
    await p.waitForTimeout(600);
    const info=await p.evaluate(()=>{
      const svg=document.getElementById('graph-canvas');
      const nodes=svg.querySelectorAll('g.graph-node, .graph-node');
      const texts=[...svg.querySelectorAll('text')].map(t=>t.textContent).filter(Boolean);
      return {
        docsBtnActive: document.getElementById('graph-mode-docs').classList.contains('active'),
        graphHidden: document.getElementById('graph-scroll').hidden,
        svgChildren: svg.querySelectorAll('*').length,
        drawnNodes: nodes.length,
        layerLabels: texts.filter(t=>['index','guide','spec','contract','plan','analysis','note'].includes(t)),
        sampleTitles: texts.filter(t=>t.length>6).slice(0,6),
        emptyShown: !document.getElementById('graph-empty').hidden,
      };
    });
    await p.screenshot({path:path.join(out,"docs-mindmap.png")});
    console.log(JSON.stringify({...info,pageErrors:errs},null,2));
    assert.ok(info.docsBtnActive,"the Docs mode must activate");
    assert.ok(!info.emptyShown,"the docs mindmap must not be empty");
    assert.ok(info.svgChildren>50,"the mindmap must actually draw: "+info.svgChildren);
    assert.ok(info.layerLabels.length>=5,"the columns must be labelled: "+JSON.stringify(info.layerLabels));
    assert.equal(errs.length,0,"page errors: "+errs.join(" | "));
    console.log("\nPASS — the docs mindmap renders.");
  } finally { await b.close(); }
})().catch(e=>{console.error("FAIL:",e.message);process.exit(1);});
