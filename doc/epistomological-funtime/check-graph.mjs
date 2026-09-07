import {createRequire} from 'node:module';
import path from 'node:path';
const require=createRequire('/Users/jim/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json');
const {chromium}=require('playwright');
const out=path.dirname(new URL(import.meta.url).pathname);
const browser=await chromium.launch({headless:true});
const page=await browser.newPage();
const errors=[];page.on('pageerror',e=>errors.push(e.message));
await page.goto('file://'+out+'/rete-sumo-preview.html');
const frame=page.frames().find(f=>f!==page.mainFrame());
await frame.waitForSelector('#rete-sumo-graph[data-ready="true"]');
for(const width of [736,360])for(const theme of ['light','dark']){
 await page.setViewportSize({width,height:1000});await page.emulateMedia({colorScheme:theme});
 await frame.selectOption('[data-view]','overview');
 await page.screenshot({path:out+'/graph-'+width+'-'+theme+'.png',fullPage:true});
 const check=await frame.evaluate(()=>{
  const root=document.getElementById('rete-sumo-graph');
  const labels=[...root.querySelectorAll('.node text')];
  const over=labels.filter(t=>{const b=t.getBBox(),p=t.parentNode.querySelector('rect').getBBox();return b.x<p.x-2||b.x+b.width>p.x+p.width+2;});
  return {nodes:labels.length,overflowLabels:over.map(n=>n.textContent),pageOverflow:document.documentElement.scrollWidth>innerWidth+1};
 });
 if(check.nodes!==8||check.overflowLabels.length||check.pageOverflow)throw Error(JSON.stringify({width,theme,...check}));
}
await page.setViewportSize({width:736,height:1000});await page.emulateMedia({colorScheme:'light'});
for(const view of ['inputs','core','rules','belief','sumo','isa','agent','read','all']){
 await frame.selectOption('[data-view]',view);
 const count=await frame.locator('.node').count();if(!count)throw Error('Blank view '+view);
 const scale=await frame.evaluate(()=>{const s=document.querySelector('#rete-sumo-graph svg');return s.getBoundingClientRect().width/s.viewBox.baseVal.width;});
 if(scale<.99)throw Error('Unreadable scaled graph '+view+' '+scale);
}
for(const node of ['net','classifier','closure','jobnet','zip']){
 await frame.selectOption('[data-node]',node);
 if(!await frame.locator('.node.selected').count())throw Error('No selected node '+node);
 if(!await frame.locator('[data-connections] .connection').count())throw Error('No links for '+node);
}
await frame.selectOption('[data-node]','closure');await page.screenshot({path:out+'/graph-closure.png',fullPage:true});
await frame.locator('[data-ledger] summary').click();
const ledger=await frame.locator('[data-ledger-content] .connection').count();
if(ledger<190)throw Error('Incomplete ledger');
console.log(JSON.stringify({errors,ledger,checked:'overview light/dark 736/360; all 9 detailed views; 5 node selections; source ledger'}));
await browser.close();if(errors.length)process.exit(1);
