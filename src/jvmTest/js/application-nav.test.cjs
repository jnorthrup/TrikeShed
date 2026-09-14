"use strict";
const {test}=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs"),vm=require("node:vm"),path=require("node:path");
const source=fs.readFileSync(path.resolve(__dirname,"../../commonMain/resources/web/application-nav.js"),"utf8");
function fixture(url,views){
  const events={},all=[],storage=new Map([["trikeshed.application.views.v1",JSON.stringify(views)]]);
  const element=()=>{const e={children:[],dataset:{},classList:{remove(){}},setAttribute(){},addEventListener(){},append(...xs){this.children.push(...xs);}};all.push(e);return e;};
  vm.runInNewContext(source,{URL,URLSearchParams,location:new URL(url),
    sessionStorage:{getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v)},
    document:{getElementById(){return null;},createElement:element,body:{prepend(){}},addEventListener(){}},
    window:{addEventListener:(name,fn)=>events[name]=fn},
  });
  return {link:name=>all.find(e=>e.dataset.destination===name),events,storage};
}
test("Board always opens the application board despite a remembered demo destination",()=>{
  const {link}=fixture("http://localhost:8888/graal",{board:"/harness?load=preset-shake#z=0.01",documents:"/documents?path=resume.md",panels:"/panels?example=preset-curator"});
  assert.equal(link("board").href,"/blackboard");
  assert.equal(link("documents").href,"/documents?path=resume.md");
  assert.equal(link("panels").href,"/panels");
});
test("visiting an example does not replace remembered user work",()=>{
  const previous={board:"/harness?load=application#z=1"};
  const {events,storage}=fixture("http://localhost:8888/harness?example=preset-shake",previous);
  events.pagehide();
  assert.deepEqual(JSON.parse(storage.get("trikeshed.application.views.v1")),previous);
});
