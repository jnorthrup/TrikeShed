export class SpatialRenderer {
  constructor(host, callbacks) {
    const Engine = globalThis.TrikeShed?.narchy?.spacegraph?.BrowserSpatialEngine;
    if (!Engine) throw new Error('Kotlin scene engine unavailable; run ./gradlew stageKotlinJs');
    this.engine = new Engine(); this.host = host; this.callbacks = callbacks; this.backend = 'canvas'; this.mode = 'orbit';
    this.output = document.createElement('div'); this.output.className = 'sg-output'; host.append(this.output);
    this.canvas = document.createElement('canvas'); this.canvas.setAttribute('aria-label', 'LCNC extruded scene');
    this.canvas.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;pointer-events:none';
    this.context = this.canvas.getContext('2d');
    this.handlers = {
      pointerdown: e => {
        if (e.button > 2) return;
        const [x,y] = this.point(e), hit = this.engine.pick(x,y), n = this.packet?.nodes.find(n => n.id === hit?.nodeId);
        this.gesture = {x,y,lastX:x,lastY:y,button:e.button,pan:e.shiftKey||e.button!==0,hit};
        if (this.mode === 'move' && e.button === 0 && n) this.gesture.drag = {id:n.id,z:n.position[2],point:this.engine.unproject(x,y,n.position[2])};
        host.setPointerCapture(e.pointerId);
      },
      pointermove: e => {
        const g = this.gesture; if (!g) return;
        const [x,y] = this.point(e), dx = x-g.lastX, dy = y-g.lastY;
        g.lastX=x; g.lastY=y;
        if (g.drag) {
          const p = this.engine.unproject(x,y,g.drag.z);
          if (p && g.drag.point) { this.callbacks.moveNode(g.drag.id,p[0]-g.drag.point[0],g.drag.point[1]-p[1]); g.drag.point=p; }
        } else { if (g.pan) this.engine.pan(dx,dy); else this.engine.orbit(dx,dy); this.changed(); }
      },
      pointerup: e => {
        const g = this.gesture; this.gesture = null;
        if (host.hasPointerCapture(e.pointerId)) host.releasePointerCapture(e.pointerId);
        if (g && Math.hypot(g.lastX-g.x,g.lastY-g.y)<5 && e.button===0) this.callbacks.select(g.hit?.nodeId||null,g.hit?.port||null);
      },
      pointercancel: () => { this.gesture=null; },
      wheel: e => { e.preventDefault(); const [x,y]=this.point(e); this.engine.zoom(Math.exp(-Math.max(-200,Math.min(200,e.deltaY))*.004),x,y); this.changed(); },
      dblclick: e => { const hit=this.hit(e); if(hit) this.focus(hit.nodeId); },
      contextmenu: e => e.preventDefault(),
    };
    for (const [event,handler] of Object.entries(this.handlers)) host.addEventListener(event,handler,{passive:false});
    this.resizeObserver=new ResizeObserver(()=>this.resize()); this.resizeObserver.observe(host);
  }
  point(e) { const r=this.host.getBoundingClientRect(); return [e.clientX-r.left,e.clientY-r.top]; }
  hit(e) { return this.engine.pick(...this.point(e)); }
  update(packet,reset=false) { this.packet=packet; this.engine.update(packet,reset||!this.hasCamera); this.hasCamera=true; this.resize(); }
  cameraValue() { return this.hasCamera?this.engine.camera():null; }
  changed() { this.render(); this.callbacks.viewChanged(); }
  front() { this.engine.front(); this.changed(); }
  fit() { this.engine.fit(); this.changed(); }
  zoom(factor) { this.engine.zoom(factor,this.host.clientWidth/2,this.host.clientHeight/2); this.changed(); }
  focus(id) { this.engine.focus(id); this.changed(); }
  highlight(id) { this.engine.select(id); this.render(); }
  setBackend(backend) {
    if (!['gl','canvas','svg'].includes(backend)) throw new Error(`Unknown provider ${backend}`);
    if (backend==='gl' && !this.gl) this.openGL();
    this.backend=backend; this.output.replaceChildren();
    if (backend==='gl') this.output.append(this.glCanvas,this.canvas);
    else if (backend==='canvas') this.output.append(this.canvas);
    this.resize();
  }
  openGL() {
    const canvas=document.createElement('canvas'); canvas.setAttribute('aria-label','LCNC GL geometry');
    canvas.style.cssText='position:absolute;inset:0;width:100%;height:100%;pointer-events:none';
    const gl=canvas.getContext('webgl',{antialias:true,preserveDrawingBuffer:true});
    if (!gl) throw new Error('WebGL unavailable');
    const shaders=[];
    const shader=(type,source)=>{const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);shaders.push(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw new Error(gl.getShaderInfoLog(s));return s;};
    const program=gl.createProgram();
    try {
      gl.attachShader(program,shader(gl.VERTEX_SHADER,'attribute vec2 point; attribute vec4 color; varying vec4 rgba; void main(){ gl_Position=vec4(point,0.,1.); rgba=color; }'));
      gl.attachShader(program,shader(gl.FRAGMENT_SHADER,'precision mediump float; varying vec4 rgba; void main(){ gl_FragColor=rgba; }'));
      gl.linkProgram(program); if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(program));
    } catch(error) { gl.deleteProgram(program); throw error; }
    finally { shaders.forEach(s=>gl.deleteShader(s)); }
    this.gl=gl; this.glCanvas=canvas; this.program=program; this.buffer=gl.createBuffer();
    gl.useProgram(program); gl.bindBuffer(gl.ARRAY_BUFFER,this.buffer);
    const position=gl.getAttribLocation(program,'point'),color=gl.getAttribLocation(program,'color');
    gl.enableVertexAttribArray(position);gl.vertexAttribPointer(position,2,gl.FLOAT,false,24,0);
    gl.enableVertexAttribArray(color);gl.vertexAttribPointer(color,4,gl.FLOAT,false,24,8);
    gl.enable(gl.BLEND);gl.blendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA);
    canvas.addEventListener('webglcontextlost',e=>{e.preventDefault();this.callbacks.unavailable('WebGL context lost; using Canvas');});
    canvas.addEventListener('webglcontextrestored',()=>{this.gl=null;this.program=null;this.buffer=null;});
  }
  resize() {
    this.width=Math.max(1,this.host.clientWidth); this.height=Math.max(1,this.host.clientHeight);
    this.ratio=Math.min(devicePixelRatio||1,2); this.engine.resize(this.width,this.height);
    for(const canvas of [this.canvas,this.glCanvas]) if(canvas) {canvas.width=Math.round(this.width*this.ratio);canvas.height=Math.round(this.height*this.ratio);}
    this.render();
  }
  render() {
    if (!this.hasCamera) return;
    if (this.backend==='svg') {
      const doc=new DOMParser().parseFromString(this.engine.svg(),'image/svg+xml');
      if(doc.querySelector('parsererror'))throw new Error('Invalid common SVG projection');
      this.output.replaceChildren(document.importNode(doc.documentElement,true)); return;
    }
    const data=this.engine.frame(this.backend==='gl'),c=this.context;
    c.setTransform(this.ratio,0,0,this.ratio,0,0);c.clearRect(0,0,this.width,this.height);
    if(this.backend==='gl') {
      const gl=this.gl;gl.viewport(0,0,gl.drawingBufferWidth,gl.drawingBufferHeight);gl.clearColor(235/255,239/255,240/255,1);gl.clear(gl.COLOR_BUFFER_BIT);
      gl.bufferData(gl.ARRAY_BUFFER,data.vertices,gl.DYNAMIC_DRAW);gl.drawArrays(gl.TRIANGLES,0,data.vertices.length/6);
      this.vertices=data.vertices.length/6;
    } else {c.fillStyle=data.background;c.fillRect(0,0,data.width,data.height);}
    for(const item of data.items) {
      c.save(); if(item.clip){c.beginPath();c.rect(...item.clip);c.clip();}
      if(item.type==='path'&&this.backend==='canvas') {
        c.beginPath();for(const [op,...args] of item.path){if(op==='M')c.moveTo(...args);else if(op==='L')c.lineTo(...args);else if(op==='Q')c.quadraticCurveTo(...args);else if(op==='C')c.bezierCurveTo(...args);else if(op==='Z')c.closePath();}
        if(item.fill){c.fillStyle=item.fill;c.fill();}if(item.stroke){c.strokeStyle=item.stroke;c.lineWidth=item.width;c.setLineDash(item.dash);c.lineDashOffset=item.dashOffset;c.stroke();}
      } else if(item.type==='text') {c.font=`${item.size}px ${item.font}`;c.fillStyle=item.color;c.fillText(item.text,item.position[0],item.position[1],item.maxWidth||10000);}
      c.restore();
    }
  }
  destroy() {this.resizeObserver.disconnect();for(const [event,handler]of Object.entries(this.handlers))this.host.removeEventListener(event,handler);if(this.gl){this.gl.deleteBuffer(this.buffer);this.gl.deleteProgram(this.program);}this.output.remove();}
}

window.SpaceGraphRenderer=SpatialRenderer;
