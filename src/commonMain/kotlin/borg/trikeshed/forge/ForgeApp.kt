package borg.trikeshed.forge

import borg.trikeshed.kanban.ForgeBoardFSM
import borg.trikeshed.userspace.reactor.KanbanFSM
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
data class ForgeAppUseCase(
    val id: String,
    val name: String,
    val summary: String,
    val pageNotes: String,
    val itemTitles: List<String>,
)

@Serializable
data class ForgeAppReactorState(
    val taxonomyNodeCount: Int = 0,
    val signalFacetCount: Int = 0,
    val cacheStoredCount: Int = 0,
    val lastEventKind: String = "INIT",
    val lastEventTimestampMs: Long = 0L,
    val recentTaxonomyNodes: List<String> = emptyList(),
    val recentSignals: List<String> = emptyList(),
)

@Serializable
data class ForgeSpatialState(
    val zoom: Double = 0.82,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val focusMode: String = "board",
)

@Serializable
data class ForgeAppState(
    val title: String,
    val pageNotes: String,
    val columns: List<ForgeAppColumn>,
    val items: List<ForgeAppItem>,
    val selectedItemId: String? = null,
    val useCases: List<ForgeAppUseCase> = emptyList(),
    val reactor: ForgeAppReactorState = ForgeAppReactorState(),
    val spatial: ForgeSpatialState = ForgeSpatialState(),
)

private val forgeAppJson = Json { prettyPrint = false }

private fun defaultForgeUseCases(): List<ForgeAppUseCase> = listOf(
    ForgeAppUseCase(
        id = "brief-board",
        name = "Project brief + board",
        summary = "Page-level brief, execution board, and checklist detail in one local-first surface.",
        pageNotes = "Capture the brief at page scope, spin execution items into the board, and keep every checklist line attached to the same card.",
        itemTitles = listOf("Frame the brief", "Break work into cards", "Track execution evidence"),
    ),
    ForgeAppUseCase(
        id = "research-dossier",
        name = "Research dossier",
        summary = "Collect observations, hold decisions, and move findings toward action without leaving the page.",
        pageNotes = "Use the page for synthesis, use cards for live questions, and zoom into the evidence around the active work item.",
        itemTitles = listOf("Capture source notes", "Extract claims", "Turn claims into actions"),
    ),
    ForgeAppUseCase(
        id = "release-room",
        name = "Release room",
        summary = "Coordinate launch readiness with board flow, detailed checklists, and a shared field map.",
        pageNotes = "Keep release notes, risks, and rollout sequencing local-first while the board shows execution pressure in real time.",
        itemTitles = listOf("Lock scope", "Run final checks", "Ship + observe"),
    ),
    ForgeAppUseCase(
        id = "mesh-ops",
        name = "Mesh / reactor ops",
        summary = "Drive signal, reduction, and board motion from one operator surface.",
        pageNotes = "Use this mode to inspect causal movement, recent reductions, and the board-space relationship under RTS-style zoom.",
        itemTitles = listOf("Observe event flow", "Reduce signal facets", "Confirm board response"),
    ),
)

private fun seedNotes(title: String): String = when (title) {
    "Setup CI pipeline" -> "Define the first repeatable gate. Keep every blocking note attached to the same work item so the board and page never drift."
    "Add user authentication" -> "Turn access rules into page notes, then split the real implementation checks into the checklist below."
    "Implement API gateway" -> "This is the active systems cut. Use the zoom field to inspect how detailed work fans out around the selected card."
    "Code review: HTX client" -> "Review notes live with the card so evidence, comments, and board motion stay local-first."
    "Initial commit" -> "Capture what proved the baseline and what must remain stable while the rest of the board changes."
    "Fix login bug" -> "Use this as the debugging pattern card: root cause notes, observed symptoms, and regression checks."
    else -> "Keep the document narrative, execution notes, and board movement attached to the same card."
}

private fun seedChecklist(cardId: String, title: String): List<ForgeAppChecklistItem> = when (title) {
    "Setup CI pipeline" -> listOf(
        ForgeAppChecklistItem("$cardId-check-1", "Pick the required build + test commands", checked = true),
        ForgeAppChecklistItem("$cardId-check-2", "Capture failing output in card notes"),
        ForgeAppChecklistItem("$cardId-check-3", "Promote the green path into the gate"),
    )
    "Implement API gateway" -> listOf(
        ForgeAppChecklistItem("$cardId-check-1", "Confirm routing seam"),
        ForgeAppChecklistItem("$cardId-check-2", "Trace live event flow"),
        ForgeAppChecklistItem("$cardId-check-3", "Lock regression coverage"),
    )
    "Fix login bug" -> listOf(
        ForgeAppChecklistItem("$cardId-check-1", "Reproduce the failure"),
        ForgeAppChecklistItem("$cardId-check-2", "Trace the true source"),
        ForgeAppChecklistItem("$cardId-check-3", "Prove the fix against the real path"),
    )
    else -> emptyList()
}

private fun defaultForgeAppState(): ForgeAppState {
    if (ForgeBoardFSM.current().activeBoard == null) {
        ForgeBoardFSM.loadDefault()
    }
    val boardState = ForgeBoardFSM.current()
    val board = boardState.activeBoard ?: error("Forge board failed to load")
    val columns = board.columns.sortedBy { it.order }.map {
        ForgeAppColumn(id = it.id.value, name = it.name, order = it.order)
    }
    val items = board.cards.sortedBy { it.order }.map { card ->
        ForgeAppItem(
            id = card.id.value,
            title = card.title,
            notes = seedNotes(card.title),
            status = card.columnId.value,
            priority = card.priority.name.lowercase(),
            checklist = seedChecklist(card.id.value, card.title),
        )
    }
    val reactorCore = KanbanFSM.current()
    val recentTaxonomy = if (reactorCore.recentTaxonomyNodes.isNotEmpty()) {
        reactorCore.recentTaxonomyNodes.takeLast(6)
    } else {
        items.takeLast(6).map { it.title }
    }
    val recentSignals = buildList {
        add("ForgeBoardFSM:${boardState.lastEventKind}")
        if (reactorCore.lastEventKind != "INIT") add("KanbanFSM:${reactorCore.lastEventKind}")
    }
    return ForgeAppState(
        title = board.name,
        pageNotes = "Forge local-first workspace: page narrative, board flow, and RTS-style zoom into card detail all live in one operator surface.",
        columns = columns,
        items = items,
        selectedItemId = items.firstOrNull()?.id,
        useCases = defaultForgeUseCases(),
        reactor = ForgeAppReactorState(
            taxonomyNodeCount = maxOf(reactorCore.taxonomyNodeCount, items.size),
            signalFacetCount = reactorCore.cacheHits + reactorCore.cacheMisses + reactorCore.cacheStored + reactorCore.cacheEvicted,
            cacheStoredCount = reactorCore.cacheStored,
            lastEventKind = if (reactorCore.lastEventKind != "INIT") reactorCore.lastEventKind else "BoardLoaded",
            lastEventTimestampMs = if (reactorCore.lastEventTimestampMs != 0L) reactorCore.lastEventTimestampMs else boardState.lastEventMs,
            recentTaxonomyNodes = recentTaxonomy,
            recentSignals = recentSignals,
        ),
        spatial = ForgeSpatialState(),
    )
}

