"use strict";

(function(root,factory){
  if(typeof module!=="undefined"&&module.exports)module.exports=factory(require("./vendor/d3-force-3.0.0.js"));
  else root.PatchLayout=factory(root.PatchForces);
})(globalThis,function(d3){
  const limits=Object.freeze({nodes:1500,edges:4096,steps:8000000,milliseconds:2000,ticks:160,gap:32,cable:72});

  function vacancy(n,gap,findHit){
    const candidates=[],seen=new Set();
    const offer=(x,y)=>{
      const key=x+","+y;if(seen.has(key))return;seen.add(key);
      if(seen.size>4096)throw Error("Layout clearance budget exceeded; select a smaller scope");
      candidates.push({x,y,cost:(x-n.x)**2+(y-n.y)**2});
    };
    offer(n.x,n.y);
    while(candidates.length){
      candidates.sort((a,b)=>b.cost-a.cost);
      const p=candidates.pop(),hit=findHit(p);
      if(!hit)return p;
      const dx=(n.w+hit.w)/2+gap+.1,dy=(n.h+hit.h)/2+gap+.1;
      offer(hit.x-dx,p.y);offer(hit.x+dx,p.y);offer(p.x,hit.y-dy);offer(p.x,hit.y+dy);
    }
    throw Error("Layout has no vacant position");
  }

  function placement(box,obstacles){
    if(obstacles.length>limits.nodes)throw Error("Landscape placement budget exceeded");
    const center=b=>({...b,x:b.x+b.w/2,y:b.y+b.h/2});
    const n=center(box),others=obstacles.map(center);
    const p=vacancy(n,limits.cable,p=>others.find(b=>Math.abs(p.x-b.x)<(n.w+b.w)/2+limits.cable&&Math.abs(p.y-b.y)<(n.h+b.h)/2+limits.cable));
    return {x:p.x-box.w/2,y:p.y-box.h/2};
  }

  // Input geometry is copied. Neither the solver nor its hints can install a cable.
  async function layout(boxes,patches,options={}){
    if(boxes.length>limits.nodes||patches.length>limits.edges)throw Error("Layout size budget exceeded");
    const now=options.now||(()=>performance.now()),start=now();
    let steps=0;
    const check=(validate=true)=>{
      if(validate&&options.valid&&!options.valid())throw Error("Layout discarded: document or selected parent changed");
      if(now()-start>limits.milliseconds)throw Error("Layout time budget exceeded; select a smaller scope");
    };
    const work=()=>{if(++steps>limits.steps)throw Error("Layout work budget exceeded; select a smaller scope");};
    const pause=options.yield||(()=>new Promise(resolve=>setTimeout(resolve,0)));
    let lastYield=start;
    const breathe=async()=>{check(false);if(now()-lastYield>=12){await pause();lastYield=now();check();}};
    const nodes=boxes.map((b,i)=>{
      if(![b.x,b.y,b.w,b.h].every(Number.isFinite)||b.w<=0||b.h<=0||Math.max(b.w,b.h)>100000)
        throw Error("Invalid layout geometry");
      return {...b,index:i,x:b.x+b.w/2,y:b.y+b.h/2,degree:0,component:i};
    });
    const byId=new Map(nodes.map(n=>[n.id,n]));
    if(byId.size!==nodes.length)throw Error("Duplicate layout identity");
    const rootOf=n=>{while(n.component!==nodes[n.component].component)n.component=nodes[n.component].component;return n.component;};
    const links=[];
    for(const p of patches){
      const a=byId.get(p.from),b=byId.get(p.to);if(!a||!b||a===b)continue;
      const offset=(v,fallback)=>v&&Number.isFinite(v.x)&&Number.isFinite(v.y)?v:fallback;
      links.push({source:a,target:b,hint:!!p.hint,
        out:offset(p.out,{x:a.w/2,y:0}),in:offset(p.in,{x:-b.w/2,y:0})});
      a.degree++;b.degree++;nodes[rootOf(b)].component=rootOf(a);
    }
    const groups=new Map();
    for(const n of nodes){const key=rootOf(n);if(!groups.has(key))groups.set(key,[]);groups.get(key).push(n);}
    const groupsLinks=new Map();
    for(const l of links){const key=rootOf(l.source);if(!groupsLinks.has(key))groupsLinks.set(key,[]);groupsLinks.get(key).push(l);}
    const packed=[];
    let ticks=0;
    for(const [key,group] of groups){
      check(false);
      const local=groupsLinks.get(key)||[];
      const cx=group.reduce((s,n)=>s+n.x,0)/group.length,cy=group.reduce((s,n)=>s+n.y,0)/group.length;
      const extent=Math.max(1,...group.map(n=>Math.max(Math.abs(n.x-cx),Math.abs(n.y-cy))));
      const radius=Math.sqrt(group.reduce((s,n)=>s+(n.w+limits.gap)*(n.h+limits.gap),0));
      const scale=Math.min(1,radius/extent);
      for(const n of group){n.x=(n.x-cx)*scale;n.y=(n.y-cy)*scale;}
      const maxW=Math.max(...group.map(n=>n.w)),maxH=Math.max(...group.map(n=>n.h));
      function separate(){
        const tree=d3.quadtree(group,n=>n.x,n=>n.y);
        let overlaps=0;
        for(const a of group){
          tree.visit((q,x0,y0,x1,y1)=>{
            work();
            const rx=(a.w+maxW)/2+limits.gap,ry=(a.h+maxH)/2+limits.gap;
            if(x0>a.x+rx||x1<a.x-rx||y0>a.y+ry||y1<a.y-ry)return true;
            if(!q.length)do{
              const b=q.data;if(b.index<=a.index)continue;
              const dx=b.x-a.x,dy=b.y-a.y;
              const ox=(a.w+b.w)/2+limits.gap-Math.abs(dx),oy=(a.h+b.h)/2+limits.gap-Math.abs(dy);
              if(ox<=0||oy<=0)continue;
              overlaps++;
              const axis=ox<oy?"x":"y",sign=(axis==="x"?dx:dy)<0?-1:1;
              const push=(Math.min(ox,oy)+.1)*sign*.5;
              a["v"+axis]-=push;b["v"+axis]+=push;
            }while(q=q.next);
            return false;
          });
        }
        return overlaps;
      }
      if(group.length>1){
        const simulation=d3.forceSimulation(group).stop().velocityDecay(.55).alphaDecay(.035)
          .force("links",d3.forceLink(local).distance(l=>(l.source.w+l.target.w)/2+limits.cable)
            .strength(l=>(l.hint?.18:.35)/Math.max(l.source.degree,l.target.degree)))
          .force("x",d3.forceX(0).strength(.006)).force("y",d3.forceY(0).strength(.006))
          .force("ports",alpha=>{
            for(const l of local){
              work();
              const a=l.source,b=l.target,k=alpha*(l.hint?.14:.24)/Math.max(a.degree,b.degree);
              const dx=b.x+l.in.x-a.x-l.out.x-limits.cable,dy=b.y+l.in.y-a.y-l.out.y;
              a.vx+=dx*k;b.vx-=dx*k;a.vy+=dy*k;b.vy-=dy*k;
            }
          }).force("clearance",()=>separate());
        try{
          for(let i=0;i<limits.ticks;i++){
            simulation.tick();ticks++;
            if(i%8===7)await breathe();
          }
        }finally{simulation.stop();}
        // Settle residual collisions at the nearest vacant rectangle, hub first.
        // The immutable placed set gives a hard clearance guarantee after cooling.
        const placed=d3.quadtree([],n=>n.x,n=>n.y);
        for(const n of [...group].sort((a,b)=>b.degree-a.degree||a.index-b.index)){
          const p=vacancy(n,limits.gap,p=>{
            let hit;
            placed.visit((q,x0,y0,x1,y1)=>{
              work();
              const rx=(n.w+maxW)/2+limits.gap,ry=(n.h+maxH)/2+limits.gap;
              if(hit||x0>p.x+rx||x1<p.x-rx||y0>p.y+ry||y1<p.y-ry)return true;
              if(!q.length)do{
                const b=q.data;
                if(Math.abs(p.x-b.x)<(n.w+b.w)/2+limits.gap&&Math.abs(p.y-b.y)<(n.h+b.h)/2+limits.gap){hit=b;break;}
              }while(q=q.next);
              return false;
            });
            return hit;
          });
          n.x=p.x;n.y=p.y;placed.add(n);
          await breathe();
        }
      }
      const x=Math.min(...group.map(n=>n.x-n.w/2)),y=Math.min(...group.map(n=>n.y-n.h/2));
      const w=Math.max(...group.map(n=>n.x+n.w/2))-x,h=Math.max(...group.map(n=>n.y+n.h/2))-y;
      for(const n of group){n.x-=x+n.w/2;n.y-=y+n.h/2;}
      packed.push({group,w,h,cx,cy});
    }
    // Disconnected segments share space, not springs or execution dependencies.
    const width=Math.max(1,...packed.map(p=>p.w),Math.sqrt(packed.reduce((s,p)=>s+(p.w+limits.cable)*(p.h+limits.cable),0)*1.5));
    let x=40,y=40,row=0;
    packed.sort((a,b)=>a.cy-b.cy||a.cx-b.cx);
    for(const p of packed){
      if(x>40&&x+p.w>width+40){x=40;y+=row+limits.cable;row=0;}
      for(const n of p.group){n.x+=x;n.y+=y;}
      x+=p.w+limits.cable;row=Math.max(row,p.h);
    }
    check();
    return {positions:nodes.map(n=>({id:n.id,x:n.x,y:n.y})),segments:packed.length,links:links.length,
      hints:links.filter(l=>l.hint).length,steps,ticks,elapsedMs:now()-start};
  }
  async function hints(document,parentId) {
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),8000);
    try{
      const pending=(document.nodes||[]).map(n=>[n,0]);let count=0;
      while(pending.length){const [n,depth]=pending.pop();
        if(++count>1500||depth>32)throw Error("Layout matching size budget exceeded");
        for(const child of n.children||[])pending.push([child,depth+1]);
      }
      // Proximity ranks layout hints across the document, not just today's Shake reach.
      // These proposals move boxes only; normal Shake keeps its own reach and effect rules.
      const body=JSON.stringify({program:document,options:{parentId,reach:Number.MAX_SAFE_INTEGER}});
      if(new TextEncoder().encode(body).length>1048576)throw Error("Layout request payload limit exceeded");
      const response=await fetch("/api/lcnc/treeshake",{method:"POST",signal:controller.signal,headers:{"Content-Type":"application/json"},body});
      const result=JSON.parse(await Landscape.readText(response,2097152));
      if(!response.ok||!result.ok)throw Error(result.detail||result.error||response.status);
      if(parentId!=null&&result.parentId!==parentId)throw Error("Server did not confirm the selected parent");
      return result.made||[];
    }finally{clearTimeout(timer);}
  }

