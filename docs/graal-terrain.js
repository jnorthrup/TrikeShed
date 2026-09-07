"use strict";
const GraalTopology = {
  categories:[
    {id:"git",label:"Git",color:"#c798df",hint:"Git paths and pack files (path-classified)"},
    {id:"classes",label:"Classes",color:"#68c8c1",hint:"Compiled .class files (path-classified)"},
    {id:"binaries",label:"Binaries",color:"#e7b66b",hint:"Known binary file extensions; unknown blobs remain Other"},
    {id:"other",label:"Other blobs",color:"#9daeb8",hint:"Source, documents and unclassified content"},
    {id:"blocks",label:"Heap blocks",color:"#79ba81",hint:"Measured GC pool occupancy blocks, not object addresses"},
    {id:"live",label:"Actual heap",color:"#ed928e",hint:"Live class histogram, when supplied by the daemon; not allocation samples or an object-reference graph"},
    {id:"allocation",label:"Allocations",color:"#8fb9ee",hint:"JFR sampled allocation attribution, not retained live bytes"},
  ],
  category(row) {
    if(row[5]?.runtime)return row[5].category;
    const id=String(row[0]);
    if(/(^|\/)\.git(\/|$)|(^|\/)pack-[0-9a-f]+\.(pack|idx)$/i.test(id))return "git";
    if(/\.class$/i.test(id))return "classes";
    if(/\.(jar|war|zip|gz|bz2|xz|7z|so|dylib|dll|exe|bin|o|a|wasm|hprof|png|jpg|jpeg|gif|webp|ico|pdf|mp[34]|wav|woff2?|ttf)$/i.test(id))return "binaries";
    return "other";
  },
  bit(id) {const i=this.categories.findIndex(c=>c.id===id);return i<0?0:1<<i;},
  all:127,
  heapRows(heap={},vitals={}) {
    const rows=[],atMs=heap.atMs||null;
    const add=(category,name,bytes,detail)=>{
      if(typeof name!=="string"||!Number.isFinite(bytes)||bytes<=0)return;
      rows.push(["runtime:"+category+"/"+encodeURIComponent(name),bytes,0,1,0,
        {runtime:true,category,name,...detail}]);
    };
    for(const r of (Array.isArray(heap.rows)?heap.rows:[]).slice(0,256))
      if(r)add("live",r.class,r.bytes,{source:"GC.class_histogram",atMs,count:r.count,bytes:r.bytes});
    for(const r of (Array.isArray(heap.allocation)?heap.allocation:[]).slice(0,256))
      if(r)add("allocation",r.class,r.bytes,{source:"JFR ObjectAllocationSample",atMs,sampledBytes:r.bytes});
    const lane=vitals.gc?.lane||{};
    for(const p of (Array.isArray(lane.pools)?lane.pools:[]).slice(0,64))
      if(p)add("blocks",p.pool,p.lastUsedBytes,{source:"JFR GCHeapMemoryPoolUsage",atMs:lane.atMs||null,
        usedBytes:p.lastUsedBytes,committedBytes:p.lastCommittedBytes,samples:p.samples});
    return rows;
  },
};
// Extracted from Graal's object viewer. Geometry is independent of the shared camera.
function createGraalTerrain(options) {
const ctx=options.canvas.getContext("2d");
const W=1600,H=1000,DB="trikeshed";
let cam={s:1,ox:0,oy:0},innerWidth=1,innerHeight=1,PROJECT_DBS=new Set(),layer=1;
let hover=null,fog=false,skin="ops",mask=GraalTopology.all;
const heatOf=()=>0;
function dbUrl(id){const parts=id.split("/");return PROJECT_DBS.has(parts[0])?"/"+parts.map(encodeURIComponent).join("/"):"/"+DB+"/"+parts.map(encodeURIComponent).join("/");}
let root=null,byId=new Map();
const HUES={'forge':300,'projects':172,'.git':262,'pointcut':8,'_design':318,'memories':210,'lost-found':48,'jules':132,'vms':282};
function hueOf(name){if(name in HUES)return HUES[name];let h=0;for(const c of name)h=(h*31+c.charCodeAt(0))>>>0;return h%360;}
function node(name,parent){return{name,parent,children:new Map(),categories:0,bytes:0,mass:0,docs:0,leaf:false,rect:null,laid:false,hue:parent?parent.hue:hueOf(name)};}
/* Nonce vaults: content-addressed storage planes. Real, divable, but they must not own the map. */
const VAULTS=new Set(['.git','lost-found','subtree-cache','objects','worktrees']);
function massOf(bytes,topName){const m=Math.log2(2+bytes);return VAULTS.has(topName)?m*0.18:m;}
/* The one cursor: rows = [id, bytes, lastSeq, revGen, code]. Terrain, sheets, ledger and layers are
   all projections over it — 'everything is a cursor' holds on the client too. The 5th element is
   the AngularCodec coordinate: in SIMILARITY arrangement the tree splits by code nibbles
   (sortable, the hash can't do this); in POSITIONAL arrangement it splits by id path as before.
   The toggle itself is served state (T), never JS invention. */
let maxSeq=1;
let ARRANGE='positional';
function buildTree(rows){
  root=node('trikeshed',null);root.rect={x:0,y:0,w:W,h:H};byId=new Map();maxSeq=1;
  for(const row of rows){
    const [id,bytes,seq,gen,code]=row;
    const parts=ARRANGE==='similarity'
      ? ['code-'+(((code|0)>>8)&0xFF).toString(16).padStart(2,'0'),'code-'+((code|0)&0xFFFF).toString(4).padStart(1,'0'),id]
      : id.split('/').filter(p=>p.length);
    let n=root;
    const mass=massOf(bytes,parts[0]);
    for(let i=0;i<parts.length;i++){
      let c=n.children.get(parts[i]);
      if(!c){c=node(parts[i],n===root?null:n);if(n===root)c.hue=ARRANGE==='similarity'?(code|0)%360:hueOf(parts[i]);else c.hue=ARRANGE==='similarity'?hueOf(parts[i]+'#'+(code|0)):n.hue;c.parent=n;n.children.set(parts[i],c);}
      n=c;n.bytes+=bytes;n.mass+=mass;n.docs++;
    }
    n.leaf=true;n.id=id;n.seq=seq||0;n.gen=gen||1;n.mass=mass;n.detail=row[5];
    const bit=GraalTopology.bit(GraalTopology.category(row));
    for(let a=n;a;a=a.parent)a.categories|=bit;
    byId.set(id,n);
    if(n.seq>maxSeq)maxSeq=n.seq;
    let a=n.parent;while(a){if((a.maxSeq||0)<n.seq)a.maxSeq=n.seq;a=a.parent;}
    if((root.maxSeq||0)<n.seq)root.maxSeq=n.seq;
    root.bytes+=bytes;root.mass+=mass;root.docs++;
  }
  invalidate(root);

}
function invalidate(n){n.laid=false;for(const c of n.children.values())invalidate(c);}
/* squarified-ish treemap: rows of children sorted by mass, laid into the node rect */
function layout(n){
  if(n.laid)return;n.laid=true;
  const kids=[...n.children.values()].sort((a,b)=>b.mass-a.mass);
  if(!kids.length)return;
  // Insets are PROPORTIONAL (self-similar at every depth) and therefore
  // zoom-INDEPENDENT: world rects never move once laid. The old pixel-capped
  // (…/cam.s) insets forced re-layout on zoom-band crossings, which shifted
  // every rect in world space mid-dive — the camera held the point under the
  // mouse, but the CONTENT at that point fled ("zoom repulsion"). Whether the
  // title TEXT is legible stays a draw-time decision; the band reservation
  // itself is a fixed fraction (sub-pixel when zoomed out, harmless).
  const pad=Math.min(n.rect.w,n.rect.h)*0.015;
  const title=(n!==root)?n.rect.h*0.07:0;
  let x=n.rect.x+pad,y=n.rect.y+pad+title,w=n.rect.w-2*pad,h=n.rect.h-2*pad-title;
  if(w<=0||h<=0){for(const k of kids)k.rect={x:n.rect.x,y:n.rect.y,w:0,h:0};return;}
  let total=kids.reduce((s,k)=>s+k.mass,0)||1;
  let i=0;
  while(i<kids.length){
    const vert=w<h; const side=vert?w:h;
    let take=[],mass=0,best=Infinity;
    for(let j=i;j<kids.length;j++){
      const m2=mass+kids[j].mass, frac=m2/total, breadth=(vert?h:w)*frac;
      const worst=Math.max(...[...take,kids[j]].map(k=>{const s1=(k.mass/m2)*side;return Math.max(s1/(breadth||1e-9),(breadth||1e-9)/s1);}));
      if(worst<=best||take.length===0){take.push(kids[j]);mass=m2;best=worst;}else break;
    }
    const frac=mass/total, breadth=(vert?h:w)*frac;
    let off=0;
    for(const k of take){const kf=k.mass/mass;
      if(vert){k.rect={x:x+off*w,y:y,w:w*kf,h:breadth};off+=kf;}
      else{k.rect={x:x,y:y+off*h,w:breadth,h:h*kf};off+=kf;}
    }
    if(vert)y+=breadth,h-=breadth;else x+=breadth,w-=breadth;
    total-=mass; i+=take.length;
    const rem=kids.slice(i).reduce((s,k)=>s+k.mass,0);
    if(rem<=0){for(let j=i;j<kids.length;j++)kids[j].rect={x,y,w:0,h:0};break;}
  }
}
function camPx(worldLen){return worldLen*cam.s;}
const SKINS={
  ops:{bg:'#0b0e14',fill:(h,l)=>`hsl(${h} 42% ${l}%)`,stroke:(h,l)=>`hsl(${h} 45% ${Math.min(l+14,40)}%)`,
       hot:(h)=>`hsl(${h} 90% 60%)`,label:(h)=>`hsl(${h} 70% 72%)`,text:'rgba(216,220,230,.82)',sub:'rgba(123,132,150,.9)',faint:'rgba(216,220,230,.55)',mini:'#11151e',miniTile:h=>`hsl(${h} 40% 22%)`,lw:0.75},
  chalk:{bg:'#1b2420',fill:(h,l)=>`hsl(${h} 18% ${Math.max(l-2,9)}%)`,stroke:(h,l)=>`hsla(${h},35%,82%,.55)`,
       hot:(h)=>`hsla(${h},80%,88%,.95)`,label:(h)=>`hsla(${h},45%,86%,.95)`,text:'rgba(233,239,232,.85)',sub:'rgba(147,163,152,.9)',faint:'rgba(233,239,232,.5)',mini:'#212b26',miniTile:h=>`hsl(${h} 22% 26%)`,lw:1.4},
  marker:{bg:'#f6f5f0',fill:(h,l)=>`hsl(${h} 60% ${96-Math.min(l,14)}%)`,stroke:(h,l)=>`hsl(${h} 55% 42%)`,
       hot:(h)=>`hsl(${h} 80% 34%)`,label:(h)=>`hsl(${h} 65% 30%)`,text:'rgba(34,38,43,.88)',sub:'rgba(118,125,136,.95)',faint:'rgba(34,38,43,.5)',mini:'#ffffff',miniTile:h=>`hsl(${h} 55% 80%)`,lw:1.6},
};
const kCache=new Map();      // id → gzip ratio 0..1 (the practical K estimate)
let kInFlight=0;
function wantK(n){
  if(n.detail?.runtime)return;
  if(kCache.has(n.id)||kInFlight>=3||n.bytes>1500000||typeof CompressionStream==='undefined')return;
  kCache.set(n.id,-1);kInFlight++;
  fetch(dbUrl(n.id)+'/content')
    .then(r=>r.ok?r.arrayBuffer():Promise.reject(r.status))
    .then(async buf=>{
      const c=await new Response(new Blob([buf]).stream().pipeThrough(new CompressionStream('gzip'))).arrayBuffer();
      kCache.set(n.id,buf.byteLength?Math.min(1,c.byteLength/buf.byteLength):0);})
    .catch(()=>kCache.set(n.id,-2)).finally(()=>{kInFlight--; options.invalidate();});
}
const previewCache=new Map(); // id → array of first lines (or null = not text)
let pInFlight=0;
const TEXTY=/\.(kt|kts|java|py|js|ts|md|json|yaml|yml|css|sh|gradle|xml|txt|html|toml|properties|sql|rs|c|h|cpp|go|wal|jsonl)$/i;
function wantPreview(n){
  if(n.detail?.runtime)return;
  if(previewCache.has(n.id)||pInFlight>=3)return;
  if(!TEXTY.test(n.id)||n.bytes>400000){previewCache.set(n.id,null);return;}
  previewCache.set(n.id,[]);pInFlight++;
  fetch(dbUrl(n.id)+'/content')
    .then(r=>r.ok?r.text():Promise.reject(r.status))
    .then(t=>previewCache.set(n.id,t.split('\n').slice(0,16)))
    .catch(()=>previewCache.set(n.id,null)).finally(()=>{pInFlight--; options.invalidate();});
  if(previewCache.size>400){const k=previewCache.keys().next().value;previewCache.delete(k);}
}
function drawNode(n,depth){
  if(!(n.categories&mask))return;
  const r=n.rect;if(!r)return;
  const x=(r.x-cam.ox)*cam.s,y=(r.y-cam.oy)*cam.s,w=r.w*cam.s,h=r.h*cam.s;
  if(x>innerWidth||y>innerHeight||x+w<0||y+h<0||w<1.2||h<1.2)return;
  const light=n.leaf?18:10+Math.min(depth*2,8);
  const S=SKINS[skin];
  const fogged=fog&&depth>0&&heatOf(n)<=0;
  if(fogged)ctx.globalAlpha=0.22;
  const glow=heatOf(n);
  ctx.fillStyle=S.fill(n.hue,light);
  ctx.strokeStyle=S.stroke(n.hue,light);
  ctx.lineWidth=n===hover?2:S.lw;
  ctx.beginPath();ctx.rect(x,y,w,h);ctx.fill();ctx.stroke();
  if(n.leaf&&w>8&&h>8){
    ctx.fillStyle=GraalTopology.categories.find(c=>GraalTopology.bit(c.id)&n.categories)?.color||S.sub;
    ctx.fillRect(x+1,y+1,Math.min(3,w-2),h-2);
  }
  if(n===hover){ctx.strokeStyle=S.hot(n.hue);ctx.stroke();}
  if(glow>0&&depth>0){ctx.globalAlpha=(fogged?0.22:1)*glow*0.8;ctx.strokeStyle=S.hot(n.hue);ctx.lineWidth=2;ctx.strokeRect(x+1,y+1,w-2,h-2);ctx.globalAlpha=fogged?0.22:1;}
  // ── layers: kolmogorov (2) and causal (3) tint the same cursor ──
  if(layer===2&&n.leaf&&w>14&&h>10){
    wantK(n);const k=kCache.get(n.id);
    if(typeof k==='number'&&k>=0){
      // structured→green calm, incompressible→red noise
      ctx.globalAlpha=(fogged?0.22:1)*0.5;
      ctx.fillStyle=k>0.92?'#ff4f58':k>0.7?'#ffb02e':'#3ddc84';
      ctx.fillRect(x,y,w,Math.max(2,h*0.12));
      if(k>0.92&&w>30){ctx.globalAlpha=0.18;for(let i=0;i<Math.min(60,w*h/220);i++){ctx.fillRect(x+Math.random()*w,y+Math.random()*h,1.4,1.4);}}
      ctx.globalAlpha=fogged?0.22:1;
      if(w>70&&h>28){ctx.font='9px monospace';ctx.fillStyle=k>0.92?'#ff8a8f':S.sub;ctx.fillText((k*100).toFixed(0)+'% K',x+w-38,y+h-5);}
    }
  }
  if(layer===3&&w>14&&h>10){
    const seq=n.leaf?n.seq:(n.maxSeq||0);
    if(seq>0){
      const age=seq/maxSeq; // 0 old → 1 newest
      ctx.globalAlpha=(fogged?0.22:1)*0.45;
      ctx.fillStyle=`hsl(${200-160*age} 70% 50%)`;
      ctx.fillRect(x,y+h-Math.max(2,h*0.12),w,Math.max(2,h*0.12));
      ctx.globalAlpha=fogged?0.22:1;
      if(n.leaf&&n.gen>1&&w>44){ctx.font='9px monospace';ctx.fillStyle='#ffb02e';ctx.fillText('⟳'+n.gen,x+w-26,y+12);}
      if(n.leaf&&w>90&&h>28){ctx.font='9px monospace';ctx.fillStyle=S.sub;ctx.fillText('seq '+seq,x+5,y+h-5);}
    }
  }
  const showKids=n.children.size&&w>26&&h>18;
  if(showKids){layout(n);for(const c of n.children.values())drawNode(c,depth+1);}
  if(w>64&&h>15){
    ctx.font=(depth<2?'bold ':'')+Math.min(13,Math.max(9,h*0.08))+'px monospace';
    ctx.fillStyle=depth<2?S.label(n.hue):S.text;
    const HEXY=/^[0-9a-f]{16,}(\.\w+)?$/;
    const runtimeLabel=n.name.startsWith('runtime:')?GraalTopology.categories.find(c=>c.id===n.name.slice(8))?.label:null;
    const nm=n.detail?.name||runtimeLabel||(HEXY.test(n.name)?'⬡'+n.name.slice(0,8):n.name);
    const label=nm+(showKids?'':n.leaf?'':' ·'+n.docs);
    ctx.fillText(label.slice(0,Math.floor(w/7)),x+5,y+12);
    if(!showKids&&h>34){ctx.font='9px monospace';ctx.fillStyle=S.sub;
      ctx.fillText(n.leaf?fmtBytes(n.bytes):n.docs.toLocaleString()+' docs · '+fmtBytes(n.bytes),x+5,y+24);}
    if(n.leaf&&w>170&&h>56){ctx.font='9px monospace';ctx.fillStyle=S.faint;
      ctx.fillText((n.id||'').slice(-Math.floor(w/6)),x+5,y+h-6);}
    if(n.leaf&&w>210&&h>90){
      wantPreview(n);const lines=previewCache.get(n.id);
      if(lines&&lines.length){
        ctx.font='8px ui-monospace,monospace';ctx.fillStyle=S.faint;
        const maxL=Math.min(lines.length,Math.floor((h-46)/9));
        for(let i=0;i<maxL;i++)ctx.fillText(lines[i].slice(0,Math.floor((w-12)/4.6)),x+6,y+38+i*9);
      }
    }
    if(!n.leaf&&!showKids&&w>120&&h>44){
      const kids=[...n.children.values()].sort((a,b)=>b.bytes-a.bytes).slice(0,3);
      let off=0;ctx.font='8px monospace';
      for(const k of kids){const bw=(w-12)*(k.bytes/(n.bytes||1));
        ctx.fillStyle=S.stroke(k.hue,30);ctx.globalAlpha=(fogged?0.22:1)*0.7;
        ctx.fillRect(x+6+off,y+h-14,Math.max(2,bw),5);ctx.globalAlpha=fogged?0.22:1;off+=bw+2;}
    }
  }
  if(fogged)ctx.globalAlpha=1;
}
function fmtBytes(b){return b>1048576?(b/1048576).toFixed(1)+'M':b>1024?(b/1024).toFixed(1)+'K':b+'B';}

return {
setRows(rows,rect,dbs=[]) {PROJECT_DBS=new Set(dbs);buildTree(rows);root.rect={...rect};invalidate(root);},
draw(camera,width,height) {cam=camera;innerWidth=width;innerHeight=height;if(root)drawNode(root,0);},
setLayer(value){layer=value;},
setMask(value){mask=value&GraalTopology.all;options.invalidate();},
visible(n){return !!(n?.categories&mask);},
hit(x,y) {
 if(!root)return null;let found=null;
 function visit(n){const r=n.rect;if(!r||x<r.x||y<r.y||x>r.x+r.w||y>r.y+r.h)return;
 if(!(n.categories&mask)){found=null;return;}found=n;
 if(n.children.size&&r.w*cam.s>26&&r.h*cam.s>18){layout(n);for(const c of n.children.values())visit(c);}}
 visit(root);return found===root?null:found;
},
nodeFor(id){return byId.get(id);},url:dbUrl,hueOf,
};
}
