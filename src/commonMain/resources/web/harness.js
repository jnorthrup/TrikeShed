"use strict";

// View state only. Documents, vocabulary, run receipts and provenance come from the board.
const Harness = {
  epoch: null, connectionGeneration: 0, viewHistory: [], focusKey: "", viewNode: null,
  board: Object.create(null), seq: 0, selected: null, applying: false, dirty: false,
  events: [], drafts: new Map(), actors: new Map(), positions: new Map(), flashes: new Map(), // Delta 2026-09-05 (fan-out): Map<key, expiresAt ms>; one Set with one timer collapsed a burst into one border
  mounts: new Map(), baselines: new Map(), nextY: 0, nextX:0, rowHeight:0,
  ready: false, live: false, running: false, frame: 0, activeBounds: {w:1600,h:900},
  connectionReport: null, shaking: false,
  parentHandle: null, parentRevision: 0, dragMoved: false,
  el(tag, cls, value) {
    const el = document.createElement(tag);
    if (cls) el.className = cls;
    if (value != null) el.textContent = String(value);
    return el;
  },
  message(value) { $("#status").textContent = value; },
  inspectionOnly(name=this.selected) {return (this.drafts.get(name)||this.board["lcnc/program/"+name]?.document)?.controls?.inspectionOnly===true;},
  programs() {
    return [...new Set([...Object.keys(this.board).filter(k => k.startsWith("lcnc/program/") && this.board[k]?.document), ...[...this.drafts.keys()].map(n=>"lcnc/program/"+n)])].sort();
  },
  changed() {
    if (this.applying || !this.ready) return;
    if(this.connectionReport){this.connectionReport=null;$("#connections").hidden=true;clearVerdicts();}
    this.dirty = JSON.stringify(this.document()) !== this.baselines.get(this.selected);
    if(this.dirty)this.drafts.set(this.selected,this.document());else this.drafts.delete(this.selected);
    $("#runBtn").disabled = this.dirty || this.running || this.inspectionOnly();
    if(this.dirty)this.message("Unpublished changes in " + this.selected);
    this.schedule();
  },
  document(name=this.selected) {
    const entry = this.board["lcnc/program/" + name] || {document:this.drafts.get(name)};
    const origin=this.mounts.get(name)||{x:0,y:0};
    const owned=G.nodes.filter(n=>n._program===name), ids=new Map(owned.map(n=>[n.id,n._localId||n.id]));
    const original=new Map();
    const walk=nodes=>{for(const n of nodes||[]){original.set(n.id,n);walk(n.children);}};walk(entry?.document?.nodes);
    // A mount translation must not round-trip authored coordinates through
    // floating-point subtraction. Reuse its exact local values until edited.
    const coordinate=(n,axis)=>n._parentScope?n[axis]:n._placement?.[axis]===n[axis]?n._placement.local[axis]:n[axis]-origin[axis];
    const node=n=>({...original.get(ids.get(n.id)),...nodeDoc(n),id:ids.get(n.id),x:coordinate(n,"x"),y:coordinate(n,"y"),children:(n.children||[]).map(node)});
    return {...entry?.document,nodes:owned.filter(n=>!n._parentScope).map(node),
      wires:G.wires.filter(w=>ids.has(w.from[0])&&ids.has(w.to[0])).map(w=>({from:[ids.get(w.from[0]),w.from[1]],to:[ids.get(w.to[0]),w.to[1]]})),seq:Math.max(entry?.document?.seq||1,...[...ids.values()].map(id=>/^n\d+$/.test(id)?Number(id.slice(1))+1:1))};
  },
  select(name, focus = true, preserveParent = false) {
    const entry = this.board["lcnc/program/" + name] || {document:this.drafts.get(name)};
    if (!entry?.document) { this.message("Program is not on the board: " + name); return false; }
    if (this.selected && this.dirty) this.drafts.set(this.selected, this.document());
    const changedMain=this.selected!==name;
    this.selected = name;
    if(changedMain||!preserveParent)this.setParent(null);
    if(this.connectionReport?.program!==name){this.connectionReport=null;$("#connections").hidden=true;clearVerdicts();}
    if(!this.mounts.has(name))this.mount(name);
    if(changedMain){UNDO.length = 0; REDO.length = 0; lastDoc = JSON.stringify(this.document()); histButtons();}
    $("#panelName").value = name.replace(/^preset-/, "");
    $("#programSelect").value = name;
    this.dirty = this.drafts.has(name);
    $("#runBtn").disabled = this.dirty || this.running || this.inspectionOnly();
    $("#runBtn").title=this.inspectionOnly()?"Inspection-only wiring specimen":"Run selected program";
    this.render();
    if (focus) this.fit(false);
    this.message(name + (this.dirty ? " has unpublished changes" : " on the blackboard"));
    const url=new URL(location.href);url.pathname="/harness";url.searchParams.set("load",name);history.replaceState(null,"",url);
    return true;
  },
  setParent(node) {
    const next={program:this.selected,nodeId:node?._localId||node?.id||null};
    if(this.parentHandle?.program!==next.program||this.parentHandle?.nodeId!==next.nodeId)this.parentRevision++;
    this.parentHandle=next;
    this.refreshParent();
  },
  selectParent(node) {
    if(node&&node._program!==this.selected&&!this.select(node._program,false))return;
    this.setParent(node?._childHost?node:node?._parentScope||null);
  },
  parentTarget() {
    const handle=this.parentHandle?.program===this.selected?this.parentHandle:{program:this.selected,nodeId:null};
    const node=handle.nodeId==null?null:G.nodes.find(n=>n._program===handle.program&&(n._localId||n.id)===handle.nodeId&&n._childHost);
    if(!handle.program||(handle.nodeId!=null&&!node))return null;
    const nodes=node?node.children||[]:G.nodes.filter(n=>n._program===handle.program&&!n._parentScope);
    return {handle,node,nodes,origin:node?{x:0,y:0}:this.mounts.get(handle.program)||{x:0,y:0}};
  },
  refreshParent() {
    const target=this.parentTarget();
    if(!target){
      if(this.selected&&this.parentHandle?.nodeId!=null){this.message("Selected scope removed; parent is now "+this.selected);this.setParent(null);}
      return;
    }
    for(const n of G.nodes)n.el?.classList.toggle("selected-parent",n===target.node);
    const labels=[];for(let n=target.node;n;n=n._parentScope)labels.unshift(n._localId||n.id);
    const label=[target.handle.program,...labels].join(" / ");
    const grip=$("#parentHandle");grip.textContent="⠿ "+label;grip.title="Drag selected parent: "+label+" (or Meta-drag anywhere in the landscape)";grip.setAttribute("aria-label","Selected parent: "+label);
    for(const [id,verb] of [["fitBtn","Fit"],["fdBtn","Layout"],["shakeBtn","Shake"]])$("#"+id).title=verb+" selected parent: "+label;
  },
  dragParent(event, leaf=null) {
    if(event.button!==0)return;
    const target=this.parentTarget();if(!target)return;
    event.preventDefault();event.stopPropagation();this.dragMoved=false;
    if(typeof killMomentum==="function")killMomentum();
    const node=leaf||target.node, nodes=node?[node]:target.nodes;
    const scale=view.z*(node?ringScaleOf(node):1),sx=event.clientX,sy=event.clientY;
    const anchor=node?null:target.origin,origin=anchor&&{x:anchor.x,y:anchor.y};
    const locals=anchor?new Map(this.document(target.handle.program).nodes.map(n=>[n.id,{x:n.x,y:n.y}])):null;
    const starts=nodes.map(n=>({n,x:n.x,y:n.y,placement:n._placement,local:locals?.get(n._localId||n.id)}));
    const revision=this.parentRevision;
    const stale=()=>this.parentRevision!==revision||starts.some(s=>!G.nodes.includes(s.n));
    const move=e=>{
      if(stale())return finish(true);
      if(!this.dragMoved&&Math.hypot(e.clientX-sx,e.clientY-sy)<3)return;
      this.dragMoved=true;
      const dx=(e.clientX-sx)/scale,dy=(e.clientY-sy)/scale;
      if(anchor){anchor.x=origin.x+dx;anchor.y=origin.y+dy;}
      for(const s of starts){s.n.x=s.x+dx;s.n.y=s.y+dy;if(s.n._parentScope){s.n.x=Math.max(0,s.n.x);s.n.y=Math.max(0,s.n.y);}if(anchor)s.n._placement={x:s.n.x,y:s.n.y,local:s.local};s.n.el.style.left=s.n.x+"px";s.n.el.style.top=s.n.y+"px";}
      redraw();this.schedule();
    };
    const finish=cancelled=>{
      cancelled=cancelled||stale();
      removeEventListener("pointermove",move);removeEventListener("pointerup",up);removeEventListener("pointercancel",cancel);
      if(cancelled){if(anchor)Object.assign(anchor,origin);for(const s of starts){s.n.x=s.x;s.n.y=s.y;s.n._placement=s.placement;s.n.el.style.left=s.x+"px";s.n.el.style.top=s.y+"px";}}
      else if(this.dragMoved&&node){resizeParentFrames(node._parentScope);save();}
      redraw();this.schedule();
    };
    const up=()=>finish(false),cancel=()=>finish(true);
    addEventListener("pointermove",move);addEventListener("pointerup",up);addEventListener("pointercancel",cancel);
  },
  mount(name,document) {
    const entry=this.board["lcnc/program/"+name]||{document:this.drafts.get(name)};if(!entry?.document)return;
    if(this.connectionReport?.program===name){this.connectionReport=null;$("#connections").hidden=true;clearVerdicts();}
    const previous=this.applying;this.applying=true;
    try {
      let anchor=this.mounts.get(name);
      const fresh=!anchor;
      if(!anchor){anchor={x:this.nextX,y:this.nextY,w:1550,h:720};this.mounts.set(name,anchor);}
      const oldIds=new Set(G.nodes.filter(n=>n._program===name).map(n=>n.id));
      for(const n of G.nodes)if(oldIds.has(n.id)){if(n._timer)clearInterval(n._timer);n._es?.close();n.el?.remove();}
      G.nodes=G.nodes.filter(n=>!oldIds.has(n.id));G.wires=G.wires.filter(w=>!oldIds.has(w.from[0])&&!oldIds.has(w.to[0]));
      for(const key of BOARD.cables.keys())if(key.startsWith(name+"::"))BOARD.cables.delete(key);
      for(const key of BOARD.violations.keys())if(key.startsWith(name+"::"))BOARD.violations.delete(key);
      const id=local=>name+"::"+local;
      const clone=(source,parent)=>{
        if(!CONTRACTS[source.type])return null;
        const n={...source,id:id(source.id),_localId:source.id,_program:name,_parentScope:parent,params:{...source.params},children:[],x:(source.x||0)+(parent?0:anchor.x),y:(source.y||0)+(parent?0:anchor.y)};
        G.nodes.push(n);buildNode(n);
        n.children=(source.children||[]).map(c=>clone(c,n)).filter(Boolean);return n;
      };
      const doc=document||this.drafts.get(name)||entry.document;
      for(const n of doc.nodes||[])clone(n,null);
      for(const n of G.nodes.filter(n=>n._program===name&&n._childHost))refreshRingChrome(n);
      for(const n of G.nodes.filter(n=>n._program===name&&n._childHost&&!n._parentScope))layoutRing(n,!!document||this.drafts.has(name));
      resolveTopLevelOverlaps();
      const allIds=new Set(G.nodes.filter(n=>n._program===name).map(n=>n.id));
      for(const wire of fromConfix(doc).wires)if(allIds.has(id(wire.from[0]))&&allIds.has(id(wire.to[0])))G.wires.push({from:[id(wire.from[0]),wire.from[1]],to:[id(wire.to[0]),wire.to[1]]});
      for(const c of entry.cables||[])BOARD.cables.set(cableKey(id(c.from[0]),c.from[1],id(c.to[0]),c.to[1]),c.type);
      for(const v of entry.violations||[])BOARD.violations.set(cableKey(id(v.fromNode),v.fromPort,id(v.toNode),v.toPort),v.detail||v.rule);
      Object.assign(anchor,this.bounds(name));
      if(fresh){
        // Normalize only the mount origin; document-local coordinates survive.
        for(const n of G.nodes.filter(n=>n._program===name&&!n._parentScope)){
          n.x-=anchor.left;n.y-=anchor.top;n.el.style.left=n.x+"px";n.el.style.top=n.y+"px";
        }
        anchor.x-=anchor.left;anchor.y-=anchor.top;
        if(this.mounts.size===1){this.nextY=anchor.y+anchor.top+anchor.h+180;this.nextX=0;}
        else {this.nextX=anchor.x+anchor.left+anchor.w+120;this.rowHeight=Math.max(this.rowHeight,anchor.h);if(this.nextX>6500){this.nextY+=this.rowHeight+180;this.nextX=0;this.rowHeight=0;}}
      }
      if(!document&&!this.drafts.has(name))this.baselines.set(name,JSON.stringify(this.document(name)));
    }finally{this.applying=previous;}
  },
  replaceSelected(doc) { this.mount(this.selected,doc);this.changed();this.render(); },
  unmount(name) {
    const ids=new Set(G.nodes.filter(n=>n._program===name).map(n=>n.id));
    for(const n of G.nodes)if(ids.has(n.id)){if(n._timer)clearInterval(n._timer);n._es?.close();n.el?.remove();}
    G.nodes=G.nodes.filter(n=>!ids.has(n.id));G.wires=G.wires.filter(w=>!ids.has(w.from[0])&&!ids.has(w.to[0]));
    this.mounts.delete(name);this.baselines.delete(name);
    for(const table of [BOARD.cables,BOARD.violations])for(const key of table.keys())if(key.startsWith(name+"::"))table.delete(key);
  },
  schedule() {
    if (this.frame) return;
    this.frame = requestAnimationFrame(() => { this.frame = 0; this.render(); });
  },
  bounds(name=this.selected) {
    const origin=this.mounts.get(name)||{x:0,y:0};
    const nodes=G.nodes.filter(n=>!n._parentScope&&n._program===name);
    if(!nodes.length)return {left:0,top:0,w:320,h:180};
    const left=Math.min(...nodes.map(n=>n.x-origin.x)),top=Math.min(...nodes.map(n=>n.y-origin.y));
    const right=Math.max(...nodes.map(n=>n.x-origin.x+(n.el?.offsetWidth||190)));
    const bottom=Math.max(...nodes.map(n=>n.y-origin.y+(n.el?.offsetHeight||100)));
    return {left,top,w:right-left,h:bottom-top};
  },
  programRight() {return Math.max(0,...[...this.mounts.values()].map(a=>a.x+(a.left||0)+a.w));},
  territory(key, title, count, x, y, w, h, tone) {
    const el = this.el("section", "territory");
    el.dataset.territory = key;
    Object.assign(el.style, {left:x+"px", top:y+"px", width:w+"px", height:h+"px"});
    el.style.setProperty("--tone", tone);
    if (this.flashes.has(key)) el.classList.add("flash");
    const head = this.el("header");
    head.append(this.el("h2", "", title), this.el("span", "", count));
    el.append(head);
    $("#territories").append(el);
    this.positions.set(key, {x,y,w,h});
    return el;
  },
  render() {
    if (!this.ready) return;
    this.refreshParent();
    $("#territories").replaceChildren();
    this.positions.clear();
    const {w,h} = this.activeBounds = this.bounds();
    for(const [name,anchor] of this.mounts){
      const box=this.bounds(name);Object.assign(anchor,box);
      const territory=this.territory("lcnc/program/"+name,name,this.drafts.has(name)?"Unpublished":"lcnc/program/"+name,anchor.x+box.left-24,anchor.y+box.top-64,box.w+48,box.h+88,"#64ceca");
      territory.classList.add("active-program");if(name===this.selected)territory.classList.add("selected");
      territory.classList.toggle("selected-parent",name===this.selected&&!this.parentTarget()?.node);
      const head=territory.querySelector("header");
      head.addEventListener("pointerdown",e=>{if(e.target.closest("button")||e.button!==0)return;if(name!==this.selected)this.select(name,false);else this.setParent(null);this.dragParent(e);});
      head.addEventListener("dblclick",()=>this.fit(false));
      const cid=this.board["lcnc/program/"+name]?.programCid;
      if(cid){const source=this.el("button","source-ref","◇");source.title="Program version "+cid;source.setAttribute("aria-label","Inspect version of "+name);source.dataset.relation="reference";source.addEventListener("click",e=>{e.stopPropagation();Landscape.inspectCid(cid,"lcnc/program/"+name);});head.append(source);}
      const run=this.el("button","run","▶ Run");run.disabled=this.running||this.drafts.has(name)||this.inspectionOnly(name);run.setAttribute("aria-label","Run "+name);
      run.addEventListener("click",e=>{e.stopPropagation();this.select(name,false);this.run();});head.append(run);
    }
    const groups = new Map();
    for (const [key,value] of Object.entries(this.board)) {
      const prefix = key.split("/")[0];
      if (prefix !== "lcnc") {
        if (!groups.has(prefix)) groups.set(prefix, []);
        groups.get(prefix).push([key,value]);
      }
    }
    const side=this.programRight()+100;
    Landscape.positionObjects();
    const runs = Object.entries(this.board).filter(([k,v]) => k.startsWith("lcnc/run/") && v.program === this.selected)
      .sort((a,b) => (b[1].sequence || b[1].startedAtMs || 0) - (a[1].sequence || a[1].startedAtMs || 0));
    $("#cancelRun").disabled=!runs.some(([,v])=>["validating","running"].includes(v.status));
    const receipt = this.territory("receipts", "Run receipt", runs.length + " runs", side, -80, 600, 350, "#91c49d");
    if (runs.length) {
      const [key,value] = runs[0];
      const inspect = this.el("button", "fact-row", value.status + " / " + value.runId);
      inspect.addEventListener("click", () => this.inspect(key));
      receipt.append(inspect, this.el("pre", "run-result" + (value.ok === false ? " error" : ""), JSON.stringify({inputs:value.inputs, returns:value.returns, error:value.error, violations:value.violations}, null, 2)));
    } else receipt.append(this.el("p", "", "No recorded run for this program"));
    let y = 300;
    for (const [prefix, items] of [...groups].sort((a,b) => b[1].length-a[1].length)) {
      const height = Math.max(190, Math.min(560, 140 + Math.sqrt(items.length)*11));
      const tone = prefix === "narsese" ? "#c4b07c" : prefix === "kanban" ? "#80b4d2" : "#a7a1c9";
      if (prefix === "narsese") { y += this.narseseTerritory(items, side, y, tone) + 24; continue; }
      const territory = this.territory(prefix, prefix, items.length + " entries", side, y, 600, height, tone);
      this.sheetButton(territory, prefix);
      if (items.length > 24) {
        const field = this.el("div", "fact-field");
        for (const [key,value] of items) {
          const cell = this.el("button", "fact-cell");
          cell.dataset.key=key;
          cell.title = key + "\n" + this.summary(value);
          cell.setAttribute("aria-label", key);
          if (this.flashes.has(key)) cell.classList.add("flash");
          cell.addEventListener("click", () => this.inspect(key));
          field.append(cell);
        }
        territory.append(field);
      } else for (const [key,value] of items) {
        const row = this.el("button", "fact-row");
        row.dataset.key=key;
        row.append(this.el("b", "", key.split("/").slice(1).join("/")), this.el("span", "", this.summary(value)));
        row.title = key;
        if (this.flashes.has(key)) row.classList.add("flash");
        row.addEventListener("click", () => this.inspect(key));
        territory.append(row);
      }
      y += height + 24;
    }
    const programKeys = this.programs();
    const actorValues = new Map();
    for (const [key,value] of Object.entries(this.board)) {
      const actor = this.actors.get(key) || (value && typeof value === "object" ? value.actor : null);
      if (!actor) continue;
      const data = actorValues.get(actor) || {count:0, prefixes:new Set()};
      data.count++; data.prefixes.add(key.split("/")[0]); actorValues.set(actor,data);
    }
    const agents = this.territory("actors", "Actors", actorValues.size + " observed", side+630, -80, 420, Math.max(210,70+actorValues.size*68), "#d59db1");
    for (const [name,data] of actorValues) {
      const row = this.el("div", "actor-row", name);
      row.append(this.el("small", "", data.count + " entries / " + [...data.prefixes].join(", ")));
      agents.append(row);
    }
    const vocab = this.board["lcnc/vocabulary"]?.contracts || [];
    const vocabulary = this.territory("vocabulary", "Vocabulary", vocab.length + " contracts", side+630, Math.max(180,actorValues.size*68), 420, 620, "#80b4d2");
    const families = new Map();
    for (const c of vocab) { const f = c.type.split(".")[0]; families.set(f,(families.get(f)||0)+1); }
    const buttons = this.el("div", "vocab-groups");
    for (const [family,count] of [...families].sort()) {
      const button = this.el("button", "", family+" "+count);
      button.addEventListener("click", async () => { await buildPalette(); $("#palette").classList.add("open"); const q=$("#palette input"); q.value=family; q.dispatchEvent(new Event("input")); });
      buttons.append(button);
    }
    vocabulary.append(buttons);
    if(Landscape.rows.length){
      const box=Landscape.objectBox;
      this.territory("objects","Graal objects",Landscape.rows.length+" objects",box.x,box.y-60,box.w,box.h+60,"#84a7bf").classList.add("objects");
    }
    const nav = $("#boardnav"); nav.replaceChildren();
    for (const [key,box] of this.positions) {
      if(key.startsWith("lcnc/program/")&&key!=="lcnc/program/"+this.selected)continue;
      const button = this.el("button", "", key.startsWith("lcnc/program/") ? this.selected : key);
      button.addEventListener("click", () => this.focus(box)); nav.append(button);
    }
    const select = $("#programSelect");
    if (select.options.length !== programKeys.length || programKeys.some((k,i)=>select.options[i]?.value!==k.slice(13))) {
      select.replaceChildren(...programKeys.map(key=> {const option=this.el("option","",key.slice(13));option.value=key.slice(13);return option;}));
    }
    select.value = this.selected;
    Landscape.refreshActivity();this.renderEvents(); this.channels(runs[0]?.[1]);
    $("#boardCount").textContent = Object.keys(this.board).length + " entries";
    applyView(); redraw();Landscape.schedule();
  },
  /* Narsese on the board is shown as EXPRESSIONS and linked by their terms.
     A curation receipt carries the expression the minter knew (plus subject /
     object / relation when the bag knows them); a rete firing carries its
     antecedent and consequent. Rows are the expressions; a row whose object
     (or consequent) is another row's subject (or antecedent) is a derivation
     step, and channels() draws that chain. A receipt with no expression is
     shown as exactly that — a coordinate the daemon could not caption — so
     the gap is visible instead of silent. */
  narseseExpression(key, value) {
    if (!value || typeof value !== "object") return null;
    if (value.expression) return value.expression;
    if (value.antecedent && value.consequent) return value.antecedent + " ==> " + value.consequent;
    if (value.subject && value.object) return value.subject + " --> " + value.object;
    return null;
  },
  narseseTerms(value) {
    if (!value || typeof value !== "object") return null;
    const head = value.antecedent || value.subject, tail = value.consequent || value.object;
    if (head && tail) return {head, tail};
    // "source: head ==> tail (note)" — the source prefix is provenance, not a term
    const m = /^(?:[^:«»]+:\s)?(.+?)\s(==>|-->|<->|=\/>|--\/>|→)\s(.+?)(\s\(.*\))?$/.exec(value.expression || "");
    return m ? {head:m[1], tail:m[3]} : null;
  },
  narseseTerritory(items, x, y, tone) {
    const rank = v => v?.event === "minted" ? 0 : v?.event === "dependent-rete-firing" ? 1 : v?.event === "revised" ? 2 : 3;
    const rows = items.map(([key,value]) => ({key, value, expression:this.narseseExpression(key,value), terms:this.narseseTerms(value)}))
      .sort((a,b) => (a.expression?0:1)-(b.expression?0:1) || rank(a.value)-rank(b.value) || a.key.localeCompare(b.key));
    const shown = rows.filter(r => r.expression).length, blind = rows.length - shown;
    const LIMIT = 36, visible = rows.slice(0, LIMIT), rest = rows.slice(LIMIT);
    const height = 96 + visible.length*34 + (rest.length ? 60 + Math.ceil(rest.length/50)*12 : 0);
    const territory = this.territory("narsese", "narsese", shown + " expressions" + (blind ? " · " + blind + " uncaptioned" : ""), x, y, 600, height, tone);
    this.sheetButton(territory, "narsese");
    const byHead = new Map();
    this.narseseChains = [];
    for (const r of visible) {
      const row = this.el("button", "fact-row nal-row" + (r.expression ? "" : " blind"));
      row.dataset.key = r.key;
      const tag = (r.value?.event || "") + (r.value?.relation ? " · " + r.value.relation.toLowerCase() : "") + (typeof r.value?.expectation === "number" ? " · e=" + r.value.expectation.toFixed(2) : "");
      row.append(this.el("b", "", r.expression || ("∠ " + (r.value?.angular || r.key) + " — no expression on this daemon")), this.el("span", "", tag));
      row.title = r.key;
      if (this.flashes.has(r.key)) row.classList.add("flash");
      row.addEventListener("click", () => this.inspect(r.key));
      territory.append(row);
      r.el = row;
      if (r.terms) { if (!byHead.has(r.terms.head)) byHead.set(r.terms.head, []); byHead.get(r.terms.head).push(r); }
    }
    for (const r of visible) {
      if (!r.terms) continue;
      for (const next of byHead.get(r.terms.tail) || []) if (next !== r) this.narseseChains.push([r.el, next.el]);
    }
    if (rest.length) {
      territory.append(this.el("p", "nal-more", rest.length + " more receipts"));
      const field = this.el("div", "fact-field");
      for (const r of rest) {
        const cell = this.el("button", "fact-cell" + (r.expression ? "" : " blind"));
        cell.dataset.key=r.key;
        cell.title = (r.expression || "no expression") + "\n" + r.key; cell.setAttribute("aria-label", r.expression || r.key);
        if (this.flashes.has(r.key)) cell.classList.add("flash");
        cell.addEventListener("click", () => this.inspect(r.key));
        field.append(cell);
      }
      territory.append(field);
    }
    return height;
  },
  channels(receipt) {
    const svg=$("#channels");svg.replaceChildren();
    // Term correspondence is an association, not evidence of a derivation.
    const w=world.getBoundingClientRect();
    for(const link of Landscape.activityLinks){
      const from=this.positions.get(link.from.split("/")[0]),to=this.positions.get(link.to.split("/")[0]);
      if(!from||!to||from===to)continue;
      const path=document.createElementNS(svg.namespaceURI,"path");path.setAttribute("class","activity-ref");path.dataset.relation="reference";
      const title=document.createElementNS(svg.namespaceURI,"title");title.textContent=link.kind+": "+link.from+" → "+link.to+" (not causal support)";path.append(title);
      path.setAttribute("d",bez({x:from.x+from.w,y:from.y+70},{x:to.x+to.w,y:to.y+70}));svg.append(path);
    }
    for (const [from,to] of this.narseseChains || []) {
      const a=from.getBoundingClientRect(), b=to.getBoundingClientRect();
      const ax=(a.right-w.left)/view.z, ay=(a.top+a.height/2-w.top)/view.z, bx=(b.right-w.left)/view.z, by=(b.top+b.height/2-w.top)/view.z;
      const path=document.createElementNS(svg.namespaceURI,"path");path.setAttribute("class","chain");
      path.dataset.relation="association";const title=document.createElementNS(svg.namespaceURI,"title");title.textContent="Term association, not causal support";path.append(title);
      path.setAttribute("d",`M ${ax} ${ay} C ${ax+70} ${ay}, ${bx+70} ${by}, ${bx} ${by}`);svg.append(path);
    }
    if(!receipt)return;
    const a=this.positions.get(receipt.programKey),b=this.positions.get("receipts");if(!a||!b)return;
    const path=document.createElementNS(svg.namespaceURI,"path");path.dataset.relation="execution";path.setAttribute("d",bez({x:a.x+a.w,y:a.y+160},{x:b.x,y:b.y+160}));svg.append(path);
    const label=document.createElementNS(svg.namespaceURI,"text");label.setAttribute("x",String(a.x+a.w+8));label.setAttribute("y",String(a.y+147));label.textContent=receipt.status;svg.append(label);
  },
  summary(value) {
    if (typeof value === "string") return value;
    if (!value || typeof value !== "object") return String(value);
    return Object.entries(value).slice(0,4).map(([k,v])=>k+": "+(typeof v === "object" ? JSON.stringify(v) : v)).join(" / ").slice(0,250);
  },
  beginInspection(key, actor, raw = "") {
    this.inspectionController?.abort();
    this.inspectionController = new AbortController();
    this.sheetTicket = (this.sheetTicket || 0) + 1;
    this.sheetNode = null;
    $("#factInspector").querySelectorAll(".terrain-ref").forEach(e=>e.remove());
    $("#factKey").textContent = key; $("#factActor").textContent = actor;
    $("#factValue").textContent = raw; $("#factSheet").replaceChildren(); $("#sheetRoots").replaceChildren();
    this.rawSheets(false);
    if (!$("#factInspector").open) $("#factInspector").showModal();
    return this.inspectionController.signal;
  },
  inspect(key) {
    this.beginInspection(key, this.actors.get(key)||this.board[key]?.actor||"", JSON.stringify(this.board[key],null,2));
    this.loadSheets([{url:"/blackboard/sheet?key="+encodeURIComponent(key)}], key);
    const receipt=this.board[key];
    for(const link of Landscape.activityLinks.filter(l=>l.from===key||l.to===key)){
      const target=link.from===key?link.to:link.from;
      const button=this.el("button","terrain-ref",link.kind+" → "+target);button.dataset.relation="reference";
      button.title="Recorded identifier reference, not causal support";button.addEventListener("click",()=>this.inspect(target));$("#factInspector").append(button);
    }
    if(receipt?.programCid){const button=this.el("button","terrain-ref","Program version "+receipt.programCid.slice(0,16));button.addEventListener("click",()=>Landscape.inspectCid(receipt.programCid));$("#factInspector").append(button);}
  },
  /* Sheets. A fact, a territory, or the whole board opens as the grid-in-cell family
     /blackboard/sheet projects (CursorSheet/confixSheets — the same projection /api/graal/sheet
     serves for a couch document): rows are (key, value), a container value is a ▤ ref cell that
     drills in, the crumb climbs out. The kanban territory also carries kanban.activeSheets
     (board · byStatus · byPriority · the lane ring) from /api/lcnc/kanban — the board's own
     sheets, the ones the MCP resource serves. The renderer is patch.js's renderConcentric on
     a synthetic node whose el is the dialog host; nothing here reshapes a sheet. */
  sheetButton(territory, prefix) {
    const button = this.el("button", "sheets", "▤");
    button.title = prefix + " as sheets"; button.setAttribute("aria-label", prefix + " as sheets");
    button.addEventListener("click", e => { e.stopPropagation(); this.openSheets([prefix]); });
    territory.querySelector("header").append(button);
  },
  openSheets(prefixes) {
    const list = prefixes.length ? prefixes : [...new Set(Object.keys(this.board).map(k => k.split("/")[0]))].sort();
    const sources = list.map(p => ({url:"/blackboard/sheet?prefix=" + encodeURIComponent(p) + "&max=1024"}));
    if (list.includes("kanban")) sources.push({url:"/api/lcnc/kanban", kanban:true});
    this.beginInspection((prefixes.length ? list.join(" · ") : "blackboard") + " as sheets", "");
    this.loadSheets(sources, list.includes("kanban") ? "board" : list[0]);
  },
  async loadSheets(sources, cur) {
    const host = $("#factSheet"), dialog = $("#factInspector");
    const ticket = this.sheetTicket = (this.sheetTicket || 0) + 1;
    host.replaceChildren(this.el("p", "sheet-note", "Projecting sheets"));
    $("#sheetRoots").replaceChildren(); this.rawSheets(false);
    const idx = {}, notes = [], continuations=[]; let orch = null;
    await Promise.all(sources.map(async src => {
      try {
        const r = await fetch(src.url, {signal:this.inspectionController?.signal}); if (!r.ok) throw Error(r.status + " on " + src.url);
        const j = JSON.parse(await Landscape.readText(r));
        const sheets = src.kanban ? [j.board, ...(j.byStatus || []), ...(j.byPriority || [])] : j;
        if(!src.kanban&&j[0]?.nextKey){const url=new URL(src.url,location.href);url.searchParams.set("after",j[0].nextKey);url.searchParams.set("revision",j[0].boardRevision);continuations.push(url.pathname+url.search);}
        if (src.kanban) orch = j.orchestration || null;
        for (const s of sheets || []) if (s && s.id) idx[s.id] = s;
      } catch (e) { notes.push(String(e.message || e)); }
    }));
    if (ticket !== this.sheetTicket) return;
    if (!Object.keys(idx).length) {
      host.replaceChildren(this.el("p", "sheet-note error", "No sheets: " + (notes.join("; ") || "empty projection")));
      if ($("#factValue").textContent) this.rawSheets(true);
      return;
    }
    host.replaceChildren();
    this.sheetNode = {el:host, _sheets:idx, _cur:idx[cur] ? cur : Object.keys(idx)[0], _orch:orch, _notes:notes};
    this.renderSheets();
    if (notes.length) host.append(this.el("p", "sheet-note error", notes.join("; ")));
    for(const url of continuations){const button=this.el("button","sheet-more","Next page");button.addEventListener("click",()=>this.loadSheets([{url}],cur));host.append(button);}
  },
  renderSheets() {
    const n = this.sheetNode; if (!n) return;
    const roots = Object.values(n._sheets).filter(s => !s.parent || !n._sheets[s.parent]);
    let top = n._sheets[n._cur], guard = 0;
    while (top && top.parent && n._sheets[top.parent] && guard++ < 24) top = n._sheets[top.parent];
    const strip = $("#sheetRoots"); strip.replaceChildren();
    if (roots.length > 1) for (const r of roots) {
      const b = this.el("button", top && top.id === r.id ? "here" : "", "▤ " + (r.title || r.id));
      b.addEventListener("click", () => { n._cur = r.id; this.renderSheets(); });
      strip.append(b);
    }
    renderConcentric(n);
    // a drill-in or crumb click re-renders inside patch.js; keep the roots strip honest
    n.el.querySelectorAll("[data-sheet]").forEach(el => el.addEventListener("click", () => this.renderSheets()));
  },
  rawSheets(on) {
    $("#factInspector").classList.toggle("raw", on);
    $("#sheetRawBtn").setAttribute("aria-pressed", String(on));
  },
  renderEvents() {
    const host=$("#events");host.replaceChildren();
    for (const event of this.events.slice(0,50)) {
      const button=this.el("button","event-row");
      button.append(this.el("b","",event.key),this.el("small","",(event.actor||"unknown actor")+" / #"+event.seq+" / "+new Date(event.atMs).toLocaleTimeString()));
      button.addEventListener("click",()=>this.inspect(event.key));host.append(button);
    }
  },
  focus(box, identity="", remember=true) {
    if(remember){this.viewHistory.push({camera:{...view},focus:this.focusKey,node:this.viewNode});if(this.viewHistory.length>64)this.viewHistory.shift();}
    this.focusKey=identity;this.viewNode=null;$("#viewBack").disabled=!this.viewHistory.length;
    if(typeof killMomentum==="function")killMomentum(); // a focus is a hard cut, never a glide target
    const r=viewport.getBoundingClientRect(),pad=30;
    view.z=Math.min(4000,Math.max(.01,Math.min((r.width-pad*2)/box.w,(r.height-pad*2)/box.h)));
    view.x=(r.width-box.w*view.z)/2-box.x*view.z;view.y=(r.height-box.h*view.z)/2-box.y*view.z;applyView();redraw();
    this.rememberView();
  },
  rememberView() {
    if(!this.ready)return;
    clearTimeout(this.viewSave);this.viewSave=setTimeout(()=>{
      const url=new URL(location.href);url.hash=LandscapeNavigation.encode(view,this.focusKey);history.replaceState(null,"",url);
    },200);
  },
  previousView() {
    const previous=this.viewHistory.pop();if(!previous)return;
    Object.assign(view,previous.camera);this.focusKey=previous.focus;this.viewNode=previous.node;
    $("#viewBack").disabled=!this.viewHistory.length;applyView();redraw();this.rememberView();
  },
  enclosingView() {
    const node=this.parentTarget()?.node;
    if(node?._parentScope)this.focusNode(node._parentScope);
    else if(node)this.select(node._program);
    else this.fit(true);
  },
  focusNode(node) {this.selectParent(node);this.focusElement(node.el,LandscapeNavigation.node(node._program,node._localId||node.id));this.viewNode=node;},
  // Editing ownership is sticky. Panning, fitting the board and zooming out
  // cannot retarget layout or mutation commands.
  prominent() {return this.selected;},
  observeZoom(px,py) {
    const r=viewport.getBoundingClientRect();
    for(const [key,a] of this.positions){
      if(!key.startsWith("lcnc/program/"))continue;
      const x=a.x*view.z+view.x,y=a.y*view.z+view.y,w=a.w*view.z,h=a.h*view.z;
      const area=Math.max(0,Math.min(r.width,x+w)-Math.max(0,x))*Math.max(0,Math.min(r.height,y+h)-Math.max(0,y));
      if(px<x||py<y||px>x+w||py>y+h||area<r.width*r.height*.55)continue;
      const name=key.slice(13);
      if(name!==this.selected)this.select(name,false);
      return;
    }
  },
  fit(all) {
    if (!all) {
      const target=this.parentTarget();if(!target)return;
      if(target.node){this.focusElement(target.node.el,LandscapeNavigation.node(this.selected,target.handle.nodeId));this.viewNode=target.node;}
      else {const box=this.positions.get("lcnc/program/"+this.selected);if(box)this.focus(box,LandscapeNavigation.program(this.selected));}
      return;
    }
    const boxes=[...this.positions.values()];
    const x=Math.min(...boxes.map(b=>b.x)),y=Math.min(...boxes.map(b=>b.y));
    this.focus({x,y,w:Math.max(...boxes.map(b=>b.x+b.w))-x,h:Math.max(...boxes.map(b=>b.y+b.h))-y});
  },
  focusElement(el,identity="") {const a=el.getBoundingClientRect(),b=world.getBoundingClientRect();this.focus({x:(a.left-b.left)/view.z,y:(a.top-b.top)/view.z,w:a.width/view.z,h:a.height/view.z},identity);},
  output(receipt) {
    if(typeof HarnessArguments!=="undefined")HarnessArguments.record(receipt);
    if(!receipt?.programKey)return;
    const entry=this.board[receipt.programKey];
    if(receipt.programCid&&entry?.programCid&&receipt.programCid!==entry.programCid)return;
    if (this.drafts.has(receipt.program)) return;
    for (const n of G.nodes.filter(n=>n._program===receipt.program)) {
      const out=receipt.outputs?.[n._localId||n.id];
      n.el.classList.remove("running","ok","err");
      if (out !== undefined) { n._lastOut=out;n.el.classList.add("ok");setResult(n,out); }
    }
    for (const v of receipt.violations || []) BOARD.violations.set(cableKey(receipt.program+"::"+v.fromNode,v.fromPort,receipt.program+"::"+v.toNode,v.toPort),v.detail||v.rule);
    this.applying=true;
    try { resolveTopLevelOverlaps(); } finally { this.applying=false; }
    this.baselines.set(receipt.program,JSON.stringify(this.document(receipt.program)));
    this.schedule();
  },
  async run() {
    if(this.inspectionOnly()){this.message("Inspection-only wiring specimen; execution is disabled");return;}
    if (this.running || this.dirty || !this.selected) return;
    let inputs;
    try{inputs=typeof HarnessArguments==="undefined"?{}:HarnessArguments.inputs(this.selected);}catch(e){this.message(e.message);return;}
    this.running=true;$("#runBtn").disabled=true;
    const name=this.selected;this.message("Running "+name);
    try {
      const response=await fetch("/api/lcnc/run",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({program:name,inputs})});
      const result=await response.json();this.output(result);
      this.message(name+": "+(result.ok?"completed":result.error||"failed"));
    } catch(e) { this.message("Run failed: "+e.message); }
    finally { this.running=false;$("#runBtn").disabled=this.dirty||this.inspectionOnly();if(typeof HarnessArguments!=="undefined"&&$("#argumentInspector").open)HarnessArguments.validate(); }
  },
  async cancelRun() {
    const run=Object.values(this.board).find(v=>v?.programKey==="lcnc/program/"+this.selected&&["validating","running"].includes(v.status));
    if(!run)return;
    const response=await fetch("/api/lcnc/run/cancel",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({runId:run.runId})});
    this.message(response.ok?"Cancellation requested":"Run is no longer active");
  },
  async publish() {
    const name=$("#panelName").value.trim();
    if(!/^[a-z0-9][a-z0-9._-]*$/.test(name)){this.message("Use a lowercase program name");return;}
    try {
      const response=await fetch("/api/panels/"+encodeURIComponent(name),{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(this.document())});
      const result=await response.json();if(!response.ok||result.verdict!=="ok")throw Error(result.error||result.detail||response.status);
      const entryResponse=await fetch("/api/panels/"+encodeURIComponent(name)+"?entry=1");
      if(!entryResponse.ok)throw Error("Published entry unavailable");
      this.board["lcnc/program/"+name]=await entryResponse.json();
      this.drafts.delete(this.selected);this.dirty=false;this.mount(name);this.select(name,false);
      this.message("Published "+name+(result.violations?.length?" with refused cables":""));
    }catch(e){this.message("Publish failed: "+e.message);}
  },
  async shake(options) {
    if(this.shaking||!this.selected)return;
    const target=this.parentTarget();if(!target)return;
    const name=this.selected,document=this.document(),snapshot=JSON.stringify(document),revision=this.parentRevision,parentId=target.handle.nodeId;
    this.shaking=true;$("#shakeBtn").disabled=true;this.message("Checking connections in "+name);
    try {
      const response=await fetch("/api/lcnc/treeshake",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({program:document,options:{...options,parentId}})});
      const result=await response.json();if(!response.ok||!result.ok)throw Error(result.detail||result.error||response.status);
      if(this.selected!==name||this.parentRevision!==revision||JSON.stringify(this.document())!==snapshot){this.message("Connections or selected parent changed during the check; run Shake again");return;}
      if(parentId!=null&&result.parentId!==parentId)throw Error("Server did not confirm the selected parent; use an updated server");
      applyServerTreeShake(result,!!options?.optional,name);
    }catch(e){this.message("Connections refused: "+e.message);}
    finally{this.shaking=false;$("#shakeBtn").disabled=false;}
  },
  showConnections(program,result,summary) {
    this.connectionReport={program,result};
    const host=$("#connections");host.hidden=false;host.replaceChildren(this.el("h3","","Connections"),this.el("p","",summary));
    const verdicts=[...(result.verdicts||[])].sort((a,b)=>Number(["optional","binding","ok"].includes(a.status))-Number(["optional","binding","ok"].includes(b.status)));
    for(const verdict of verdicts.slice(0,100)){
      const node=G.nodes.find(n=>n.id===verdict.nodeId);if(!node)continue;
      const row=this.el("div","connection-row "+verdict.status);
      const focus=this.el("button","connection-focus",(node._localId||node.id)+" / "+verdict.port);
      focus.title="Focus "+node.type+" "+verdict.port;
      focus.addEventListener("click",()=>this.focusNode(node));
      row.append(focus,this.el("span","",verdict.label));
      if(verdict.dir==="in"&&!["ok","binding"].includes(verdict.status)){
        const mate=this.el("button","connection-mate","+");mate.title="Choose a source for "+verdict.port;
        mate.setAttribute("aria-label","Choose source for "+(node._localId||node.id)+" "+verdict.port);
        mate.addEventListener("click",()=>{
          this.focusNode(node);
          const box=viewport.getBoundingClientRect();
          showMateMenu(box.left+20,box.top+20,node,verdict.port,node.x-240,node.y,node._parentScope,"in");
        });
        row.append(mate);
      }
      host.append(row);
    }
    if(verdicts.length>100)host.append(this.el("p","",(verdicts.length-100)+" additional port results"));
  },
  async accept(event) {
    if (!event.key || Number(event.seq)<=this.seq) return;
    if(event.epoch!==this.epoch||Number(event.seq)!==this.seq+1){this.reconnect("Revision gap");return;}
    this.seq=Number(event.seq);
    if(event.deleted)delete this.board[event.key];else this.board[event.key]=event.value;
    if(event.actor)this.actors.set(event.key,event.actor);
    this.events.unshift(event);this.events.length=Math.min(this.events.length,100);
    const prefix=event.key.split("/")[0];this.flash(prefix);this.flash(event.key);
    if(event.key==="lcnc/vocabulary") { await syncContracts(event.value);buildPalette(); }
    if(event.key.startsWith("lcnc/program/")){
      const name=event.key.slice(13);
      if(this.drafts.has(name))this.message("Board updated while "+name+" has unpublished changes");
      else if(event.deleted)this.unmount(name);
      else this.mount(name);
    }
    if(event.key.startsWith("lcnc/run/")&&!event.deleted){this.output(event.value);this.flash(event.value.programKey);this.flash("receipts");}
    this.schedule();
  },
  // Delta 2026-09-05 (fan-out): each key keeps its own expiry (now + 1000 ms) so a burst of N kanban
  // commits lights N rows that fade on their own clocks. The old single re-armed timer cleared every
  // flash 1 s after the LAST event, so a burst was one amber border that went dark all at once.
  flash(key) { if (key) this.flashes.set(key, Date.now() + 1000); this.armFlashSweep(); },
  armFlashSweep() {
    if (this.flashSweep) return;
    this.flashSweep = setInterval(() => {
      const now = Date.now(); let expired = false;
      for (const [key, until] of this.flashes) if (until <= now) { this.flashes.delete(key); expired = true; }
      if (expired) this.schedule();
      if (!this.flashes.size) { clearInterval(this.flashSweep); this.flashSweep = null; }
    }, 250);
  },
  reconnect(reason="Reconnecting") {
    this.live=false;Landscape.refreshActivity();
    this.stream?.close();this.connectionGeneration++;
    $("#connection").textContent=reason;$("#connection").classList.remove("live");
    clearTimeout(this.reconnectTimer);this.reconnectTimer=setTimeout(()=>this.connect(),500);
  },
  async connect() {
    this.live=false;
    this.stream?.close();clearTimeout(this.reconnectTimer);
    const generation=++this.connectionGeneration,initial=!this.ready;
    const bookmark=initial?LandscapeNavigation.decode(location.hash):null;
    let buffer=[],hydrating=true,chain=Promise.resolve();
    $("#connection").textContent="Syncing";
    try {
      const response=await fetch("/blackboard/board");if(!response.ok)throw Error("Snapshot "+response.status);
      const snapshot=await response.json();if(generation!==this.connectionGeneration)return;
      this.board=snapshot.board||Object.create(null);this.seq=Number(snapshot.revision);this.epoch=snapshot.epoch;
      if(!Number.isSafeInteger(this.seq)||!this.epoch)throw Error("Revision-linked snapshot required");
      this.actors=new Map(Object.entries(snapshot.provenance||{}).map(([k,v])=>[k,v.actor]));
      const stream=this.stream=new EventSource("/blackboard/facts?since="+this.seq+"&epoch="+encodeURIComponent(this.epoch));
      stream.addEventListener("reset",()=>{if(generation===this.connectionGeneration)this.reconnect("Refreshing snapshot");});
      stream.onmessage=e=>{
        if(generation!==this.connectionGeneration)return;
        try {
          const event=JSON.parse(e.data);
          if(hydrating){buffer.push(event);if(buffer.length>2048)this.reconnect("Snapshot backlog");}
          else chain=chain.then(()=>generation===this.connectionGeneration?this.accept(event):null).catch(()=>this.reconnect());
        }catch(_){this.reconnect("Invalid event");}
      };
      stream.onerror=()=>{if(generation===this.connectionGeneration)this.reconnect();};
      await syncContracts(this.board["lcnc/vocabulary"]);await syncLanes();
      if(generation!==this.connectionGeneration)return;
      this.ready=true;
      for(const name of this.mounts.keys())if(!this.board["lcnc/program/"+name]&&!this.drafts.has(name))this.unmount(name);
      const requested=new URLSearchParams(location.search).get("load");
      const names=this.programs().map(k=>k.slice(13));
      const name=[this.selected,requested,requested?"preset-"+requested:null,"preset-curator",names[0]].find(n=>names.includes(n));
      const first=this.board["lcnc/program/preset-curator"]?"preset-curator":names[0];
      if(first)this.mount(first);
      for(const n of names)if(n!==first)this.mount(n);
      if(name)this.select(name,initial&&!bookmark,!initial);
      for(const event of buffer) {
        if(generation!==this.connectionGeneration)return;
        await this.accept(event);
      }
      buffer=[];hydrating=false;this.render();buildPalette();
      await Landscape.refresh();
      if(generation!==this.connectionGeneration)return;
      if(bookmark){Object.assign(view,bookmark.camera);this.focusKey=bookmark.focus;applyView();redraw();}
      else if(initial&&!requested)this.fit(true);
      $("#connection").textContent="Live";$("#connection").classList.add("live");
      this.live=true;Landscape.refreshActivity();
    }catch(e){if(generation===this.connectionGeneration){this.message("Blackboard unavailable: "+e.message);this.reconnect();}}
  },
};

