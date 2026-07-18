package borg.trikeshed.forge

import borg.trikeshed.forge.blackboard.ForgeBlackboardView
import borg.trikeshed.forge.gallery.ForgeGalleryCatalog
import borg.trikeshed.forge.gallery.ForgeGalleryRenderer
import borg.trikeshed.graph.CausalGraphNodeDTO
import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.reactor.KanbanFSM
import kotlinx.serialization.Serializable

@Serializable
data class LcncEntityDTO(
    val entityId: String,
    val lcncKind: String,
    val lane: String,
    val facet: String,
    val causalKey: String? = null,
    val title: String,
    val description: String = "",
)

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
    val causalNodes: List<CausalGraphNodeDTO> = emptyList(),
    val lcncEntities: List<LcncEntityDTO> = emptyList(),
    val blackboardId: String = "",
    val cascadeGrid: List<CascadeRollupRow> = emptyList(),
)

@Serializable
data class CascadeRollupRow(
    val viewName: String,
    val metric: String,
    val sum: Double = 0.0,
    val avg: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val count: Long = 0L,
)

/** Build the cascade grid by running sample reading docs through the view-server tool. */
private fun defaultCascadeGrid(): List<CascadeRollupRow> {
    val server = borg.trikeshed.viewserver.CommonViewServer()
    val views = listOf("byOrganization", "byMachine", "byBillingGroup")
    val docs = listOf(
        mapOf(
            "organization_id" to borg.trikeshed.viewserver.ViewValue.Text("org-1"),
            "machine_id" to borg.trikeshed.viewserver.ViewValue.Text("machine-1"),
            "billing_group_id" to borg.trikeshed.viewserver.ViewValue.Text("billing-1"),
            "reading_date" to borg.trikeshed.viewserver.ViewValue.Text("2026-07-15T12:34:00Z"),
            "cpu_mhz" to borg.trikeshed.viewserver.ViewValue.Number(100.0),
            "memory_mib" to borg.trikeshed.viewserver.ViewValue.Number(200.0),
        ),
        mapOf(
            "organization_id" to borg.trikeshed.viewserver.ViewValue.Text("org-1"),
            "machine_id" to borg.trikeshed.viewserver.ViewValue.Text("machine-1"),
            "billing_group_id" to borg.trikeshed.viewserver.ViewValue.Text("billing-1"),
            "reading_date" to borg.trikeshed.viewserver.ViewValue.Text("2026-07-15T12:35:00Z"),
            "cpu_mhz" to borg.trikeshed.viewserver.ViewValue.Number(300.0),
            "memory_mib" to borg.trikeshed.viewserver.ViewValue.Number(400.0),
        ),
    )
    val docValues = docs.map { borg.trikeshed.viewserver.ViewValue.ObjectValue(it) }
    val rows = mutableListOf<CascadeRollupRow>()
    for (view in views) {
        server.reset()
        server.addFunction("tool:couchdbcascade/$view")
        val rollup = server.reduce("tool:couchdbcascade", docValues)
        val metricsObj = (rollup as borg.trikeshed.viewserver.ViewValue.ArrayValue).values[0]
            as borg.trikeshed.viewserver.ViewValue.ObjectValue
        val count = (rollup.values[1] as borg.trikeshed.viewserver.ViewValue.Number).value.toLong()
        for ((metric, stats) in metricsObj.fields) {
            val s = stats as borg.trikeshed.viewserver.ViewValue.ObjectValue
            rows += CascadeRollupRow(
                viewName = view,
                metric = metric,
                sum = (s.fields["sum"] as borg.trikeshed.viewserver.ViewValue.Number).value,
                avg = (s.fields["avg"] as borg.trikeshed.viewserver.ViewValue.Number).value,
                min = (s.fields["min"] as borg.trikeshed.viewserver.ViewValue.Number).value,
                max = (s.fields["max"] as borg.trikeshed.viewserver.ViewValue.Number).value,
                count = count,
            )
        }
    }
    return rows
}

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
    val userId = "jim"
    val reduction = try {
        ForgeKanbanIngest.load(userId)
    } catch (e: Throwable) {
        // Browser or first-run fallback — build a minimal seed entirely in
        // memory without touching disk (Files.write would call require('fs')
        // in the browser and crash).
        try {
            ForgeKanbanIngest.persistMarkdown(userId, "/tmp/hi")
        } catch (_: Throwable) {
            ForgeKanbanIngest.fallbackReduction()
        }
    }
    val board = reduction.board
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
            checklist = emptyList(),
        )
    }
    val reactorCore = KanbanFSM.current()
    val recentTaxonomy = if (reactorCore.recentTaxonomyNodes.isNotEmpty()) {
        reactorCore.recentTaxonomyNodes.takeLast(6)
    } else {
        items.takeLast(6).map { it.title }
    }
    val recentSignals = buildList {
        add("Rete:${reduction.reteFacts.count { it.fields["kind"] == "task" }} tasks")
        add("Rete:${reduction.reteFacts.count { it.fields["kind"] == "link" }} links")
        if (reactorCore.lastEventKind != "INIT") add("KanbanFSM:${reactorCore.lastEventKind}")
    }

    val cascadeGrid = defaultCascadeGrid()
    return ForgeAppState(
        title = board.name,
        pageNotes = reduction.source.description,
        columns = columns,
        items = items,
        selectedItemId = items.firstOrNull()?.id,
        useCases = defaultForgeUseCases(),
        reactor = ForgeAppReactorState(
            taxonomyNodeCount = maxOf(reactorCore.taxonomyNodeCount, items.size),
            signalFacetCount = reactorCore.cacheHits + reactorCore.cacheMisses + reactorCore.cacheStored + reactorCore.cacheEvicted,
            cacheStoredCount = reactorCore.cacheStored,
            lastEventKind = if (reactorCore.lastEventKind != "INIT") reactorCore.lastEventKind else "SourceReduced",
            lastEventTimestampMs = reactorCore.lastEventTimestampMs,
            recentTaxonomyNodes = recentTaxonomy,
            recentSignals = recentSignals,
        ),
        spatial = ForgeSpatialState(),
        causalNodes = reduction.causalNodes.map {
            CausalGraphNodeDTO(
                nodeId = it.nodeId,
                opId = it.opId,
                opVersion = it.opVersion,
                parentNodeIds = it.parentNodeIds,
                causalKey = it.causalKey,
                topoOrdinal = it.topoOrdinal,
                causalClock = it.causalClock,
            )
        },
        lcncEntities = reduction.correlations.map { correlation ->
            val card = board.cards.first { it.id.value == correlation.taskId }
            LcncEntityDTO(
                entityId = "task:${correlation.taskId}",
                lcncKind = "work-package",
                lane = card.columnId.value,
                facet = if (correlation.ready) "ready" else "dependency-gated",
                causalKey = correlation.causalKey,
                title = card.title,
                description = card.description,
            )
        },
        blackboardId = board.id.value,
        cascadeGrid = cascadeGrid,
    )
}

