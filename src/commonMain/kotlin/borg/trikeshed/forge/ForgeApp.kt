package borg.trikeshed.forge

import borg.trikeshed.common.Files
import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.cursor.provenance
import borg.trikeshed.forge.blackboard.ForgeBlackboardSection3D
import borg.trikeshed.forge.blackboard.ForgeBlackboardView
import borg.trikeshed.forge.gallery.ForgeGalleryCatalog
import borg.trikeshed.forge.gallery.ForgeGalleryRenderer
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.lcnc.reactor.IngestCodec
import borg.trikeshed.lcnc.reactor.IngestFormat
import borg.trikeshed.lcnc.reactor.IngestSource
import borg.trikeshed.lcnc.reactor.LcncIngestPipeline
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ForgeApp — single file server-side renderable workspace shell.
 * Static assets consolidated to src/commonMain/resources/web/; server-side render in commonMain.
 * PWA offline-first: Forge captures projects server-free; server/mesh additive sync.
 */
object ForgeApp {

    data class ForgeItem(
        val id: String,
        val title: String,
        val notes: String,
        val status: String,
        val priority: String,
        val checklist: List<ForgeChecklistItem> = emptyList(),
    )

    data class ForgeChecklistItem(
        val id: String,
        val text: String,
        val checked: Boolean = false,
    )

    data class WorkspaceLayout(
        val columns: List<ForgeColumn>,
    )

    data class ForgeColumn(
        val id: String,
        val name: String,
        val order: Int,
    )

    data class CausalNode(
        val id: String,
        val title: String,
        val parents: List<String> = emptyList(),
        val children: List<String> = emptyList(),
    )

    data class CascadeGridRow(
        val viewName: String,
        val metric: String,
        val sum: Double = 0.0,
        val avg: Double = 0.0,
        val min: Double = 0.0,
        val max: Double = 0.0,
        val count: Long = 0L,
    )

    data class IngestJob(
        val id: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val status: String, // "pending" | "processing" | "done" | "error"
        val progress: Double = 0.0,
        val error: String? = null,
        val entitiesCreated: Int = 0,
    )

    data class ForgeAppState(
        val title: String,
        val userId: String,
        val items: List<ForgeItem>,
        val workspace: WorkspaceLayout,
        val causalGraph: List<CausalNode>,
        val cascadeGrid: List<CascadeGridRow>,
        val ingestJobs: List<IngestJob> = emptyList(),
    )

    fun defaultForgeAppState(userId: String, ingestJobs: List<IngestJob> = emptyList()): ForgeAppState {
        val markdownPath = "/tmp/hi"
        val reduction = if (Files.exists(markdownPath)) {
            ForgeKanbanIngest.persistMarkdown(userId, markdownPath)
        } else {
            ForgeKanbanIngest.fallbackReduction()
        }
        return ForgeAppState(
            title = "Forge Workspace",
            userId = userId,
            items = reduction.board.cards.map { card ->
                ForgeItem(
                    id = card.id.value,
                    title = card.title,
                    notes = card.description,
                    status = card.columnId.value,
                    priority = card.priority.name,
                )
            },
            workspace = WorkspaceLayout(
                columns = ForgeGalleryCatalog.widgets()
                    .groupBy { it.section }
                    .map { (section, widgets) ->
                        ForgeColumn(
                            id = section.name.lowercase().replace(" ", "-"),
                            name = section.name,
                            order = section.ordinal,
                        )
                    }
                    .sortedBy { it.order },
            ),
            causalGraph = reduction.causalNodes.map { node ->
                CausalNode(
                    id = node.causalKey,
                    title = node.nodeId,
                    parents = node.parentNodeIds,
                    children = emptyList(),
                )
            },
            cascadeGrid = emptyList(),
            ingestJobs = ingestJobs,
        )
    }

    /** Render the complete Forge HTML shell with seeded state for PWA offline-first hydration. */
    fun renderHtml(userId: String = "jim"): String {
        val state = defaultForgeAppState(userId)
        val seed = forgeSeedJson(state)
        return htmlShell(seed)
    }

