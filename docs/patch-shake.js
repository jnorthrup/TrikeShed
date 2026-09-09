"use strict";

// The daemon owns matching. Both surfaces share request guards and verdicts.
async function requestTreeShake(owner,options,namespace) {
  if(owner.shaking||!owner.selected)return;
  const target=owner.parentTarget();if(!target)return;
  const name=owner.selected;
  const revision=owner.parentRevision,parentId=target.handle.nodeId;
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),8000);
  owner.shaking=true;$("#shakeBtn").disabled=true;owner.message("Checking connections in "+name);
  try {
    const program=owner.document(),snapshot=JSON.stringify(program);
    const body=JSON.stringify({program,options:{...options,parentId}});
    if(new TextEncoder().encode(body).length>1048576)throw Error("Connection request payload limit exceeded");
    const response=await fetch("/api/lcnc/treeshake",{method:"POST",signal:controller.signal,headers:{"Content-Type":"application/json"},body});
    const result=await response.json();
    if(!response.ok||!result.ok)throw Error(result.detail||result.error||response.status);
    if(owner.selected!==name||owner.parentRevision!==revision||JSON.stringify(owner.document())!==snapshot){
      owner.message("Connections or selected parent changed during the check; run Shake again");return;
    }
    if(parentId!=null&&result.parentId!==parentId)throw Error("Server did not confirm the selected parent; use an updated server");
    return applyServerTreeShake(result,!!options?.optional,namespace);
  }catch(e){owner.message("Connections refused: "+(e.name==="AbortError"?"check timed out; try again":e.message));}
  finally{clearTimeout(timer);owner.shaking=false;$("#shakeBtn").disabled=false;}
}

let STARVED=new Set();   // node ids whose reach runs on nothing (redraw paints their cables)
let VERDICTS=[];       // [{nodeId,dir,port,cls}] — the shaken sockets and their outcome
function verdictLayer(){
  let L=document.getElementById("verdicts");
  if(!L){ L=document.createElement("div"); L.id="verdicts"; viewport.appendChild(L); }
  return L;
}
function clearVerdicts(){
  document.querySelectorAll(".port.v-ok,.port.v-dead,.port.v-open,.port.v-scope,.port.v-optional,.port.v-binding")
    .forEach(e=>e.classList.remove("v-ok","v-dead","v-open","v-scope","v-optional","v-binding"));
  document.querySelectorAll(".node.starved").forEach(e=>e.classList.remove("starved"));
  verdictLayer().textContent="";
  VERDICTS=[]; STARVED=new Set();
}
/* Build the badges once, staggered, so the pass reads as a pass. */
function buildVerdicts(){
  const L=verdictLayer(); L.textContent="";
  const fragment=document.createDocumentFragment();
  VERDICTS.forEach((v,i)=>{
    const c=portCenter(v.nodeId,v.dir,v.port); if(!c) return;
    const d=document.createElement("div");
    d.className="vmark "+v.cls;
    d.textContent=v.cls==="ok"?"✓":v.cls==="binding"?"=":v.cls==="optional"?"-":v.cls==="dead"?"✕":v.cls==="scope"?"⇱":"?";
    d.style.left=c.x+"px"; d.style.top=c.y+"px";
    d.style.animationDelay=Math.min(i*12,300)+"ms";
    d.title=v.label||"";
    fragment.appendChild(d); v.el=d;v.point=c;
  });
  L.appendChild(fragment);projectVerdicts();
}
function projectVerdicts(){
  if(!VERDICTS.length)return;
  verdictLayer().style.setProperty("--verdict-scale",Math.min(1,view.z));
  const width=viewport.clientWidth,height=viewport.clientHeight;
  for(const v of VERDICTS){
    if(!v.el)continue;
    const c=v.point,x=c&&c.x*view.z+view.x,y=c&&c.y*view.z+view.y;
    const display=c&&x>=-32&&y>=-32&&x<=width+32&&y<=height+32?"":"none";
    if(v.el.style.display!==display)v.el.style.display=display;
    if(display!=="none"){
      v.el.style.left=x+"px";v.el.style.top=y+"px";
    }
  }
}
/* Re-anchor on redraw — cheap, and it keeps a badge on its socket when the
   node it belongs to is dragged. Rebuilding here would restart every animation
   on every pointermove. */
