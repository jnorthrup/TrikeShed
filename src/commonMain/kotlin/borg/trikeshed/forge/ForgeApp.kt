package borg.trikeshed.forge

import borg.trikeshed.common.Files
import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.cursor.provenance
import borg.trikeshed.dag.causalGraphNode
import borg.trikeshed.forge.gallery.ForgeGalleryCatalog
import borg.trikeshed.forge.gallery.ForgeGalleryRenderer
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.lib.j
import borg.trikeshed.lib.linkedMapOf
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ForgeApp — single file server-side renderable workspace shell.
 * Static assets consolidated to src/commonMain/resources/web/; server-side render in commonMain.
 * PWA offline-first: Forge captures projects server-free; server/mesh additive sync.
 */
object ForgeApp {

    data class ForgeAppState(
        val title: String,
        val userId: String,
        val items: List<ForgeItem>,
        val workspace: WorkspaceLayout,
        val causalGraph: List<CausalNode>,
        val cascadeGrid: List<CascadeGridRow>,
    ) {
        fun toJsonValue(): JsonObject = JsonObject(mapOf(
            "title" to JsonPrimitive(title),
            "userId" to JsonPrimitive(userId),
            "items" to kotlinx.serialization.json.JsonArray(items.map { it.toJsonValue() }),
            "workspace" to workspace.toJsonValue(),
            "causalGraph" to kotlinx.serialization.json.JsonArray(causalGraph.map { it.toJsonValue() }),
            "cascadeGrid" to kotlinx.serialization.json.JsonArray(cascadeGrid.map { it.toJsonValue() }),
        ))
    }

    data class ForgeItem(
        val id: String,
        val title: String,
        val description: String,
        val status: String,
        val column: String,
        val causalKey: String,
        val lcncKind: String,
    ) {
        fun toJsonValue(): JsonObject = JsonObject(mapOf(
            "id" to JsonPrimitive(id),
            "title" to JsonPrimitive(title),
            "description" to JsonPrimitive(description),
            "status" to JsonPrimitive(status),
            "column" to JsonPrimitive(column),
            "causalKey" to JsonPrimitive(causalKey),
            "lcncKind" to JsonPrimitive(lcncKind),
        ))
    }

    data class WorkspaceLayout(
        val columns: List<ColumnLayout>,
    ) {
        fun toJsonValue(): JsonObject = JsonObject(mapOf(
            "columns" to kotlinx.serialization.json.JsonArray(columns.map { it.toJsonValue() }),
        ))
    }

    data class ColumnLayout(
        val id: String,
        val title: String,
        val order: Int,
    ) {
        fun toJsonValue(): JsonObject = JsonObject(mapOf(
            "id" to JsonPrimitive(id),
            "title" to JsonPrimitive(title),
            "order" to JsonPrimitive(order),
        ))
    }

    data class CausalNode(
        val id: String,
        val opId: String,
        val opVersion: String,
        val parentNodeIds: List<String>,
        val inputFingerprint: String,
        val causalKey: String,
        val topoOrdinal: Int,
    ) {
        fun toJsonValue(): JsonObject = JsonObject(mapOf(
            "id" to JsonPrimitive(id),
            "opId" to JsonPrimitive(opId),
            "opVersion" to JsonPrimitive(opVersion),
            "parentNodeIds" to kotlinx.serialization.json.JsonArray(parentNodeIds.map { JsonPrimitive(it) }),
            "inputFingerprint" to JsonPrimitive(inputFingerprint),
            "causalKey" to JsonPrimitive(causalKey),
            "topoOrdinal" to JsonPrimitive(topoOrdinal),
        ))
    }

    data class CascadeGridRow(
        val viewName: String,
        val metric: String,
        val sum: Double,
        val avg: Double,
        val min: Double,
        val max: Double,
        val count: Long,
    ) {
        fun toJsonValue(): JsonObject = JsonObject(mapOf(
            "viewName" to JsonPrimitive(viewName),
            "metric" to JsonPrimitive(metric),
            "sum" to JsonPrimitive(sum),
            "avg" to JsonPrimitive(avg),
            "min" to JsonPrimitive(min),
            "max" to JsonPrimitive(max),
            "count" to JsonPrimitive(count),
        ))
    }

    fun defaultForgeAppState(): ForgeAppState {
        val reduction = ForgeKanbanIngest.reduce(ForgeBoardPersistence.load("jim").getOrThrow())
        return ForgeAppState(
            title = reduction.source.title,
            userId = reduction.source.userId,
            items = reduction.board.cards.map { card ->
                ForgeItem(
                    id = card.id.value,
                    title = card.title,
                    description = card.description,
                    status = card.columnId.value,
                    column = card.columnId.value,
                    causalKey = reduction.correlations.first { it.taskId == card.id.value }.causalKey,
                    lcncKind = "task",
                )
            },
            workspace = WorkspaceLayout(
                columns = reduction.board.columns.map { col ->
                    ColumnLayout(id = col.id.value, title = col.name, order = col.order)
                },
            ),
            causalGraph = reduction.causalNodes.map { node ->
                CausalNode(
                    id = node.nodeId,
                    opId = node.opId,
                    opVersion = node.opVersion,
                    parentNodeIds = node.parentNodeIds,
                    inputFingerprint = node.inputFingerprint,
                    causalKey = node.causalKey,
                    topoOrdinal = node.topoOrdinal,
                )
            },
            cascadeGrid = listOf(
                CascadeGridRow("work-packages", "duration", 120.0, 12.0, 1.0, 40.0, 10),
                CascadeGridRow("work-packages", "effort", 500.0, 50.0, 5.0, 200.0, 10),
            ),
        )
    }

    private fun htmlEscape(s: String): String = s
        .replace("&", "&")
        .replace("<", "<")
        .replace(">", ">")
        .replace("\"", """)
        .replace("'", "'")

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
  <div id="blackboard" class="blackboard">
    <div id="blackboard-canvas" class="blackboard-canvas">
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
    </div>
    <div class="bb-hud">
      <div class="bb-hud-corner bb-hud-tl"><button class="bb-btn bb-hud-btn" id="hud-reset" title="Reset view (0)">⌂</button></div>
      <div class="bb-hud-corner bb-hud-tr"><button class="bb-btn bb-hud-btn" id="hud-depth" title="Cycle depth (d)">◈</button></div>
      <div class="bb-hud-corner bb-hud-bl"><button class="bb-btn bb-hud-btn" id="hud-fit" title="Fit all (f)">⤢</button></div>
      <div class="bb-hud-corner bb-hud-br"><button class="bb-btn bb-hud-btn" id="hud-center" title="Center (c)">◎</button></div>
      <div class="bb-hud-title">
        <span id="hud-title-left">Forge</span>
        <span id="hud-zoom-pill" class="bb-zoom-pill">1.0×</span>
        <span id="hud-title-right">Blackboard</span>
      </div>
    </div>
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

    private fun forgeAppStyles(): String = borg.trikeshed.forge.generated.ForgeAssets.stylesCss

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
}