    private fun forgeSeedJson(state: ForgeAppState): String {
        val json = JsonObject(
            mapOf(
                "title" to JsonPrimitive(state.title),
                "userId" to JsonPrimitive(state.userId),
                "items" to JsonArray(state.items.map { item ->
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(item.id),
                            "title" to JsonPrimitive(item.title),
                            "notes" to JsonPrimitive(item.notes),
                            "status" to JsonPrimitive(item.status),
                            "priority" to JsonPrimitive(item.priority),
                            "checklist" to JsonArray(item.checklist.map { c ->
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(c.id),
                                        "text" to JsonPrimitive(c.text),
                                        "checked" to JsonPrimitive(c.checked),
                                    )
                                )
                            }),
                        )
                    )
                }),
                "workspace" to JsonObject(
                    mapOf(
                        "columns" to JsonArray(state.workspace.columns.map { col ->
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(col.id),
                                    "name" to JsonPrimitive(col.name),
                                    "order" to JsonPrimitive(col.order),
                                )
                            )
                        }),
                    )
                ),
                "causalGraph" to JsonArray(state.causalGraph.map { node ->
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(node.id),
                            "title" to JsonPrimitive(node.title),
                            "parents" to JsonArray(node.parents.map { JsonPrimitive(it) }),
                            "children" to JsonArray(node.children.map { JsonPrimitive(it) }),
                        )
                    )
                }),
                "cascadeGrid" to JsonArray(state.cascadeGrid.map { row ->
                    JsonObject(
                        mapOf(
                            "viewName" to JsonPrimitive(row.viewName),
                            "metric" to JsonPrimitive(row.metric),
                            "sum" to JsonPrimitive(row.sum),
                            "avg" to JsonPrimitive(row.avg),
                            "min" to JsonPrimitive(row.min),
                            "max" to JsonPrimitive(row.max),
                            "count" to JsonPrimitive(row.count),
                        )
                    )
                }),
                "ingestJobs" to JsonArray(state.ingestJobs.map { job ->
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(job.id),
                            "fileName" to JsonPrimitive(job.fileName),
                            "fileSize" to JsonPrimitive(job.fileSize),
                            "mimeType" to JsonPrimitive(job.mimeType),
                            "status" to JsonPrimitive(job.status),
                            "progress" to JsonPrimitive(job.progress),
                            "error" to (job.error?.let { JsonPrimitive(it) } ?: JsonPrimitive("")),
                            "entitiesCreated" to JsonPrimitive(job.entitiesCreated),
                        )
                    )
                }),
                "blackboardSeed" to forgeBlackboardSeed(),
            )
        )
        return json.toString()
    }

    private fun htmlShell(seed: String): String = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Forge — Local-First Workspace</title>
  <link rel="manifest" href="/manifest.webmanifest">
  <meta name="theme-color" content="#090D13">
  <style>${forgeAppStyles()}</style>
</head>
<body>
  <div id="forge-root">
    <header class="forge-header">
      <div class="header-left">
        <svg class="forge-logo" viewBox="0 0 32 32" width="28" height="28"><path d="M4 28V4h24v24H4Zm2-2h20V6H6v20Z"/></svg>
        <span id="forge-title">FORGE</span>
      </div>
      <div class="header-center">
        <div class="search-box"><input type="text" placeholder="Search workspace…" id="forge-search"/></div>
      </div>
      <div class="header-right">
        <button class="btn" id="new-doc-btn">New Doc</button>
        <button class="btn" id="sync-btn">Sync</button>
      </div>
    </header>
    <div class="forge-body">
      <aside id="rail" class="rail">
        <div class="rail-section">
          <h3>Workspace</h3>
          <nav id="rail-nav"></nav>
        </div>
        <div class="rail-section">
          <h3>Gallery</h3>
          ${galleryHtml()}
        </div>
      </aside>
      <main id="canvas" class="canvas">
        <div id="blackboard-surface" class="blackboard-surface">
          <div class="hud-top">
            <span id="hud-title-left">Page</span>
            <span id="hud-title-center">Board</span>
            <span id="hud-title-right">Blackboard</span>
          </div>
        </div>
      </main>
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

    private fun forgeBlackboardSeed(): JsonObject {
        val view = ForgeBlackboardView.DEFAULT
        val cam = view.defaultCamera
        val cam3d = view.mode3D
        return JsonObject(
            mapOf(
                "surface" to JsonPrimitive(view.surface),
                "sections" to JsonArray(view.sections.map { JsonPrimitive(it) }),
                "defaultMode" to JsonPrimitive(view.defaultMode.name),
                "cornerButtons" to JsonArray(view.cornerButtons.map { btn ->
                    JsonObject(
                        mapOf(
                            "slot" to JsonPrimitive(btn.slot.name),
                            "id" to JsonPrimitive(btn.id),
                            "label" to JsonPrimitive(btn.label),
                            "hotkey" to JsonPrimitive(btn.hotkey),
                            "surface" to JsonPrimitive(btn.surface),
                        )
                    )
                }),
                "camera" to JsonObject(
                    mapOf(
                        "x" to JsonPrimitive(cam.x),
                        "y" to JsonPrimitive(cam.y),
                        "zoom" to JsonPrimitive(cam.zoom),
                        "tilt" to JsonPrimitive(cam.tilt),
                        "vx" to JsonPrimitive(cam.vx),
                        "vy" to JsonPrimitive(cam.vy),
                        "vz" to JsonPrimitive(cam.vz),
                        "minZoom" to JsonPrimitive(cam.minZoom),
                        "maxZoom" to JsonPrimitive(cam.maxZoom),
                    )
                ),
                "camera3D" to JsonObject(
                    mapOf(
                        "yawRadians" to JsonPrimitive(cam3d.yawRadians),
                        "pitchRadians" to JsonPrimitive(cam3d.pitchRadians),
                        "distance" to JsonPrimitive(cam3d.distance),
                        "focalLength" to JsonPrimitive(cam3d.focalLength),
                        "minDistance" to JsonPrimitive(cam3d.minDistance),
                        "maxDistance" to JsonPrimitive(cam3d.maxDistance),
                    )
                ),
                "layout3D" to JsonArray(view.layout3D.map { section ->
                    JsonObject(
                        mapOf(
                            "sectionId" to JsonPrimitive(section.sectionId),
                            "centerX" to JsonPrimitive(section.centerX),
                            "centerY" to JsonPrimitive(section.centerY),
                            "width" to JsonPrimitive(section.width),
                            "height" to JsonPrimitive(section.height),
                            "elevation" to JsonPrimitive(section.elevation),
                        )
                    )
                }),
            )
        )
    }
}