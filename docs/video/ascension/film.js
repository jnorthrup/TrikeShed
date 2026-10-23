// Ascension — a deterministic canvas film. Film.frame(T) paints second T.
// Every level is drawn in its own frame; the level below lives inside it at a similarity placement, and the
// camera flies a logarithmic spiral from one frame's full view to the next. The last frame collapses to the
// dot the first frame opens on, so the film loops.
(function () {
  'use strict';
  const W = 1920, H = 1080, S = 540, TAU = Math.PI * 2;
  const C = {
    BLUE: '#58C4DD', BLUE_E: '#1C758A', TEAL: '#5CD0B3', GREEN: '#83C167', YELLOW: '#FFE45E', GOLD: '#F0AC5F',
    ORANGE: '#FF862F', RED: '#FC6255', PURPLE: '#9A72AC', PINK: '#D147BD', WHITE: '#FFFFFF', GREY: '#8A8F98',
  };
  const FONT = {
    math: 'KaTeXMath, KaTeXMain, KaTeXAMS, serif',
    main: 'KaTeXMain, KaTeXAMS, KaTeXMath, serif',
    mono: "'JetBrains Mono', 'DejaVu Sans Mono', monospace",
  };
  const MATHCH = /[αλ↻⋈∘⇄≠←→·]/;

  // ── numbers ───────────────────────────────────────────────────
  const clamp = (x, a = 0, b = 1) => x < a ? a : x > b ? b : x;
  const lerp = (a, b, t) => a + (b - a) * t;
  const sm = x => { x = clamp(x); return x * x * x * (x * (x * 6 - 15) + 10); };
  const seg = (u, a, b) => sm((u - a) / (b - a));
  const backOut = x => { x = clamp(x); const c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * (x - 1) ** 3 + c1 * (x - 1) ** 2; };
  const pop = (u, a, d = 0.35) => u <= a ? 0 : backOut((u - a) / d);
  const tnb = x => x <= 0 || x >= 1 ? 0 : Math.sin(Math.PI * x);
  const frac = x => x - Math.floor(x);
  function rng(seed) {
    let s = seed >>> 0;
    return () => { s = (s + 0x6D2B79F5) >>> 0; let t = s; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  }

  // ── affine maps [a b c d e f]: x' = a x + c y + e, y' = b x + d y + f ──
  const mul = (M, N) => [M[0] * N[0] + M[2] * N[1], M[1] * N[0] + M[3] * N[1], M[0] * N[2] + M[2] * N[3], M[1] * N[2] + M[3] * N[3],
    M[0] * N[4] + M[2] * N[5] + M[4], M[1] * N[4] + M[3] * N[5] + M[5]];
  const place = (x, y, r = 1, phi = 0) => { const c = Math.cos(phi) * r, s = Math.sin(phi) * r; return [c, s, -s, c, x, y]; };
  const ap = (M, x, y) => [M[0] * x + M[2] * y + M[4], M[1] * x + M[3] * y + M[5]];
  // place a frame so that its point (cx, cy) lands on (x, y)
  const at = (x, y, r, phi, [cx, cy]) => place(x - r * (Math.cos(phi) * cx - Math.sin(phi) * cy), y - r * (Math.sin(phi) * cx + Math.cos(phi) * cy), r, phi);
  const about = (x, y, s) => place(x * (1 - s), y * (1 - s), s, 0);

  // complex numbers as [re, im]
  const cmul = (a, b) => [a[0] * b[0] - a[1] * b[1], a[0] * b[1] + a[1] * b[0]];
  const cdiv = (a, b) => { const d = b[0] * b[0] + b[1] * b[1]; return [(a[0] * b[0] + a[1] * b[1]) / d, (a[1] * b[0] - a[0] * b[1]) / d]; };
  const cadd = (a, b) => [a[0] + b[0], a[1] + b[1]];
  const csub = (a, b) => [a[0] - b[0], a[1] - b[1]];
  const cexp = a => { const e = Math.exp(a[0]); return [e * Math.cos(a[1]), e * Math.sin(a[1])]; };
  const clog = a => [Math.log(Math.hypot(a[0], a[1])), Math.atan2(a[1], a[0])];

  // ── shapes ────────────────────────────────────────────────────
  const ngon = (n, r, rot = -Math.PI / 2, cx = 0, cy = 0) => Array.from({ length: n }, (_, i) => [cx + r * Math.cos(rot + TAU * i / n), cy + r * Math.sin(rot + TAU * i / n)]);
  const starPts = (n, r1, r2, rot = -Math.PI / 2) => Array.from({ length: 2 * n }, (_, i) => { const r = i % 2 ? r2 : r1, a = rot + Math.PI * i / n; return [r * Math.cos(a), r * Math.sin(a)]; });
  function resample(pts, N) {
    const P = pts.concat([pts[0]]), L = [0];
    for (let i = 1; i < P.length; i++) L.push(L[i - 1] + Math.hypot(P[i][0] - P[i - 1][0], P[i][1] - P[i - 1][1]));
    const out = [], tot = L[L.length - 1];
    for (let k = 0, j = 1; k < N; k++) {
      const s = tot * k / N;
      while (L[j] < s) j++;
      const t = (s - L[j - 1]) / (L[j] - L[j - 1] || 1);
      out.push([lerp(P[j - 1][0], P[j][0], t), lerp(P[j - 1][1], P[j][1], t)]);
    }
    return out;
  }
  function rrectPts(cx, cy, hw, hh, r) {
    const pts = [];
    for (const [x, y, a0] of [[cx + hw - r, cy - hh + r, -Math.PI / 2], [cx + hw - r, cy + hh - r, 0], [cx - hw + r, cy + hh - r, Math.PI / 2], [cx - hw + r, cy - hh + r, Math.PI]])
      for (let k = 0; k <= 4; k++) { const a = a0 + Math.PI / 2 * k / 4; pts.push([x + r * Math.cos(a), y + r * Math.sin(a)]); }
    return pts;
  }
  const ellPts = (cx, cy, rx, ry, rot, a0, a1, n = 48) => Array.from({ length: n + 1 }, (_, i) => {
    const a = lerp(a0, a1, i / n), x = rx * Math.cos(a), y = ry * Math.sin(a);
    return [cx + x * Math.cos(rot) - y * Math.sin(rot), cy + x * Math.sin(rot) + y * Math.cos(rot)];
  });

  // ── drawing context: world units in, pixels out ───────────────
  class G {
    constructor(ctx) { this.ctx = ctx; this.M = [1, 0, 0, 1, 0, 0]; this.a = 1; this.tint = null; this.st = []; }
    push(N, a = 1, tint) { this.st.push([this.M, this.a, this.tint]); this.M = mul(this.M, N); this.a *= a; if (tint !== undefined) this.tint = tint; }
    pop() { [this.M, this.a, this.tint] = this.st.pop(); }
    get s() { const M = this.M; return Math.sqrt(Math.abs(M[0] * M[3] - M[1] * M[2])); }
    col(c) { return this.tint || c; }
    set() { const M = this.M; this.ctx.setTransform(M[0], M[1], M[2], M[3], M[4], M[5]); }
    // a sub-pixel stroke fades with its coverage instead of vanishing
    stroke(c, w, a) {
      const px = w * this.s, ctx = this.ctx;
      if (px < 1) { ctx.lineWidth = 1 / this.s; ctx.globalAlpha = clamp(a * this.a * Math.max(px, 0.04)); }
      else { ctx.lineWidth = w; ctx.globalAlpha = clamp(a * this.a); }
      ctx.strokeStyle = this.col(c);
    }
    line(x1, y1, x2, y2, o = {}) {
      const { c = C.WHITE, w = 0.006, a = 1, k = 1, dash = null } = o;
      if (a * this.a <= 0.004 || k <= 0) return;
      const ctx = this.ctx; this.set(); this.stroke(c, w, a); ctx.lineCap = 'round';
      if (dash) ctx.setLineDash(dash);
      ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(lerp(x1, x2, k), lerp(y1, y2, k)); ctx.stroke();
      if (dash) ctx.setLineDash([]);
    }
    poly(pts, o = {}) {
      const { c = C.WHITE, w = 0.006, a = 1, k = 1, closed = false, fill = null, fa = 0.2, dash = null } = o;
      if (a * this.a <= 0.004 || k <= 0 || pts.length < 2) return;
      const ctx = this.ctx; this.set();
      if (fill && fa > 0 && k >= 1) {
        ctx.globalAlpha = clamp(fa * a * this.a); ctx.fillStyle = this.col(fill);
        ctx.beginPath(); pts.forEach((p, i) => i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])); ctx.closePath(); ctx.fill();
      }
      if (!(w > 0) || !c) return;
      this.stroke(c, w, a); ctx.lineJoin = 'round'; ctx.lineCap = 'round';
      if (dash) ctx.setLineDash(dash);
      ctx.beginPath();
      if (k >= 1) { pts.forEach((p, i) => i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])); if (closed) ctx.closePath(); }
      else {
        const P = closed ? pts.concat([pts[0]]) : pts, L = [];
        let tot = 0;
        for (let i = 1; i < P.length; i++) { const d = Math.hypot(P[i][0] - P[i - 1][0], P[i][1] - P[i - 1][1]); L.push(d); tot += d; }
        let rem = tot * k; ctx.moveTo(P[0][0], P[0][1]);
        for (let i = 1; i < P.length && rem > 0; i++) {
          const d = L[i - 1];
          if (d <= rem) { ctx.lineTo(P[i][0], P[i][1]); rem -= d; }
          else { const t = rem / d; ctx.lineTo(lerp(P[i - 1][0], P[i][0], t), lerp(P[i - 1][1], P[i][1], t)); rem = 0; }
        }
      }
      ctx.stroke();
      if (dash) ctx.setLineDash([]);
    }
    circle(x, y, r, o = {}) {
      const { c = C.WHITE, w = 0.006, a = 1, k = 1, fill = null, fa = 1, a0 = -Math.PI / 2, dash = null } = o;
      if (a * this.a <= 0.004 || k <= 0 || r <= 0) return;
      const ctx = this.ctx, pr = r * this.s; this.set();
      if (pr < 0.6) {
        if (!fill && !c) return;
        ctx.globalAlpha = clamp(a * this.a * (fill ? fa : 0.5) * Math.max(pr / 0.6, 0.15) ** 2 * 1.5);
        ctx.fillStyle = this.col(fill || c); const q = 0.6 / this.s; ctx.fillRect(x - q, y - q, 2 * q, 2 * q); return;
      }
      if (fill && fa > 0) { ctx.globalAlpha = clamp(fa * a * this.a * Math.min(1, k * 1.5)); ctx.fillStyle = this.col(fill); ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.fill(); }
      if (w > 0 && c) {
        this.stroke(c, w, a); if (dash) ctx.setLineDash(dash);
        ctx.beginPath(); ctx.arc(x, y, r, a0, a0 + TAU * k); ctx.stroke();
        if (dash) ctx.setLineDash([]);
      }
    }
    dot(x, y, r, c, a = 1) { this.circle(x, y, r, { c: null, w: 0, fill: c, fa: 1, a }); }
    // text sized in world units; Greek and math symbols in a mono string are set in Computer Modern
    text(str, x, y, o = {}) {
      const { size = 0.08, font = 'math', c = C.WHITE, a = 1, align = 'center', k = 1 } = o;
      if (a * this.a <= 0.004 || k <= 0) return;
      const s = this.s, px = size * s; if (px < 3.2) return;
      const M = this.M, [X, Y] = ap(M, x, y), ctx = this.ctx;
      ctx.setTransform(M[0] / s, M[1] / s, M[2] / s, M[3] / s, X, Y);
      ctx.textBaseline = 'middle'; ctx.textAlign = 'left';
      const runs = [];
      if (font === 'mono') { let cur = null; for (const ch of str) { const f = MATHCH.test(ch) ? 'math' : 'mono'; if (!cur || cur[1] !== f) runs.push(cur = ['', f]); cur[0] += ch; } }
      else runs.push([str, font]);
      let wd = 0;
      for (const r of runs) { ctx.font = `${px.toFixed(2)}px ${FONT[r[1]]}`; r[2] = ctx.measureText(r[0]).width; wd += r[2]; }
      const x0 = align === 'center' ? -wd / 2 : align === 'right' ? -wd : 0;
      const alpha = clamp(a * this.a * clamp(px / 7 - 0.3));
      const paint = (stroke) => { let xx = x0; for (const r of runs) { ctx.font = `${px.toFixed(2)}px ${FONT[r[1]]}`; if (stroke) ctx.strokeText(r[0], xx, 0); else ctx.fillText(r[0], xx, 0); xx += r[2]; } };
      ctx.fillStyle = ctx.strokeStyle = this.col(c);
      if (k >= 1) { ctx.globalAlpha = alpha; paint(false); return; }
      // Write: an outline sweeps across, the fill follows
      ctx.save(); ctx.beginPath(); ctx.rect(x0 - px, -px, (wd + 2 * px) * clamp(k * 1.5), 2 * px); ctx.clip();
      ctx.globalAlpha = alpha * (1 - clamp((k - 0.55) / 0.45)); ctx.lineWidth = Math.max(0.8, px / 30); paint(true);
      ctx.restore();
      ctx.globalAlpha = alpha * clamp((k - 0.3) / 0.7); paint(false);
    }
  }

  // ── small vocabulary of marks ────────────────────────────────
  function bowtie(g, x, y, hw, hh, o = {}) {
    const { c = C.WHITE, w = 0.006, k = 1, a = 1, fa = 0.12 } = o;
    g.poly([[x - hw, y - hh], [x - hw, y + hh], [x, y]], { c, w, k, a, closed: true, fill: c, fa });
    g.poly([[x + hw, y - hh], [x + hw, y + hh], [x, y]], { c, w, k, a, closed: true, fill: c, fa });
  }
  function arrowHead(g, x, y, ang, s, c, a = 1) {
    g.poly([[x + s * Math.cos(ang), y + s * Math.sin(ang)], [x + 0.62 * s * Math.cos(ang + 2.45), y + 0.62 * s * Math.sin(ang + 2.45)], [x + 0.62 * s * Math.cos(ang - 2.45), y + 0.62 * s * Math.sin(ang - 2.45)]],
      { c, w: 0, closed: true, fill: c, fa: 1, a });
  }
  function flash(g, x, y, r, p, c, n = 12) {
    if (p <= 0 || p >= 1) return;
    for (let i = 0; i < n; i++) {
      const an = TAU * i / n, r0 = r * (0.45 + 0.75 * p), r1 = r * (0.75 + 1.0 * p);
      g.line(x + r0 * Math.cos(an), y + r0 * Math.sin(an), x + r1 * Math.cos(an), y + r1 * Math.sin(an), { c, w: r * 0.06, a: (1 - p) * Math.min(1, p * 6) });
    }
  }
  function keyGlyph(g, x, y, s, rot, c, a = 1) {
    g.push(place(x, y, s, rot));
    g.circle(-0.42, 0, 0.24, { c, w: 0.1, a });
    g.line(-0.18, 0, 0.58, 0, { c, w: 0.1, a });
    g.line(0.3, 0, 0.3, 0.22, { c, w: 0.1, a });
    g.line(0.5, 0, 0.5, 0.28, { c, w: 0.1, a });
    g.pop();
  }

  // ── level registry and the recursive painter ─────────────────
  const LV = [];
  const def = o => { o.view = Object.assign({ x: 0, y: 0, v: 1 }, o.view || {}); o.tinyAt = o.tinyAt || 40; LV.push(o); return o; };
  const origin = [];
  let canvasW = W, canvasH = H, latticeGain = 1;

  function lattice(g, L) {
    const s = g.s, vis = clamp((s - 170) / 260) * clamp((3400 - s) / 1700) * latticeGain;
    if (vis <= 0.01) return;
    const ctx = g.ctx, R = L.R * 1.7, sp = 0.25, cx = L.cx, cy = L.cy;
    ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.fillStyle = '#3d6f86';
    const n = Math.ceil(R / sp);
    for (let i = -n; i <= n; i++) for (let j = -n; j <= n; j++) {
      const x = Math.round(cx / sp) * sp + i * sp, y = Math.round(cy / sp) * sp + j * sp, d = Math.hypot(x - cx, y - cy) / R;
      if (d >= 1) continue;
      const [X, Y] = ap(g.M, x, y);
      if (X < -4 || Y < -4 || X > canvasW + 4 || Y > canvasH + 4) continue;
      ctx.globalAlpha = 0.3 * vis * (1 - d * d) * g.a;
      ctx.fillRect(X - 1.1, Y - 1.1, 2.2, 2.2);
    }
  }

  const sub = (k, st) => st.hero ? { u: st.T - origin[k], T: st.T, hero: true } : { u: 1e6, T: st.T, hero: false };
  function drawLevel(k, g, st) {
    const L = LV[k], px = g.s * L.R;
    if (px < 0.7 || g.a <= 0.004) return;
    const [X, Y] = ap(g.M, L.cx, L.cy), rr = px * 1.15;
    if (X + rr < 0 || X - rr > canvasW || Y + rr < 0 || Y - rr > canvasH) return;
    if (px < 5) { const r = Math.min(px, 3.2) * 0.5 / g.s; g.dot(L.cx, L.cy, r, L.color, clamp(px / 5) * 0.9); return; }
    if (px < L.tinyAt) { L.tiny(g, st); return; }
    if (st.hero) lattice(g, L);
    if (L.draw) L.draw(g, st);
    if (L.place) {
      g.push(L.place(st.T), L.heroAlpha ? L.heroAlpha(st.u) : 1);
      drawLevel(k - 1, g, sub(k - 1, st));
      g.pop();
    }
    if (L.over) L.over(g, st);
  }
  function inst(g, k, M, T, a = 1) { g.push(M, a); drawLevel(k, g, { u: 1e6, T, hero: false }); g.pop(); }
  const center = k => [LV[k].cx, LV[k].cy];

  // ══ 0 · Join ══════════════════════════════════════════════════
  {
    const J = { b: 0.9, d0: 1.0, d1: 3.2, bow: 3.2, lab: 3.9, hint: 4.5 };
    const pos = u => {
      if (u < J.d0) return [0, 0, 0.6, 0];
      const s = sm((u - J.d0) / (J.d1 - J.d0)), mx = lerp(0.3, 0, s), th = 4 * Math.PI * s, rho = 0.3 + 0.12 * Math.sin(Math.PI * s);
      const c = Math.cos(th) * rho, sn = Math.sin(th) * rho;
      return [mx - c, -sn, mx + c, sn];
    };
    def({
      name: 'Join', color: C.WHITE, R: 0.6, cx: 0, cy: 0.08, dwell: 6.0, mult: 1,
      draw(g, { u, T }) {
        if (u > 0.15 && u < 1.05) { const q = (u - 0.15) / 0.9; g.circle(0, 0, 0.035 + 0.3 * sm(q), { c: C.BLUE, w: 0.004, a: 0.8 * (1 - q) }); }
        if (u > J.d0 && u < J.d1 + 0.5) {
          const fade = clamp((J.d1 + 0.5 - u) / 0.5);
          for (let j = 10; j >= 1; j--) {
            const Q = pos(u - j * 0.03), fa = fade * 0.4 * (1 - j / 11), r = 0.03 * (1 - j / 14);
            g.dot(Q[0], Q[1], r, C.BLUE, fa); g.dot(Q[2], Q[3], r, C.YELLOW, fa);
          }
        }
        const kb = seg(u, J.bow, J.bow + 0.6);
        if (kb > 0) bowtie(g, 0, 0, 0.24, 0.11, { k: kb, fa: 0.12 });
        const P = pos(u), br = u > 4.6 ? 1 + 0.05 * Math.sin(T * 2.2) : 1;
        g.dot(P[0], P[1], 0.035 * br, C.BLUE);
        const pb = pop(u, J.b, 0.45);
        if (pb > 0) g.dot(P[2], P[3], 0.035 * pb * br, C.YELLOW);
        flash(g, P[2], P[3], 0.12, (u - J.b) / 0.6, C.YELLOW);
        g.text('a', -0.3, 0.14, { size: 0.085, c: C.BLUE, k: seg(u, J.lab, J.lab + 0.6) });
        g.text('b', 0.3, 0.14, { size: 0.085, c: C.YELLOW, k: seg(u, J.lab + 0.12, J.lab + 0.72) });
        g.text('a j b', 0, 0.31, { font: 'mono', size: 0.05, c: C.GREY, k: seg(u, J.hint, J.hint + 0.8) });
      },
      tiny(g) { g.line(-0.3, 0, 0.3, 0, { w: 0.03, a: 0.5 }); g.dot(-0.3, 0, 0.07, C.BLUE); g.dot(0.3, 0, 0.07, C.YELLOW); },
      ev: [[0, 'bell', 0, 0.2, 0], [J.b, 'pluck', 4, 0.45, 0.3], [1.5, 'pluck', 5, 0.16, -0.2], [2.0, 'pluck', 7, 0.16, 0.2], [2.5, 'pluck', 6, 0.16, -0.2],
        [3.0, 'pluck', 8, 0.18, 0.2], [3.5, 'chord', 0, 0.4, 0], [3.95, 'pluck', 7, 0.2, -0.3], [4.1, 'pluck', 9, 0.2, 0.3]],
    });
  }

  // ══ 1 · Series: size j λ, a row the λ answers on read ═════════
  {
    const R1 = 0.42, X1 = -1.2, cellX = i => -0.72 + 0.29 * i, CH = 0.115, H0 = 1.5, HD = 0.34;
    const arrive = i => H0 + HD * i, lamX = X1 + 0.3 * R1;
    const headX = u => { if (u <= H0) return cellX(0); const f = (u - H0) / HD, i = Math.floor(f); return i >= 7 ? cellX(7) : lerp(cellX(i), cellX(i + 1), sm((f - i - 0.45) / 0.55)); };
    const GR = Array.from({ length: 11 }, (_, n) => {
      const out = []; if (!n) return out;
      const sp = 0.19 / n, rr = Math.min(0.028, sp * 0.34);
      for (let a = 0; a < n; a++) for (let b = 0; b < n; b++) out.push([(a - (n - 1) / 2) * sp, (b - (n - 1) / 2) * sp, rr]);
      return out;
    });
    def({
      name: 'Series', color: C.BLUE, R: 1.6, cx: 0.1, cy: 0.05, zoom: 2.2, dwell: 4.8, mult: 9, hint: ['size j { i -> i * i }', 4.0], view: { x: 0.05, y: 0.05 },
      place: () => place(X1, 0, R1, 0),
      draw(g, { u }) {
        g.text('8', X1 - 0.3 * R1, -0.105, { size: 0.075, c: C.BLUE, k: seg(u, 0.05, 0.55) });
        g.text('λ', lamX, -0.105, { size: 0.075, c: C.YELLOW, k: seg(u, 0.25, 0.75) });
        for (let i = 0; i < 11; i++) {
          const x = cellX(i), ghost = i >= 8, ga = ghost ? [0.35, 0.2, 0.1][i - 8] : 1;
          const k = seg(u, 0.35 + 0.05 * i, 0.8 + 0.05 * i), d = u - arrive(i);
          const lit = !ghost && d >= 0 && d < 0.55, done = !ghost && d >= 0.55;
          g.poly(rrectPts(x, 0, CH, CH, 0.03), { c: lit ? C.YELLOW : C.GREY, w: lit ? 0.007 : 0.004, a: ga * (lit ? 1 : done ? 0.75 : 0.5), k, closed: true, dash: lit || done ? null : [0.02, 0.015] });
          const pts = GR[i];
          if (!ghost && d >= 0) pts.forEach((p, m) => { const q = pop(d, m * 0.22 / Math.max(1, pts.length), 0.25); if (q > 0) g.dot(x + p[0], p[1], p[2] * q, C.BLUE, d < 0.55 ? 1 : 0.6); });
          if (ghost) { const gk = seg(u, 4.3 + 0.15 * (i - 8), 4.9 + 0.15 * (i - 8)); if (gk > 0) pts.forEach(p => g.dot(x + p[0], p[1], p[2], C.BLUE, ga * 0.5 * gk)); }
        }
        g.line(1.455, -0.19, 1.455, 0.19, { c: C.BLUE, w: 0.005, a: 0.8, k: seg(u, 1.0, 1.4), dash: [0.025, 0.02] });
        const ha = seg(u, 1.25, 1.5) * (1 - seg(u, 4.4, 4.9));
        if (ha > 0) {
          const hx = headX(u);
          g.line(lamX + 0.015, 0, hx, -0.15, { c: C.YELLOW, w: 0.003, a: 0.45 * ha });
          g.poly([[hx, -0.135], [hx - 0.032, -0.2], [hx + 0.032, -0.2]], { c: C.YELLOW, w: 0.004, a: ha, closed: true, fill: C.YELLOW, fa: 0.9 });
        }
      },
      tiny(g) { g.line(X1 - 0.126, 0, X1 + 0.126, 0, { w: 0.03, a: 0.5 }); for (let i = 0; i < 8; i++) g.poly(rrectPts(cellX(i), 0, CH, CH, 0.03), { c: C.BLUE, w: 0.02, a: 0.6, closed: true }); },
      ev: [[0.05, 'pluck', 2, 0.16, -0.6], [0.25, 'pluck', 4, 0.16, -0.55], ...Array.from({ length: 8 }, (_, i) => [arrive(i), 'pluck', i + 2, 0.3, -0.4 + 0.11 * i]),
        [4.3, 'pluck', 12, 0.07, 0.6], [4.45, 'pluck', 13, 0.06, 0.65], [4.6, 'pluck', 14, 0.05, 0.7]],
    });
  }

  // ══ 2 · α: projections climb the row, then fuse ══════════════
  {
    const R2 = 0.3, X2 = 0, Y2 = 0.6, cellX = i => -0.72 + 0.29 * i, rowY = j => -0.62 * j, SN = 48, FU = 3.55;
    const COLS = [C.BLUE, C.TEAL, C.GREEN, C.YELLOW, C.GOLD, C.RED];
    const shapeAt = (j, i) => {
      const r = 0.03 + 0.011 * i;
      const p = j === 0 ? ngon(SN, 0.012) : j === 1 ? ngon(SN, r) : j === 2 ? ngon(3, r * 1.15) : j === 3 ? ngon(4, r * 1.1, -Math.PI / 4) : j === 4 ? ngon(5, r * 1.05) : starPts(5, r * 1.2, r * 0.5);
      return resample(p, SN);
    };
    const SH = Array.from({ length: 6 }, (_, j) => Array.from({ length: 8 }, (_, i) => shapeAt(j, i)));
    const B = j => 0.25 + 0.62 * (j - 1);
    const E2 = place(X2, Y2, R2, 0);
    def({
      name: 'α', color: C.TEAL, R: 0.72, cx: 0.17, cy: 0.18, zoom: 2.2, dwell: 4.4, mult: 6, hint: ['xs α f α g ≡ xs α (g ∘ f)', 3.9], view: { x: 0.12, y: 0.15 },
      place: () => E2,
      draw(g, { u, T }) {
        g.push(E2);
        const fz = seg(u, FU, FU + 0.6);
        for (let j = 1; j <= 5; j++) {
          const b = B(j), mid = j < 5;
          for (let i = 0; i < 8; i++) {
            const x = cellX(i), off = 0.025 * i;
            g.line(x, rowY(j - 1) - 0.12, x, rowY(j) + 0.12, { c: COLS[j], w: 0.006, a: 0.5 * lerp(1, 0.3, fz), k: seg(u, b + off, b + 0.3 + off) });
            g.poly(rrectPts(x, rowY(j), 0.115, 0.115, 0.03), { c: C.GREY, w: 0.004, a: 0.35 * seg(u, b + 0.3 + off, b + 0.6 + off) * (mid ? lerp(1, 0.4, fz) : 1), closed: true, dash: [0.02, 0.015] });
            const p = seg(u, b + 0.12 + off, b + 0.52 + off);
            if (p > 0) {
              const y = lerp(rowY(j - 1), rowY(j), p), A = SH[j - 1][i], Bp = SH[j][i];
              const spin = j === 5 ? 0.5 * T : 0, cs = Math.cos(spin), sn = Math.sin(spin);
              const pts = A.map((q, m) => { const px = lerp(q[0], Bp[m][0], p), py = lerp(q[1], Bp[m][1], p); return [x + px * cs - py * sn, y + px * sn + py * cs]; });
              g.poly(pts, { c: COLS[j], w: 0.006, closed: true, fill: COLS[j], fa: 0.35, a: mid ? lerp(1, 0.42, fz) : 1 });
            }
          }
          g.text('α', cellX(0) - 0.33, (rowY(j - 1) + rowY(j)) / 2, { size: 0.15, c: C.PURPLE, k: seg(u, b, b + 0.4), a: lerp(1, 0.4, fz) });
        }
        for (let i = 0; i < 8; i++) g.line(cellX(i), rowY(0) - 0.12, cellX(i), rowY(5) + 0.12, { c: C.PURPLE, w: 0.008, a: 0.75, k: seg(u, FU + 0.2 + 0.03 * i, FU + 0.8 + 0.03 * i) });
        g.text('g ∘ f', cellX(7) + 0.55, rowY(2.5), { size: 0.26, c: C.PURPLE, k: seg(u, FU + 0.5, FU + 1.1) });
        g.pop();
      },
      tiny(g) { for (let j = 0; j < 6; j++) g.line(X2 + R2 * cellX(0), Y2 + R2 * rowY(j), X2 + R2 * cellX(7), Y2 + R2 * rowY(j), { c: COLS[j], w: 0.05, a: 0.7 }); },
      ev: [...[1, 2, 3, 4, 5].flatMap(j => [[B(j) + 0.52, 'pluck', 2 * j, 0.24, -0.2], [B(j) + 0.6, 'pluck', 2 * j + 2, 0.18, 0.2]]),
        ...Array.from({ length: 8 }, (_, i) => [FU + 0.2 + 0.07 * i, 'pluck', 10 + i, 0.1, -0.5 + 0.14 * i]), [FU + 0.7, 'bell', 7, 0.22, 0.4]],
    });
  }

  // ══ 3 · ↻: a shadow of a shadow is the shadow ════════════════
  {
    const R3 = 0.62, E3 = place(-0.5934, 0.0263, R3, 0), SUN = [-1.45, -0.72], SHADE = '#C9B3FF';
    const SHM = k => [1, 0, -1.3 * k, 1 - 0.5 * k, 0.546 * k, 0.21 * k];
    const shp = p => [p[0] - 1.3 * p[1] + 0.546, 0.5 * p[1] + 0.21];
    const COLX = i => -0.5934 + R3 * 0.3 * (-0.72 + 0.29 * i), COLTOP = 0.0263 + R3 * (0.6 - 0.3 * 3.1);
    const tops = [[-0.748, -0.201], [-0.327, -0.201]];
    const arc = (T, ka, n = 64) => { const a0 = 2.25 + 0.35 * T, pts = []; for (let i = 0; i <= n; i++) { const a = a0 + 5.3 * ka * i / n; pts.push([-0.2 + 1.3 * Math.cos(a), 0.06 + 0.94 * Math.sin(a)]); } return pts; };
    const ring = (g, T, ka, w) => { const pts = arc(T, ka); g.poly(pts, { c: C.GREEN, w }); const e = pts[pts.length - 1], q = pts[pts.length - 2]; arrowHead(g, e[0], e[1], Math.atan2(e[1] - q[1], e[0] - q[0]), w * 5.5, C.GREEN); };
    def({
      name: '↻', color: C.GREEN, R: 1.42, cx: -0.2, cy: 0.06, zoom: 2.2, dwell: 4.2, mult: 2, view: { x: -0.15, y: 0.03 }, hint: ['existingRev == rev → no-op', 3.2],
      place: () => E3,
      draw(g, st) {
        const { u } = st, kf = seg(u, 0, 0.7);
        g.line(-1.55, 0.42, 1.45, 0.42, { c: C.GREY, w: 0.005, k: kf });
        for (let r = 1; r <= 3; r++) g.line(-1.55 + 0.14 * r, 0.42 - 0.11 * r, 1.45 + 0.14 * r, 0.42 - 0.11 * r, { c: C.BLUE_E, w: 0.003, a: 0.7 / r, k: kf });
        for (let x = -1.4; x <= 1.3; x += 0.3) g.line(x, 0.42, x + 0.44, 0.08, { c: C.BLUE_E, w: 0.003, a: 0.45, k: kf });
        const kg = seg(u, 1.4, 1.9);
        if (kg > 0) g.poly([shp([-0.76, 0.42]), shp([-0.31, 0.42]), shp([-0.31, -0.21]), shp([-0.76, -0.21])], { c: null, w: 0, closed: true, fill: '#9A72AC', fa: 0.14 * kg });
        const k1 = seg(u, 0.9, 1.8);
        for (let i = 0; i < 8; i++) { const a = [COLX(i), COLTOP], b = shp(a), kl = seg(u, 0.95 + 0.04 * i, 1.6 + 0.04 * i); if (kl > 0) g.line(a[0], a[1], b[0], b[1], { c: '#C9B3FF', w: 0.0025, a: 0.55 * (1 - 0.5 * seg(u, 2.0, 2.6)), k: kl, dash: [0.012, 0.01] }); }
        if (k1 > 0) { g.push(mul(SHM(k1), E3), 0.72, SHADE); drawLevel(2, g, sub(2, st)); g.pop(); }
        const k2 = tnb((u - 2.35) / 0.7);
        if (k2 > 0) { g.push(mul(SHM(1), E3), 0.75 * k2, C.WHITE); drawLevel(2, g, sub(2, st)); g.pop(); }
      },
      over(g, { u, T }) {
        const ks = pop(u, 0.3, 0.5);
        if (ks > 0) {
          g.dot(SUN[0], SUN[1], 0.065 * ks, C.YELLOW);
          for (let i = 0; i < 10; i++) { const a = T * 0.4 + i * TAU / 10; g.line(SUN[0] + 0.095 * ks * Math.cos(a), SUN[1] + 0.095 * ks * Math.sin(a), SUN[0] + 0.14 * ks * Math.cos(a), SUN[1] + 0.14 * ks * Math.sin(a), { c: C.YELLOW, w: 0.006 }); }
        }
        const kr = seg(u, 0.8, 1.4), pulse = 0.45 + 0.55 * tnb((u - 2.35) / 0.7);
        for (const tp of tops) {
          g.line(SUN[0], SUN[1], tp[0], tp[1], { c: C.YELLOW, w: 0.003, a: 0.5 * pulse, k: kr });
          const sp = shp(tp); g.line(tp[0], tp[1], sp[0], sp[1], { c: C.YELLOW, w: 0.003, a: 0.5 * pulse, k: seg(u, 1.1, 1.8), dash: [0.02, 0.015] });
        }
        g.text('P', 0.72, 0.1, { size: 0.11, c: SHADE, k: seg(u, 1.6, 2.0) });
        flash(g, -0.1, 0.26, 0.4, (u - 2.6) / 0.7, C.WHITE);
        g.text('P ∘ P = P', 0.3, 0.7, { size: 0.12, k: seg(u, 3.0, 3.7) });
        const ka = seg(u, 3.3, 4.2);
        if (ka > 0) ring(g, T, ka, 0.014);
      },
      tiny(g, { T }) { ring(g, T, 1, 0.04); g.line(-0.6, 0.38, 0.4, 0.3, { c: SHADE, w: 0.08, a: 0.6 }); },
      ev: [[0.4, 'pluck', 9, 0.18, -0.7], [1.0, 'pluck', 9, 0.16, -0.2], [1.2, 'pluck', 7, 0.16, 0], [1.4, 'pluck', 4, 0.16, 0.2], [1.8, 'chord', 2, 0.32, 0.1],
        [2.65, 'chord', 2, 0.32, 0.1], [3.2, 'pluck', 9, 0.2, 0.3], [3.5, 'pluck', 4, 0.14, 0.6], [3.8, 'pluck', 6, 0.14, 0], [4.1, 'pluck', 8, 0.16, -0.6]],
    });
  }

  // ══ 4 · Cursor: value ⋈ ↻, row by row ═════════════════════════
  {
    const xc = c => -0.99 + 0.66 * c, yr = r => -0.6 + 0.26 * r, MR = 0.072, MPHI = -0.35, L3c = center(3);
    const metaAt = (r, c) => at(xc(c) + 0.19, yr(r), MR, MPHI, L3c);
    const VC = [C.BLUE, C.TEAL, C.YELLOW, C.RED];
    const appear = (r, c) => 0.12 + 0.17 * (Math.abs(r - 2) + Math.abs(c - 2)) + 0.03 * c;
    const cells = [];
    for (let r = 0; r < 6; r++) for (let c = 0; c < 4; c++) cells.push([r, c]);
    def({
      name: 'Cursor', color: C.GREEN, R: 1.45, cx: 0, cy: 0.1, zoom: 2.6, dwell: 3.6, mult: 24, hint: ['Cursor = Series<RowVec>', 2.6], view: { x: 0, y: 0.06 },
      place: () => metaAt(2, 2),
      draw(g, { u, T }) {
        const ex = seg(u, 1.6, 2.2);
        for (const [r, c] of cells) {
          const q = pop(u, appear(r, c), 0.4);
          if (q <= 0) continue;
          const x = xc(c), y = yr(r), hero = r === 2 && c === 2;
          g.push(about(x, y, hero ? 1 : q), hero ? clamp(q) : 1);
          g.poly(rrectPts(x, y, 0.31, 0.11, 0.03), { c: C.GREY, w: 0.004, a: 0.45, closed: true });
          if (r === 0 && ex > 0) g.poly(rrectPts(x, y, 0.316, 0.116, 0.032), { c: C.GREEN, w: 0.007, k: ex, closed: true });
          g.dot(x - 0.2, y, 0.028 * (1 + 0.08 * Math.sin(T * 3 + r + c)), VC[c]);
          bowtie(g, x - 0.03, y, 0.085, 0.045, { w: 0.003, a: 0.75 });
          if (!hero) inst(g, 3, metaAt(r, c), T);
          g.pop();
        }
        const cs = seg(u, 2.3, 3.2);
        if (cs > 0) {
          const x0 = xc(2) - 0.335, x1 = xc(2) + 0.335, y0 = -0.75, y1 = lerp(y0, 0.82, cs);
          g.poly([[x0, y0], [x1, y0], [x1, y1], [x0, y1]], { c: null, w: 0, closed: true, fill: C.PURPLE, fa: lerp(0.13, 0.08, seg(u, 3.2, 3.7)) });
          g.line(x0, y1, x1, y1, { c: C.PURPLE, w: 0.006, a: 1 - seg(u, 3.15, 3.5) });
        }
        g.text('α', xc(2), -0.83, { size: 0.075, c: C.PURPLE, k: seg(u, 2.2, 2.7) });
      },
      tiny(g) { g.poly(rrectPts(0.0, 0.05, 1.36, 0.82, 0.08), { c: C.GREY, w: 0.05, a: 0.7, closed: true }); for (let r = 1; r < 6; r++) g.line(-1.3, yr(r) - 0.13, 1.3, yr(r) - 0.13, { c: C.GREY, w: 0.025, a: 0.35 }); for (const [r, c] of cells) g.dot(xc(c), yr(r), 0.07, r === 0 ? C.GREEN : VC[c], 0.85); },
      ev: [...cells.map(([r, c]) => [appear(r, c), 'pluck', Math.abs(r - 2) + Math.abs(c - 2) + 2 * (c % 2), 0.12, (xc(c)) / 2]),
        [1.7, 'bell', 7, 0.2, -0.3], ...[0, 1, 2, 3, 4, 5].map(r => [2.3 + 0.15 * r, 'pluck', 10 + r, 0.1, 0.1])],
    });
  }

  // ══ 5 · ISAM: fixed-width pages on a spinning spiral ══════════
  const ISAM = {};
  {
    const SA = 0.2, SB = 0.052, THMAX = 15.4, L4c = center(4);
    const ST = [[0, 0]];
    for (let k = 1, acc = 0; k <= 3000; k++) {
      const t0 = THMAX * (k - 1) / 3000, t1 = THMAX * k / 3000, r0 = SA + SB * t0, r1 = SA + SB * t1;
      acc += Math.hypot(r1 * Math.cos(t1) - r0 * Math.cos(t0), r1 * Math.sin(t1) - r0 * Math.sin(t0)); ST.push([t1, acc]);
    }
    const thAt = s => { let lo = 0, hi = ST.length - 1; while (hi - lo > 1) { const m = (lo + hi) >> 1; if (ST[m][1] < s) lo = m; else hi = m; } const t = (s - ST[lo][1]) / (ST[hi][1] - ST[lo][1]); return lerp(ST[lo][0], ST[hi][0], t); };
    const PAGES = [];
    for (let s = 0.32; s < ST[ST.length - 1][1] - 0.08; s += 0.135) PAGES.push(thAt(s));
    const HERO = Math.round(PAGES.length * 0.62), spin = T => 0.09 * T;
    const pagePlace = (m, T) => {
      const th = PAGES[m], r = SA + SB * th, w = spin(T), x = r * Math.cos(th), y = r * Math.sin(th);
      const ang = Math.atan2(SB * Math.sin(th) + r * Math.cos(th), SB * Math.cos(th) - r * Math.sin(th));
      const c = Math.cos(w), s = Math.sin(w);
      return at(c * x - s * y, s * x + c * y, 0.0425, ang + w, L4c);
    };
    const PV = [1.42, 0.86], rH = SA + SB * PAGES[HERO], psi = Math.atan2(PV[1], PV[0]) - 0.22;
    const TT = [rH * Math.cos(psi), rH * Math.sin(psi)], ARM = Math.hypot(TT[0] - PV[0], TT[1] - PV[1]), BT = Math.atan2(TT[1] - PV[1], TT[0] - PV[0]);
    const spiral = (T, n) => Array.from({ length: n + 1 }, (_, i) => { const th = THMAX * i / n, r = SA + SB * th, a = th + spin(T); return [r * Math.cos(a), r * Math.sin(a)]; });
    Object.assign(ISAM, { pages: PAGES.length });
    def({
      name: 'ISAM', color: C.GOLD, R: 1.6, cx: 0.12, cy: 0.06, zoom: 2.8, dwell: 3.6, mult: PAGES.length, hint: ['RecordMeta(name, type, begin, end)', 2.2], view: { x: 0.05, y: 0.02 },
      place: T => pagePlace(HERO, T),
      turn: T => { const a = PAGES[HERO] + Math.atan2(rH, SB); return a + spin(T) - TAU * Math.round((a + spin(origin[5])) / TAU); },
      draw(g, { u, T }) {
        const kd = seg(u, 0, 0.8), w = spin(T);
        for (let r = 0.25; r < 1.08; r += 0.055) g.circle(0, 0, r, { c: C.GOLD, w: 0.002, a: 0.1 * kd });
        for (let i = 0; i < 6; i++) { const a = w + i * TAU / 6; g.line(0.14 * Math.cos(a), 0.14 * Math.sin(a), 1.08 * Math.cos(a), 1.08 * Math.sin(a), { c: C.GOLD, w: 0.002, a: 0.07 * kd }); }
        g.circle(0, 0, 1.1, { c: C.GOLD, w: 0.008, a: 0.6, k: kd });
        g.circle(0, 0, 0.13, { c: C.GOLD, w: 0.006, a: 0.8, k: kd }); g.dot(0, 0, 0.03, C.GOLD, kd);
        g.poly(spiral(T, 400), { c: C.GOLD, w: 0.003, a: 0.35, k: seg(u, 0, 1.2) });
        for (let m = 0; m < PAGES.length; m++) {
          if (m === HERO) continue;
          const q = pop(u, 0.2 + 0.035 * Math.abs(m - HERO), 0.35);
          if (q > 0) inst(g, 4, mul(pagePlace(m, T), about(L4c[0], L4c[1], q)), T);
        }
      },
      over(g, { u, T }) {
        const sw = seg(u, 1.6, 2.4), b = BT + 0.75 * (1 - sw), tip = [PV[0] + ARM * Math.cos(b), PV[1] + ARM * Math.sin(b)];
        const ka = seg(u, 1.2, 1.6);
        if (ka > 0) {
          g.line(PV[0], PV[1], tip[0], tip[1], { c: C.GREY, w: 0.022, a: 0.85 * ka });
          g.circle(PV[0], PV[1], 0.06, { c: C.GREY, w: 0.01, fill: '#1b1d22', fa: 1, a: ka });
          g.dot(tip[0], tip[1], 0.022, C.YELLOW, ka);
        }
        if (sw >= 1) for (let m = 0; m < PAGES.length; m++) {
          const P = pagePlace(m, T), [x, y] = ap(P, L4c[0], L4c[1]), d = Math.hypot(x - tip[0], y - tip[1]);
          if (d < 0.08) { g.push(P); g.poly(rrectPts(L4c[0], L4c[1], 1.38, 0.9, 0.08), { c: C.YELLOW, w: 0.06, a: 1 - d / 0.08, closed: true }); g.pop(); }
        }
        g.text('offset = i · w + b', 1.38, -0.86, { font: 'math', size: 0.07, c: C.GOLD, align: 'right', k: seg(u, 2.5, 3.3) });
      },
      tiny(g, { T }) { g.circle(0, 0, 1.1, { c: C.GOLD, w: 0.04, a: 0.7 }); g.poly(spiral(T, 60), { c: C.GOLD, w: 0.03, a: 0.5 }); },
      ev(o) {
        const out = PAGES.map((_, m) => [0.2 + 0.035 * Math.abs(m - HERO), 'pluck', 5 + (Math.abs(m - HERO) % 7), 0.06, clamp(Math.cos(PAGES[m]) * 0.6, -0.6, 0.6)]);
        out.push([1.3, 'pluck', 0, 0.16, 0.7], [2.4, 'thump', 0, 0.25, 0.5]);
        PAGES.forEach((th, m) => {
          if (Math.abs(SA + SB * th - rH) > 0.06) return;
          for (let n = -3; n <= 3; n++) { const Tp = (psi + TAU * n - th) / 0.09, up = Tp - o; if (up > 2.4 && up < 9) out.push([up, 'tick', 0, 0.14, 0.5]); }
        });
        return out;
      },
    });
  }

  // ══ 6 · Confix: brackets → rainbows → tree ⇄ table ═══════════
  {
    const JS = '{"a":[1,{"b":[2,3]},[4]],"c":{"d":[5]}}', SZ = 0.068, CW = 0.6 * SZ, X0 = -1.62, TY = -0.2;
    const cx6 = i => X0 + (i + 0.5) * CW, DC = [C.BLUE, C.TEAL, C.GREEN, C.YELLOW, C.GOLD];
    const pairs = [], stack = [];
    for (let i = 0; i < JS.length; i++) { const ch = JS[i]; if (ch === '{' || ch === '[') stack.push([i, stack.length]); else if (ch === '}' || ch === ']') { const [o, d] = stack.pop(); pairs.push({ o, c: i, d }); } }
    pairs.sort((a, b) => a.o - b.o);
    pairs.forEach((p, k) => { p.k = k; p.parent = -1; for (let j = k - 1; j >= 0; j--) if (pairs[j].o < p.o && pairs[j].c > p.c) { p.parent = j; break; } });
    const depthOf = i => { let d = 0; for (const p of pairs) if (p.o < i && p.c > i) d++; return d; };
    pairs.forEach(p => { p.mx = (cx6(p.o) + cx6(p.c)) / 2; p.rx = (cx6(p.c) - cx6(p.o)) / 2; p.ry = 0.06 + 0.5 * p.rx; p.nx = p.mx; p.ny = 0.1 + 0.16 * p.d; p.ty = -0.5 + 0.12 * p.k; });
    const TX = c => 0.03 + 0.15 * c, DISK = [1.1, 0.25];
    const arch = p => ellPts(p.mx, TY - 0.06, p.rx, p.ry, 0, Math.PI, TAU, 40);
    def({
      name: 'Confix', color: C.BLUE, R: 1.62, cx: -0.1, cy: -0.05, zoom: 2.6, dwell: 4.6, mult: pairs.length, hint: ['JSON ⇄ ISAM · index.facet(Spans)', 3.9], view: { x: -0.1, y: -0.02 },
      place: () => at(DISK[0], DISK[1], 0.28, 0.3, center(5)),
      draw(g, { u }) {
        const kt = seg(u, 0, 0.8), shown = Math.floor(clamp(kt * 1.5) * JS.length + 0.001);
        g.text(JS, X0, TY, { font: 'mono', size: SZ, c: C.GREY, align: 'left', k: kt });
        for (let i = 0; i < shown; i++) if ('{}[]'.includes(JS[i])) g.text(JS[i], cx6(i), TY, { font: 'mono', size: SZ, c: DC[depthOf(i)] });
        const morph = seg(u, 1.9, 2.7);
        for (const p of pairs) {
          const k = seg(u, 0.85 + 0.12 * p.d + 0.03 * p.k, 1.5 + 0.12 * p.d + 0.03 * p.k);
          g.poly(arch(p), { c: DC[p.d], w: 0.008, k, a: lerp(1, 0.3, morph) });
          const q = seg(u, 1.9 + 0.05 * p.k, 2.6 + 0.05 * p.k);
          if (q > 0) {
            const x = lerp(p.mx, p.nx, q), y = lerp(TY - 0.06 - p.ry, p.ny, q);
            if (p.parent >= 0) { const pp = pairs[p.parent]; g.line(pp.nx, pp.ny, p.nx, p.ny, { c: C.GREY, w: 0.004, a: 0.7, k: seg(u, 2.55, 2.95) }); }
            g.dot(x, y, 0.026, DC[p.d]);
          }
        }
        g.text('open', TX(0), -0.62, { font: 'mono', size: 0.026, c: C.GREY, k: seg(u, 2.9, 3.3) });
        g.text('close', TX(1), -0.62, { font: 'mono', size: 0.026, c: C.GREY, k: seg(u, 2.95, 3.35) });
        g.text('parent', TX(2), -0.62, { font: 'mono', size: 0.026, c: C.GREY, k: seg(u, 3.0, 3.4) });
        g.text('tag', TX(3), -0.62, { font: 'mono', size: 0.026, c: C.GREY, k: seg(u, 3.05, 3.45) });
        for (const p of pairs) {
          const q = seg(u, 3.0 + 0.06 * p.k, 3.5 + 0.06 * p.k);
          if (q <= 0) continue;
          if (q < 1) g.dot(lerp(p.nx, TX(3), q), lerp(p.ny, p.ty, q), 0.022, DC[p.d]);
          else {
            [p.o, p.c, p.parent].forEach((v, c) => { g.poly(rrectPts(TX(c), p.ty, 0.068, 0.045, 0.012), { c: C.GREY, w: 0.003, a: 0.6, closed: true }); g.text(String(v), TX(c), p.ty, { font: 'mono', size: 0.034 }); });
            g.poly(rrectPts(TX(3), p.ty, 0.068, 0.045, 0.012), { c: DC[p.d], w: 0.004, closed: true });
            g.text(JS[p.o], TX(3), p.ty, { font: 'mono', size: 0.04, c: DC[p.d] });
          }
          g.line(TX(3) + 0.08, p.ty, DISK[0] - 0.02, DISK[1], { c: C.GOLD, w: 0.003, a: 0.5, k: seg(u, 3.9 + 0.04 * p.k, 4.4 + 0.04 * p.k) });
        }
      },
      tiny(g) { for (const p of pairs.slice(0, 4)) g.poly(arch(p), { c: DC[p.d], w: 0.03, a: 0.8 }); g.circle(DISK[0], DISK[1], 0.3, { c: C.GOLD, w: 0.03, a: 0.7 }); },
      ev: [...Array.from({ length: 12 }, (_, i) => [i * 0.065, 'tick', 0, 0.05, -0.7 + 0.06 * i]),
        ...pairs.map(p => [1.0 + 0.12 * p.d + 0.03 * p.k, 'pluck', 4 + 2 * p.d, 0.16, (p.mx) / 2]),
        ...pairs.map(p => [2.6 + 0.05 * p.k, 'pluck', 8 + p.d, 0.12, p.nx / 2]),
        ...pairs.map(p => [3.5 + 0.06 * p.k, 'pluck', 9 + p.k, 0.1, 0.1]), [4.45, 'chord', 4, 0.3, 0.5]],
    });
  }

  // ══ 7 · Couch: a copy-on-write B+tree fortress over documents ═
  {
    const lx = i => -1.5 + 0.2 * i, LY = 0.55, nx = n => -1.2 + 0.8 * n, NY = 0.17, RY = -0.2, HERO = 10, LR = 0.052;
    const leafAt = i => at(lx(i), LY, LR, 0, center(6));
    const N2 = [0.62, 0.03], R2 = [0.3, -0.37], NEW = C.YELLOW;
    const node = (g, x, y, hw, hh, ticks, c, o = {}) => {
      g.poly(rrectPts(x, y, hw, hh, 0.025), { c, w: 0.006, closed: true, fill: '#14100a', fa: 0.85, ...o });
      for (const t of ticks) g.line(x + t, y - hh * 0.6, x + t, y + hh * 0.6, { c, w: 0.003, a: 0.6 * (o.a ?? 1), k: o.k ?? 1 });
    };
    const merlons = (g, x, y, a) => { for (let m = 0; m < 6; m++) g.poly(rrectPts(x - 0.36 + 0.144 * m, y - 0.025, 0.033, 0.025, 0.006), { c: C.ORANGE, w: 0.004, closed: true, fill: C.ORANGE, fa: 0.35, a }); };
    const flag = (g, x, y, T, a) => {
      g.line(x, y, x, y - 0.36, { w: 0.006, a });
      const pts = [[x, y - 0.36]]; for (let i = 1; i <= 6; i++) pts.push([x + 0.028 * i, y - 0.36 + 0.035 * (i / 6) + 0.012 * Math.sin(3 * T - i * 0.7)]);
      pts.push([x, y - 0.29]); g.poly(pts, { c: C.RED, w: 0.004, closed: true, fill: C.RED, fa: 0.85, a });
    };
    def({
      name: 'Couch', color: C.ORANGE, R: 1.65, cx: 0, cy: 0.12, zoom: 2.8, dwell: 3.8, mult: 16, hint: ['copy-on-write B+tree · WAL · _changes', 2.9], view: { x: 0, y: 0.08 },
      place: () => leafAt(HERO),
      draw(g, { u, T }) {
        for (let i = 0; i < 16; i++) {
          const q = pop(u, 0.05 + 0.07 * Math.abs(i - HERO), 0.35);
          if (q <= 0) continue;
          g.push(about(lx(i), LY, i === HERO ? 1 : q));
          g.poly(rrectPts(lx(i), LY, 0.092, 0.07, 0.015), { c: C.ORANGE, w: 0.004, a: 0.55 * clamp(q), closed: true });
          if (i !== HERO) inst(g, 6, leafAt(i), T);
          g.pop();
          if (i < 15) { const k = seg(u, 0.5 + 0.03 * i, 0.8 + 0.03 * i); g.line(lx(i) + 0.094, LY, lx(i + 1) - 0.1, LY, { c: C.GREY, w: 0.003, a: 0.6, k }); if (k >= 1) arrowHead(g, lx(i + 1) - 0.094, LY, 0, 0.018, C.GREY, 0.6); }
        }
        const cow = seg(u, 2.8, 3.4), old = 1 - 0.75 * cow;
        for (let n = 0; n < 4; n++) {
          const q = pop(u, 1.0 + 0.12 * n, 0.4);
          if (q <= 0) continue;
          for (let j = 0; j < 4; j++) g.line(nx(n) - 0.1875 + 0.125 * j, NY + 0.055, lx(4 * n + j), LY - 0.07, { c: C.GREY, w: 0.003, a: 0.5 * (n === 2 ? old : 1), k: seg(u, 1.2 + 0.12 * n, 1.6 + 0.12 * n) });
          g.push(about(nx(n), NY, q)); node(g, nx(n), NY, 0.25, 0.055, [-0.125, 0, 0.125], C.ORANGE, { a: n === 2 ? old : 1, dash: n === 2 && cow > 0.5 ? [0.02, 0.012] : null }); g.pop();
        }
        const qr = pop(u, 1.7, 0.45);
        if (qr > 0) {
          for (let j = 0; j < 4; j++) g.line(-0.315 + 0.21 * j, RY + 0.065, nx(j), NY - 0.055, { c: C.GREY, w: 0.003, a: 0.5 * old, k: seg(u, 1.8, 2.2) });
          g.push(about(0, RY, qr)); node(g, 0, RY, 0.42, 0.065, [-0.21, 0, 0.21], C.ORANGE, { a: old, dash: cow > 0.5 ? [0.02, 0.012] : null }); g.pop();
          merlons(g, 0, RY - 0.09, old * seg(u, 2.0, 2.3)); flag(g, 0, RY - 0.065, T, seg(u, 2.1, 2.5) * (1 - cow));
        }
        const q2 = pop(u, 2.9, 0.45), q3 = pop(u, 3.3, 0.45);
        if (q2 > 0) {
          for (let j = 0; j < 4; j++) g.line(N2[0] - 0.1875 + 0.125 * j, N2[1] + 0.055, lx(8 + j), LY - 0.07, { c: NEW, w: 0.004, a: 0.85, k: seg(u, 3.0, 3.3) });
          g.push(about(N2[0], N2[1], q2)); node(g, N2[0], N2[1], 0.25, 0.055, [-0.125, 0, 0.125], NEW); g.pop();
        }
        if (q3 > 0) {
          const kids = [[nx(0), NY], [nx(1), NY], N2, [nx(3), NY]];
          kids.forEach(([x, y], j) => g.line(R2[0] - 0.315 + 0.21 * j, R2[1] + 0.065, x, y - 0.055, { c: NEW, w: 0.004, a: 0.85, k: seg(u, 3.4, 3.7) }));
          g.push(about(R2[0], R2[1], q3)); node(g, R2[0], R2[1], 0.42, 0.065, [-0.21, 0, 0.21], NEW); g.pop();
          merlons(g, R2[0], R2[1] - 0.09, seg(u, 3.5, 3.8)); flag(g, R2[0], R2[1] - 0.065, T, seg(u, 3.6, 4.0));
        }
        const nW = Math.floor(clamp((u - 0.5) / 0.12, 0, 35));
        for (let n = 0; n < nW; n++) g.poly(rrectPts(-1.5 + 0.085 * n, 0.86, 0.032, 0.022, 0.006), { c: C.GOLD, w: 0.003, closed: true, fill: C.GOLD, fa: 0.5, a: 0.8 });
        if (nW > 0) { const hx = -1.5 + 0.085 * (nW - 1); g.text('_changes', Math.min(hx + 0.07, 1.25), 0.86, { font: 'mono', size: 0.032, c: C.GREY, align: 'left' }); }
        g.text('WAL', -1.56, 0.86, { font: 'mono', size: 0.034, c: C.GOLD, align: 'right', k: seg(u, 0.5, 1.0) });
      },
      tiny(g) { g.poly([[-1.5, LY], [1.5, LY], [0, RY - 0.1]], { c: C.ORANGE, w: 0.03, a: 0.7, closed: true }); g.line(0, RY - 0.1, 0, RY - 0.45, { w: 0.03, a: 0.7 }); g.dot(0.08, RY - 0.42, 0.06, C.RED); },
      ev: [...Array.from({ length: 16 }, (_, i) => [0.05 + 0.07 * Math.abs(i - HERO), 'pluck', 3 + Math.abs(i - HERO) % 5, 0.1, lx(i) / 2]),
        ...[0, 1, 2, 3].map(n => [1.0 + 0.12 * n, 'pluck', 8 + n, 0.16, nx(n) / 2]), [1.75, 'bell', 12, 0.24, 0], [2.2, 'pluck', 14, 0.12, 0],
        [2.95, 'bell', 13, 0.22, 0.3], [3.35, 'bell', 15, 0.26, 0.05], ...Array.from({ length: 35 }, (_, n) => [0.5 + 0.12 * n, 'tick', 0, 0.035, -0.7 + 0.04 * n])],
    });
  }

  // ══ 8 · CCEK: reactors on every side, keyed into one context ══
  {
    const inner = (i, T) => { const a = TAU * i / 8 + 0.16 * T; return [0.98 * Math.cos(a), 0.6 * Math.sin(a), a]; };
    const outer = (j, T) => { const a = TAU * j / 14 - 0.11 * T + 0.2; return [1.5 * Math.cos(a), 0.92 * Math.sin(a), a]; };
    const port = a => [0.5 * Math.cos(a), 0.27 * Math.sin(a)];
    const reactor = (g, x, y, r, c, T, i, a) => {
      g.poly(ngon(6, r, T * 0.5 + i), { c, w: r * 0.09, closed: true, fill: '#120c1c', fa: 0.92, a });
      keyGlyph(g, x, y, r * 0.55, T * 0.8 + i * 1.3, C.YELLOW, a);
    };
    def({
      name: 'CCEK', color: C.PURPLE, R: 1.6, cx: 0, cy: 0, zoom: 2.6, dwell: 3.4, mult: 23, hint: ['coroutineContext[Key] · SupervisorJob', 2.3],
      place: () => at(0, 0.04, 0.27, 0.25, center(7)),
      draw(g, { u, T }) {
        const kc = seg(u, 0.9, 1.7), kp = seg(u, 2.0, 2.6);
        for (let i = 0; i < 8; i++) {
          const [x, y, a] = inner(i, T), p = port(a);
          g.line(p[0], p[1], x, y, { c: C.PURPLE, w: 0.004, a: 0.4, k: kc });
          if (kp > 0) {
            const f = frac(T * 0.75 + i * 0.21), f2 = frac(T * 0.75 + i * 0.21 + 0.5);
            g.dot(lerp(p[0], x, f), lerp(p[1], y, f), 0.013, C.BLUE, kp);
            g.dot(lerp(x, p[0], f2), lerp(y, p[1], f2), 0.013, C.GREEN, kp);
          }
        }
        for (let j = 0; j < 14; j++) {
          const [x, y, a] = outer(j, T), i = Math.round(((a - 0.16 * T) / TAU * 8) % 8 + 8) % 8, [ix, iy] = inner(i, T);
          g.line(ix, iy, x, y, { c: C.TEAL, w: 0.003, a: 0.18, k: seg(u, 1.6, 2.4) });
          if (kp > 0) { const f = frac(T * 0.5 + j * 0.13); g.dot(lerp(ix, x, f), lerp(iy, y, f), 0.009, C.TEAL, 0.7 * kp); }
        }
      },
      over(g, { u, T }) {
        for (let i = 0; i < 8; i++) { const q = pop(u, 0.1 + 0.13 * i, 0.4); if (q > 0) { const [x, y] = inner(i, T); g.push(about(x, y, q)); g.push(place(x, y)); reactor(g, 0, 0, 0.072, C.PURPLE, T, i, 1); g.pop(); g.pop(); } }
        for (let j = 0; j < 14; j++) { const q = pop(u, 1.4 + 0.08 * j, 0.35); if (q > 0) { const [x, y] = outer(j, T); g.push(about(x, y, q)); g.push(place(x, y)); reactor(g, 0, 0, 0.05, C.TEAL, T, j + 3, 1); g.pop(); g.pop(); } }
      },
      tiny(g, { T }) { g.dot(0, 0, 0.18, C.ORANGE, 0.6); for (let i = 0; i < 8; i++) { const [x, y] = inner(i, T); g.dot(x, y, 0.07, C.PURPLE); } },
      ev: [...Array.from({ length: 8 }, (_, i) => [0.1 + 0.13 * i, 'pluck', i, 0.2, Math.cos(TAU * i / 8) * 0.6]),
        ...Array.from({ length: 14 }, (_, j) => [1.4 + 0.08 * j, 'pluck', 6 + j, 0.07, Math.cos(TAU * j / 14) * 0.7]), [2.0, 'bell', 9, 0.18, 0]],
    });
  }

  // ══ 9 · SQL · facet · graph: one object, three shadows ════════
  {
    const O = [0, 0.36], EX = [0.66, 0.255], EY = [-0.66, 0.255], EZ = [0, -0.74];
    const P3 = (X, Y, Z) => [O[0] + X * EX[0] + Y * EY[0] + Z * EZ[0], O[1] + X * EX[1] + Y * EY[1] + Z * EZ[1]];
    const WL = [EY[0], EY[1], EZ[0], EZ[1], O[0], O[1]], WR = [EX[0], EX[1], EZ[0], EZ[1], O[0], O[1]], FL = [EX[0], EX[1], EY[0], EY[1], O[0], O[1]];
    const HC = P3(0.72, 0.72, 0.62);
    const BARS = [0.55, 0.3, 0.72, 0.42, 0.62, 0.24], BC = [C.BLUE, C.TEAL, C.GREEN, C.YELLOW, C.GOLD, C.RED];
    const GN = [[0.45, 0.5], [0.8, 0.38], [1.1, 0.6], [0.62, 0.85], [0.95, 0.95], [1.2, 1.2], [0.5, 1.15]], GE = [[0, 1], [1, 2], [0, 3], [3, 4], [1, 4], [4, 5], [3, 6], [2, 5], [6, 4]];
    const cube = () => { const h = 0.24, c = [0.72, 0.72, 0.62], V = []; for (const sx of [-1, 1]) for (const sy of [-1, 1]) for (const sz of [-1, 1]) V.push(P3(c[0] + sx * h, c[1] + sy * h, c[2] + sz * h)); return [V, [[0, 1], [2, 3], [4, 5], [6, 7], [0, 2], [1, 3], [4, 6], [5, 7], [0, 4], [1, 5], [2, 6], [3, 7]]]; };
    const [CV, CE] = cube();
    const wall = (g, M, k, a) => { g.push(M); g.poly([[0, 0], [1.4, 0], [1.4, 1.4], [0, 1.4]], { c: C.GREY, w: 0.006, a: 0.6 * a, k, closed: true }); for (let t = 0.2; t < 1.4; t += 0.2) { g.line(t, 0, t, 1.4, { c: C.BLUE_E, w: 0.003, a: 0.35 * a * k }); g.line(0, t, 1.4, t, { c: C.BLUE_E, w: 0.003, a: 0.35 * a * k }); } g.pop(); };
    const cone = (g, corners, c, k) => { if (k <= 0) return; g.poly([HC, ...corners], { c: null, w: 0, closed: true, fill: c, fa: 0.05 * k }); for (const q of corners) g.line(HC[0], HC[1], q[0], q[1], { c, w: 0.003, a: 0.3, k }); };
    def({
      name: 'SQL · facet · graph', color: C.TEAL, R: 1.3, cx: 0, cy: 0.18, zoom: 2.6, dwell: 3.6, mult: 4, hint: ['QueryK · PostgREST · Graph<N, E>', 3.0], view: { x: 0, y: 0.18 },
      place: () => at(HC[0], HC[1], 0.15, 0, center(8)),
      draw(g, { u, T }) {
        const kw = seg(u, 0, 0.9);
        wall(g, WL, kw, 1); wall(g, WR, kw, 1); wall(g, FL, kw, 0.8);
        const kc = seg(u, 1.2, 2.0);
        cone(g, [P3(0, 0.32, 0.32), P3(0, 1.12, 0.32), P3(0, 1.12, 1.0), P3(0, 0.32, 1.0)], C.BLUE, kc);
        cone(g, [P3(0.3, 0, 0.3), P3(1.12, 0, 0.3), P3(1.12, 0, 1.05), P3(0.3, 0, 1.05)], C.GOLD, kc);
        cone(g, [P3(0.4, 0.35, 0), P3(1.25, 0.35, 0), P3(1.25, 1.25, 0), P3(0.4, 1.25, 0)], C.GREEN, kc);
        g.push(WL);
        for (let r = 0; r < 5; r++) for (let c = 0; c < 4; c++) {
          const q = seg(u, 1.9 + 0.035 * (r * 4 + c), 2.2 + 0.035 * (r * 4 + c));
          if (q <= 0) continue;
          const x = 0.32 + 0.2 * c, y = 1.0 - 0.136 * (r + 1);
          g.poly([[x + 0.01, y + 0.01], [x + 0.19, y + 0.01], [x + 0.19, y + 0.126], [x + 0.01, y + 0.126]], { c: r === 0 ? C.GREEN : C.BLUE, w: 0.008, closed: true, fill: r === 0 ? C.GREEN : C.BLUE, fa: 0.18, a: q });
        }
        g.push([-1, 0, 0, -1, 0.72, 1.2]); g.text('SQL', 0, 0, { font: 'mono', size: 0.12, c: C.WHITE, a: 0.8, k: seg(u, 3.0, 3.5) }); g.pop();
        g.pop();
        g.push(WR);
        BARS.forEach((h, b) => { const q = pop(u, 2.0 + 0.08 * b, 0.45); if (q > 0) { const x = 0.36 + 0.13 * b; g.poly([[x, 0.3], [x + 0.09, 0.3], [x + 0.09, 0.3 + h * q], [x, 0.3 + h * q]], { c: BC[b], w: 0.008, closed: true, fill: BC[b], fa: 0.55 }); } });
        g.push([1, 0, 0, -1, 0.72, 1.2]); g.text('facet', 0, 0, { font: 'mono', size: 0.12, c: C.WHITE, a: 0.8, k: seg(u, 3.1, 3.6) }); g.pop();
        g.pop();
        g.push(FL);
        GE.forEach(([a, b], i) => g.line(GN[a][0], GN[a][1], GN[b][0], GN[b][1], { c: C.GREEN, w: 0.008, a: 0.8, k: seg(u, 2.5 + 0.04 * i, 2.9 + 0.04 * i) }));
        GN.forEach(([x, y], i) => { const q = pop(u, 2.2 + 0.07 * i, 0.4); if (q > 0) g.circle(x, y, 0.055 * q, { c: C.WHITE, w: 0.008, fill: C.GREEN, fa: 0.8 }); });
        g.text('graph', 0.25, 1.3, { font: 'mono', size: 0.12, c: C.WHITE, a: 0.8, k: seg(u, 3.2, 3.7) });
        g.pop();
      },
      over(g, { u }) { const k = seg(u, 0.9, 1.6); for (const [a, b] of CE) g.line(CV[a][0], CV[a][1], CV[b][0], CV[b][1], { c: C.WHITE, w: 0.003, a: 0.3, k }); },
      tiny(g) { const o = P3(0, 0, 0); for (const q of [P3(1.4, 0, 0), P3(0, 1.4, 0), P3(0, 0, 1.4)]) g.line(o[0], o[1], q[0], q[1], { c: C.GREY, w: 0.03, a: 0.7 }); g.dot(HC[0], HC[1], 0.12, C.PURPLE); },
      ev: [[0.3, 'pluck', 0, 0.12, -0.5], [0.6, 'pluck', 2, 0.12, 0.5], [1.4, 'gliss', 0, 0.12, 0], ...Array.from({ length: 10 }, (_, i) => [1.9 + 0.07 * i, 'pluck', 5 + (i % 5), 0.09, -0.6]),
        ...BARS.map((h, b) => [2.0 + 0.08 * b, 'pluck', 4 + Math.round(h * 10), 0.14, 0.6]), ...GN.map((_, i) => [2.2 + 0.07 * i, 'pluck', 9 + i, 0.1, 0])],
    });
  }

  // ══ 10 · io_uring: two rings, one pillar ══════════════════════
  {
    const RX = 1.18, RY = 0.34, TILT = 0.3, N = 32, RATE = 6;
    const ringPt = (tilt, a, dr = 0) => { const x = (RX + dr) * Math.cos(a), y = (RY + dr * 0.3) * Math.sin(a); return [x * Math.cos(tilt) - y * Math.sin(tilt), x * Math.sin(tilt) + y * Math.cos(tilt)]; };
    const front = a => Math.sin(a) > 0;
    let T0 = 0;
    const band = (g, tilt, rot, c, k, wantFront) => {
      for (const dr of [-0.03, 0.03]) {
        const pts = []; let seg0 = [];
        for (let i = 0; i <= 160; i++) { const a = TAU * i / 160; if (front(a) === wantFront) seg0.push(ringPt(tilt, a, dr)); else if (seg0.length) { pts.push(seg0); seg0 = []; } }
        if (seg0.length) pts.push(seg0);
        for (const p of pts) g.poly(p, { c, w: 0.006, a: (wantFront ? 0.85 : 0.4), k });
      }
    };
    const slots = (g, T, tilt, rot, c, litFn, wantFront, a) => {
      for (let s = 0; s < N; s++) {
        const an = TAU * s / N + rot; if (front(((an % TAU) + TAU) % TAU) !== wantFront) continue;
        const p0 = ringPt(tilt, an, -0.026), p1 = ringPt(tilt, an, 0.026), lit = litFn(s);
        g.line(p0[0], p0[1], p1[0], p1[1], { c: lit ? c : C.GREY, w: lit ? 0.02 : 0.005, a: a * (wantFront ? 1 : 0.5) * (lit ? 1 : 0.6) });
      }
    };
    def({
      name: 'io_uring', color: C.GOLD, R: 1.3, cx: 0, cy: 0, zoom: 2.6, dwell: 3.4, mult: 64, hint: ['SQ → kernel → CQ', 2.0],
      place: () => at(0, 0.02, 0.3, -0.2, center(9)),
      init(o) { T0 = o + 1.4; },
      draw(g, st) { this.rings(g, st, false); const kp = seg(st.u, 1.0, 1.6); if (kp > 0) for (const [w, a] of [[0.06, 0.06], [0.025, 0.12], [0.008, 0.35]]) g.line(0, 0.98, 0, -0.98, { c: C.WHITE, w, a: a * kp * (0.85 + 0.15 * Math.sin(st.T * 7)) }); },
      over(g, st) {
        this.rings(g, st, true);
        const { u, T } = st, kl = seg(u, 1.6, 2.2);
        const sq = ringPt(-TILT, 0), cq = ringPt(TILT, Math.PI);
        g.text('SQ', sq[0] + 0.12, sq[1], { font: 'mono', size: 0.055, c: C.GOLD, k: kl });
        g.text('CQ', cq[0] - 0.12, cq[1], { font: 'mono', size: 0.055, c: C.GREEN, k: kl });
        if (T < T0) return;
        const n = Math.floor((T - T0) * RATE);
        for (let m = Math.max(0, n - 12); m <= n; m++) {
          const tc = T0 + (m + 6) / RATE, d = T - tc;
          if (d > 0 && d < 0.5) { const an = TAU * (m % N) / N + 0.2 * T, p = ringPt(-TILT, an); g.dot(lerp(p[0], 0, sm(d / 0.5)), lerp(p[1], 0, sm(d / 0.5)), 0.016, C.GOLD); }
          const d2 = d - 0.5;
          if (d2 > 0 && d2 < 0.45) { const an = TAU * (m % N) / N - 0.2 * T, p = ringPt(TILT, an); g.dot(lerp(0, p[0], sm(d2 / 0.45)), lerp(0, p[1], sm(d2 / 0.45)), 0.016, C.GREEN); }
          const d3 = d2 - 1.0;
          if (d3 > 0 && d3 < 0.6) { const an = TAU * (m % N) / N - 0.2 * T, p = ringPt(TILT, an); g.dot(lerp(p[0], 0, sm(d3 / 0.6)), lerp(p[1], -1.1, sm(d3 / 0.6)), 0.013, C.WHITE, 1 - d3 / 0.6); }
        }
      },
      rings(g, { u, T }, wantFront) {
        const k1 = seg(u, 0, 1.0), k2 = seg(u, 0.3, 1.3), ka = seg(u, 1.2, 1.6);
        band(g, -TILT, 0, C.GOLD, k1, wantFront); band(g, TILT, 0, C.GREEN, k2, wantFront);
        if (ka <= 0) return;
        const n = T < T0 ? -1 : Math.floor((T - T0) * RATE);
        const inSQ = s => { for (let m = n - 5; m <= n; m++) if (m >= 0 && m % N === s) return true; return false; };
        const inCQ = s => { for (let m = n - 15; m <= n - 9; m++) if (m >= 0 && m % N === s) return true; return false; };
        slots(g, T, -TILT, 0.2 * T, C.GOLD, inSQ, wantFront, ka);
        slots(g, T, TILT, -0.2 * T, C.GREEN, inCQ, wantFront, ka);
      },
      tiny(g) { for (const t of [-TILT, TILT]) g.poly(ellPts(0, 0, RX, RY, t, 0, TAU, 40), { c: t < 0 ? C.GOLD : C.GREEN, w: 0.04, a: 0.8 }); },
      ev(o) { const out = [[0.2, 'pluck', 0, 0.14, 0.6], [0.5, 'pluck', 4, 0.14, -0.6], [1.1, 'gliss', 0, 0.14, 0]]; for (let t = 1.6; t < 6.0; t += 0.5) out.push([t, 'kick', 0, 0.3, 0], [t + 0.25, 'hat', 0, 0.06, 0.3]); return out; },
    });
  }

  // ══ 11 · blackboard: agents propose in concentric rings ═══════
  {
    const RINGS = [{ r: 0.6, n: 10, c: C.TEAL, w: 0.22 }, { r: 0.8, n: 16, c: C.PURPLE, w: -0.15 }, { r: 1.0, n: 24, c: C.PINK, w: 0.1 }];
    const agent = (ri, i, T) => { const R = RINGS[ri], a = TAU * i / R.n + R.w * T + ri * 0.4; return [R.r * Math.cos(a), R.r * Math.sin(a), a]; };
    const CARDS = 10, cardA = c => TAU * c / CARDS + 0.3;
    const PROPS = Array.from({ length: 26 }, (_, k) => { const ri = k % 3; return { t: 1.9 + 0.14 * k, ri, i: (k * 7) % RINGS[ri].n, ok: k % 4 !== 2, card: (k * 3) % CARDS }; });
    const RIM = 0.42;
    def({
      name: 'blackboard', color: C.PINK, R: 1.08, cx: 0, cy: 0, zoom: 2.6, dwell: 3.6, mult: 51, hint: ['propose ≠ command', 2.2],
      place: () => at(0, 0, 0.21, 0.35, center(10)),
      draw(g, { u, T }) {
        g.circle(0, 0, RIM, { c: C.GREEN, w: 0.006, a: 0.5, fill: '#0b1a12', fa: 0.9, k: seg(u, 0, 0.7) });
        RINGS.forEach((R, ri) => {
          const k = seg(u, 0.4 + 0.5 * ri, 1.4 + 0.5 * ri);
          if (k <= 0) return;
          const pts = Array.from({ length: R.n }, (_, i) => agent(ri, i, T));
          g.poly(pts.map(p => [p[0], p[1]]), { c: R.c, w: 0.003, a: 0.35, k, closed: true });
          if (ri === 0) pts.forEach(([x, y, a]) => { const [x2, y2] = [RINGS[1].r * Math.cos(a), RINGS[1].r * Math.sin(a)]; g.line(x, y, x2, y2, { c: C.GREY, w: 0.002, a: 0.15 * k }); });
        });
      },
      over(g, { u, T }) {
        for (let c = 0; c < CARDS; c++) {
          const a = cardA(c), x = 0.345 * Math.cos(a), y = 0.345 * Math.sin(a), q = pop(u, 0.3 + 0.04 * c, 0.3);
          if (q <= 0) continue;
          const fired = PROPS.filter(p => p.ok && p.card === c && u > p.t + 0.45);
          const fl = fired.length ? clamp(1 - (u - fired[fired.length - 1].t - 0.45) / 0.6) : 0;
          g.push(mul(place(x, y, q, a + Math.PI / 2), place(0, 0)));
          g.poly(rrectPts(0, 0, 0.034, 0.022, 0.006), { c: fired.length ? C.GREEN : C.YELLOW, w: 0.004, closed: true, fill: fired.length ? C.GREEN : C.YELLOW, fa: fired.length ? 0.55 + 0.4 * fl : 0.12 });
          g.pop();
        }
        RINGS.forEach((R, ri) => { for (let i = 0; i < R.n; i++) { const q = pop(u, 0.4 + 0.5 * ri + 0.03 * i, 0.3); if (q > 0) { const [x, y] = agent(ri, i, T); g.dot(x, y, 0.022 * q, R.c); g.dot(x, y, 0.008 * q, C.WHITE, 0.9); } } });
        for (const p of PROPS) {
          const d = u - p.t; if (d < 0 || d > 0.8) continue;
          const [x, y, a] = agent(p.ri, p.i, p.t + (T - u)), rim = [RIM * Math.cos(a), RIM * Math.sin(a)];
          if (d < 0.45) g.dot(lerp(x, rim[0], sm(d / 0.45)), lerp(y, rim[1], sm(d / 0.45)), 0.014, RINGS[p.ri].c);
          else if (p.ok) flash(g, rim[0], rim[1], 0.06, (d - 0.45) / 0.35, C.GREEN, 8);
          else g.dot(lerp(rim[0], x, sm((d - 0.45) / 0.35)), lerp(rim[1], y, sm((d - 0.45) / 0.35)), 0.014, C.RED, 1 - (d - 0.45) / 0.35);
        }
      },
      tiny(g) { RINGS.forEach(R => g.circle(0, 0, R.r, { c: R.c, w: 0.04, a: 0.7 })); g.dot(0, 0, 0.2, C.GREEN, 0.5); },
      ev: [[0.2, 'pluck', 2, 0.14, 0], ...RINGS.flatMap((R, ri) => Array.from({ length: R.n }, (_, i) => [0.4 + 0.5 * ri + 0.03 * i, 'pluck', 4 + ri * 3 + (i % 5), 0.045, Math.cos(TAU * i / R.n) * 0.7])),
        ...PROPS.map(p => [p.t + 0.45, p.ok ? 'bell' : 'pluck', p.ok ? 9 + (p.card % 5) : -3, p.ok ? 0.1 : 0.12, 0])],
    });
  }

  // ══ 12 · p2p: a galaxy of peers trading content-addressed blocks ══
  const P2P = {};
  {
    const rnd = rng(7), PEERS = [], HERO = [0.38, -0.12];
    for (let t = 0; t < 6000 && PEERS.length < 140; t++) {
      const a = rnd() * TAU, r = Math.sqrt(rnd()), x = 2.7 * r * Math.cos(a), y = 1.7 * r * Math.sin(a);
      if (Math.hypot(x - HERO[0], y - HERO[1]) < 0.32) continue;
      if (PEERS.some(p => Math.hypot(p.x - x, p.y - y) < 0.3)) continue;
      PEERS.push({ x, y, s: 0.07 + 0.03 * rnd(), phi: rnd() * TAU });
    }
    PEERS.forEach(p => { p.d = Math.hypot(p.x - HERO[0], p.y - HERO[1]); p.t = 0.1 + 0.5 * p.d; });
    const NODES = [{ x: HERO[0], y: HERO[1], t: 0 }, ...PEERS], EDGES = [], key = new Set();
    NODES.forEach((p, i) => NODES.map((q, j) => [Math.hypot(p.x - q.x, p.y - q.y), j]).filter(([, j]) => j !== i).sort((a, b) => a[0] - b[0]).slice(0, 3).forEach(([, j]) => { const k = i < j ? i + ':' + j : j + ':' + i; if (!key.has(k)) { key.add(k); EDGES.push([i, j]); } }));
    const ADJ = NODES.map(() => []); EDGES.forEach(([a, b]) => { ADJ[a].push(b); ADJ[b].push(a); });
    const WALKS = Array.from({ length: 28 }, (_, p) => { const r = rng(100 + p); let n = Math.floor(r() * NODES.length); const w = [n]; for (let s = 0; s < 80; s++) { n = ADJ[n][Math.floor(r() * ADJ[n].length)]; w.push(n); } return w; });
    Object.assign(P2P, { peers: PEERS.length });
    def({
      name: 'p2p · IPFS', color: C.RED, R: 2.75, cx: 0, cy: 0, zoom: 2.8, dwell: 3.4, mult: PEERS.length + 1, tinyAt: 30, hint: ['CID · IPNS · libp2p', 2.1],
      place: () => at(HERO[0], HERO[1], 0.105, 0.5, center(11)),
      draw(g, { u, T }) {
        for (const [a, b] of EDGES) { const p = NODES[a], q = NODES[b], k = seg(u, Math.max(p.t, q.t) + 0.15, Math.max(p.t, q.t) + 0.55); g.line(p.x, p.y, q.x, q.y, { c: C.RED, w: 0.004, a: 0.28, k }); }
        for (const p of PEERS) { const q = pop(u, p.t, 0.4); if (q > 0) inst(g, 11, mul(at(p.x, p.y, p.s, p.phi + 0.05 * T, center(11)), about(0, 0, q)), T); }
      },
      over(g, { u, T }) {
        const kp = seg(u, 1.5, 2.0);
        if (kp > 0) WALKS.forEach((w, i) => {
          const tau = (T * 1.1 + i * 0.37) % 80, s = Math.floor(tau), f = sm(tau - s), a = NODES[w[s]], b = NODES[w[s + 1]];
          const x = lerp(a.x, b.x, f), y = lerp(a.y, b.y, f);
          g.poly(ngon(6, 0.017, 0, x, y), { c: C.GOLD, w: 0.004, closed: true, fill: C.GOLD, fa: 0.6, a: kp });
          if (i === 0) g.text('bafybeigdyrzt…', x + 0.03, y - 0.035, { font: 'mono', size: 0.03, c: C.GOLD, align: 'left', a: kp });
        });
      },
      tiny(g) { for (const p of PEERS.slice(0, 24)) g.dot(p.x, p.y, 0.09, C.RED, 0.8); for (const [a, b] of EDGES.slice(0, 30)) g.line(NODES[a].x, NODES[a].y, NODES[b].x, NODES[b].y, { c: C.RED, w: 0.03, a: 0.4 }); },
      ev: [...PEERS.filter(p => p.d < 1.9).map((p, i) => [p.t, 'pluck', 5 + (i % 9), 0.045, clamp(p.x / 2, -0.8, 0.8)]), [1.6, 'gliss', 0, 0.1, 0]],
    });
  }

  // ══ 13 · ouroboros: the chain of blocks eats its tail ═════════
  {
    const RS = 0.93, TH0 = -Math.PI / 2, G0 = 0.3, G1 = 2.9, FULL = 0.985 * TAU, GL = ['⋈', 'λ', 'α', '↻'];
    const heads = u => {
      if (u < G0) return null;
      if (u < G1) return [TH0, TH0 + FULL * sm((u - G0) / (G1 - G0))];
      const h = TH0 + FULL + 0.85 * (u - G1); return [h - FULL, h];
    };
    const snake = (g, u, T, full) => {
      const hs = full ? [TH0 + 0.85 * T - FULL, TH0 + 0.85 * T] : heads(u);
      if (!hs) return;
      const [tA, hA] = hs, segs = [];
      for (let th = hA - 0.06; th > tA; th -= 0.108) segs.push(th);
      const body = []; for (let th = tA; th <= hA; th += 0.03) body.push([RS * Math.cos(th), RS * Math.sin(th)]);
      g.poly(body, { c: C.GREEN, w: 0.01, a: 0.55 });
      segs.forEach((th, m) => {
        const sz = lerp(0.052, 0.02, clamp((hA - th) / TAU)), x = RS * Math.cos(th), y = RS * Math.sin(th);
        g.poly(ngon(6, sz, th, x, y), { c: m % 2 ? C.TEAL : C.GREEN, w: sz * 0.12, closed: true, fill: '#0e1f12', fa: 0.92 });
        if (m % 3 === 1 && sz > 0.03) { g.push(place(x, y, 1, th + Math.PI / 2)); g.text(GL[(m / 3 | 0) % 4], 0, 0, { size: sz * 1.1, c: C.WHITE, a: 0.85 }); g.pop(); }
      });
      const hx = RS * Math.cos(hA), hy = RS * Math.sin(hA);
      g.push(place(hx, hy, 1, hA + Math.PI / 2));
      g.poly([[0.08, 0], [-0.02, 0.055], [-0.05, 0.032], [-0.05, -0.032], [-0.02, -0.055]], { c: C.WHITE, w: 0.004, a: 0.7, closed: true, fill: C.GREEN, fa: 0.95 });
      g.dot(0.012, -0.026, 0.012, C.WHITE); g.dot(0.016, -0.026, 0.006, '#000000');
      if (Math.sin(T * 3.1) > 0.8) { g.line(0.08, 0, 0.125, 0, { c: C.RED, w: 0.005 }); g.line(0.125, 0, 0.14, 0.012, { c: C.RED, w: 0.004 }); g.line(0.125, 0, 0.14, -0.012, { c: C.RED, w: 0.004 }); }
      g.pop();
    };
    def({
      name: 'ouroboros', color: C.GREEN, R: 1.05, cx: 0, cy: 0, zoom: 2.6, dwell: 3.6, mult: 57, hint: ['worktree → CAS → peers → hot-swap', 3.0],
      place: () => at(0, 0, 0.3, -0.3, center(12)),
      over(g, { u, T, hero }) {
        snake(g, u, T, !hero || u > 1e5);
        const cl = TH0 + FULL;
        flash(g, RS * Math.cos(cl), RS * Math.sin(cl), 0.16, (u - G1) / 0.6, C.WHITE, 14);
        if (u > G1 && u < G1 + 0.8) g.circle(0, 0, lerp(RS, 1.7, sm((u - G1) / 0.8)), { c: C.WHITE, w: 0.006, a: 0.7 * (1 - (u - G1) / 0.8) });
      },
      tiny(g, { T }) { g.circle(0, 0, RS, { c: C.GREEN, w: 0.06, a: 0.85 }); const h = TH0 + 0.85 * T; g.dot(RS * Math.cos(h), RS * Math.sin(h), 0.1, C.WHITE, 0.9); },
      ev: [[0.4, 'pluck', 4, 0.16, 0], [1.0, 'pluck', 6, 0.16, 0.6], [1.6, 'pluck', 8, 0.16, 0], [2.2, 'pluck', 10, 0.16, -0.6], [G1, 'chord', 5, 0.5, 0], [G1, 'thump', 0, 0.4, 0]],
    });
  }

  // ══ 14 · models: the whole stack is one neuron of the net it serves ══
  {
    const LAY = [5, 8, 9, 8, 4], LX = [-1.32, -0.66, 0, 0.66, 1.32], ny = (l, n) => (n - (LAY[l] - 1) / 2) * 0.2, NR = 0.074;
    const NEU = []; LAY.forEach((c, l) => { for (let n = 0; n < c; n++) NEU.push({ l, n, x: LX[l], y: ny(l, n), t: 0.1 + 0.16 * Math.abs(l - 2) + 0.03 * n }); });
    const WAVES = [1.5, 2.75, 4.0, 5.25], FR = (u, w) => -1.55 + 2.3 * (u - w);
    const PHR = [['a', '⋈', 'b'], ['composes'], ['all', 'the', 'way', 'up'], ['↻']];
    def({
      name: 'models', color: C.BLUE, R: 1.62, cx: 0, cy: 0, zoom: 2.6, dwell: 4.6, mult: 34,
      place: () => at(0, 0, NR, 0, center(13)),
      draw(g, { u, T }) {
        const kc = seg(u, 0.6, 1.8);
        if (kc > 0) for (let l = 0; l < 4; l++) for (let a = 0; a < LAY[l]; a++) for (let b = 0; b < LAY[l + 1]; b++) {
          g.line(LX[l], ny(l, a), LX[l + 1], ny(l + 1, b), { c: C.BLUE, w: 0.003, a: 0.14 * kc });
          WAVES.forEach((w, wi) => { const f = (FR(u, w) - LX[l]) / 0.66; if (f > 0 && f < 1 && (a + b + wi) % 3 === 0) g.dot(lerp(LX[l], LX[l + 1], f), lerp(ny(l, a), ny(l + 1, b), f), 0.008, C.WHITE, 0.8); });
        }
        for (const nu of NEU) {
          let glow = 0; for (const w of WAVES) glow = Math.max(glow, 1 - Math.abs(nu.x - FR(u, w)) / 0.2);
          if (glow > 0) g.dot(nu.x, nu.y, 0.1, C.WHITE, 0.22 * glow);
        }
        if (u > 1.0) for (let n = 0; n < 5; n++) for (let j = 0; j < 3; j++) { const f = frac(T * 0.45 + n * 0.13 + j / 3), x = lerp(-1.95, LX[0] - 0.09, f); g.poly(ngon(6, 0.02, 0, x, ny(0, n)), { c: C.GOLD, w: 0.004, closed: true, fill: C.GOLD, fa: 0.5, a: seg(u, 1.0, 1.5) * clamp(f * 4) }); }
      },
      over(g, { u, T }) {
        for (const nu of NEU) { if (nu.l === 2 && nu.n === 4) continue; const q = pop(u, nu.t, 0.35); if (q > 0) inst(g, 13, mul(at(nu.x, nu.y, NR, nu.l * 1.7 + nu.n * 0.9, center(13)), about(0, 0, q)), T); }
        WAVES.forEach((w, wi) => { PHR[wi].forEach((tok, j) => {
          const t0 = w + 1.25 + 0.14 * j, d = u - t0; if (d < 0 || d > 2.4) return;
          g.text(tok, 1.5 + 0.11 * j + 0.16 * d, -0.42 + 0.3 * wi + 0.0 * j, { font: MATHCH.test(tok) ? 'math' : 'main', size: 0.07, c: [C.YELLOW, C.TEAL, C.PINK, C.GREEN][wi], a: clamp(d / 0.2) * clamp((2.4 - d) / 0.8), align: 'left' });
        }); });
        g.text('weights ← CID', -1.62, 0.56, { font: 'mono', size: 0.04, c: C.GREY, align: 'left', k: seg(u, 1.4, 2.2) });
        g.text('tokens →', 1.46, -0.62, { font: 'mono', size: 0.04, c: C.GREY, align: 'left', k: seg(u, 2.6, 3.2) });
      },
      tiny(g) { NEU.forEach(nu => g.dot(nu.x, nu.y, 0.05, C.GREEN, 0.9)); for (let l = 0; l < 4; l++) g.line(LX[l], -0.6, LX[l + 1], 0.6, { c: C.BLUE, w: 0.02, a: 0.3 }); },
      ev: [...NEU.map(nu => [nu.t, 'pluck', 3 + nu.l * 2 + (nu.n % 3), 0.05, nu.x / 2]),
        ...WAVES.flatMap(w => LX.map((x, l) => [w + (x + 1.55) / 2.3, 'pluck', 5 + 2 * l, 0.08, x / 2])),
        ...WAVES.flatMap((w, wi) => PHR[wi].map((_, j) => [w + 1.25 + 0.14 * j, 'pluck', 12 + j + wi, 0.14, 0.6]))],
    });
  }

  // ══ 15 · the whole thing is a point again ═════════════════════
  def({
    name: '', color: C.BLUE, R: 0.6, cx: 0, cy: 0.08, zoom: 3.4, dwell: 4.0, mult: 1,
    place: () => at(0, 0, 0.012, 0.5, center(14)),
    heroAlpha: u => 1 - seg(u, 0.8, 1.6),
    over(g, { u }) {
      g.dot(0, 0, 0.035, C.BLUE, seg(u, 0.6, 1.4));
      const out = 1 - seg(u, 3.9, 4.6);
      g.text('TrikeShed', 0, 0.27, { font: 'main', size: 0.12, a: out, k: seg(u, 1.9, 2.9) });
      g.text('Join · λ · α · ↻', 0, 0.4, { size: 0.055, c: C.GREY, a: out, k: seg(u, 2.5, 3.3) });
    },
    tiny(g) { g.dot(0, 0, 0.035, C.BLUE); },
    ev: [[1.0, 'bell', 0, 0.3, 0], [2.0, 'chord', 0, 0.3, 0], [2.6, 'pluck', 7, 0.14, 0.3], [3.0, 'pluck', 9, 0.12, -0.3]],
  });

  // ── timeline ──────────────────────────────────────────────────
  const DRIFT = 0.06, ZS = [], ZE = [], DE = [];
  ZS[0] = -1; ZE[0] = 0; DE[0] = LV[0].dwell; origin[0] = 0;
  for (let k = 1, t = LV[0].dwell; k < LV.length; k++) {
    ZS[k] = t; ZE[k] = t + LV[k].zoom; DE[k] = ZE[k] + LV[k].dwell; origin[k] = ZS[k] + 0.5 * LV[k].zoom; t = DE[k];
  }
  const LAST = LV.length - 1, duration = DE[LAST];
  LV.forEach((L, k) => { if (L.init) L.init(origin[k]); });
  function levelAt(T) {
    if (T < DE[0]) return DRIFT * T / DE[0];
    for (let k = 1; k <= LAST; k++) {
      if (T < ZE[k]) return k - 1 + DRIFT + (1 - DRIFT) * sm((T - ZS[k]) / LV[k].zoom);
      if (T < DE[k]) return k === LAST ? LAST : k + DRIFT * (T - ZE[k]) / LV[k].dwell;
    }
    return LAST;
  }
  function camera(T, q) {
    const Lv = levelAt(T), lo = Math.min(Math.floor(Lv), LAST - 1), t = Lv - lo, a = lo + 1;
    const P = LV[a].place(T), rho = [P[0], P[1]], c = [P[4], P[5]], vp = LV[a - 1].view, va = LV[a].view;
    // the spiral turns through the placement's own angle, unwrapped where it moves with time
    const phi = LV[a].turn ? LV[a].turn(T) : Math.atan2(P[1], P[0]), r = Math.hypot(P[0], P[1]);
    const l0 = cmul(rho, [vp.v, 0]), Q0 = cadd(c, cmul(rho, [vp.x, vp.y])), l1 = [va.v, 0], Q1 = [va.x, va.y];
    const lg = [Math.log(va.v / (r * vp.v)), -phi], lt = cmul(l0, cexp([lg[0] * t, lg[1] * t]));
    const p = cdiv(csub(cmul(Q1, l0), cmul(Q0, l1)), csub(l0, l1)), Q = cadd(p, cmul(csub(Q0, p), cdiv(lt, l0)));
    const A = cdiv([S * q, 0], lt), B = csub([W * q / 2, H * q / 2], cmul(A, Q));
    return { a, Lv, M: [A[0], A[1], -A[1], A[0], B[0], B[1]] };
  }

  // ── HUD: the ladder climbed so far, and how many Joins are in view ──
  const NAMES = LV.map(L => L.name);
  const LOGN = []; LV.reduce((acc, L, k) => (LOGN[k] = acc + Math.log10(L.mult)), 0);
  const SUP = '⁰¹²³⁴⁵⁶⁷⁸⁹';
  function hudAlpha(T) { return seg(T, 1.0, 2.0) * (1 - seg(T - origin[LAST], 3.0, 4.0)); }
  function runs(ctx, str, x, y, px, align, mono) {
    const parts = []; let cur = null;
    for (const ch of str) { const f = MATHCH.test(ch) ? FONT.math : mono; if (!cur || cur[1] !== f) parts.push(cur = ['', f]); cur[0] += ch; }
    let wd = 0; for (const p of parts) { ctx.font = `${px}px ${p[1]}`; p[2] = ctx.measureText(p[0]).width; wd += p[2]; }
    let xx = align === 'right' ? x - wd : x;
    for (const p of parts) { ctx.font = `${px}px ${p[1]}`; ctx.fillText(p[0], xx, y); xx += p[2]; }
  }
  function hud(ctx, T, Lv, q) {
    const a = hudAlpha(T); if (a <= 0.003) return;
    ctx.setTransform(q, 0, 0, q, 0, 0); ctx.textBaseline = 'middle'; ctx.textAlign = 'left';
    const X = 1866, Y0 = 950, Y1 = 130, top = LAST - 1, yOf = l => lerp(Y0, Y1, l / top), cur = Math.min(Lv, top);
    ctx.globalAlpha = 0.16 * a; ctx.fillStyle = '#ffffff'; ctx.fillRect(X - 1, Y1, 2, Y0 - Y1);
    ctx.globalAlpha = 0.55 * a; ctx.fillRect(X - 1, yOf(cur), 2, Y0 - yOf(cur));
    for (let l = 0; l <= top; l++) {
      if (l > cur + 0.35) continue;
      const on = Math.abs(l - cur) < 0.5, y = yOf(l), la = clamp(1 - Math.abs(l - cur) * 1.6);
      ctx.globalAlpha = (on ? 1 : 0.4) * a; ctx.fillStyle = on ? LV[l].color : '#9aa0aa';
      ctx.fillRect(X - 6, y - 1, 12, 2);
      if (la > 0.01) { ctx.globalAlpha = la * a; ctx.fillStyle = LV[l].color; runs(ctx, NAMES[l], X - 16, y, 18, 'right', FONT.mono); }
    }
    const my = yOf(cur); ctx.globalAlpha = a; ctx.fillStyle = LV[Math.round(cur)].color;
    ctx.beginPath(); ctx.moveTo(X + 6, my); ctx.lineTo(X + 18, my - 7); ctx.lineTo(X + 18, my + 7); ctx.closePath(); ctx.fill();
    const lo = Math.min(Math.floor(Lv), LAST - 1), lg = lerp(LOGN[lo], LOGN[lo + 1], Lv - lo), e = Math.floor(lg), m = Math.pow(10, lg - e);
    ctx.globalAlpha = 0.8 * a; ctx.fillStyle = '#ffffff'; ctx.font = `34px ${FONT.main}`;
    const body = e < 5 ? `⋈ × ${Math.round(Math.pow(10, lg)).toLocaleString('en-US')}` : `⋈ × ${m.toFixed(1)} · 10`;
    ctx.fillText(body, 48, 64);
    if (e >= 5) { const w = ctx.measureText(body).width; ctx.font = `22px ${FONT.main}`; ctx.fillText(String(e).split('').map(d => SUP[+d]).join(''), 50 + w, 52); }
    // the engineers' caption: one line of the real names, written in, gone at the next climb
    for (let k = 0; k < LV.length; k++) {
      const h = LV[k].hint; if (!h) continue;
      const kw = seg(T - origin[k], h[1], h[1] + 0.9), out = 1 - seg(T, DE[k], DE[k] + 0.5);
      if (kw <= 0 || out <= 0) continue;
      ctx.save(); ctx.beginPath(); ctx.rect(40, 990, 1700 * clamp(kw * 1.15), 80); ctx.clip();
      ctx.globalAlpha = 0.85 * a * out; ctx.fillStyle = '#b4bac4'; runs(ctx, h[0], 48, 1032, 24, 'left', FONT.mono);
      ctx.restore();
    }
  }

  // ── frame ─────────────────────────────────────────────────────
  let cv, ctx, b1, b2;
  function bind(canvas) {
    cv = canvas; ctx = cv.getContext('2d'); canvasW = cv.width; canvasH = cv.height;
    const mk = (w, h) => { const c = document.createElement('canvas'); c.width = w; c.height = h; return c; };
    b1 = mk(cv.width >> 1, cv.height >> 1); b2 = mk(cv.width >> 2, cv.height >> 2);
  }
  function frame(T) {
    T = clamp(T, 0, duration);
    const q = cv.width / W, cam = camera(T, q), g = new G(ctx);
    ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.globalAlpha = 1; ctx.globalCompositeOperation = 'source-over';
    ctx.fillStyle = '#000'; ctx.fillRect(0, 0, cv.width, cv.height);
    g.M = cam.M; latticeGain = seg(T, 0.8, 2.0) * (1 - seg(T - origin[LAST], 2.0, 3.0));
    drawLevel(cam.a, g, { u: T - origin[cam.a], T, hero: true });
    // bloom: two blurred copies added back
    ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.globalAlpha = 1;
    const c1 = b1.getContext('2d'), c2 = b2.getContext('2d');
    c1.globalCompositeOperation = 'copy'; c1.filter = 'blur(3px)'; c1.drawImage(cv, 0, 0, b1.width, b1.height);
    c2.globalCompositeOperation = 'copy'; c2.filter = 'blur(6px)'; c2.drawImage(cv, 0, 0, b2.width, b2.height);
    ctx.globalCompositeOperation = 'lighter'; ctx.globalAlpha = 0.5; ctx.drawImage(b1, 0, 0, cv.width, cv.height);
    ctx.globalAlpha = 0.45; ctx.drawImage(b2, 0, 0, cv.width, cv.height);
    ctx.globalCompositeOperation = 'source-over'; ctx.globalAlpha = 1;
    const vg = ctx.createRadialGradient(cv.width / 2, cv.height / 2, cv.height * 0.45, cv.width / 2, cv.height / 2, cv.width * 0.62);
    vg.addColorStop(0, 'rgba(0,0,0,0)'); vg.addColorStop(1, 'rgba(0,0,0,0.55)'); ctx.fillStyle = vg; ctx.fillRect(0, 0, cv.width, cv.height);
    hud(ctx, T, cam.Lv, q);
  }

  // ── score data: events on the global clock, and the ascent curve ──
  const events = [];
  LV.forEach((L, k) => {
    const list = typeof L.ev === 'function' ? L.ev(origin[k]) : (L.ev || []);
    for (const [u, kind, p, vel, pan] of list) { const t = origin[k] + u; if (t >= 0 && t < duration) events.push({ t, kind, p, vel, pan: pan || 0, level: k }); }
    if (k > 0) events.push({ t: ZE[k], kind: 'thump', p: 0, vel: 0.3, pan: 0, level: k });
  });
  events.sort((a, b) => a.t - b.t);
  const curve = hz => Array.from({ length: Math.ceil(duration * hz) + 1 }, (_, i) => levelAt(i / hz));

  window.Film = { bind, frame, duration, events, curve, levels: LV.map((L, k) => ({ name: L.name, zs: ZS[k], ze: ZE[k], de: DE[k], origin: origin[k] })), counts: { pages: ISAM.pages, peers: P2P.peers } };
})();
