"use strict";

function visibleClosure(node) {
  if(typeof Harness!=="undefined"&&node?._program===Harness.selected&&Landscape.details.has(node.id))return node;
  let result=node,parent=node?._parentScope;
  while(parent){const r=parent.el.getBoundingClientRect();if(r.width<420||r.height<240)result=parent;parent=parent._parentScope;}
  return result;
}

const Landscape = {
  detailOwner:null, details:new Set(),
  canvas:document.getElementById("landscape"), pending:0, terrain:null, rows:[], dbs:[], edges:[], hits:[],
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
      for(let parent=n._parentScope;parent;parent=parent._parentScope){
        const clip=parent._childHost.getBoundingClientRect();ctx.beginPath();ctx.rect(clip.left-vr.left,clip.top-vr.top,clip.width,clip.height);ctx.clip();
      }
      ctx.fillStyle=scope?`hsl(${hue} 23% 28%)`:n.el.classList.contains("ok")?"#7fbd93":n.type.startsWith("beliefs.")?"#b2a270":n.type.startsWith("vm.")?"#ad93c2":"#669b9c";
      if(size<4){ctx.fillRect(b.x+b.w/2-1,b.y+b.h/2-1,2,2);}
      else {ctx.fillRect(b.x,b.y,b.w,b.h);ctx.strokeStyle=scope?"#8ac5bb":"#28383b";ctx.strokeRect(b.x,b.y,b.w,b.h);}
      if(b.w>85&&b.h>20){ctx.fillStyle=scope?"#d7ebe5":"#101c1d";ctx.font="10px system-ui";ctx.fillText(n.type.slice(0,Math.floor(b.w/6)),b.x+4,b.y+13);}
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
      const from=this.terrain?.nodeFor(edge.from)?.rect,to=this.terrain?.nodeFor(edge.to)?.rect;if(!from||!to)continue;
      const a=screen(from),b=screen(to);ctx.beginPath();ctx.moveTo(a.x+a.w/2,a.y+a.h/2);ctx.lineTo(b.x+b.w/2,b.y+b.h/2);ctx.stroke();
    }
    ctx.setLineDash([]);
    const closureKey=Harness.selected+":"+G.nodes.map(n=>visibleClosure(n)?.id).join("|");
    if(this.closureKey!==closureKey){this.closureKey=closureKey;redraw();}
  },
  async refresh() {
    try {
      const response=await fetch("/api/graal/map");if(!response.ok)throw Error(response.status);
      const map=await response.json();this.rows=map.rows||[];this.dbs=map.dbs||[];
      this.positionObjects();
      if(!this.terrain)this.terrain=createGraalTerrain({canvas:this.canvas,invalidate:()=>this.schedule()});
      this.terrain.setRows(this.rows,this.objectBox,this.dbs);
      Harness.schedule();this.schedule();
    }catch(e){Harness.message("Object terrain unavailable: "+e.message);}
  },
  async inspect(id) {
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
