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
  return {layout,placement,limits};
});
