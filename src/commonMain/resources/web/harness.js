"use strict";

// View state only. Documents, vocabulary, run receipts and provenance come from the board.
const Harness = {
  board: Object.create(null), seq: 0, selected: null, applying: false, dirty: false,
  events: [], drafts: new Map(), actors: new Map(), positions: new Map(), flashes: new Map(), // Delta 2026-09-05 (fan-out): Map<key, expiresAt ms>; one Set with one timer collapsed a burst into one border
  mounts: new Map(), baselines: new Map(), nextY: 0, nextX:0, rowHeight:0,
  ready: false, running: false, frame: 0, activeBounds: {w:1600,h:900},
  el(tag, cls, value) {
    const el = document.createElement(tag);
    if (cls) el.className = cls;
    if (value != null) el.textContent = String(value);
    return el;
  },
  message(value) { $("#status").textContent = value; },
  programs() {
    return Object.keys(this.board).filter(k => k.startsWith("lcnc/program/") && this.board[k]?.document).sort();
  },
  changed() {
    if (this.applying || !this.ready) return;
    this.dirty = JSON.stringify(this.document()) !== this.baselines.get(this.selected);
    if(this.dirty)this.drafts.set(this.selected,this.document());else this.drafts.delete(this.selected);
    $("#runBtn").disabled = this.dirty || this.running;
    if(this.dirty)this.message("Unpublished changes in " + this.selected);
    this.schedule();
  },
  document(name=this.selected) {
    const entry = this.board["lcnc/program/" + name];
    const origin=this.mounts.get(name)||{x:0,y:0};
    const owned=G.nodes.filter(n=>n._program===name), ids=new Map(owned.map(n=>[n.id,n._localId||n.id]));
    const original=new Map();
    const walk=nodes=>{for(const n of nodes||[]){original.set(n.id,n);walk(n.children);}};walk(entry?.document?.nodes);
    const node=n=>({...original.get(ids.get(n.id)),...nodeDoc(n),id:ids.get(n.id),x:n.x-(n._parentScope?0:origin.x),y:n.y-(n._parentScope?0:origin.y),children:(n.children||[]).map(node)});
    return {...entry?.document,nodes:owned.filter(n=>!n._parentScope).map(node),
      wires:G.wires.filter(w=>ids.has(w.from[0])&&ids.has(w.to[0])).map(w=>({from:[ids.get(w.from[0]),w.from[1]],to:[ids.get(w.to[0]),w.to[1]]})),seq:Math.max(entry?.document?.seq||1,...[...ids.values()].map(id=>/^n\d+$/.test(id)?Number(id.slice(1))+1:1))};
  },
  select(name, focus = true) {
    const entry = this.board["lcnc/program/" + name];
    if (!entry?.document) { this.message("Program is not on the board: " + name); return false; }
    if (this.selected && this.dirty) this.drafts.set(this.selected, this.document());
    this.selected = name;
    if(!this.mounts.has(name))this.mount(name);
    UNDO.length = 0; REDO.length = 0; lastDoc = JSON.stringify(this.document()); histButtons();
    $("#panelName").value = name.replace(/^preset-/, "");
    $("#programSelect").value = name;
    this.dirty = this.drafts.has(name);
    $("#runBtn").disabled = this.dirty || this.running;
    this.render();
    if (focus) this.fit(false);
    this.message(name + (this.dirty ? " has unpublished changes" : " on the blackboard"));
    history.replaceState(null, "", "/harness?load=" + encodeURIComponent(name));
    return true;
  },
  mount(name,document) {
    const entry=this.board["lcnc/program/"+name];if(!entry?.document)return;
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
      for(const n of G.nodes.filter(n=>n._program===name&&n._childHost&&!n._parentScope))layoutRing(n);
      resolveTopLevelOverlaps();
      const allIds=new Set(G.nodes.filter(n=>n._program===name).map(n=>n.id));
      for(const wire of fromConfix(doc).wires)if(allIds.has(id(wire.from[0]))&&allIds.has(id(wire.to[0])))G.wires.push({from:[id(wire.from[0]),wire.from[1]],to:[id(wire.to[0]),wire.to[1]]});
      for(const c of entry.cables||[])BOARD.cables.set(cableKey(id(c.from[0]),c.from[1],id(c.to[0]),c.to[1]),c.type);
      for(const v of entry.violations||[])BOARD.violations.set(cableKey(id(v.fromNode),v.fromPort,id(v.toNode),v.toPort),v.detail||v.rule);
      Object.assign(anchor,this.bounds(name));
      if(fresh){
        if(this.mounts.size===1){this.nextY=Math.max(3900,anchor.y+anchor.h+250);this.nextX=0;}
        else {this.nextX=anchor.x+anchor.w+220;this.rowHeight=Math.max(this.rowHeight,anchor.h);if(this.nextX>6500){this.nextY+=this.rowHeight+250;this.nextX=0;this.rowHeight=0;}}
      }
      if(!document&&!this.drafts.has(name))this.baselines.set(name,JSON.stringify(this.document(name)));
    }finally{this.applying=previous;}
  },
  replaceSelected(doc) { this.mount(this.selected,doc);this.changed();this.render(); },
  schedule() {
    if (this.frame) return;
    this.frame = requestAnimationFrame(() => { this.frame = 0; this.render(); });
  },
  bounds(name=this.selected) {
    const origin=this.mounts.get(name)||{x:0,y:0};
    let w = 1550, h = 720;
    for (const n of G.nodes.filter(n => !n._parentScope&&n._program===name)) {
      w = Math.max(w, n.x-origin.x + (n.el?.offsetWidth || 190) + 50);
      h = Math.max(h, n.y-origin.y + (n.el?.offsetHeight || 100) + 50);
    }
    return {w,h};
  },
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
    $("#territories").replaceChildren();
    this.positions.clear();
    const {w,h} = this.activeBounds = this.bounds();
    for(const [name,anchor] of this.mounts){
      const box=this.bounds(name);Object.assign(anchor,box);
      const territory=this.territory("lcnc/program/"+name,name,this.drafts.has(name)?"Unpublished":"lcnc/program/"+name,anchor.x-30,anchor.y-80,box.w+60,box.h+110,"#64ceca");
      territory.classList.add("active-program");if(name===this.selected)territory.classList.add("selected");
      const head=territory.querySelector("header");head.addEventListener("click",()=>this.select(name));
      const run=this.el("button","run","▶ Run");run.disabled=this.running||this.drafts.has(name);run.setAttribute("aria-label","Run "+name);
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
    const first=this.mounts.values().next().value||{w:1550};
    const side = first.w+100;
    const runs = Object.entries(this.board).filter(([k,v]) => k.startsWith("lcnc/run/") && v.program === this.selected)
      .sort((a,b) => (b[1].startedAtMs || 0) - (a[1].startedAtMs || 0));
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
      if (items.length > 24) {
        const field = this.el("div", "fact-field");
        for (const [key,value] of items) {
          const cell = this.el("button", "fact-cell");
          cell.title = key + "\n" + this.summary(value);
          cell.setAttribute("aria-label", key);
          if (this.flashes.has(key)) cell.classList.add("flash");
          cell.addEventListener("click", () => this.inspect(key));
          field.append(cell);
        }
        territory.append(field);
      } else for (const [key,value] of items) {
        const row = this.el("button", "fact-row");
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
    this.renderEvents(); this.channels(runs[0]?.[1]);
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
    // derivation chains inside the narsese territory: object → subject of the next expression
    const w=world.getBoundingClientRect();
    for (const [from,to] of this.narseseChains || []) {
      const a=from.getBoundingClientRect(), b=to.getBoundingClientRect();
      const ax=(a.right-w.left)/view.z, ay=(a.top+a.height/2-w.top)/view.z, bx=(b.right-w.left)/view.z, by=(b.top+b.height/2-w.top)/view.z;
      const path=document.createElementNS(svg.namespaceURI,"path");path.setAttribute("class","chain");
      path.setAttribute("d",`M ${ax} ${ay} C ${ax+70} ${ay}, ${bx+70} ${by}, ${bx} ${by}`);svg.append(path);
    }
    if(!receipt)return;
    const a=this.positions.get(receipt.programKey),b=this.positions.get("receipts");if(!a||!b)return;
    const path=document.createElementNS(svg.namespaceURI,"path");path.setAttribute("d",bez({x:a.x+a.w,y:a.y+160},{x:b.x,y:b.y+160}));svg.append(path);
    const label=document.createElementNS(svg.namespaceURI,"text");label.setAttribute("x",String(a.x+a.w+8));label.setAttribute("y",String(a.y+147));label.textContent=receipt.status;svg.append(label);
  },
  summary(value) {
    if (typeof value === "string") return value;
    if (!value || typeof value !== "object") return String(value);
    return Object.entries(value).slice(0,4).map(([k,v])=>k+": "+(typeof v === "object" ? JSON.stringify(v) : v)).join(" / ").slice(0,250);
  },
  inspect(key) {
    $("#factKey").textContent=key;$("#factActor").textContent=this.actors.get(key)||this.board[key]?.actor||"";
    $("#factValue").textContent=JSON.stringify(this.board[key],null,2);$("#factInspector").showModal();
  },
  renderEvents() {
    const host=$("#events");host.replaceChildren();
    for (const event of this.events.slice(0,50)) {
      const button=this.el("button","event-row");
      button.append(this.el("b","",event.key),this.el("small","",(event.actor||"unknown actor")+" / #"+event.seq+" / "+new Date(event.atMs).toLocaleTimeString()));
      button.addEventListener("click",()=>this.inspect(event.key));host.append(button);
    }
  },
  focus(box) {
    if(typeof killMomentum==="function")killMomentum(); // a focus is a hard cut, never a glide target
    const r=viewport.getBoundingClientRect(),pad=30;
    view.z=Math.min(4000,Math.max(.01,Math.min((r.width-pad*2)/box.w,(r.height-pad*2)/box.h)));
    view.x=(r.width-box.w*view.z)/2-box.x*view.z;view.y=(r.height-box.h*view.z)/2-box.y*view.z;applyView();redraw();
  },
  /* The panel that owns the most of the viewport right now. fd and shake act on
     what the operator is looking at, not on a selection that may sit off-screen
     from an earlier click. Screen = world * view.z + view (the wheel math's
     frame). No territory on screen → the selection stands. */
  prominent() {
    const r=viewport.getBoundingClientRect();let best=this.selected,bestArea=0;
    for(const [name,a] of this.mounts){
      const x0=Math.max(0,a.x*view.z+view.x),y0=Math.max(0,a.y*view.z+view.y);
      const x1=Math.min(r.width,(a.x+a.w)*view.z+view.x),y1=Math.min(r.height,(a.y+a.h)*view.z+view.y);
      const area=Math.max(0,x1-x0)*Math.max(0,y1-y0);
      if(area>bestArea){best=name;bestArea=area;}
    }
    return best;
  },
  fit(all) {
    if (!all) { const anchor=this.mounts.get(this.selected)||{x:0,y:0};this.focus({x:anchor.x-40,y:anchor.y-90,w:this.activeBounds.w+120,h:this.activeBounds.h+130}); return; }
    const boxes=[...this.positions.values()];
    const x=Math.min(...boxes.map(b=>b.x)),y=Math.min(...boxes.map(b=>b.y));
    this.focus({x,y,w:Math.max(...boxes.map(b=>b.x+b.w))-x,h:Math.max(...boxes.map(b=>b.y+b.h))-y});
  },
  focusElement(el) {const a=el.getBoundingClientRect(),b=world.getBoundingClientRect();this.focus({x:(a.left-b.left)/view.z,y:(a.top-b.top)/view.z,w:a.width/view.z,h:a.height/view.z});},
  output(receipt) {
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
    if (this.running || this.dirty || !this.selected) return;
    this.running=true;$("#runBtn").disabled=true;
    const name=this.selected;this.message("Running "+name);
    try {
      const response=await fetch("/api/lcnc/run",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({program:name})});
      const result=await response.json();this.output(result);
      this.message(name+": "+(result.ok?"completed":result.error||"failed"));
    } catch(e) { this.message("Run failed: "+e.message); }
    finally { this.running=false;$("#runBtn").disabled=this.dirty; }
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
    try {
      const response=await fetch("/api/lcnc/treeshake",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({program:this.document(),options:options||{}})});
      const result=await response.json();if(!response.ok||!result.ok)throw Error(result.error||response.status);
      this.replaceSelected(typeof result.program==="string"?JSON.parse(result.program):result.program);
      this.message((result.made||[]).length+" cables connected in "+this.selected);
    }catch(e){this.message("Connections refused: "+e.message);}
  },
  async accept(event) {
    if (!event.key || Number(event.seq)<=this.seq) return;
    this.seq=Number(event.seq);this.board[event.key]=event.value;
    if(event.actor)this.actors.set(event.key,event.actor);
    this.events.unshift(event);this.events.length=Math.min(this.events.length,100);
    const prefix=event.key.split("/")[0];this.flash(prefix);this.flash(event.key);
    if(event.key==="lcnc/vocabulary") { await syncContracts(event.value);buildPalette(); }
    if(event.key.startsWith("lcnc/program/")){
      const name=event.key.slice(13);
      if(this.drafts.has(name))this.message("Board updated while "+name+" has unpublished changes");
      else this.mount(name);
    }
    if(event.key.startsWith("lcnc/run/"))this.output(event.value);
    if(event.key.startsWith("lcnc/run/")){this.flash(event.value.programKey);this.flash("receipts");}
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
  connect() {
    let buffer=[],hydrating=true,chain=Promise.resolve();
    const stream=new EventSource("/blackboard/facts?since="+this.seq);
    stream.onmessage=e=>{try{const d=JSON.parse(e.data);if(hydrating)buffer.push(d);else chain=chain.then(()=>this.accept(d));}catch(_){} };
    stream.onopen=async()=>{
      hydrating=true;$("#connection").textContent="Syncing";
      try {
        const response=await fetch("/blackboard/board");if(!response.ok)throw Error("Snapshot "+response.status);
        const snapshot=await response.json();this.board=snapshot.board||Object.create(null);this.seq=Number(snapshot.seq)||0;
        await syncContracts(this.board["lcnc/vocabulary"]);await syncLanes();
        this.ready=true;
        const requested=new URLSearchParams(location.search).get("load");
        const name=this.selected||[requested,requested?"preset-"+requested:null,"preset-curator",this.programs()[0]?.slice(13)].find(n=>this.board["lcnc/program/"+n]);
        const first=this.board["lcnc/program/preset-curator"]?"preset-curator":this.programs()[0]?.slice(13);
        if(first)this.mount(first);
        for(const key of this.programs())if(key.slice(13)!==first)this.mount(key.slice(13));
        this.select(name,!this.selected);
        for(const d of buffer.sort((a,b)=>a.seq-b.seq)) {
          if(d.actor)this.actors.set(d.key,d.actor);
          await this.accept(d);
        }
        buffer=[];hydrating=false;this.render();buildPalette();
        Landscape.refresh().then(()=>{if(!requested)this.fit(true);});
        $("#connection").textContent="Live";$("#connection").classList.add("live");
      }catch(e){this.message("Blackboard unavailable: "+e.message);stream.close();setTimeout(()=>this.connect(),2000);}
    };
    stream.onerror=()=>{stream.close();$("#connection").textContent="Reconnecting";$("#connection").classList.remove("live");setTimeout(()=>this.connect(),2000);};
    addEventListener("pagehide",()=>stream.close(),{once:true});
  },
};

