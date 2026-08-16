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
import borg.trikeshed.kanban.ForgeKanbanReduction
import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.forge.correlationToBlock
import borg.trikeshed.lcnc.reactor.IngestCodec
import borg.trikeshed.lcnc.reactor.IngestFormat
import borg.trikeshed.lcnc.reactor.IngestSource
import borg.trikeshed.lcnc.reactor.LcncIngestPipeline
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.datetime.Clock
import borg.trikeshed.blackboard.BlackboardSurface
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.parse.json.JsonSupport

/**
 * ForgeApp — single file server-side renderable workspace shell.
 * Static assets consolidated to src/commonMain/resources/web/; server-side render in commonMain.
 * PWA offline-first: Forge captures projects server-free; server/mesh additive sync.
 */
object ForgeApp {

    /** Render the complete Forge HTML shell with seeded state for PWA offline-first hydration. */
    fun renderHtml(userId: String = "jim"): String {
        val reduction = runCatching { ForgeKanbanIngest.load(userId) }.getOrElse { ForgeKanbanIngest.fallbackReduction() }
        val seed = forgeSeedJson(userId, reduction)
        return htmlShell(seed)
    }

    private fun forgeSeedJson(userId: String, reduction: ForgeKanbanReduction): String {
        val seedMap = mapOf<String, Any?>(
            "userId" to userId,
            "source" to mapOf(
                "title" to reduction.source.title,
                "version" to reduction.source.version,
            ),
            "board" to mapOf(
                "columns" to reduction.board.columns.map { col ->
                    mapOf(
                        "id" to col.id.value,
                        "name" to col.name,
                        "order" to col.order,
                    )
                },
                "cards" to reduction.board.cards.map { card ->
                    mapOf(
                        "id" to card.id.value,
                        "title" to card.title,
                        "description" to card.description,
                        "columnId" to card.columnId.value,
                        "order" to card.order,
                        "priority" to card.priority.name,
                        "dependencies" to card.dependencies.map { it.value },
                    )
                },
            ),
            "causalGraph" to reduction.causalNodes.map { node ->
                mapOf(
                    "id" to node.nodeId,
                    "title" to (node.opId + ":" + node.opVersion),
                    "parents" to node.parentNodeIds,
                    "children" to emptyList<String>(),
                )
            },
            "correlations" to reduction.correlations.map { corr ->
                mapOf(
                    "taskId" to corr.taskId,
                    "parentIds" to corr.parentIds,
                    "childIds" to corr.childIds,
                    "ready" to corr.ready,
                    "causalKey" to corr.causalKey,
                )
            },
            "blackboardSeed" to forgeBlackboardSeed(),
            "dashboards" to forgeDashboardSeed(),
        )
        return JsonSupport.stringify(seedMap)
    }

    /**
     * Dashboard seed: launch-time native I/O capability + latest flywheel cycle
     * evidence. The server render is the authoritative launch-time snapshot.
     */
    private fun forgeDashboardSeed(): Map<String, Any?> {
        val report = runCatching {
            borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport()
        }.getOrElse {
            borg.trikeshed.userspace.nio.spi.NioCapabilityReport(
                backendName = "unknown",
                ioUringAvailable = false,
                capabilities = emptyList(),
                kernelHint = "probe-failed",
                checkedAt = Clock.System.now().toEpochMilliseconds(),
            )
        }
        return mapOf(
            "nio" to mapOf(
                "backendName" to report.backendName,
                "ioUringAvailable" to report.ioUringAvailable,
                "capabilities" to report.capabilities,
                "kernelHint" to report.kernelHint,
                "checkedAt" to report.checkedAt,
            ),
            "flywheel" to emptyMap<String, Any?>(),
        )
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
        <div class="search-box"><input type="text" placeholder="Search workspace…" aria-label="Search workspace" id="forge-search"/></div>
      </div>
      <div class="header-right">
        <button class="btn" id="new-doc-btn" aria-label="Create new document">New Doc</button>
        <button class="btn" id="sync-btn" aria-label="Synchronize workspace">Sync</button>
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
        return mapOf(
            "surface" to view.surface,
            "sections" to view.sections,
            "defaultMode" to view.defaultMode.name,
            "cornerButtons" to view.cornerButtons.map { btn ->
                mapOf(
                    "slot" to btn.slot.name,
                    "id" to btn.id,
                    "label" to btn.label,
                    "hotkey" to btn.hotkey,
                    "surface" to btn.surface,
                )
            },
            "camera" to mapOf(
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
            "camera3D" to mapOf(
                "yawRadians" to cam3d.yawRadians,
                "pitchRadians" to cam3d.pitchRadians,
                "distance" to cam3d.distance,
                "focalLength" to cam3d.focalLength,
                "minDistance" to cam3d.minDistance,
                "maxDistance" to cam3d.maxDistance,
            ),
            "layout3D" to view.layout3D.map { section ->
                mapOf(
                    "sectionId" to section.sectionId,
                    "centerX" to section.centerX,
                    "centerY" to section.centerY,
                    "width" to section.width,
                    "height" to section.height,
                    "elevation" to section.elevation,
                )
            },
        )
    }