fun forgeAppHtml(): String {
    val seed = htmlEscape(forgeAppJson.encodeToString(defaultForgeAppState()))
    return """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <meta name="theme-color" content="#090d13" />
  <meta name="apple-mobile-web-app-capable" content="yes" />
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent" />
  <link rel="manifest" href="./manifest.webmanifest" />
  <link rel="icon" href="./icons/forge-icon.svg" type="image/svg+xml" />
  <link rel="apple-touch-icon" href="./icons/forge-icon-maskable.svg" />
  <title>TrikeShed Forge workspace</title>
  <style>
${forgeAppStyles()}
  </style>
</head>
<body>
  <div class="app-shell">
    <aside class="rail">
      <div class="brand panel">
        <div class="eyebrow">Forge local-first</div>
        <h1>Page, board, reactor, field</h1>
        <p>CRUD on the same local-first work graph, with an RTS-style zoom surface that opens fractal detail around the selected card.</p>
        <div class="toolbar compact">
          <button class="btn primary" id="add-item-top">New work item</button>
          <button class="btn" id="reset-workspace">Reset local state</button>
        </div>
      </div>
      <div class="panel section-block">
        <div class="section-head">
          <h2>Use cases</h2>
          <p>Load document + board patterns into the same workspace.</p>
        </div>
        <div id="usecase-root" class="usecase-list"></div>
      </div>
      <div class="panel section-block">
        <div class="section-head">
          <h2>Work items</h2>
          <p>Document blocks and board cards are the same local objects.</p>
        </div>
        <div id="nav-root" class="nav-list"></div>
      </div>
    </aside>
    <main class="editor">
      <div id="doc-root" class="page"></div>
    </main>
    <aside class="board-pane">
      <div class="panel section-block">
        <div class="section-head">
          <h2>Kanban reactor core</h2>
          <p>CRUD emits taxonomy and signal events into the local-first board surface.</p>
        </div>
        <div id="reactor-root"></div>
      </div>
      <div class="panel section-block">
        <div class="section-head">
          <h2>RTS / fractal field</h2>
          <p>Zoom from workspace lanes into card-level checklist detail without leaving the board.</p>
        </div>
        <div class="space-toolbar">
          <label class="zoom-stack" for="zoom-slider">
            <span>Zoom</span>
            <input id="zoom-slider" type="range" min="0.55" max="2.8" step="0.05" />
          </label>
          <div class="toolbar compact">
            <button class="btn" id="focus-board">Whole board</button>
            <button class="btn" id="focus-selected">Selected card</button>
          </div>
        </div>
        <div class="space-caption"><span id="zoom-label"></span></div>
        <div id="spatial-shell" class="spatial-shell">
          <svg id="spatial-root" class="spatial-root" viewBox="0 0 1200 720" preserveAspectRatio="xMidYMid meet"></svg>
        </div>
      </div>
      <div class="panel section-block">
        <div class="section-head">
          <h2>Kanban board</h2>
          <p>Move the same items you edit in the page. CRUD stays local-first.</p>
        </div>
        <div id="board-root" class="column-stack"></div>
      </div>
    </aside>
  </div>
  <script id="forge-seed" type="application/json">$seed</script>
  <script>
${forgeAppScript()}
  </script>
</body>
</html>
    """.trimIndent()
}

private fun forgeAppStyles(): String = """
    :root {
      --bg:#090d13;
      --pane:#111824;
      --pane2:#0d131d;
      --line:#1b2635;
      --line2:#263548;
      --ink:#dbe7f3;
      --muted:#7e8da0;
      --blue:#7aa2f7;
      --cyan:#7dcfff;
      --green:#9ece6a;
      --amber:#e0af68;
      --red:#f7768e;
      --shadow:0 18px 44px rgba(0,0,0,.28);
    }
    * { box-sizing:border-box; }
    html, body {
      margin:0;
      min-height:100%;
      background:radial-gradient(circle at top, #101a27 0%, var(--bg) 52%);
      color:var(--ink);
      font-family:Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
    }
    button, input, textarea, select { font:inherit; }
    .app-shell {
      min-height:100vh;
      display:grid;
      grid-template-columns:280px minmax(0, 1fr) 460px;
      background:linear-gradient(180deg, rgba(255,255,255,.015), rgba(255,255,255,0));
    }
    .rail { border-right:1px solid var(--line); padding:16px; display:grid; gap:14px; background:rgba(9,13,19,.88); }
    .editor { padding:24px; overflow:auto; }
    .board-pane { border-left:1px solid var(--line); padding:16px; display:grid; gap:14px; background:rgba(9,13,19,.92); overflow:auto; }
    .panel {
      background:linear-gradient(180deg, rgba(18,25,36,.97), rgba(12,18,28,.95));
      border:1px solid var(--line);
      border-radius:18px;
      box-shadow:var(--shadow);
    }
    .brand { padding:16px; }
    .section-block { padding:14px; }
    .eyebrow { color:var(--cyan); font-size:11px; text-transform:uppercase; letter-spacing:.16em; font-weight:800; }
    .brand h1 { margin:10px 0 8px; font-size:24px; line-height:1.1; }
    .brand p, .section-head p, .reactor-note, .space-caption { color:var(--muted); font-size:12px; line-height:1.55; }
    .section-head { margin-bottom:12px; }
    .section-head h2 { margin:0 0 6px; font-size:16px; }
    .toolbar { display:flex; flex-wrap:wrap; gap:8px; }
    .toolbar.compact { margin-top:12px; }
    .btn, .status-btn, .ghost-btn {
      border:1px solid var(--line2);
      border-radius:12px;
      background:rgba(17,24,36,.95);
      color:var(--ink);
      min-height:38px;
      padding:0 12px;
      cursor:pointer;
    }
    .btn.primary { background:rgba(122,162,247,.18); border-color:rgba(122,162,247,.45); }
    .status-btn { min-width:38px; padding:0; }
    .ghost-btn { background:rgba(9,13,19,.55); color:var(--muted); }
    .usecase-list, .nav-list, .column-stack, .checklist { display:grid; gap:10px; }
    .usecase-card, .nav-card, .item-card, .board-column, .board-card { border:1px solid var(--line); border-radius:16px; background:rgba(17,24,36,.95); box-shadow:var(--shadow); }
    .usecase-card, .nav-card { width:100%; text-align:left; padding:12px; cursor:pointer; }
    .usecase-card:hover, .nav-card:hover, .board-card:hover { border-color:rgba(122,162,247,.45); }
    .nav-card.active, .board-card.active, .item-card.selected { border-color:var(--blue); box-shadow:0 0 0 1px rgba(122,162,247,.24), var(--shadow); }
    .usecase-name, .nav-name, .board-title { font-size:14px; font-weight:700; }
    .usecase-summary, .nav-meta, .board-meta { margin-top:6px; color:var(--muted); font-size:12px; line-height:1.5; white-space:pre-wrap; }
    .page { max-width:980px; margin:0 auto; }
    .page-head { display:grid; gap:14px; margin-bottom:18px; }
    .title-input, .item-title, .notes-input, .check-input, .page-notes { width:100%; border:none; outline:none; background:transparent; color:var(--ink); }
    .title-input { font-size:40px; font-weight:800; letter-spacing:-.03em; }
    .page-notes, .notes-input, .check-input { resize:vertical; line-height:1.6; }
    .page-notes-wrap, .notes-wrap, .field select { border:1px solid var(--line2); border-radius:14px; background:rgba(8,12,18,.82); }
    .page-notes-wrap, .notes-wrap { padding:12px 14px; }
    .page-notes { min-height:96px; color:var(--muted); }
    .item-card { padding:16px; margin-bottom:14px; }
    .item-title { font-size:24px; font-weight:760; margin-bottom:12px; }
    .item-meta { display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:10px; margin-bottom:12px; }
    .field { display:grid; gap:6px; }
    .field label { font-size:11px; color:var(--muted); text-transform:uppercase; letter-spacing:.08em; }
    .field select { color:var(--ink); padding:10px 12px; min-height:42px; }
    .notes-input { min-height:96px; }
    .check-row {
      display:grid;
      grid-template-columns:auto 1fr auto;
      gap:8px;
      align-items:start;
      border:1px solid var(--line2);
      border-radius:12px;
      padding:8px 10px;
      background:rgba(8,12,18,.82);
    }
    .check-row input[type=checkbox] { margin-top:6px; }
    .check-input { min-height:32px; }
    .board-column { overflow:hidden; }
    .board-column .head {
      padding:12px 14px;
      border-bottom:1px solid var(--line);
      display:flex;
      justify-content:space-between;
      gap:10px;
    }
    .board-column .name { font-weight:700; }
    .board-column .count { color:var(--muted); font-size:12px; }
    .board-list { padding:12px; display:grid; gap:10px; }
    .board-card { padding:12px; }
    .board-actions { display:flex; gap:8px; margin-top:10px; }
    .empty {
      color:var(--muted);
      font-size:12px;
      border:1px dashed var(--line2);
      border-radius:12px;
      padding:14px;
      text-align:center;
    }
    .reactor-grid {
      display:grid;
      grid-template-columns:repeat(2, minmax(0, 1fr));
      gap:10px;
      margin-bottom:12px;
    }
    .reactor-stat {
      border:1px solid var(--line2);
      border-radius:14px;
      padding:12px;
      background:rgba(8,12,18,.82);
    }
    .reactor-stat .label { color:var(--muted); font-size:11px; text-transform:uppercase; letter-spacing:.08em; }
    .reactor-stat .value { margin-top:6px; font-size:22px; font-weight:760; }
    .reactor-stack { display:grid; gap:10px; }
    .reactor-list {
      display:grid;
      gap:6px;
      border:1px solid var(--line2);
      border-radius:14px;
      padding:12px;
      background:rgba(8,12,18,.82);
    }
    .reactor-pill {
      display:inline-flex;
      align-items:center;
      gap:6px;
      min-height:28px;
      padding:0 10px;
      border-radius:999px;
      border:1px solid rgba(122,162,247,.28);
      background:rgba(122,162,247,.12);
      font-size:12px;
    }
    .space-toolbar {
      display:grid;
      grid-template-columns:minmax(0,1fr) auto;
      gap:12px;
      align-items:end;
      margin-bottom:10px;
    }
    .zoom-stack { display:grid; gap:6px; color:var(--muted); font-size:11px; text-transform:uppercase; letter-spacing:.08em; }
    .zoom-stack input { width:100%; }
    .spatial-shell {
      position:relative;
      min-height:420px;
      border:1px solid var(--line2);
      border-radius:18px;
      background:radial-gradient(circle at top, rgba(122,162,247,.08), rgba(8,12,18,.96) 56%);
      overflow:hidden;
      cursor:grab;
    }
    .spatial-shell.dragging { cursor:grabbing; }
    .spatial-root { width:100%; height:420px; display:block; }
    @media (max-width: 1380px) {
      .app-shell { grid-template-columns:250px minmax(0, 1fr); }
      .board-pane { grid-column:1 / -1; border-left:none; border-top:1px solid var(--line); }
    }
    @media (max-width: 900px) {
      .app-shell { grid-template-columns:1fr; }
      .rail { border-right:none; border-bottom:1px solid var(--line); }
      .editor { padding:16px; }
      .item-meta, .space-toolbar, .reactor-grid { grid-template-columns:1fr; }
      .board-pane { border-top:1px solid var(--line); }
    }
""".trimIndent()

