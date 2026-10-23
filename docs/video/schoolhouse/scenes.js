// Compositional Density — a deterministic SVG film. Film.frame(t) returns the whole 1920×1080 frame at second t,
// so the browser player and the frame-capture renderer draw exactly the same pixels.
(function () {
  const C = {
    bg: '#0b0d12', panel: '#121622', line: '#262c3e', fg: '#e8eaf0', dim: '#7d8599', faint: '#3a4258',
    join: '#7aa2ff', lam: '#c792ea', proj: '#89ddff', idem: '#c3e88d', io: '#ffcb6b',
    couch: '#f78c6c', net: '#ff7a93', agent: '#82e0aa',
  };
  const MONO = `'JetBrains Mono','DejaVu Sans Mono',monospace`;

  // ── primitives ────────────────────────────────────────────────
  const clamp = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
  const ease = x => { x = clamp(x); return x * x * (3 - 2 * x); };
  const win = (p, a, b) => ease((p - a) / (b - a));
  const lerp = (a, b, k) => a + (b - a) * k;
  const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const op = o => o >= 0.999 ? '' : ` opacity="${clamp(o).toFixed(3)}"`;
  // Greek letters set in Computer Modern math italic: the mono face draws α like a Latin a
  const greek = s => s.replace(/[αλ]/g, g => `<tspan font-family="KaTeXMath">${g}</tspan>`);

  function txt(x, y, s, o = {}) {
    const { size = 24, fill = C.fg, anchor = 'start', weight = 400, a = 1, italic = false } = o;
    if (a <= 0.001) return '';
    return `<text x="${x}" y="${y}" font-family="${MONO}" font-size="${size}" fill="${fill}" text-anchor="${anchor}" font-weight="${weight}"${italic ? ' font-style="italic"' : ''} xml:space="preserve" style="white-space:pre"${op(a)}>${greek(esc(s))}</text>`;
  }
  function rect(x, y, w, h, o = {}) {
    const { fill = 'none', stroke = C.line, r = 10, sw = 2, a = 1, dash = '' } = o;
    if (a <= 0.001) return '';
    return `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${fill}" stroke="${stroke}" stroke-width="${sw}"${dash ? ` stroke-dasharray="${dash}"` : ''}${op(a)}/>`;
  }
  function circle(x, y, r, o = {}) {
    const { fill = 'none', stroke = C.line, sw = 2, a = 1, dash = '' } = o;
    if (a <= 0.001) return '';
    return `<circle cx="${x}" cy="${y}" r="${r}" fill="${fill}" stroke="${stroke}" stroke-width="${sw}"${dash ? ` stroke-dasharray="${dash}"` : ''}${op(a)}/>`;
  }
  function line(x1, y1, x2, y2, o = {}) {
    const { stroke = C.dim, sw = 2, a = 1, dash = '', arrow = false } = o;
    if (a <= 0.001) return '';
    let s = `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${stroke}" stroke-width="${sw}"${dash ? ` stroke-dasharray="${dash}"` : ''}${op(a)}/>`;
    if (arrow) {
      const ang = Math.atan2(y2 - y1, x2 - x1), L = 12;
      const p = k => `${x2 - L * Math.cos(ang + k)},${y2 - L * Math.sin(ang + k)}`;
      s += `<polygon points="${x2},${y2} ${p(0.45)} ${p(-0.45)}" fill="${stroke}"${op(a)}/>`;
    }
    return s;
  }
  // a line drawn progressively from its start
  const grow = (x1, y1, x2, y2, k, o = {}) => k <= 0 ? '' : line(x1, y1, lerp(x1, x2, k), lerp(y1, y2, k), { ...o, arrow: o.arrow && k > 0.98 });
  const dot = (x1, y1, x2, y2, k, fill, r = 6, a = 1) => circle(lerp(x1, x2, k), lerp(y1, y2, k), r, { fill, stroke: 'none', a });
  const g = (inner, a = 1, tf = '') => a <= 0.001 ? '' : `<g${op(a)}${tf ? ` transform="${tf}"` : ''}>${inner}</g>`;
  const pill = (cx, cy, label, color, o = {}) => {
    const { size = 20, a = 1, fill = C.panel, padX = 16, h = size * 1.7 } = o;
    const w = label.length * size * 0.6 + padX * 2;
    return rect(cx - w / 2, cy - h / 2, w, h, { fill, stroke: color, r: h / 2, a }) +
      txt(cx, cy + size * 0.35, label, { size, fill: color, anchor: 'middle', a });
  };
  const box = (x, y, w, h, title, color, o = {}) => {
    const { a = 1, size = 22, sub = '', subSize = 17 } = o;
    return rect(x, y, w, h, { fill: C.panel, stroke: color, a }) +
      txt(x + w / 2, y + (sub ? h / 2 - 4 : h / 2 + size * 0.35), title, { size, fill: color, anchor: 'middle', weight: 700, a }) +
      (sub ? txt(x + w / 2, y + h / 2 + subSize + 6, sub, { size: subSize, fill: C.dim, anchor: 'middle', a }) : '');
  };

  // ── code panel with typed reveal and light highlighting ────────
  const KW = /\b(typealias|interface|val|var|fun|operator|infix|inline|return|class|companion|object|override|if|when|else|get|out|SELECT|FROM|GROUP|BY|WHERE|GET|PUT|POST|count)\b/g;
  function hl(s) {
    const cm = s.search(/\/\/|--|\/\*\*/);
    let code = cm >= 0 ? s.slice(0, cm) : s, comment = cm >= 0 ? s.slice(cm) : '';
    let out = '', last = 0;
    const re = new RegExp(`${KW.source}|("[^"]*"?)|(\`[^\`]*\`?)|(\\b\\d+\\b)|([αλ↺↻⋈∘])`, 'g');
    let m;
    while ((m = re.exec(code))) {
      out += esc(code.slice(last, m.index));
      const col = m[1] ? C.lam : m[2] ? C.couch : m[3] ? C.idem : m[4] ? C.io : C.proj;
      out += `<tspan fill="${col}">${greek(esc(m[0]))}</tspan>`;
      last = m.index + m[0].length;
    }
    out += greek(esc(code.slice(last)));
    if (comment) out += `<tspan fill="#6b7a8f" font-style="italic">${greek(esc(comment))}</tspan>`;
    return out;
  }
  function codePanel(lines, p, a) {
    const X = 1270, Y = 150, W = 620, H = 430;
    let s = rect(X, Y, W, H, { fill: C.panel, stroke: C.line, a });
    s += txt(X + 18, Y + 32, 'kotlin · borg.trikeshed', { size: 15, fill: C.faint, a });
    const total = lines.reduce((n, l) => n + l.length + 1, 0);
    let budget = Math.floor(win(p, 0.04, 0.5) * total);
    const size = lines.length > 11 ? 15 : 17, lh = size * 1.55;
    lines.forEach((l, i) => {
      if (budget <= 0) return;
      const shown = l.slice(0, budget); budget -= l.length + 1;
      s += `<text x="${X + 18}" y="${Y + 66 + i * lh}" font-family="${MONO}" font-size="${size}" fill="${C.fg}" xml:space="preserve" style="white-space:pre"${op(a)}>${hl(shown)}</text>`;
    });
    return s;
  }

  // ── the scenes ────────────────────────────────────────────────
  // Each scene: dur (s), title, law (which law it leans on), adds (concepts it composes), code, say, draw(p, T).
  const CX = 635, CY = 535;
  const S = [];

  S.push({
    dur: 9, title: 'Compositional Density', law: 'prologue', color: C.fg, adds: [],
    code: [
      'interface Join<A, B> { val a: A; val b: B }',
      '',
      'typealias Twin<T>   = Join<T, T>',
      'typealias Series<T> = Join<Int, (Int) -> T>',
      '',
      'operator fun <T> Series<T>.get(i: Int): T = b(i)',
      '',
      'typealias RowVec = Series2<Any?, () -> ColumnMeta>',
      'typealias Cursor = Series<RowVec>',
    ],
    say: 'Three small laws — idempotence, projection, and lambdas without bounds checks. Every layer that follows is a composition of them, and nothing more.',
    draw(p) {
      let s = txt(CX, 330, 'COMPOSITIONAL DENSITY', { size: 64, weight: 800, anchor: 'middle', a: win(p, 0, 0.15) });
      s += txt(CX, 385, 'from three laws to a living database', { size: 26, fill: C.dim, anchor: 'middle', a: win(p, 0.08, 0.22) });
      const laws = [['↻', 'idempotence', C.idem, -330], ['α', 'projection', C.proj, 0], ['λ', 'no bounds check', C.lam, 330]];
      laws.forEach(([gl, nm, col, dx], i) => {
        const k = win(p, 0.25 + i * 0.1, 0.4 + i * 0.1);
        const conv = win(p, 0.75, 0.95);
        const x = CX + dx * (1 - conv * 0.55), y = 600;
        s += circle(x, y, 80, { stroke: col, sw: 3, fill: C.panel, a: k });
        s += txt(x, y + 30, gl, { size: 88, fill: col, anchor: 'middle', weight: 700, a: k });
        s += txt(x, y + 125, nm, { size: 22, fill: col, anchor: 'middle', a: k * (1 - conv) });
      });
      s += txt(CX, 830, '⋈', { size: 70, fill: C.join, anchor: 'middle', a: win(p, 0.8, 0.95) });
      return s;
    },
  });

  S.push({
    dur: 9, title: 'Join', law: 'the product', color: C.join, adds: ['Join', 'a j b', 'Twin'],
    code: [
      '/** The base binary composition — a product type',
      ' *  with two properties and nothing else. */',
      'interface Join<out A, out B> {',
      '    val a: A',
      '    val b: B',
      '}',
      '',
      'inline infix fun <A, B> A.j(b: B): Join<A, B>',
      '',
      'typealias Twin<T> = Join<T, T>',
    ],
    say: 'Join: a product with two properties and nothing else. "a j b" is the one constructor the whole system needs.',
    draw(p) {
      const m = win(p, 0.15, 0.35);
      let s = box(lerp(150, 330, m), 260, 200, 110, 'A', C.join, { a: win(p, 0, 0.1) });
      s += box(lerp(920, 740, m), 260, 200, 110, 'B', C.join, { a: win(p, 0.04, 0.14) });
      s += txt(CX, 330, '⋈', { size: 56, fill: C.fg, anchor: 'middle', a: m });
      s += txt(CX, 430, 'a j b', { size: 34, fill: C.join, anchor: 'middle', a: win(p, 0.3, 0.4) });
      // nesting: Joins of Joins build any shape
      const n = win(p, 0.45, 0.65), t = win(p, 0.65, 0.85);
      s += txt(CX, 520, 'nest it, and every shape appears', { size: 22, fill: C.dim, anchor: 'middle', a: n });
      const tree = [[CX, 600, '⋈'], [CX - 200, 700, '⋈'], [CX + 200, 700, 'C'], [CX - 300, 800, 'A'], [CX - 100, 800, 'B']];
      [[0, 1], [0, 2], [1, 3], [1, 4]].forEach(([u, v]) => s += line(tree[u][0], tree[u][1] + 10, tree[v][0], tree[v][1] - 30, { stroke: C.faint, a: n }));
      tree.forEach(([x, y, l]) => s += circle(x, y - 10, 30, { fill: C.panel, stroke: l === '⋈' ? C.fg : C.join, a: n }) + txt(x, y + 2, l, { size: 28, anchor: 'middle', fill: l === '⋈' ? C.fg : C.join, a: n }));
      s += txt(CX + 360, 720, 'Twin<T> = T ⋈ T', { size: 24, fill: C.join, anchor: 'middle', a: t });
      s += txt(CX + 360, 760, '(0 j 9)  — a span', { size: 20, fill: C.dim, anchor: 'middle', a: t });
      return s;
    },
  });

  S.push({
    dur: 10, title: 'Series', law: 'λ — no bounds check', color: C.lam, adds: ['Series', 'size j { i -> }', 'MetaSeries'],
    code: [
      'typealias MetaSeries<I, T> = Join<I, (I) -> T>',
      'typealias Series<T> = MetaSeries<Int, T>',
      '',
      'val <T> Series<T>.size: Int get() = a',
      'operator fun <T> Series<T>.get(i: Int): T = b(i)',
      '',
      'val squares: Series<Int> = 8 j { i -> i * i }',
      '',
      '// no array, no copy, no bounds check:',
      '// the index is just an argument',
    ],
    say: 'Series: a length joined to a lambda. Reading index i calls the function — the size travels as data beside it, so the hot path carries no array and no bounds check.',
    draw(p, T) {
      let s = pill(CX - 200, 270, 'size = 8', C.join, { size: 26, a: win(p, 0, 0.1) });
      s += txt(CX, 280, '⋈', { size: 40, anchor: 'middle', a: win(p, 0.05, 0.15) });
      s += pill(CX + 200, 270, 'λ i → i·i', C.lam, { size: 26, a: win(p, 0.1, 0.2) });
      const cells = 8, w = 120, x0 = CX - cells * w / 2;
      const k = win(p, 0.22, 0.32);
      const cur = Math.floor(clamp((p - 0.35) / 0.5) * cells * 1.999) % cells;
      for (let i = 0; i < cells; i++) {
        const lit = p > 0.35 && p < 0.88 && i === cur;
        s += rect(x0 + i * w + 6, 460, w - 12, 100, { stroke: lit ? C.lam : C.faint, dash: lit ? '' : '6 6', fill: lit ? '#1e1830' : 'none', a: k });
        s += txt(x0 + i * w + w / 2, 600, `[${i}]`, { size: 18, fill: C.dim, anchor: 'middle', a: k });
        if (lit) s += txt(x0 + i * w + w / 2, 524, String(i * i), { size: 36, fill: C.lam, anchor: 'middle', weight: 700 });
      }
      if (p > 0.35 && p < 0.88) {
        const x = x0 + cur * w + w / 2;
        s += line(CX + 200, 295, x, 455, { stroke: C.lam, dash: '4 6', arrow: true });
        s += txt(CX, 680, `get(${cur}) = b(${cur}) = ${cur * cur}`, { size: 30, fill: C.fg, anchor: 'middle' });
      }
      s += txt(CX, 760, 'cells exist only while they are read', { size: 22, fill: C.dim, anchor: 'middle', a: win(p, 0.45, 0.55) });
      s += txt(CX, 810, 'computed on read · never stored · never range-checked', { size: 22, fill: C.lam, anchor: 'middle', a: win(p, 0.6, 0.7) });
      return s;
    },
  });

  S.push({
    dur: 10, title: 'Projection α', law: 'α — projection', color: C.proj, adds: ['α', 'fusion', 'index views'],
    code: [
      'infix fun <X, C> Series<X>.α(xform: (X) -> C) =',
      '    size j { i -> xform(this[i]) }',
      '',
      'operator fun <T> Series<T>.get(ix: IntArray) =',
      '    ix.size j { i -> this[ix[i]] }',
      'operator fun <T> Series<T>.get(r: IntRange) =',
      '    r.count() j { i -> this[r.first + i] }',
      '',
      'xs α f α g   // ≡ xs.size j { g(f(xs[it])) }',
    ],
    say: 'Projection: α maps without materializing. Chains of α fuse into one lambda, and index views — s[ix], s[range] — project the domain instead of copying the data.',
    draw(p) {
      const rows = [['xs', C.join], ['xs α f', C.proj], ['xs α f α g', C.proj]];
      const n = 6, w = 110, x0 = CX - n * w / 2 + 80;
      const fuse = win(p, 0.5, 0.65);
      let s = '';
      rows.forEach(([nm, col], r) => {
        const a = win(p, r * 0.12, r * 0.12 + 0.12);
        const y = lerp(250 + r * 140, 390, fuse * (r === 1 ? 1 : 0)) ;
        const aa = r === 1 ? a * (1 - fuse) : a;
        s += txt(x0 - 30, y + 48, nm, { size: 22, fill: col, anchor: 'end', a: aa });
        for (let i = 0; i < n; i++) {
          s += rect(x0 + i * w + 8, y + 10, w - 16, 64, { stroke: col, a: aa, dash: r ? '6 6' : '' });
          if (r > 0 && a > 0.5) s += line(x0 + i * w + w / 2, y - 66, x0 + i * w + w / 2, y + 8, { stroke: C.faint, a: aa * (r === 2 ? 1 - fuse : 1), arrow: true });
        }
        if (r === 2) for (let i = 0; i < n; i++) s += line(x0 + i * w + w / 2, 324, x0 + i * w + w / 2, y + 8, { stroke: C.proj, a: fuse, arrow: true });
      });
      s += txt(x0 + n * w / 2, 652, '= one lambda: g∘f∘b', { size: 22, fill: C.proj, anchor: 'middle', a: fuse });
      // index view: a permutation, still no copy
      const v = win(p, 0.7, 0.82), y = 700, perm = [4, 0, 5, 2];
      s += txt(x0 - 30, y + 48, 'xs[ix]', { size: 22, fill: C.proj, anchor: 'end', a: v });
      perm.forEach((src, i) => {
        s += rect(x0 + i * w + 8, y + 10, w - 16, 64, { stroke: C.proj, dash: '6 6', a: v });
        s += txt(x0 + i * w + w / 2, y + 52, `[${src}]`, { size: 20, fill: C.dim, anchor: 'middle', a: v });
        s += line(x0 + src * w + w / 2, 324, x0 + i * w + w / 2, y + 8, { stroke: C.faint, dash: '3 6', a: v * 0.8 });
      });
      s += txt(CX, 850, 'zero copies — every view is a function of the one below', { size: 22, fill: C.dim, anchor: 'middle', a: win(p, 0.82, 0.92) });
      return s;
    },
  });

  S.push({
    dur: 10, title: 'Idempotence', law: '↻ — idempotence', color: C.idem, adds: ['f∘f = f', 'ColumnMeta↻', 'safe replay'],
    code: [
      '// idempotent: f(f(x)) == f(x)',
      '',
      'typealias `ColumnMeta↻` = () -> ColumnMeta',
      '',
      '// IsamLeaves: reads are idempotent — each decode',
      '// fills a fresh leaf, published whole, never written after',
      '',
      '// ProductionCouchIngress',
      'if (existingRev == rev) return true // already the head',
      '',
      '/** Pull only frames after the persisted checkpoint.',
      ' *  Safe and idempotent. */',
    ],
    say: 'Idempotence: asking twice is free. Meta suppliers, decoded leaves, revisions and replays can all be re-run without changing the answer — so any lambda may be called anywhere, at any time.',
    draw(p, T) {
      let s = txt(CX, 300, 'f ∘ f = f', { size: 72, fill: C.idem, anchor: 'middle', weight: 800, a: win(p, 0, 0.12) });
      const k = win(p, 0.12, 0.25);
      s += box(CX - 450, 360, 160, 80, 'x', C.fg, { a: k });
      s += box(CX - 80, 360, 160, 80, 'f(x)', C.idem, { a: k });
      s += box(CX + 290, 360, 160, 80, 'f(x)', C.idem, { a: win(p, 0.25, 0.35) });
      s += grow(CX - 290, 400, CX - 86, 400, win(p, 0.15, 0.25), { stroke: C.idem, arrow: true });
      s += txt(CX - 185, 385, 'f', { size: 24, fill: C.idem, anchor: 'middle', a: k });
      s += grow(CX + 80, 400, CX + 284, 400, win(p, 0.25, 0.35), { stroke: C.idem, arrow: true });
      s += txt(CX + 185, 385, 'f again', { size: 22, fill: C.idem, anchor: 'middle', a: win(p, 0.25, 0.35) });
      s += txt(CX + 370, 480, 'same answer', { size: 20, fill: C.dim, anchor: 'middle', a: win(p, 0.32, 0.4) });
      const cards = [
        ['row 0', 'meta exemplar: ColumnMeta↻'],
        ['ISAM leaf', 'decoded once, published whole'],
        ['revision', 'existingRev == rev → done'],
        ['view', 'replay after checkpoint'],
      ];
      cards.forEach(([h, b], i) => {
        const a = win(p, 0.42 + i * 0.08, 0.52 + i * 0.08);
        const x = 90 + (i % 2) * 560, y = 560 + Math.floor(i / 2) * 150;
        s += rect(x, y, 520, 120, { fill: C.panel, stroke: C.idem, a });
        s += txt(x + 24, y + 48, h, { size: 26, fill: C.idem, weight: 700, a });
        s += txt(x + 24, y + 88, b, { size: 20, fill: C.fg, a });
      });
      return s;
    },
  });

  S.push({
    dur: 10, title: 'Cursor', law: '⋈ + λ, twice', color: C.join, adds: ['RowVec', 'Cursor', 'ColumnMeta child'],
    code: [
      'typealias Series2<A, B> = Series<Join<A, B>>',
      'typealias RowVec = Series2<Any?, `ColumnMeta↻`>',
      'typealias Cursor = Series<RowVec>',
      '',
      '/** ColumnMeta = name × type × child */',
      'interface ColumnMeta :',
      '    Join<CharSequence, Join<TypeMemento, ColumnMeta?>>',
      '',
      '// child ≠ null → this cell is a sub-document root',
    ],
    say: 'Cursor: a Series of rows, each row a Series of value ⋈ meta. A table is three Joins and two lambdas deep — and the child slot lets any cell be a whole document.',
    draw(p) {
      const R = 6, Cc = 4, w = 210, h = 70, x0 = CX - Cc * w / 2, y0 = 300;
      const names = ['open', 'close', 'parent', 'tag'];
      let s = '';
      for (let c = 0; c < Cc; c++) s += txt(x0 + c * w + w / 2, y0 - 20, names[c], { size: 22, fill: C.join, anchor: 'middle', a: win(p, 0.05, 0.15) });
      for (let r = 0; r < R; r++) {
        const a = win(p, 0.08 + r * 0.05, 0.18 + r * 0.05);
        for (let c = 0; c < Cc; c++) {
          const hiC = p > 0.6 && c === 3;
          s += rect(x0 + c * w + 6, y0 + r * h + 6, w - 12, h - 12, { stroke: r === 0 ? C.idem : hiC ? C.proj : C.faint, fill: r === 0 ? '#16201a' : C.panel, a });
          s += txt(x0 + c * w + w / 2, y0 + r * h + 44, 'v ⋈ ↻', { size: 20, fill: r === 0 ? C.idem : hiC ? C.proj : C.dim, anchor: 'middle', a });
        }
        s += txt(x0 - 20, y0 + r * h + 44, `${r}`, { size: 18, fill: C.dim, anchor: 'end', a });
      }
      s += txt(x0 + Cc * w + 20, y0 + 44, '← row 0: exemplar', { size: 18, fill: C.idem, a: win(p, 0.4, 0.5) });
      s += txt(CX, y0 + R * h + 60, 'cursor α { row -> row[3] }  — a column is a projection', { size: 22, fill: C.proj, anchor: 'middle', a: win(p, 0.6, 0.7) });
      s += txt(CX, y0 + R * h + 105, 'Series< Series< Join<value, () -> meta> > >', { size: 22, fill: C.dim, anchor: 'middle', a: win(p, 0.75, 0.85) });
      return s;
    },
  });

  S.push({
    dur: 10, title: 'ISAM Cursors', law: 'λ over offsets', color: C.io, adds: ['RecordMeta', 'ISAM', 'mmap'],
    code: [
      'RecordMeta("open",   IoInt,     0,  4)',
      'RecordMeta("close",  IoInt,     4,  8)',
      'RecordMeta("parent", IoInt,     8, 12)',
      'RecordMeta("tag",    IoString, 12, 44)',
      '',
      '// cell (i, c) = decode(bytes, i·recLen + begin .. end)',
      'rows j { i -> cols j { c ->',
      '    decode(i, c) j meta[c].`↺` } }',
      '',
      '// UringIsamOperations: page reads via io_uring',
    ],
    say: 'ISAM Cursors: fixed-width records on memory-mapped files. The row lambda becomes an offset computation — the same Cursor algebra, now zero-copy over disk.',
    draw(p) {
      let s = txt(80, 260, 'file  (memory-mapped)', { size: 20, fill: C.dim, a: win(p, 0, 0.1) });
      const recW = 264, x0 = 80, y = 280, fields = [[0, 4, 'open'], [4, 8, 'close'], [8, 12, 'parent'], [12, 44, 'tag']];
      for (let r = 0; r < 4; r++) {
        const a = win(p, 0.03 + r * 0.04, 0.12 + r * 0.04);
        fields.forEach(([b, e, nm]) => {
          const fx = x0 + r * recW + b * recW / 44, fw = (e - b) * recW / 44;
          s += rect(fx, y, fw, 60, { r: 3, stroke: C.io, fill: r === 1 && p > 0.4 ? '#2a2412' : C.panel, a, sw: 1.5 });
        });
        s += txt(x0 + r * recW + recW / 2, y + 90, `record ${r}`, { size: 16, fill: C.dim, anchor: 'middle', a });
      }
      // brackets for record 1
      const bk = win(p, 0.3, 0.42);
      fields.forEach(([b, e, nm], i) => {
        const fx = x0 + recW + b * recW / 44, fw = (e - b) * recW / 44;
        s += txt(fx + fw / 2, y - 12 - i * 20, nm, { size: 15, fill: C.io, anchor: 'middle', a: bk });
      });
      // offset arithmetic
      s += txt(CX, 470, 'offset(i, c) = i · 44 + begin[c]', { size: 30, fill: C.io, anchor: 'middle', a: win(p, 0.42, 0.52) });
      s += grow(x0 + recW * 1.5, 380, CX, 600, win(p, 0.52, 0.62), { stroke: C.io, arrow: true, dash: '6 6' });
      // resulting cursor row
      const a2 = win(p, 0.6, 0.7);
      ['open', 'close', 'parent', 'tag'].forEach((nm, c) => {
        s += rect(CX - 420 + c * 210 + 6, 610, 198, 70, { stroke: C.join, fill: C.panel, a: a2 });
        s += txt(CX - 420 + c * 210 + 105, 654, `${nm} ⋈ ↻`, { size: 20, fill: C.join, anchor: 'middle', a: a2 });
      });
      s += txt(CX, 730, 'Cursor row 1 — nothing parsed until read', { size: 20, fill: C.dim, anchor: 'middle', a: a2 });
      // uring badge
      const u = win(p, 0.75, 0.85);
      s += circle(CX - 70, 820, 40, { stroke: C.io, sw: 6, dash: '14 8', a: u });
      s += circle(CX + 70, 820, 40, { stroke: C.io, sw: 6, dash: '14 8', a: u });
      s += txt(CX - 70, 827, 'SQ', { size: 20, fill: C.io, anchor: 'middle', a: u });
      s += txt(CX + 70, 827, 'CQ', { size: 20, fill: C.io, anchor: 'middle', a: u });
      s += txt(CX + 140, 827, 'io_uring page reads', { size: 20, fill: C.dim, a: u });
      return s;
    },
  });

  S.push({
    dur: 11, title: 'Confix', law: 'faceted isomorphism', color: C.proj, adds: ['Confix', 'facets', 'JSON ⇄ ISAM', 'YAML · CBOR'],
    code: [
      'val spans    = index.facet(ConfixIndexK.Spans)',
      'val tags     = index.facet(ConfixIndexK.Tags)',
      'val depths   = index.facet(ConfixIndexK.Depths)',
      'val children = index.facet(ConfixIndexK.DirectChildren)',
      '',
      'return spans.size j { i -> schema.size j { c ->',
      '    when (c) { 0 -> spans[i].a; 1 -> spans[i].b',
      '               2 -> parents[i]; else -> tags[i].name }',
      '      j schema[c].`↺` } }',
      '',
      '// ConfixIsamIsomorphism.geometry(index): Cursor',
    ],
    say: 'Confix: one parser for JSON, YAML and CBOR. Its index is faceted — spans, tags, depths, children — and the parse geometry is itself an ISAM Cursor. Document ⇄ table is an isomorphism, not an import.',
    draw(p) {
      const src = '{"a":[1,2,{"b":true}],"c":"x"}';
      const cw = 30, x0 = CX - src.length * cw / 2, y = 290;
      let s = '';
      ['JSON', 'YAML', 'CBOR'].forEach((f, i) => s += pill(220 + i * 150, 220, f, i ? C.dim : C.proj, { size: 18, a: win(p, 0, 0.1) }));
      s += txt(720, 226, '→ one Confix scanner', { size: 20, fill: C.dim, a: win(p, 0.05, 0.12) });
      for (let i = 0; i < src.length; i++) s += txt(x0 + i * cw + cw / 2, y, src[i], { size: 28, anchor: 'middle', fill: '{}[]'.includes(src[i]) ? C.proj : C.fg, a: win(p, 0.05 + i * 0.004, 0.1 + i * 0.004) });
      // span brackets by depth
      const spans = [[0, 29, 0], [5, 20, 1], [11, 19, 2]];
      spans.forEach(([o, c, d], i) => {
        const a = win(p, 0.2 + i * 0.06, 0.28 + i * 0.06), yy = y + 30 + d * 34;
        s += line(x0 + o * cw + cw / 2, yy, x0 + c * cw + cw / 2, yy, { stroke: C.proj, a, sw: 3 });
        s += line(x0 + o * cw + cw / 2, yy - 12, x0 + o * cw + cw / 2, yy, { stroke: C.proj, a, sw: 3 });
        s += line(x0 + c * cw + cw / 2, yy - 12, x0 + c * cw + cw / 2, yy, { stroke: C.proj, a, sw: 3 });
        s += txt(x0 + c * cw + cw, yy + 6, `depth ${d}`, { size: 15, fill: C.dim, a });
      });
      // facet columns → geometry cursor
      const f = win(p, 0.42, 0.52);
      ['Spans', 'Tags', 'Depths', 'Children'].forEach((nm, i) => s += pill(200 + i * 290, 470, `facet(${nm})`, C.proj, { size: 18, a: f }));
      const rows = [['0', '29', '-1', 'Object'], ['5', '20', '0', 'Array'], ['11', '19', '1', 'Object']];
      const t = win(p, 0.58, 0.7), tx = CX - 400;
      ['open', 'close', 'parent', 'tag'].forEach((h, c) => s += txt(tx + c * 200 + 100, 560, h, { size: 20, fill: C.io, anchor: 'middle', a: t }));
      rows.forEach((r, i) => r.forEach((v, c) => {
        s += rect(tx + c * 200 + 6, 575 + i * 62, 188, 52, { stroke: C.io, fill: C.panel, a: t, sw: 1.5 });
        s += txt(tx + c * 200 + 100, 609 + i * 62, v, { size: 20, anchor: 'middle', a: t });
      }));
      s += txt(CX, 800, 'document  ⇄  ISAM Cursor', { size: 34, fill: C.proj, anchor: 'middle', weight: 700, a: win(p, 0.75, 0.85) });
      s += txt(CX, 845, 'the parse is the table — read once, retained by nothing', { size: 20, fill: C.dim, anchor: 'middle', a: win(p, 0.8, 0.9) });
      return s;
    },
  });

  S.push({
    dur: 10, title: 'The Couch Fortress', law: 'durable ↻', color: C.couch, adds: ['Couch', 'WAL', 'COW B+tree', '_changes', 'CAS block'],
    code: [
      '// CouchWireRouter — the CouchDB 1.6/1.7 HTTP shape',
      'GET  /{db}/{docid}        PUT /{db}/{docid}',
      'GET  /{db}/_changes?since=…',
      'GET  /_cas/{cid}          // content-addressed block',
      'POST /api/v0/block/get    // IPFS alias, same CAS',
      '',
      '// ConfixDocStore · ConfixWal · DurableAppendLog',
      '',
      '// ProductionCouchIngress',
      'if (existingRev == rev) return true // idempotent',
    ],
    say: 'The fortress: a Couch over Confix documents, a write-ahead log and a copy-on-write B+tree. Its wire speaks CouchDB — and every block is also a content address.',
    draw(p) {
      const k = win(p, 0, 0.12);
      let s = rect(CX - 260, 330, 520, 380, { fill: C.panel, stroke: C.couch, sw: 3, a: k });
      // battlements
      for (let i = 0; i < 7; i++) s += rect(CX - 260 + i * 78, 300, 46, 40, { fill: C.panel, stroke: C.couch, sw: 3, r: 3, a: k });
      const layers = [['ConfixDocStore', 'documents are Cursors'], ['COW B+tree', 'never overwritten'], ['WAL', 'append-only frames']];
      layers.forEach(([a, b], i) => s += box(CX - 220, 380 + i * 105, 440, 88, a, C.couch, { a: win(p, 0.12 + i * 0.07, 0.22 + i * 0.07), sub: b, size: 24 }));
      const routes = [['/{db}/{doc}', -1, 380], ['/_changes', -1, 520], ['/_cas/{cid}', 1, 380], ['/api/v0/block', 1, 520]];
      routes.forEach(([r, side, y], i) => {
        const a = win(p, 0.4 + i * 0.06, 0.5 + i * 0.06);
        const xe = CX + side * 260, xs = CX + side * 520;
        s += pill(xs + side * 20, y, r, C.couch, { size: 20, a });
        s += line(xs - side * 80, y, xe, y, { stroke: C.couch, a, dash: '6 6', arrow: true });
      });
      // revision chain
      const rv = win(p, 0.68, 0.8);
      ['1-a1f', '2-c07', '3-9be'].forEach((r, i) => {
        s += pill(CX - 260 + i * 260, 790, `_rev ${r}`, C.idem, { size: 20, a: rv });
        if (i) s += line(CX - 260 + (i - 1) * 260 + 95, 790, CX - 260 + i * 260 - 95, 790, { stroke: C.idem, a: rv, arrow: true });
      });
      s += txt(CX, 850, 'same rev again → already the head: a no-op', { size: 20, fill: C.dim, anchor: 'middle', a: win(p, 0.8, 0.9) });
      return s;
    },
  });

  const ELEMENTS = ['CouchChangesFactElement', 'CouchReportReactorElement', 'IncrementalViewElement', 'ConfixIsamElement',
    'FileWatchReactorElement', 'HtxElement', 'GitReconcileElement', 'HermesConsoleElement'];
  S.push({
    dur: 11, title: 'CCEK Reactors', law: 'context = registry', color: C.lam, adds: ['CCEK', 'SupervisorJob', 'fan-out', 'fan-in'],
    code: [
      '// CCEK = CoroutineContext.Element.Key',
      'class CouchChangesFactElement(…) : CoroutineContext.Element {',
      '    companion object Key :',
      '        AsyncContextKey<CouchChangesFactElement>()',
      '    override val key get() = Key',
      '}',
      '',
      'coroutineContext[CouchChangesFactElement]  // lookup',
      '',
      '// no god object: the context IS the registry',
      '// every CCEK task is a SupervisorJob',
      '// channelization → fan-out → fan-in',
    ],
    say: 'CCEK reactors on every side: each service is a CoroutineContext Element found by its Key. No god object — the coroutine context is the registry, and every reactor runs as its own SupervisorJob.',
    draw(p, T) {
      let s = box(CX - 130, CY - 60, 260, 120, 'Couch', C.couch, { a: win(p, 0, 0.08), sub: 'ConfixDocStore', size: 30 });
      const rx = 430, ry = 300;
      ELEMENTS.forEach((nm, i) => {
        const ang = -Math.PI / 2 + i * Math.PI * 2 / ELEMENTS.length;
        const x = CX + rx * Math.cos(ang), y = CY + ry * Math.sin(ang);
        const a = win(p, 0.08 + i * 0.04, 0.16 + i * 0.04);
        const ex = CX + 150 * Math.cos(ang), ey = CY + 80 * Math.sin(ang);
        s += line(x, y, ex, ey, { stroke: C.faint, a, dash: '4 6' });
        if (p > 0.5) {
          const ph = ((T * 0.6 + i * 0.13) % 1);
          s += dot(ex, ey, x, y, ph, C.lam, 5, a);           // fan-out
          s += dot(x, y, ex, ey, (ph + 0.5) % 1, C.idem, 5, a); // fan-in
        }
        s += pill(x, y, nm, C.lam, { size: 16, a });
      });
      s += txt(CX, 900, 'each element: its own Key · its own SupervisorJob · channels in and out', { size: 20, fill: C.dim, anchor: 'middle', a: win(p, 0.55, 0.65) });
      return s;
    },
  });

  S.push({
    dur: 12, title: 'Transparency', law: 'one Cursor, many faces', color: C.proj, adds: ['SQL (QueryK)', 'PostgREST', 'facet index', 'Graph<N,E>'],
    code: [
      '-- SQL acquiescence: planned onto Cursor stages',
      'SELECT tag, count(*) FROM geometry GROUP BY tag;',
      '',
      '-- PostgREST / Supabase: same AST, same planner',
      'GET /rest/v1/geometry?select=tag&depth=eq.2',
      '',
      '// facets: QueryFacets · index.facet(ConfixIndexK.Tags)',
      '// lines:  LineCas — content-addressed line index',
      '',
      '// graph:  interface Graph<N, E> {',
      '//           outEdges(n); inEdges(n) }   CausalGraph',
    ],
    say: 'Transparency: SQL, PostgREST, facet search and graph walks are not separate databases. They are projections of the same Cursor — one AST, one planner, the same column kernels.',
    draw(p) {
      const k = win(p, 0, 0.1);
      let s = '';
      for (let r = 0; r < 4; r++) for (let c = 0; c < 4; c++) s += rect(CX - 80 + c * 40, CY - 80 + r * 40, 34, 34, { r: 4, stroke: C.join, fill: C.panel, a: k });
      s += txt(CX, CY + 115, 'Cursor', { size: 24, fill: C.join, anchor: 'middle', weight: 700, a: k });
      const faces = [
        ['SQL', 'QueryK · ExprCompiler', 'SELECT … GROUP BY', 260, 250],
        ['PostgREST', 'Supabase /rest/v1', 'GET ?select=…&eq.', 1010, 250],
        ['Facets', 'QueryFacets · LineCas', 'facet(Tags) → counts', 260, 820],
        ['Graph', 'Graph<N,E> · CausalGraph', 'outEdges(n) / inEdges(n)', 1010, 820],
      ];
      faces.forEach(([h, sub, ex, x, y], i) => {
        const a = win(p, 0.12 + i * 0.1, 0.24 + i * 0.1);
        s += grow(CX + (x < CX ? -90 : 90), CY + (y < CY ? -90 : 90), x + (x < CX ? 180 : -180), y + (y < CY ? 70 : -70), a, { stroke: C.proj, dash: '6 6' });
        s += rect(x - 200, y - 75, 400, 150, { fill: C.panel, stroke: C.proj, a });
        s += txt(x, y - 28, h, { size: 30, fill: C.proj, anchor: 'middle', weight: 700, a });
        s += txt(x, y + 10, sub, { size: 18, fill: C.dim, anchor: 'middle', a });
        s += txt(x, y + 48, ex, { size: 18, fill: C.fg, anchor: 'middle', a });
      });
      s += txt(CX, 535 + 175, 'one AST · one planner · same kernels', { size: 22, fill: C.idem, anchor: 'middle', a: win(p, 0.6, 0.7) });
      return s;
    },
  });

  S.push({
    dur: 10, title: 'Unified I/O', law: 'io_uring, all the way up', color: C.io, adds: ['io_uring', 'SQ / CQ', 'userspace Btrfs'],
    code: [
      '// commonMain: real userspace io_uring',
      'Liburing              // SQ / CQ rings',
      'UringIsamOperations   // ISAM page reads',
      'BtrfsUringFileVolume  // userspace Btrfs volume',
      'LiburingFacadeSeam    // SCTP transport',
      '',
      '// submit below, complete below —',
      '// every layer above is a lambda over completions',
    ],
    say: 'Unified I/O: io_uring shot through every layer. ISAM pages, the userspace Btrfs volume and the network transport submit through one liburing seam; completions arrive as values the lambdas above simply read.',
    draw(p, T) {
      const layers = ['Series / α', 'Cursor', 'ISAM', 'Confix', 'Couch', 'CCEK reactors', 'SQL · facets · graph'];
      let s = '';
      layers.forEach((nm, i) => {
        const y = 700 - i * 76, a = win(p, i * 0.04, 0.1 + i * 0.04);
        s += rect(CX - 360, y - 30, 720, 60, { fill: C.panel, stroke: C.faint, a });
        s += txt(CX - 330, y + 8, nm, { size: 22, a });
      });
      const b = win(p, 0.35, 0.55);
      s += rect(CX + 150, lerp(730, 200, b), 40, lerp(0, 530, b), { r: 6, fill: '#3b2f10', stroke: C.io, a: b > 0 ? 1 : 0 });
      if (p > 0.55) for (let j = 0; j < 6; j++) {
        const ph = (T * 0.5 + j / 6) % 1;
        s += circle(CX + 160, lerp(720, 210, ph), 5, { fill: C.io, stroke: 'none' });
        s += circle(CX + 180, lerp(210, 720, ph), 5, { fill: C.idem, stroke: 'none' });
      }
      const u = win(p, 0.2, 0.32), rot = T * 40;
      s += g(circle(0, 0, 50, { stroke: C.io, sw: 8, dash: '18 10', a: u }), 1, `translate(${CX + 70},${810}) rotate(${rot})`);
      s += g(circle(0, 0, 50, { stroke: C.idem, sw: 8, dash: '18 10', a: u }), 1, `translate(${CX + 270},${810}) rotate(${-rot})`);
      s += txt(CX + 70, 817, 'SQ', { size: 20, fill: C.io, anchor: 'middle', a: u });
      s += txt(CX + 270, 817, 'CQ', { size: 20, fill: C.idem, anchor: 'middle', a: u });
      s += txt(CX - 340, 817, 'submit ↑ · complete ↓', { size: 20, fill: C.dim, a: win(p, 0.6, 0.7) });
      return s;
    },
  });

  S.push({
    dur: 11, title: 'Blackboard Agents', law: 'propose, never command', color: C.agent, adds: ['blackboard', 'Rete agenda', 'council seats', 'provenance'],
    code: [
      '// BlackboardSurface — one row of the blackboard',
      'card_id · lane · phase · facet · provenance · causalKey',
      '',
      '// agents propose; rules decide',
      'council.seat  → proposal      // never a command',
      'Rete agenda   → salience, refraction',
      'rule firing   → link to the quoted source sentence',
      '',
      '// guests run revocable, with deadline-bounded host calls',
    ],
    say: 'Agents on a blackboard, in concentric networks. Models only propose; the Rete agenda decides; every conclusion links back to the sentence it came from.',
    draw(p, T) {
      let s = rect(CX - 170, CY - 110, 340, 220, { fill: C.panel, stroke: C.agent, sw: 3, a: win(p, 0, 0.1) });
      s += txt(CX, CY - 75, 'blackboard', { size: 22, fill: C.agent, anchor: 'middle', weight: 700, a: win(p, 0, 0.1) });
      for (let i = 0; i < 6; i++) s += rect(CX - 150 + (i % 3) * 102, CY - 50 + Math.floor(i / 3) * 70, 90, 56, { r: 6, stroke: C.faint, fill: '#152019', a: win(p, 0.1 + i * 0.03, 0.18 + i * 0.03) });
      const rings = [[205, 'rules · Rete · NARS', 6, C.idem], [275, 'local models', 9, C.proj], [345, 'council seats', 12, C.lam]];
      rings.forEach(([r, nm, n, col], k) => {
        const a = win(p, 0.2 + k * 0.1, 0.32 + k * 0.1);
        s += circle(CX, CY, r, { stroke: col, dash: '3 9', a: a * 0.6 });
        s += txt(CX + r * 0.72 + 12, CY - r * 0.72, nm, { size: 18, fill: col, a });
        for (let i = 0; i < n; i++) {
          const ang = i * Math.PI * 2 / n + T * 0.15 * (k % 2 ? -1 : 1);
          const x = CX + r * Math.cos(ang), y = CY + r * Math.sin(ang);
          s += circle(x, y, 10, { fill: col, stroke: 'none', a });
          if (p > 0.55 && (i + k) % 3 === 0) {
            const ph = (T * 0.45 + i * 0.07) % 1;
            s += dot(x, y, CX + 0.4 * (x - CX) * 0.5, CY + 0.4 * (y - CY) * 0.5, ph, col, 4, a * (1 - ph));
          }
        }
      });
      return s;
    },
  });

  S.push({
    dur: 11, title: 'Ouroboros', law: 'code as replicated data', color: C.net, adds: ['IPNS', 'libp2p', 'CID', 'Oroboros'],
    code: [
      '// IPNS over libp2p: Ed25519 identity, yamux, TLS, DHT',
      'IpnsNode · IpnsPublisher · IpnsDht · Libp2pYamux',
      'IpfsBridge   // CAS blocks ⇄ CIDs',
      '',
      '// Oroboros: the daemon absorbs worktree, git,',
      '// agent-environment and runtime changes as',
      '// content-addressed, peer-replicable history —',
      '// and hot-swaps its own code while it runs.',
    ],
    say: 'Ouroboros: the system’s own code, worktree and history become content-addressed blocks — replicated peer to peer over IPNS, and hot-swapped into the running daemon.',
    draw(p, T) {
      let s = '';
      const peers = 6, R = 230;
      const pos = i => [CX + R * Math.cos(i * Math.PI / 3 - Math.PI / 2), CY + R * Math.sin(i * Math.PI / 3 - Math.PI / 2)];
      for (let i = 0; i < peers; i++) for (let j = i + 1; j < peers; j++) {
        if ((j - i) % 3 === 0 || j - i === 1 || (i === 0 && j === 5)) { const [x1, y1] = pos(i), [x2, y2] = pos(j); s += line(x1, y1, x2, y2, { stroke: C.faint, a: win(p, 0.1, 0.2) }); }
      }
      for (let i = 0; i < peers; i++) {
        const [x, y] = pos(i), a = win(p, i * 0.03, 0.08 + i * 0.03);
        s += circle(x, y, 44, { fill: C.panel, stroke: C.net, sw: 3, a });
        s += txt(x, y + 7, `peer ${i}`, { size: 17, fill: C.net, anchor: 'middle', a });
      }
      if (p > 0.3) for (let j = 0; j < 5; j++) {
        const ph = (T * 0.35 + j * 0.2) % 1, a = (j * 2 + 1) % peers, b2 = (j * 5 + 2) % peers;
        const [x1, y1] = pos(a), [x2, y2] = pos(b2);
        const x = lerp(x1, x2, ph), y = lerp(y1, y2, ph);
        s += rect(x - 30, y - 12, 60, 24, { r: 4, fill: '#2b1520', stroke: C.net, sw: 1.5 });
        s += txt(x, y + 6, 'CID', { size: 14, fill: C.net, anchor: 'middle' });
      }
      // ouroboros: an arc that chases its own tail
      const o = win(p, 0.4, 0.75), r = 340, sweep = lerp(0.02, 0.94, o) * Math.PI * 2, start = -Math.PI / 2 + T * 0.35;
      if (o > 0) {
        const end = start + sweep, x1 = CX + r * Math.cos(start), y1 = CY + r * Math.sin(start), x2 = CX + r * Math.cos(end), y2 = CY + r * Math.sin(end);
        s += `<path d="M ${x1} ${y1} A ${r} ${r} 0 ${sweep > Math.PI ? 1 : 0} 1 ${x2} ${y2}" fill="none" stroke="${C.idem}" stroke-width="14" stroke-linecap="round" opacity="0.85"/>`;
        s += circle(x2, y2, 16, { fill: C.idem, stroke: 'none' });
        s += txt(CX, CY + 8, 'worktree · git · runtime', { size: 18, fill: C.idem, anchor: 'middle', a: o });
        s += txt(CX, CY + 36, '→ CAS → peers → hot-swap', { size: 18, fill: C.dim, anchor: 'middle', a: o });
      }
      return s;
    },
  });

  S.push({
    dur: 10, title: 'Serving Models', law: 'the payoff', color: C.fg, adds: ['model serving'],
    code: [
      '// a model is more content on the same fabric:',
      '',
      'weights   → CAS blocks, replicated by CID',
      'requests  → reactor elements on the same rings',
      'output    → a stream the layers above project',
      '',
      '// nothing new was invented to serve it —',
      '// it is a Join of what was already there',
    ],
    say: 'And the payoff: AI models served from this fabric. Weights are content-addressed blocks, requests are reactor events, output is a stream — and the latest master is ready to release.',
    draw(p, T) {
      const names = ['Series', 'Cursor', 'ISAM', 'Confix', 'Couch', 'CCEK', 'faces', 'io_uring', 'blackboard', 'p2p'];
      let s = '';
      const k = win(p, 0, 0.3);
      names.forEach((nm, i) => {
        const r = lerp(40 + i * 34, 30 + i * 16, k);
        const col = [C.lam, C.join, C.io, C.proj, C.couch, C.lam, C.proj, C.io, C.agent, C.net][i];
        s += circle(CX - 120, CY, r, { stroke: col, sw: 2, a: 0.8 });
      });
      s += txt(CX - 120, CY + 8, 'core', { size: 20, anchor: 'middle', fill: C.fg });
      // weights in, tokens out
      const w = win(p, 0.3, 0.45);
      for (let j = 0; j < 4; j++) {
        const ph = (T * 0.4 + j * 0.25) % 1;
        if (w > 0) s += rect(lerp(60, CX - 300, ph) - 40, CY - 160 + j * 90, 80, 30, { r: 4, fill: C.panel, stroke: C.net, a: w });
        if (w > 0) s += txt(lerp(60, CX - 300, ph), CY - 140 + j * 90, 'weights', { size: 14, fill: C.net, anchor: 'middle', a: w });
      }
      s += txt(100, CY - 210, 'CID blocks', { size: 18, fill: C.net, a: w });
      const words = ['the', 'algebra', 'composes', 'into', 'a', 'served', 'model', '…'];
      const n = Math.floor(win(p, 0.45, 0.85) * words.length);
      words.slice(0, n).forEach((wd, i) => s += txt(CX + 130, CY - 170 + i * 46, wd, { size: 26, fill: C.agent, a: 1 }));
      s += txt(CX + 130, CY - 220, 'stream out', { size: 18, fill: C.agent, a: win(p, 0.45, 0.55) });
      s += txt(CX, 880, 'latest master · release-ready', { size: 30, fill: C.idem, anchor: 'middle', weight: 700, a: win(p, 0.8, 0.9) });
      return s;
    },
  });

  S.push({
    dur: 11, title: 'Density', law: 'epilogue', color: C.idem, adds: [],
    code: [
      'density(layer n) = layer(n-1) ⋈ λ',
      '',
      '  ↻  makes every lambda safe to re-run',
      '  α  makes every view free',
      '  λ  without bounds checks keeps it fast',
      '',
      '// Series → Cursor → ISAM → Confix → Couch',
      '// → CCEK → SQL · facets · graph → io_uring',
      '// → blackboard → p2p ouroboros → models',
    ],
    say: 'Compositional density: each layer is the layer below joined with a lambda. Idempotence makes it safe, projection makes it free, and lambdas without bounds checks keep it fast.',
    draw(p) {
      // cumulative concept count per scene, drawn as a rising curve
      const pts = []; let n = 0;
      S.forEach((sc, i) => { n += sc.adds.length; pts.push([i, n]); });
      const X0 = 120, Y0 = 820, W = 1040, H = 520, max = n;
      let s = line(X0, Y0, X0 + W, Y0, { stroke: C.faint, a: win(p, 0, 0.1) }) + line(X0, Y0, X0, Y0 - H, { stroke: C.faint, a: win(p, 0, 0.1) });
      s += txt(X0, Y0 - H - 20, 'concepts composed', { size: 18, fill: C.dim, a: win(p, 0, 0.1) });
      const k = win(p, 0.08, 0.6);
      const shown = pts.slice(0, Math.max(1, Math.ceil(k * pts.length)));
      const xy = ([i, v]) => [X0 + i * W / (pts.length - 1), Y0 - v / max * H];
      s += `<polyline points="${shown.map(xy).map(q => q.join(',')).join(' ')}" fill="none" stroke="${C.idem}" stroke-width="4"/>`;
      shown.forEach((q, i) => { const [x, y] = xy(q); s += circle(x, y, 6, { fill: S[i].color, stroke: 'none' }); if (S[i].adds.length && i % 2 === 1) s += txt(x, Y0 + 30, S[i].title.split(' ')[0], { size: 13, fill: C.dim, anchor: 'middle' }); });
      s += txt(CX, 230, 'Join · λ · α · ↻', { size: 54, fill: C.fg, anchor: 'middle', weight: 800, a: win(p, 0.6, 0.72) });
      s += txt(CX, 880, 'nothing was added that was not a Join, a lambda, a projection, or a re-run', { size: 21, fill: C.idem, anchor: 'middle', a: win(p, 0.72, 0.85) });
      return s;
    },
  });

  // ── timeline ──────────────────────────────────────────────────
  let acc = 0;
  S.forEach(sc => { sc.t0 = acc; acc += sc.dur; });
  const duration = acc;
  const conceptList = [];
  S.forEach((sc, i) => sc.adds.forEach(c => conceptList.push({ c, scene: i, color: sc.color })));

  function densityWall(si, p) {
    const X = 1270, Y = 600, W = 620;
    let s = rect(X, Y, W, 450, { fill: C.panel, stroke: C.line });
    const live = conceptList.filter(o => o.scene < si || (o.scene === si));
    const count = conceptList.filter(o => o.scene < si).length + conceptList.filter(o => o.scene === si).filter((o, j) => p > 0.1 + j * 0.08).length;
    s += txt(X + 18, Y + 34, 'COMPOSITIONAL DENSITY', { size: 16, fill: C.dim, weight: 700 });
    s += txt(X + W - 18, Y + 34, `${count} concepts`, { size: 16, fill: C.idem, anchor: 'end' });
    live.forEach((o, i) => {
      if (i >= count) return;
      const col = i % 4, row = Math.floor(i / 4), fresh = o.scene === si;
      const x = X + 14 + col * 150, y = Y + 54 + row * 31;
      const a = fresh ? win(p, 0.1 + (i - conceptList.findIndex(q => q.scene === si)) * 0.08, 0.16 + (i - conceptList.findIndex(q => q.scene === si)) * 0.08) : 0.75;
      const label = o.c.length > 15 ? o.c.slice(0, 14) + '…' : o.c;
      s += rect(x, y, 142, 26, { r: 5, fill: fresh ? '#1b2232' : C.bg, stroke: o.color, sw: fresh ? 2 : 1, a });
      s += txt(x + 71, y + 18, label, { size: 13, fill: o.color, anchor: 'middle', a });
    });
    return s;
  }

  function wrap(s, max) {
    const out = []; let cur = '';
    s.split(' ').forEach(w => { if ((cur + ' ' + w).trim().length > max) { out.push(cur.trim()); cur = w; } else cur += ' ' + w; });
    if (cur.trim()) out.push(cur.trim());
    return out;
  }

  function frame(t) {
    t = clamp(t, 0, duration - 1e-6);
    const si = S.findIndex(sc => t >= sc.t0 && t < sc.t0 + sc.dur);
    const sc = S[si], T = t - sc.t0, p = T / sc.dur;
    const fade = Math.min(win(T, 0, 0.45), 1 - win(T, sc.dur - 0.45, sc.dur));
    let s = `<rect width="1920" height="1080" fill="${C.bg}"/>`;
    // header
    s += txt(40, 70, `${String(si).padStart(2, '0')} / ${S.length - 1}`, { size: 20, fill: C.dim });
    s += txt(150, 72, sc.title, { size: 40, weight: 800, fill: sc.color, a: fade });
    s += txt(1890, 72, sc.law, { size: 20, fill: C.dim, anchor: 'end', a: fade });
    s += line(40, 105, 1890, 105, { stroke: C.line });
    // stage + code + density
    s += g(sc.draw(p, T), fade);
    s += codePanel(sc.code, p, fade);
    s += densityWall(si, p);
    // caption
    const cap = wrap(sc.say, 74);
    const ca = Math.min(win(p, 0.04, 0.12), 1 - win(T, sc.dur - 0.45, sc.dur));
    cap.forEach((l, i) => s += txt(40, 960 + i * 36 - (cap.length - 2) * 18, l, { size: 25, fill: C.fg, a: ca }));
    // progress
    s += rect(0, 1072, 1920, 8, { r: 0, fill: C.line, stroke: 'none' });
    s += rect(0, 1072, 1920 * t / duration, 8, { r: 0, fill: sc.color, stroke: 'none' });
    return s;
  }

  window.Film = { frame, duration, scenes: S.map(({ t0, dur, title, say }) => ({ t0, dur, title, say })) };
})();
