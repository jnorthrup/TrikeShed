/* Forge workspace — block editor shell.
 * Consumes the baked seed (forge-seed JSON) for initial content,
 * persists workspace state to localStorage, renders a block-based
 * document editor with sidebar navigation, slash commands, and a
 * board view over the same items.
 */
(function () {
  'use strict';

  // ── Seed + persistence ──────────────────────────────────────────────
  const seedEl = document.getElementById('forge-seed');
  let seed = {};
  try { 
      const rawSeed = seedEl ? seedEl.textContent : '{}';
      seed = (typeof parseForge === 'function') ? parseForge(rawSeed) : JSON.parse(rawSeed); 
  } catch (e) { seed = {}; }

  const LS_KEY = 'forge.workspace.v2';
  const BOARD_SEED_KEY = 'forge:seed:board';
  const CAUSAL_SEED_KEY = 'forge:seed:causal';

  function uid() {
    return 'b' + Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);
  }

  function defaultBlocks() {
    return [
      { id: uid(), type: 'h1', text: 'Welcome to Forge' },
      { id: uid(), type: 'p', text: 'This is your workspace. Documents, boards, and graphs are the same underlying content shown from different angles.' },
      { id: uid(), type: 'h2', text: 'Getting started' },
      { id: uid(), type: 'todo', text: 'Click a checkbox to mark it done', checked: false },
      { id: uid(), type: 'todo', text: 'Press / at the start of a line for block types', checked: false },
      { id: uid(), type: 'bullet', text: 'Everything persists locally — no server required' },
      { id: uid(), type: 'quote', text: 'The blackboard is the database. The projection is the page.' },
      { id: uid(), type: 'divider', text: '' },
      { id: uid(), type: 'code', text: '// blocks are typed\n// the graph is causal\n// the board is a projection' },
    ];
  }

  function blocksFromSeed() {
    const entities = Array.isArray(seed.lcncEntities) ? seed.lcncEntities : [];
    if (!entities.length) return null;
    const blocks = [{ id: uid(), type: 'h1', text: 'Ingested corpus' }];
    entities.slice(0, 40).forEach((e) => {
      const title = (e && (e.title || e.name || e.path)) || 'Untitled';
      const kind = (e && (e.lcncKind || e.kind)) || 'entity';
      blocks.push({ id: uid(), type: 'bullet', text: title + '  ·  ' + kind });
    });
    return blocks;
  }

  function defaultWorkbook() {
    const sheetId = uid();
    return {
      sheets: [{
        id: sheetId,
        title: 'Workbook',
        columns: ['Item', 'Qty', 'Price', 'Total'],
        rows: [
          ['Paper', '2', '3.50', '=B1*C1'],
          ['Pens', '5', '1.20', '=B2*C2'],
          ['Total', '', '', '=SUM(D1:D2)'],
        ],
      }],
      activeSheetId: sheetId,
      selected: { row: 0, col: 0 },
    };
  }

  function normalizeWorkbook(workbook) {
    const source = workbook && Array.isArray(workbook.sheets) ? workbook : defaultWorkbook();
    const normalized = {
      sheets: source.sheets.map((sheet, index) => {
        const columns = Array.isArray(sheet.columns) && sheet.columns.length
          ? sheet.columns.map((column) => String(column == null ? '' : column))
          : ['A'];
        const rows = Array.isArray(sheet.rows)
          ? sheet.rows.map((row) => {
              const cells = Array.isArray(row) ? row.slice(0, columns.length) : [];
              while (cells.length < columns.length) cells.push('');
              return cells.map((cell) => String(cell == null ? '' : cell));
            })
          : [];
        return {
          id: sheet.id || uid(),
          title: String(sheet.title || ('Sheet ' + (index + 1))),
          columns,
          rows,
        };
      }),
      activeSheetId: source.activeSheetId,
      selected: source.selected || { row: 0, col: 0 },
    };
    if (!normalized.sheets.length) return defaultWorkbook();
    if (!normalized.sheets.some((sheet) => sheet.id === normalized.activeSheetId)) {
      normalized.activeSheetId = normalized.sheets[0].id;
    }
    normalized.selected = {
      row: Math.max(0, Number(normalized.selected.row) || 0),
      col: Math.max(0, Number(normalized.selected.col) || 0),
    };
    return normalized;
  }

  function normalizeState(loaded) {
    const normalized = loaded || defaultState();
    normalized.workbook = normalizeWorkbook(normalized.workbook);
    normalized.pages.forEach((page) => {
      if (!page.format) page.format = 'document';
      if (!Array.isArray(page.blocks)) page.blocks = [];
    });
    return normalized;
  }

  // The seed is ForgeApp.renderHtml()'s JSON: { userId, source, board:{columns,cards}, causalGraph,
  // correlations, graphLayout, blackboardSeed, dashboards }. Older seeds carried lcncEntities.
  const seedBoard = (seed.board && Array.isArray(seed.board.columns) && seed.board.columns.length) ? seed.board : null;

  function seedColumns() {
    if (seedBoard) {
      return seedBoard.columns
        .slice()
        .sort((a, b) => (a.order || 0) - (b.order || 0))
        .map((c) => ({ id: c.id, name: c.name }));
    }
    return [
      { id: 'todo', name: 'To do' },
      { id: 'doing', name: 'Doing' },
      { id: 'done', name: 'Done' },
    ];
  }

  function defaultState() {
    const homeId = uid();
    return {
      pages: [
        { id: homeId, icon: '▤', title: '', format: 'document', blocks: blocksFromSeed() || defaultBlocks(), children: [] },
      ],
      activePageId: homeId,
      view: 'doc',
      workbook: defaultWorkbook(),
      board: {
        columns: seedColumns(),
        cards: seedCards(),
      },
    };
  }

  function seedCards() {
    if (seedBoard && Array.isArray(seedBoard.cards)) {
      return seedBoard.cards
        .slice()
        .sort((a, b) => (a.order || 0) - (b.order || 0))
        .map((c) => ({
          id: c.id || uid(),
          title: c.title || 'Untitled',
          column: c.columnId,
          meta: [c.priority, (c.dependencies && c.dependencies.length) ? '← ' + c.dependencies.join(', ') : '']
            .filter(Boolean).join('  ·  '),
        }));
    }
    const entities = Array.isArray(seed.lcncEntities) ? seed.lcncEntities : [];
    return entities.slice(0, 12).map((e, i) => ({
      id: uid(),
      title: (e && (e.title || e.name || e.path)) || ('Card ' + (i + 1)),
      column: i % 3 === 0 ? 'doing' : 'todo',
      meta: (e && (e.lcncKind || e.kind)) || '',
    }));
  }

  function loadState() {
    let loaded = null;
    try {
      const raw = localStorage.getItem(LS_KEY);
      if (raw) {
        const parsed = (typeof parseForge === 'function') ? parseForge(raw) : JSON.parse(raw);
        if (parsed && Array.isArray(parsed.pages) && parsed.pages.length) loaded = parsed;
      }
    } catch (e) { /* fall through to default */ }
    
    loaded = normalizeState(loaded || defaultState());
    
    try {
      const boardStr = localStorage.getItem(BOARD_SEED_KEY);
      if (boardStr) {
          const board = (typeof parseForge === 'function') ? parseForge(boardStr) : JSON.parse(boardStr);
          if (board.cards && Array.isArray(board.cards)) {
              loaded.board.cards = board.cards;
          }
      }
    } catch (e) { console.error('Failed to load namespaced seed', e); }
    
    return loaded;
  }

  let state = loadState();

  function saveState() {
    try { 
        const stateStr = (typeof stringifyForge === 'function') ? stringifyForge(state) : JSON.stringify(state);
        localStorage.setItem(LS_KEY, stateStr);
        
        if (state.board && state.board.cards) {
            const boardStr = (typeof stringifyForge === 'function') ? stringifyForge({ cards: state.board.cards }) : JSON.stringify({ cards: state.board.cards });
            localStorage.setItem(BOARD_SEED_KEY, boardStr);
        }
        
        if (seed.causalGraph) {
            const causalStr = (typeof stringifyForge === 'function') ? stringifyForge(seed.causalGraph) : JSON.stringify(seed.causalGraph);
            localStorage.setItem(CAUSAL_SEED_KEY, causalStr);
        }
    } catch (e) { /* quota */ }
  }

  // ── Command Queue → reactor ingress ─────────────────────────────────
  // Every mutation is a command. Commands batch for a short window and POST to ./api/invoke
  // (relative: the same directory the shell was served from). Offline, the service worker
  // answers {status:'queued'} and replays the batch on background sync — local state is
  // already persisted, so the page never waits on the network.
  window.__forgeCommandQueue = window.__forgeCommandQueue || [];
  const INVOKE_URL = new URL('api/invoke', document.baseURI).toString();
  const BOARD_URL = new URL('api/board', document.baseURI).toString();
  const syncNoteEl = document.getElementById('sync-note');
  let flushTimer = null;
  let flushedCount = 0;

  function noteSync(text) { if (syncNoteEl) syncNoteEl.textContent = text; }

  function flushCommands() {
    flushTimer = null;
    const batch = window.__forgeCommandQueue.splice(0, window.__forgeCommandQueue.length);
    if (!batch.length) return;
    const body = JSON.stringify({ userId: seed.userId || 'jim', commands: batch });
    fetch(INVOKE_URL, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body })
      .then((r) => r.json().catch(() => ({})))
      .then((res) => {
        if (res && res.status === 'queued') { noteSync('Offline — ' + batch.length + ' queued for sync'); return; }
        flushedCount += batch.length;
        const rejected = res && res.rejected ? res.rejected : 0;
        noteSync('Synced ' + flushedCount +
          (rejected ? '  ·  ' + rejected + ' rejected' : '') +
          (res && res.sequence != null ? '  ·  seq ' + res.sequence : ''));
        // The server verdict is the truth: reconcile optimistic board state.
        // Force on rejection — the sequence may not have moved, but our optimism did.
        if (res && rejected) hydrateBoard(true);
        else if (res && res.sequence != null && res.sequence !== boardSequence) hydrateBoard();
      })
      .catch(() => noteSync('Local only — reactor unreachable'));
  }

  function scheduleFlush() {
    if (flushTimer) return;
    flushTimer = setTimeout(flushCommands, 400);
  }

  // Doc edits persist LOCALLY (localStorage is their store); only BOARD actions
  // are commands — real JobCommands lowered server-side, no more ACK-and-drop
  // Submits minted per keystroke.
  function mutate(updater) {
    updater(state);
    saveState();
  }

  // ── Board commands: the real spine (invoke → lowering → WAL → projection) ──
  let boardSequence = null;

  function queueBoardCommand(cmd) {
    cmd.idempotencyKey = cmd.idempotencyKey || (cmd.jobId + '#ui#' + Date.now());
    window.__forgeCommandQueue.push(cmd);
    scheduleFlush();
  }

  function hydrateBoard(force) {
    fetch(BOARD_URL)
      .then((r) => r.json())
      .then((b) => {
        if (!b || !Array.isArray(b.columns) || !Array.isArray(b.items)) return;
        if (!force && boardSequence != null && b.sequence === boardSequence) return; // watermark: no change
        boardSequence = b.sequence;
        state.board = {
          columns: b.columns.slice().sort((x, y) => (x.order || 0) - (y.order || 0))
            .map((c) => ({ id: c.id, name: c.name, wipLimit: c.wipLimit, count: c.count })),
          cards: b.items.map((it) => ({
            id: it.id,
            title: it.title || it.id,
            column: it.status,
            revision: it.revision,
            meta: [
              it.contested ? '⚡ contested' : '',
              (it.attention != null) ? 'attn ' + Number(it.attention).toFixed(2) : '',
            ].filter(Boolean).join('  ·  '),
          })),
        };
        saveState();
        if (state.view === 'board') renderBoard();
      })
      .catch(() => {}); // seed/local board stands until the daemon answers
  }

  window.addEventListener('online', () => { noteSync('Back online'); scheduleFlush(); });
  window.addEventListener('offline', () => noteSync('Offline — edits stay local'));


  // ── Element refs ────────────────────────────────────────────────────
  const pageTreeEl = document.getElementById('page-tree');
  const breadcrumbEl = document.getElementById('breadcrumb');
  const titleEl = document.getElementById('doc-title');
  const iconEl = document.getElementById('doc-icon');
  const docToolsEl = document.getElementById('doc-tools');
  const blocksEl = document.getElementById('doc-blocks');
  const docScrollEl = document.getElementById('doc-scroll');
  const boardScrollEl = document.getElementById('board-scroll');
  const boardCanvasEl = document.getElementById('board-canvas');
  const slashMenuEl = document.getElementById('slash-menu');
  const seedNoteEl = document.getElementById('seed-note');

  // ── Block type definitions ──────────────────────────────────────────
  const BLOCK_TYPES = [
    { type: 'p',       name: 'Text',             desc: 'Plain paragraph',            icon: '¶',  placeholder: "Type '/' for commands" },
    { type: 'h1',      name: 'Heading 1',        desc: 'Big section heading',        icon: 'H1', placeholder: 'Heading 1' },
    { type: 'h2',      name: 'Heading 2',        desc: 'Medium section heading',     icon: 'H2', placeholder: 'Heading 2' },
    { type: 'h3',      name: 'Heading 3',        desc: 'Small section heading',      icon: 'H3', placeholder: 'Heading 3' },
    { type: 'todo',    name: 'To-do list',       desc: 'Track tasks with checkboxes', icon: '☑', placeholder: 'To-do' },
    { type: 'bullet',  name: 'Bulleted list',    desc: 'Simple bulleted list',       icon: '•',  placeholder: 'List item' },
    { type: 'numbered',name: 'Numbered list',    desc: 'Numbered list',              icon: '1.', placeholder: 'List item' },
    { type: 'quote',   name: 'Quote',            desc: 'Capture a quotation',        icon: '❝', placeholder: 'Quote' },
    { type: 'code',    name: 'Code',             desc: 'Code block with mono font',  icon: '</>',placeholder: 'Code' },
    { type: 'divider', name: 'Divider',          desc: 'Horizontal rule',            icon: '—', placeholder: '' },
  ];
  const typeDef = (t) => BLOCK_TYPES.find((d) => d.type === t) || BLOCK_TYPES[0];

  // ── Page helpers ────────────────────────────────────────────────────
  function activePage() {
    return state.pages.find((p) => p.id === state.activePageId) || state.pages[0];
  }

  function newPage(title) {
    const page = { id: uid(), icon: '▤', title: title || '', format: 'document', blocks: [], children: [] };
    mutate((s) => {
      s.pages.push(page);
    s.activePageId = page.id;
    });
    return page;
  }

  // ── Render: sidebar ─────────────────────────────────────────────────
  function renderSidebar() {
    pageTreeEl.innerHTML = '';
    state.pages.forEach((page) => {
      const item = document.createElement('div');
      const isActive = page.id === state.activePageId;
      item.className = 'page-tree-item' + (isActive ? ' active' : '');
      item.role = 'button';
      item.tabIndex = 0;
      item.setAttribute('aria-label', (isActive ? 'Active page: ' : 'Page: ') + (page.title || 'Untitled'));
      if (isActive) {
        item.setAttribute('aria-current', 'page');
      }
      const toggle = document.createElement('span');
      toggle.className = 'tree-toggle';
      toggle.textContent = page.children && page.children.length ? '▾' : '▸';
      toggle.setAttribute('aria-hidden', 'true');
      const icon = document.createElement('span');
      icon.className = 'tree-icon';
      icon.textContent = page.icon || '▤';
      icon.setAttribute('aria-hidden', 'true');
      const label = document.createElement('span');
      label.className = 'tree-label' + (page.title ? '' : ' untitled');
      label.textContent = page.title || 'Untitled';
      item.append(toggle, icon, label);
      item.addEventListener('click', () => {
        mutate((s) => { s.activePageId = page.id; });
        renderAll();
      });
      pageTreeEl.appendChild(item);
    });
  }

  // ── Render: document ────────────────────────────────────────────────
  function renderTitle() {
    const page = activePage();
    if (titleEl.textContent !== page.title) titleEl.textContent = page.title;
    iconEl.textContent = page.icon || '▤';
    breadcrumbEl.textContent = 'Private  /  ' + (page.title || 'Untitled');
    renderDocTools();
  }

  titleEl.addEventListener('input', () => {
    mutate(() => { activePage().title = titleEl.textContent; });
    renderSidebar();
    breadcrumbEl.textContent = 'Private  /  ' + (activePage().title || 'Untitled');
  });
  titleEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      const page = activePage();
      if (page.blocks.length === 0) {
        mutate(() => { page.blocks.push({ id: uid(), type: 'p', text: '' }); });
        renderBlocks();
        focusBlock(page.blocks[0].id, true);
      } else {
        focusBlock(page.blocks[0].id, false);
      }
    }
  });

  function renderBlocks() {
    const page = activePage();
    blocksEl.innerHTML = '';
    page.blocks.forEach((block, idx) => {
      blocksEl.appendChild(blockEl(block, idx));
    });
  }

  function blocksToText(page) {
    return page.blocks.map((block) => {
      const text = block.text || '';
      if (block.type === 'h1') return '# ' + text;
      if (block.type === 'h2') return '## ' + text;
      if (block.type === 'h3') return '### ' + text;
      if (block.type === 'bullet') return '- ' + text;
      if (block.type === 'numbered') return '1. ' + text;
      if (block.type === 'todo') return '- [' + (block.checked ? 'x' : ' ') + '] ' + text;
      if (block.type === 'quote') return '> ' + text;
      if (block.type === 'code') return '```\n' + text + '\n```';
      if (block.type === 'divider') return '---';
      return text;
    }).join('\n\n');
  }

  function htmlEscape(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
  }

  function blocksToHtml(page) {
    const body = page.blocks.map((block) => {
      const text = htmlEscape(block.text || '');
      if (block.type === 'h1' || block.type === 'h2' || block.type === 'h3') return '<' + block.type + '>' + text + '</' + block.type + '>';
      if (block.type === 'bullet') return '<ul><li>' + text + '</li></ul>';
      if (block.type === 'numbered') return '<ol><li>' + text + '</li></ol>';
      if (block.type === 'todo') return '<p>[' + (block.checked ? 'x' : ' ') + '] ' + text + '</p>';
      if (block.type === 'quote') return '<blockquote>' + text + '</blockquote>';
      if (block.type === 'code') return '<pre><code>' + text + '</code></pre>';
      if (block.type === 'divider') return '<hr>';
      return '<p>' + text + '</p>';
    }).join('\n');
    return '<!doctype html><html><head><meta charset="utf-8"><title>' + htmlEscape(page.title || 'Document') + '</title></head><body><h1>' + htmlEscape(page.title || 'Untitled') + '</h1>' + body + '</body></html>\n';
  }

  function blocksFromText(text) {
    const blocks = [], lines = String(text || '').split(/\r?\n/);
    let inCode = false, code = [];
    const push = (type, value, checked) => blocks.push({ id: uid(), type, text: value || '', checked: !!checked });
    lines.forEach((line) => {
      if (/^\s*```/.test(line)) {
        if (inCode) { push('code', code.join('\n')); code = []; }
        inCode = !inCode; return;
      }
      if (inCode) { code.push(line); return; }
      let match = /^(#{1,3})\s+(.*)$/.exec(line);
      if (match) { push('h' + match[1].length, match[2]); return; }
      match = /^\s*-\s*\[([ xX])\]\s+(.*)$/.exec(line);
      if (match) { push('todo', match[2], match[1].toLowerCase() === 'x'); return; }
      match = /^\s*[-*+]\s+(.*)$/.exec(line);
      if (match) { push('bullet', match[1]); return; }
      match = /^\s*\d+[.)]\s+(.*)$/.exec(line);
      if (match) { push('numbered', match[1]); return; }
      match = /^\s*>\s?(.*)$/.exec(line);
      if (match) { push('quote', match[1]); return; }
      if (/^\s*-{3,}\s*$/.test(line)) { push('divider', ''); return; }
      if (line.trim()) push('p', line);
    });
    if (inCode && code.length) push('code', code.join('\n'));
    return blocks.length ? blocks : [{ id: uid(), type: 'p', text: '' }];
  }

  function importedTitle(name) {
    return String(name || 'Untitled').replace(/\.[A-Za-z0-9]+$/, '') || 'Untitled';
  }

  function importDocument(name, text, format) {
    const page = { id: uid(), icon: format === 'pdf' ? '▧' : '▤', title: importedTitle(name), format: format || 'document', blocks: blocksFromText(text), children: [] };
    mutate((s) => { s.pages.push(page); s.activePageId = page.id; s.view = 'doc'; });
    renderAll();
    return page;
  }

  function delimitedRow(line, delimiter) {
    const cells = [];
    let value = '', quoted = false, i = 0;
    while (i < line.length) {
      const ch = line[i];
      if (ch === '"') {
        if (quoted && line[i + 1] === '"') { value += '"'; i += 2; continue; }
        quoted = !quoted; i++; continue;
      }
      if (!quoted && ch === delimiter) { cells.push(value); value = ''; i++; continue; }
      value += ch; i++;
    }
    cells.push(value);
    return cells;
  }

  function importWorkbook(name, text) {
    const source = String(text || '').replace(/\r/g, ''), lines = source.split('\n').filter((line) => line.length || source.length === 0);
    const markdownLines = lines.filter((line) => /^\s*\|.*\|\s*$/.test(line));
    const markdownTable = markdownLines.length > 1;
    const delimiter = markdownTable ? '|' : /\t/.test(source) ? '\t' : ',';
    const parsed = (markdownTable ? markdownLines : lines)
      .filter((line) => !(markdownTable && /^\s*\|?\s*:?-{3,}\s*(\|\s*:?-{3,}\s*)+\|?\s*$/.test(line)))
      .map((line) => {
        const row = delimitedRow(line, delimiter);
        return markdownTable ? row.slice(0, row.length - (row[row.length - 1] === '' ? 1 : 0)).slice(row[0] === '' ? 1 : 0).map((cell) => cell.trim()) : row;
      });
    const width = Math.max(1, ...parsed.map((row) => row.length));
    const header = parsed.length ? parsed[0] : [];
    const columns = Array.from({ length: width }, (_, col) => String(header[col] || workbookColumnName(col)));
    const rows = parsed.slice(1).map((row) => {
      const cells = row.slice(0, width); while (cells.length < width) cells.push(''); return cells;
    });
    const id = uid(), sheet = { id, title: importedTitle(name), columns, rows };
    mutate((s) => {
      s.workbook.sheets.push(sheet); s.workbook.activeSheetId = id; s.workbook.selected = { row: 0, col: 0 };
      s.sheetId = id; s.view = 'sheet';
    });
    renderAll();
    return sheet;
  }

  function downloadText(name, text, type) {
    const link = document.createElement('a');
    link.href = URL.createObjectURL(new Blob([text], { type: type || 'text/plain;charset=utf-8' }));
    link.download = name; link.click();
    window.setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  }

  function renderDocTools() {
    if (!docToolsEl) return;
    docToolsEl.innerHTML = '';
    const page = activePage(), format = page.format === 'pdf' ? 'PDF import' : page.format === 'document' ? 'Editable document' : 'Imported document';
    const label = document.createElement('span'); label.className = 'doc-format'; label.textContent = format; docToolsEl.appendChild(label);
    docToolsEl.appendChild(workbookButton('Export text', 'Download this document as plain text', () => {
      downloadText((page.title || 'document').replace(/[^A-Za-z0-9_-]+/g, '-') + '.txt', blocksToText(page));
    }));
    docToolsEl.appendChild(workbookButton('Export HTML', 'Download an editable HTML document', () => {
      downloadText((page.title || 'document').replace(/[^A-Za-z0-9_-]+/g, '-') + '.html', blocksToHtml(page), 'text/html;charset=utf-8');
    }));
    docToolsEl.appendChild(workbookButton('Print / PDF', 'Print or save this document as PDF', () => window.print()));
  }

  function blockEl(block, idx) {
    const def = typeDef(block.type);
    const el = document.createElement('div');
    el.className = 'block block-' + (block.type === 'p' ? 'p' : block.type);
    if (block.type === 'todo' && block.checked) el.classList.add('done');
    el.dataset.blockId = block.id;

    // gutter: + and drag handle
    const gutter = document.createElement('div');
    gutter.className = 'block-gutter';
    const addBtn = document.createElement('button');
    addBtn.className = 'gutter-btn';
    addBtn.textContent = '+';
    addBtn.title = 'Add block below';
    addBtn.setAttribute('aria-label', 'Add block below');
    addBtn.addEventListener('click', () => {
      insertBlock(idx + 1, { id: uid(), type: 'p', text: '' });
      focusBlock(activePage().blocks[idx + 1].id, true);
    });
    const dragBtn = document.createElement('button');
    dragBtn.className = 'gutter-btn gutter-drag';
    dragBtn.textContent = '⋮⋮';
    dragBtn.title = 'Drag to reorder';
    dragBtn.setAttribute('aria-label', 'Drag to reorder block');
    gutter.append(addBtn, dragBtn);
    el.appendChild(gutter);

    if (block.type === 'divider') {
      el.appendChild(document.createElement('hr'));
      return el;
    }

    if (block.type === 'todo') {
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.className = 'todo-checkbox';
      cb.checked = !!block.checked;
      cb.setAttribute('aria-label', 'Toggle todo status');
      cb.addEventListener('change', () => {
        mutate(() => { block.checked = cb.checked; });
        el.classList.toggle('done', cb.checked);
      });
      el.appendChild(cb);
    }

    if (block.type === 'bullet' || block.type === 'numbered') {
      const marker = document.createElement('span');
      marker.className = 'bullet-marker';
      marker.textContent = block.type === 'bullet' ? '•' : (numberedIndex(idx) + '.');
      marker.setAttribute('aria-hidden', 'true');
      el.appendChild(marker);
    }

    const content = document.createElement('div');
    content.className = 'block-content';
    content.contentEditable = 'true';
    content.spellcheck = false;
    content.dataset.placeholder = def.placeholder;
    content.setAttribute('aria-label', 'Block content');
    content.textContent = block.text || '';
    el.appendChild(content);

    content.addEventListener('input', () => {
      mutate(() => { block.text = content.textContent; });
      if (content.textContent === '/') openSlashMenu(block, el);
    });
    content.addEventListener('keydown', (e) => blockKeydown(e, block, idx, content));

    return el;
  }

  function numberedIndex(idx) {
    const blocks = activePage().blocks;
    let n = 1;
    for (let i = idx - 1; i >= 0; i--) {
      if (blocks[i].type === 'numbered') n++;
      else if (blocks[i].type !== 'numbered') break;
    }
    return n;
  }

  function blockKeydown(e, block, idx, content) {
    const page = activePage();
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      closeSlashMenu();
      // headings/quotes exit to paragraph on Enter
      const nextType = (block.type === 'h1' || block.type === 'h2' || block.type === 'h3' || block.type === 'quote') ? 'p' : block.type;
      const tail = content.textContent.slice(getCaretOffset(content));
      content.textContent = content.textContent.slice(0, getCaretOffset(content));
      block.text = content.textContent;
      const next = { id: uid(), type: nextType, text: tail, checked: false };
      insertBlock(idx + 1, next);
      focusBlock(next.id, true);
    } else if (e.key === 'Backspace' && content.textContent === '') {
      e.preventDefault();
      closeSlashMenu();
      const focusTarget = idx > 0 ? page.blocks[idx - 1].id : null;
      mutate(() => {
        page.blocks.splice(idx, 1);
        if (page.blocks.length === 0) page.blocks.push({ id: uid(), type: 'p', text: '' });
      });
      renderBlocks();
      if (focusTarget) {
        focusBlock(focusTarget, false, true);
      } else {
        titleEl.focus();
      }
    } else if (e.key === 'Escape') {
      closeSlashMenu();
    } else if (slashMenuEl.hidden === false && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
      e.preventDefault();
      slashNav(e.key === 'ArrowDown' ? 1 : -1);
    } else if (slashMenuEl.hidden === false && e.key === 'Tab') {
      e.preventDefault();
      slashPick(activeSlashIndex);
    }
  }

  function getCaretOffset(el) {
    const sel = window.getSelection();
    if (!sel.rangeCount) return 0;
    const range = sel.getRangeAt(0).cloneRange();
    range.selectNodeContents(el);
    range.setEnd(sel.getRangeAt(0).endContainer, sel.getRangeAt(0).endOffset);
    return range.toString().length;
  }

  function focusBlock(blockId, atStart, atEnd) {
    const el = blocksEl.querySelector('[data-block-id="' + blockId + '"] .block-content');
    if (!el) return;
    el.focus();
    const range = document.createRange();
    range.selectNodeContents(el);
    range.collapse(!!atStart);
    if (atEnd) range.collapse(false);
    const sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
  }

  function insertBlock(idx, block) {
    mutate(() => { activePage().blocks.splice(idx, 0, block); });
    renderBlocks();
  }

  // ── Slash menu ──────────────────────────────────────────────────────
  let slashBlock = null;
  let slashAnchor = null;
  let activeSlashIndex = 0;
  let slashFilter = '';

  function openSlashMenu(block, anchorEl) {
    slashBlock = block;
    slashAnchor = anchorEl;
    slashFilter = '';
    activeSlashIndex = 0;
    const rect = anchorEl.getBoundingClientRect();
    slashMenuEl.style.left = Math.max(8, rect.left) + 'px';
    slashMenuEl.style.top = (rect.bottom + 6) + 'px';
    renderSlashMenu();
    slashMenuEl.hidden = false;
  }

  function closeSlashMenu() {
    slashMenuEl.hidden = true;
    if (slashBlock) {
      const el = blocksEl.querySelector('[data-block-id="' + slashBlock.id + '"] .block-content');
      if (el && el.textContent === '/') { el.textContent = ''; mutate(() => { slashBlock.text = ''; }); }
    }
    slashBlock = null;
    slashAnchor = null;
  }

  function renderSlashMenu() {
    slashMenuEl.innerHTML = '<div class="slash-menu-label">Basic blocks</div>';
    const items = BLOCK_TYPES.filter((d) =>
      !slashFilter || d.name.toLowerCase().includes(slashFilter.toLowerCase())
    );
    items.forEach((d, i) => {
      const item = document.createElement('button');
      const isActive = i === activeSlashIndex;
      item.className = 'slash-item' + (isActive ? ' active' : '');
      item.setAttribute('aria-label', d.name + ' command' + (isActive ? ' (currently selected)' : '') + ': ' + d.desc);
      const icon = document.createElement('span');
      icon.className = 'slash-item-icon';
      icon.textContent = d.icon;
      icon.setAttribute('aria-hidden', 'true');
      const text = document.createElement('span');
      text.className = 'slash-item-text';
      const name = document.createElement('span');
      name.className = 'slash-item-name';
      name.textContent = d.name;
      const desc = document.createElement('span');
      desc.className = 'slash-item-desc';
      desc.textContent = d.desc;
      text.append(name, desc);
      item.append(icon, text);
      item.addEventListener('click', () => slashApply(d.type));
      slashMenuEl.appendChild(item);
    });
  }

  function slashNav(delta) {
    const count = slashMenuEl.querySelectorAll('.slash-item').length;
    if (!count) return;
    activeSlashIndex = (activeSlashIndex + delta + count) % count;
    renderSlashMenu();
  }

  function slashPick(i) {
    const items = BLOCK_TYPES.filter((d) =>
      !slashFilter || d.name.toLowerCase().includes(slashFilter.toLowerCase())
    );
    if (items[i]) slashApply(items[i].type);
  }

  function slashApply(type) {
    if (!slashBlock) { closeSlashMenu(); return; }
    mutate(() => {
      slashBlock.type = type;
      slashBlock.text = '';
      if (type === 'todo') slashBlock.checked = false;
    });
    const focusTargetId = slashBlock.id;
    closeSlashMenuSilent();
    renderBlocks();
    focusBlock(focusTargetId, true);
  }

  function closeSlashMenuSilent() {
    slashMenuEl.hidden = true;
    slashBlock = null;
    slashAnchor = null;
  }


  // ── Global keyboard accessibility for role="button" ───────────────────
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      if (document.activeElement &&
          document.activeElement.getAttribute('role') === 'button' &&
          document.activeElement.getAttribute('tabindex') === '0') {
        e.preventDefault();
        document.activeElement.click();
      }
    }
  });

  document.addEventListener('mousedown', (e) => {
    if (!slashMenuEl.hidden && !slashMenuEl.contains(e.target)) closeSlashMenu();
  });

  // ── Render: board ───────────────────────────────────────────────────
  function renderBoard() {
    boardCanvasEl.innerHTML = '';
    state.board.columns.forEach((col) => {
      const colEl = document.createElement('div');
      colEl.className = 'board-column';
      const head = document.createElement('div');
      head.className = 'board-column-head';
      const name = document.createElement('span');
      name.textContent = col.name;
      const count = document.createElement('span');
      count.className = 'board-column-count';
      const cards = state.board.cards.filter((c) => c.column === col.id);
      count.textContent = cards.length;
      head.append(name, count);
      colEl.appendChild(head);

      const cardsEl = document.createElement('div');
      cardsEl.className = 'board-cards';
      cards.forEach((card) => {
        const cardEl = document.createElement('div');
        cardEl.className = 'board-card' + (col.id === 'done' ? ' done-card' : '');
        cardEl.role = 'button';
        cardEl.tabIndex = 0;
        const nextColIndex = (state.board.columns.indexOf(col) + 1) % state.board.columns.length;
        const nextColName = state.board.columns[nextColIndex].name;
        cardEl.setAttribute('aria-label', card.title + ' in ' + col.name + '. Activate to move to ' + nextColName);
        const title = document.createElement('div');
        title.className = 'board-card-title';
        title.textContent = card.title;
        cardEl.appendChild(title);
        if (card.meta) {
          const meta = document.createElement('div');
          meta.className = 'board-card-meta';
          meta.textContent = card.meta;
          cardEl.appendChild(meta);
        }
        cardEl.addEventListener('click', () => {
          // cycle columns on click — a REAL Move command now (optimistic UI,
          // the flush verdict re-hydrates on rejection or sequence advance)
          const order = state.board.columns.map((c) => c.id);
          const next = order[(order.indexOf(card.column) + 1) % order.length];
          if (card.revision != null) {
            queueBoardCommand({ type: 'move', jobId: card.id, expectedRevision: card.revision, toColumn: next });
          }
          mutate(() => { card.column = next; });
          renderBoard();
        });
        cardsEl.appendChild(cardEl);
      });
      colEl.appendChild(cardsEl);

      const addBtn = document.createElement('button');
      addBtn.className = 'board-add-card';
      addBtn.textContent = '+ New';
      addBtn.setAttribute('aria-label', 'Add new card to ' + col.name);
      addBtn.addEventListener('click', () => {
        const title = (window.prompt('Card title') || '').trim();
        if (!title) return;
        const jobId = 'card-' + uid();
        queueBoardCommand({ type: 'submit', jobId: jobId, title: title });
        mutate((s) => { s.board.cards.push({ id: jobId, title: title, column: col.id, revision: 1, meta: '' }); });
        renderBoard();
      });
      colEl.appendChild(addBtn);
      boardCanvasEl.appendChild(colEl);
    });
  }

  // ── Render: graph (SVG over the commonMain force layout) ────────────
  // seed.graphLayout = { nodes:[{id,title,x,y,topo}], edges:[{from,to}], camera:{x,y,zoom} }
  // The layout math ran server-side (ForceLayout.kt); here we only draw and move the camera.
  const graphScrollEl = document.getElementById('graph-scroll');
  const graphSvg = document.getElementById('graph-canvas');
  const graphEmptyEl = document.getElementById('graph-empty');
  const graphZoomPill = document.getElementById('graph-zoom-pill');
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const EMPTY_LAYOUT = { nodes: [], edges: [], camera: { x: 0, y: 0, zoom: 1 } };
  function layoutOf(v) { return (v && Array.isArray(v.nodes)) ? v : EMPTY_LAYOUT; }
  // Three sources, one renderer: the causal graph (forceLayout), the concept lattice
  // (ConceptGraph.layoutSeed) and the docs mindmap (DocsGraph.layoutSeed, derived from docs/).
  const layouts = { causal: layoutOf(seed.graphLayout), concept: layoutOf(seed.conceptGraph), docs: layoutOf(seed.docsGraph) };
  const GRAPH_MODES = ['causal', 'concept', 'docs'];
  let graphMode = GRAPH_MODES.includes(state.graphMode) ? state.graphMode : 'causal';
  let layout = layouts[graphMode];
  const cam = { x: 0, y: 0, zoom: 1 };
  function resetCam() { const c = layout.camera || { x: 0, y: 0, zoom: 1 }; cam.x = c.x; cam.y = c.y; cam.zoom = c.zoom; }
  resetCam();
  const NODE_W = 150, NODE_H = 36;
  let graphBuilt = false;
  let graphViewport = null;
  const graphInspector = document.getElementById('graph-inspector');
  const graphModeCausalBtn = document.getElementById('graph-mode-causal');
  const graphModeConceptBtn = document.getElementById('graph-mode-concept');
  const graphModeDocsBtn = document.getElementById('graph-mode-docs');

  function svgEl(tag, attrs) {
    const el = document.createElementNS(SVG_NS, tag);
    Object.keys(attrs || {}).forEach((k) => el.setAttribute(k, attrs[k]));
    return el;
  }

  function setGraphMode(mode) {
    if (!layouts[mode]) return;
    if (mode !== graphMode) { graphMode = mode; layout = layouts[mode]; graphBuilt = false; resetCam(); mutate((s) => { s.graphMode = mode; }, 'graphMode'); }

    const updateBtn = (btn, isAct) => {
      if (!btn) return;
      btn.classList.toggle('active', isAct);
      if (isAct) btn.setAttribute('aria-current', 'page');
      else btn.removeAttribute('aria-current');
    };
    updateBtn(graphModeCausalBtn, graphMode === 'causal');
    updateBtn(graphModeConceptBtn, graphMode === 'concept');
    updateBtn(graphModeDocsBtn, graphMode === 'docs');

    graphEmptyEl.textContent = graphMode === 'concept' ? 'No concept lattice in the seed.'
      : graphMode === 'docs' ? 'No docs mindmap in the seed — bake with a docs/ corpus to populate it.'
      : 'No causal nodes in the seed yet — ingest a donor to populate the graph.';
    graphInspector.hidden = true;
    buildGraph(); applyCamera();
  }

  function inspectNode(n) {
    if (!n) { graphInspector.hidden = true; return; }
    const rels = (layout.edges || []).filter((e) => e.from === n.id || e.to === n.id).map((e) => {
      const other = e.from === n.id ? e.to : e.from;
      const o = (layout.nodes.find((x) => x.id === other) || { title: other }).title;
      return (e.from === n.id ? '→ ' : '← ') + '<b>' + (e.rel || 'parent') + '</b> ' + o;
    });
    graphInspector.innerHTML = '<div class="gi-title">' + n.title + (n.layer ? '<span class="gi-layer">' + n.layer + '</span>' : '') + '</div>' +
      (n.symbol ? '<div class="gi-symbol">' + n.symbol + '</div>' : '') +
      (n.file ? '<div class="gi-file">' + n.file + '</div>' : '<div class="gi-file">' + n.id + '</div>') +
      (rels.length ? '<div class="gi-rels">' + rels.join('<br>') + '</div>' : '');
    graphInspector.hidden = false;
    graphSvg.querySelectorAll('.graph-node.selected').forEach((g) => g.classList.remove('selected'));
    const sel = graphSvg.querySelector('.graph-node[data-id="' + n.id + '"]');
    if (sel) sel.classList.add('selected');
  }

  function buildGraph() {
    if (graphBuilt) return;
    graphBuilt = true;
    graphSvg.innerHTML = '';
    if (!layout.nodes.length) { graphEmptyEl.hidden = false; return; }
    graphEmptyEl.hidden = true;
    const defs = svgEl('defs');
    const marker = svgEl('marker', { id: 'graph-arrow', viewBox: '0 0 10 10', refX: '10', refY: '5', markerWidth: '7', markerHeight: '7', orient: 'auto-start-reverse' });
    marker.appendChild(svgEl('path', { d: 'M 0 0 L 10 5 L 0 10 z', fill: '#aeaca6' }));
    defs.appendChild(marker);
    graphSvg.appendChild(defs);
    graphViewport = svgEl('g', { id: 'graph-viewport' });
    graphSvg.appendChild(graphViewport);

    const byId = {};
    layout.nodes.forEach((n) => { byId[n.id] = n; });
    const edgesG = svgEl('g', { class: 'graph-edges' });
    (layout.edges || []).forEach((e) => {
      const a = byId[e.from], b = byId[e.to];
      if (!a || !b) return;
      const mx = (a.x + b.x) / 2;
      edgesG.appendChild(svgEl('path', {
        class: 'graph-edge' + (e.rel ? ' rel-' + e.rel : ''),
        d: 'M ' + a.x + ' ' + (a.y + NODE_H / 2) + ' C ' + mx + ' ' + (a.y + NODE_H / 2) + ', ' + mx + ' ' + (b.y - NODE_H / 2) + ', ' + b.x + ' ' + (b.y - NODE_H / 2),
      }));
    });
    graphViewport.appendChild(edgesG);

    const nodesG = svgEl('g', { class: 'graph-nodes' });
    layout.nodes.forEach((n) => {
      const g = svgEl('g', { class: 'graph-node' + (n.layer ? ' layer-' + n.layer : ''), 'data-id': n.id, transform: 'translate(' + (n.x - NODE_W / 2) + ',' + (n.y - NODE_H / 2) + ')', tabindex: '0', role: 'button' });
      g.setAttribute('aria-label', n.title + ' (topo ' + n.topo + ')');
      g.addEventListener('click', (ev) => { ev.stopPropagation(); inspectNode(n); });
      g.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') inspectNode(n); });
      g.appendChild(svgEl('rect', { width: NODE_W, height: NODE_H }));
      const label = svgEl('text', { x: 10, y: 22 });
      label.textContent = n.title.length > 18 ? n.title.slice(0, 17) + '…' : n.title;
      g.appendChild(label);
      const topo = svgEl('text', { x: NODE_W - 8, y: 12, 'text-anchor': 'end', class: 'graph-node-topo' });
      topo.textContent = '#' + n.topo;
      g.appendChild(topo);
      const title = svgEl('title');
      title.textContent = n.title + '\n' + n.id;
      g.appendChild(title);
      nodesG.appendChild(g);
    });
    graphViewport.appendChild(nodesG);
    if (Array.isArray(layout.layers) && layout.layers.length) {
      // Column captions for the lattice: lib → cursor → confix → facets → surface → widgets.
      const byLayer = {};
      layout.nodes.forEach((n) => { if (!byLayer[n.layer] || n.y < byLayer[n.layer].y) byLayer[n.layer] = n; });
      const labelsG = svgEl('g', { class: 'graph-layer-labels' });
      layout.layers.forEach((l) => {
        const top = byLayer[l]; if (!top) return;
        const t = svgEl('text', { class: 'graph-layer-label', x: top.x, y: top.y - NODE_H, 'text-anchor': 'middle' });
        t.textContent = l; labelsG.appendChild(t);
      });
      graphViewport.appendChild(labelsG);
    }
    applyCamera();
  }
  graphModeCausalBtn.addEventListener('click', () => setGraphMode('causal'));
  graphModeConceptBtn.addEventListener('click', () => setGraphMode('concept'));
  if (graphModeDocsBtn) graphModeDocsBtn.addEventListener('click', () => setGraphMode('docs'));
  graphSvg.addEventListener('click', () => inspectNode(null));

  // world → screen: translate(-cam) → scale(zoom) → center in viewport (ForgeBlackboardCamera convention)
  function applyCamera() {
    if (!graphViewport) return;
    const w = graphScrollEl.clientWidth || 800, h = graphScrollEl.clientHeight || 600;
    graphViewport.setAttribute('transform',
      'translate(' + (w / 2) + ',' + (h / 2) + ') scale(' + cam.zoom + ') translate(' + (-cam.x) + ',' + (-cam.y) + ')');
    if (graphZoomPill) graphZoomPill.textContent = Math.round(cam.zoom * 100) + '%';
  }

  function fitGraph() {
    if (!layout.nodes.length) return;
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    layout.nodes.forEach((n) => {
      minX = Math.min(minX, n.x - NODE_W); maxX = Math.max(maxX, n.x + NODE_W);
      minY = Math.min(minY, n.y - NODE_H); maxY = Math.max(maxY, n.y + NODE_H);
    });
    const w = graphScrollEl.clientWidth || 800, h = graphScrollEl.clientHeight || 600;
    cam.x = (minX + maxX) / 2; cam.y = (minY + maxY) / 2;
    cam.zoom = Math.max(0.1, Math.min(3.2, Math.min(w / (maxX - minX), h / (maxY - minY)) * 0.9));
    applyCamera();
  }

  (function wireGraphGestures() {
    let dragging = false, lastX = 0, lastY = 0;
    graphSvg.addEventListener('pointerdown', (e) => {
      dragging = true; lastX = e.clientX; lastY = e.clientY;
      graphSvg.classList.add('dragging'); graphSvg.setPointerCapture(e.pointerId);
    });
    graphSvg.addEventListener('pointermove', (e) => {
      if (!dragging) return;
      cam.x -= (e.clientX - lastX) / cam.zoom; cam.y -= (e.clientY - lastY) / cam.zoom;
      lastX = e.clientX; lastY = e.clientY; applyCamera();
    });
    const end = () => { dragging = false; graphSvg.classList.remove('dragging'); };
    graphSvg.addEventListener('pointerup', end);
    graphSvg.addEventListener('pointercancel', end);
    graphSvg.addEventListener('wheel', (e) => {
      e.preventDefault();
      const factor = Math.exp(-e.deltaY * 0.0015);
      const rect = graphSvg.getBoundingClientRect();
      const w = rect.width, h = rect.height;
      // zoom around the pointer: keep the world point under the cursor fixed
      const wx = cam.x + (e.clientX - rect.left - w / 2) / cam.zoom;
      const wy = cam.y + (e.clientY - rect.top - h / 2) / cam.zoom;
      const next = Math.max(0.1, Math.min(3.2, cam.zoom * factor));
      const ratio = cam.zoom / next;
      cam.x = wx - (wx - cam.x) * ratio; cam.y = wy - (wy - cam.y) * ratio; cam.zoom = next;
      applyCamera();
    }, { passive: false });
    document.getElementById('graph-fit').addEventListener('click', fitGraph);
    window.addEventListener('resize', applyCamera);
    document.addEventListener('keydown', (e) => {
      if (graphScrollEl.hidden || e.target.isContentEditable) return;
      if (e.key === 'f') fitGraph();
      if (e.key === '+' || e.key === '=') { cam.zoom = Math.min(3.2, cam.zoom * 1.2); applyCamera(); }
      if (e.key === '-') { cam.zoom = Math.max(0.1, cam.zoom / 1.2); applyCamera(); }
    });
  })();

  // ── View switching ──────────────────────────────────────────────────
  // ── Render: sheets (TreeSheets idiom — a cell may hold another sheet) ─────
  // seed.sheets = [{id,title,parent,columns:[{name,type}],rows:[[cell]]}]; cell = scalar | {sheet:id}
  const sheetScrollEl = document.getElementById('sheet-scroll');
  const sheetTabsEl = document.getElementById('sheet-tabs');
  const sheetCrumbsEl = document.getElementById('sheet-crumbs');
  const sheetToolbarEl = document.getElementById('sheet-toolbar');
  const sheetWrapEl = document.getElementById('sheet-grid-wrap');
  const sheetEmptyEl = document.getElementById('sheet-empty');
  const sheets = Array.isArray(seed.sheets) ? seed.sheets : [];
  const sheetById = {};
  sheets.forEach((sh) => { sheetById[sh.id] = sh; });
  const rootSheets = sheets.filter((sh) => !sh.parent);
  let sheetExpanded = state.sheetExpanded || {};   // nested refs opened inline, keyed by sheetId|row|col
  let sheetSort = {};                               // sheetId -> {col, dir}

  function currentSheetId() {
    const id = state.sheetId;
    if (id && (sheetById[id] || workbookSheetById(id))) return id;
    return state.workbook.activeSheetId || (rootSheets[0] ? rootSheets[0].id : null);
  }

  function workbookSheetById(id) {
    return state.workbook && state.workbook.sheets.find((sheet) => sheet.id === id);
  }

  function workbookColumnName(index) {
    let n = index + 1, label = '';
    while (n > 0) {
      const rest = (n - 1) % 26;
      label = String.fromCharCode(65 + rest) + label;
      n = Math.floor((n - 1) / 26);
    }
    return label;
  }

  function parseWorkbookRef(value) {
    const match = /^([A-Z]+)([1-9][0-9]*)$/i.exec(String(value || ''));
    if (!match) return null;
    let col = 0;
    match[1].toUpperCase().split('').forEach((ch) => { col = col * 26 + ch.charCodeAt(0) - 64; });
    return { row: Number(match[2]) - 1, col: col - 1 };
  }

  function rawWorkbookCell(sheet, row, col) {
    return sheet.rows[row] && sheet.rows[row][col] != null ? sheet.rows[row][col] : '';
  }

  function workbookNumber(value) {
    if (typeof value === 'number' && Number.isFinite(value)) return value;
    if (typeof value === 'boolean') return value ? 1 : 0;
    if (value == null || String(value).trim() === '') return 0;
    const number = Number(value);
    if (Number.isFinite(number)) return number;
    throw new Error('not numeric');
  }

  function workbookTruthy(value) {
    if (Array.isArray(value)) return value.length > 0;
    if (typeof value === 'boolean') return value;
    if (typeof value === 'number') return value !== 0;
    return String(value == null ? '' : value).trim() !== '';
  }

  function workbookRange(sheet, start, end, stack) {
    const values = [];
    const first = parseWorkbookRef(start), last = parseWorkbookRef(end);
    if (!first || !last) throw new Error('bad range');
    const rowStart = Math.min(first.row, last.row), rowEnd = Math.max(first.row, last.row);
    const colStart = Math.min(first.col, last.col), colEnd = Math.max(first.col, last.col);
    for (let row = rowStart; row <= rowEnd; row++) {
      for (let col = colStart; col <= colEnd; col++) {
        values.push(evaluateWorkbookCell(sheet, row, col, stack));
      }
    }
    return values;
  }

  function workbookFunction(name, args) {
    const flat = [];
    args.forEach((arg) => { if (Array.isArray(arg)) flat.push(...arg); else flat.push(arg); });
    const numeric = flat.filter((value) => {
      if (value == null || String(value).trim() === '') return false;
      return Number.isFinite(Number(value));
    }).map((value) => Number(value));
    switch (name.toUpperCase()) {
      case 'SUM': return numeric.reduce((sum, value) => sum + value, 0);
      case 'PRODUCT': return numeric.reduce((product, value) => product * value, 1);
      case 'AVERAGE': return numeric.length ? numeric.reduce((sum, value) => sum + value, 0) / numeric.length : 0;
      case 'MIN': return numeric.length ? Math.min(...numeric) : 0;
      case 'MAX': return numeric.length ? Math.max(...numeric) : 0;
      case 'COUNT': return numeric.length;
      case 'COUNTA': return flat.filter((value) => value != null && String(value) !== '').length;
      case 'ABS': return Math.abs(workbookNumber(args[0]));
      case 'ROUND': {
        const digits = args.length > 1 ? workbookNumber(args[1]) : 0;
        const factor = Math.pow(10, digits);
        return Math.round(workbookNumber(args[0]) * factor) / factor;
      }
      case 'IF': return workbookTruthy(args[0]) ? (args.length > 1 ? args[1] : true) : (args.length > 2 ? args[2] : false);
      default: throw new Error('unknown function');
    }
  }

  function formulaTokens(formula) {
    const source = String(formula).slice(1), tokens = [];
    let i = 0;
    while (i < source.length) {
      const ch = source[i];
      if (/\s/.test(ch)) { i++; continue; }
      if (ch === '"' || ch === "'") {
        const quote = ch; let value = ''; i++;
        while (i < source.length && source[i] !== quote) value += source[i++];
        if (source[i] !== quote) throw new Error('unterminated text');
        i++; tokens.push({ kind: 'value', value }); continue;
      }
      if (/[0-9.]/.test(ch)) {
        const match = /^(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?/.exec(source.slice(i));
        if (!match) throw new Error('bad number');
        i += match[0].length; tokens.push({ kind: 'number', value: Number(match[0]) }); continue;
      }
      if (/[A-Za-z_]/.test(ch)) {
        const match = /^[A-Za-z_][A-Za-z0-9_]*/.exec(source.slice(i));
        i += match[0].length; tokens.push({ kind: 'word', value: match[0] }); continue;
      }
      if ('+-*/^(),:'.includes(ch)) { tokens.push({ kind: ch, value: ch }); i++; continue; }
      throw new Error('bad token');
    }
    return tokens;
  }

  function evaluateFormula(sheet, formula, stack) {
    const tokens = formulaTokens(formula); let pos = 0;
    const peek = () => tokens[pos];
    const take = () => tokens[pos++];
    function primary() {
      const token = take();
      if (!token) throw new Error('missing value');
      if (token.kind === 'number' || token.kind === 'value') return token.value;
      if (token.kind === '(') { const value = addSub(); if (!take() || tokens[pos - 1].kind !== ')') throw new Error('missing )'); return value; }
      if (token.kind !== 'word') throw new Error('expected value');
      const word = token.value, next = peek();
      if (next && next.kind === ':') {
        take(); const finish = take();
        if (!finish || finish.kind !== 'word') throw new Error('bad range');
        return workbookRange(sheet, word, finish.value, stack);
      }
      if (next && next.kind === '(') {
        take(); const args = [];
        if (!peek() || peek().kind !== ')') {
          while (true) {
            args.push(addSub());
            if (peek() && peek().kind === ',') { take(); continue; }
            break;
          }
        }
        if (!take() || tokens[pos - 1].kind !== ')') throw new Error('missing )');
        return workbookFunction(word, args);
      }
      if (word.toUpperCase() === 'TRUE') return true;
      if (word.toUpperCase() === 'FALSE') return false;
      const ref = parseWorkbookRef(word);
      if (!ref) throw new Error('unknown name');
      return evaluateWorkbookCell(sheet, ref.row, ref.col, stack);
    }
    function unary() {
      if (peek() && (peek().kind === '+' || peek().kind === '-')) {
        const sign = take().kind === '-' ? -1 : 1;
        return sign * workbookNumber(unary());
      }
      return primary();
    }
    function power() {
      const left = unary();
      if (peek() && peek().kind === '^') { take(); return Math.pow(workbookNumber(left), workbookNumber(power())); }
      return left;
    }
    function mulDiv() {
      let value = power();
      while (peek() && (peek().kind === '*' || peek().kind === '/')) {
        const op = take().kind, right = workbookNumber(power());
        value = op === '*' ? workbookNumber(value) * right : workbookNumber(value) / right;
      }
      return value;
    }
    function addSub() {
      let value = mulDiv();
      while (peek() && (peek().kind === '+' || peek().kind === '-')) {
        const op = take().kind, right = workbookNumber(mulDiv());
        value = op === '+' ? workbookNumber(value) + right : workbookNumber(value) - right;
      }
      return value;
    }
    const value = addSub();
    if (pos !== tokens.length) throw new Error('unexpected token');
    return value;
  }

  function evaluateWorkbookCell(sheet, row, col, stack) {
    const raw = rawWorkbookCell(sheet, row, col);
    if (typeof raw !== 'string' || raw.charAt(0) !== '=') {
      if (raw === true || raw === false) return raw;
      const number = raw !== '' && Number.isFinite(Number(raw)) ? Number(raw) : raw;
      return number;
    }
    const key = sheet.id + '!' + row + ':' + col;
    const active = stack || {};
    if (active[key]) throw new Error('circular reference');
    active[key] = true;
    try {
      const value = evaluateFormula(sheet, raw, active);
      delete active[key];
      return value;
    } catch (error) {
      delete active[key];
      throw error;
    }
  }

  function workbookDisplay(sheet, row, col) {
    try {
      const value = evaluateWorkbookCell(sheet, row, col, {});
      if (value == null) return '';
      if (typeof value === 'number') return String(Math.round(value * 10000000000) / 10000000000);
      return String(value);
    } catch (error) {
      return '#ERR';
    }
  }

  function isRef(cell) { return cell && typeof cell === 'object' && typeof cell.sheet === 'string'; }

  function cellText(cell) {
    if (cell === null || cell === undefined) return '';
    if (typeof cell === 'object') return JSON.stringify(cell);
    return String(cell);
  }

  function sortedRows(sh) {
    const srt = sheetSort[sh.id];
    const rows = sh.rows.map((r, i) => ({ r, i }));
    if (!srt) return rows;
    const c = srt.col, dir = srt.dir;
    rows.sort((a, b) => {
      const x = a.r[c], y = b.r[c];
      const xs = isRef(x) ? '\uffff' + x.sheet : cellText(x), ys = isRef(y) ? '\uffff' + y.sheet : cellText(y);
      const xn = Number(xs), yn = Number(ys);
      const cmp = (!isNaN(xn) && !isNaN(yn) && xs !== '' && ys !== '') ? xn - yn : xs.localeCompare(ys);
      return dir === 'desc' ? -cmp : cmp;
    });
    return rows;
  }

  function buildSheetTable(sh, depth) {
    const table = document.createElement('table');
    table.className = 'sheet';
    table.dataset.sheet = sh.id;
    const thead = document.createElement('thead');
    const hr = document.createElement('tr');
    sh.columns.forEach((col, ci) => {
      const th = document.createElement('th');
      th.textContent = col.name;
      const ty = document.createElement('span'); ty.className = 'sheet-type'; ty.textContent = col.type; th.appendChild(ty);
      const srt = sheetSort[sh.id];
      if (srt && srt.col === ci) th.classList.add(srt.dir === 'desc' ? 'sorted-desc' : 'sorted-asc');
      th.addEventListener('click', () => {
        const cur = sheetSort[sh.id];
        sheetSort[sh.id] = (cur && cur.col === ci && cur.dir === 'asc') ? { col: ci, dir: 'desc' } : { col: ci, dir: 'asc' };
        renderSheet();
      });
      hr.appendChild(th);
    });
    thead.appendChild(hr); table.appendChild(thead);
    const tbody = document.createElement('tbody');
    sortedRows(sh).forEach(({ r, i }) => {
      const tr = document.createElement('tr');
      r.forEach((cell, ci) => {
        const td = document.createElement('td');
        td.tabIndex = 0;
        td.dataset.row = i; td.dataset.col = ci;
        if (isRef(cell)) {
          td.className = 'sheet-ref-cell';
          const key = sh.id + '|' + i + '|' + ci;
          const child = sheetById[cell.sheet];
          const ref = document.createElement('button');
          ref.className = 'sheet-ref';
          const isExpanded = !!sheetExpanded[key];
          ref.setAttribute('aria-expanded', isExpanded ? 'true' : 'false');
          const sheetName = child ? child.title.split('/').pop() : cell.sheet;
          ref.setAttribute('aria-label', (isExpanded ? 'Collapse' : 'Expand') + ' sheet reference: ' + sheetName);
          ref.innerHTML = '<span aria-hidden="true">' + (isExpanded ? '▾' : '▸') + '</span><span>▦ ' + sheetName + '</span>' +
            '<span class="sheet-count">' + (child ? child.rows.length + ' rows' : '') + '</span>';
          ref.title = 'Click: expand in place · Open: zoom into ' + cell.sheet;
          ref.setAttribute('aria-expanded', sheetExpanded[key] ? 'true' : 'false');
          ref.setAttribute('aria-label', (sheetExpanded[key] ? 'Collapse ' : 'Expand ') + (child ? child.title.split('/').pop() : cell.sheet));
          ref.addEventListener('click', (ev) => {
            ev.stopPropagation();
            sheetExpanded[key] = !sheetExpanded[key];
            mutate((s) => { s.sheetExpanded = sheetExpanded; }, 'sheetExpanded');
            renderSheet();
          });
          td.appendChild(ref);
          const open = document.createElement('button');
          open.className = 'sheet-ref sheet-ref-open'; open.textContent = 'open ↗'; open.title = 'Zoom into this sheet';
          open.setAttribute('aria-label', 'Zoom into sheet ' + cell.sheet);
          open.addEventListener('click', (ev) => { ev.stopPropagation(); openSheet(cell.sheet); });
          td.appendChild(open);
          if (sheetExpanded[key] && child && depth < 6) td.appendChild(buildSheetTable(child, depth + 1));
        } else {
          td.textContent = cellText(cell);
        }
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    return table;
  }

  function selectedWorkbookCell(sheet) {
    const selected = state.workbook.selected || { row: 0, col: 0 };
    return {
      row: Math.min(Math.max(0, selected.row || 0), Math.max(0, sheet.rows.length - 1)),
      col: Math.min(Math.max(0, selected.col || 0), Math.max(0, sheet.columns.length - 1)),
    };
  }

  function focusWorkbookCell(row, col) {
    Array.from(sheetWrapEl.querySelectorAll('td.workbook-cell.selected')).forEach((cell) => cell.classList.remove('selected'));
    const input = Array.from(sheetWrapEl.querySelectorAll('input[data-workbook-cell="true"]'))
      .find((cell) => Number(cell.dataset.row) === row && Number(cell.dataset.col) === col);
    if (input) { input.closest('td').classList.add('selected'); input.focus(); input.select(); }
  }

  function updateWorkbookFormulaBar(sheet, row, col, value) {
    const formula = document.getElementById('sheet-formula');
    if (formula && document.activeElement !== formula) formula.value = value != null ? value : rawWorkbookCell(sheet, row, col);
    const status = document.getElementById('sheet-status');
    if (status) status.textContent = workbookColumnName(col) + (row + 1) + '  ·  ' + workbookDisplay(sheet, row, col);
  }

  function refreshWorkbookValues(sheet) {
    Array.from(sheetWrapEl.querySelectorAll('input[data-workbook-cell="true"]')).forEach((input) => {
      const row = Number(input.dataset.row), col = Number(input.dataset.col), raw = rawWorkbookCell(sheet, row, col);
      input.dataset.raw = raw;
      if (document.activeElement !== input) input.value = String(raw).charAt(0) === '=' ? workbookDisplay(sheet, row, col) : raw;
      const td = input.closest('td');
      if (td) {
        td.classList.toggle('formula', String(raw).charAt(0) === '=');
        td.classList.toggle('error', String(raw).charAt(0) === '=' && workbookDisplay(sheet, row, col) === '#ERR');
        td.title = String(raw).charAt(0) === '=' ? raw + '  →  ' + workbookDisplay(sheet, row, col) : '';
      }
    });
  }

  function commitWorkbookCell(sheet, row, col, value, next) {
    const text = String(value == null ? '' : value), old = rawWorkbookCell(sheet, row, col);
    mutate((s) => {
      const target = workbookSheetById(sheet.id);
      while (target.rows.length <= row) target.rows.push(new Array(target.columns.length).fill(''));
      target.rows[row][col] = text;
      s.workbook.selected = { row, col };
      s.workbook.activeSheetId = sheet.id;
      s.sheetId = sheet.id;
    }, 'workbookCell');
    refreshWorkbookValues(sheet);
    updateWorkbookFormulaBar(sheet, row, col, text);
    if (next) window.setTimeout(() => focusWorkbookCell(next.row, next.col), 0);
    return old !== text;
  }

  function workbookButton(label, title, action) {
    const button = document.createElement('button');
    button.className = 'topbar-btn'; button.type = 'button'; button.textContent = label; button.title = title; button.setAttribute('aria-label', title);
    button.addEventListener('click', action);
    return button;
  }

  function addWorkbookSheet() {
    const id = uid(), index = state.workbook.sheets.length + 1;
    mutate((s) => {
      s.workbook.sheets.push({ id, title: 'Sheet ' + index, columns: ['A', 'B', 'C'], rows: [['', '', '']] });
      s.workbook.activeSheetId = id; s.workbook.selected = { row: 0, col: 0 }; s.sheetId = id;
    }, 'workbookSheet');
    renderSheet();
    window.setTimeout(() => focusWorkbookCell(0, 0), 0);
  }

  function addWorkbookRow(sheet) {
    mutate((s) => {
      const target = workbookSheetById(sheet.id);
      target.rows.push(new Array(target.columns.length).fill(''));
      s.workbook.selected = { row: target.rows.length - 1, col: 0 };
    }, 'workbookRow');
    renderSheet();
    window.setTimeout(() => focusWorkbookCell(sheet.rows.length - 1, 0), 0);
  }

  function addWorkbookColumn(sheet) {
    mutate((s) => {
      const target = workbookSheetById(sheet.id);
      target.columns.push(workbookColumnName(target.columns.length));
      target.rows.forEach((row) => row.push(''));
      s.workbook.selected = { row: 0, col: target.columns.length - 1 };
    }, 'workbookColumn');
    renderSheet();
    window.setTimeout(() => focusWorkbookCell(0, sheet.columns.length - 1), 0);
  }

  function exportWorkbookCsv(sheet) {
    const rows = [sheet.columns].concat(sheet.rows);
    const csv = rows.map((row) => row.map((value) => {
      const text = String(value == null ? '' : value);
      return /[",\n]/.test(text) ? '"' + text.replace(/"/g, '""') + '"' : text;
    }).join(',')).join('\n');
    const link = document.createElement('a');
    link.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    link.download = (sheet.title || 'workbook').replace(/[^A-Za-z0-9_-]+/g, '-') + '.csv';
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  }

  function renderWorkbookToolbar(sheet) {
    sheetToolbarEl.innerHTML = '';
    const name = document.createElement('span'); name.className = 'sheet-name'; name.textContent = sheet.title;
    sheetToolbarEl.appendChild(name);
    sheetToolbarEl.appendChild(workbookButton('New sheet', 'Add an editable sheet', addWorkbookSheet));
    sheetToolbarEl.appendChild(workbookButton('Add row', 'Append a row', () => addWorkbookRow(sheet)));
    sheetToolbarEl.appendChild(workbookButton('Add column', 'Append a column', () => addWorkbookColumn(sheet)));
    sheetToolbarEl.appendChild(workbookButton('Export CSV', 'Download this sheet as CSV', () => exportWorkbookCsv(sheet)));
    const formula = document.createElement('input');
    formula.id = 'sheet-formula'; formula.className = 'sheet-formula'; formula.type = 'text';
    formula.placeholder = 'Formula or value'; formula.setAttribute('aria-label', 'Selected cell formula');
    const selected = selectedWorkbookCell(sheet);
    formula.value = rawWorkbookCell(sheet, selected.row, selected.col);
    const commitFormula = () => {
      const current = selectedWorkbookCell(sheet);
      commitWorkbookCell(sheet, current.row, current.col, formula.value);
      formula.value = rawWorkbookCell(sheet, current.row, current.col);
    };
    formula.addEventListener('keydown', (event) => {
      const current = selectedWorkbookCell(sheet);
      if (event.key === 'Enter') { event.preventDefault(); commitFormula(); focusWorkbookCell(current.row, current.col); }
      if (event.key === 'Escape') { formula.value = rawWorkbookCell(sheet, current.row, current.col); formula.blur(); }
    });
    formula.addEventListener('blur', commitFormula);
    sheetToolbarEl.appendChild(formula);
    const status = document.createElement('span'); status.id = 'sheet-status'; status.className = 'sheet-status';
    sheetToolbarEl.appendChild(status);
    const help = document.createElement('span'); help.className = 'sheet-help'; help.textContent = 'Use =SUM(A1:A3), +, −, ×, ÷, IF, or ROUND';
    sheetToolbarEl.appendChild(help);
    updateWorkbookFormulaBar(sheet, selected.row, selected.col);
  }

  function renderReadOnlyToolbar(sheet) {
    sheetToolbarEl.innerHTML = '';
    const name = document.createElement('span'); name.className = 'sheet-name'; name.textContent = sheet ? sheet.title : 'Source sheets';
    sheetToolbarEl.appendChild(name);
    sheetToolbarEl.appendChild(workbookButton('New sheet', 'Add an editable sheet', addWorkbookSheet));
    const status = document.createElement('span'); status.className = 'sheet-status'; status.textContent = 'Read-only source projection';
    sheetToolbarEl.appendChild(status);
  }

  function buildWorkbookTable(sheet) {
    const table = document.createElement('table');
    table.className = 'sheet workbook-sheet'; table.dataset.sheet = sheet.id;
    const head = document.createElement('thead'), header = document.createElement('tr');
    const corner = document.createElement('th'); corner.textContent = '#'; corner.setAttribute('aria-label', 'Row number'); header.appendChild(corner);
    sheet.columns.forEach((column, col) => {
      const th = document.createElement('th');
      th.textContent = workbookColumnName(col) + '  ' + (column || 'Column ' + (col + 1));
      header.appendChild(th);
    });
    head.appendChild(header); table.appendChild(head);
    const body = document.createElement('tbody'), rowCount = Math.max(1, sheet.rows.length);
    const selected = selectedWorkbookCell(sheet);
    for (let row = 0; row < rowCount; row++) {
      const tr = document.createElement('tr');
      const number = document.createElement('th'); number.scope = 'row'; number.textContent = String(row + 1); tr.appendChild(number);
      for (let col = 0; col < sheet.columns.length; col++) {
        const td = document.createElement('td'), input = document.createElement('input');
        const raw = rawWorkbookCell(sheet, row, col), formula = String(raw).charAt(0) === '=';
        td.className = 'workbook-cell' + (formula ? ' formula' : '');
        if (formula && workbookDisplay(sheet, row, col) === '#ERR') td.classList.add('error');
        if (row === selected.row && col === selected.col) td.classList.add('selected');
        input.type = 'text'; input.dataset.workbookCell = 'true'; input.dataset.row = row; input.dataset.col = col; input.dataset.raw = raw;
        input.value = formula ? workbookDisplay(sheet, row, col) : raw;
        input.setAttribute('aria-label', workbookColumnName(col) + (row + 1));
        td.title = formula ? raw + '  →  ' + workbookDisplay(sheet, row, col) : '';
        input.addEventListener('focus', () => {
          state.workbook.selected = { row, col };
          input.value = rawWorkbookCell(sheet, row, col);
          input.dataset.raw = input.value;
          Array.from(sheetWrapEl.querySelectorAll('td.workbook-cell.selected')).forEach((cell) => cell.classList.remove('selected'));
          td.classList.add('selected');
          updateWorkbookFormulaBar(sheet, row, col, input.value);
        });
        input.addEventListener('input', () => updateWorkbookFormulaBar(sheet, row, col, input.value));
        input.addEventListener('blur', () => commitWorkbookCell(sheet, row, col, input.value));
        input.addEventListener('keydown', (event) => {
          if (event.key === 'Escape') { event.preventDefault(); input.value = input.dataset.raw; input.blur(); return; }
          if (event.key !== 'Enter' && event.key !== 'Tab') return;
          event.preventDefault();
          const next = event.key === 'Enter'
            ? { row: Math.min(row + 1, Math.max(0, sheet.rows.length - 1)), col }
            : { row, col: Math.min(col + 1, Math.max(0, sheet.columns.length - 1)) };
          commitWorkbookCell(sheet, row, col, input.value, next);
        });
        td.appendChild(input); tr.appendChild(td);
      }
      body.appendChild(tr);
    }
    table.appendChild(body);
    return table;
  }

  function openSheet(id) {
    if (!sheetById[id] && !workbookSheetById(id)) return;
    mutate((s) => {
      s.sheetId = id;
      if (s.workbook && s.workbook.sheets.some((sheet) => sheet.id === id)) s.workbook.activeSheetId = id;
    }, 'sheetId');
    renderSheet();
  }

  function renderSheet() {
    sheetTabsEl.innerHTML = ''; sheetCrumbsEl.innerHTML = ''; sheetWrapEl.innerHTML = '';
    const workbookSheets = state.workbook.sheets;
    if (!workbookSheets.length && !sheets.length) { sheetToolbarEl.innerHTML = ''; sheetEmptyEl.hidden = false; return; }
    sheetEmptyEl.hidden = true;
    const curId = currentSheetId();
    const workbook = workbookSheetById(curId);
    if (workbook) {
      renderWorkbookToolbar(workbook);
      const b = document.createElement('button');
      b.className = 'sheet-tab active'; b.setAttribute('role', 'tab'); b.setAttribute('aria-selected', 'true');
      b.textContent = workbook.title;
      const n = document.createElement('span'); n.className = 'sheet-count'; n.textContent = workbook.rows.length + ' × ' + workbook.columns.length; b.appendChild(n);
      b.addEventListener('click', () => openSheet(workbook.id)); sheetTabsEl.appendChild(b);
      workbookSheets.forEach((other) => {
        if (other.id === workbook.id) return;
        const tab = document.createElement('button'); tab.className = 'sheet-tab'; tab.setAttribute('role', 'tab'); tab.setAttribute('aria-selected', 'false');
        tab.textContent = other.title;
        const count = document.createElement('span'); count.className = 'sheet-count'; count.textContent = other.rows.length + ' × ' + other.columns.length; tab.appendChild(count);
        tab.addEventListener('click', () => openSheet(other.id)); sheetTabsEl.appendChild(tab);
      });
      const crumb = document.createElement('button'); crumb.textContent = workbook.title; crumb.setAttribute('aria-label', 'Current sheet: ' + workbook.title); sheetCrumbsEl.appendChild(crumb);
      sheetWrapEl.appendChild(buildWorkbookTable(workbook));
      return;
    }
    const cur = sheetById[curId];
    if (!cur) { renderReadOnlyToolbar(null); sheetEmptyEl.hidden = false; return; }
    renderReadOnlyToolbar(cur);
    workbookSheets.forEach((workbookSheet) => {
      const tab = document.createElement('button'); tab.className = 'sheet-tab'; tab.setAttribute('role', 'tab'); tab.setAttribute('aria-selected', 'false');
      tab.textContent = workbookSheet.title;
      const count = document.createElement('span'); count.className = 'sheet-count'; count.textContent = workbookSheet.rows.length + ' × ' + workbookSheet.columns.length; tab.appendChild(count);
      tab.addEventListener('click', () => openSheet(workbookSheet.id)); sheetTabsEl.appendChild(tab);
    });
    // tabs = root sheets (one per source: blackboard cursor, confix doc, …)
    let rootOf = cur; while (rootOf && rootOf.parent && sheetById[rootOf.parent]) rootOf = sheetById[rootOf.parent];
    rootSheets.forEach((sh) => {
      const b = document.createElement('button');
      const isActive = rootOf && rootOf.id === sh.id;
      b.className = 'sheet-tab' + (isActive ? ' active' : '');
      b.setAttribute('role', 'tab');
      b.setAttribute('aria-selected', isActive ? 'true' : 'false');
      b.textContent = sh.title;
      const n = document.createElement('span'); n.className = 'sheet-count'; n.textContent = sh.rows.length + ' × ' + sh.columns.length; b.appendChild(n);
      b.addEventListener('click', () => { openSheet(sh.id); Array.from(sheetTabsEl.children).forEach(c => c.setAttribute('aria-selected', 'false')); b.setAttribute('aria-selected', 'true'); });
      sheetTabsEl.appendChild(b);
    });
    // breadcrumb = parent chain (zoom path)
    const chain = []; let p = cur; while (p) { chain.unshift(p); p = p.parent ? sheetById[p.parent] : null; }
    chain.forEach((sh, i) => {
      if (i) sheetCrumbsEl.appendChild(document.createTextNode(' / '));
      const title = sh.id.split('/').pop() || sh.title;
      const b = document.createElement('button'); b.textContent = title;
      b.setAttribute('aria-label', 'Navigate to parent sheet: ' + title);
      b.addEventListener('click', () => openSheet(sh.id)); sheetCrumbsEl.appendChild(b);
    });
    sheetWrapEl.appendChild(buildSheetTable(cur, 0));
  }

  // arrow-key cell navigation within the focused table
  sheetWrapEl.addEventListener('keydown', (e) => {
    if (e.target.matches && e.target.matches('input[data-workbook-cell="true"]')) return;
    const td = e.target.closest && e.target.closest('td');
    if (!td) return;
    const tr = td.parentElement; const table = tr.closest('table');
    const r = tr.rowIndex - 1, c = td.cellIndex;
    const rows = table.tBodies[0].rows;
    let target = null;
    if (e.key === 'ArrowDown' && rows[r + 1]) target = rows[r + 1].cells[c];
    if (e.key === 'ArrowUp' && rows[r - 1]) target = rows[r - 1].cells[c];
    if (e.key === 'ArrowRight') target = tr.cells[c + 1];
    if (e.key === 'ArrowLeft') target = tr.cells[c - 1];
    if (e.key === 'Enter') { const btn = td.querySelector('.sheet-ref'); if (btn) { btn.click(); e.preventDefault(); return; } }
    if (target) { e.preventDefault(); target.focus(); }
  });

  // gallery cards for confix.* widgets open the sheet view
  const galleryBody = document.querySelector('.sidebar-gallery-body');
  if (galleryBody) galleryBody.addEventListener('click', (e) => {
    const card = e.target.closest && e.target.closest('.gallery-card');
    if (!card) return;
    const id = (card.querySelector('.id') || {}).textContent || '';
    if (id.trim().startsWith('confix.')) { setView('sheet'); openSheet('confix'); }
    else if (id.trim() === 'forge.graph') { setView('graph'); }
    else if (id.trim().startsWith('host.')) { setView('host'); }
  });

  // ── View switching ──────────────────────────────────────────────────
  const viewDocBtn = document.getElementById('btn-view-doc');
  const viewBoardBtn = document.getElementById('btn-view-board');
  const viewGraphBtn = document.getElementById('btn-view-graph');
  const viewSheetBtn = document.getElementById('btn-view-sheet');

  // ── Drop zone interaction ───────────────────────────────────────────
  const dropZoneEl = document.getElementById('drop-zone');
  const fileInputEl = document.getElementById('file-input');

  if (dropZoneEl && fileInputEl) {
    dropZoneEl.addEventListener('click', () => {
      fileInputEl.click();
    });

    dropZoneEl.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        fileInputEl.click();
      }
    });
  }


  // ── Shape strip (ingest motif) ──────────────────────────────────────
  const shapeScrollEl = document.getElementById('shape-scroll');
  const viewShapeBtn = document.getElementById('btn-view-shape');
  // The alphabet, gate and box walker are commonMain (ForgeKanbanIngest.planRules, IsoBmff); ForgeNodeMain publishes them on
  // window.forgeKotlin when the js bundle hydrates. This file only draws.
  const SHAPE_NAMES = { _: 'blank', 6: 'work packages', 7: 'section 7', S: 'section', H: 'heading', W: 'package', D: 'depends', B: 'bullet', T: 'table', C: 'fence', J: 'code', P: 'prose' };
  const SHAPE_MEDIA = /\.(mp4|m4a|m4v|mov|3gp|heic|heif|avif|mj2)$/i, SHAPE_TEXT = /\.(md|markdown|txt|html?)$/i;
  const shapeDocs = [];
  let shapeDepth = Infinity, shapePick = null, shapeFib = null, shapePending = 0;
  const kotlin = () => window.forgeKotlin && window.forgeKotlin.runs ? window.forgeKotlin : null;
  const fibSet = () => (shapeFib ||= new Set(kotlin().fib(1024)));
  const shapeEl = (tag, cls, text) => { const e = document.createElement(tag); e.className = cls; if (text != null) e.textContent = text; return e; };

  // Lines: runs come back as "sym:start:end" (half-open, absolute) — the Kotlin Shape<Char> facet.
  function shapeOf(name, text) {
    const k = kotlin(), lines = text.split(/\r?\n/);
    const runs = k.runs(text).map((s) => { const [c, a, b] = s.split(':'); return { c, start: +a, end: +b - 1, n: +b - +a }; });
    const key = runs.map((r) => r.c), kind = k.isPlan(text) ? 'plan' : key.includes('T') ? 'table' : key.some((c) => c === 'C' || c === 'J') ? 'code' : key.some((c) => c === 'H' || c === 'S') ? 'note' : 'prose';
    return { name, lines, runs, key, sep: '', kind, unit: 'lines' };
  }
  // Boxes: "path:bytes" per ISO BMFF box in walk order — the Kotlin boxes() emit; width ∝ bytes, symbol = box type.
  function shapeOfBoxes(name, buf) {
    const rows = kotlin().boxes(new Int8Array(buf));
    const runs = rows.map((s, i) => { const j = s.lastIndexOf(':'), path = s.slice(0, j); return { c: path.split('/').pop(), path, start: i, end: i, n: +s.slice(j + 1) }; });
    return { name, lines: rows.map((s) => s.replace(/:(\d+)$/, '  $1 B')), runs, key: runs.map((r) => r.c), sep: ' ', kind: 'box media', unit: 'bytes' };
  }

  function shapeStrip(doc, di) {
    const strip = shapeEl('div', 'shape-strip');
    strip.setAttribute('role', 'group'); strip.setAttribute('aria-label', 'Shape of ' + doc.name);
    doc.runs.forEach((r, ri) => {
      const cell = shapeEl('button', 'shape-cell');
      cell.style.setProperty('--c', 'var(--shape-' + r.c + ', var(--shape-P))');
      cell.style.flex = r.n + ' 0 2px';
      cell.classList.toggle('fib', fibSet().has(ri));
      cell.classList.toggle('picked', !!shapePick && shapePick.doc === di && shapePick.run === ri);
      cell.title = (r.path || SHAPE_NAMES[r.c] || r.c) + ' · ' + r.n + ' ' + doc.unit + (r.path ? '' : ' ' + (r.start + 1) + '–' + (r.end + 1)) + (fibSet().has(ri) ? ' · depth ' + ri : '');
      cell.setAttribute('aria-label', cell.title);
      cell.addEventListener('click', () => { shapePick = { doc: di, run: ri }; renderShape(); });
      strip.appendChild(cell);
    });
    if (!doc.runs.length) { const c = shapeEl('button', 'shape-cell'); c.style.flex = '1 0 4px'; c.title = doc.via === 'store' ? 'store row — click for bytes' : 'empty'; c.addEventListener('click', () => { shapePick = { doc: di, run: 0 }; renderShape(); }); strip.appendChild(c); }
    return strip;
  }

  // One group = one Couch group_level=depth row; ids[0] is the representative doc.
  function shapeDocEl(prefix, ids) {
    const di = ids[0], doc = shapeDocs[di], group = ids.length > 1;
    const box = shapeEl('div', 'shape-doc');
    const head = shapeEl('div', 'shape-doc-head');
    const isStore = doc.via === 'store';
    head.append(shapeEl('span', 'shape-name', group ? '×' + ids.length + '  ' + prefix : doc.name),
      shapeEl('span', 'shape-chip ' + doc.kind.split(' ')[0], isStore ? 'store row · ' + doc.key.join('') : doc.kind === 'plan' ? 'kanban plan · persist' : doc.kind + (doc.kind === 'box media' ? '' : ' · rejected')),
      shapeEl('span', 'shape-meta', isStore
        ? (doc.bytes || 0).toLocaleString() + ' bytes · code ' + doc.key.join('')
        : doc.lines.length + ' ' + (doc.unit === 'bytes' ? 'boxes' : 'lines') + ' · ' + doc.runs.length + ' runs' + (doc.via ? ' · ' + doc.via : '')));
    box.append(head, shapeStrip(doc, di), shapeEl('code', 'shape-key', doc.key.join(doc.sep)));
    if (group) box.appendChild(shapeEl('div', 'shape-members', ids.map((i) => shapeDocs[i].name).join(', ')));
    if (shapePick && shapePick.doc === di) {
      if (isStore) {
        const pre = shapeEl('pre', 'block-code shape-src');
        pre.textContent = 'loading bytes…';
        fetch('/' + DBNAME + '/' + doc.name.split('/').map(encodeURIComponent).join('/') + '/content')
          .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
          .then((t) => { pre.textContent = t.slice(0, 20000) + (t.length > 20000 ? '\n…' : ''); })
          .catch(() => { pre.textContent = 'content unavailable'; });
        box.appendChild(pre);
      } else {
        const r = doc.runs[shapePick.run];
        if (r) box.appendChild(shapeEl('pre', 'block-code shape-src', doc.lines.slice(r.start, r.end + 1).map((l, k) => String(r.start + k + 1).padStart(4) + '  ' + l).join('\n')));
      }
    }
    return box;
  }

  // ── R1: the group_level=<k> ladder, repointed from dropped files to store rows ──
  // The ladder was built over browser-dropped files (planRules run symbols). The Step C
  // residual: the same ladder groups the DAEMON'S STORE ROWS by the hex nibbles of each doc's
  // AngularCodec code (16-bit = 4 nibbles; depth k = the first k nibbles — the zoom ring).
  // fibTicks (kotlin().fib) are unchanged: they pick the ladder rungs.
  const DBNAME = 'trikeshed';
  let shapeStoreLoaded = false;
  const storeKey = (code) => (((code >>> 0) & 0xFFFF)).toString(16).padStart(4, '0').split('');
  async function loadStoreRows() {
    shapePending++; setView('shape');
    try {
      const m = await (await fetch('/api/graal/map')).json();
      (m.rows || []).forEach((r) => {
        const [id, bytes, seq, gen, code] = r;
        if (shapeDocs.some((d) => d.name === id && d.via === 'store')) return;
        shapeDocs.push({ name: id, lines: [], runs: [], key: storeKey(code), sep: '', kind: 'store', unit: 'rows', via: 'store', bytes });
      });
      shapeStoreLoaded = true; shapePick = null; setView('shape');
    } catch (e) {
      shapeScrollEl && shapeScrollEl.appendChild(shapeEl('span', 'shape-count', 'store rows unavailable: ' + e + ' — drop files instead'));
    } finally { shapePending = Math.max(0, shapePending - 1); }
  }

  function renderShape() {
    shapeScrollEl.textContent = '';
    if (!kotlin()) { shapeScrollEl.appendChild(shapeEl('span', 'shape-count', 'Kotlin bundle not loaded — bake with -PforgePagesStages=jvm,js')); return; }
    const hud = shapeEl('div', 'shape-hud');
    hud.setAttribute('role', 'group'); hud.setAttribute('aria-label', 'Shape depth (group level)');
    const maxKey = Math.max(0, ...shapeDocs.map((d) => d.key.length));
    [...fibSet()].filter((f) => f > 0 && f <= maxKey).concat(Infinity).forEach((f) => {
      const label = f === Infinity ? '∞' : String(f);
      const b = shapeEl('button', 'topbar-btn' + (shapeDepth === f ? ' active' : ''), label);
      b.title = 'group_level=' + label; b.setAttribute('aria-label', 'Depth ' + label);
      b.addEventListener('click', () => { shapeDepth = f; renderShape(); });
      hud.appendChild(b);
    });
    if (!shapeStoreLoaded) {
      const s = shapeEl('button', 'topbar-btn', '⬇ store rows');
      s.title = 'repoint the ladder: load the daemon store rows (grouped by code nibbles)';
      s.setAttribute('aria-label', 'Load store rows');
      s.addEventListener('click', loadStoreRows);
      hud.appendChild(s);
    }
    hud.appendChild(shapeEl('span', 'shape-count', shapeDocs.length + ' files' + (shapePending ? ' · ' + shapePending + ' processing' : '') + ' · drop files anywhere'));
    shapeScrollEl.appendChild(hud);
    const groups = new Map();
    shapeDocs.forEach((d, i) => { const k = d.key.slice(0, shapeDepth).join(d.sep); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(i); });
    [...groups].sort((a, b) => b[1].length - a[1].length).forEach(([prefix, ids]) => shapeScrollEl.appendChild(shapeDocEl(prefix, ids)));
    const legend = shapeEl('div', 'shape-legend');
    Object.keys(SHAPE_NAMES).forEach((c) => { const s = shapeEl('span', '', c + ' ' + SHAPE_NAMES[c]); s.prepend(shapeEl('i', '')); s.firstChild.style.setProperty('--c', 'var(--shape-' + c + ')'); legend.appendChild(s); });
    shapeScrollEl.appendChild(legend);
  }

  // Everything that is not text or box media goes to the local ingester (ForgeIngestServer: Tika, ffmpeg+tesseract for scans);
  // with no server (Pages) the browser does it: office parts via commonMain, images and thin PDF pages via the same pre-pass → tesseract.js.
  const SHAPE_OFFICE = /\.(docx|pptx|xlsx)$/i, SHAPE_WORKBOOK = /\.(csv|tsv|xlsx|xls|numbers|ods)$/i, SHAPE_IMAGE = /\.(png|jpe?g|gif|bmp|webp|tiff?)$/i, SHAPE_PDF = /\.pdf$/i;
  const CDN = { tesseract: 'https://cdn.jsdelivr.net/npm/tesseract.js@5/dist/tesseract.min.js',
    pdfjs: 'https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/build/pdf.min.mjs', pdfjsWorker: 'https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/build/pdf.worker.min.mjs' };
  const loadScript = (src) => new Promise((ok, no) => { const s = document.createElement('script'); s.src = src; s.onload = ok; s.onerror = no; document.head.appendChild(s); });
  async function ocrCanvas(canvas) {
    const ctx = canvas.getContext('2d'), img = ctx.getImageData(0, 0, canvas.width, canvas.height);
    kotlin().prepass(new Int8Array(img.data.buffer)); ctx.putImageData(img, 0, 0);
    if (!window.Tesseract) await loadScript(CDN.tesseract);
    return (await Tesseract.recognize(canvas, 'eng')).data.text;
  }
  async function imageText(f) {
    const bmp = await createImageBitmap(f), c = document.createElement('canvas'); c.width = bmp.width; c.height = bmp.height;
    c.getContext('2d').drawImage(bmp, 0, 0); return ocrCanvas(c);
  }
  async function pdfText(f) {
    const pdfjs = await import(CDN.pdfjs); pdfjs.GlobalWorkerOptions.workerSrc = CDN.pdfjsWorker;
    const doc = await pdfjs.getDocument({ data: await f.arrayBuffer() }).promise, pages = [];
    for (let i = 1; i <= doc.numPages; i++) {
      const page = await doc.getPage(i), text = (await page.getTextContent()).items.map((t) => t.str).join(' ');
      if (text.replace(/\s/g, '').length >= 10) { pages.push(text); continue; }   // OCR_STRATEGY auto: thin text layer ⇒ rasterise
      const vp = page.getViewport({ scale: 2 }), c = document.createElement('canvas'); c.width = vp.width; c.height = vp.height;
      await page.render({ canvasContext: c.getContext('2d'), viewport: vp }).promise; pages.push(await ocrCanvas(c));
    }
    return pages.join('\n\n');
  }
  const browserText = (f) => SHAPE_OFFICE.test(f.name) ? f.arrayBuffer().then((b) => kotlin().office(new Int8Array(b)))
    : SHAPE_IMAGE.test(f.name) ? imageText(f) : SHAPE_PDF.test(f.name) ? pdfText(f) : Promise.reject('unsupported');
  function importExtractedFile(file, text) {
    const workbook = SHAPE_WORKBOOK.test(file.name);
    if (workbook) { importWorkbook(file.name, text); return 'sheet'; }
    const extension = /\.([A-Za-z0-9]+)$/.exec(file.name);
    importDocument(file.name, text, extension ? extension[1].toLowerCase() : 'document');
    return 'doc';
  }
  function tikaIngest(f) {
    shapePending++; setView('shape');
    return fetch('/ingest', { method: 'POST', body: f, headers: { 'X-Forge-Name': f.name } })
      .then((r) => r.ok ? r.json() : Promise.reject(r.status))
      .then((j) => {
        const text = j.markdown || '';
        const importedView = importExtractedFile(f, text);
        return Object.assign(shapeOf(f.name, text), { via: 'tika' + (j.persisted ? ' · persisted' : ''), importedView });
      })
      .catch(() => browserText(f).then((t) => {
        const text = String(t || ''), importedView = importExtractedFile(f, text);
        return Object.assign(shapeOf(f.name, '# ' + f.name + '\n\n' + text + '\n'), { via: 'browser', importedView });
      }))
      .catch(() => ({ name: f.name, lines: [], runs: [], key: [], sep: '', kind: 'unsupported here', unit: 'lines', via: './gradlew serveForgePages' }))
      .finally(() => shapePending--);
  }
  function shapeIngest(files) {
    if (!kotlin()) { setView('shape'); return; }
    let importedView = null;
    Promise.all([...files].map((f) => SHAPE_MEDIA.test(f.name)
      ? f.arrayBuffer().then((b) => shapeOfBoxes(f.name, b))
      : SHAPE_TEXT.test(f.name)
        ? f.text().then((t) => { importedView = importExtractedFile(f, t); return Object.assign(shapeOf(f.name, t), { via: 'browser', importedView }); })
        : tikaIngest(f)))
      .then((docs) => {
        docs.forEach((doc) => { shapeDocs.push(doc); if (doc.importedView) importedView = doc.importedView; });
        shapePick = null; setView(importedView || 'shape');
      });
  }
  document.addEventListener('dragover', (e) => { e.preventDefault(); if (dropZoneEl) dropZoneEl.classList.add('drag-active'); });
  document.addEventListener('dragleave', (e) => { if (!e.relatedTarget && dropZoneEl) dropZoneEl.classList.remove('drag-active'); });
  document.addEventListener('drop', (e) => { e.preventDefault(); if (dropZoneEl) dropZoneEl.classList.remove('drag-active'); if (e.dataTransfer.files.length) shapeIngest(e.dataTransfer.files); });
  fileInputEl.addEventListener('change', () => { shapeIngest(fileInputEl.files); fileInputEl.value = ''; });

  // ── Render: host (the sub-VM substrate — borg.trikeshed.vm) ─────────
  // seed.hosts = { host:{platform,subVm,languages,vms,nio,discontinued}, providers:[report], vms:<sheet> }
  const hostScrollEl = document.getElementById('host-scroll');
  const viewHostBtn = document.getElementById('btn-view-host');
  const hostTilesEl = document.getElementById('host-tiles');
  const hostVmsEl = document.getElementById('host-vms');
  const hostVmCountEl = document.getElementById('host-vm-count');
  const hostForm = document.getElementById('host-spawn');
  const hostFacetSel = document.getElementById('host-vm-facet');
  const hostLiveNote = document.getElementById('host-live-note');
  const hostLog = document.getElementById('host-log');
  const hosts = (seed.hosts && seed.hosts.host) ? seed.hosts : { host: { platform: 'none', subVm: false, languages: [], vms: 0, nio: {}, discontinued: [] }, providers: [], vms: null };
  let hostLiveProbe = null;   // null = unknown, true = /api/vm answered, false = static dump
  let hostEvents = null;

  function tile(k, v, sub, cls) {
    const d = document.createElement('div'); d.className = 'host-tile' + (cls ? ' ' + cls : '');
    d.innerHTML = '<div class="ht-k"></div><div class="ht-v"></div><div class="ht-sub"></div>';
    d.children[0].textContent = k; d.children[1].textContent = v; d.children[2].textContent = sub || '';
    return d;
  }

  function hostLogLine(text) {
    hostLog.textContent += text + '\n';
    hostLog.scrollTop = hostLog.scrollHeight;
  }

  function renderHostTiles(live) {
    hostTilesEl.innerHTML = '';
    const h = hosts.host, nio = h.nio || (seed.dashboards && seed.dashboards.nio) || {};
    hostTilesEl.appendChild(tile('platform', h.platform || 'none', live === true ? 'live server' : (live === false ? 'static dump (baked on ' + (h.platform || '?') + ')' : 'probing…')));
    hostTilesEl.appendChild(tile('sub-vm host', h.subVm ? 'bound' : 'dead', h.subVm ? (h.languages || []).join(', ') + ' · ' + (h.vms || 0) + ' vms' : 'vm.spawn is discontinued on this target', h.subVm ? 'live' : 'dead'));
    hostTilesEl.appendChild(tile('nio backend', nio.backendName || 'unknown', (nio.ioUringAvailable ? 'io_uring · ' : '') + (nio.capabilities || []).join(' ')));
    (hosts.providers || []).forEach((p) => {
      hostTilesEl.appendChild(tile('tier · ' + p.providerId, p.available ? p.sandboxKind : 'unavailable',
        (p.languages || []).join(',') + (p.wallBudgetSupported ? ' · wall budget' : ' · no wall budget') + (p.callSupported ? ' · call' : ' · no call') + (p.note ? ' — ' + p.note : ''),
        p.available ? 'live' : 'dead'));
    });
    const dead = h.discontinued || [];
    const dt = tile('dead features', String(dead.length), '', dead.length ? 'dead' : 'live');
    const ul = document.createElement('div'); ul.className = 'host-dead-list'; ul.textContent = dead.join('\n'); dt.appendChild(ul);
    hostTilesEl.appendChild(dt);
  }

  function renderHostVms(sheet) {
    hostVmsEl.innerHTML = '';
    const sh = sheet || hosts.vms;
    if (sh && Array.isArray(sh.columns) && sh.columns.length) {
      hostVmsEl.appendChild(buildSheetTable(sh, 0));
      hostVmCountEl.textContent = sh.rows.length + ' rows';
    } else {
      hostVmsEl.textContent = 'no VM rows (host dead)';
      hostVmCountEl.textContent = '';
    }
  }

  function hostApi(path, body) {
    return fetch(path, body === undefined ? { method: 'GET' } : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
      .then((r) => r.ok ? r.json() : r.text().then((t) => { throw new Error(r.status + ' ' + t); }));
  }

  function probeHostLive() {
    if (hostLiveProbe !== null) return Promise.resolve(hostLiveProbe);
    return hostApi('./api/vm').then((sheet) => { hostLiveProbe = true; renderHostVms(sheet); return true; })
      .catch(() => { hostLiveProbe = false; return false; });
  }

  function renderHost() {
    hostFacetSel.innerHTML = '';
    (hosts.host.languages && hosts.host.languages.length ? hosts.host.languages : ['js']).forEach((l) => {
      const o = document.createElement('option'); o.value = l; o.textContent = l; hostFacetSel.appendChild(o);
    });
    renderHostTiles(hostLiveProbe);
    renderHostVms();
    probeHostLive().then((live) => {
      renderHostTiles(live);
      hostForm.setAttribute('aria-disabled', live ? 'false' : 'true');
      hostLiveNote.textContent = live ? 'served live — spawn/eval round-trip to /api/vm' : 'static Pages build — no live host; spawn/eval need the JVM server (runKanbanHttpServerJvm)';
      if (live && !hostEvents && typeof EventSource !== 'undefined') {
        hostEvents = new EventSource('./api/vm/events');
        hostEvents.onmessage = (e) => hostLogLine(e.data);
        hostEvents.onerror = () => hostLogLine('[events] stream closed');
      }
    });
  }

  hostForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const id = document.getElementById('host-vm-id').value.trim() || ('vm-' + uid().slice(0, 6));
    const facet = hostFacetSel.value;
    const trust = document.getElementById('host-vm-trust').value;
    const source = document.getElementById('host-vm-src').value;
    hostLogLine('> spawn ' + id + ' (' + facet + ', ' + trust + ')');
    hostApi('./api/vm/spawn', { id, facet, trust })
      .then(() => source.trim() ? hostApi('./api/vm/' + encodeURIComponent(id) + '/eval', { source }) : { value: null })
      .then((r) => { hostLogLine('< ' + JSON.stringify(r)); return hostApi('./api/vm'); })
      .then((sheet) => renderHostVms(sheet))
      .catch((err) => hostLogLine('! ' + err.message));
  });

  const VIEWS = {
    doc: { el: docScrollEl, btns: [viewDocBtn, document.getElementById('btn-home')] },
    board: { el: boardScrollEl, btns: [viewBoardBtn, document.getElementById('btn-board')] },
    graph: { el: graphScrollEl, btns: [viewGraphBtn, document.getElementById('btn-graph')] },
    sheet: { el: sheetScrollEl, btns: [viewSheetBtn, document.getElementById('btn-sheet')] },
    shape: { el: shapeScrollEl, btns: [viewShapeBtn] },
    host: { el: hostScrollEl, btns: [viewHostBtn, document.getElementById('btn-host')] }
  };
  function setView(view) {
    mutate((s) => { s.view = view; }, 'view');
    for (const [k, config] of Object.entries(VIEWS)) {
      config.el.hidden = k !== view;
      for (const btn of config.btns) {
        if (!btn) continue;
        btn.classList.toggle('active', k === view);
        if (k === view) {
          btn.setAttribute('aria-current', 'page');
        } else {
          btn.removeAttribute('aria-current');
        }
      }
    }
    if (view === 'board') { renderBoard(); hydrateBoard(); }
    if (view === 'graph') { setGraphMode(graphMode); }
    if (view === 'sheet') renderSheet();
    if (view === 'shape') renderShape();
    if (view === 'host') renderHost();
  }

  for (const [k, config] of Object.entries(VIEWS)) {
    for (const btn of config.btns) {
      if (btn) btn.addEventListener('click', () => setView(k));
    }
  }
  document.getElementById('btn-new-page').addEventListener('click', () => {
    newPage();
    renderAll();
    titleEl.focus();
  });

  // ── Seed note ───────────────────────────────────────────────────────
  (function renderSeedNote() {
    const parts = [];
    if (seed.source && seed.source.title) parts.push(seed.source.title);
    if (seedBoard && Array.isArray(seedBoard.cards) && seedBoard.cards.length) {
      parts.push(seedBoard.cards.length + ' cards');
    }
    if (Array.isArray(seed.lcncEntities) && seed.lcncEntities.length) {
      parts.push(seed.lcncEntities.length + ' entities');
    }
    const causal = Array.isArray(seed.causalGraph) ? seed.causalGraph : (Array.isArray(seed.causalNodes) ? seed.causalNodes : []);
    if (causal.length) parts.push(causal.length + ' causal nodes');
    if (Array.isArray(seed.correlations) && seed.correlations.length) parts.push(seed.correlations.length + ' correlations');
    if (seed.conceptGraph && Array.isArray(seed.conceptGraph.nodes) && seed.conceptGraph.nodes.length) parts.push(seed.conceptGraph.nodes.length + ' concepts');
    if (seed.docsGraph && Array.isArray(seed.docsGraph.nodes) && seed.docsGraph.nodes.length) parts.push(seed.docsGraph.nodes.length + ' documents');
    if (sheets.length) parts.push(sheets.length + ' sheets');
    if (seed.hosts && seed.hosts.host) parts.push(seed.hosts.host.subVm ? 'host: ' + seed.hosts.host.platform : 'host: dead');
    seedNoteEl.textContent = parts.length ? 'Seed: ' + parts.join(' · ') : 'Local-first workspace';
  })();

  // ── Global interactions ─────────────────────────────────────────────
  document.addEventListener('keydown', (e) => {
    if ((e.key === 'Enter' || e.key === ' ') && e.target.getAttribute('role') === 'button') {
      e.preventDefault();
      e.target.click();
    }
  });

  // ── Render all ──────────────────────────────────────────────────────
  function renderAll() {
    renderSidebar();
    renderTitle();
    renderBlocks();
    setView(state.view || 'doc');
  }

  renderAll();

  // CTA links live IN the sidebar footer with the seed/sync notes — same size,
  // same muted palette, in normal flow. Never floated over the app's own chrome.
  (function mountCta() {
    const anchor = document.getElementById('sync-note');
    const host = document.createElement('div');
    host.style.cssText = 'display:flex;gap:12px;margin-top:6px;font-size:11px';
    function link(text, title, onClick) {
      const a = document.createElement('button');
      a.textContent = text;
      a.title = title;
      a.setAttribute('aria-label', title);
      a.style.cssText = 'color:var(--text-faint);cursor:pointer;text-decoration:none;border-bottom:1px dotted var(--text-faint);background:none;border:none;padding:0;font:inherit;';
      a.addEventListener('click', onClick);
      host.appendChild(a);
      return a;
    }
    link('\u26A1 install', 'run the quickstart in anger', () => {
      const d = document.createElement('div');
      d.dataset.qs = '1';
      d.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:99;display:flex;align-items:center;justify-content:center';
      d.innerHTML = '<div style="background:var(--bg,#fff);border:1px solid var(--border,#ccc);border-radius:8px;padding:18px 22px;max-width:580px;color:var(--text,#222);font:13px monospace;box-shadow:0 8px 30px rgba(0,0,0,.25)">' +
        '<b>Run TrikeShed in anger \u2014 five minutes, one port</b>' +
        '<pre id="qsCmds" style="background:rgba(127,127,127,.12);padding:10px;border-radius:4px;margin:10px 0;user-select:text;white-space:pre-wrap">git clone git@github.com:jnorthrup/TrikeShed.git && cd TrikeShed\n./gradlew hotswapFeed\nbin/oroboros-daemon --watch</pre>' +
        '<button aria-label="Copy installation commands" onclick="navigator.clipboard.writeText(document.getElementById(\'qsCmds\').textContent)" style="font:inherit;padding:4px 12px;cursor:pointer">copy commands</button>' +
        '<a href="https://github.com/jnorthrup/TrikeShed#run-it-in-anger--please" target="_blank" style="margin-left:10px">README \u2197</a>' +
        '<button aria-label="Close installation instructions" onclick="document.querySelector(\'div[data-qs]\').remove()" style="background:none;border:none;padding:0;font:inherit;color:inherit;text-decoration:underline;margin-left:14px;cursor:pointer">close</button></div>';
      d.addEventListener('click', (e) => { if (e.target === d) d.remove(); });
      document.body.appendChild(d);
    });
    link('\u2691 feedback', 'open a GitHub issue prefilled with where you are right now', () => {
      const coords = {
        view: state.view,
        page: state.activePageId,
        boardSequence: boardSequence,
        cards: state.board && state.board.cards ? state.board.cards.length : 0,
        columns: state.board && state.board.columns ? state.board.columns.map((c) => c.id) : [],
      };
      const body = '**Surface:** board\n**URL:** ' + location.href + '\n**Coordinates:**\n```json\n' +
        JSON.stringify(coords, null, 1) + '\n```\n\n**What I did:**\n\n**What happened:**\n\n**What I expected:**\n';
      window.open('https://github.com/jnorthrup/TrikeShed/issues/new?labels=quickstart-feedback' +
        '&title=' + encodeURIComponent('[board] ') + '&body=' + encodeURIComponent(body), '_blank');
    });
    if (anchor && anchor.parentElement) anchor.parentElement.appendChild(host);
    else document.body.appendChild(host);
  })();

  // Live board: hydrate from the WAL-backed store at load, then poll on a
  // watermark (sequence unchanged = no re-render) while the board is visible.
  hydrateBoard();
  setInterval(() => { if (state.view === 'board' && !document.hidden) hydrateBoard(); }, 15000);
})();
