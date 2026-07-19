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
    )

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

    fun defaultForgeAppState(userId: String): ForgeAppState {
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
                    notes = card.body,
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
                    title = node.payload["title"]?.toString() ?? node.causalKey,
                    parents = node.deps.map { it.value },
                    children = emptyList(),
                )
            },
            cascadeGrid = emptyList(),
        )
    }

    /** Render the complete Forge HTML shell with seeded state for PWA offline-first hydration. */
    fun renderHtml(userId: String = "jim"): String {
        val state = defaultForgeAppState(userId)
        val seed = forgeSeedJson(state)
        return htmlShell(seed)
    }

    private fun forgeSeedJson(state: ForgeAppState): String {
        return JsonObject(
            mapOf(
                "title" to JsonPrimitive(state.title),
                "userId" to JsonPrimitive(state.userId),
                "items" to state.items.map { item ->
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(item.id),
                            "title" to JsonPrimitive(item.title),
                            "notes" to JsonPrimitive(item.notes),
                            "status" to JsonPrimitive(item.status),
                            "priority" to JsonPrimitive(item.priority),
                            "checklist" to item.checklist.map { c ->
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(c.id),
                                        "text" to JsonPrimitive(c.text),
                                        "checked" to JsonPrimitive(c.checked),
                                    )
                                )
                            },
                        )
                    )
                },
                "workspace" to JsonObject(
                    mapOf(
                        "columns" to state.workspace.columns.map { col ->
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(col.id),
                                    "name" to JsonPrimitive(col.name),
                                    "order" to JsonPrimitive(col.order),
                                )
                            )
                        },
                    )
                ),
                "causalGraph" to state.causalGraph.map { node ->
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(node.id),
                            "title" to JsonPrimitive(node.title),
                            "parents" to node.parents.map { JsonPrimitive(it) },
                            "children" to node.children.map { JsonPrimitive(it) },
                        )
                    )
                },
                "cascadeGrid" to state.cascadeGrid.map { row ->
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
                },
                "blackboardSeed" to forgeBlackboardSeed(),
            )
        ).toString()
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