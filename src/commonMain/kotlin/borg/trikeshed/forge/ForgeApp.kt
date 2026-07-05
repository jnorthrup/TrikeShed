package borg.trikeshed.forge

import borg.trikeshed.kanban.ForgeBoardFSM
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ForgeAppColumn(
    val id: String,
    val name: String,
    val order: Int,
)

@Serializable
data class ForgeAppChecklistItem(
    val id: String,
    val text: String,
    val checked: Boolean = false,
)

@Serializable
data class ForgeAppItem(
    val id: String,
    val title: String,
    val notes: String,
    val status: String,
    val priority: String,
    val checklist: List<ForgeAppChecklistItem> = emptyList(),
)

@Serializable
data class ForgeAppState(
    val title: String,
    val pageNotes: String,
    val columns: List<ForgeAppColumn>,
    val items: List<ForgeAppItem>,
    val selectedItemId: String? = null,
)

private val forgeAppJson = Json { prettyPrint = false }

private fun defaultForgeAppState(): ForgeAppState {
    if (ForgeBoardFSM.current().activeBoard == null) {
        ForgeBoardFSM.loadDefault()
    }
    val board = ForgeBoardFSM.current().activeBoard ?: error("Forge board failed to load")
    val columns = board.columns.sortedBy { it.order }.map {
        ForgeAppColumn(id = it.id.value, name = it.name, order = it.order)
    }
    val items = board.cards.sortedBy { it.order }.map { card ->
        ForgeAppItem(
            id = card.id.value,
            title = card.title,
            notes = card.description,
            status = card.columnId.value,
            priority = card.priority.name.lowercase(),
        )
    }
    val selectedItemId = items.firstOrNull()?.id
    return ForgeAppState(
        title = board.name,
        pageNotes = "Local-first workspace. Edit the page, keep notes with the work item, and move the same items across the board without leaving the document.",
        columns = columns,
        items = items,
        selectedItemId = selectedItemId,
    )
}

