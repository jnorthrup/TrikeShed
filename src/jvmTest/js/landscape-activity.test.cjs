"use strict";
const {test}=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs"),vm=require("node:vm"),path=require("node:path");
const web=path.resolve(__dirname,"../../commonMain/resources/web");
function fixture(){
  const context=vm.createContext({document:{getElementById:()=>({})},Map,Set,Date});
  vm.runInContext(fs.readFileSync(path.join(web,"landscape.js"),"utf8")+"\nglobalThis.activity=LandscapeActivity;globalThis.landscape=Landscape;",context);
  return {context,activity:context.activity,landscape:context.landscape};
}
const entry={programCid:"cid-a",document:{}};
function run(status,fields={}){return {program:"a",programKey:"lcnc/program/a",programCid:"cid-a",startedAtMs:1000,budgets:{timeoutMs:5000},status,...fields};}

test("installed programs and unpublished grafts are unknown, not operational or spent",()=>{
  const {activity:a}=fixture();
  assert.equal(a.program("a",entry,new Map(),false,true,2000).state,"unknown");
  const runs=a.latest({"lcnc/run/1":run("completed",{outputs:{n:{}}})});
  assert.equal(a.program("a",entry,runs,true,true,2000).state,"unknown");
  assert.equal(a.program("a",{programCid:"new"},runs,false,true,2000).state,"unknown");
  assert.equal(a.program("a",{document:{controls:{inspectionOnly:true}}},runs,false,true,2000).state,"inert");
});

test("program activity expires and disconnected activity is not displayed green",()=>{
  const {activity:a}=fixture(),runs=a.latest({"lcnc/run/1":run("running")});
  assert.equal(a.program("a",entry,runs,false,true,2000).state,"operational");
  assert.equal(a.program("a",entry,runs,false,false,2000).state,"unknown");
  assert.equal(a.program("a",entry,runs,false,true,12000).state,"unknown");
  assert.equal(a.program("a",entry,a.latest({"lcnc/run/2":run("running",{budgets:{}})}),false,true,2000).state,"unknown");
});

test("overlapping runs do not make an older still-running invocation look spent",()=>{
  const {activity:a}=fixture();
  const runs=a.latest({"lcnc/run/old":run("running"),"lcnc/run/new":run("completed",{startedAtMs:1500,sequence:8})});
  const state=a.program("a",entry,runs,false,true,2000);
  assert.equal(state.state,"operational");assert.equal(state.receipt.key,"lcnc/run/old");
});

test("program running is not proof a mux node is executing; empty output is completion evidence",()=>{
  const {activity:a}=fixture(),node={id:"a::n",_localId:"n",type:"mux.chat"};
  const running=a.program("a",entry,a.latest({"lcnc/run/1":run("running")}),false,true,2000);
  assert.equal(a.node(node,running).state,"unknown");
  const done=a.program("a",entry,a.latest({"lcnc/run/2":run("completed",{outputs:{n:{}}})}),false,true,2000);
  assert.equal(a.node(node,done).state,"completed");
  assert.equal(a.node({...node,_localId:"other"},done).state,"unknown");
  assert.equal(a.node({...node,_timer:7},running).state,"operational");
});

test("validation and execution failures do not blame unrelated nodes",()=>{
  const {activity:a}=fixture();
  const program=a.program("a",entry,a.latest({"lcnc/run/1":run("refused",{phase:"validation",violations:[{toNode:"bad"}]})}),false,true,2000);
  assert.equal(program.state,"blocked");
  assert.equal(a.node({_localId:"bad"},program).state,"blocked");
  assert.equal(a.node({_localId:"innocent"},program).state,"unknown");
  for(const [status,state] of [["validating","waiting"],["cancelled","inert"],["interrupted","inert"],["timed_out","blocked"],["failed","blocked"]])
    assert.equal(a.program("a",entry,a.latest({"lcnc/run/1":run(status)}),false,true,2000).state,state);
});

test("NARS and kanban events are historical; shared identifiers are references, not causal claims",()=>{
  const {activity:a}=fixture();
  const board={"narsese/a":{event:"minted",angular:"42",expression:"a --> b"},
    "kanban/review/42":{gloss:"review"},"narsese/b":{expression:"a --> b"},
    "kanban/committed/work/1":{col:"running"},"kanban/rule/claim/2":{jobId:"work"}};
  const links=a.references(board);
  assert.equal(links.length,2);
  assert.equal(links[0].kind,"shared atom reference");
  assert.equal(links.some(l=>l.from==="narsese/b"||l.to==="narsese/b"),false);
  assert.equal(a.fact("kanban/committed/work/1",board["kanban/committed/work/1"]).state,"completed");
  assert.equal(a.fact("narsese/b",board["narsese/b"]).state,"unknown");
  const many=Object.fromEntries(Array.from({length:500},(_,i)=>["narsese/"+i,{angular:"42"}]));
  assert.equal(a.references(many).length,15);
});

test("activity masks are independent of blob masks and preserve node identity",()=>{
  const {landscape:l}=fixture(),node={id:"n",x:10,y:20};
  l.activityNodes.set("n",{state:"completed"});l.mask=23;
  assert.equal(l.activityVisible(node),true);l.activityMask.delete("completed");
  assert.equal(l.activityVisible(node),false);assert.equal(l.mask,23);
  assert.deepEqual(node,{id:"n",x:10,y:20});l.activityMask.add("completed");
  assert.equal(l.activityVisible(node),true);
});
