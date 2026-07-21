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
import borg.trikeshed.forge.correlationToBlock
import borg.trikeshed.lcnc.reactor.IngestCodec
import borg.trikeshed.lcnc.reactor.IngestFormat
import borg.trikeshed.lcnc.reactor.IngestSource
import borg.trikeshed.lcnc.reactor.LcncIngestPipeline
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.datetime.Clock
import borg.trikeshed.parse.confix.ConfixArray
import borg.trikeshed.parse.confix.ConfixObject
import borg.trikeshed.parse.confix.ConfixPrimitive

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
        val json = ConfixObject(
            mapOf(
                "title" to ConfixPrimitive(state.title),
                "userId" to ConfixPrimitive(state.userId),
                "items" to ConfixArray(state.items.map { item ->
                    ConfixObject(
                        mapOf(
                            "id" to ConfixPrimitive(item.id),
                            "title" to ConfixPrimitive(item.title),
                            "notes" to ConfixPrimitive(item.notes),
                            "status" to ConfixPrimitive(item.status),
                            "priority" to ConfixPrimitive(item.priority),
                            "checklist" to ConfixArray(item.checklist.map { c ->
                                ConfixObject(
                                    mapOf(
                                        "id" to ConfixPrimitive(c.id),
                                        "text" to ConfixPrimitive(c.text),
                                        "checked" to ConfixPrimitive(c.checked),
                                    )
                                )
                            }),
                        )
                    )
                }),
                "workspace" to ConfixObject(
                    mapOf(
                        "columns" to ConfixArray(state.workspace.columns.map { col ->
                            ConfixObject(
                                mapOf(
                                    "id" to ConfixPrimitive(col.id),
                                    "name" to ConfixPrimitive(col.name),
                                    "order" to ConfixPrimitive(col.order),
                                )
                            )
                        }),
                    )
                ),
                "causalGraph" to ConfixArray(state.causalGraph.map { node ->
                    ConfixObject(
                        mapOf(
                            "id" to ConfixPrimitive(node.id),
                            "title" to ConfixPrimitive(node.title),
                            "parents" to ConfixArray(node.parents.map { ConfixPrimitive(it) }),
                            "children" to ConfixArray(node.children.map { ConfixPrimitive(it) }),
                        )
                    )
                }),
                "cascadeGrid" to ConfixArray(state.cascadeGrid.map { row ->
                    ConfixObject(
                        mapOf(
                            "viewName" to ConfixPrimitive(row.viewName),
                            "metric" to ConfixPrimitive(row.metric),
                            "sum" to ConfixPrimitive(row.sum),
                            "avg" to ConfixPrimitive(row.avg),
                            "min" to ConfixPrimitive(row.min),
                            "max" to ConfixPrimitive(row.max),
                            "count" to ConfixPrimitive(row.count),
                        )
                    )
                }),
                "ingestJobs" to ConfixArray(state.ingestJobs.map { job ->
                    ConfixObject(
                        mapOf(
                            "id" to ConfixPrimitive(job.id),
                            "fileName" to ConfixPrimitive(job.fileName),
                            "fileSize" to ConfixPrimitive(job.fileSize),
                            "mimeType" to ConfixPrimitive(job.mimeType),
                            "status" to ConfixPrimitive(job.status),
                            "progress" to ConfixPrimitive(job.progress),
                            "error" to (job.error?.let { ConfixPrimitive(it) } ?: ConfixPrimitive("")),
                            "entitiesCreated" to ConfixPrimitive(job.entitiesCreated),
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

    private fun forgeBlackboardSeed(): ConfixObject {
        val view = ForgeBlackboardView.DEFAULT
        val cam = view.defaultCamera
        val cam3d = view.mode3D
        return ConfixObject(
            mapOf(
                "surface" to ConfixPrimitive(view.surface),
                "sections" to ConfixArray(view.sections.map { ConfixPrimitive(it) }),
                "defaultMode" to ConfixPrimitive(view.defaultMode.name),
                "cornerButtons" to ConfixArray(view.cornerButtons.map { btn ->
                    ConfixObject(
                        mapOf(
                            "slot" to ConfixPrimitive(btn.slot.name),
                            "id" to ConfixPrimitive(btn.id),
                            "label" to ConfixPrimitive(btn.label),
                            "hotkey" to ConfixPrimitive(btn.hotkey),
                            "surface" to ConfixPrimitive(btn.surface),
                        )
                    )
                }),
                "camera" to ConfixObject(
                    mapOf(
                        "x" to ConfixPrimitive(cam.x),
                        "y" to ConfixPrimitive(cam.y),
                        "zoom" to ConfixPrimitive(cam.zoom),
                        "tilt" to ConfixPrimitive(cam.tilt),
                        "vx" to ConfixPrimitive(cam.vx),
                        "vy" to ConfixPrimitive(cam.vy),
                        "vz" to ConfixPrimitive(cam.vz),
                        "minZoom" to ConfixPrimitive(cam.minZoom),
                        "maxZoom" to ConfixPrimitive(cam.maxZoom),
                    )
                ),
                "camera3D" to ConfixObject(
                    mapOf(
                        "yawRadians" to ConfixPrimitive(cam3d.yawRadians),
                        "pitchRadians" to ConfixPrimitive(cam3d.pitchRadians),
                        "distance" to ConfixPrimitive(cam3d.distance),
                        "focalLength" to ConfixPrimitive(cam3d.focalLength),
                        "minDistance" to ConfixPrimitive(cam3d.minDistance),
                        "maxDistance" to ConfixPrimitive(cam3d.maxDistance),
                    )
                ),
                "layout3D" to ConfixArray(view.layout3D.map { section ->
                    ConfixObject(
                        mapOf(
                            "sectionId" to ConfixPrimitive(section.sectionId),
                            "centerX" to ConfixPrimitive(section.centerX),
                            "centerY" to ConfixPrimitive(section.centerY),
                            "width" to ConfixPrimitive(section.width),
                            "height" to ConfixPrimitive(section.height),
                            "elevation" to ConfixPrimitive(section.elevation),
                        )
                    )
                }),
            )
        )
}
