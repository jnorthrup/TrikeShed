/* Dedicated operator surfaces over the shared mux API. */
(() => {
  "use strict";
  const C=window.MuxCore,$=selector=>document.querySelector(selector);
  const page=location.pathname.includes("keymux")?"keys":location.pathname.includes("sessions")?"sessions":location.pathname.includes("stats")?"stats":"models";
  const titles={keys:["KeyMux","CREDENTIALS & QUOTA"],models:["ModelMux","MODEL ROUTING"],sessions:["Sessions","CONVERSATIONS & BRANCHES"],stats:["Live statistics","REQUESTS & QUOTA"]};
  const state={roster:null,models:null,endpoints:null,standings:null,calls:null,sessions:null,detail:null,
    selected:Number(new URLSearchParams(location.search).get("id"))||null,tab:"conversation",modelsSelected:new Set(),live:true,
    busy:false,pending:false,lastPoll:0,lastCatalog:0,errors:new Map(),detailShell:null,quotaAt:null};
  const htmlCache=new WeakMap();
  const roster=()=>state.catalogRoster??state.roster;
  const esc=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  const icon=name=>'<i data-lucide="'+name+'"></i>';
  const btn=(action,label,symbol,extra="")=>'<button data-action="'+action+'" title="'+esc(label)+'" aria-label="'+esc(label)+'" '+extra+'>'+icon(symbol)+'</button>';
  function html(el,value){if(typeof el==="string")el=$(el);if(!el||htmlCache.get(el)===value)return;htmlCache.set(el,value);el.innerHTML=value;}
  function icons(){window.MuxIcons?.();}
  function badge(status,label=status){return '<span class="badge '+esc(status)+'">'+esc(label)+'</span>';}
  function empty(text,symbol="network"){return '<div class="empty">'+icon(symbol)+esc(text)+'</div>';}
  function count(v){return v==null?"--":new Intl.NumberFormat(undefined,{notation:Math.abs(v)>=10000?"compact":"standard",maximumFractionDigits:1}).format(v);}
  function duration(ms){if(ms==null)return "--";if(ms<1000)return Math.round(ms)+" ms";if(ms<60000)return (ms/1000).toFixed(1)+" s";if(ms<3600000)return Math.ceil(ms/60000)+" min";return (ms/3600000).toFixed(1)+" h";}
  function time(at){return at?new Date(at).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit",second:"2-digit"}):"--";}
  function date(at){return at?new Date(at).toLocaleString([],{month:"short",day:"numeric",hour:"2-digit",minute:"2-digit"}):"--";}
  function toast(message){$("#toast").textContent=message;$("#toast").hidden=false;clearTimeout(toast.timer);toast.timer=setTimeout(()=>$("#toast").hidden=true,4000);}
  async function request(path,body,method=body===undefined?"GET":"POST"){
    const response=await fetch(path,{method,headers:body===undefined?{}:{"Content-Type":"application/json"},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000),cache:"no-store"});
    let data;try{data=await response.json();}catch{throw Error("Invalid response ("+response.status+")");}
    if(!response.ok)throw Error(data.error||data.detail||data.verdict||"HTTP "+response.status);
    return data;
  }
  function showErrors(){
    const entries=[...state.errors.entries()];
    $("#errors").hidden=!entries.length;
    $("#errors").textContent=entries.map(([name,error])=>name+": "+error).join(" | ");
    const label=!state.live?"Paused":entries.length?"Connection degraded":state.lastPoll?"Live · "+time(state.lastPoll):"Connecting";
    html("#connection",'<span class="dot '+(entries.length?"offline":state.live&&state.lastPoll?"live":"")+'"></span>'+esc(label));
  }
  async function poll(force=false){
    if(state.busy){state.pending=state.pending||force;return;}
    if(!force&&(!state.live||document.hidden))return;
    state.busy=true;
    const catalog=force||Date.now()-state.lastCatalog>20000;
    const sources=[
      ["Activity","/api/mux/activity",r=>state.calls=r.calls||[]],
      ["Sessions","/api/mux/sessions",r=>state.sessions=r.sessions||[]],
      ["Quota","/api/mux/standings",r=>{state.standings=r.standings||[];state.quotaAt=r.atMs;}],
    ];
    if(catalog)sources.push(["Credentials","/api/mux/keys",r=>state.roster=r.roster||[]],
      ["Models","/api/mux/catalog",r=>{state.models=r.models||[];state.catalogRoster=r.roster||null;}],
      ["Endpoints","/api/mux/endpoints",r=>state.endpoints=r.user||[]]);
    await Promise.allSettled(sources.map(async([name,path,apply])=>{
      try{apply(await request(path));state.errors.delete(name);}catch(error){state.errors.set(name,error.message);}
    }));
    if(catalog)state.lastCatalog=Date.now();
    if(page==="sessions"&&!state.selected)state.selected=state.sessions?.find(s=>!s.archived)?.id||null;
    if(page==="sessions"&&state.selected){
      const id=state.selected;
      try{const detail=await request("/api/mux/sessions?id="+id);if(state.selected===id){state.detail=detail;state.errors.delete("Session");}}
      catch(error){state.errors.set("Session",error.message);}
    }
    state.lastPoll=Date.now();state.busy=false;render();
    if(state.pending){state.pending=false;void poll(true);}
  }
  function metric(label,value,note,symbol){return '<div class="metric"><div class="metric-label">'+icon(symbol)+esc(label)+'</div><div class="metric-value">'+esc(value)+'</div><div class="metric-note">'+esc(note)+'</div></div>';}
  function metrics(){
    const calls=state.calls||[],t=C.totals(calls),active=state.sessions?.filter(s=>C.active.has(s.status)&&!s.parentId).length;
    const ready=roster()?new Set(roster().filter(r=>r.keyPresent).map(r=>r.envVar||r.name)).size:null;
    let rows;
    if(page==="keys")rows=[
      ["Credentials present",count(ready),roster()?roster().length+" roster entries":"Waiting for credentials","key-round"],
      ["Observed quota pools",count(state.standings?.length),count(state.standings?.filter(s=>s.limit>0).length)+" with known limits","network"],
      ["Exhausted pools",count(state.standings?.filter(s=>s.exhausted||!s.usable).length),"Provider quota windows","clock"],
      ["Provider tokens",state.calls?count(t.input+t.output):"--","Retained attempts, excluding local cache","chart-no-axes-combined"]];
    else rows=[
      ["Active sessions",count(active),count(state.sessions?.filter(s=>C.active.has(s.status)&&s.parentId).length)+" active branches","messages-square"],
      ["Model attempts",state.calls?count(t.attempts):"--",t.running+" in flight · "+t.failed+" failed","network"],
      ["Provider tokens",state.calls?count(t.input+t.output):"--",count(t.input)+" input · "+count(t.output)+" output","chart-no-axes-combined"],
      ["Median latency",duration(t.medianMs),t.cached+" local cache hits · retained attempts","clock"]];
    html("#metrics",rows.map(r=>metric(...r)).join(""));
  }
  function catalogStanding(row){
    const calls=(state.calls||[]).filter(c=>c.model===row.model);
    const keyIds=new Set(calls.map(c=>c.keyId).filter(Boolean));
    const providers=new Set(calls.map(c=>c.provider));
    const matches=(state.standings||[]).filter(s=>keyIds.has(s.keyId)||providers.has(s.provider)||s.provider===row.name||s.provider===row.model);
    return matches.find(s=>s.usable)||matches[0];
  }
  function quotaCell(s){
    const q=C.quota(s);if(!s)return '<span class="muted">Not observed</span>';
    if(!q.known)return '<span class="muted">Limit unknown</span><small>'+count(s.spent)+' tokens spent</small>';
    return count(s.spent)+' / '+count(s.limit)+'<span class="progress '+(q.status==="exhausted"?"bad":q.used>.8?"warn":"")+'"><span style="width:'+q.used*100+'%"></span></span>';
  }
  function renderKeys(){
    const search=$("#keySearch").value.toLowerCase(),status=$("#keyStatus").value;
    const rows=(roster()||[]).filter(r=>[r.name,r.model,r.envVar].join(" ").toLowerCase().includes(search)&&(status==="all"||(status==="ready"?r.keyPresent:!r.keyPresent)));
    html("#keyRows",rows.length?rows.map(r=>{
      const s=catalogStanding(r),q=C.quota(s);
      return '<tr><td><strong>'+esc(r.name)+'</strong><small class="mono">'+esc(r.model)+'</small></td><td>'+badge(r.keyPresent?"good":"bad",r.keyPresent?"Present":"Missing")+'<small class="mono">'+esc(r.envVar)+'</small></td><td>'+badge(s&&!s.usable?"bad":r.keyPresent?"good":"",s&&!s.usable?"Exhausted":r.keyPresent?"Available":"Unbound")+'</td><td>'+quotaCell(s)+'</td><td class="mono">'+(q.status==="refreshing"?"Refreshing":duration(q.resetMs))+'</td><td class="mono">'+count(s?.accessCount)+'</td></tr>';
    }).join(""):'<tr><td colspan="6">'+empty(state.roster?"No matching credentials":"Credential roster unavailable","key-round")+'</td></tr>');
    html("#endpointRows",state.endpoints?.length?state.endpoints.map((r,index)=>{
      const bound=(state.models||[]).some(m=>m.base===r.base&&m.model===r.model);
      return '<tr><td><strong>'+esc(r.name)+'</strong><small>'+esc(r.base)+'</small></td><td class="mono">'+esc(r.model)+'</td><td class="mono">'+esc(r.envVar)+'</td><td>'+badge(r.flags?.enabled!==false?"good":"",r.flags?.enabled!==false?"Enabled":"Disabled")+(r.flags?.preferred?' '+badge("","Preferred"):"")+'</td><td>'+badge(bound?"good":"queued",bound?"In roster":"Registry only")+'</td><td><div class="toolbar">'+btn("edit-endpoint","Edit "+r.name,"pencil",'data-index="'+index+'"')+btn("delete-endpoint","Remove "+r.name,"trash-2",'data-index="'+index+'"')+'</div></td></tr>';
    }).join(""):'<tr><td colspan="6">'+empty(state.endpoints?"No registered endpoints":"Endpoint registry unavailable","key-round")+'</td></tr>');
  }
  function renderModelRows(){
    const search=$("#modelSearch").value.toLowerCase();
    const models=state.models||[],rows=models.filter(m=>[m.name,m.model].join(" ").toLowerCase().includes(search));
    $("#modelCount").textContent=state.models?String(models.length):"";
    html("#modelRows",rows.length?rows.map(m=>{
      const present=(roster()||[]).some(r=>r.model===m.model&&r.keyPresent),selected=state.modelsSelected.has(m.model);
      const t=C.totals((state.calls||[]).filter(c=>c.model===m.model));
      return '<tr class="'+(selected?"selected":"")+'"><td class="check-cell"><input type="checkbox" data-model="'+esc(m.model)+'" aria-label="Select '+esc(m.model)+'" '+(selected?"checked":"")+'></td><td><strong class="mono" title="'+esc(m.model)+'">'+esc(m.model)+'</strong><small>'+esc(m.name)+'</small></td><td>'+badge(present?"good":"",present?"Present":"Unresolved")+'</td><td class="mono">'+count(t.attempts)+'</td><td class="mono">'+duration(t.medianMs)+'</td></tr>';
    }).join(""):'<tr><td colspan="5">'+empty(state.models?"No matching models":"Model roster unavailable")+'</td></tr>');
    const runSession=$("#runSession"),selected=runSession.value;
    html(runSession,'<option value="">New session</option>'+(state.sessions||[]).filter(s=>!s.archived&&!C.active.has(s.status)&&!s.parentId).map(s=>'<option value="'+s.id+'">'+esc(s.title)+'</option>').join(""));
    if([...runSession.options].some(o=>o.value===selected))runSession.value=selected;
    selection();
  }
  function selection(){
    const models=[...state.modelsSelected],mode=$("#runMode").value;
    html("#selectionSummary",models.length?models.map(m=>'<div class="selected-model"><span class="mono">'+esc(m)+'</span>'+btn("deselect-model","Deselect "+m,"x",'data-model="'+esc(m)+'"')+'</div>').join(""):"Automatic routing");
    const extra=mode==="synthesize"?1:0,lanes=mode==="chat"?1:models.length;
    $("#runAllocation").textContent=lanes+" lane"+(lanes!==1?"s":"")+(extra?" + synthesis":"")+" · "+count((lanes+extra)*(Number($("#maxTokens").value)||0))+" output cap";
    icons();
  }
  function attemptsTable(calls){
    if(!calls.length)return empty("No attempts in this view","chart-no-axes-combined");
    return '<div class="table-wrap"><table><thead><tr><th>Time / session</th><th>Model / key</th><th>Status</th><th>Input</th><th>Output</th><th>Latency</th><th>Cache</th></tr></thead><tbody>'+[...calls].reverse().map(c=>'<tr><td><strong class="mono">'+time(c.startedAt)+'</strong><small>'+(c.conversationId?'<a href="/mux/sessions?id='+c.conversationId+'">Session '+c.conversationId+'</a>':esc(c.assessmentId||"External call"))+'</small></td><td><strong class="mono" title="'+esc(c.model)+'">'+esc(c.model)+'</strong><small class="mono">'+esc(c.keyId||c.provider)+'</small></td><td>'+badge(c.status)+'<small>'+esc(c.httpStatus?"HTTP "+c.httpStatus:c.error||"")+'</small></td><td class="mono">'+count(c.inputTokens)+'</td><td class="mono">'+count(c.outputTokens)+'</td><td class="mono">'+duration((c.endedAt??Date.now())-c.startedAt)+'</td><td>'+badge(c.cachedHit?"good":"",c.cachedHit?"Local hit":"Provider")+(c.cacheReadTokens?'<small>'+count(c.cacheReadTokens)+' read tokens</small>':"")+'</td></tr>').join("")+'</tbody></table></div>';
  }
  function renderRecent(){
    const sessions=state.sessions||[];
    const active=sessions.filter(s=>C.active.has(s.status)).length;
    $("#activeCount").textContent=active||"";
    html("#recentSessions",sessions.filter(s=>!s.archived&&!s.parentId).slice(0,7).map(s=>'<a class="recent-session" href="/mux/sessions?id='+s.id+'"><span class="dot '+esc(s.status)+'"></span><span>'+esc(s.title)+'</span></a>').join("")||'<div class="muted" style="padding:10px;font-size:11px">No sessions yet</div>');
  }
  function sessionCalls(){return C.uniqueCalls([...(state.detail?.calls||[]),...(state.calls||[]).filter(c=>c.conversationId===state.selected)]);}
  function renderSessions(){
    const rows=C.filterSessions(state.sessions||[],$("#sessionSearch").value,$("#sessionStatus").value);
    html("#sessionRows",rows.map(s=>'<button class="session-row" data-action="select-session" data-id="'+s.id+'" aria-current="'+(s.id===state.selected)+'"><strong>'+esc(s.title)+'</strong><span class="meta"><span class="dot '+esc(s.status)+'"></span>'+esc(s.status)+'<span>'+date(s.updatedAt)+'</span></span></button>').join("")||empty(state.sessions?"No matching sessions":"Sessions unavailable","messages-square"));
    const s=state.detail;
    if(!s||s.id!==state.selected){html("#sessionDetail",empty(state.selected?"Loading session":"No session selected","messages-square"));state.detailShell=null;return;}
    if(state.detailShell!==s.id){
      html("#sessionDetail",'<div class="detail-heading"><h2 id="detailTitle"></h2><div class="toolbar">'+btn("rename","Rename session","pencil")+btn("fork","Fork session","git-branch")+btn("export-session","Export session","download")+btn("archive","Archive session","archive")+'</div></div><div id="detailMeta" class="detail-meta"></div><div class="session-tabs" role="tablist" aria-label="Session views"><button role="tab" data-action="session-tab" data-tab="conversation">Conversation</button><button role="tab" data-action="session-tab" data-tab="branches">Branches</button><button role="tab" data-action="session-tab" data-tab="receipts">Receipts</button></div><div id="sessionPanel" role="tabpanel"></div><form id="followupForm" class="followup"><textarea id="followupPrompt" aria-label="Continue session" placeholder="Continue this session..." maxlength="32000" required></textarea><div class="toolbar"><select id="followupModel" aria-label="Reply model"></select><div class="toolbar">'+btn("cancel","Stop run","square",'class="danger"')+'<button type="submit" class="primary" aria-label="Send message" title="Send message">'+icon("arrow-up")+'</button></div></div></form>');
      $("#followupForm").onsubmit=sendFollowup;
      state.detailShell=s.id;
    }
    $("#detailTitle").textContent=s.title;
    const t=C.totals(sessionCalls()),children=(state.sessions||[]).filter(c=>s.children.includes(c.id));
    const tokens=t.input+t.output+children.reduce((sum,c)=>sum+(c.inputTokens||0)+(c.outputTokens||0),0);
    html("#detailMeta",badge(s.status)+'<span>Session '+s.id+'</span><span>'+count(tokens)+' provider tokens'+(children.length?" incl. branches":"")+'</span><span>'+esc(s.mode)+'</span>'+(s.parentId?'<a href="/mux/sessions?id='+s.parentId+'">'+icon("git-branch")+' Parent session</a>':"")+(s.error?'<span class="danger">'+esc(s.error)+'</span>':""));
    document.querySelectorAll("[data-action=session-tab]").forEach(b=>{b.setAttribute("aria-selected",String(state.tab===b.dataset.tab));b.tabIndex=state.tab===b.dataset.tab?0:-1;});
    const running=C.active.has(s.status);
    for(const action of ["rename","fork","archive"])$("#sessionDetail [data-action="+action+"]").disabled=running;
    const archive=$("#sessionDetail [data-action=archive]");archive.title=s.archived?"Restore session":"Archive session";archive.setAttribute("aria-label",archive.title);
    $("#followupForm").hidden=s.archived;
    $("#followupForm button[type=submit]").disabled=running;
    $("#followupPrompt").disabled=running;
    $("#followupForm [data-action=cancel]").hidden=!running;
    const modelSelect=$("#followupModel"),current=modelSelect.value;
    html(modelSelect,'<option value="">Automatic routing</option>'+[...new Set((state.models||[]).map(m=>m.model))].map(m=>'<option>'+esc(m)+'</option>').join(""));
    if([...modelSelect.options].some(o=>o.value===current))modelSelect.value=current;
    let content;
    if(state.tab==="conversation"){
      content='<div class="transcript">'+(s.messages.length?s.messages.map(m=>'<article class="message '+esc(m.role)+'"><div class="message-header">'+icon(m.role==="user"?"message-square":"network")+'<b>'+esc(m.role==="user"?"You":m.model||"ModelMux")+'</b><span>'+time(m.at)+'</span></div><div class="message-content">'+messageContent(m.content)+'</div></article>').join(""):empty("No messages yet","message-square"))+(running?'<div class="message"><span class="badge running">'+esc(s.status)+ '</span><span class="muted"> '+t.running+' provider attempts in flight</span></div>':"")+'</div>';
    } else if(state.tab==="receipts")content=attemptsTable(sessionCalls());
    else content='<div class="graph-root">'+icon("git-branch")+'<b>'+esc(s.title)+'</b>'+badge(s.status)+'</div>'+(children.length?'<div class="branch-list">'+children.map(c=>'<a class="branch" href="/mux/sessions?id='+c.id+'"><div><strong>'+esc(c.model||c.title)+'</strong><small>'+count((c.inputTokens||0)+(c.outputTokens||0))+' tokens · '+date(c.updatedAt)+'</small></div>'+badge(c.status)+'</a>').join("")+'</div><div class="graph-root">'+icon("git-merge")+'<span>'+ (s.mode==="synthesize"?"Synthesis":"Collected responses")+'</span>'+badge(s.status)+'</div>':empty("No fan-out branches","git-branch"));
    const transcript=$(".transcript"),bottom=!transcript||transcript.scrollHeight-transcript.clientHeight-transcript.scrollTop<60;
    html("#sessionPanel",content);
    if(bottom&&$(".transcript"))$(".transcript").scrollTop=$(".transcript").scrollHeight;
  }
  function messageContent(text){return String(text).split(/```[^\n]*\n([\s\S]*?)```/g).map((part,i)=>i%2?'<pre><code>'+esc(part)+'</code></pre>':esc(part)).join("");}
  function renderStats(){
    const minutes=Number($("#statsWindow").value),start=Date.now()-minutes*60000;
    const calls=(state.calls||[]).filter(c=>(c.endedAt??c.startedAt)>=start);
    const status=$("#attemptStatus").value;
    html("#statsActivity",attemptsTable(calls.filter(c=>status==="all"||c.status===status)));
    $("#quotaAt").textContent=state.quotaAt?"Observed "+time(state.quotaAt):"Quota unavailable";
    const pools=state.standings||[],groups=new Map();
    for(const c of calls){const group=groups.get(c.model)||[];group.push(c);groups.set(c.model,group);}
    const sessions=(state.sessions||[]).filter(s=>s.updatedAt>=start&&!s.parentId);
    html("#quotaFlow",'<div class="flow-column"><div class="flow-title">KEY POOLS '+icon("chevron-right")+'</div>'+ (pools.slice(0,10).map(s=>'<div class="flow-item"><span>'+esc(s.provider)+'<small class="muted mono" style="display:block;margin-top:5px">'+esc(s.keyId)+'</small></span><b>'+ (s.limit>0?count(s.remaining)+" left":"Unknown limit")+'</b></div>').join("")||empty("No observed pools","key-round"))+'</div><div class="flow-column"><div class="flow-title">MODEL DISPATCH '+icon("chevron-right")+'</div>'+(Array.from(groups).slice(0,10).map(([model,list])=>'<div class="flow-item"><span>'+esc(model)+'</span><b>'+list.length+' calls</b></div>').join("")||empty("No model dispatches"))+'</div><div class="flow-column"><div class="flow-title">SESSION FAN-IN '+icon("git-merge")+'</div>'+(sessions.slice(0,10).map(s=>'<a class="flow-item" href="/mux/sessions?id='+s.id+'"><span>'+esc(s.title)+'<small class="muted" style="display:block;margin-top:5px">'+s.children.length+' branches</small></span>'+badge(s.status)+'</a>').join("")||empty("No sessions in this window","messages-square"))+'</div>');
    drawChart(calls,minutes);
  }
  function drawChart(calls,minutes){
    const canvas=$("#trafficChart"),rect=canvas.getBoundingClientRect();if(!rect.width)return;
    const dpr=window.devicePixelRatio||1;canvas.width=rect.width*dpr;canvas.height=rect.height*dpr;
    const ctx=canvas.getContext("2d");ctx.scale(dpr,dpr);
    const bins=C.buckets(calls,minutes),max=Math.max(4,...bins.map(b=>b.completed+b.failed+b.cached));
    const left=30,right=10,top=10,bottom=28,width=rect.width-left-right,height=rect.height-top-bottom;
    ctx.font="10px -apple-system, sans-serif";ctx.textBaseline="middle";
    for(let i=0;i<=4;i++){const y=top+height*i/4;ctx.strokeStyle="#e3e8ea";ctx.beginPath();ctx.moveTo(left,y);ctx.lineTo(rect.width-right,y);ctx.stroke();ctx.fillStyle="#677179";ctx.fillText(String(Math.round(max*(1-i/4))),2,y);}
    const step=width/bins.length;
    bins.forEach((bin,i)=>{let y=top+height;for(const [key,color]of[["completed","#087f6b"],["failed","#ba3544"],["cached","#346bc2"]]){const h=bin[key]/max*height;ctx.fillStyle=color;ctx.fillRect(left+i*step+2,y-h,Math.max(2,step-4),h);y-=h;}});
    for(const i of [0,Math.floor(bins.length/2),bins.length-1]){ctx.fillStyle="#677179";ctx.textAlign=i===bins.length-1?"right":"left";ctx.fillText(new Date(bins[i].at).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"}),left+i*step+(i===bins.length-1?step:0),rect.height-10);}
    if(!calls.length){ctx.textAlign="center";ctx.fillStyle="#677179";ctx.fillText("No completed requests in this window",rect.width/2,rect.height/2);}
  }
  function render(){showErrors();metrics();renderRecent();if(page==="keys")renderKeys();if(page==="models"){renderModelRows();html("#modelActivity",attemptsTable((state.calls||[]).slice(-6)));}if(page==="sessions")renderSessions();if(page==="stats")renderStats();icons();}
  async function mutate(action){
    if(state.mutating)return;
    state.mutating=true;
    try{await action();state.errors.delete("Action");await poll(true);}catch(error){state.errors.set("Action",error.message);showErrors();}
    finally{state.mutating=false;}
  }
  function goSession(id){location.href="/mux/sessions?id="+id;}
  function exportJson(value,name){const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:"application/json"}));const link=document.createElement("a");link.href=url;link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(url),5000);}
  function openEndpoint(entry){const form=$("#endpointForm");form.reset();for(const key of ["name","base","model","envVar"])form.elements[key].value=entry?.[key]||"";form.elements.name.readOnly=!!entry;form.elements.enabled.checked=entry?.flags?.enabled!==false;form.elements.preferred.checked=!!entry?.flags?.preferred;$("#endpointError").textContent="";$("#endpointTitle").textContent=entry?"Edit endpoint":"Add endpoint";$("#endpointDialog").showModal();}
  async function sendFollowup(event){event.preventDefault();const button=event.currentTarget.querySelector("button[type=submit]");const prompt=$("#followupPrompt").value,model=$("#followupModel").value;button.disabled=true;await mutate(async()=>{await request("/api/mux/sessions/run",{id:state.selected,prompt,models:model?[model]:[],mode:"chat",maxTokens:1024,temperature:.2});$("#followupPrompt").value="";});button.disabled=C.active.has(state.detail?.status);}
  document.addEventListener("click",event=>{
    const b=event.target.closest("[data-action]");if(!b)return;
    const a=b.dataset.action;
    if(a==="refresh"){void poll(true);return;}
    if(a==="close-dialog"){$("#endpointDialog").close();return;}
    if(a==="close-rename"){$("#renameDialog").close();return;}
    if(a==="add-endpoint"){openEndpoint();return;}
    if(a==="edit-endpoint"){openEndpoint(state.endpoints[Number(b.dataset.index)]);return;}
    if(a==="select-session"){state.selected=Number(b.dataset.id);history.replaceState(null,"","?id="+state.selected);state.detail=null;state.detailShell=null;renderSessions();void poll(true);return;}
    if(a==="session-tab"){state.tab=b.dataset.tab;renderSessions();icons();return;}
    if(a==="deselect-model"){state.modelsSelected.delete(b.dataset.model);renderModelRows();icons();return;}
    if(a==="export-activity"){exportJson({atMs:Date.now(),calls:state.calls,standings:state.standings},"mux-activity.json");return;}
    if(a==="export-session"){exportJson(state.detail,"session-"+state.selected+".json");return;}
    if(a==="rename"){$("#renameForm").elements.title.value=state.detail.title;$("#renameDialog").showModal();return;}
    void mutate(async()=>{
      if(a==="new"){const session=await request("/api/mux/sessions",{title:"New session"});goSession(session.id);}
      if(a==="fork"){const session=await request("/api/mux/sessions/fork",{id:state.selected});goSession(session.id);}
      if(a==="archive"){await request("/api/mux/sessions/update",{id:state.selected,archived:!state.detail.archived});toast(state.detail.archived?"Session restored":"Session archived");}
      if(a==="cancel"){await request("/api/mux/sessions/cancel",{id:state.selected});toast("Stopping run and branches");}
      if(a==="delete-endpoint"){const entry=state.endpoints[Number(b.dataset.index)];await request("/api/mux/endpoints/"+encodeURIComponent(entry.name),undefined,"DELETE");toast("Endpoint removed: "+entry.name);}
    });
  });
  document.addEventListener("change",event=>{
    if(event.target.matches("[data-model]")){
      const model=event.target.dataset.model;
      if(event.target.checked){if($("#runMode").value==="chat")state.modelsSelected.clear();if(state.modelsSelected.size>=6){event.target.checked=false;toast("Up to six fan-out models");return;}state.modelsSelected.add(model);}else state.modelsSelected.delete(model);
      renderModelRows();icons();
    }
  });
  $("#liveToggle").onclick=()=>{state.live=!state.live;const b=$("#liveToggle");b.setAttribute("aria-pressed",String(state.live));b.title=state.live?"Pause live updates":"Resume live updates";b.setAttribute("aria-label",b.title);html(b,icon(state.live?"pause":"play"));showErrors();icons();if(state.live)void poll(true);};
  for(const id of ["keySearch","modelSearch","sessionSearch"])$("#"+id).oninput=render;
  for(const id of ["keyStatus","sessionStatus","statsWindow","attemptStatus"])$("#"+id).onchange=render;
  $("#runMode").onchange=()=>{if($("#runMode").value==="chat"&&state.modelsSelected.size>1)state.modelsSelected=new Set([[...state.modelsSelected][0]]);renderModelRows();icons();};
  $("#maxTokens").oninput=selection;
  $("#runForm").onsubmit=async event=>{
    event.preventDefault();const models=[...state.modelsSelected],mode=$("#runMode").value;
    if(mode!=="chat"&&models.length<2){toast("Select at least two models from the roster");return;}
    const button=event.currentTarget.querySelector("button[type=submit]");button.disabled=true;
    await mutate(async()=>{const id=Number($("#runSession").value)||(await request("/api/mux/sessions",{title:"New session"})).id;
      await request("/api/mux/sessions/run",{id,models,mode,prompt:$("#runPrompt").value,maxTokens:Number($("#maxTokens").value),temperature:Number($("#temperature").value)});goSession(id);});button.disabled=false;
  };
  $("#endpointForm").onsubmit=async event=>{
    event.preventDefault();const form=event.currentTarget,button=form.querySelector("button[type=submit]");button.disabled=true;
    try{const payload=Object.fromEntries(["name","base","model","envVar"].map(k=>[k,form.elements[k].value.trim()]));payload.flags={enabled:form.elements.enabled.checked,preferred:form.elements.preferred.checked};await request("/api/mux/endpoints",payload);$("#endpointDialog").close();toast("Endpoint registry saved");await poll(true);}catch(error){$("#endpointError").textContent=error.message;}finally{button.disabled=false;}
  };
  $("#renameForm").onsubmit=event=>{event.preventDefault();void mutate(async()=>{await request("/api/mux/sessions/update",{id:state.selected,title:event.target.elements.title.value});$("#renameDialog").close();});};
  document.addEventListener("keydown",event=>{if(event.target.matches(".session-tabs button")&&["ArrowLeft","ArrowRight"].includes(event.key)){event.preventDefault();const tabs=[...document.querySelectorAll(".session-tabs button")],index=tabs.indexOf(event.target),next=tabs[(index+(event.key==="ArrowRight"?1:tabs.length-1))%tabs.length];next.click();next.focus();}});
  document.addEventListener("visibilitychange",()=>{if(!document.hidden&&state.live)void poll(true);});
  window.addEventListener("resize",()=>{if(page==="stats")renderStats();});
  document.title=titles[page][0]+" | Forge";$("#pageTitle").textContent=titles[page][0];$("#eyebrow").textContent=titles[page][1];$("#breadcrumb").textContent="Workspace / "+titles[page][0];
  $("#"+page+"View").hidden=false;document.querySelectorAll('[data-page="'+page+'"]').forEach(el => el.setAttribute("aria-current","page"));
  html("#pageActions",page==="keys"?'<a href="/mux/stats">Quota activity '+icon("arrow-up-right")+'</a>':page==="stats"?btn("export-activity","Export activity","download"):'<button class="primary" data-action="new">'+icon("plus")+'New session</button>');
  render();void poll(true);
  async function loop(){await poll();setTimeout(loop,2000);}setTimeout(loop,2000);
})();