fun forgeAppHtml(): String {
    val seed = htmlEscape(forgeAppJson.encodeToString(defaultForgeAppState()))
    return buildString {
        appendLine("<!doctype html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("  <meta charset=\"utf-8\" />")
        appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />")
        appendLine("  <title>TrikeShed Forge workspace</title>")
        appendLine("  <style>")
        appendLine("    :root { --bg:#0b0f14; --pane:#121922; --pane2:#0f151d; --line:#1d2835; --line2:#263548; --ink:#d8e0ea; --muted:#7b8da3; --blue:#7aa2f7; --cyan:#7dcfff; --green:#9ece6a; --amber:#e0af68; --red:#f7768e; --shadow:0 16px 36px rgba(0,0,0,.25); }")
        appendLine("    * { box-sizing:border-box; }")
        appendLine("    html, body { margin:0; min-height:100%; background:var(--bg); color:var(--ink); font-family:Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, sans-serif; }")
        appendLine("    button, input, textarea, select { font:inherit; }")
        appendLine("    .app { min-height:100vh; display:grid; grid-template-columns:260px minmax(0,1fr) 420px; }")
        appendLine("    .rail, .board { background:var(--pane2); }")
        appendLine("    .rail { border-right:1px solid var(--line); padding:18px 14px; }")
        appendLine("    .board { border-left:1px solid var(--line); padding:18px 14px; overflow:auto; }")
        appendLine("    .editor { padding:24px; overflow:auto; }")
        appendLine("    .brand { padding:8px 8px 18px; border-bottom:1px solid var(--line); margin-bottom:14px; }")
        appendLine("    .eyebrow { color:var(--cyan); font-size:11px; text-transform:uppercase; letter-spacing:.14em; font-weight:700; }")
        appendLine("    .brand h1 { margin:10px 0 6px; font-size:20px; line-height:1.1; }")
        appendLine("    .brand p { margin:0; color:var(--muted); font-size:12px; line-height:1.55; }")
        appendLine("    .toolbar, .item-toolbar { display:flex; flex-wrap:wrap; gap:8px; }")
        appendLine("    .toolbar { margin-top:14px; }")
        appendLine("    .btn, .icon-btn, .status-btn { border:1px solid var(--line2); background:var(--pane); color:var(--ink); border-radius:12px; min-height:38px; padding:0 12px; cursor:pointer; }")
        appendLine("    .btn.primary { background:rgba(122,162,247,.14); border-color:rgba(122,162,247,.35); }")
        appendLine("    .icon-btn, .status-btn { min-width:38px; padding:0; }")
        appendLine("    .list { display:grid; gap:10px; }")
        appendLine("    .nav-card, .item-card, .board-card, .panel { background:var(--pane); border:1px solid var(--line); border-radius:16px; box-shadow:var(--shadow); }")
        appendLine("    .nav-card { padding:12px; cursor:pointer; }")
        appendLine("    .nav-card.active { border-color:var(--blue); background:rgba(122,162,247,.08); }")
        appendLine("    .nav-card .name { font-size:13px; font-weight:650; }")
        appendLine("    .nav-card .meta { margin-top:6px; font-size:11px; color:var(--muted); }")
        appendLine("    .page { max-width:920px; margin:0 auto; }")
        appendLine("    .page-head { margin-bottom:18px; }")
        appendLine("    .title-input, .notes-input, .item-title, .check-input, .page-notes { width:100%; border:none; outline:none; color:var(--ink); background:transparent; }")
        appendLine("    .title-input { font-size:36px; font-weight:760; letter-spacing:-.03em; }")
        appendLine("    .page-notes, .notes-input, .check-input { resize:vertical; }")
        appendLine("    .page-notes { min-height:84px; margin-top:10px; color:var(--muted); line-height:1.6; }")
        appendLine("    .item-card { padding:16px; margin-bottom:14px; }")
        appendLine("    .item-card.selected { border-color:var(--blue); box-shadow:0 0 0 1px rgba(122,162,247,.25), var(--shadow); }")
        appendLine("    .item-title { font-size:24px; font-weight:700; margin-bottom:10px; }")
        appendLine("    .item-meta { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; margin-bottom:12px; }")
        appendLine("    .field { display:grid; gap:6px; }")
        appendLine("    .field label { font-size:11px; color:var(--muted); text-transform:uppercase; letter-spacing:.08em; }")
        appendLine("    .field select, .notes-wrap, .page-notes-wrap { border:1px solid var(--line2); background:rgba(10,14,20,.72); color:var(--ink); border-radius:12px; padding:10px 12px; }")
        appendLine("    .notes-wrap { margin-bottom:12px; }")
        appendLine("    .notes-input { min-height:84px; line-height:1.55; }")
        appendLine("    .checklist { display:grid; gap:8px; margin-top:10px; }")
        appendLine("    .check-row { display:grid; grid-template-columns:auto 1fr auto; gap:8px; align-items:start; border:1px solid var(--line2); border-radius:12px; padding:8px 10px; background:rgba(10,14,20,.72); }")
        appendLine("    .check-row input[type=checkbox] { margin-top:5px; }")
        appendLine("    .check-input { min-height:32px; color:var(--ink); }")
        appendLine("    .panel { padding:14px; }")
        appendLine("    .panel h2 { margin:0 0 10px; font-size:16px; }")
        appendLine("    .panel p { margin:0; color:var(--muted); font-size:12px; line-height:1.55; }")
        appendLine("    .column-stack { display:grid; gap:14px; }")
        appendLine("    .board-column { border:1px solid var(--line); border-radius:16px; background:var(--pane); overflow:hidden; }")
        appendLine("    .board-column .head { padding:12px 14px; border-bottom:1px solid var(--line); display:flex; justify-content:space-between; gap:10px; }")
        appendLine("    .board-column .name { font-weight:700; }")
        appendLine("    .board-column .count { color:var(--muted); font-size:12px; }")
        appendLine("    .board-list { padding:12px; display:grid; gap:10px; }")
        appendLine("    .board-card { padding:12px; }")
        appendLine("    .board-card.active { border-color:var(--blue); background:rgba(122,162,247,.08); }")
        appendLine("    .board-card .title { font-weight:700; margin-bottom:6px; }")
        appendLine("    .board-card .meta { color:var(--muted); font-size:12px; line-height:1.5; white-space:pre-wrap; }")
        appendLine("    .board-actions { display:flex; gap:8px; margin-top:10px; }")
        appendLine("    .empty { color:var(--muted); font-size:12px; border:1px dashed var(--line2); border-radius:12px; padding:14px; text-align:center; }")
        appendLine("    @media (max-width: 1280px) { .app { grid-template-columns:220px minmax(0,1fr); } .board { grid-column:1 / -1; border-left:none; border-top:1px solid var(--line); } }")
        appendLine("    @media (max-width: 860px) { .app { grid-template-columns:1fr; } .rail { border-right:none; border-bottom:1px solid var(--line); } .editor { padding:16px; } .board { border-top:1px solid var(--line); } .item-meta { grid-template-columns:1fr; } }")
        appendLine("  </style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("  <div class=\"app\">")
        appendLine("    <aside class=\"rail\">")
        appendLine("      <div class=\"brand\">")
        appendLine("        <div class=\"eyebrow\">Forge workspace</div>")
        appendLine("        <h1>Local-first doc + kanban</h1>")
        appendLine("        <p>One source of truth. Write the page, keep notes on the work item, and move the same item across the board.</p>")
        appendLine("        <div class=\"toolbar\">")
        appendLine("          <button class=\"btn primary\" id=\"add-item-top\">New work item</button>")
        appendLine("          <button class=\"btn\" id=\"reset-workspace\">Reset local state</button>")
        appendLine("        </div>")
        appendLine("      </div>")
        appendLine("      <div id=\"nav-root\" class=\"list\"></div>")
        appendLine("    </aside>")
        appendLine("    <main class=\"editor\">")
        appendLine("      <div class=\"page\" id=\"doc-root\"></div>")
        appendLine("    </main>")
        appendLine("    <aside class=\"board\">")
        appendLine("      <div class=\"panel\" style=\"margin-bottom:14px;\">")
        appendLine("        <h2>Kanban</h2>")
        appendLine("        <p>The board is live. Moving a card changes the same work item you edit in the document.</p>")
        appendLine("      </div>")
        appendLine("      <div id=\"board-root\" class=\"column-stack\"></div>")
        appendLine("    </aside>")
        appendLine("  </div>")
        appendLine("  <script id=\"forge-seed\" type=\"application/json\">$seed</script>")
        appendLine("  <script>")
        appendLine(forgeAppScript())
        appendLine("  </script>")
        appendLine("</body>")
        appendLine("</html>")
    }
}

