"use strict";

const LandscapeActivity = {
  states:[
    {id:"operational",label:"Operational",color:"#69cd92",hint:"Observed running program or armed browser source; not proof every child is executing"},
    {id:"waiting",label:"Waiting",color:"#e4c66f",hint:"Validation or an explicit waiting state"},
    {id:"completed",label:"Completed",color:"#7baedc",hint:"A recorded result, not a currently running process"},
    {id:"blocked",label:"Blocked",color:"#ee8884",hint:"Recorded failure, refusal or timeout"},
    {id:"inert",label:"Inert",color:"#a9a0ba",hint:"Explicitly stopped, cancelled or inspection-only"},
    {id:"unknown",label:"Unknown",color:"#9da6ac",hint:"Missing, disconnected, expired or different-version evidence"},
  ],
  latest(board) {
    const runs=new Map();
    for(const [key,r] of Object.entries(board)){
      if(!key.startsWith("lcnc/run/")||!r?.programKey)continue;
      const list=runs.get(r.programKey)||[];list.push({key,...r});runs.set(r.programKey,list);
    }
    for(const list of runs.values())list.sort((a,b)=>(b.startedAtMs||0)-(a.startedAtMs||0)||(b.sequence||0)-(a.sequence||0));
    return runs;
  },
  program(name,entry,runs,draft,connected,now=Date.now()) {
    const evidence={state:"unknown",reason:"No run receipt for this program version"};
    if(draft)return {...evidence,reason:"Unpublished draft; published receipts do not describe these edits"};
    if(entry?.document?.controls?.inspectionOnly)return {state:"inert",reason:"Inspection-only assembly; execution disabled"};
    const history=runs.get("lcnc/program/"+name)||[];
    const matching=history.filter(r=>entry?.programCid&&r.programCid===entry.programCid);
    const active=matching.filter(r=>["running","validating"].includes(r.status));
    const receipt=active[0]||matching[0];if(!receipt)return history.length?
      {...evidence,lastReceipt:history[0],reason:"No version-matched receipt; last recorded run was "+history[0].status}:evidence;
    const base={receipt,atMs:receipt.finishedAtMs||receipt.startedAtMs,activeRuns:active.length};
    if(active.length){
      if(!connected)return {...base,state:"unknown",reason:"Disconnected; last observed run was "+receipt.status};
      const timeout=receipt.budgets?.timeoutMs;
      if(!Number.isFinite(receipt.startedAtMs)||!Number.isFinite(timeout)||now>receipt.startedAtMs+timeout+5000)
        return {...base,state:"unknown",reason:"Run freshness unconfirmed; no timely terminal receipt"};
    }
    const states={running:"operational",validating:"waiting",completed:"completed",failed:"blocked",refused:"blocked",timed_out:"blocked",cancelled:"inert",interrupted:"inert"};
    return {...base,state:states[receipt.status]||"unknown",reason:"Program "+receipt.status+(active.length>1?"; "+active.length+" concurrent runs":"")};
  },
  node(n,program) {
    if(n._timer)return {state:"operational",reason:"Browser timer armed; this is not proof of a server invocation"};
    if(n._es?.readyState===1)return {state:"operational",reason:"Browser event subscription connected"};
    if(program.state==="inert"&&!program.receipt)return program;
    const r=program.receipt,id=n._localId||n.id;
    if(r?.status==="completed"&&Object.prototype.hasOwnProperty.call(r.outputs||{},id))
      return {...program,state:"completed",reason:"Output recorded for node "+id};
    if(r?.phase==="validation"&&(r.violations||[]).some(v=>v.toNode===id||v.fromNode===id))
      return {...program,state:"blocked",reason:"Validation violation names node "+id};
    return {...program,state:"unknown",reason:r?program.reason+"; node execution phase not reported":program.reason};
  },
  fact(key,value) {
    if(key.startsWith("kanban/committed/")||key.startsWith("kanban/review/")||key.startsWith("kanban/rule/")||
      (key.startsWith("narsese/")&&["minted","revised","dependent-rete-firing"].includes(value?.event)))
      return {state:"completed",reason:"Recorded event, not current worker activity",atMs:value?.atMs};
    return {state:"unknown",reason:"No execution lifecycle on this fact"};
  },
  references(board) {
    const atoms=new Map(),jobs=new Map(),links=[],seen=new Set();
    const index=(map,id,key)=>{if(typeof id!=="string"&&typeof id!=="number")return;
      const k=String(id);if(!k)return;const list=map.get(k)||[];if(list.length<16)list.push(key);map.set(k,list);};
    for(const [key,v] of Object.entries(board)){
      if(!v||typeof v!=="object")continue;
      index(atoms,v.angular??(key.startsWith("kanban/review/")?key.slice(14):null),key);
      let job=v.jobId;
      if(!job&&key.startsWith("kanban/committed/")){const tail=key.slice(17);job=tail.slice(0,tail.lastIndexOf("/"));}
      index(jobs,job,key);
    }
    for(const [groups,kind] of [[atoms,"shared atom reference"],[jobs,"shared job reference"]]){
      for(const keys of groups.values())for(let i=1;i<keys.length&&links.length<128;i++){
        const from=keys[0],to=keys[i],id=from+"\n"+to+"\n"+kind;
        if(!seen.has(id)){seen.add(id);links.push({from,to,kind});}
      }
    }
    return links;
  },
};

