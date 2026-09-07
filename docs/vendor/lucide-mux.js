(()=>{var I=(a,o,r=[])=>{let f=document.createElementNS("http://www.w3.org/2000/svg",a);return Object.keys(o).forEach(t=>{f.setAttribute(t,String(o[t]))}),r.length&&r.forEach(t=>{let l=I(...t);f.appendChild(l)}),f},W=([a,o,r])=>I(a,o,r);var J=a=>Array.from(a.attributes).reduce((o,r)=>(o[r.name]=r.value,o),{}),Q=a=>typeof a=="string"?a:!a||!a.class?"":a.class&&typeof a.class=="string"?a.class.split(" "):a.class&&Array.isArray(a.class)?a.class:"",j=a=>a.flatMap(Q).map(r=>r.trim()).filter(Boolean).filter((r,f,t)=>t.indexOf(r)===f).join(" "),Y=a=>a.replace(/(\w)(\w*)(_|-|\s*)/g,(o,r,f)=>r.toUpperCase()+f.toLowerCase()),u=(a,{nameAttr:o,icons:r,attrs:f})=>{let t=a.getAttribute(o);if(t==null)return;let l=Y(t),H=r[l];if(!H)return console.warn(`${a.outerHTML} icon name was not found in the provided icons object.`);let G=J(a),[N,X,K]=H,E={...X,"data-lucide":t,...f,...G},V=j(["lucide",`lucide-${t}`,G,f]);V&&Object.assign(E,{class:V});let Z=W([N,E,K]);return a.parentNode?.replaceChild(Z,a)};var e={xmlns:"http://www.w3.org/2000/svg",width:24,height:24,viewBox:"0 0 24 24",fill:"none",stroke:"currentColor","stroke-width":2,"stroke-linecap":"round","stroke-linejoin":"round"};var d=["svg",e,[["rect",{width:"20",height:"5",x:"2",y:"3",rx:"1"}],["path",{d:"M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8"}],["path",{d:"M10 12h4"}]]];var p=["svg",e,[["path",{d:"M7 7h10v10"}],["path",{d:"M7 17 17 7"}]]];var m=["svg",e,[["path",{d:"m5 12 7-7 7 7"}],["path",{d:"M12 19V5"}]]];var x=["svg",e,[["path",{d:"M12 16v5"}],["path",{d:"M16 14v7"}],["path",{d:"M20 10v11"}],["path",{d:"m22 3-8.646 8.646a.5.5 0 0 1-.708 0L9.354 8.354a.5.5 0 0 0-.707 0L2 15"}],["path",{d:"M4 18v3"}],["path",{d:"M8 14v7"}]]];var i=["svg",e,[["path",{d:"M20 6 9 17l-5-5"}]]];var n=["svg",e,[["path",{d:"m9 18 6-6-6-6"}]]];var c=["svg",e,[["circle",{cx:"12",cy:"12",r:"10"}]]];var h=["svg",e,[["circle",{cx:"12",cy:"12",r:"10"}],["polyline",{points:"12 6 12 12 16 14"}]]];var C=["svg",e,[["rect",{width:"14",height:"14",x:"8",y:"8",rx:"2",ry:"2"}],["path",{d:"M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"}]]];var g=["svg",e,[["path",{d:"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}],["polyline",{points:"7 10 12 15 17 10"}],["line",{x1:"12",x2:"12",y1:"15",y2:"3"}]]];var S=["svg",e,[["path",{d:"M15 3h6v6"}],["path",{d:"M10 14 21 3"}],["path",{d:"M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"}]]];var w=["svg",e,[["line",{x1:"6",x2:"6",y1:"3",y2:"15"}],["circle",{cx:"18",cy:"6",r:"3"}],["circle",{cx:"6",cy:"18",r:"3"}],["path",{d:"M18 9a9 9 0 0 1-9 9"}]]];var A=["svg",e,[["circle",{cx:"18",cy:"18",r:"3"}],["circle",{cx:"6",cy:"6",r:"3"}],["path",{d:"M6 21V9a9 9 0 0 0 9 9"}]]];var P=["svg",e,[["path",{d:"M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z"}],["circle",{cx:"16.5",cy:"7.5",r:".5",fill:"currentColor"}]]];var k=["svg",e,[["path",{d:"M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"}]]];var M=["svg",e,[["path",{d:"M14 9a2 2 0 0 1-2 2H6l-4 4V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2z"}],["path",{d:"M18 9h2a2 2 0 0 1 2 2v11l-4-4h-6a2 2 0 0 1-2-2v-1"}]]];var B=["svg",e,[["rect",{x:"16",y:"16",width:"6",height:"6",rx:"1"}],["rect",{x:"2",y:"16",width:"6",height:"6",rx:"1"}],["rect",{x:"9",y:"2",width:"6",height:"6",rx:"1"}],["path",{d:"M5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3"}],["path",{d:"M12 12V8"}]]];var s=["svg",e,[["rect",{width:"18",height:"18",x:"3",y:"3",rx:"2"}],["path",{d:"M3 9h18"}],["path",{d:"M9 21V9"}]]];var F=["svg",e,[["rect",{x:"14",y:"4",width:"4",height:"16",rx:"1"}],["rect",{x:"6",y:"4",width:"4",height:"16",rx:"1"}]]];var D=["svg",e,[["path",{d:"M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"}],["path",{d:"m15 5 4 4"}]]];var y=["svg",e,[["polygon",{points:"6 3 20 12 6 21 6 3"}]]];var L=["svg",e,[["path",{d:"M5 12h14"}],["path",{d:"M12 5v14"}]]];var b=["svg",e,[["path",{d:"M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"}],["path",{d:"M21 3v5h-5"}],["path",{d:"M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"}],["path",{d:"M8 16H3v5"}]]];var R=["svg",e,[["path",{d:"M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"}],["path",{d:"M3 3v5h5"}]]];var v=["svg",e,[["circle",{cx:"11",cy:"11",r:"8"}],["path",{d:"m21 21-4.3-4.3"}]]];var q=["svg",e,[["rect",{width:"18",height:"18",x:"3",y:"3",rx:"2"}]]];var T=["svg",e,[["path",{d:"M3 6h18"}],["path",{d:"M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"}],["path",{d:"M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"}],["line",{x1:"10",x2:"10",y1:"11",y2:"17"}],["line",{x1:"14",x2:"14",y1:"11",y2:"17"}]]];var U=["svg",e,[["rect",{width:"8",height:"8",x:"3",y:"3",rx:"2"}],["path",{d:"M7 11v4a2 2 0 0 0 2 2h4"}],["rect",{width:"8",height:"8",x:"13",y:"13",rx:"2"}]]];var O=["svg",e,[["path",{d:"M18 6 6 18"}],["path",{d:"m6 6 12 12"}]]];var z=({icons:a={},nameAttr:o="data-lucide",attrs:r={}}={})=>{if(!Object.values(a).length)throw new Error(`Please provide an icons object.
If you want to use all the icons you can import it like:
 \`import { createIcons, icons } from 'lucide';
lucide.createIcons({icons});\``);if(typeof document>"u")throw new Error("`createIcons()` only works in a browser environment.");let f=document.querySelectorAll(`[${o}]`);if(Array.from(f).forEach(t=>u(t,{nameAttr:o,icons:a,attrs:r})),o==="data-lucide"){let t=document.querySelectorAll("[icon-name]");t.length>0&&(console.warn("[Lucide] Some icons were found with the now deprecated icon-name attribute. These will still be replaced for backwards compatibility, but will no longer be supported in v1.0 and you should switch to data-lucide"),Array.from(t).forEach(l=>u(l,{nameAttr:"icon-name",icons:a,attrs:r})))}};window.MuxIcons=()=>z({icons:{Workflow:U,KeyRound:P,Network:B,MessagesSquare:M,ChartNoAxesCombined:x,Plus:L,PanelsTopLeft:s,Pause:F,Play:y,RefreshCw:b,ArrowUp:m,ArrowUpRight:p,GitBranch:w,Download:g,Check:i,X:O,Square:q,Archive:d,Pencil:D,Search:v,Circle:c,Clock:h,ChevronRight:n,GitMerge:A,Copy:C,RotateCcw:R,ExternalLink:S,Trash2:T,MessageSquare:k}});})();
/*! Bundled license information:

lucide/dist/esm/createElement.js:
lucide/dist/esm/replaceElement.js:
lucide/dist/esm/defaultAttributes.js:
lucide/dist/esm/icons/archive.js:
lucide/dist/esm/icons/arrow-up-right.js:
lucide/dist/esm/icons/arrow-up.js:
lucide/dist/esm/icons/chart-no-axes-combined.js:
lucide/dist/esm/icons/check.js:
lucide/dist/esm/icons/chevron-right.js:
lucide/dist/esm/icons/circle.js:
lucide/dist/esm/icons/clock.js:
lucide/dist/esm/icons/copy.js:
lucide/dist/esm/icons/download.js:
lucide/dist/esm/icons/external-link.js:
lucide/dist/esm/icons/git-branch.js:
lucide/dist/esm/icons/git-merge.js:
lucide/dist/esm/icons/key-round.js:
lucide/dist/esm/icons/message-square.js:
lucide/dist/esm/icons/messages-square.js:
lucide/dist/esm/icons/network.js:
lucide/dist/esm/icons/panels-top-left.js:
lucide/dist/esm/icons/pause.js:
lucide/dist/esm/icons/pencil.js:
lucide/dist/esm/icons/play.js:
lucide/dist/esm/icons/plus.js:
lucide/dist/esm/icons/refresh-cw.js:
lucide/dist/esm/icons/rotate-ccw.js:
lucide/dist/esm/icons/search.js:
lucide/dist/esm/icons/square.js:
lucide/dist/esm/icons/trash-2.js:
lucide/dist/esm/icons/workflow.js:
lucide/dist/esm/icons/x.js:
lucide/dist/esm/lucide.js:
  (**
   * @license lucide v0.468.0 - ISC
   *
   * This source code is licensed under the ISC license.
   * See the LICENSE file in the root directory of this source tree.
   *)
*/
