"use strict";
window.AllocationInspector=(()=>{
  let dialog,controller,frameController,selectedClass="",origin=null,data=null,activeFrame=null;
  const el=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text!=null)n.textContent=text;return n;};
  const bytes=n=>((Number(n)||0)/1048576).toFixed(2)+" MiB";
  const icon=(name,title,action)=>{const b=el("button","alloc-icon");b.type="button";b.title=title;b.setAttribute("aria-label",title);
    const i=el("i");i.dataset.lucide=name;b.append(i);b.addEventListener("click",action);return b;};
  const q=s=>dialog.querySelector(s);
  function mount(){
    if(dialog)return;
    dialog=el("dialog","alloc-inspector");dialog.id="allocationInspector";dialog.setAttribute("aria-labelledby","allocationTitle");
    const header=el("header","alloc-header");const title=el("h2",null,"Allocation Sites");title.id="allocationTitle";
    header.append(title,icon("refresh-cw","Refresh allocation sites",()=>load()),icon("download","Download allocation evidence",download),
      icon("x","Close allocation sites",()=>dialog.close()));
    const form=el("form","alloc-toolbar"),label=el("label",null,"Class"),input=el("input");
    input.name="class";input.id="allocationClass";input.setAttribute("list","allocationClasses");input.autocomplete="off";label.htmlFor=input.id;
    const list=el("datalist");list.id="allocationClasses";
    form.append(label,input,list,icon("search","Inspect allocated class",()=>{selectedClass=input.value.trim();load();}));
    form.addEventListener("submit",e=>{e.preventDefault();selectedClass=input.value.trim();load();});
    const status=el("div","alloc-status");status.setAttribute("role","status");
    const body=el("div","alloc-layout"),sites=el("nav","alloc-sites");sites.setAttribute("aria-label","Allocation stacks");
    const detail=el("section","alloc-detail");body.append(sites,detail);
    dialog.append(header,form,status,body);document.body.append(dialog);
    dialog.addEventListener("close",()=>{controller?.abort();frameController?.abort();});
    for(const type of ["keydown","pointerdown","pointermove","wheel"])dialog.addEventListener(type,e=>e.stopPropagation());
    window.MuxIcons?.();
  }
  async function request(path,signal){const r=await fetch(path,{signal:AbortSignal.any([signal,AbortSignal.timeout(10000)])});
    if(!r.ok)throw Error(r.status===404?"Site expired; refresh the recent samples.":"HTTP "+r.status);return r.json();}
  async function load(){
    controller?.abort();frameController?.abort();controller=new AbortController();const signal=controller.signal;
    q("input").value=selectedClass;q(".alloc-status").textContent="Loading recent samples";q(".alloc-sites").replaceChildren();q(".alloc-detail").replaceChildren();activeFrame=null;
    try{
      const result=await request("/api/graal/allocations?class="+encodeURIComponent(selectedClass),signal);if(signal.aborted)return;data=result;
      q("datalist").replaceChildren(...(result.classes||[]).map(c=>{const o=el("option");o.value=c.class;return o;}));
      let status=bytes(result.bytes)+" sampled / "+result.windowSeconds+"s | "+result.samples+" samples | "+new Date(result.toMs).toLocaleTimeString();
      if(origin?.bytes!=null)status+=" | "+(origin.kind==="live"?"Live histogram: ":"Since start: ")+bytes(origin.bytes);
      if(result.omittedSamples)status+=" | Capacity omitted "+result.omittedSamples+" samples ("+bytes(result.omittedBytes)+", all classes)";
      if(result.omittedSiteRows)status+=" | "+result.omittedSiteRows+" additional sites";
      if(!result.jfr)status+=" | JFR unavailable: "+(result.jfrError||"not running");
      q(".alloc-status").textContent=status;
      const sites=result.sites||[];
      if(!sites.length){q(".alloc-detail").textContent="No samples for this class in the recent window.";return;}
      sites.forEach((site,i)=>{
        const b=el("button","alloc-site"),top=site.frames?.[0];b.type="button";
        b.append(el("strong",null,bytes(site.bytes)),el("span",null,top?top.class+"."+top.method:"Stack unavailable"),
          el("small",null,site.samples+" samples"+(site.truncated?" | truncated stack":"")));
        b.addEventListener("click",()=>showSite(site,b));q(".alloc-sites").append(b);
        if(i===0)showSite(site,b);
      });
    }catch(e){if(!signal.aborted)q(".alloc-status").textContent="Allocation sites unavailable: "+e.message;}
  }
  function showSite(site,button){
    frameController?.abort();activeFrame=null;for(const b of q(".alloc-sites").children)b.removeAttribute("aria-current");button.setAttribute("aria-current","true");
    const host=q(".alloc-detail");host.replaceChildren();const frames=el("div","alloc-frames");
    frames.append(el("h3",null,"Sampled Stack"));
    (site.frames||[]).forEach((f,i)=>{
      const b=el("button","alloc-frame");b.type="button";b.title=f.class+"."+f.method+f.descriptor;
      b.append(el("span","alloc-method",f.class+"."+f.method),
        el("small",null,"line "+f.line+" | BCI "+f.bci+" | "+f.execution));
      b.addEventListener("click",()=>showFrame(site.id,i,b));frames.append(b);
    });
    const code=el("section","alloc-code");code.append(el("h3",null,"Classpath"));
    code.append(el("p","alloc-muted",site.frames?.length?"No frame selected.":"No stack was recorded for these samples."));
    host.append(frames,code);
    const evidence=el("details","alloc-evidence");evidence.append(el("summary",null,"AOT / Measurement"));
    const aot=data?.aot||{};
    evidence.append(el("p",null,"AOT input: "+(aot.cacheInput||"no explicit cache input")+" | mode: "+(aot.mode||"unknown")),
      el("p",null,"Per-site AOT attribution unavailable. Sampled allocations are not retained objects or GC-root paths."));host.append(evidence);
  }
  async function showFrame(site,index,button){
    frameController?.abort();frameController=new AbortController();const signal=frameController.signal;
    for(const b of q(".alloc-frames").querySelectorAll("button"))b.removeAttribute("aria-current");button.setAttribute("aria-current","true");
    const code=q(".alloc-code");code.textContent="Resolving classpath frame";
    try{
      const f=await request("/api/graal/allocation-frame?site="+encodeURIComponent(site)+"&frame="+index,signal);if(signal.aborted)return;activeFrame=f;
      code.replaceChildren(el("h3",null,"Classpath / "+(f.sourceFile||f.class)));
      if(!f.available){code.append(el("p",null,f.reason||"Class resource unavailable"));return;}
      code.append(el("p","alloc-origin",f.resource),el("p","alloc-muted",f.bytecodeEvidence));
      if(f.source){const pre=el("pre","alloc-source");f.source.lines.forEach((line,i)=>{
        const number=f.source.startLine+i,row=el("span",number===f.line?"alloc-sampled":"");row.textContent=String(number).padStart(5)+"  "+line;pre.append(row);
      });code.append(el("p","alloc-origin",f.source.id),pre);}
      else code.append(el("p","alloc-muted","Source unavailable or ambiguous."));
      const pre=el("pre","alloc-bytecode");
      for(const ins of f.instructions||[]){const row=el("span",(ins.sampled?"alloc-sampled ":"")+(ins.boxing?"alloc-boxing":""));
        row.textContent=String(ins.bci).padStart(5)+"  "+ins.opcode+" "+(ins.owner?ins.owner+".":"")+(ins.name||"")+" "+(ins.descriptor||"")+(ins.boxing?"  [boxing]":"");pre.append(row);}
      code.append(el("h3",null,"Bytecode"),pre);
      if(!f.bciMatched)code.append(el("p","alloc-muted","Recorded BCI unavailable in this class resource."));
      code.append(el("p","alloc-muted",f.sourceEvidence));
      code.scrollIntoView({block:"nearest"});
    }catch(e){if(!signal.aborted)code.textContent=e.message;}
  }
  function download(){if(!data)return;const url=URL.createObjectURL(new Blob([JSON.stringify({allocation:data,frame:activeFrame},null,2)],{type:"application/json"}));
    const a=el("a");a.href=url;a.download="allocation-sites.json";a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
  function open(name="java.lang.Integer",context=null){mount();selectedClass=name;origin=context;
    if(typeof blipLeave==="function")blipLeave();if(!dialog.open)dialog.showModal();load();}
  return {open};
})();