private fun ForgeAppState.toJsonValue(): Map<String, Any?> = linkedMapOf(
    "title" to title,
    "pageNotes" to pageNotes,
    "columns" to columns.map { linkedMapOf("id" to it.id, "name" to it.name, "order" to it.order) },
    "items" to items.map { item ->
        linkedMapOf(
            "id" to item.id,
            "title" to item.title,
            "notes" to item.notes,
            "status" to item.status,
            "priority" to item.priority,
            "checklist" to item.checklist.map {
                linkedMapOf("id" to it.id, "text" to it.text, "checked" to it.checked)
            },
        )
    },
    "selectedItemId" to selectedItemId,
    "useCases" to useCases.map {
        linkedMapOf(
            "id" to it.id,
            "name" to it.name,
            "summary" to it.summary,
            "pageNotes" to it.pageNotes,
            "itemTitles" to it.itemTitles,
        )
    },
    "reactor" to linkedMapOf(
        "taxonomyNodeCount" to reactor.taxonomyNodeCount,
        "signalFacetCount" to reactor.signalFacetCount,
        "cacheStoredCount" to reactor.cacheStoredCount,
        "lastEventKind" to reactor.lastEventKind,
        "lastEventTimestampMs" to reactor.lastEventTimestampMs,
        "recentTaxonomyNodes" to reactor.recentTaxonomyNodes,
        "recentSignals" to reactor.recentSignals,
    ),
    "spatial" to linkedMapOf(
        "zoom" to spatial.zoom,
        "offsetX" to spatial.offsetX,
        "offsetY" to spatial.offsetY,
        "focusMode" to spatial.focusMode,
    ),
    "causalNodes" to causalNodes.map {
        linkedMapOf(
            "nodeId" to it.nodeId,
            "opId" to it.opId,
            "opVersion" to it.opVersion,
            "parentNodeIds" to it.parentNodeIds,
            "causalKey" to it.causalKey,
            "topoOrdinal" to it.topoOrdinal,
            "causalClock" to it.causalClock,
        )
    },
    "lcncEntities" to lcncEntities.map {
        linkedMapOf(
            "entityId" to it.entityId,
            "lcncKind" to it.lcncKind,
            "lane" to it.lane,
            "facet" to it.facet,
            "causalKey" to it.causalKey,
            "title" to it.title,
            "description" to it.description,
        )
    },
    "blackboardId" to blackboardId,
    "cascadeGrid" to cascadeGrid.map {
        linkedMapOf(
            "viewName" to it.viewName,
            "metric" to it.metric,
            "sum" to it.sum,
            "avg" to it.avg,
            "min" to it.min,
            "max" to it.max,
            "count" to it.count,
        )
    },
)

