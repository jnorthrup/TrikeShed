import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

const vector = p => new THREE.Vector3(...p);

export class SpatialRenderer {
  constructor(host, callbacks) {
    this.host = host; this.callbacks = callbacks; this.backend = 'gl'; this.mode = 'orbit';
    this.scene = new THREE.Scene(); this.scene.background = new THREE.Color('#ebeff0');
    this.camera = new THREE.PerspectiveCamera(45, 1, .1, 1000000);
    this.controls = new OrbitControls(this.camera, host);
    this.controls.enableDamping = false; this.controls.screenSpacePanning = true;
    this.controls.addEventListener('change', () => { if (!this.settingCamera) { this.render(); callbacks.viewChanged(); } });
    this.scene.add(new THREE.AmbientLight(0xffffff, 2.2));
    const light = new THREE.DirectionalLight(0xffffff, 2); light.position.set(-500, 600, 1400); this.scene.add(light);
    this.objects = new THREE.Group(); this.scene.add(this.objects);
    this.picks = []; this.nodes = new Map(); this.raycaster = new THREE.Raycaster();
    this.output = document.createElement('div'); this.output.className = 'sg-output'; host.append(this.output);
    this.down = event => {
      if (event.button !== 0) return;
      this.start = {x:event.clientX, y:event.clientY};
      const hit = this.hit(event);
      if (this.mode === 'move' && hit?.nodeId) {
        const node = this.nodes.get(hit.nodeId); if (!node) return;
        this.controls.enabled = false;
        const plane = new THREE.Plane(new THREE.Vector3(0, 0, 1), -node.position[2]);
        const point = this.raycaster.ray.intersectPlane(plane, new THREE.Vector3());
        if (point) { this.drag = {id:node.id, plane, point:point.clone()}; host.setPointerCapture(event.pointerId); }
      }
    };
    this.move = event => {
      if (!this.drag) return;
      this.ray(event); const point = this.raycaster.ray.intersectPlane(this.drag.plane, new THREE.Vector3());
      if (point) this.drag.delta = point.sub(this.drag.point);
    };
    this.up = event => {
      if (this.drag) {
        const drag = this.drag; this.drag = null; this.controls.enabled = true;
        if (host.hasPointerCapture(event.pointerId)) host.releasePointerCapture(event.pointerId);
        if (drag.delta) callbacks.moveNode(drag.id, drag.delta.x, -drag.delta.y);
      } else if (this.start && Math.hypot(event.clientX-this.start.x, event.clientY-this.start.y) < 5) {
        const hit = this.hit(event); callbacks.select(hit?.nodeId || null, hit?.port || null);
      }
      this.start = null;
    };
    // Capture disables orbit before OrbitControls sees a node-move gesture.
    host.addEventListener('pointerdown', this.down, true);
    host.addEventListener('pointermove', this.move);
    host.addEventListener('pointerup', this.up);
    this.cancel = () => { this.drag = null; this.start = null; this.controls.enabled = true; };
    host.addEventListener('pointercancel', this.cancel);
    this.resizeObserver = new ResizeObserver(() => this.resize()); this.resizeObserver.observe(host);
  }
  ray(event) {
    const r = this.host.getBoundingClientRect();
    this.raycaster.setFromCamera(new THREE.Vector2((event.clientX-r.left)/r.width*2-1, 1-(event.clientY-r.top)/r.height*2), this.camera);
  }
  hit(event) { this.ray(event); this.scene.updateMatrixWorld(true); return this.raycaster.intersectObjects(this.picks, false)[0]?.object.userData; }
  disposeObjects() {
    this.objects.traverse(o => {
      o.geometry?.dispose();
      for (const m of (Array.isArray(o.material) ? o.material : [o.material])) { m?.map?.dispose(); m?.dispose(); }
    });
    this.objects.clear(); this.picks = []; this.nodes.clear();
  }
  texture(node) {
    const canvas = document.createElement('canvas');
    const ratio = Math.min(3, 1536/node.size[0], 1536/node.size[1]);
    canvas.width = Math.max(2, Math.round(node.size[0]*ratio)); canvas.height = Math.max(2, Math.round(node.size[1]*ratio));
    const c = canvas.getContext('2d'); c.scale(ratio, ratio);
    const [w,h] = node.size;
    c.fillStyle = '#fbfcfc'; c.fillRect(0,0,w,h); c.fillStyle = node.color; c.fillRect(0,0,w,4);
    c.fillStyle = '#eaf0f1'; c.fillRect(0,4,w,30);
    c.save(); c.beginPath(); c.rect(10,5,w-20,28); c.clip(); c.font='600 12px system-ui'; c.fillStyle='#27343a'; c.fillText(node.title,12,24); c.restore();
    c.font='10px monospace'; c.fillStyle='#67767e'; c.fillText(node.type,12,50,Math.max(1,w-24));
    let y=71;
    for (const [key,value] of Object.entries(node.params || {}).slice(0,5)) {
      if (y > h-20) break;
      c.fillStyle='#75828a'; c.fillText(key,12,y,Math.max(1,w-24)); y+=16;
      c.fillStyle='#33444c';
      const words=String(value).split(/\s+/); let line='';
      for (const word of words) {
        if (c.measureText(line+word).width > w-24 && line) { c.fillText(line,12,y,w-24); y+=14; line=''; }
        if (y>h-14) break; line+=word+' ';
      }
      if (y<h-14) c.fillText(line,12,y,Math.max(1,w-24)); y+=24;
    }
    const texture = new THREE.CanvasTexture(canvas); texture.colorSpace = THREE.SRGBColorSpace; return texture;
  }
  update(packet, reset = false) {
    this.packet = packet; this.disposeObjects();
    for (const node of packet.nodes) {
      this.nodes.set(node.id,node);
      const [w,h,d]=node.size, color=new THREE.Color(node.color);
      const shape=new THREE.Shape(); shape.moveTo(-w/2,-h/2); shape.lineTo(w/2,-h/2); shape.lineTo(w/2,h/2); shape.lineTo(-w/2,h/2); shape.closePath();
      if (node.scope && w>16 && h>16) {
        const hole=new THREE.Path(); hole.moveTo(-w/2+4,-h/2+4); hole.lineTo(-w/2+4,h/2-4); hole.lineTo(w/2-4,h/2-4); hole.lineTo(w/2-4,-h/2+4); hole.closePath(); shape.holes.push(hole);
      }
      const geometry = new THREE.ExtrudeGeometry(shape,{depth:d,bevelEnabled:false,steps:1}); geometry.translate(0,0,-d/2);
      const material = new THREE.MeshStandardMaterial({color:node.scope?color:'#d5dfe2',roughness:.85,metalness:0});
      const body = new THREE.Mesh(geometry,material); body.position.copy(vector(node.position)); body.userData={nodeId:node.id}; this.objects.add(body); this.picks.push(body);
      const edge = new THREE.LineSegments(new THREE.EdgesGeometry(geometry), new THREE.LineBasicMaterial({color})); edge.position.copy(body.position); edge.userData={outline:node.id}; this.objects.add(edge);
      if (!node.scope) {
        const face=new THREE.Mesh(new THREE.PlaneGeometry(w,h),new THREE.MeshBasicMaterial({map:this.texture(node),side:THREE.FrontSide}));
        face.position.copy(body.position); face.position.z+=d/2+.1; face.userData={nodeId:node.id}; this.objects.add(face); this.picks.push(face);
      } else {
        const label={...node,size:[Math.min(w,360),34,d],params:{}};
        const face=new THREE.Mesh(new THREE.PlaneGeometry(label.size[0],34),new THREE.MeshBasicMaterial({map:this.texture(label)}));
        face.position.copy(body.position).add(new THREE.Vector3(-w/2+label.size[0]/2,h/2+17,d/2+.2)); face.userData={nodeId:node.id}; this.objects.add(face); this.picks.push(face);
      }
      for (const p of node.ports) {
        const port=new THREE.Mesh(new THREE.SphereGeometry(5,12,8),new THREE.MeshStandardMaterial({color:p.input?'#287caf':'#15968a',roughness:.5}));
        port.position.copy(vector(p.position)); port.userData={nodeId:node.id,port:p}; this.objects.add(port); this.picks.push(port);
      }
    }
    for (const cable of packet.cables) {
      const curve=new THREE.CubicBezierCurve3(...cable.points.map(vector));
      const tube=new THREE.Mesh(new THREE.TubeGeometry(curve,32,1.4,5,false),new THREE.MeshBasicMaterial({color:'#15988e'}));
      this.objects.add(tube);
      const arrow=new THREE.Mesh(new THREE.ConeGeometry(4,12,8),new THREE.MeshBasicMaterial({color:'#148278'}));
      arrow.position.copy(curve.getPoint(.88)); arrow.quaternion.setFromUnitVectors(new THREE.Vector3(0,1,0),curve.getTangent(.88).normalize()); this.objects.add(arrow);
    }
    if (reset || !this.hasCamera) this.setCamera(packet.camera);
    this.highlight(this.selected); this.resize(); this.render();
  }
  setCamera(data) {
    this.settingCamera=true; this.camera.position.copy(vector(data.position)); this.controls.target.copy(vector(data.center));
    this.camera.fov=data.fov||45; this.camera.near=data.near||.1; this.camera.far=data.far||1000000; this.camera.zoom=data.zoom||1;
    this.camera.updateProjectionMatrix(); this.controls.update(); this.settingCamera=false; this.hasCamera=true;
  }
  cameraValue() { return this.hasCamera ? {position:this.camera.position.toArray(),center:this.controls.target.toArray(),zoom:this.camera.zoom} : null; }
  front() { const d=this.camera.position.distanceTo(this.controls.target); this.camera.position.copy(this.controls.target).add(new THREE.Vector3(0,0,d)); this.controls.update(); }
  fit() { if(this.packet) {this.setCamera(this.packet.camera); this.render(); this.callbacks.viewChanged();} }
  zoom(factor) { this.camera.zoom=THREE.MathUtils.clamp(this.camera.zoom*factor,.01,100); this.camera.updateProjectionMatrix(); this.render(); this.callbacks.viewChanged(); }
  focus(id) {
    const n=this.nodes.get(id); if(!n)return;
    const direction=this.camera.position.clone().sub(this.controls.target).normalize();
    const radius=vector(n.size).length()/2,angle=Math.atan(Math.tan(this.camera.fov*Math.PI/360)*Math.min(1,this.camera.aspect));
    this.controls.target.copy(vector(n.position));this.camera.position.copy(this.controls.target).addScaledVector(direction,Math.max(80,radius/Math.sin(angle)*1.15));
    this.camera.zoom=1;this.camera.updateProjectionMatrix();
    this.controls.update(); this.render(); this.callbacks.viewChanged();
  }
  highlight(id) {
    this.selected=id;
    this.objects.traverse(o=>{if(o.userData.outline){o.material.color.set(o.userData.outline===id?'#e35067':this.nodes.get(o.userData.outline).color);}});
    this.render();
  }
  setBackend(backend) {
    if(backend==='gl'&&!this.gl) {
      this.gl=new THREE.WebGLRenderer({antialias:true,preserveDrawingBuffer:true});
      this.gl.setPixelRatio(Math.min(devicePixelRatio||1,2)); this.gl.outputColorSpace=THREE.SRGBColorSpace;
      this.gl.domElement.setAttribute('aria-label','LCNC extruded scene');
      this.gl.domElement.addEventListener('webglcontextlost',event=>{event.preventDefault(); this.callbacks.unavailable('WebGL context lost');});
    }
    this.backend=backend; this.output.replaceChildren(); if(backend==='gl')this.output.append(this.gl.domElement);
    this.resize(); this.render();
  }
  frame(data, svgText) {
    if(this.backend==='gl')return;
    if(this.backend==='svg') {
      const doc=new DOMParser().parseFromString(svgText,'image/svg+xml');
      if(doc.querySelector('parsererror'))throw new Error('Invalid SVG projection');
      this.output.replaceChildren(document.importNode(doc.documentElement,true)); return;
    }
    const canvas=document.createElement('canvas'),ratio=Math.min(devicePixelRatio||1,2);
    canvas.width=Math.round(data.width*ratio);canvas.height=Math.round(data.height*ratio);
    const c=canvas.getContext('2d'); if(!c)throw new Error('Canvas 2D unavailable'); c.scale(ratio,ratio);c.fillStyle=data.background;c.fillRect(0,0,data.width,data.height);
    for(const item of data.items){
      c.save();if(item.clip){c.beginPath();c.rect(...item.clip);c.clip();}
      if(item.type==='path'){
        c.beginPath();for(const [op,...args] of item.path){if(op==='M')c.moveTo(...args);else if(op==='L')c.lineTo(...args);else if(op==='Q')c.quadraticCurveTo(...args);else if(op==='C')c.bezierCurveTo(...args);else if(op==='Z')c.closePath();}
        if(item.fill){c.fillStyle=item.fill;c.fill();}if(item.stroke){c.strokeStyle=item.stroke;c.lineWidth=item.width;c.setLineDash(item.dash);c.stroke();}
      }else if(item.type==='text'){c.font=`${item.size}px ${item.font}`;c.fillStyle=item.color;c.fillText(item.text,item.position[0],item.position[1],item.maxWidth||10000);}
      c.restore();
    }
    this.output.replaceChildren(canvas);
  }
  resize(){const w=Math.max(1,this.host.clientWidth),h=Math.max(1,this.host.clientHeight);this.camera.aspect=w/h;this.camera.updateProjectionMatrix();if(this.gl)this.gl.setSize(w,h);this.render();}
  render(){if(this.backend==='gl'&&this.gl)this.gl.render(this.scene,this.camera);}
  destroy(){this.resizeObserver.disconnect();this.controls.dispose();this.disposeObjects();this.gl?.dispose();this.output.remove();this.host.removeEventListener('pointerdown',this.down,true);this.host.removeEventListener('pointermove',this.move);this.host.removeEventListener('pointerup',this.up);this.host.removeEventListener('pointercancel',this.cancel);}
}

window.SpaceGraphRenderer=SpatialRenderer;
