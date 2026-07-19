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
  try { seed = JSON.parse(seedEl ? seedEl.textContent : '{}'); } catch (e) { seed = {}; }

  const LS_KEY = 'forge.workspace.v2';

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

  function defaultState() {
    const homeId = uid();
    return {
      pages: [
        { id: homeId, icon: '▤', title: '', blocks: blocksFromSeed() || defaultBlocks(), children: [] },
      ],
      activePageId: homeId,
      view: 'doc',
      board: {
        columns: [
          { id: 'todo', name: 'To do' },
          { id: 'doing', name: 'Doing' },
          { id: 'done', name: 'Done' },
        ],
        cards: seedCards(),
      },
    };
  }

  function seedCards() {
    const entities = Array.isArray(seed.lcncEntities) ? seed.lcncEntities : [];
    return entities.slice(0, 12).map((e, i) => ({
      id: uid(),
      title: (e && (e.title || e.name || e.path)) || ('Card ' + (i + 1)),
      column: i % 3 === 0 ? 'doing' : 'todo',
      meta: (e && (e.lcncKind || e.kind)) || '',
    }));
  }

  function loadState() {
    try {
      const raw = localStorage.getItem(LS_KEY);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && Array.isArray(parsed.pages) && parsed.pages.length) return parsed;
      }
    } catch (e) { /* fall through to default */ }
    return defaultState();
  }

  let state = loadState();

  function saveState() {
    try { localStorage.setItem(LS_KEY, JSON.stringify(state)); } catch (e) { /* quota */ }
  }

  // ── Command Queue ───────────────────────────────────────────────────
  window.__forgeCommandQueue = window.__forgeCommandQueue || [];

  function mutate(updater) {
    updater(state);
    saveState();

    const jobId = uid();
    const idempotencyKey = jobId + '-' + Date.now();
    window.__forgeCommandQueue.push({
      type: 'Submit',
      jobId: jobId,
      idempotencyKey: idempotencyKey,
      dependencies: [],
      expectedRevision: null
    });
  }


  // ── Element refs ────────────────────────────────────────────────────
  const pageTreeEl = document.getElementById('page-tree');
  const breadcrumbEl = document.getElementById('breadcrumb');
  const titleEl = document.getElementById('doc-title');
  const iconEl = document.getElementById('doc-icon');
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
    const page = { id: uid(), icon: '▤', title: title || '', blocks: [], children: [] };
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
      item.className = 'page-tree-item' + (page.id === state.activePageId ? ' active' : '');
      const toggle = document.createElement('span');
      toggle.className = 'tree-toggle';
      toggle.textContent = page.children && page.children.length ? '▾' : '▸';
      const icon = document.createElement('span');
      icon.className = 'tree-icon';
      icon.textContent = page.icon || '▤';
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
    addBtn.addEventListener('click', () => {
      insertBlock(idx + 1, { id: uid(), type: 'p', text: '' });
      focusBlock(activePage().blocks[idx + 1].id, true);
    });
    const dragBtn = document.createElement('button');
    dragBtn.className = 'gutter-btn gutter-drag';
    dragBtn.textContent = '⋮⋮';
    dragBtn.title = 'Drag to reorder';
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
      el.appendChild(marker);
    }

    const content = document.createElement('div');
    content.className = 'block-content';
    content.contentEditable = 'true';
    content.spellcheck = false;
    content.dataset.placeholder = def.placeholder;
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
      if (focusTarget) focusBlock(focusTarget, false, true);
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
      item.className = 'slash-item' + (i === activeSlashIndex ? ' active' : '');
      const icon = document.createElement('span');
      icon.className = 'slash-item-icon';
      icon.textContent = d.icon;
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
          // cycle columns on click for a lightweight gesture
          const order = state.board.columns.map((c) => c.id);
          const next = order[(order.indexOf(card.column) + 1) % order.length];
          mutate(() => { card.column = next; });
          renderBoard();
        });
        cardsEl.appendChild(cardEl);
      });
      colEl.appendChild(cardsEl);

      const addBtn = document.createElement('button');
      addBtn.className = 'board-add-card';
      addBtn.textContent = '+ New';
      addBtn.addEventListener('click', () => {
        mutate((s) => { s.board.cards.push({ id: uid(), title: '', column: col.id, meta: '' }); });
        renderBoard();
      });
      colEl.appendChild(addBtn);
      boardCanvasEl.appendChild(colEl);
    });
  }

  // ── View switching ──────────────────────────────────────────────────
  const viewDocBtn = document.getElementById('btn-view-doc');
  const viewBoardBtn = document.getElementById('btn-view-board');

  function setView(view) {
    mutate((s) => { s.view = view; });
    docScrollEl.hidden = view !== 'doc';
    boardScrollEl.hidden = view !== 'board';
    viewDocBtn.classList.toggle('active', view === 'doc');
    viewBoardBtn.classList.toggle('active', view === 'board');
    if (view === 'board') renderBoard();
  }

  viewDocBtn.addEventListener('click', () => setView('doc'));
  viewBoardBtn.addEventListener('click', () => setView('board'));
  document.getElementById('btn-board').addEventListener('click', () => setView('board'));
  document.getElementById('btn-home').addEventListener('click', () => setView('doc'));
  document.getElementById('btn-new-page').addEventListener('click', () => {
    newPage();
    renderAll();
  });

  // ── Seed note ───────────────────────────────────────────────────────
  (function renderSeedNote() {
    const parts = [];
    if (Array.isArray(seed.lcncEntities) && seed.lcncEntities.length) {
      parts.push(seed.lcncEntities.length + ' entities');
    }
    if (Array.isArray(seed.causalNodes) && seed.causalNodes.length) {
      parts.push(seed.causalNodes.length + ' causal nodes');
    }
    if (seed.gallery) parts.push('gallery');
    seedNoteEl.textContent = parts.length ? 'Seed: ' + parts.join(' · ') : 'Local-first workspace';
  })();

  // ── Render all ──────────────────────────────────────────────────────
  function renderAll() {
    renderSidebar();
    renderTitle();
    renderBlocks();
    setView(state.view || 'doc');
  }

  renderAll();
})();