private fun forgeAppScript(): String = """
(() => {
  const STORAGE_KEY = 'forge.workspace.v4';
  const CACHE_NAME = 'forge-webcache-v1';
  const SNAPSHOT_CACHE_URL = './forge-workspace.snapshot.json';
  const DB_NAME = 'forge-local-first-v1';
  const DB_VERSION = 1;
  const SNAPSHOT_STORE = 'workspaceSnapshots';
  const EVENT_STORE = 'reactorEvents';
  const REACTOR_LOG_LIMIT = 24;
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const seed = JSON.parse(document.getElementById('forge-seed').textContent);
  let state = loadState();
  let dragCamera = null;
  let persistenceDbPromise = null;
  let persistenceFlushTimer = null;
  let persistenceFlushPromise = Promise.resolve();

  registerPwaShell();

  const navRoot = document.getElementById('nav-root');
  const usecaseRoot = document.getElementById('usecase-root');
  const docRoot = document.getElementById('doc-root');
  const boardRoot = document.getElementById('board-root');
  const reactorRoot = document.getElementById('reactor-root');
  const spatialShell = document.getElementById('spatial-shell');
  const spatialRoot = document.getElementById('spatial-root');
  const zoomSlider = document.getElementById('zoom-slider');
  const zoomLabel = document.getElementById('zoom-label');

  zoomSlider.value = String(state.spatial.zoom || 0.82);

  document.getElementById('add-item-top').addEventListener('click', () => {
    addItem();
    render();
  });
  document.getElementById('reset-workspace').addEventListener('click', async () => {
    await clearPersistedWorkspace();
    state = normalizeState(structuredClone(seed));
    ensureSelection();
    recordReactor('CacheStored', 'workspace reset', 'pwa');
    saveState();
    render();
  });
  document.getElementById('focus-board').addEventListener('click', () => {
    state.spatial.focusMode = 'board';
    state.spatial.zoom = 0.82;
    state.spatial.offsetX = 0;
    state.spatial.offsetY = 0;
    saveState();
    renderSpatial();
  });
  document.getElementById('focus-selected').addEventListener('click', () => {
    state.spatial.focusMode = 'selected';
    state.spatial.zoom = Math.max(state.spatial.zoom || 0.82, 1.9);
    focusSelected();
    saveState();
    renderSpatial();
  });
  zoomSlider.addEventListener('input', (event) => {
    state.spatial.zoom = clamp(Number(event.target.value) || 0.82, 0.55, 2.8);
    state.spatial.focusMode = 'manual';
    renderSpatial();
  });
  zoomSlider.addEventListener('change', () => saveState());
  bindSpatialGestures();
  hydratePersistence();

  function loadState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return normalizeState(structuredClone(seed));
      return normalizeState(JSON.parse(raw));
    } catch (_) {
      return normalizeState(structuredClone(seed));
    }
  }

  function normalizeState(raw) {
    const base = structuredClone(seed);
    const next = Object.assign(base, raw || {});
    next.title = typeof next.title === 'string' && next.title.trim() ? next.title : base.title;
    next.pageNotes = typeof next.pageNotes === 'string' ? next.pageNotes : base.pageNotes;
    next.columns = Array.isArray(raw && raw.columns) && raw.columns.length ? raw.columns : base.columns;
    next.items = Array.isArray(raw && raw.items) ? raw.items.map(normalizeItem) : base.items;
    next.useCases = Array.isArray(raw && raw.useCases) && raw.useCases.length ? raw.useCases : base.useCases;
    next.reactor = Object.assign({}, base.reactor, raw && raw.reactor ? raw.reactor : {});
    next.reactor.recentTaxonomyNodes = Array.isArray(next.reactor.recentTaxonomyNodes) ? next.reactor.recentTaxonomyNodes.slice(-6) : [];
    next.reactor.recentSignals = Array.isArray(next.reactor.recentSignals) ? next.reactor.recentSignals.slice(-8) : [];
    next.reactorLog = normalizeReactorLog(raw && raw.reactorLog);
    next.cache = normalizeCacheState(raw && raw.cache);
    next.spatial = Object.assign({}, base.spatial, raw && raw.spatial ? raw.spatial : {});
    next.spatial.zoom = clamp(Number(next.spatial.zoom) || base.spatial.zoom, 0.55, 2.8);
    next.spatial.offsetX = Number(next.spatial.offsetX) || 0;
    next.spatial.offsetY = Number(next.spatial.offsetY) || 0;
    next.spatial.focusMode = typeof next.spatial.focusMode === 'string' ? next.spatial.focusMode : 'board';
    ensureSelection(next);
    return next;
  }

  function normalizeCacheState(raw) {
    const next = Object.assign({
      hydrationSource: 'seed',
      hydrationTimestampMs: 0,
      snapshotTimestampMs: 0,
      lastLocalSaveMs: 0,
      lastPersistTarget: 'localStorage',
      status: 'local-only',
    }, raw || {});
    next.hydrationSource = typeof next.hydrationSource === 'string' ? next.hydrationSource : 'seed';
    next.lastPersistTarget = typeof next.lastPersistTarget === 'string' ? next.lastPersistTarget : 'localStorage';
    next.status = typeof next.status === 'string' ? next.status : 'local-only';
    next.hydrationTimestampMs = Number(next.hydrationTimestampMs) || 0;
    next.snapshotTimestampMs = Number(next.snapshotTimestampMs) || 0;
    next.lastLocalSaveMs = Number(next.lastLocalSaveMs) || 0;
    return next;
  }

  function normalizeReactorLog(entries) {
    return Array.isArray(entries)
      ? entries
          .filter((entry) => entry && typeof entry.kind === 'string')
          .map((entry) => ({
            id: entry.id || nextId('evt'),
            kind: entry.kind,
            label: typeof entry.label === 'string' ? entry.label : '',
            source: typeof entry.source === 'string' ? entry.source : 'forge',
            timestampMs: Number(entry.timestampMs) || 0,
          }))
          .slice(-REACTOR_LOG_LIMIT)
      : [];
  }

  async function hydratePersistence() {
    const persisted = await loadPersistedSnapshot();
    if (persisted) {
      state = normalizeState(persisted.snapshot);
      state.cache.hydrationSource = persisted.source;
      state.cache.hydrationTimestampMs = Date.now();
      state.cache.status = 'hydrated';
      state.cache.lastPersistTarget = persisted.source;
    } else {
      state = normalizeState(state);
      state.cache.hydrationSource = 'localStorage';
      state.cache.hydrationTimestampMs = Date.now();
      state.cache.status = 'local-only';
    }
    const reactorLog = await loadPersistedReactorEvents();
    if (reactorLog.length) {
      state.reactorLog = normalizeReactorLog(reactorLog);
    }
    render();
  }

  function normalizeItem(item) {
    return {
      id: item && item.id ? item.id : nextId('item'),
      title: typeof item.title === 'string' ? item.title : 'Untitled work item',
      notes: typeof item.notes === 'string' ? item.notes : '',
      status: typeof item.status === 'string' ? item.status : 'col-backlog',
      priority: typeof item.priority === 'string' ? item.priority : 'medium',
      checklist: Array.isArray(item && item.checklist)
        ? item.checklist.map((check) => ({
            id: check && check.id ? check.id : nextId('check'),
            text: typeof check.text === 'string' ? check.text : '',
            checked: !!check.checked,
          }))
        : [],
    };
  }

  function saveState() {
    state.cache = normalizeCacheState(state.cache);
    state.cache.lastLocalSaveMs = Date.now();
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    queuePersistenceFlush();
  }

  function queuePersistenceFlush() {
    if (persistenceFlushTimer) {
      clearTimeout(persistenceFlushTimer);
    }
    persistenceFlushTimer = setTimeout(() => {
      persistenceFlushTimer = null;
      persistenceFlushPromise = persistenceFlushPromise
        .then(() => persistWorkspaceSnapshot())
        .catch((error) => console.warn('Forge snapshot persistence failed', error));
    }, 90);
  }

  async function persistWorkspaceSnapshot() {
    const snapshot = normalizeState(JSON.parse(JSON.stringify(state)));
    const now = Date.now();
    snapshot.cache.snapshotTimestampMs = now;
    snapshot.cache.lastPersistTarget = 'indexeddb+cache';
    snapshot.cache.status = 'persisted';
    await Promise.all([
      writeSnapshotToIndexedDb(snapshot),
      writeSnapshotToCache(snapshot),
    ]);
    state.cache.snapshotTimestampMs = now;
    state.cache.lastPersistTarget = 'indexeddb+cache';
    state.cache.status = 'persisted';
  }

  function openPersistenceDb() {
    if (!("indexedDB" in window)) return Promise.resolve(null);
    if (persistenceDbPromise) return persistenceDbPromise;
    persistenceDbPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(SNAPSHOT_STORE)) {
          db.createObjectStore(SNAPSHOT_STORE, { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains(EVENT_STORE)) {
          const store = db.createObjectStore(EVENT_STORE, { keyPath: 'id' });
          store.createIndex('timestampMs', 'timestampMs', { unique: false });
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error('Forge persistence DB open failed'));
    });
    return persistenceDbPromise;
  }

  async function writeSnapshotToIndexedDb(snapshot) {
    const db = await openPersistenceDb();
    if (!db) return;
    await new Promise((resolve, reject) => {
      const tx = db.transaction(SNAPSHOT_STORE, 'readwrite');
      tx.objectStore(SNAPSHOT_STORE).put({
        id: 'workspace',
        updatedAt: snapshot.cache.snapshotTimestampMs || Date.now(),
        payload: snapshot,
      });
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error || new Error('Forge snapshot IndexedDB write failed'));
      tx.onabort = () => reject(tx.error || new Error('Forge snapshot IndexedDB transaction aborted'));
    });
  }

  async function writeSnapshotToCache(snapshot) {
    if (!("caches" in window)) return;
    const cache = await caches.open(CACHE_NAME);
    await cache.put(
      SNAPSHOT_CACHE_URL,
      new Response(JSON.stringify(snapshot), {
        headers: {
          'content-type': 'application/json',
          'cache-control': 'no-store',
        },
      }),
    );
  }

  async function loadPersistedSnapshot() {
    const indexedDbSnapshot = await readSnapshotFromIndexedDb();
    if (indexedDbSnapshot) return { source: 'indexeddb', snapshot: indexedDbSnapshot };
    const cachedSnapshot = await readSnapshotFromCache();
    if (cachedSnapshot) return { source: 'cache-storage', snapshot: cachedSnapshot };
    return null;
  }

  async function readSnapshotFromIndexedDb() {
    const db = await openPersistenceDb();
    if (!db) return null;
    return new Promise((resolve, reject) => {
      const tx = db.transaction(SNAPSHOT_STORE, 'readonly');
      const request = tx.objectStore(SNAPSHOT_STORE).get('workspace');
      request.onsuccess = () => resolve(request.result && request.result.payload ? request.result.payload : null);
      request.onerror = () => reject(request.error || new Error('Forge snapshot IndexedDB read failed'));
    }).catch((error) => {
      console.warn('Forge snapshot IndexedDB read failed', error);
      return null;
    });
  }

  async function readSnapshotFromCache() {
    if (!("caches" in window)) return null;
    try {
      const cache = await caches.open(CACHE_NAME);
      const response = await cache.match(SNAPSHOT_CACHE_URL);
      if (!response) return null;
      return await response.json();
    } catch (error) {
      console.warn('Forge snapshot cache read failed', error);
      return null;
    }
  }

  async function clearPersistedWorkspace() {
    localStorage.removeItem(STORAGE_KEY);
    if ("caches" in window) {
      const cache = await caches.open(CACHE_NAME);
      await cache.delete(SNAPSHOT_CACHE_URL);
    }
    const db = await openPersistenceDb();
    if (!db) return;
    await Promise.all([
      new Promise((resolve, reject) => {
        const tx = db.transaction(SNAPSHOT_STORE, 'readwrite');
        tx.objectStore(SNAPSHOT_STORE).delete('workspace');
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error || new Error('Forge snapshot delete failed'));
      }),
      new Promise((resolve, reject) => {
        const tx = db.transaction(EVENT_STORE, 'readwrite');
        tx.objectStore(EVENT_STORE).clear();
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error || new Error('Forge event log clear failed'));
      }),
    ]);
  }

  async function persistReactorEvent(entry) {
    const db = await openPersistenceDb();
    if (!db) return;
    await new Promise((resolve, reject) => {
      const tx = db.transaction(EVENT_STORE, 'readwrite');
      tx.objectStore(EVENT_STORE).put(entry);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error || new Error('Forge reactor event write failed'));
      tx.onabort = () => reject(tx.error || new Error('Forge reactor event transaction aborted'));
    }).catch((error) => {
      console.warn('Forge reactor event persistence failed', error);
    });
  }

  async function loadPersistedReactorEvents() {
    const db = await openPersistenceDb();
    if (!db) return [];
    return new Promise((resolve, reject) => {
      const tx = db.transaction(EVENT_STORE, 'readonly');
      const request = tx.objectStore(EVENT_STORE).getAll();
      request.onsuccess = () => resolve(normalizeReactorLog(request.result || []));
      request.onerror = () => reject(request.error || new Error('Forge reactor event read failed'));
    }).catch((error) => {
      console.warn('Forge reactor event load failed', error);
      return [];
    });
  }

  function registerPwaShell() {
    if (!("serviceWorker" in navigator)) return;
    window.addEventListener('appinstalled', () => {
      recordReactor('CacheStored', 'PWA installed');
      saveState();
      renderReactor();
    });
    navigator.serviceWorker.register('./sw.js').then((registration) => {
      const scope = registration.scope || 'local';
      recordReactor('CacheStored', 'PWA shell ' + scope);
      saveState();
      renderReactor();
    }).catch((error) => {
      console.warn('Forge PWA registration failed', error);
    });
  }

  function ensureSelection(target) {
    const ref = target || state;
    if (!ref.items.length) {
      ref.selectedItemId = null;
      return;
    }
    if (!ref.selectedItemId || !ref.items.some((item) => item.id === ref.selectedItemId)) {
      ref.selectedItemId = ref.items[0].id;
    }
  }

  function sortedColumns() {
    return [...state.columns].sort((a, b) => a.order - b.order);
  }

  function itemById(itemId) {
    return state.items.find((item) => item.id === itemId) || null;
  }

  function nextId(prefix) {
    return prefix + '-' + Math.random().toString(36).slice(2, 10);
  }

  function addItem(prefill) {
    const firstColumn = sortedColumns()[0];
    const item = {
      id: nextId('item'),
      title: (prefill && prefill.title) || 'Untitled work item',
      notes: (prefill && prefill.notes) || '',
      status: (prefill && prefill.status) || (firstColumn ? firstColumn.id : 'col-backlog'),
      priority: (prefill && prefill.priority) || 'medium',
      checklist: (prefill && Array.isArray(prefill.checklist)) ? prefill.checklist : [],
    };
    state.items.push(item);
    state.selectedItemId = item.id;
    recordReactor('TaxonomyNodeCreated', item.title);
    saveState();
  }

  function deleteItem(itemId) {
    const doomed = itemById(itemId);
    state.items = state.items.filter((item) => item.id !== itemId);
    ensureSelection();
    recordReactor('SignalFacetReduced', doomed ? 'Deleted ' + doomed.title : 'Deleted item');
    saveState();
    render();
  }

  function moveItem(itemId, step) {
    const item = itemById(itemId);
    if (!item) return;
    const columns = sortedColumns();
    const index = columns.findIndex((col) => col.id === item.status);
    if (index < 0) return;
    const target = columns[index + step];
    if (!target) return;
    item.status = target.id;
    recordReactor('SignalFacetReduced', item.title + ' → ' + target.name);
    saveState();
    renderBoard();
    renderNav();
    renderReactor();
    renderSpatial();
  }

  function addChecklist(itemId) {
    const item = itemById(itemId);
    if (!item) return;
    item.checklist.push({ id: nextId('check'), text: '', checked: false });
    recordReactor('TaxonomyNodeCreated', item.title + ' checklist');
    saveState();
    render();
  }

  function deleteChecklist(itemId, checkId) {
    const item = itemById(itemId);
    if (!item) return;
    item.checklist = item.checklist.filter((check) => check.id !== checkId);
    recordReactor('SignalFacetReduced', item.title + ' checklist trimmed');
    saveState();
    render();
  }

  function recordReactor(kind, label, source) {
    const now = Date.now();
    const origin = source || 'forge';
    state.reactor.lastEventKind = kind;
    state.reactor.lastEventTimestampMs = now;
    if (kind === 'TaxonomyNodeCreated') {
      state.reactor.taxonomyNodeCount += 1;
      if (label) {
        state.reactor.recentTaxonomyNodes = [...state.reactor.recentTaxonomyNodes, label].slice(-6);
      }
    } else if (kind === 'SignalFacetReduced') {
      state.reactor.signalFacetCount += 1;
      if (label) {
        state.reactor.recentSignals = [...state.reactor.recentSignals, label].slice(-8);
      }
    } else if (kind === 'CacheStored') {
      state.reactor.cacheStoredCount += 1;
      if (label) {
        state.reactor.recentSignals = [...state.reactor.recentSignals, 'stored: ' + label].slice(-8);
      }
    }
    const entry = {
      id: nextId('evt'),
      kind,
      label: label || '',
      source: origin,
      timestampMs: now,
    };
    state.reactorLog = normalizeReactorLog([...(state.reactorLog || []), entry]);
    void persistReactorEvent(entry);
  }

  function applyUseCase(useCaseId) {
    const useCase = state.useCases.find((entry) => entry.id === useCaseId);
    if (!useCase) return;
    state.title = useCase.name;
    state.pageNotes = useCase.pageNotes;
    const columns = sortedColumns();
    useCase.itemTitles.forEach((title, index) => {
      if (state.items.some((item) => item.title === title)) return;
      const fallback = columns[Math.min(index, columns.length - 1)] || columns[0];
      addItem({
        title,
        notes: useCase.summary,
        status: fallback ? fallback.id : 'col-backlog',
        priority: index === 0 ? 'high' : 'medium',
        checklist: [
          { id: nextId('check'), text: 'Capture the page context', checked: false },
          { id: nextId('check'), text: 'Move this through the board', checked: false },
        ],
      });
    });
    recordReactor('CacheStored', useCase.name);
    saveState();
    render();
  }

  function renderUseCases() {
    usecaseRoot.innerHTML = '';
    state.useCases.forEach((useCase) => {
      const card = document.createElement('button');
      card.type = 'button';
      card.className = 'usecase-card';
      card.addEventListener('click', () => applyUseCase(useCase.id));
      const name = document.createElement('div');
      name.className = 'usecase-name';
      name.textContent = useCase.name;
      const summary = document.createElement('div');
      summary.className = 'usecase-summary';
      summary.textContent = useCase.summary;
      card.append(name, summary);
      usecaseRoot.appendChild(card);
    });
  }

  function renderNav() {
    navRoot.innerHTML = '';
    if (!state.items.length) {
      const empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = 'No work items yet. Start with New work item.';
      navRoot.appendChild(empty);
      return;
    }
    state.items.forEach((item) => {
      const card = document.createElement('button');
      card.type = 'button';
      card.className = 'nav-card' + (item.id === state.selectedItemId ? ' active' : '');
      card.addEventListener('click', () => {
        state.selectedItemId = item.id;
        state.spatial.focusMode = 'selected';
        focusSelected();
        saveState();
        render();
      });
      const name = document.createElement('div');
      name.className = 'nav-name';
      name.textContent = item.title || 'Untitled work item';
      const meta = document.createElement('div');
      meta.className = 'nav-meta';
      meta.textContent = item.priority + ' · ' + columnName(item.status) + ' · ' + checklistSummary(item);
      card.append(name, meta);
      navRoot.appendChild(card);
    });
  }

  function renderEditor() {
    ensureSelection();
    docRoot.innerHTML = '';

    const pageHead = document.createElement('section');
    pageHead.className = 'page-head';

    const titleInput = document.createElement('input');
    titleInput.className = 'title-input';
    titleInput.value = state.title;
    titleInput.placeholder = 'Workspace title';
    titleInput.addEventListener('input', (event) => {
      state.title = event.target.value;
      recordReactor('CacheStored', 'workspace title');
      saveState();
      renderNav();
      renderReactor();
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
      recordReactor('CacheStored', 'page notes');
      saveState();
      renderReactor();
      renderSpatial();
    });
    notesWrap.appendChild(pageNotes);
    pageHead.appendChild(notesWrap);

    const actions = document.createElement('div');
    actions.className = 'toolbar compact';
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

    state.items.forEach((item) => {
      const card = document.createElement('article');
      card.className = 'item-card' + (item.id === state.selectedItemId ? ' selected' : '');
      card.addEventListener('click', () => {
        if (state.selectedItemId !== item.id) {
          state.selectedItemId = item.id;
          state.spatial.focusMode = 'selected';
          focusSelected();
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
        recordReactor('CacheStored', 'item title');
        saveState();
        renderNav();
        renderBoard();
        renderReactor();
        renderSpatial();
      });
      card.appendChild(title);

      const meta = document.createElement('div');
      meta.className = 'item-meta';
      meta.appendChild(selectField('Status', state.columns, item.status, (value) => {
        item.status = value;
        recordReactor('SignalFacetReduced', item.title + ' → ' + columnName(value));
        saveState();
        renderNav();
        renderBoard();
        renderReactor();
        renderSpatial();
      }));
      meta.appendChild(selectField('Priority', [
        { id: 'critical', name: 'critical' },
        { id: 'high', name: 'high' },
        { id: 'medium', name: 'medium' },
        { id: 'low', name: 'low' },
      ], item.priority, (value) => {
        item.priority = value;
        recordReactor('CacheStored', item.title + ' priority');
        saveState();
        renderNav();
        renderBoard();
        renderReactor();
        renderSpatial();
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
        recordReactor('CacheStored', item.title + ' notes');
        saveState();
        renderBoard();
        renderReactor();
        renderSpatial();
      });
      noteWrap.appendChild(noteArea);
      card.appendChild(noteWrap);

      const itemToolbar = document.createElement('div');
      itemToolbar.className = 'toolbar compact';
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
      item.checklist.forEach((check) => {
        const row = document.createElement('div');
        row.className = 'check-row';

        const toggle = document.createElement('input');
        toggle.type = 'checkbox';
        toggle.checked = !!check.checked;
        toggle.addEventListener('change', () => {
          check.checked = toggle.checked;
          recordReactor('SignalFacetReduced', item.title + ' checklist');
          saveState();
          renderNav();
          renderBoard();
          renderReactor();
          renderSpatial();
        });

        const text = document.createElement('textarea');
        text.className = 'check-input';
        text.rows = 1;
        text.placeholder = 'Checklist line';
        text.value = check.text;
        text.addEventListener('input', (event) => {
          check.text = event.target.value;
          autoGrow(text);
          recordReactor('CacheStored', item.title + ' checklist text');
          saveState();
          renderBoard();
          renderReactor();
          renderSpatial();
        });
        autoGrow(text);

        const remove = document.createElement('button');
        remove.className = 'status-btn';
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
    sortedColumns().forEach((column) => {
      const cards = state.items.filter((item) => item.status === column.id);
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
        cards.forEach((item) => {
          const card = document.createElement('article');
          card.className = 'board-card' + (item.id === activeId ? ' active' : '');
          card.addEventListener('click', () => {
            state.selectedItemId = item.id;
            state.spatial.focusMode = 'selected';
            focusSelected();
            saveState();
            render();
          });

          const title = document.createElement('div');
          title.className = 'board-title';
          title.textContent = item.title || 'Untitled work item';
          const meta = document.createElement('div');
          meta.className = 'board-meta';
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

  function renderReactor() {
    reactorRoot.innerHTML = '';

    const grid = document.createElement('div');
    grid.className = 'reactor-grid';
    grid.append(
      reactorStat('taxonomy nodes', String(state.reactor.taxonomyNodeCount)),
      reactorStat('signal reductions', String(state.reactor.signalFacetCount)),
      reactorStat('stored edits', String(state.reactor.cacheStoredCount)),
      reactorStat('last event', state.reactor.lastEventKind || 'INIT'),
    );
    reactorRoot.appendChild(grid);

    const stack = document.createElement('div');
    stack.className = 'reactor-stack';
    stack.appendChild(reactorList('recent taxonomy', state.reactor.recentTaxonomyNodes));
    stack.appendChild(reactorList('recent signal trail', state.reactor.recentSignals));
    stack.appendChild(reactorList('operational log', (state.reactorLog || []).map(formatReactorEvent)));

    const note = document.createElement('div');
    note.className = 'reactor-note';
    note.textContent = 'Hydrated from ' + (state.cache.hydrationSource || 'localStorage') + ', snapshot ' + formatTimestamp(state.cache.snapshotTimestampMs) + ', last local save ' + formatTimestamp(state.cache.lastLocalSaveMs) + '. CRUD in the page and board feeds this local reactor summary.';
    stack.appendChild(note);

    reactorRoot.appendChild(stack);
  }

  function reactorStat(labelText, valueText) {
    const card = document.createElement('div');
    card.className = 'reactor-stat';
    const label = document.createElement('div');
    label.className = 'label';
    label.textContent = labelText;
    const value = document.createElement('div');
    value.className = 'value';
    value.textContent = valueText;
    card.append(label, value);
    return card;
  }

  function reactorList(labelText, entries) {
    const block = document.createElement('div');
    block.className = 'reactor-list';
    const label = document.createElement('div');
    label.className = 'label';
    label.textContent = labelText;
    block.appendChild(label);
    if (!entries || !entries.length) {
      const empty = document.createElement('div');
      empty.className = 'reactor-note';
      empty.textContent = 'No events yet';
      block.appendChild(empty);
      return block;
    }
    entries.forEach((entry) => {
      const pill = document.createElement('div');
      pill.className = 'reactor-pill';
      pill.textContent = entry;
      block.appendChild(pill);
    });
    return block;
  }

  function layoutBoard() {
    const columns = sortedColumns();
    const laneWidth = 290;
    const rowGap = 152;
    const marginX = 120;
    const marginY = 120;
    const basePositions = {};
    const maxCards = Math.max(1, ...columns.map((column) => state.items.filter((item) => item.status === column.id).length));
    const width = Math.max(1200, marginX * 2 + columns.length * laneWidth + 120);
    const height = Math.max(720, marginY * 2 + maxCards * rowGap + 220);

    columns.forEach((column, columnIndex) => {
      const cards = state.items.filter((item) => item.status === column.id);
      cards.forEach((item, rowIndex) => {
        basePositions[item.id] = {
          x: marginX + columnIndex * laneWidth + 140,
          y: marginY + rowIndex * rowGap + 110,
          width: 190,
          height: 92,
          column,
        };
      });
    });

    const graph = buildSpatialGraph(columns, basePositions, width, height);
    return { columns, basePositions, positions: graph.positions, overlayPositions: graph.overlayPositions, edges: graph.edges, width, height, laneWidth, marginX, marginY };
  }

  function buildSpatialGraph(columns, basePositions, width, height) {
    const cardNodes = state.items.map((item) => ({
      id: item.id,
      type: 'card',
      item,
      x: basePositions[item.id] ? basePositions[item.id].x : width / 2,
      y: basePositions[item.id] ? basePositions[item.id].y : height / 2,
      vx: 0,
      vy: 0,
      laneX: basePositions[item.id] ? basePositions[item.id].x : width / 2,
      laneY: basePositions[item.id] ? basePositions[item.id].y : height / 2,
    }));
    const nodesById = Object.fromEntries(cardNodes.map((node) => [node.id, node]));
    const selected = itemById(state.selectedItemId);
    const selectedNode = selected ? nodesById[selected.id] : null;
    const edges = [];
    const overlayNodes = [];

    cardNodes.forEach((node, index) => {
      const columnCards = state.items.filter((item) => item.status === node.item.status);
      const row = columnCards.findIndex((item) => item.id === node.id);
      const previous = row > 0 ? columnCards[row - 1] : null;
      if (previous) {
        edges.push({ from: previous.id, to: node.id, kind: 'lane' });
      }
      if (index > 0) {
        edges.push({ from: cardNodes[index - 1].id, to: node.id, kind: 'flow' });
      }
    });

    if (selectedNode) {
      const selectedChecklist = selected.checklist.length
        ? selected.checklist
        : [{ id: selected.id + '-detail', text: previewText(selected.notes || 'Add detail to open the fractal view.'), checked: false }];
      selectedChecklist.slice(0, 7).forEach((detail, index) => {
        const angle = (-Math.PI / 2) + (index * ((Math.PI * 1.8) / Math.max(1, selectedChecklist.length)));
        const radius = 150 + (index % 2) * 26;
        const overlayId = 'overlay-' + detail.id;
        overlayNodes.push({
          id: overlayId,
          parentId: selected.id,
          kind: 'dag-child',
          label: detail.text || 'detail',
          checked: !!detail.checked,
          x: selectedNode.x + Math.cos(angle) * radius,
          y: selectedNode.y + Math.sin(angle) * radius,
        });
        edges.push({ from: selected.id, to: overlayId, kind: 'dag-child' });
      });

      const related = state.items
        .filter((item) => item.id !== selected.id)
        .filter((item) => item.status === selected.status || item.priority === selected.priority)
        .slice(0, 4);
      related.forEach((item) => {
        edges.push({ from: selected.id, to: item.id, kind: 'facet' });
      });
    }

    for (let step = 0; step < 42; step += 1) {
      cardNodes.forEach((node) => {
        const anchorPullX = (node.laneX - node.x) * 0.05;
        const anchorPullY = (node.laneY - node.y) * 0.045;
        node.vx += anchorPullX;
        node.vy += anchorPullY;
      });

      for (let i = 0; i < cardNodes.length; i += 1) {
        for (let j = i + 1; j < cardNodes.length; j += 1) {
          const a = cardNodes[i];
          const b = cardNodes[j];
          const dx = b.x - a.x;
          const dy = b.y - a.y;
          const distSq = Math.max(2200, dx * dx + dy * dy);
          const dist = Math.sqrt(distSq);
          const force = 3400 / distSq;
          const rx = (dx / dist) * force;
          const ry = (dy / dist) * force;
          a.vx -= rx;
          a.vy -= ry;
          b.vx += rx;
          b.vy += ry;
        }
      }

      edges.forEach((edge) => {
        const from = nodesById[edge.from];
        const to = nodesById[edge.to];
        if (!from || !to) return;
        const dx = to.x - from.x;
        const dy = to.y - from.y;
        const dist = Math.max(1, Math.sqrt(dx * dx + dy * dy));
        const target = edge.kind === 'facet' ? 235 : 170;
        const tension = edge.kind === 'facet' ? 0.012 : 0.022;
        const delta = (dist - target) * tension;
        const fx = (dx / dist) * delta;
        const fy = (dy / dist) * delta;
        from.vx += fx;
        from.vy += fy;
        to.vx -= fx;
        to.vy -= fy;
      });

      cardNodes.forEach((node) => {
        node.vx *= 0.72;
        node.vy *= 0.72;
        node.x = clamp(node.x + node.vx, 100, width - 100);
        node.y = clamp(node.y + node.vy, 96, height - 96);
      });
    }

    const positions = {};
    cardNodes.forEach((node) => {
      positions[node.id] = {
        x: node.x,
        y: node.y,
        width: 190,
        height: 92,
        column: basePositions[node.id] ? basePositions[node.id].column : columns[0],
      };
    });

    const overlayPositions = {};
    overlayNodes.forEach((node, index) => {
      const parent = positions[node.parentId] || { x: width / 2, y: height / 2 };
      const angle = (-Math.PI / 2) + index * 0.8;
      const radius = 150 + (index % 3) * 18;
      overlayPositions[node.id] = {
        x: parent.x + Math.cos(angle) * radius,
        y: parent.y + Math.sin(angle) * radius,
        r: 30,
        label: node.label,
        checked: node.checked,
        parentId: node.parentId,
      };
    });

    return { positions, overlayPositions, edges };
  }

  function renderSpatial() {
    const layout = layoutBoard();
    clampCamera(layout);
    const viewWidth = layout.width / state.spatial.zoom;
    const viewHeight = layout.height / state.spatial.zoom;
    spatialRoot.setAttribute('viewBox', state.spatial.offsetX + ' ' + state.spatial.offsetY + ' ' + viewWidth + ' ' + viewHeight);
    spatialRoot.innerHTML = '';
    zoomSlider.value = String(state.spatial.zoom);
    zoomLabel.textContent = spatialDepthLabel(state.spatial.zoom) + ' · drag to pan · wheel to zoom';

    for (let y = 0; y <= layout.height; y += 80) {
      spatialRoot.appendChild(svgElement('line', {
        x1: 0, y1: y, x2: layout.width, y2: y,
        stroke: 'rgba(125,207,255,0.08)', 'stroke-width': 1,
      }));
    }

    layout.columns.forEach((column, index) => {
      const x = layout.marginX + index * layout.laneWidth;
      spatialRoot.appendChild(svgElement('rect', {
        x, y: 70, width: 230, height: layout.height - 140, rx: 28,
        fill: 'rgba(17,24,36,0.78)', stroke: 'rgba(122,162,247,0.22)', 'stroke-width': 2,
      }));
      spatialRoot.appendChild(svgElement('text', {
        x: x + 18, y: 108, fill: '#dbe7f3', 'font-size': 20, 'font-weight': 700,
      }, column.name));
    });

    layout.edges.forEach((edge) => {
      const from = layout.positions[edge.from] || layout.overlayPositions[edge.from];
      const to = layout.positions[edge.to] || layout.overlayPositions[edge.to];
      if (!from || !to) return;
      spatialRoot.appendChild(svgElement('line', {
        x1: from.x,
        y1: from.y,
        x2: to.x,
        y2: to.y,
        stroke: edge.kind === 'facet' ? 'rgba(224,175,104,0.26)' : 'rgba(125,207,255,0.22)',
        'stroke-width': edge.kind === 'facet' ? 2.5 : 2,
        'stroke-dasharray': edge.kind === 'facet' ? '8 8' : '0',
      }));
    });

    state.items.forEach((item) => {
      const pos = layout.positions[item.id];
      if (!pos) return;
      const isSelected = item.id === state.selectedItemId;
      const fill = isSelected ? 'rgba(122,162,247,0.26)' : 'rgba(15,21,29,0.94)';
      const stroke = isSelected ? '#7aa2f7' : 'rgba(125,207,255,0.28)';
      spatialRoot.appendChild(svgElement('rect', {
        x: pos.x - pos.width / 2,
        y: pos.y - pos.height / 2,
        width: pos.width,
        height: pos.height,
        rx: 20,
        fill,
        stroke,
        'stroke-width': isSelected ? 3 : 2,
      }));
      spatialRoot.appendChild(svgElement('text', {
        x: pos.x,
        y: pos.y - 8,
        fill: '#dbe7f3',
        'font-size': state.spatial.zoom > 1.35 ? 18 : 15,
        'font-weight': 700,
        'text-anchor': 'middle',
      }, item.title || 'Untitled'));
      spatialRoot.appendChild(svgElement('text', {
        x: pos.x,
        y: pos.y + 18,
        fill: '#7e8da0',
        'font-size': 12,
        'text-anchor': 'middle',
      }, columnName(item.status) + ' · ' + item.priority));
    });

    Object.values(layout.overlayPositions).forEach((overlay) => {
      if (state.spatial.zoom < 1.45) return;
      spatialRoot.appendChild(svgElement('circle', {
        cx: overlay.x,
        cy: overlay.y,
        r: overlay.r,
        fill: overlay.checked ? 'rgba(158,206,106,0.28)' : 'rgba(224,175,104,0.22)',
        stroke: overlay.checked ? '#9ece6a' : '#e0af68',
        'stroke-width': 2,
      }));
      spatialRoot.appendChild(svgElement('text', {
        x: overlay.x,
        y: overlay.y + 4,
        fill: '#dbe7f3',
        'font-size': 10,
        'text-anchor': 'middle',
      }, trimText(overlay.label || 'detail', 16)));
    });
  }

  function bindSpatialGestures() {
    spatialShell.addEventListener('wheel', (event) => {
      event.preventDefault();
      const next = clamp((state.spatial.zoom || 0.82) * (event.deltaY < 0 ? 1.08 : 0.92), 0.55, 2.8);
      state.spatial.zoom = next;
      state.spatial.focusMode = 'manual';
      renderSpatial();
      saveState();
    }, { passive: false });

    spatialShell.addEventListener('pointerdown', (event) => {
      spatialShell.classList.add('dragging');
      dragCamera = {
        x: event.clientX,
        y: event.clientY,
        offsetX: state.spatial.offsetX,
        offsetY: state.spatial.offsetY,
      };
      spatialShell.setPointerCapture(event.pointerId);
    });

    spatialShell.addEventListener('pointermove', (event) => {
      if (!dragCamera) return;
      const zoom = state.spatial.zoom || 0.82;
      state.spatial.offsetX = dragCamera.offsetX - (event.clientX - dragCamera.x) / zoom;
      state.spatial.offsetY = dragCamera.offsetY - (event.clientY - dragCamera.y) / zoom;
      state.spatial.focusMode = 'manual';
      renderSpatial();
    });

    const release = (event) => {
      if (!dragCamera) return;
      dragCamera = null;
      spatialShell.classList.remove('dragging');
      if (event && spatialShell.hasPointerCapture(event.pointerId)) {
        spatialShell.releasePointerCapture(event.pointerId);
      }
      saveState();
    };

    spatialShell.addEventListener('pointerup', release);
    spatialShell.addEventListener('pointercancel', release);
    spatialShell.addEventListener('pointerleave', () => {
      if (!dragCamera) spatialShell.classList.remove('dragging');
    });
  }

  function focusSelected() {
    const selected = itemById(state.selectedItemId);
    if (!selected) return;
    const layout = layoutBoard();
    const pos = layout.positions[selected.id];
    if (!pos) return;
    const viewWidth = layout.width / state.spatial.zoom;
    const viewHeight = layout.height / state.spatial.zoom;
    state.spatial.offsetX = pos.x - viewWidth / 2;
    state.spatial.offsetY = pos.y - viewHeight / 2;
    clampCamera(layout);
  }

  function clampCamera(layout) {
    const viewWidth = layout.width / (state.spatial.zoom || 0.82);
    const viewHeight = layout.height / (state.spatial.zoom || 0.82);
    const maxX = Math.max(0, layout.width - viewWidth);
    const maxY = Math.max(0, layout.height - viewHeight);
    state.spatial.offsetX = clamp(state.spatial.offsetX || 0, 0, maxX);
    state.spatial.offsetY = clamp(state.spatial.offsetY || 0, 0, maxY);
  }

  function svgElement(tag, attrs, text) {
    const node = document.createElementNS(SVG_NS, tag);
    Object.entries(attrs || {}).forEach(([key, value]) => node.setAttribute(key, String(value)));
    if (text) node.textContent = text;
    return node;
  }

  function render() {
    ensureSelection();
    saveState();
    renderUseCases();
    renderNav();
    renderEditor();
    renderReactor();
    renderSpatial();
    renderBoard();
  }

  function selectField(labelText, options, selectedValue, onChange) {
    const field = document.createElement('div');
    field.className = 'field';
    const label = document.createElement('label');
    label.textContent = labelText;
    const select = document.createElement('select');
    options.forEach((option) => {
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
    const column = state.columns.find((entry) => entry.id === columnId);
    return column ? column.name : columnId;
  }

  function previewText(text) {
    if (!text) return '';
    return text.length > 120 ? text.slice(0, 117) + '...' : text;
  }

  function checklistSummary(item) {
    if (!item.checklist.length) return '0 checklist';
    const done = item.checklist.filter((check) => check.checked).length;
    return done + '/' + item.checklist.length + ' checklist';
  }

  function autoGrow(textarea) {
    textarea.style.height = 'auto';
    textarea.style.height = Math.max(textarea.scrollHeight, 32) + 'px';
  }

  function formatTimestamp(value) {
    if (!value) return 'not yet';
    try {
      return new Date(value).toLocaleTimeString();
    } catch (_) {
      return String(value);
    }
  }

  function formatReactorEvent(entry) {
    if (!entry) return 'event';
    const label = entry.label ? ' · ' + entry.label : '';
    return '[' + (entry.source || 'forge') + '] ' + entry.kind + label + ' @ ' + formatTimestamp(entry.timestampMs);
  }

  function spatialDepthLabel(zoom) {
    if (zoom < 0.8) return 'workspace shell';
    if (zoom < 1.25) return 'lane geometry';
    if (zoom < 1.85) return 'card topology';
    return 'fractal checklist detail';
  }

  function trimText(text, limit) {
    if (!text) return '';
    return text.length > limit ? text.slice(0, limit - 1) + '…' : text;
  }

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
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