AUTOSAVE=false;
$("#programSelect").addEventListener("change",e=>Harness.select(e.target.value));
$("#boardHome").addEventListener("click",()=>Harness.fit(true));
$("#objectsBtn").addEventListener("click",()=>Harness.focus(Landscape.objectBox));
$("#terrainLayer").addEventListener("change",e=>{Landscape.terrain?.setLayer(Number(e.target.value));Landscape.schedule();});
viewport.addEventListener("pointerdown",e=>{
  const element=e.target.closest(".node"),n=element&&G.nodes.find(n=>n.el===element);
  if(n?._program&&n._program!==Harness.selected)Harness.select(n._program,false);
},true);
viewport.addEventListener("dblclick",e=>{
  if(e.target.closest(".node,button,input,textarea,select"))return;
  const hit=Landscape.hit(e);if(!hit)return;
  e.preventDefault();e.stopImmediatePropagation();
  if(hit.object)Harness.focus(hit.object.rect);
  else if(hit.node)Harness.focusElement(hit.node.el);
  else if(hit.key?.startsWith("lcnc/program/"))Harness.select(hit.key.slice(13));
  else Harness.focus(hit.box);
},true);
let landscapePress=null;
viewport.addEventListener("pointerdown",e=>{landscapePress={x:e.clientX,y:e.clientY};},true);
viewport.addEventListener("click",e=>{
  if(e.target.closest(".node,button,input,textarea,select")||!landscapePress||Math.hypot(e.clientX-landscapePress.x,e.clientY-landscapePress.y)>4)return;
  const hit=Landscape.hit(e);if(hit?.object?.leaf)Landscape.inspect(hit.object.id);
});
addEventListener("resize",()=>Harness.fit(false));
Harness.connect();
