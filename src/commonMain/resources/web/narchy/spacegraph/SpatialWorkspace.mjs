import './SpatialRenderer.mjs';

/* The LCNC document owns all effects; this workspace owns only presentation. */
(() => {
  'use strict';
  let open=false,backend='gl',response,request,timer,serial=0,selected=null,pendingPort=null;
  let workspace,surface,inspector,tree,badge,renderer,provider,modeButton,editButton,spacing,query,observer;
  let resetCamera=true,dirtyAlignment=true,heldParams=null,lastIdentity='',cameraRevision=0,paletteWasOpen=false,refreshPending=false;
  const $=id=>document.getElementById(id);
  const el=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text!=null)n.textContent=text;return n;};
  const node=id=>typeof G!=='undefined'?G.nodes.find(n=>n.id===id):null;
  function icon(name){const n=el('i');n.dataset.lucide=name;return n;}
  function command(name,glyph,action,label=false){const b=el('button','sg-command');b.type='button';b.title=name;b.setAttribute('aria-label',name);b.append(icon(glyph));if(label)b.append(el('span','',name));b.onclick=action;return b;}
  function icons(){window.MuxIcons?.();}
  function measurements(){
    const origin=$('world').getBoundingClientRect(),scale=view.z;
    return G.nodes.map(n=>{
      const r=n.el.getBoundingClientRect();let visible=!!r.width&&!!r.height;
      for(let p=n._parentScope;p;p=p._parentScope)if(p.el.classList.contains('collapsed'))visible=false;
      return {id:n.id,x:(r.left-origin.left)/scale,y:(r.top-origin.top)/scale,width:r.width/scale,height:r.height/scale,visible,
        ports:[...n.el.querySelectorAll('.port')].filter(p=>p.closest('.node')===n.el&&p.getClientRects().length).map(p=>{
          const r=p.getBoundingClientRect();return {name:p.dataset.port,input:p.dataset.dir==='in',x:(r.left+r.width/2-origin.left)/scale,y:(r.top+r.height/2-origin.top)/scale};
        })};
    });
  }
  function releaseParams(){if(!heldParams)return;const{params,placeholder}=heldParams;if(placeholder.isConnected)placeholder.replaceWith(params);else params.remove();heldParams=null;}
  function activate(value){
    if(value!==open){if(value){paletteWasOpen=$('palette').classList.contains('open');$('palette').classList.remove('open');}else $('palette').classList.toggle('open',paletteWasOpen);}
    open=value;workspace.hidden=!open;document.body.classList.toggle('sg-spatial',open);
    modeButton.setAttribute('aria-pressed',String(open));editButton.setAttribute('aria-pressed',String(!open));$('viewport').inert=open;
    document.querySelectorAll('[data-spatial]').forEach(n=>n.hidden=!open);
    if(open)schedule();else{releaseParams();request?.abort();request=null;serial++;clearTimeout(timer);timer=null;refreshPending=false;pendingPort=null;}
    dispatchEvent(new Event('resize'));icons();
  }
  function editNode(id){
    const n=node(id);if(!n)return;activate(false);
    const r=n.el.getBoundingClientRect(),v=$('viewport').getBoundingClientRect();
    view.x+=v.left+v.width/2-r.left-r.width/2;view.y+=v.top+v.height/2-r.top-r.height/2;
    applyView();redraw();n.el.animate([{outline:'3px solid #cf5266'},{outline:'0 solid transparent'}],{duration:900});
  }
  function select(id,port=null){
    selected=id;renderer?.highlight(id);document.body.classList.toggle('sg-inspecting',!!id);
    if(port){
      if(pendingPort&&pendingPort.input!==port.input){
        const from=port.input?pendingPort:port,to=port.input?port:pendingPort;
        badge.textContent=connectPanelPorts([from.nodeId,from.name],[to.nodeId,to.name])?'Cable connected':$('status').textContent;pendingPort=null;
      }else{pendingPort=port;badge.textContent=`${port.nodeId} / ${port.name}`;}
    }
    paintInspector();paintTree();
  }
  function paintTree(){
    if(!response)return;const filter=query.value.toLowerCase();tree.replaceChildren();
    for(const n of response.scene.nodes){
      if(filter&&!`${n.id} ${n.title} ${n.type}`.toLowerCase().includes(filter))continue;
      const b=el('button','sg-tree-node');b.setAttribute('aria-pressed',String(n.id===selected));b.title=n.id;b.dataset.nodeId=n.id;b.style.paddingLeft=`${12+Math.min(n.level,8)*12}px`;
      const mark=el('span','sg-node-mark');mark.style.background=n.color;b.append(mark,el('span','sg-tree-title',n.title),el('small','',String(n.ports.length)));
      b.onclick=()=>{select(n.id);renderer.focus(n.id);};tree.append(b);
    }
    if(!tree.children.length)tree.append(el('p','sg-empty','No matching nodes'));
  }
  function section(title){const s=el('section','sg-inspector-section');s.append(el('h3','',title));inspector.append(s);return s;}
  function paintInspector(){
    releaseParams();inspector.replaceChildren();const n=node(selected),geometry=response?.scene.nodes.find(n=>n.id===selected);
    const top=el('header','sg-inspector-title');top.append(el('strong','',n?(CONTRACTS[n.type]?.title||n.type):'Scene'));if(n)top.append(command('Close inspector','x',()=>select(null)));inspector.append(top);
    if(!n){
      const s=section('Document');s.append(el('p','sg-document-name',$('panelName').value||'Untitled'));
      for(const[label,value]of [['Nodes',response?.scene.nodes.length||0],['Cables',response?.scene.cables.length||0],['Measured surfaces',response?.scene.nodes.filter(n=>n.measured).length||0]]){const r=el('div','sg-stat');r.append(el('span','',label),el('b','',String(value)));s.append(r);}
      section('Evidence').append(el('p','sg-muted','No exact node bindings'));return;
    }
    const meta=section('Identity');meta.append(el('code','sg-id',n.id),el('p','sg-muted',n.type));
    if(geometry){const r=el('div','sg-stat');r.append(el('span','','Containment depth'),el('b','',String(geometry.level)));meta.append(r);}
    const actions=el('div','sg-actions');actions.append(command('Open editor','pencil',()=>editNode(n.id),true),command('Frame node','search',()=>renderer.focus(n.id)),command('Delete node','trash-2',()=>{releaseParams();removeNode(n.id);select(null);}));meta.append(actions);
    const params=n.el.querySelector(':scope > .params');
    if(params&&params.children.length){const s=section('Parameters'),r=params.getBoundingClientRect(),scale=n.el.getBoundingClientRect().width/n.el.offsetWidth;
      const placeholder=el('div','sg-params-placeholder');placeholder.style.height=`${r.height/Math.max(scale,.00001)}px`;params.replaceWith(placeholder);s.append(params);heldParams={params,placeholder};}
    const ports=section('Ports');
    for(const p of geometry?.ports||[]){const r=el('button','sg-port');r.setAttribute('aria-pressed',String(pendingPort?.nodeId===n.id&&pendingPort?.name===p.name&&pendingPort?.input===p.input));r.title=`${p.input?'Input':'Output'} ${p.name}`;
      r.append(el('span',p.input?'sg-port-dot input':'sg-port-dot'),el('span','',p.name),el('code','',p.kind||'unresolved'));r.onclick=()=>select(n.id,p);ports.append(r);}
    if(!geometry?.ports.length)ports.append(el('p','sg-muted','No ports'));
    const cables=G.wires.filter(w=>w.from[0]===n.id||w.to[0]===n.id);
    if(cables.length){const s=section('Connections');for(const wire of cables){const r=el('div','sg-connection');r.append(el('code','',`${wire.from[0]}:${wire.from[1]} → ${wire.to[0]}:${wire.to[1]}`),command('Disconnect cable','x',()=>{G.wires=G.wires.filter(w=>w!==wire);redraw();save();}));s.append(r);}}
    const alignment=response?.alignment?.nodes?.find(r=>r.id===n.id),s=section('Alignment candidates');
    for(const[label,values]of [['Rete',alignment?.watchedBy],['Causal rules',alignment?.causalRules],['KIF',alignment?.facts]])s.append(el('h4','',label),el('pre','',(values||[]).join('\n')||'None'));
    section('Epistemic bindings').append(el('p','sg-muted','Not supplied'));icons();
  }
  function moveNode(id,dx,dy){const n=node(id);if(!n)return;const scale=n.el.getBoundingClientRect().width/view.z/n.el.offsetWidth;n.x+=dx/scale;n.y+=dy/scale;n.el.style.left=`${n.x}px`;n.el.style.top=`${n.y}px`;if(n._parentScope)growRingWorldFor(n);redraw();save();}
  function report(error){badge.textContent=error.message||String(error);badge.dataset.error='true';}
  function useProvider(value){try{renderer.setBackend(value);backend=value;provider.value=value;schedule();}catch(error){backend='canvas';provider.value='canvas';renderer.setBackend('canvas');report(`GL unavailable: ${error.message}`);schedule();}}
  async function refresh(){
    if(!open||typeof serialize!=='function'||!renderer)return;
    refreshPending=false;const active=new AbortController();request=active;const current=++serial,align=dirtyAlignment;dirtyAlignment=false;
    try{
      const identity=G.nodes.map(n=>n.id).join('|'),reset=resetCamera||lastIdentity!==identity,revision=cameraRevision;
      const reply=await fetch(`/api/lcnc/spacegraph?width=${Math.max(1,surface.clientWidth)}&height=${Math.max(1,surface.clientHeight)}&alignment=${align?1:0}`,{
        method:'POST',headers:{'Content-Type':'application/json'},signal:active.signal,body:JSON.stringify({...serialize(),name:$('panelName').value||'canvas',geometry:measurements(),spacing:+spacing.value,camera:reset?null:renderer.cameraValue()})});
      const body=await reply.json();if(!reply.ok)throw new Error(body.error||`Projection failed (${reply.status})`);if(current!==serial||!open)return;
      if(!body.scene)throw new Error('Spatial endpoint is not loaded in this daemon');body.alignment??=response?.alignment;response=body;lastIdentity=identity;
      for(const spec of body.scene.nodes){const n=node(spec.id);spec.params=Object.fromEntries(Object.entries(n?.params||{}).map(([key,value])=>[key,CONTRACTS[n.type]?.params?.[key]?.ph?.startsWith('secret:')?'[redacted]':value]));}
      renderer.update(body.scene,reset&&revision===cameraRevision);resetCamera=false;renderer.frame(body.frame,body.svg);
      badge.textContent=`${backend.toUpperCase()} · ${body.scene.nodes.length} nodes · ${body.scene.cables.length} cables${body.scene.issues.length?` · ${body.scene.issues.length} unprojected`:''}`;delete badge.dataset.error;
      if(!inspector.contains(document.activeElement))paintInspector();paintTree();
    }catch(error){dirtyAlignment||=align;if(error.name!=='AbortError')report(error);}
    finally{if(request===active){request=null;if(refreshPending&&open)schedule();}}
  }
  function schedule(alignment=false){dirtyAlignment||=alignment;if(!open)return;refreshPending=true;if(timer||request)return;timer=setTimeout(()=>{timer=null;refresh();},140);}
  function retitle(id,name,glyph,label=false){const b=$(id);if(!b)return;b.replaceChildren(icon(glyph));if(label)b.append(el('span','',name));b.title=name;b.setAttribute('aria-label',name);b.classList.add('sg-command');}
  addEventListener('DOMContentLoaded',()=>{
    if(!$('spacegraphBtn'))return;document.body.classList.add('sg-workbench');
    const bar=$('bar');bar.querySelector('b').textContent='LCNC';bar.querySelector('.sub').textContent='SpaceGraph';
    for(const args of [['runBtn','Run','play',true],['stopBtn','Stop timers','square'],['addBtn','Add node','plus',true],['fdBtn','Layout','git-branch'],['shakeBtn','Connect open ports','git-merge'],['fitBtn','Fit document','search'],['storeSaveBtn','Save to store','archive'],['storeLoadBtn','Load from store','download'],['presetsBtn','Programs','panels-top-left',true],['rdfBtn','RDF and ontology','network'],['paletteBtn','Node library','panels-top-left'],['keysBtn','Provider keys','key-round'],['exportBtn','Export','download'],['importBtn','Import','arrow-up'],['undoBtn','Undo','rotate-ccw'],['redoBtn','Redo','rotate-ccw'],['clearBtn','Clear document','trash-2'],['qsBtn','Quickstart','external-link'],['fbBtn','Feedback','message-square']])retitle(...args);
    $('redoBtn').classList.add('sg-redo');
    const tools=el('div','sg-tools');tools.id='sg-tools';document.body.append(tools);
    const modes=el('div','sg-modes');modes.setAttribute('role','group');modes.setAttribute('aria-label','Workspace view');
    editButton=command('Editor','panels-top-left',()=>activate(false),true);editButton.id='sg-editor';modeButton=$('spacegraphBtn');modeButton.replaceChildren(icon('network'),el('span','','Spatial'));modeButton.title='Spatial workspace';modeButton.setAttribute('aria-label','Spatial workspace');modeButton.onclick=()=>activate(true);modes.append(editButton,modeButton);tools.append(modes);
    for(const id of ['addBtn','paletteBtn','fdBtn','shakeBtn','fitBtn','undoBtn','redoBtn'])tools.append($(id));
    const documents=el('div','sg-documents');for(const id of ['presetsBtn','storeSaveBtn'])documents.append($(id));bar.append(documents);
    const more=el('details','sg-more'),summary=el('summary');summary.title='Document actions';summary.setAttribute('aria-label','Document actions');summary.append(icon('panels-top-left'));more.append(summary);
    const menu=el('div','sg-more-menu');for(const id of ['storeLoadBtn','exportBtn','importBtn','rdfBtn','keysBtn','clearBtn','qsBtn','fbBtn']){const b=$(id);b.append(el('span','',b.getAttribute('aria-label')));b.addEventListener('click',()=>more.open=false);menu.append(b);}more.append(menu);bar.append(more,$('runBtn'),$('stopBtn'));
    const spatial=el('div','sg-spatial-tools');spatial.dataset.spatial='';provider=el('select');provider.setAttribute('aria-label','Rendering provider');for(const value of ['gl','canvas','svg']){const o=el('option','',value.toUpperCase());o.value=value;provider.append(o);}provider.onchange=()=>useProvider(provider.value);
    const fit=command('Frame scene','search',()=>{resetCamera=true;schedule();});fit.id='sg-fit';
    const front=command('Front view','square',()=>renderer.front());front.id='sg-front';
    const move=command('Move nodes','pencil',()=>{const on=renderer.mode!=='move';renderer.mode=on?'move':'orbit';move.setAttribute('aria-pressed',String(on));});move.id='sg-move';move.setAttribute('aria-pressed','false');
    spacing=el('input');spacing.type='range';spacing.min='0';spacing.max='400';spacing.step='10';spacing.value='110';spacing.setAttribute('aria-label','Layer spacing');spacing.title='Layer spacing';spacing.oninput=()=>schedule();
    spatial.append(provider,fit,front,move,command('Zoom in','plus',()=>renderer.zoom(1.3)),command('Zoom out','search',()=>renderer.zoom(1/1.3)),spacing);tools.append(spatial);
    workspace=el('main','sg-workspace');workspace.id='spacegraph-shadow';workspace.hidden=true;
    const navigator=el('nav','sg-navigator');navigator.setAttribute('aria-label','Scene nodes');const nh=el('header');nh.append(el('strong','','Nodes'));navigator.append(nh);
    query=el('input');query.type='search';query.placeholder='Find a node';query.setAttribute('aria-label','Find a node');query.oninput=paintTree;navigator.append(query);tree=el('div','sg-tree');navigator.append(tree);
    surface=el('div','sg-surface');surface.id='sg-surface';surface.setAttribute('aria-label','Spatial canvas');surface.tabIndex=0;
    inspector=el('aside','sg-inspector');inspector.setAttribute('aria-label','Node inspector');workspace.append(navigator,surface,inspector);document.body.append(workspace);
    badge=el('div','sg-badge','Preparing scene');badge.setAttribute('role','status');workspace.append(badge);
    spatial.append(command('Node inspector','panels-top-left',()=>{if(matchMedia('(max-width:650px)').matches)document.body.classList.toggle('sg-inspecting');else document.body.classList.toggle('sg-inspector-hidden');}));
    if(typeof SpaceGraphRenderer==='undefined'){report('Spatial renderer asset unavailable');activate(false);return;}
    renderer=new SpaceGraphRenderer(surface,{select,moveNode,viewChanged:()=>{cameraRevision++;if(backend!=='gl')schedule();},unavailable:message=>{useProvider('canvas');report(message);}});useProvider('gl');
    new ResizeObserver(()=>schedule()).observe(surface);
    observer=new MutationObserver(records=>{if(records.some(r=>{
      const target=r.target.nodeType===1?r.target:r.target.parentElement;
      if(target?.closest('#wires')||(r.type==='attributes'&&target===$('world')))return false;
      return r.type!=='childList'||![...r.addedNodes,...r.removedNodes].every(n=>n.nodeType===1&&n.matches('.params,.sg-params-placeholder'));
    }))schedule();});
    observer.observe($('world'),{attributes:true,attributeFilter:['class','style'],childList:true,subtree:true,characterData:true});
    $('fitBtn').addEventListener('click',()=>{if(open){resetCamera=true;schedule();}});
    window.SpaceGraphWorkspace={select,activate,refresh:()=>schedule(true),measurements,get scene(){return response?.scene;},get renderer(){return renderer;},get backend(){return backend;}};
    icons();activate(new URLSearchParams(location.search).get('spacegraph')!=='0');
  });
  addEventListener('lcnc:shadow-update',()=>schedule(true));
  addEventListener('pagehide',()=>{releaseParams();request?.abort();clearTimeout(timer);observer?.disconnect();renderer?.destroy();});
})();