AUTOSAVE=false;
$("#argumentsBtn").addEventListener("click",()=>HarnessArguments.open());
$("#argumentAdd").addEventListener("click",()=>HarnessArguments.add());
$("#argumentRun").addEventListener("click",()=>{if(HarnessArguments.validate())Harness.run();});
$("#programSelect").addEventListener("change",e=>Harness.select(e.target.value));
$("#boardHome").addEventListener("click",()=>Harness.fit(true));
$("#objectsBtn").addEventListener("click",()=>Harness.focus(Landscape.objectBox,LandscapeNavigation.object("")));
$("#viewBack").addEventListener("click",()=>Harness.previousView());
$("#viewUp").addEventListener("click",()=>Harness.enclosingView());
$("#cancelRun").addEventListener("click",()=>Harness.cancelRun().catch(e=>Harness.message(e.message)));
$("#parentHandle").addEventListener("pointerdown",e=>Harness.dragParent(e));
$("#parentHandle").addEventListener("click",()=>{if(!Harness.dragMoved)Harness.fit(false);});
$("#sheetsBtn").addEventListener("click",()=>Harness.openSheets([]));
$("#sheetRawBtn").addEventListener("click",()=>Harness.rawSheets(!$("#factInspector").classList.contains("raw")));
$("#factInspector").addEventListener("close",()=>{
  Harness.inspectionController?.abort(); Harness.sheetTicket = (Harness.sheetTicket || 0) + 1;
});
$("#terrainLayer").addEventListener("change",e=>{Landscape.terrain?.setLayer(Number(e.target.value));Landscape.schedule();});
let parentDragGesture=false;
viewport.addEventListener("pointerdown",e=>{
  parentDragGesture=false;
  if(e.metaKey&&e.button===0&&Harness.parentTarget()){
    parentDragGesture=true;e.stopImmediatePropagation();Harness.dragParent(e);return;
  }
  const element=e.target.closest(".node"),n=element&&G.nodes.find(n=>n.el===element);
  if(n?._program&&n._program!==Harness.selected){e.preventDefault();e.stopImmediatePropagation();}
},true);
viewport.addEventListener("click",e=>{
  if(parentDragGesture&&e.detail>0){e.preventDefault();e.stopImmediatePropagation();}
},true);
viewport.addEventListener("dblclick",e=>{
  if(parentDragGesture){e.preventDefault();e.stopImmediatePropagation();return;}
  if(e.target.closest(".node,button,input,textarea,select"))return;
  const hit=Landscape.hit(e);if(!hit)return;
  e.preventDefault();e.stopImmediatePropagation();
  if(hit.object)Harness.focus(hit.object.rect,LandscapeNavigation.object(hit.object.id||""));
  else if(hit.node)Harness.focusNode(hit.node);
  else if(hit.key?.startsWith("lcnc/program/"))Harness.select(hit.key.slice(13));
  else Harness.focus(hit.box);
},true);
let landscapePress=null;
viewport.addEventListener("pointerdown",e=>{landscapePress={x:e.clientX,y:e.clientY};},true);
viewport.addEventListener("click",e=>{
  if(e.target.closest(".node,button,input,textarea,select")||!landscapePress||Math.hypot(e.clientX-landscapePress.x,e.clientY-landscapePress.y)>4)return;
  const hit=Landscape.hit(e);if(hit?.object?.leaf)Landscape.inspect(hit.object.id);
});
addEventListener("resize",()=>{applyView();redraw();Landscape.schedule();});
addEventListener("pagehide",()=>{Harness.stream?.close();clearTimeout(Harness.reconnectTimer);});
Harness.connect();