async function settle(owner){
  if(settle.busy)return;
  const target=owner!=null?owner.parentTarget():null;
  if(owner!=null&&!target)return;
  const nodes=(target?target.nodes:G.nodes.filter(n=>!n._parentScope)).filter(n=>n.el);
  const origin=target?.origin||{x:0,y:0},parent=target?.node||null;
  const revision=owner!=null?owner.parentRevision:0;
  if(nodes.length<2){ $("#status").textContent="fd: nothing to place"; return; }
  const name=target?.handle.program,document=target?owner.document():null,snapshot=JSON.stringify(document);
  const boxes=nodes.map(n=>({id:n.id,x:n.x-origin.x,y:n.y-origin.y,w:n.el.offsetWidth||220,h:n.el.offsetHeight||120}));
  const current=()=>nodes.every((n,i)=>G.nodes.includes(n)&&(n.el.offsetWidth||220)===boxes[i].w&&(n.el.offsetHeight||120)===boxes[i].h)&&
    (!target||(owner.selected===name&&owner.parentRevision===revision&&owner.parentTarget()?.handle.nodeId===target.handle.nodeId&&JSON.stringify(owner.document())===snapshot));
  settle.busy=true;$("#fdBtn").disabled=true;$("#status").textContent="Checking nearby patches";
  try{
    if(nodes.length>PatchLayout.limits.nodes)throw Error("Layout size budget exceeded; select a smaller scope");
    let hints=[],warning="";
    if(target)try{hints=await owner.layoutHints(document,target.handle.nodeId);}
    catch(e){warning="; existing cables only: "+e.message;}
    if(!current())throw Error("Layout discarded: document or selected parent changed");
    const byId=new Map(G.nodes.map(n=>[n.id,n])),selected=new Map(nodes.map(n=>[n.id,n]));
    const topOf=id=>{let n=byId.get(id);while(n&&n._parentScope&&n._parentScope!==parent)n=n._parentScope;return n;};
    const edges=[],seen=new Set();
    // DOM coordinates are divided by the full enclosing scale, including ring zoom.
    const pin=(id,dir,port,top)=>{
      const n=byId.get(id);if(n!==top)return null;
      const el=[...n.el.querySelectorAll?.(".port")||[]].find(p=>p.dataset.dir===dir&&p.dataset.port===port&&p.closest(".node")===n.el);
      if(!el)return null;
      const a=el.getBoundingClientRect(),b=n.el.getBoundingClientRect(),scale=view.z*ringScaleOf(n);
      return {x:(a.left+a.width/2-b.left)/scale-(n.el.offsetWidth||220)/2,y:(a.top+a.height/2-b.top)/scale-(n.el.offsetHeight||120)/2};
    };
    const add=(from,to,hint)=>{
      const a=topOf(from[0]),b=topOf(to[0]);if(!a||!b||a===b||!selected.has(a.id)||!selected.has(b.id))return;
      const key=JSON.stringify([from,to]);if(seen.has(key))return;seen.add(key);
      edges.push({from:a.id,to:b.id,out:pin(from[0],"out",from[1],a),in:pin(to[0],"in",to[1],b),hint});
    };
    for(const w of G.wires)add(w.from,w.to,false);
    for(const h of hints)add([owner.layoutNodeId?.(h.fromNode)??name+"::"+h.fromNode,h.fromPort],[owner.layoutNodeId?.(h.toNode)??name+"::"+h.toNode,h.toPort],true);
    $("#status").textContent="Settling nearby patches";
    const result=await PatchLayout.layout(boxes,edges,{valid:current});
    if(!current())throw Error("Layout discarded: document or selected parent changed");
    if(target&&!parent&&owner.mounts){
      const left=Math.min(...result.positions.map(p=>p.x)),top=Math.min(...result.positions.map(p=>p.y));
      const box={x:origin.x+left-24,y:origin.y+top-64,
        w:Math.max(...result.positions.map((p,i)=>p.x+boxes[i].w))-left+48,
        h:Math.max(...result.positions.map((p,i)=>p.y+boxes[i].h))-top+88};
      const obstacles=[];
      for(const [other,anchor] of owner.mounts){if(other===name)continue;const b=owner.bounds(other);
        obstacles.push({x:anchor.x+b.left-24,y:anchor.y+b.top-64,w:b.w+48,h:b.h+88});}
      const position=PatchLayout.placement(box,obstacles);
      origin.x+=position.x-box.x;origin.y+=position.y-box.y;
    }
    for(const p of result.positions){const n=selected.get(p.id);n.x=origin.x+p.x;n.y=origin.y+p.y;n.el.style.left=n.x+"px";n.el.style.top=n.y+"px";}
    if(parent){if(owner.resizeParent)owner.resizeParent(parent);else resizeParentFrames(parent);}
    redraw();save();
    requestAnimationFrame(()=>{redraw();if(owner==null||owner.selected===name&&owner.parentRevision===revision)fitToContent();});
    $("#status").textContent="fd: "+nodes.length+" boxes, "+(result.links-result.hints)+" cable links, "+result.segments+" connected groups, "+result.hints+" candidate pulls; cables unchanged"+warning;
  }catch(e){$("#status").textContent=e.message;}
  finally{settle.busy=false;$("#fdBtn").disabled=false;}
}

  function ringView(width,height){return {x:0,y:0,z:Math.min(1,560/width,360/height)};}

