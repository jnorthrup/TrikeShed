"use strict";

// Graal's byte projections, shared by terrain, sheets, results and immutable refs.
const GraalFileViewer = (() => {
const LIMIT=1500000, states=new WeakMap();
const isCid=value=>typeof value==="string"&&/^sha256:[0-9a-f]{64}$/.test(value);
function fmtBytes(b){return b>1048576?(b/1048576).toFixed(1)+" MiB":b>1024?(b/1024).toFixed(1)+" KiB":b+" B";}
function esc(x){return (x??'').toString().replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');}
const KW=/\b(fun|val|var|class|object|interface|override|suspend|return|if|else|when|for|while|import|package|private|public|internal|data|companion|def|lambda|function|const|let|new|static|void|int|long|boolean|public|final|try|catch|finally|throw|async|await)\b/g;
function codeView(text,maxLines){
  const lines=text.split('\n').slice(0,maxLines||1200);
  return '<div class="codeview">'+lines.map(line=>{
    let html='',at=0;
    const tokens=/"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|\/\/.*$|#.*$|\b(?:fun|val|var|class|object|interface|override|suspend|return|if|else|when|for|while|import|package|private|public|internal|data|companion|def|lambda|function|const|let|new|static|void|int|long|boolean|final|try|catch|finally|throw|async|await)\b/g;
    for(const match of line.matchAll(tokens)){
      html+=esc(line.slice(at,match.index));
      const token=match[0],kind=/^(\/\/|#)/.test(token)?'com':/^["']/.test(token)?'str':'kw';
      html+='<span class="'+kind+'">'+esc(token)+'</span>';at=match.index+token.length;
    }
    return '<div>'+(html+esc(line.slice(at))||' ')+'</div>';
  }).join('')+'</div>';
}
function mdView(text){
  let h=esc(text);
  h=h.replace(/```([\s\S]*?)```/g,(m,c)=>'<pre>'+c+'</pre>');
  h=h.replace(/^###### (.*)$/gm,'<h3>$1</h3>').replace(/^##### (.*)$/gm,'<h3>$1</h3>').replace(/^#### (.*)$/gm,'<h3>$1</h3>')
     .replace(/^### (.*)$/gm,'<h3>$1</h3>').replace(/^## (.*)$/gm,'<h2>$1</h2>').replace(/^# (.*)$/gm,'<h1>$1</h1>');
  h=h.replace(/^\s*[-*] (.*)$/gm,'<li>$1</li>');
  h=h.replace(/\*\*([^*]+)\*\*/g,'<b>$1</b>').replace(/`([^`]+)`/g,'<code>$1</code>');
  h=h.replace(/\[([^\]]+)\]\(([^)]+)\)/g,(m,label,url)=>/^(https?:\/\/|\/(?!\/)|#)/i.test(url)?'<a href="'+url+'" target="_blank" rel="noopener">'+label+'</a>':label);
  h=h.replace(/\n\n+/g,'<br><br>');
  return '<div class="mdview">'+h+'</div>';
}
function classCard(b){
  if(b.byteLength<10)return null;
  const dv=b instanceof Uint8Array?new DataView(b.buffer,b.byteOffset,b.byteLength):new DataView(b);
  if(dv.getUint32(0)!==0xCAFEBABE)return null;
  const minor=dv.getUint16(4),major=dv.getUint16(6),pool=dv.getUint16(8);
  const jdk=major>=49?('Java '+(major-44)):'pre-1.5';
  return '<table class="classcard"><tr><td>magic</td><td>CAFEBABE</td></tr><tr><td>class file version</td><td>'+major+'.'+minor+' ('+jdk+')</td></tr><tr><td>constant pool</td><td>'+pool+' entries</td></tr><tr><td>size</td><td>'+fmtBytes(b.byteLength)+'</td></tr></table>';
}
function hexHead(b,n){
  const v=(b instanceof Uint8Array?b:new Uint8Array(b)).subarray(0,n||128);let out='';
  for(let i=0;i<v.length;i+=16){
    out+=Array.from(v.slice(i,i+16)).map(x=>x.toString(16).padStart(2,'0')).join(' ')+'   '+
         Array.from(v.slice(i,i+16)).map(x=>x>=32&&x<127?String.fromCharCode(x):'·').join('')+'\n';}
  return '<pre style="font-size:10px">'+esc(out)+'</pre><div style="color:var(--dim)">first '+v.length+' of '+fmtBytes(b.byteLength)+' — no friendlier projection for this type yet</div>';
}
function extOf(id){return id.split('.').pop().toLowerCase();}
/* ── shape strip: the Cascade Shape<S> of a text, RLE runs + fib ticks — sees content
      relationships with stochastic features. Same taxonomy as the Forge PWA strip. */
let _fib=null;function fibSet(){if(_fib)return _fib;_fib=new Set();let a=0,b=1;while(a<100000){_fib.add(a);const t=a+b;a=b;b=t;}return _fib;}
function classifyLine(l){
  const t=l.trim();
  if(!t)return '_';
  if(/^#{1,6} /.test(t)||/^=+$|^-{3,}$/.test(t))return 'H';
  if(/^\|.*\|/.test(t))return 'T';
  if(/^```/.test(t))return 'C';
  if(/^[-*+•] |^\d+\. /.test(t))return 'B';
  if(/^(fun|val|var|class|object|import|package|def|function|const|let|public|private|#include|@)/.test(t)||/[{};]\s*$/.test(t))return 'J';
  if(/^(##+ )?(section|chapter)\b/i.test(t))return 'S';
  return 'P';
}
function shapeRuns(text){
  const lines=text.split('\n');const runs=[];
  let c=null,n=0,start=1;
  lines.forEach((l,i)=>{const k=classifyLine(l);
    if(k===c)n++;else{if(c)runs.push({c,n,start});c=k;n=1;start=i+1;}});
  if(c)runs.push({c,n,start});
  return {runs,lines:lines.length};
}
const SHAPE_NAMES={H:'heading',S:'section',P:'prose','_':'blank',B:'bullet',T:'table',J:'code',C:'fence'};
function shapeStripHtml(text){
  const {runs,lines}=shapeRuns(text);const fib=fibSet();
  let depth=0;
  const cells=runs.map((r,i)=>{
    const isFib=fib.has(i);
    const title=(SHAPE_NAMES[r.c]||r.c)+' · '+r.n+' lines '+r.start+'–'+(r.start+r.n-1)+(isFib?' · depth '+i:'');
    return '<button class="shape-cell'+(isFib?' fib':'')+'" title="'+title+'" style="--c:var(--shape-'+r.c+',var(--shape-P));flex:'+r.n+' 0 2px" data-line="'+r.start+'"></button>';
  }).join('');
  const key=runs.map(r=>r.c).join('');
  return '<div class="shape-strip">'+cells+'</div><code class="shape-key">'+esc(key)+' · '+lines+' lines · '+runs.length+' runs</code>';
}

function element(tag,cls,text){const el=document.createElement(tag);if(cls)el.className=cls;if(text!=null)el.textContent=String(text);return el;}
function localUrl(value){const url=new URL(value,location.href);if(url.origin!==location.origin||!["http:","https:"].includes(url.protocol))throw Error("Non-local blob URL");return url.pathname+url.search;}
async function readBytes(response,limit=LIMIT){
  const reader=response.body?.getReader();if(!reader)throw Error("Response stream unavailable");
  let size=0;const chunks=[];
  try{
    if(Number(response.headers.get("content-length"))>limit)throw Error("Preview exceeds "+fmtBytes(limit));
    while(true){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>limit)throw Error("Preview exceeds "+fmtBytes(limit));chunks.push(value);}
  }catch(error){await reader.cancel().catch(()=>{});throw error;}finally{reader.releaseLock();}
  const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.byteLength;}return bytes;
}
async function measure(bytes){
  if(!bytes.byteLength)return {raw:0,gzip:0,ratio:0};
  if(typeof CompressionStream==="undefined")return null;
  const compressed=await readBytes(new Response(new Blob([bytes]).stream().pipeThrough(new CompressionStream("gzip"))),LIMIT+65536);
  return {raw:bytes.byteLength,gzip:compressed.byteLength,ratio:compressed.byteLength/bytes.byteLength};
}
function kind(bytes,type="",id=""){
  const ext=extOf(id),ct=type.toLowerCase();
  if(ct.includes("java-vm")||ext==="class"||(bytes.length>=4&&bytes[0]===202&&bytes[1]===254&&bytes[2]===186&&bytes[3]===190))return "class";
  if(ct.startsWith("image/")||/^(svg|png|jpg|jpeg|gif|webp|avif)$/.test(ext))return "image";
  if(ext==="md"||ct.includes("markdown"))return "markdown";
  if(ct.includes("html")||/^(html|htm)$/.test(ext))return "html";
  if(ct.startsWith("text/")||/json|xml|javascript/.test(ct)||/^(kt|kts|java|py|js|ts|json|yaml|yml|css|sh|gradle|xml|txt|toml|properties|sql|rs|c|h|cpp|go|jsonl)$/.test(ext))return "text";
  try{const text=new TextDecoder("utf-8",{fatal:true}).decode(bytes);if(!/[\x00-\x08\x0e-\x1f]/.test(text))return "text";}catch(_){}
  return "binary";
}
function chip(cid,label="blob"){
  if(!isCid(cid))return esc(cid||"");
  return '<button class="blob-ref" data-blob-cid="'+cid+'" title="Open blob '+cid+'">'+esc(label)+' '+cid.slice(7,19)+'</button>';
}
function bindRefs(mount){mount.querySelectorAll("[data-blob-cid]").forEach(button=>button.addEventListener("click",e=>{e.stopPropagation();api.open({cid:button.dataset.blobCid});}));}
function dispose(mount){const state=states.get(mount);if(!state)return;state.controller.abort();for(const url of state.urls)URL.revokeObjectURL(url);states.delete(mount);}
async function sourceMates(id,mount,state){
  const region=element("div","file-mates");mount.append(region);
  try{
    const response=await fetch("/api/graal/decompile?source="+encodeURIComponent(id),{signal:state.controller.signal});
    const data=JSON.parse(new TextDecoder().decode(await readBytes(response)));if(state.controller.signal.aborted)return;
    if(!response.ok){region.textContent="Class projection unavailable: "+(data.error||response.status);return;}
    const mates=(data.mates||[]).slice(0,24);
    region.append(element("p","file-note",mates.length?"SourceFile mates: "+mates.length:"No SourceFile-mated class blob"));
    for(const mate of mates){
      const detail=element("details","mate"),title=element("summary",null,mate.className+" / "+(mate.exactRuntimeBlob?"exact runtime mate":mate.onClasspath?"classpath bytes differ":"not on runtime classpath"));
      detail.append(title);const body=element("div","matebody");
      body.innerHTML=chip(mate.blobCid)+(mate.runtimeCid?chip(mate.runtimeCid,"runtime"):"")+codeView(mate.decompiler?.pseudoSource||"No class projection",2400);
      detail.append(body);region.append(detail);
    }
    bindRefs(region);
  }catch(error){if(!state.controller.signal.aborted)region.textContent="Class projection unavailable: "+error.message;}
}
async function render(mount,options){
  dispose(mount);mount.replaceChildren();mount.classList.add("graal-file");
  const state={controller:new AbortController(),urls:[]};states.set(mount,state);
  const signal=state.controller.signal;
  if(options.signal?.aborted){state.controller.abort();return;}
  options.signal?.addEventListener("abort",()=>dispose(mount),{once:true});
  const status=element("p","file-note","Loading bytes");mount.append(status);
  try{
    let bytes=options.bytes,type=options.type||"",url=options.url&&localUrl(options.url);
    if(!bytes){
      let response;
      const urls=url?[url]:isCid(options.cid)?["/trikeshed/_cas/"+options.cid,"/api/lcnc/content?cid="+encodeURIComponent(options.cid)+"&view=bytes"]:[];
      if(!urls.length)throw Error("Blob identity required");
      for(const candidate of urls){response=await fetch(candidate,{signal});if(response.status!==404)break;}
      if(!response.ok)throw Error("Blob read failed: "+response.status);
      bytes=await readBytes(response);type=type||response.headers.get("content-type")||"";
    }
    bytes=bytes instanceof Uint8Array?bytes:new Uint8Array(bytes);
    if(bytes.byteLength>LIMIT)throw Error("Preview exceeds "+fmtBytes(LIMIT));
    if(signal.aborted)return;
    if(isCid(options.cid)){
      if(!crypto.subtle)throw Error("Content identity verification unavailable");
      const digest=await crypto.subtle.digest("SHA-256",bytes);
      const actual="sha256:"+Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,"0")).join("");
      if(actual!==options.cid)throw Error("Content identity mismatch");
    }
    if(signal.aborted)return;
    const meta=element("div","file-meta");
    meta.innerHTML=(options.cid?chip(options.cid):"");meta.append(element("span",null,fmtBytes(bytes.byteLength)));
    const gauge=element("meter","kgauge");gauge.min=0;gauge.max=1;gauge.value=0;gauge.setAttribute("aria-label","Gzip compression ratio");
    const k=element("span","file-note","Measuring K");meta.append(gauge,k);mount.replaceChildren(meta);bindRefs(meta);
    measure(bytes).then(result=>{
      if(signal.aborted)return;
      if(!result){gauge.hidden=true;k.textContent="Gzip measurement unavailable";return;}
      gauge.value=Math.min(1,result.ratio);k.textContent=(result.ratio*100).toFixed(0)+"% K";
      meta.title="Gzip "+fmtBytes(result.gzip)+" / raw "+fmtBytes(result.raw)+". Compression proxy, not exact Kolmogorov complexity.";
    }).catch(()=>{if(!signal.aborted){gauge.hidden=true;k.textContent="Gzip measurement unavailable";}});
    const body=element("div","file-projection");mount.append(body);
    const mode=kind(bytes,type,options.id||""),text=()=>new TextDecoder().decode(bytes);
    if(mode==="image"){
      const ext=extOf(options.id||""),mime=type.startsWith("image/")?type:ext==="svg"?"image/svg+xml":"image/"+(ext==="jpg"?"jpeg":ext);
      const objectUrl=URL.createObjectURL(new Blob([bytes],{type:mime}));state.urls.push(objectUrl);
      const img=element("img","imgview");img.alt=options.id||options.cid||"Blob image";img.src=objectUrl;body.append(img);
    }else if(mode==="html"){
      const frame=element("iframe","htmlview");frame.title=options.id||"HTML blob";frame.setAttribute("sandbox","");
      frame.srcdoc='<meta http-equiv="Content-Security-Policy" content="default-src &#39;none&#39;; img-src data: blob:; style-src &#39;unsafe-inline&#39;">'+text();body.append(frame);
    }else if(mode==="class")body.innerHTML=classCard(bytes)||hexHead(bytes);
    else if(mode==="binary")body.innerHTML=hexHead(bytes);
    else{
      const value=text(),source=element("div");
      source.innerHTML=shapeStripHtml(value)+codeView(value);
      source.querySelectorAll("[data-line]").forEach(button=>button.addEventListener("click",()=>{
        const row=source.querySelector(".codeview")?.children[Number(button.dataset.line)-1];row?.scrollIntoView({block:"center"});
      }));
      if(mode==="markdown"){const rendered=element("div");rendered.innerHTML=mdView(value);body.append(rendered);const details=element("details");details.append(element("summary",null,"Source"),source);body.append(details);}
      else body.append(source);
      if(value.split("\n").length>1200)body.append(element("p","file-note","First 1200 lines; compression measures all loaded bytes."));
      if(options.sourceId&&/\.(kt|kts|java)$/i.test(options.id||""))await sourceMates(options.sourceId,body,state);
    }
  }catch(error){if(!signal.aborted)mount.replaceChildren(element("p","file-error",error.message));}
}
let dialog,dialogMount;
const api={isCid,kind,readBytes,measure,codeView,mdView,classCard,hexHead,shapeRuns,shapeStripHtml,render,dispose,chip,bindRefs,
  open(reference){
    if(!dialog){
      dialog=element("dialog","graal-file-dialog");const close=element("button","file-close","\u00d7");close.setAttribute("aria-label","Close file viewer");close.addEventListener("click",()=>dialog.close());
      dialog.append(close,element("h2"));dialogMount=element("div");dialog.append(dialogMount);document.body.append(dialog);
      dialog.addEventListener("close",()=>dispose(dialogMount));
    }
    dialog.querySelector("h2").textContent=reference.id||reference.cid||"File";
    if(!dialog.open)dialog.showModal();return render(dialogMount,reference);
  }
};
return api;
})();
if(typeof module!=="undefined")module.exports=GraalFileViewer;
