"use strict";
const {test}=require("node:test");
const assert=require("node:assert/strict");
const {layout,placement,limits}=require("../../commonMain/resources/web/patch-layout.js");

function clearance(boxes,result){
  const positions=new Map(result.positions.map(p=>[p.id,p]));
  const actual=boxes.map(b=>({...b,...positions.get(b.id)}));
  for(let i=0;i<actual.length;i++)for(let j=i+1;j<actual.length;j++){
    const a=actual[i],b=actual[j],gap=limits.gap-.01;
    assert.ok(a.x+a.w+gap<=b.x||b.x+b.w+gap<=a.x||a.y+a.h+gap<=b.y||b.y+b.h+gap<=a.y,
      `overlap: ${a.id}, ${b.id}`);
  }
  assert.ok(actual.every(b=>Number.isFinite(b.x)&&Number.isFinite(b.y)&&b.x>=0&&b.y>=0));
  return actual;
}
const box=(id,x,y,w=220,h=120)=>({id,x,y,w,h});
const length=(boxes,links)=>{
  const nodes=new Map(boxes.map(b=>[b.id,b]));
  return links.reduce((s,l)=>{const a=nodes.get(l.from),b=nodes.get(l.to);
    return s+Math.hypot(b.x-a.x-a.w,b.y+b.h/2-a.y-a.h/2);},0);
};

test("legal candidate gravity shortens patches without mutating the graph",async()=>{
  const boxes=[box("in",-4000,900),box("segment",1000,-900,460,330),box("out",8000,2700)];
  const links=[{from:"in",to:"segment",hint:true},{from:"segment",to:"out",hint:true}];
  const before=JSON.stringify({boxes,links});
  const result=await layout(boxes,links),placed=clearance(boxes,result);
  assert.ok(length(placed,links)<length(boxes,links)*.15);
  assert.equal(JSON.stringify({boxes,links}),before);
  assert.equal(result.hints,2);assert.equal(result.segments,1);
  assert.ok(placed[0].x<placed[1].x&&placed[1].x<placed[2].x);
});

test("large assemblies and fanout retain measured rectangular clearance",async()=>{
  const boxes=[box("hub",0,0,980,660),...Array.from({length:16},(_,i)=>box("p"+i,(i%4)*5000,i*900,180+i*10,80+i*8))];
  const links=boxes.slice(1).map((n,i)=>i%2?{from:"hub",to:n.id}:{from:n.id,to:"hub"});
  const result=await layout(boxes,links);
  assert.ok(length(clearance(boxes,result),links)<length(boxes,links)*.3);
});

test("feedback cycles settle finitely instead of extending layer depth",async()=>{
  const boxes=[box("a",0,0),box("b",0,0),box("c",0,0)];
  const links=[{from:"a",to:"b"},{from:"b",to:"c"},{from:"c",to:"a"}];
  const result=await layout(boxes,links);clearance(boxes,result);
  assert.ok(result.positions.every(p=>p.x<1500&&p.y<1500));
  assert.equal(result.ticks,limits.ticks);
});

test("palette-sized disconnected segments pack compactly without artificial links",async()=>{
  const boxes=[],links=[];
  for(let i=0;i<140;i++){
    for(let j=0;j<4;j++)boxes.push(box(`${i}.${j}`,i*3000,j*5000,200+j*25,90+j*20));
    for(let j=1;j<4;j++)links.push({from:`${i}.0`,to:`${i}.${j}`,hint:true});
  }
  const result=await layout(boxes,links);const placed=clearance(boxes,result);
  assert.equal(result.segments,140);assert.equal(result.links,420);
  const width=Math.max(...placed.map(n=>n.x+n.w)),height=Math.max(...placed.map(n=>n.y+n.h));
  assert.ok(width/height<3&&height/width<3);
  assert.ok(result.steps<=limits.steps);
});

test("deterministic inputs retain nearest authored neighborhoods",async()=>{
  const boxes=[box("a",0,0),box("b",1000,0),box("c",0,700),box("d",1000,700)];
  const links=[{from:"a",to:"b",hint:true},{from:"c",to:"d",hint:true}];
  assert.deepEqual((await layout(boxes,links)).positions,(await layout(boxes,links)).positions);
});

test("a fully collocated connected graph receives hard clearance after cooling",async()=>{
  const boxes=Array.from({length:572},(_,i)=>box(String(i),0,0));
  const links=boxes.slice(1).map((n,i)=>({from:String(i),to:n.id}));
  const result=await layout(boxes,links);clearance(boxes,result);
  assert.equal(result.segments,1);assert.ok(result.steps<=limits.steps);
});

test("limits and stale selection fail without returning partial geometry",async()=>{
  await assert.rejects(layout(Array.from({length:1501},(_,i)=>box(i,0,0)),[]),/size budget/);
  await assert.rejects(layout([box("a",NaN,0)],[]),/geometry/);
  await assert.rejects(layout([box("a",0,0)],[],{valid:()=>false}),/discarded/);
  let now=0;
  await assert.rejects(layout([box("a",0,0),box("b",0,0)],[{from:"a",to:"b"}],{now:()=>now+=1000}),/time budget/);
});

test("a grown assembly moves to vacant space without moving its neighbors",()=>{
  const box={x:0,y:0,w:800,h:1000},neighbors=[{x:0,y:750,w:1000,h:600},{x:1100,y:0,w:800,h:1000}];
  const before=JSON.stringify(neighbors),p=placement(box,neighbors),actual={...box,...p};
  for(const b of neighbors)assert.ok(actual.x+actual.w+72<=b.x||b.x+b.w+72<=actual.x||actual.y+actual.h+72<=b.y||b.y+b.h+72<=actual.y);
  assert.equal(JSON.stringify(neighbors),before);
  assert.deepEqual(placement({x:-1000,y:-1000,w:100,h:100},neighbors),{x:-1000,y:-1000});
});