function ringLayout(n,options={}){
  const {preserve=false,resize,frame}=options;
  const kids=(n.children||[]).filter(c=>c.el);
  const rings=kids.filter(c=>c.children&&c.children.length);
  for(const r of rings) ringLayout(r,options);     // inward first — post-order
  // Moving into a scaled host must not change shrink-to-fit widths mid-layout.
  for(const c of kids)c.el.style.width=(c.el.offsetWidth||210)+"px";
  if(preserve&&n._ringWorld){
    for(const c of kids){c.el.classList.add("inring");c.el.style.position="absolute";c.el.style.left=c.x+"px";c.el.style.top=c.y+"px";n._ringWorld.appendChild(c.el);}
    resize(n,false);
    return {w:parseFloat(n._ringWorld.style.width),h:parseFloat(n._ringWorld.style.height)};
  }
  const sizes=new Map(kids.map(c=>[c,{w:c.el.offsetWidth||210,h:c.el.offsetHeight||96}]));
  const ins=kids.filter(c=>c.type==="scope.in");
  const outs=kids.filter(c=>c.type==="scope.out");
  const mids=kids.filter(c=>!ins.includes(c)&&!outs.includes(c)&&!rings.includes(c));
  const sz=c=>sizes.get(c);
  const stackH=a=>a.length?a.reduce((s,c)=>s+sz(c).h,0)+(a.length-1)*RING_GAP:0;
  const rowW=a=>a.length?a.reduce((s,c)=>s+sz(c).w,0)+(a.length-1)*RING_GAP:0;
  const rowH=a=>a.length?Math.max(...a.map(c=>sz(c).h)):0;
  const colW=a=>a.length?Math.max(...a.map(c=>sz(c).w)):0;
  const top=mids.filter((_,i)=>i%2===0), bot=mids.filter((_,i)=>i%2===1);
  const centerW=Math.max(rowW(rings),rowW(top),rowW(bot),140);
  const centerH=Math.max(rowH(rings),stackH(ins),stackH(outs),60);
  const W=colW(ins)+centerW+colW(outs)+4*RING_EDGE;
  const H=(top.length?rowH(top)+RING_EDGE:0)+centerH+(bot.length?rowH(bot)+RING_EDGE:0)+2*RING_EDGE;
  const host=n._childHost;
  const rw=n._ringWorld||host;
  if(n._ringWorld){
    rw.style.width=W+"px"; rw.style.height=H+"px";
    n._view=ringView(W,H);
    // applyRingView sizes the frame from the world × this ring's zoom, so the
    // floor is never re-set to the unscaled W/H behind a zoomed interior.
    applyRingView(n);
    frame?.(n);
  } else {
    host.style.minWidth=W+"px"; host.style.minHeight=H+"px";
  }
  // the shared center sits mid-CENTER-BAND (between the in and out edges),
  // so asymmetric edge columns never squeeze the inner shells into them
  const cx=colW(ins)+2*RING_EDGE+centerW/2, cy=(top.length?rowH(top)+RING_EDGE:0)+RING_EDGE+centerH/2;
  const place=(c,x,y)=>{ c.x=x; c.y=y; c.el.classList.add("inring"); c.el.style.position="absolute";
    c.el.style.left=x+"px"; c.el.style.top=y+"px"; rw.appendChild(c.el); };
  let y=cy-stackH(ins)/2;
  for(const c of ins){ place(c,RING_EDGE,y); y+=sz(c).h+RING_GAP; }
  y=cy-stackH(outs)/2;
  for(const c of outs){ place(c,W-RING_EDGE-sz(c).w,y); y+=sz(c).h+RING_GAP; }
  let x=cx-rowW(top)/2;
  for(const c of top){ place(c,x,RING_EDGE); x+=sz(c).w+RING_GAP; }
  x=cx-rowW(bot)/2;
  for(const c of bot){ place(c,x,H-RING_EDGE-sz(c).h); x+=sz(c).w+RING_GAP; }
  x=cx-rowW(rings)/2;                               // the innermost shell
  for(const c of rings){ place(c,x,cy-sz(c).h/2); x+=sz(c).w+RING_GAP; }
  return {w:W,h:H};
}

  return {layout,placement,limits,hints,settle,ringView,ringLayout};
});