function positionVerdicts(){
  if(!VERDICTS.length) return;
  const positions=VERDICTS.map(v=>[v,v.el&&portCenter(v.nodeId,v.dir,v.port)]);
  for(const [v,c] of positions){
    if(!v.el) continue;
    v.point=c;
  }
  projectVerdicts();
}
/* mark one of a node's OWN ports (a ring's DOM holds its children's ports too) */
function markPort(nd,dir,port,cls){
  if(!nd.el) return;
  for(const pe of nd.el.querySelectorAll('.port[data-dir="'+dir+'"]')){
    if(pe.closest(".node")!==nd.el) continue;
    if(pe.dataset.port!==port) continue;
    pe.classList.add(cls); return;
  }
}
function applyServerTreeShake(res, inclOptional, programName){
  if(programName){
    const id=local=>programName+"::"+local;
    res={...res,made:(res.made||[]).map(m=>({...m,fromNode:id(m.fromNode),toNode:id(m.toNode)})),
      verdicts:(res.verdicts||[]).map(v=>({...v,nodeId:id(v.nodeId)})),starved:(res.starved||[]).map(id)};
  }
  clearVerdicts();
  const made = res.made || [];
  for(const m of made){
    const exists = G.wires.some(w => w.from[0]===m.fromNode && w.from[1]===m.fromPort && w.to[0]===m.toNode && w.to[1]===m.toPort);
    if(!exists) G.wires.push({from:[m.fromNode,m.fromPort], to:[m.toNode,m.toPort]});
  }
  if(made.length){ redraw(); save(); }

  const verdicts = res.verdicts || [];
  for(const v of verdicts){
    const nd = G.nodes.find(x => x.id === v.nodeId);
    if(nd){
      markPort(nd, v.dir, v.port, "v-" + v.status);
      VERDICTS.push({nodeId: v.nodeId, dir: v.dir, port: v.port, cls: v.status, label: v.label});
    }
  }
  buildVerdicts();

  STARVED = new Set(res.starved || []);
  for(const id of STARVED){
    const nd = G.nodes.find(x => x.id === id);
    if(nd && nd.el) nd.el.classList.add("starved");
  }
  if(STARVED.size) redraw();

  const reachable = verdicts.filter(v => v.status === "open");
  const scoped = verdicts.filter(v => v.status === "scope");
  const dead = verdicts.filter(v => v.status === "dead");
  const parts = [];
  parts.push(made.length ? made.length + " cables connected" : "No cables changed");
  const coverage=res.coverage;
  if(coverage&&Number.isInteger(coverage.total)&&coverage.total>0&&Number.isInteger(coverage.connected)&&coverage.connected>=0&&coverage.connected<=coverage.total){
    parts.push(coverage.connected+"/"+coverage.total+" sockets connected ("+Math.floor(100*coverage.connected/coverage.total)+"%)");
  }
  // Preserve the daemon's reason: proximity cannot authorize an effect input.
  const reasons=new Map();
  for(const v of reachable){
    const label=v.label||"Connection unresolved";
    if(!reasons.has(label))reasons.set(label,{count:0,ports:[]});
    const group=reasons.get(label);group.count++;
    if(group.ports.length<3){
      const node=G.nodes.find(n=>n.id===v.nodeId);
      group.ports.push((node?._localId||v.nodeId)+"."+v.port);
    }
  }
  for(const [label,group] of reasons){
    parts.push(group.ports.join(", ")+(group.count>3?" and "+(group.count-3)+" more inputs":"")+": "+label);
  }
  if(scoped.length) parts.push("⇱ " + scoped.length + " scope-blocked");
  if(dead.length) parts.push("✕ " + dead.length + " with no mate on the board");
  if(STARVED.size) parts.push(STARVED.size + " node" + (STARVED.size===1?"":"s") + " downstream run on nothing");
  if(res.outletBlocked) parts.push("⇱ " + res.outletBlocked + " outlet" + (res.outletBlocked===1?"":"s") + " blocked by ring depth");
  const optional=verdicts.filter(v=>v.status==="optional").length;
  if(optional)parts.push(optional+" optional inputs unchanged");
  if(!made.length&&!reachable.length&&!scoped.length&&!dead.length&&!STARVED.size)parts.push("No required cable gaps found");
  $("#status").textContent = parts.join(" · ");
  if(programName)Harness.showConnections(programName,res,$("#status").textContent);

  for(const m of made){
    for(const el of document.querySelectorAll('.port[data-port="'+CSS.escape(m.toPort)+'"]')){
      const nd = G.nodes.find(x => x.id === m.toNode);
      if(nd && el.closest(".node") === nd.el){
        el.classList.add("just-shook");
        setTimeout(() => el.classList.remove("just-shook"), 1400);
      }
    }
    const a = portCenter(m.fromNode, "out", m.fromPort), b = portCenter(m.toNode, "in", m.toPort);
    if(a && b){
      const g = document.createElementNS("http://www.w3.org/2000/svg", "path");
      g.classList.add("cand", "only", "just-merged");
      LandscapeNavigation.wireCurve(g,a,b,viewport,view);
      g.style.stroke = "var(--ok)";
      wiresSvg.appendChild(g);
      setTimeout(() => g.remove(), 1400);
    }
  }
  return made.length;
}