private fun forgeAppScript(): String = """
(() => {
  const STORAGE_KEY = 'forge.workspace.v2';
  const seed = JSON.parse(document.getElementById('forge-seed').textContent);
  let state = loadState();

  const navRoot = document.getElementById('nav-root');
  const docRoot = document.getElementById('doc-root');
  const boardRoot = document.getElementById('board-root');
  document.getElementById('add-item-top').addEventListener('click', () => {
    addItem();
    render();
  });
  document.getElementById('reset-workspace').addEventListener('click', () => {
    localStorage.removeItem(STORAGE_KEY);
    state = structuredClone(seed);
    ensureSelection();
    saveState();
    render();
  });

  function loadState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return structuredClone(seed);
      const parsed = JSON.parse(raw);
      if (!parsed || !Array.isArray(parsed.columns) || !Array.isArray(parsed.items)) return structuredClone(seed);
      return parsed;
    } catch (_) {
      return structuredClone(seed);
    }
  }

  function saveState() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  }

  function ensureSelection() {
    if (!state.items.length) {
      state.selectedItemId = null;
      return;
    }
    if (!state.selectedItemId || !state.items.some(item => item.id === state.selectedItemId)) {
      state.selectedItemId = state.items[0].id;
    }
  }

  function sortedColumns() {
    return [...state.columns].sort((a, b) => a.order - b.order);
  }

  function selectedItem() {
    ensureSelection();
    return state.items.find(item => item.id === state.selectedItemId) || null;
  }

  function itemById(itemId) {
    return state.items.find(item => item.id === itemId) || null;
  }

  function nextId(prefix) {
    return prefix + '-' + Math.random().toString(36).slice(2, 10);
  }

  function addItem() {
    const firstColumn = sortedColumns()[0];
    const item = {
      id: nextId('item'),
      title: 'Untitled work item',
      notes: '',
      status: firstColumn ? firstColumn.id : 'col-backlog',
      priority: 'medium',
      checklist: [],
    };
    state.items.push(item);
    state.selectedItemId = item.id;
    saveState();
  }

  function deleteItem(itemId) {
    state.items = state.items.filter(item => item.id !== itemId);
    ensureSelection();
    saveState();
    render();
  }

  function moveItem(itemId, step) {
    const item = itemById(itemId);
    if (!item) return;
    const columns = sortedColumns();
    const index = columns.findIndex(col => col.id === item.status);
    if (index < 0) return;
    const target = columns[index + step];
    if (!target) return;
    item.status = target.id;
    saveState();
    render();
  }

  function addChecklist(itemId) {
    const item = itemById(itemId);
    if (!item) return;
    item.checklist.push({ id: nextId('check'), text: '', checked: false });
    saveState();
    render();
  }

  function deleteChecklist(itemId, checkId) {
    const item = itemById(itemId);
    if (!item) return;
    item.checklist = item.checklist.filter(check => check.id !== checkId);
    saveState();
    render();
  }

  function renderNav() {
    navRoot.innerHTML = '';
    if (!state.items.length) {
      const empty = document.createElement('div');
      empty.className = 'panel';
      empty.innerHTML = '<p>No work items yet. Start with New work item.</p>';
      navRoot.appendChild(empty);
      return;
    }
    state.items.forEach(item => {
      const card = document.createElement('button');
      card.type = 'button';
      card.className = 'nav-card' + (item.id === state.selectedItemId ? ' active' : '');
      card.addEventListener('click', () => {
        state.selectedItemId = item.id;
        saveState();
        render();
      });
      const name = document.createElement('div');
      name.className = 'name';
      name.textContent = item.title || 'Untitled work item';
      const meta = document.createElement('div');
      meta.className = 'meta';
      meta.textContent = item.priority + ' · ' + columnName(item.status) + ' · ' + item.checklist.filter(check => check.checked).length + '/' + item.checklist.length + ' done';
      card.append(name, meta);
      navRoot.appendChild(card);
    });
  }

  function renderEditor() {
    docRoot.innerHTML = '';

    const pageHead = document.createElement('section');
    pageHead.className = 'page-head';

    const titleInput = document.createElement('input');
    titleInput.className = 'title-input';
    titleInput.value = state.title;
    titleInput.placeholder = 'Workspace title';
    titleInput.addEventListener('input', (event) => {
      state.title = event.target.value;
      saveState();
      renderNav();
    });
    pageHead.appendChild(titleInput);

    const notesWrap = document.createElement('div');
    notesWrap.className = 'page-notes-wrap';
    const pageNotes = document.createElement('textarea');
    pageNotes.className = 'page-notes';
    pageNotes.placeholder = 'Page notes';
    pageNotes.value = state.pageNotes || '';
    pageNotes.addEventListener('input', (event) => {
      state.pageNotes = event.target.value;
      saveState();
    });
    notesWrap.appendChild(pageNotes);
    pageHead.appendChild(notesWrap);

    const actions = document.createElement('div');
    actions.className = 'toolbar';
    const addItemBtn = document.createElement('button');
    addItemBtn.className = 'btn primary';
    addItemBtn.textContent = 'Add work item';
    addItemBtn.addEventListener('click', () => {
      addItem();
      render();
    });
    actions.appendChild(addItemBtn);
    pageHead.appendChild(actions);

    docRoot.appendChild(pageHead);

    state.items.forEach(item => {
      const card = document.createElement('article');
      card.className = 'item-card' + (item.id === state.selectedItemId ? ' selected' : '');
      card.addEventListener('click', () => {
        if (state.selectedItemId !== item.id) {
          state.selectedItemId = item.id;
          saveState();
          render();
        }
      });

      const title = document.createElement('input');
      title.className = 'item-title';
      title.value = item.title;
      title.placeholder = 'Untitled work item';
      title.addEventListener('input', (event) => {
        item.title = event.target.value;
        saveState();
        renderNav();
        renderBoard();
      });
      card.appendChild(title);

      const meta = document.createElement('div');
      meta.className = 'item-meta';
      meta.appendChild(selectField('Status', state.columns, item.status, (value) => {
        item.status = value;
        saveState();
        renderNav();
        renderBoard();
      }));
      meta.appendChild(selectField('Priority', [
        { id: 'critical', name: 'critical' },
        { id: 'high', name: 'high' },
        { id: 'medium', name: 'medium' },
        { id: 'low', name: 'low' },
      ], item.priority, (value) => {
        item.priority = value;
        saveState();
        renderNav();
        renderBoard();
      }));
      card.appendChild(meta);

      const noteWrap = document.createElement('div');
      noteWrap.className = 'notes-wrap';
      const noteArea = document.createElement('textarea');
      noteArea.className = 'notes-input';
      noteArea.placeholder = 'Write notes, specs, or next steps here.';
      noteArea.value = item.notes || '';
      noteArea.addEventListener('input', (event) => {
        item.notes = event.target.value;
        saveState();
        renderBoard();
      });
      noteWrap.appendChild(noteArea);
      card.appendChild(noteWrap);

      const itemToolbar = document.createElement('div');
      itemToolbar.className = 'item-toolbar';
      const addChecklistBtn = document.createElement('button');
      addChecklistBtn.className = 'btn';
      addChecklistBtn.textContent = 'Add checklist line';
      addChecklistBtn.addEventListener('click', (event) => {
        event.stopPropagation();
        addChecklist(item.id);
      });
      const deleteBtn = document.createElement('button');
      deleteBtn.className = 'btn';
      deleteBtn.textContent = 'Delete item';
      deleteBtn.addEventListener('click', (event) => {
        event.stopPropagation();
        deleteItem(item.id);
      });
      itemToolbar.append(addChecklistBtn, deleteBtn);
      card.appendChild(itemToolbar);

      const checklist = document.createElement('div');
      checklist.className = 'checklist';
      item.checklist.forEach(check => {
        const row = document.createElement('div');
        row.className = 'check-row';

        const toggle = document.createElement('input');
        toggle.type = 'checkbox';
        toggle.checked = !!check.checked;
        toggle.addEventListener('change', () => {
          check.checked = toggle.checked;
          saveState();
          renderNav();
          renderBoard();
        });

        const text = document.createElement('textarea');
        text.className = 'check-input';
        text.rows = 1;
        text.placeholder = 'Checklist line';
        text.value = check.text;
        text.addEventListener('input', (event) => {
          check.text = event.target.value;
          autoGrow(text);
          saveState();
          renderBoard();
        });
        autoGrow(text);

        const remove = document.createElement('button');
        remove.className = 'icon-btn';
        remove.textContent = '×';
        remove.addEventListener('click', (event) => {
          event.stopPropagation();
          deleteChecklist(item.id, check.id);
        });

        row.append(toggle, text, remove);
        checklist.appendChild(row);
      });
      card.appendChild(checklist);
      docRoot.appendChild(card);
    });
  }

  function renderBoard() {
    boardRoot.innerHTML = '';
    const activeId = state.selectedItemId;
    sortedColumns().forEach(column => {
      const cards = state.items.filter(item => item.status === column.id);
      const section = document.createElement('section');
      section.className = 'board-column';

      const head = document.createElement('div');
      head.className = 'head';
      const name = document.createElement('div');
      name.className = 'name';
      name.textContent = column.name;
      const count = document.createElement('div');
      count.className = 'count';
      count.textContent = cards.length + ' item' + (cards.length === 1 ? '' : 's');
      head.append(name, count);
      section.appendChild(head);

      const list = document.createElement('div');
      list.className = 'board-list';
      if (!cards.length) {
        const empty = document.createElement('div');
        empty.className = 'empty';
        empty.textContent = 'No items in this column';
        list.appendChild(empty);
      } else {
        cards.forEach(item => {
          const card = document.createElement('article');
          card.className = 'board-card' + (item.id === activeId ? ' active' : '');
          card.addEventListener('click', () => {
            state.selectedItemId = item.id;
            saveState();
            render();
          });

          const title = document.createElement('div');
          title.className = 'title';
          title.textContent = item.title || 'Untitled work item';
          const meta = document.createElement('div');
          meta.className = 'meta';
          meta.textContent = [item.priority, previewText(item.notes), checklistSummary(item)].filter(Boolean).join('\n');
          card.append(title, meta);

          const actions = document.createElement('div');
          actions.className = 'board-actions';
          const left = document.createElement('button');
          left.className = 'status-btn';
          left.textContent = '←';
          left.addEventListener('click', (event) => {
            event.stopPropagation();
            moveItem(item.id, -1);
          });
          const right = document.createElement('button');
          right.className = 'status-btn';
          right.textContent = '→';
          right.addEventListener('click', (event) => {
            event.stopPropagation();
            moveItem(item.id, 1);
          });
          actions.append(left, right);
          card.appendChild(actions);
          list.appendChild(card);
        });
      }
      section.appendChild(list);
      boardRoot.appendChild(section);
    });
  }

  function render() {
    ensureSelection();
    saveState();
    renderNav();
    renderEditor();
    renderBoard();
  }

  function selectField(labelText, options, selectedValue, onChange) {
    const field = document.createElement('div');
    field.className = 'field';
    const label = document.createElement('label');
    label.textContent = labelText;
    const select = document.createElement('select');
    options.forEach(option => {
      const opt = document.createElement('option');
      opt.value = option.id;
      opt.textContent = option.name;
      opt.selected = option.id === selectedValue;
      select.appendChild(opt);
    });
    select.addEventListener('change', (event) => onChange(event.target.value));
    field.append(label, select);
    return field;
  }

  function columnName(columnId) {
    const column = state.columns.find(entry => entry.id === columnId);
    return column ? column.name : columnId;
  }

  function previewText(text) {
    if (!text) return '';
    return text.length > 120 ? text.slice(0, 117) + '...' : text;
  }

  function checklistSummary(item) {
    if (!item.checklist.length) return '';
    const done = item.checklist.filter(check => check.checked).length;
    return done + '/' + item.checklist.length + ' checklist'; 
  }

  function autoGrow(textarea) {
    textarea.style.height = 'auto';
    textarea.style.height = Math.max(textarea.scrollHeight, 32) + 'px';
  }

  render();
})();
""".trimIndent()

private fun htmlEscape(text: String): String = buildString(text.length) {
    text.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(ch)
        }
    }
}