fun forgeAppHtml(): String {
    val baseSeed = defaultForgeAppState().toJsonValue().toMutableMap()
    baseSeed["gallery"] = ForgeGalleryCatalog.toJsonValue()
    baseSeed["blackboard"] = forgeBlackboardSeed()
    val seed = htmlEscape(JsonSupport.stringify(baseSeed))
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
        <h1>Page, board, field</h1>
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
      <div class="panel section-block">
        <div class="section-head">
          <h2>Widget Gallery</h2>
          <p>Forge widget catalog with per-target support matrix.</p>
        </div>
        <div id="gallery-root" class="gallery-list">${galleryHtml()}</div>
      </div>
    </aside>
    <main class="editor">
      <div id="doc-root" class="page"></div>
    </main>
    <main class="graph-pane">
      <div class="panel section-block" style="height:100%; display:grid; grid-template-rows:auto 1fr auto;">
        <div class="section-head">
          <h2>RTS / Causal Graph</h2>
          <p>Force-directed causal graph — drag nodes, scroll to zoom, click to inspect</p>
        </div>
        <div class="graph-toolbar" style="display:flex; gap:8px; align-items:center; padding:8px 0;">
          <label class="zoom-stack" for="graph-zoom-slider" style="display:grid; gap:4px; color:var(--muted); font-size:11px; text-transform:uppercase; letter-spacing:.08em;">
            <span>Zoom</span>
            <input id="graph-zoom-slider" type="range" min="0.2" max="3" step="0.05" value="1" style="width:140px;" />
          </label>
          <button class="btn" id="btn-graph-fit">Fit</button>
          <button class="btn" id="btn-graph-center">Center</button>
          <button class="btn primary" id="btn-graph-seed">Seed Demo</button>
        </div>
        <div id="graph-spatial-shell" class="graph-spatial-shell" style="flex:1; position:relative; min-height:500px; border:1px solid var(--line2); border-radius:18px; background:radial-gradient(circle at top, rgba(122,162,247,.08), rgba(8,12,18,.96) 56%); overflow:hidden; cursor:grab;">
          <svg id="graph-spatial-root" class="graph-spatial-root" viewBox="0 0 1200 720" preserveAspectRatio="xMidYMid meet" style="width:100%; height:100%; display:block;"></svg>
        </div>
        <div class="graph-status" style="display:flex; gap:8px; flex-wrap:wrap; padding-top:12px;">
          <span class="status-chip"><span class="dot"></span>Force sim active</span>
          <span class="status-chip"><span class="dot"></span>Graph: <span id="graph-stat-nodes">0</span> nodes, <span id="graph-stat-links">0</span> links</span>
        </div>
      </div>
    </main>
    <aside class="board-pane">
      <div class="panel section-block" style="height:100%; display:grid; grid-template-rows:auto 1fr auto;">
        <div class="section-head">
          <h2>RTS / Causal Graph</h2>
          <p>Force-directed causal graph — drag nodes, scroll to zoom, click to inspect</p>
        </div>
        <div class="graph-toolbar" style="display:flex; gap:8px; align-items:center; padding:8px 0;">
          <label class="zoom-stack" for="graph-zoom-slider" style="display:grid; gap:4px; color:var(--muted); font-size:11px; text-transform:uppercase; letter-spacing:.08em;">
            <span>Zoom</span>
            <input id="graph-zoom-slider" type="range" min="0.2" max="3" step="0.05" value="1" style="width:140px;" />
          </label>
          <button class="btn" id="btn-graph-fit">Fit</button>
          <button class="btn" id="btn-graph-center">Center</button>
          <button class="btn primary" id="btn-graph-seed">Seed Demo</button>
        </div>
        <div id="graph-spatial-shell" class="graph-spatial-shell" style="flex:1; position:relative; min-height:500px; border:1px solid var(--line2); border-radius:18px; background:radial-gradient(circle at top, rgba(122,162,247,.08), rgba(8,12,18,.96) 56%); overflow:hidden; cursor:grab;">
          <svg id="graph-spatial-root" class="graph-spatial-root" viewBox="0 0 1200 720" preserveAspectRatio="xMidYMid meet" style="width:100%; height:100%; display:block;"></svg>
        </div>
        <div class="graph-status" style="display:flex; gap:8px; flex-wrap:wrap; padding-top:12px;">
          <span class="status-chip"><span class="dot"></span>Force sim active</span>
          <span class="status-chip"><span class="dot"></span>Graph: <span id="graph-stat-nodes">0</span> nodes, <span id="graph-stat-links">0</span> links</span>
        </div>
      </div>
    </main>
    <aside class="board-pane">
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
            <button class="btn" id="toggle-depth">2.5D depth</button>
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
      <div class="panel section-block">
        <div class="section-head">
          <h2>Cascade grid</h2>
          <p>CouchDB Cascade metric rollup via view-server tool.</p>
        </div>
        <div id="cascade-grid-root" class="cascade-grid"></div>
      </div>
    </aside>
  </div>
  <div id="reactor-root" class="status-strip"></div>
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
      grid-template-columns:260px minmax(320px, 0.8fr) 1.2fr minmax(400px, 1.3fr);
      background:linear-gradient(180deg, rgba(255,255,255,.015), rgba(255,255,255,0));
    }
    .rail { border-right:1px solid var(--line); padding:16px; display:grid; gap:14px; background:rgba(9,13,19,.88); }
    .editor { padding:18px; overflow:auto; background:linear-gradient(180deg, rgba(9,13,19,.52), rgba(9,13,19,.16)); border-right:1px solid var(--line); }
    .graph-pane { padding:18px; overflow:auto; background:linear-gradient(180deg, rgba(9,13,19,.52), rgba(9,13,19,.16)); }
    .board-pane { border-left:1px solid var(--line); padding:18px; display:grid; gap:14px; background:rgba(9,13,19,.94); overflow:auto; }
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
    .brand p, .section-head p, .status-note, .space-caption { color:var(--muted); font-size:12px; line-height:1.55; }
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
    .page { max-width:760px; margin:0 auto; }
    .page-head {
      display:grid;
      grid-template-columns:minmax(0, 1.1fr) minmax(240px, .9fr) auto;
      gap:12px;
      margin-bottom:14px;
      align-items:end;
    }
    .title-input, .item-title, .notes-input, .check-input, .page-notes { width:100%; border:none; outline:none; background:transparent; color:var(--ink); }
    .title-input { font-size:28px; font-weight:800; letter-spacing:-.03em; min-height:56px; }
    .page-notes, .notes-input, .check-input { resize:vertical; line-height:1.6; }
    .page-notes-wrap, .notes-wrap, .field select { border:1px solid var(--line2); border-radius:14px; background:rgba(8,12,18,.82); }
    .page-notes-wrap, .notes-wrap { padding:10px 12px; }
    .page-notes { min-height:56px; color:var(--muted); }
    .dialog-shell {
      display:grid;
      grid-template-columns:minmax(0, 1fr);
      gap:12px;
      align-items:start;
    }
    .dialog-card, .dialog-sidecard {
      border:1px solid var(--line);
      border-radius:20px;
      background:linear-gradient(180deg, rgba(17,24,36,.98), rgba(10,15,23,.96));
      box-shadow:var(--shadow);
    }
    .dialog-card { padding:14px; }
    .dialog-sidecard { padding:14px; display:grid; gap:12px; }
    .dialog-head {
      display:flex;
      justify-content:space-between;
      gap:10px;
      align-items:flex-start;
      margin-bottom:12px;
    }
    .dialog-title-stack { display:grid; gap:8px; min-width:0; }
    .dialog-kicker { color:var(--cyan); font-size:11px; font-weight:800; letter-spacing:.14em; text-transform:uppercase; }
    .dialog-title {
      width:100%;
      border:none;
      outline:none;
      background:transparent;
      color:var(--ink);
      font-size:22px;
      font-weight:780;
      letter-spacing:-.03em;
      padding:0;
    }
    .dialog-meta-line { color:var(--muted); font-size:12px; line-height:1.5; }
    .dialog-chip-row { display:flex; flex-wrap:wrap; gap:8px; }
    .dialog-chip {
      display:inline-flex;
      align-items:center;
      min-height:28px;
      padding:0 10px;
      border-radius:999px;
      border:1px solid rgba(122,162,247,.28);
      background:rgba(122,162,247,.12);
      color:var(--ink);
      font-size:12px;
    }
    .dialog-grid { display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:10px; margin-bottom:12px; }
    .dialog-field { display:grid; gap:8px; }
    .dialog-field label { font-size:11px; color:var(--muted); text-transform:uppercase; letter-spacing:.08em; }
    .dialog-field textarea, .dialog-field input, .dialog-field select {
      width:100%;
      border:1px solid var(--line2);
      border-radius:14px;
      background:rgba(8,12,18,.88);
      color:var(--ink);
      padding:12px 14px;
      outline:none;
    }
    .dialog-field textarea { min-height:96px; resize:vertical; line-height:1.55; }
    .dialog-actions { display:flex; flex-wrap:wrap; gap:8px; margin:4px 0 12px; }
    .dialog-section { display:grid; gap:10px; }
    .dialog-section-head { display:flex; justify-content:space-between; gap:10px; align-items:center; }
    .dialog-section-head h3, .dialog-sidecard h3 { margin:0; font-size:14px; }
    .dialog-hint { color:var(--muted); font-size:12px; line-height:1.5; }
    .dialog-summary-list { display:grid; gap:10px; }
    .dialog-summary {
      width:100%;
      text-align:left;
      border:1px solid var(--line2);
      border-radius:14px;
      background:rgba(8,12,18,.78);
      color:var(--ink);
      padding:12px;
      cursor:pointer;
    }
    .dialog-summary:hover { border-color:rgba(122,162,247,.45); }
    .dialog-summary.active { border-color:var(--blue); box-shadow:0 0 0 1px rgba(122,162,247,.22) inset; }
    .dialog-summary strong { display:block; font-size:13px; }
    .dialog-summary span { display:block; margin-top:6px; color:var(--muted); font-size:12px; line-height:1.45; white-space:pre-wrap; }
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
    .status-strip {
      border-top:1px solid var(--line);
      background:rgba(9,13,19,.96);
      padding:12px 16px 16px;
    }
    .status-row {
      display:flex;
      flex-wrap:wrap;
      gap:8px;
      align-items:center;
    }
    .status-chip {
      display:inline-flex;
      align-items:center;
      gap:8px;
      min-height:32px;
      padding:0 12px;
      border-radius:999px;
      border:1px solid var(--line2);
      background:rgba(8,12,18,.82);
      font-size:12px;
    }
    .status-chip .label { color:var(--muted); text-transform:uppercase; letter-spacing:.08em; font-size:10px; }
    .status-chip .value { color:var(--ink); font-weight:700; }
    .status-trail {
      margin-top:8px;
      display:flex;
      flex-wrap:wrap;
      gap:8px;
    }
    .status-pill {
      display:inline-flex;
      align-items:center;
      min-height:26px;
      padding:0 10px;
      border-radius:999px;
      border:1px solid rgba(122,162,247,.28);
      background:rgba(122,162,247,.12);
      font-size:11px;
      color:var(--ink);
    }
    .status-note { margin-top:8px; }
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
      min-height:540px;
      border:1px solid var(--line2);
      border-radius:18px;
      background:radial-gradient(circle at top, rgba(122,162,247,.08), rgba(8,12,18,.96) 56%);
      overflow:hidden;
      cursor:grab;
    }
    .spatial-shell.dragging { cursor:grabbing; }
    .spatial-root { width:100%; height:540px; display:block; }
    @media (max-width: 1380px) {
      .app-shell { grid-template-columns:250px minmax(0, 1fr); }
      .board-pane { grid-column:1 / -1; border-left:none; border-top:1px solid var(--line); }
    }
    @media (max-width: 900px) {
      .app-shell { grid-template-columns:1fr; }
      .rail { border-right:none; border-bottom:1px solid var(--line); }
      .editor { padding:16px; }
      .page-head, .dialog-grid { grid-template-columns:1fr; }
      .item-meta, .space-toolbar { grid-template-columns:1fr; }
      .board-pane { border-top:1px solid var(--line); }
    }
    .cascade-grid { display:grid; gap:6px; }
    .cascade-grid table { width:100%; border-collapse:collapse; font-size:12px; }
    .cascade-grid th { text-align:left; color:var(--muted); text-transform:uppercase; letter-spacing:.08em; font-size:10px; padding:6px 8px; border-bottom:1px solid var(--line2); }
    .cascade-grid td { padding:6px 8px; border-bottom:1px solid var(--line); color:var(--ink); }
    .cascade-grid td.num { text-align:right; font-variant-numeric:tabular-nums; }
