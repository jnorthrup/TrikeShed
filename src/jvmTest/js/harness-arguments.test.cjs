"use strict";
const {test}=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs"),vm=require("node:vm"),path=require("node:path");
const web=path.resolve(__dirname,"../../commonMain/resources/web");
function fixture(){
  const elements=new Map(),element=id=>{if(!elements.has(id))elements.set(id,{open:false,replaceChildren(){},append(){}});return elements.get(id);};
  const harness={selected:"a",board:{"lcnc/program/a":{programCid:"cid-a",document:{nodes:[]}}},drafts:new Map(),dirty:false,running:false,inspectionOnly:()=>false};
  const requests=[],context=vm.createContext({Harness:harness,document:{getElementById:element,createElement:()=>({append(){}})},$:element,fetch:async(url,options)=>{requests.push({url,...options});return {json:async()=>({ok:true})};}});
  vm.runInContext(fs.readFileSync(path.join(web,"harness-arguments.js"),"utf8")+"\nglobalThis.args=HarnessArguments;",context);
  const source=fs.readFileSync(path.join(web,"harness.js"),"utf8");
  vm.runInContext("globalThis.run=({"+source.slice(source.indexOf("  async run() {"),source.indexOf("  async cancelRun() {"))+"}).run;",context);
  harness.run=context.run;harness.message=message=>{harness.messageText=message;};harness.output=result=>{harness.result=result;};
  return {args:context.args,harness,requests};
}
test("invocation JSON preserves null, arrays and omission without editing program state",()=>{
  const {args,harness}=fixture(),before=JSON.stringify(harness.board);
  args.edits("a").set("nullable","null");args.edits("a").set("construction",'{"maxConcurrency":3,"record":true}');args.edits("a").set("list","[1,2]");
  assert.deepEqual(JSON.parse(JSON.stringify(args.inputs("a"))),{nullable:null,construction:{maxConcurrency:3,record:true},list:[1,2]});
  assert.equal(Object.hasOwn(args.inputs("a"),"omitted"),false);assert.equal(Object.keys(args.inputs("b")).length,0);
  assert.equal(JSON.stringify(harness.board),before);assert.equal(harness.drafts.size,0);assert.equal(harness.dirty,false);
});
test("input parsing rejects invalid JSON and bounds the complete invocation",()=>{
  const {args}=fixture(),edits=args.edits("a");edits.set("text","bare");assert.throws(()=>args.inputs("a"),/Invalid JSON for argument text/);
  edits.set("text",JSON.stringify("x".repeat(65536)));assert.throws(()=>args.inputs("a"),/64 KiB/);
  edits.clear();for(let i=0;i<65;i++)edits.set("x"+i,"0");assert.throws(()=>args.inputs("a"),/64 invocation/);
});
test("Run sends typed invocation bindings and blocks malformed input before HTTP",async()=>{
  const {args,harness,requests}=fixture();args.edits("a").set("text",'"through-browser"');await harness.run();
  assert.deepEqual(JSON.parse(requests[0].body),{program:"a",inputs:{text:"through-browser"}});assert.equal(harness.running,false);
  args.edits("a").set("text","broken");await harness.run();assert.equal(requests.length,1);assert.match(harness.messageText,/Invalid JSON/);
});
test("binding evidence is version-bound and cannot regress on replay",()=>{
  const {args,harness}=fixture(),receipt={program:"a",programKey:"lcnc/program/a",programCid:"cid-a",runId:"one",startedAtMs:1,timelineRevision:4,sequence:8,status:"completed"};
  args.record(receipt);args.record({...receipt,timelineRevision:2,sequence:6,status:"running"});assert.equal(args.receipts.get("a").status,"completed");
  args.record({...receipt,programCid:"other",sequence:9});assert.equal(args.receipts.get("a").programCid,"cid-a");
  args.record({...receipt,runId:"older",startedAtMs:0,sequence:10});assert.equal(args.receipts.get("a").runId,"one");
  args.program="missing";args.renderReceipt();harness.drafts.set("a",{});args.program="a";args.renderReceipt();
});
