"use strict";

const HarnessArguments={
  values:new Map(),receipts:new Map(),program:null,
  edits(name){if(!this.values.has(name))this.values.set(name,new Map());return this.values.get(name);},
  inputs(name){
    const edits=this.edits(name),out=Object.create(null);let size=0;
    if(edits.size>64)throw Error("At most 64 invocation bindings");
    for(const [key,raw] of edits){
      if(!key||key.length>128)throw Error("Invalid argument name");
      size+=raw.length;if(size>65536)throw Error("Invocation bindings exceed 64 KiB");
      try{out[key]=JSON.parse(raw);}catch(_){throw Error("Invalid JSON for argument "+key);}
    }
    return out;
  },
  open(){
    this.program=Harness.selected;if(!this.program)return;
    this.render();document.getElementById("argumentInspector").showModal();
  },
  render(){
    const name=this.program,doc=Harness.document(name),edits=this.edits(name);
    document.getElementById("argumentTitle").textContent=name+" arguments";
    const definitions=new Map((doc.nodes||[]).filter(n=>n.type==="scope.in").map(n=>[n.params?.name?.replace(/\?$/, ""),n.params||{}]).filter(([key])=>key));
    for(const key of edits.keys())if(!definitions.has(key))definitions.set(key,{});
    const body=document.getElementById("argumentInputs");body.replaceChildren();
    for(const [key,param] of definitions){
      const row=document.createElement("tr"),toggle=document.createElement("input"),input=document.createElement("input");
      toggle.type="checkbox";toggle.checked=edits.has(key);toggle.setAttribute("aria-label","Supply "+key);
      input.setAttribute("aria-label","Value for "+key);input.spellcheck=false;
      input.value=edits.get(key)??JSON.stringify(param.default??"");input.disabled=!toggle.checked;
      toggle.addEventListener("change",()=>{input.disabled=!toggle.checked;if(toggle.checked)edits.set(key,input.value);else edits.delete(key);this.validate();});
      input.addEventListener("input",()=>{edits.set(key,input.value);this.validate();});
      for(const value of [toggle,key,param.kind||"generic",input,Object.hasOwn(param,"default")?JSON.stringify(param.default):"unbound"]){
        const cell=document.createElement("td");if(typeof value==="string")cell.textContent=value;else cell.append(value);row.append(cell);
      }
      body.append(row);
    }
    this.validate();this.renderReceipt();
  },
  validate(){
    let error="";try{this.inputs(this.program);}catch(e){error=e.message;}
    document.getElementById("argumentError").textContent=error;
    document.getElementById("argumentRun").disabled=!!error||Harness.running||Harness.dirty||Harness.selected!==this.program||Harness.inspectionOnly();
    return !error;
  },
  add(){
    const field=document.getElementById("argumentName"),name=field.value.trim();
    if(!name||name.length>128||this.edits(this.program).has(name))return;
    this.edits(this.program).set(name,'""');field.value="";this.render();
  },
  record(receipt){
    const entry=Harness.board[receipt?.programKey];
    if(!receipt?.program||!receipt.programCid||receipt.programCid!==entry?.programCid)return;
    const old=this.receipts.get(receipt.program);
    if(old){
      if(old.runId===receipt.runId&&Number(old.timelineRevision)>Number(receipt.timelineRevision))return;
      if(old.runId!==receipt.runId&&Number(old.startedAtMs)>Number(receipt.startedAtMs))return;
      if(old.startedAtMs===receipt.startedAtMs&&Number(old.sequence)>Number(receipt.sequence))return;
    }
    this.receipts.set(receipt.program,receipt);
    if(this.program===receipt.program&&document.getElementById("argumentInspector").open)this.renderReceipt();
  },
  renderReceipt(){
    const body=document.getElementById("argumentResolved");body.replaceChildren();
    const receipt=this.receipts.get(this.program),entry=Harness.board["lcnc/program/"+this.program];
    const valid=!!receipt?.programCid&&receipt.programCid===entry?.programCid&&!Harness.drafts.has(this.program);
    const status=document.getElementById("argumentReceipt");
    status.textContent=!receipt?"No recorded invocation":!valid?"Receipt belongs to another program version":
      receipt.status+" / "+receipt.runId+(receipt.error?" / "+receipt.error:"")+(receipt.bindingsTruncated?" / binding report truncated":"");
    if(!valid)return;
    const rows=(receipt.bindings||[]).map(r=>({...r,node:r.nodeId}));
    const nodes=[];const walk=ns=>{for(const n of ns||[]){nodes.push(n);walk(n.children);}};walk(entry.document?.nodes);
    for(const n of nodes.filter(n=>n.type==="ccek.incarnate"))for(const r of receipt.outputs?.[n.id]?.arguments||[])rows.push({...r,node:n.id});
    for(const r of rows.slice(0,1024)){
      const row=document.createElement("tr");
      for(const value of [r.node,r.name,r.type,r.source,JSON.stringify(r.value),r.status]){
        const cell=document.createElement("td");cell.textContent=value??"";
        if(r.overridden)cell.title="Overrides "+r.overridden.source+": "+JSON.stringify(r.overridden.value);
        row.append(cell);
      }body.append(row);
    }
  },
};