function visibleClosure(node) {
  if(typeof Harness!=="undefined"&&node?._program===Harness.selected&&Landscape.details.has(node.id))return node;
  let result=node,parent=node?._parentScope;
  while(parent){const r=parent.el.getBoundingClientRect();if(r.width<420||r.height<240)result=parent;parent=parent._parentScope;}
  return result;
}

const Landscape = {
  detailOwner:null, details:new Set(),
  canvas:document.getElementById("landscape"), pending:0, terrain:null, rows:[], dbs:[], edges:[], hits:[],
  storeRows:[], runtimeRows:[], mask:127, heapBusy:false, heapStatus:"Not loaded", poolStatus:"Not loaded",
  activityMask:new Set(LandscapeActivity.states.map(s=>s.id)), activityNodes:new Map(), activityPrograms:new Map(), activityFacts:new Map(), activityLinks:[],
  refreshActivity() {
    const runs=LandscapeActivity.latest(Harness.board),now=Date.now();
    this.activityPrograms=new Map();this.activityNodes=new Map();this.activityFacts=new Map();
    for(const key of Harness.programs()){
      const name=key.slice(13);this.activityPrograms.set(name,LandscapeActivity.program(name,Harness.board[key],runs,Harness.drafts.has(name),Harness.live===true,now));
    }
    for(const n of G.nodes)this.activityNodes.set(n.id,LandscapeActivity.node(n,this.activityPrograms.get(n._program)||{state:"unknown",reason:"No program evidence"}));
    for(const [key,value] of Object.entries(Harness.board))if(!key.startsWith("lcnc/"))this.activityFacts.set(key,LandscapeActivity.fact(key,value));
    this.activityLinks=LandscapeActivity.references(Harness.board);
    const paint=(el,evidence,label,inspect)=>{
      if(!el||!evidence)return;
      const state=LandscapeActivity.states.find(s=>s.id===evidence.state);
      el.dataset.activity=evidence.state;el.style.setProperty("--activity-color",state.color);
      el.classList.toggle("activity-muted",!this.activityMask.has(evidence.state));
      let lamp=el.querySelector(":scope > .activity-lamp");
      if(!lamp){lamp=document.createElement("button");lamp.className="activity-lamp";lamp.textContent="●";
        lamp.addEventListener("pointerdown",e=>e.stopPropagation());
        lamp.addEventListener("click",e=>{e.stopPropagation();inspect();});el.append(lamp);}
      lamp.title=state.label+": "+evidence.reason;lamp.setAttribute("aria-label",label+": "+state.label+". Inspect activity");
    };
    for(const n of G.nodes)paint(n.el,this.activityNodes.get(n.id),n.type,()=>this.inspectActivity(n._program,n.id));
    for(const el of document.querySelectorAll(".territory[data-territory]")){
      const key=el.dataset.territory;if(key.startsWith("lcnc/program/"))paint(el,this.activityPrograms.get(key.slice(13)),key.slice(13),()=>this.inspectActivity(key.slice(13)));
    }
    for(const el of document.querySelectorAll(".fact-row[data-key],.fact-cell[data-key]")){
      const evidence=this.activityFacts.get(el.dataset.key);if(!evidence)continue;
      el.dataset.activity=evidence.state;el.style.setProperty("--activity-color",LandscapeActivity.states.find(s=>s.id===evidence.state).color);
      el.classList.toggle("activity-muted",!this.activityMask.has(evidence.state));el.title=el.dataset.key+"\n"+evidence.reason;
    }
    const counts=new Map(LandscapeActivity.states.map(s=>[s.id,0]));
    for(const e of [...this.activityPrograms.values(),...this.activityNodes.values(),...this.activityFacts.values()])counts.set(e.state,counts.get(e.state)+1);
    document.querySelectorAll("#activityCategories output").forEach(el=>el.textContent=String(counts.get(el.dataset.state)||0));
    const selection=document.getElementById("activitySelection"),current=this.activityPrograms.get(Harness.selected);
    if(selection){selection.textContent=(Harness.selected||"No program")+": "+(LandscapeActivity.states.find(s=>s.id===current?.state)?.label||"Unknown");
      selection.title=current?.reason||"No selected program";selection.disabled=!Harness.selected;}
    this.schedule();
  },
  activityVisible(node){return this.activityMask.has(this.activityNodes.get(node?.id)?.state||"unknown");},
  inspectActivity(program,nodeId) {
    const evidence=nodeId?this.activityNodes.get(nodeId):this.activityPrograms.get(program);
    Harness.beginInspection(program+(nodeId?" / "+nodeId:""),"Activity evidence");Harness.rawSheets(true);
    document.getElementById("factValue").textContent=JSON.stringify({state:evidence?.state||"unknown",reason:evidence?.reason,
      atMs:evidence?.atMs,programCid:Harness.board["lcnc/program/"+program]?.programCid,receipt:evidence?.receipt||null,lastOtherVersionReceipt:evidence?.lastReceipt||null},null,2);
  },
  objectBox:{x:3000,y:1000,w:2200,h:1600},
  positionObjects() {
    const x=Harness.programRight()+1760;
    if(this.objectBox.x===x)return;
    this.objectBox={x,y:0,w:3200,h:2400};
    this.terrain?.setRows(this.rows,this.objectBox,this.dbs);
  },
  schedule() {if(this.pending)return;this.pending=requestAnimationFrame(()=>{this.pending=0;this.draw();});},
  detailFor(node, box, absorbed) {
    if(this.detailOwner!==Harness.selected){this.detailOwner=Harness.selected;this.details.clear();}
    const active=node._program===this.detailOwner;
    const readable=!absorbed&&box.w>=115&&box.h>=38;
    if(active&&readable)this.details.add(node.id);
    return active&&this.details.has(node.id);
  },
  draw() {
    if(typeof Harness==="undefined"||!Harness.ready)return;
    const width=viewport.clientWidth,height=viewport.clientHeight,dpr=devicePixelRatio||1;
    if(this.canvas.width!==Math.round(width*dpr)||this.canvas.height!==Math.round(height*dpr)){
      this.canvas.width=Math.round(width*dpr);this.canvas.height=Math.round(height*dpr);
    }
    const ctx=this.canvas.getContext("2d");ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,width,height);
    const screen=b=>({x:b.x*view.z+view.x,y:b.y*view.z+view.y,w:b.w*view.z,h:b.h*view.z});
    this.hits=[];
    for(const [key,box] of Harness.positions){
      const b=screen(box);if(b.x>width||b.y>height||b.x+b.w<0||b.y+b.h<0)continue;
      const program=key.startsWith("lcnc/program/");
      if(view.z<.45){
        ctx.fillStyle=program?"#1b3738":key==="narsese"?"#3a3624":"#222a30";ctx.fillRect(b.x,b.y,b.w,b.h);
        ctx.strokeStyle=Harness.flashes.has(key)?"#e0c26d":program?"#639d96":"#7f8990";ctx.lineWidth=1;ctx.strokeRect(b.x,b.y,b.w,b.h);
        if(b.w>75&&b.h>22){ctx.fillStyle="#d8e5e2";ctx.font="12px system-ui";ctx.fillText((program?key.slice(13):key).slice(0,Math.floor(b.w/7)),b.x+5,b.y+16);}
        if(program){const evidence=this.activityPrograms.get(key.slice(13));
          if(evidence&&this.activityMask.has(evidence.state)){ctx.fillStyle=LandscapeActivity.states.find(s=>s.id===evidence.state).color;ctx.fillRect(b.x,b.y,Math.min(b.w,8),Math.min(b.h,8));}}
      }
      this.hits.push({box,key});
    }
    if(this.terrain)this.terrain.draw({s:view.z,ox:-view.x/view.z,oy:-view.y/view.z},width,height);
    const byId=new Map(G.nodes.map(n=>[n.id,n])),vr=viewport.getBoundingClientRect();
    ctx.strokeStyle="#81aaa8";ctx.lineWidth=.7;
    for(const wire of G.wires){
      const from=byId.get(wire.from[0]),to=byId.get(wire.to[0]);if(!from?.el||!to?.el)continue;
      const closure=visibleClosure(from);
      if(closure===from||closure!==visibleClosure(to)||!closure._childHost)continue;
      const a=from.el.getBoundingClientRect(),b=to.el.getBoundingClientRect(),clip=closure._childHost.getBoundingClientRect();
      if(clip.right<vr.left||clip.left>vr.right||clip.bottom<vr.top||clip.top>vr.bottom)continue;
      ctx.save();ctx.beginPath();ctx.rect(clip.left-vr.left,clip.top-vr.top,clip.width,clip.height);ctx.clip();
      ctx.beginPath();ctx.moveTo(a.right-vr.left,a.top+a.height/2-vr.top);ctx.lineTo(b.left-vr.left,b.top+b.height/2-vr.top);ctx.stroke();ctx.restore();
    }
    for(const n of G.nodes){
      if(!n.el)continue;
      const rect=n.el.getBoundingClientRect(),vr=viewport.getBoundingClientRect();
      const box={x:(rect.left-vr.left-view.x)/view.z,y:(rect.top-vr.top-view.y)/view.z,w:rect.width/view.z,h:rect.height/view.z};
      const b=screen(box),scope=!!n.children?.length;
      const absorbed=visibleClosure(n)!==n;
      const onScreen=b.x<=width&&b.y<=height&&b.x+b.w>=0&&b.y+b.h>=0;
      const detail=onScreen&&this.detailFor(n,b,absorbed);
      n.el.style.visibility=detail?"visible":"hidden";
      n.el.inert=n._program!==Harness.selected;
      if(detail||!onScreen)continue;
      const size=Math.min(b.w,b.h);
      // The same interior survives at every depth, down to a pixel. Collapsing
      // interaction does not erase the nested spatial structure.
      if(absorbed&&size<1.5)continue;
      const hue=this.terrain?.hueOf(n._program||n.type)||175;
      ctx.save();
      if(!this.activityVisible(n))ctx.globalAlpha=.16;
      for(let parent=n._parentScope;parent;parent=parent._parentScope){
        const clip=parent._childHost.getBoundingClientRect();ctx.beginPath();ctx.rect(clip.left-vr.left,clip.top-vr.top,clip.width,clip.height);ctx.clip();
      }
      ctx.fillStyle=scope?`hsl(${hue} 23% 28%)`:n.type.startsWith("beliefs.")?"#b2a270":n.type.startsWith("vm.")?"#ad93c2":"#669b9c";
      if(size<4){ctx.fillRect(b.x+b.w/2-1,b.y+b.h/2-1,2,2);}
      else {ctx.fillRect(b.x,b.y,b.w,b.h);ctx.strokeStyle=scope?"#8ac5bb":"#28383b";ctx.strokeRect(b.x,b.y,b.w,b.h);}
      const activity=this.activityNodes.get(n.id);
      if(activity&&size>=4){ctx.fillStyle=LandscapeActivity.states.find(s=>s.id===activity.state).color;ctx.fillRect(b.x,b.y,Math.min(4,b.w),Math.min(8,b.h));}
      if(b.w>85&&b.h>20){
        const label=n.type==="scope.in"?"in: "+(n.params?.name||"?"):n.type==="scope.out"?"yield: "+(n.params?.name||"?"):n.type;
        ctx.fillStyle=scope?"#d7ebe5":"#101c1d";ctx.font="10px system-ui";ctx.fillText(label.slice(0,Math.floor(b.w/6)),b.x+4,b.y+13);
      }
      ctx.restore();
      this.hits.push({box,node:n});
    }
    if(view.z<.45)for(const [key,box] of Harness.positions){
      const b=screen(box);if(b.w<75||b.h<22||b.x>width||b.y>height||b.x+b.w<0||b.y+b.h<0)continue;
      const program=key.startsWith("lcnc/program/");ctx.fillStyle="#152124";ctx.fillRect(b.x,b.y,Math.min(b.w,330),20);
      ctx.fillStyle="#d8e5e2";ctx.font="12px system-ui";ctx.fillText((program?key.slice(13):key).slice(0,Math.floor(Math.min(b.w,330)/7)),b.x+5,b.y+14);
    }
    // These links have explicit references: program.ref names and object DAG edges.
    ctx.strokeStyle="#ac9dc5";ctx.lineWidth=1;ctx.setLineDash([4,5]);
    for(const n of G.nodes){
      const target=n.params?.program||n.subprogram;
      if(!target)continue;
      const to=Harness.positions.get("lcnc/program/"+target),from=Harness.positions.get("lcnc/program/"+n._program);
      if(!to||!from||to===from)continue;
      const a=screen(from),b=screen(to);ctx.beginPath();ctx.moveTo(a.x+a.w,a.y+a.h/2);ctx.quadraticCurveTo(Math.max(a.x+a.w,b.x+b.w)+50,(a.y+b.y)/2,b.x+b.w,b.y+b.h/2);ctx.stroke();
    }
    for(const edge of this.edges){
      const source=this.terrain?.nodeFor(edge.from),target=this.terrain?.nodeFor(edge.to);
      if(!this.terrain?.visible(source)||!this.terrain.visible(target))continue;
      const from=source.rect,to=target.rect;if(!from||!to)continue;
      const a=screen(from),b=screen(to);ctx.beginPath();ctx.moveTo(a.x+a.w/2,a.y+a.h/2);ctx.lineTo(b.x+b.w/2,b.y+b.h/2);ctx.stroke();
    }
    ctx.setLineDash([]);
    const closureKey=Harness.selected+":"+G.nodes.map(n=>visibleClosure(n)?.id).join("|");
    if(this.closureKey!==closureKey){this.closureKey=closureKey;redraw();}
  },
  async refresh() {
    this.initLegend();
    try {
      const response=await fetch("/api/graal/map");if(!response.ok)throw Error(response.status);
      const map=await response.json();this.storeRows=map.rows||[];this.dbs=map.dbs||[];
      this.positionObjects();
      if(!this.terrain)this.terrain=createGraalTerrain({canvas:this.canvas,invalidate:()=>this.schedule()});
      this.updateTerrain();
    }catch(e){Harness.message("Object terrain unavailable: "+e.message);}
    // Runtime diagnostics must not delay blackboard connection or accepted work.
    if(!this.heapLoaded)void this.refreshHeap();
  },
  updateTerrain() {
    this.rows=this.storeRows.concat(this.runtimeRows);
    this.terrain?.setRows(this.rows,this.objectBox,this.dbs);
    this.terrain?.setMask(this.mask);
    this.updateLegend();Harness.schedule();this.schedule();
  },
  initLegend() {
    if(this.legend)return;
    this.legend=document.getElementById("topologyLegend");if(!this.legend)return;
    try{const saved=JSON.parse(localStorage.getItem("graal.topology.mask"));
      if(Number.isInteger(saved)&&saved>=0&&saved<=GraalTopology.all)this.mask=saved;
    }catch(_){}
    try{const saved=JSON.parse(localStorage.getItem("graal.activity.mask"));
      if(Array.isArray(saved))this.activityMask=new Set(saved.filter(id=>LandscapeActivity.states.some(s=>s.id===id)));
    }catch(_){}
    const items=document.getElementById("topologyCategories");
    for(const c of GraalTopology.categories){
      const label=document.createElement("label");label.title=c.hint;
      const input=document.createElement("input");input.type="checkbox";input.value=c.id;
      input.checked=!!(this.mask&GraalTopology.bit(c.id));input.setAttribute("aria-label",c.label);
      input.addEventListener("change",()=>this.setCategory(c.id,input.checked));
      const swatch=document.createElement("i");swatch.style.background=c.color;swatch.setAttribute("aria-hidden","true");
      const name=document.createElement("span");name.textContent=c.label;
      const count=document.createElement("output");count.dataset.category=c.id;
      label.append(input,swatch,name,count);items.append(label);
    }
    const activityItems=document.getElementById("activityCategories");
    document.getElementById("activitySelection").addEventListener("click",()=>this.inspectActivity(Harness.selected));
    for(const s of LandscapeActivity.states){
      const label=document.createElement("label");label.title=s.hint;
      const input=document.createElement("input");input.type="checkbox";input.checked=this.activityMask.has(s.id);input.setAttribute("aria-label",s.label);
      input.addEventListener("change",()=>{
        if(input.checked)this.activityMask.add(s.id);else this.activityMask.delete(s.id);
        try{localStorage.setItem("graal.activity.mask",JSON.stringify([...this.activityMask]));}catch(_){}
        this.refreshActivity();redraw();
      });
      const swatch=document.createElement("i");swatch.style.background=s.color;swatch.setAttribute("aria-hidden","true");
      const name=document.createElement("span");name.textContent=s.label;
      const count=document.createElement("output");count.dataset.state=s.id;
      label.append(input,swatch,name,count);activityItems.append(label);
    }
    this.refreshActivity();
    this.activityTimer=setInterval(()=>{if(Harness.ready)this.refreshActivity();},1000);
    document.getElementById("refreshHeap").addEventListener("click",()=>this.refreshHeap());
    this.updateLegend();
  },
  setCategory(id,enabled) {
    const bit=GraalTopology.bit(id);this.mask=enabled?this.mask|bit:this.mask&~bit;
    try{localStorage.setItem("graal.topology.mask",JSON.stringify(this.mask));}catch(_){}
    this.terrain?.setMask(this.mask);this.updateLegend();this.schedule();
  },
  updateLegend() {
    if(!this.legend)return;
    const counts=new Map(GraalTopology.categories.map(c=>[c.id,0]));let visible=0;
    for(const row of this.rows){const id=GraalTopology.category(row);counts.set(id,(counts.get(id)||0)+1);
      if(this.mask&GraalTopology.bit(id))visible++;}
    this.legend.querySelectorAll("output[data-category]").forEach(el=>{el.textContent=String(counts.get(el.dataset.category)||0);});
    this.legend.querySelectorAll("#topologyCategories input[type=checkbox]").forEach(el=>{el.checked=!!(this.mask&GraalTopology.bit(el.value));});
    document.getElementById("topologyCount").textContent=visible+" / "+this.rows.length;
    document.getElementById("heapAvailability").textContent=this.heapStatus;
    document.getElementById("poolAvailability").textContent=this.poolStatus;
    document.getElementById("refreshHeap").disabled=this.heapBusy;
  },
  async refreshHeap() {
    if(this.heapBusy)return;
    this.heapBusy=true;this.heapStatus="Heap: loading";this.poolStatus="Pools: loading";this.updateLegend();
    const read=async path=>{
      const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),10000);
      try{const response=await fetch(path,{signal:controller.signal});if(!response.ok)throw Error("HTTP "+response.status);
        const data=JSON.parse(await this.readText(response));
        if(!data||typeof data!=="object"||Array.isArray(data))throw Error("invalid measurement payload");
        return data;
      }catch(e){if(controller.signal.aborted)throw Error("request timed out (10s)");throw e;}
      finally{clearTimeout(timer);}
    };
    let heap={},vitals={};
    // Independent sources: a failed histogram must not hide available pool data.
    try{heap=await read("/api/graal/heap");
      this.heapStatus=Array.isArray(heap.rows)&&heap.rows.length?"Live histogram: "+new Date(heap.atMs).toLocaleTimeString():"Live histogram unavailable";
      if(!Array.isArray(heap.allocation)||!heap.allocation.length)this.heapStatus+="; no allocation samples";
    }catch(e){this.heapStatus="Heap unavailable: "+e.message;}
    try{vitals=await read("/api/graal/vitals");
      this.poolStatus=vitals.gc?.lane?.pools?.length?"GC pool samples: "+new Date(vitals.gc.lane.atMs).toLocaleTimeString():"GC pool samples unavailable";
    }catch(e){this.poolStatus="Pools unavailable: "+e.message;}
    this.runtimeRows=GraalTopology.heapRows(heap,vitals);this.heapLoaded=true;this.heapBusy=false;
    if(!this.terrain)this.terrain=createGraalTerrain({canvas:this.canvas,invalidate:()=>this.schedule()});
    this.updateTerrain();
  },
  async inspect(id) {
    const runtime=this.terrain?.nodeFor(id)?.detail;
    if(runtime?.runtime){
      Harness.beginInspection(runtime.name,GraalTopology.categories.find(c=>c.id===runtime.category)?.label||"Runtime","Measured snapshot");
      document.getElementById("factValue").textContent=JSON.stringify(runtime,null,2);
      Harness.rawSheets(true);return;
    }
    const signal=Harness.beginInspection(id,"Object","Loading");
    const dialog=document.getElementById("factInspector"),body=document.getElementById("factValue");
    try {
      const response=await fetch(this.terrain.url(id),{signal});if(!response.ok)throw Error(response.status);
      const doc=JSON.parse(await this.readText(response));if(signal.aborted)return;
      body.textContent=JSON.stringify(doc,null,2);
      Harness.loadSheets([{url:"/api/graal/sheet?id="+encodeURIComponent(id)}],id);
      const attachment=doc._attachments?.content;
      if(attachment){
        try {
          const content=await fetch(this.terrain.url(id)+"/content",{signal});
          if(content.ok){
            const type=content.headers.get("content-type")||"";
            const texty=type.startsWith("text/")||/json|javascript|xml/.test(type)||/\.(kt|kts|py|md|txt|java|rs|sh|js|html|css)$/.test(id);
            const bytes=await this.readBytes(content,texty?131072:2048);
            if(signal.aborted)return;
            body.textContent=texty?new TextDecoder().decode(bytes):JSON.stringify(doc,null,2)+"\n\n"+Array.from(bytes,b=>b.toString(16).padStart(2,"0")).join(" ");
          }
        }catch(e){if(signal.aborted)return;body.textContent+="\n\nContent preview unavailable: "+e.message;}
      }
      const dagResponse=await fetch("/api/graal/dag?id="+encodeURIComponent(id),{signal});
      if(dagResponse.ok){const dag=JSON.parse(await this.readText(dagResponse));if(signal.aborted)return;
        this.edges=(dag.edges||[]).slice(0,256).map(e=>({...e,from:id}));
        for(const edge of this.edges){
          const button=Harness.el("button","terrain-ref",edge.kind+" → "+edge.to);
          button.dataset.relation=edge.kind==="pointcut-source"?"association":"reference";
          if(button.dataset.relation==="association")button.title="Class-name association, not causal support";
          button.addEventListener("click",()=>this.inspect(edge.to));dialog.append(button);
        }
      }
      this.schedule();
    }catch(e){if(!signal.aborted){body.textContent="Object read failed: "+e.message;Harness.rawSheets(true);}}
  },
  async inspectCid(cid, programKey) {
    const signal=Harness.beginInspection(cid,"Immutable program version","Loading");
    const body=document.getElementById("factValue");
    try {
      const url="/api/lcnc/content?cid="+encodeURIComponent(cid)+(programKey?"&key="+encodeURIComponent(programKey):"");
      const response=await fetch(url,{signal});if(!response.ok)throw Error(response.status);
      const text=await this.readText(response);if(signal.aborted)return;
      body.textContent=text;
      await Harness.loadSheets([{url:url+"&view=sheet"}],cid);
    }catch(e){if(!signal.aborted){body.textContent="Version read failed: "+e.message;Harness.rawSheets(true);}}
  },
  async readBytes(response, limit=1048576) {
    const reader=response.body?.getReader();if(!reader)throw Error("Response stream unavailable");
    const chunks=[];let size=0;
    try {
      if(Number(response.headers.get("content-length"))>limit)throw Error("payload_limit");
      while(true){
        const {done,value}=await reader.read();if(done)break;
        size+=value.byteLength;if(size>limit)throw Error("payload_limit");chunks.push(value);
      }
    }catch(e){await reader.cancel().catch(()=>{});throw e;}finally{reader.releaseLock();}
    const bytes=new Uint8Array(size);let offset=0;
    for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.byteLength;}
    return bytes;
  },
  async readText(response, limit=1048576) {
    return new TextDecoder().decode(await this.readBytes(response,limit));
  },
  hit(e) {
    const r=viewport.getBoundingClientRect(),x=(e.clientX-r.left-view.x)/view.z,y=(e.clientY-r.top-view.y)/view.z;
    const object=this.terrain?.hit(x,y);if(object)return {object};
    return [...this.hits].reverse().find(h=>x>=h.box.x&&y>=h.box.y&&x<=h.box.x+h.box.w&&y<=h.box.y+h.box.h);
  },
};