""".trimIndent()

private fun forgeAppScript(): String = forgePersistenceScript()

/** Server-rendered gallery HTML for the workspace rail. No client-side hydration needed. */
private fun galleryHtml(): String = ForgeGalleryRenderer.renderHtml()

private fun forgeBlackboardSeed(): Map<String, Any?> {
    val view = ForgeBlackboardView.DEFAULT
    val cam = view.defaultCamera
    val cam3d = view.mode3D
    return linkedMapOf(
        "surface" to view.surface,
        "sections" to view.sections,
        "defaultMode" to view.defaultMode.name,
        "cornerButtons" to view.cornerButtons.map {
            linkedMapOf(
                "slot" to it.slot.name,
                "id" to it.id,
                "label" to it.label,
                "hotkey" to it.hotkey,
                "surface" to it.surface,
            )
        },
        "camera" to linkedMapOf(
            "x" to cam.x,
            "y" to cam.y,
            "zoom" to cam.zoom,
            "tilt" to cam.tilt,
            "vx" to cam.vx,
            "vy" to cam.vy,
            "vz" to cam.vz,
            "minZoom" to cam.minZoom,
            "maxZoom" to cam.maxZoom,
        ),
        "camera3D" to linkedMapOf(
            "yawRadians" to cam3d.yawRadians,
            "pitchRadians" to cam3d.pitchRadians,
            "distance" to cam3d.distance,
            "focalLength" to cam3d.focalLength,
            "minDistance" to cam3d.minDistance,
            "maxDistance" to cam3d.maxDistance,
        ),
        "layout3D" to view.layout3D.map {
            linkedMapOf(
                "sectionId" to it.sectionId,
                "centerX" to it.centerX,
                "centerY" to it.centerY,
                "width" to it.width,
                "height" to it.height,
                "elevation" to it.elevation,
            )
        },
    )
}

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
