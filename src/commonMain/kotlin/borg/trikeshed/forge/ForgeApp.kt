package borg.trikeshed.forge

import borg.trikeshed.common.Files
import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.cursor.provenance
import borg.trikeshed.forge.blackboard.ForgeBlackboardCamera
import borg.trikeshed.forge.blackboard.ForgeBlackboardSection3D
import borg.trikeshed.forge.blackboard.ForgeBlackboardView
import borg.trikeshed.forge.blackboard.forceLayout
import borg.trikeshed.forge.gallery.ForgeGalleryCatalog
import borg.trikeshed.forge.gallery.ForgeGalleryRenderer
import borg.trikeshed.forge.concept.ConceptGraph
import borg.trikeshed.platform.Discontinued
import borg.trikeshed.platform.PlatformHost
import borg.trikeshed.vm.VmHost
import borg.trikeshed.vm.VmSupervisor
import borg.trikeshed.vm.isDead
import borg.trikeshed.vm.vmHost
import borg.trikeshed.forge.sheet.confixSheets
import borg.trikeshed.forge.sheet.sheetSeed
import borg.trikeshed.job.schema.loadConfixSchemaBytes
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.jules.ui.JulesBlackboardAdapter
import borg.trikeshed.jules.ui.JulesBlackboardSurface
import borg.trikeshed.jules.ui.ForgeSurfaceSession
import borg.trikeshed.jules.ui.ForgeSurfaceActivity
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.kanban.ForgeKanbanReduction
import borg.trikeshed.lcnc.reactor.IngestCodec
import borg.trikeshed.lcnc.reactor.IngestFormat
import borg.trikeshed.lcnc.reactor.IngestSource
import borg.trikeshed.lcnc.reactor.LcncIngestPipeline
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.blackboard.BlackboardSurface
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.datetime.Clock

/**
 * ForgeApp — single file server-side renderable workspace shell.
 * Static assets consolidated to src/commonMain/resources/web/; server-side render in commonMain.
 * PWA offline-first: Forge captures projects server-free; server/mesh additive sync.
 */
object ForgeApp {

    /**
     * Flat snapshot of FlywheelDriver.lastReactiveReport, flattened to plain
     * primitives so it lives in commonMain and serializes into the dashboard seed.
     * Constructed by the caller in jvmMain where CycleReport is defined:
     * ```
     * val report = flywheelDriver.lastReactiveReport ?: return@runBlocking null
     * val snapshot = ForgeApp.FlywheelReportSnapshot(
     *     cycleMs = report.cycleMs, answered = report.answered, harvested = report.harvested,
     *     reworked = report.reworked, dispatched = report.dispatched, alive = report.alive,
     *     available = report.available, inducted = report.inducted, settled = report.settled,
     *     archived = report.archived, phase = report.phase.name, conflicts = report.conflicts,
     *     http429 = report.http429, http5xx = report.http5xx,
     * )
     * ```
     */
    data class FlywheelReportSnapshot(
        val cycleMs: Long,
        val answered: Int,
        val harvested: Int,
        val reworked: Int,
        val dispatched: Int,
        val alive: Int,
        val available: Int,
        val inducted: Int,
        val settled: Boolean,
        val archived: Int,
        val phase: String,
        val conflicts: List<String>,
        val http429: Int,
        val http5xx: Int,
        val updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "cycleMs" to cycleMs,
            "answered" to answered,
            "harvested" to harvested,
            "reworked" to reworked,
            "dispatched" to dispatched,
            "alive" to alive,
            "available" to available,
            "inducted" to inducted,
            "settled" to settled,
            "archived" to archived,
            "phase" to phase,
            "conflicts" to conflicts,
            "http429" to http429,
            "http5xx" to http5xx,
            "updatedAt" to updatedAt,
        )
    }

    /** Render the complete Forge HTML shell with seeded state for PWA offline-first hydration. */
    fun renderHtml(
        userId: String = "jim",
        julesSurface: JulesBlackboardSurface? = null,
        flywheelReport: FlywheelReportSnapshot? = null,
        bundles: List<String> = emptyList(),
        vmHost: VmHost = PlatformHost.default.vmHost,
    ): String {
        val reduction = runCatching { ForgeKanbanIngest.loadProjection(userId) }.getOrElse { ForgeKanbanIngest.fallbackReduction() }
        val seed = forgeSeedJson(userId, reduction, julesSurface, flywheelReport, vmHost)
        return htmlShell(seed, bundles)
    }

    private fun forgeSeedJson(
        userId: String,
        reduction: ForgeKanbanReduction,
        julesSurface: JulesBlackboardSurface?,
        flywheelReport: FlywheelReportSnapshot?,
        vmHost: VmHost,
    ): String {
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
            "graphLayout" to forgeGraphLayoutSeed(reduction),
            "conceptGraph" to ConceptGraph.layoutSeed(),
            "sheets" to forgeSheetsSeed(reduction),
            "blackboardSeed" to forgeBlackboardSeed(julesSurface),
            "dashboards" to forgeDashboardSeed(flywheelReport),
            "hosts" to forgeHostsSeed(vmHost),
        )
        // The seed lives inside <script type="application/json">; a literal "</" must not end that element.
        return JsonSupport.stringify(seedMap).replace("</", "<\\/")
    }

    /**
     * Graph view seed: the causal graph laid out by [forceLayout] in commonMain, so the browser
     * renderer only draws (nodes in world px, edges parent→child, and the camera that frames them).
     */
    private fun forgeGraphLayoutSeed(reduction: ForgeKanbanReduction): Map<String, Any?> {
        val index = CausalGraphNodeIndex()
        reduction.causalNodes.forEach { index.addOrGet(it) }
        val (camera, positions) = forceLayout(index, ForgeBlackboardCamera(), iterations = 120)
        // Causal node ids are the kanban task ids, so the card title is the human label;
        // opId:opVersion is the same string for every node of one ingest and says nothing.
        val cardTitles = reduction.board.cards.associate { it.id.value to it.title }
        val nodes = reduction.causalNodes.map { node ->
            val p = positions[node.nodeId]
            mapOf(
                "id" to node.nodeId,
                "title" to (cardTitles[node.nodeId] ?: (node.opId + ":" + node.opVersion)),
                "x" to (p?.screenX ?: 0.0) * GRAPH_SPREAD,
                "y" to (p?.screenY ?: 0.0) * GRAPH_SPREAD,
                "topo" to node.topoOrdinal,
            )
        }
        val known = reduction.causalNodes.map { it.nodeId }.toSet()
        val edges = reduction.causalNodes.flatMap { node ->
            node.parentNodeIds.filter { it in known }.map { parent -> mapOf("from" to parent, "to" to node.nodeId) }
        }
        return mapOf(
            "nodes" to nodes,
            "edges" to edges,
            "camera" to mapOf("x" to camera.x * GRAPH_SPREAD, "y" to camera.y * GRAPH_SPREAD, "zoom" to camera.zoom / GRAPH_SPREAD),
        )
    }

    /**
     * [forceLayout] rests springs at 50 world px (tuned for the blackboard's dot nodes); the
     * graph view draws ~200 px cards, so the same positions are spread uniformly (camera too)
     * rather than retuning the shared layout.
     */
    private const val GRAPH_SPREAD = 2.6

    /**
     * Sheet view seed: the blackboard surface as a flat Cursor sheet (`BlackboardSurface.asCursor`, the one
     * Cursor-shaped UI projection) plus the Confix job-nexus schema as nested sheets (grid-in-cell).
     * Each source is independent; a failing one is dropped rather than blanking the view.
     */
    private fun forgeSheetsSeed(reduction: ForgeKanbanReduction): List<Map<String, Any?>> {
        val sheets = ArrayList<Map<String, Any?>>()
        runCatching {
            val cardById = reduction.board.cards.associateBy { it.id.value }
            val entities = reduction.correlations.mapNotNull { corr -> cardById[corr.taskId]?.let { correlationToBlock(corr, it) } }
            val index = CausalGraphNodeIndex()
            reduction.causalNodes.forEach { index.addOrGet(it) }
            val surface = BlackboardSurface.project("forge-sheet", index, entities)
            sheetSeed("blackboard", "Blackboard surface · " + reduction.source.title, surface.asCursor())
        }.onSuccess { sheets.add(it.toMap()) }
        runCatching {
            val json = loadConfixSchemaBytes("classpath:/confix/job-nexus.schema.json").decodeToString()
            confixSheets("confix", "Confix · job-nexus.schema.json", confixDoc(json))
        }.onSuccess { family -> family.forEach { sheets.add(it.toMap()) } }
        return sheets
    }

    /**
     * Dashboard seed: launch-time native I/O capability + latest flywheel cycle evidence.
     * The server render is the authoritative launch-time snapshot.
     */
    /**
     * Host view seed: what this host can run. The VM tiers come from the common VM API
     * (`borg.trikeshed.vm`): the bound host's rows as a sheet, every provider's capability report,
     * and the features that are dead on this target (interface chokepoints hit or declared) — the
     * static Pages build bakes whatever the JVM baker had, and the live server re-renders per request.
     */
    private fun forgeHostsSeed(vmHost: VmHost): Map<String, Any?> {
        val reports = runCatching { VmSupervisor.reports }.getOrElse { emptyList() }
        if (vmHost.isDead) listOf("vm.spawn", "vm.eval", "vm.call", "vm.revoke").forEach { Discontinued.declare(it) }
        val vmColumns = borg.trikeshed.vm.VM_COLUMNS.map { borg.trikeshed.forge.sheet.SheetColumn(it.first, it.second.name) }
        val vms = runCatching { sheetSeed("vms", "Sub-VMs", vmHost.rows(), columns = vmColumns).toMap() }.getOrNull()
        return mapOf(
            "host" to mapOf(
                "platform" to vmHost.platform,
                "subVm" to !vmHost.isDead,
                "languages" to vmHost.languages.map { it.id },
                "vms" to vmHost.ids().size,
                "nio" to nioSeed(),
                "discontinued" to (Discontinued.features + Discontinued.declaredDead).sorted(),
            ),
            "providers" to reports.map { it.toMap() },
            "vms" to vms,
        )
    }

    private fun nioSeed(): Map<String, Any?> {
        val nioReport = runCatching {
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
            "backendName" to nioReport.backendName,
            "ioUringAvailable" to nioReport.ioUringAvailable,
            "capabilities" to nioReport.capabilities,
            "kernelHint" to nioReport.kernelHint,
            "checkedAt" to nioReport.checkedAt,
        )
    }

    private fun forgeDashboardSeed(flywheelReport: FlywheelReportSnapshot?): Map<String, Any?> = mapOf(
        "nio" to nioSeed(),
        "flywheel" to (flywheelReport?.toMap() ?: emptyMap<String, Any?>()),
    )

    /** Placeholders in `src/commonMain/resources/web/index.html`; the shell and `script.js` share one DOM. */
    const val SEED_SLOT = "{{SEED}}"
    const val STYLES_SLOT = "{{STYLES}}"
    const val SCRIPT_SLOT = "{{SCRIPT}}"
    const val GALLERY_SLOT = "{{GALLERY}}"
    /** Kotlin/JS / wasmJs bundle `<script>` tags published by `generateForgePages` stages; empty for the pure static shell. */
    const val BUNDLES_SLOT = "{{BUNDLES}}"

    /**
     * The one shell: the web template with its slots filled. Relative asset paths (`./sw.js`,
     * `./manifest.webmanifest`, `./icons/…`) keep the PWA scope at wherever the page is served —
     * a sub-path on GitHub Pages, `/` on the JVM server — never a root-scoped service worker by accident.
     */
    private fun htmlShell(seed: String, bundles: List<String>): String =
        borg.trikeshed.forge.generated.ForgeAssets.indexHtml
            .replace(STYLES_SLOT, forgeAppStyles())
            .replace(GALLERY_SLOT, galleryHtml())
            .replace(SCRIPT_SLOT, forgeAppScript())
            .replace(BUNDLES_SLOT, bundleTags(bundles))
            .replace(SEED_SLOT, seed)

    /** Relative `src` + `defer`: the bundle runs after script.js has hydrated and the PWA scope stays wherever the page is served. */
    private fun bundleTags(bundles: List<String>): String =
        bundles.joinToString("\n") { "  <script src=\"$it\" defer></script>" }

    private fun forgeAppStyles(): String = borg.trikeshed.forge.generated.ForgeAssets.stylesCss

    private fun forgeAppScript(): String = forgePersistenceScript()

    /** Server-rendered gallery HTML for the sidebar. No client-side hydration needed. */
    private fun galleryHtml(): String = runCatching { ForgeGalleryRenderer.renderHtml() }.getOrElse { "" }

    private fun forgeBlackboardSeed(julesSurface: JulesBlackboardSurface?): Map<String, Any?> {
        val view = ForgeBlackboardView.DEFAULT
        val cam = view.defaultCamera
        val cam3d = view.mode3D

        // Build the 3D layout: default sections + Jules session sections + Jules activity sections.
        val sessionSections: List<Map<String, Any?>> = julesSurface?.sessions?.map { s: ForgeSurfaceSession ->
            mapOf(
                "sectionId" to s.sectionId,
                "centerX" to s.centerX,
                "centerY" to s.centerY,
                "width" to s.width,
                "height" to s.height,
                "elevation" to s.elevation,
            )
        } ?: emptyList()

        val activitySections: List<Map<String, Any?>> = julesSurface?.activities?.map { a: ForgeSurfaceActivity ->
            mapOf(
                "sectionId" to a.sectionId,
                "centerX" to a.centerX,
                "centerY" to a.centerY,
                "width" to a.width,
                "height" to a.height,
                "elevation" to a.elevation,
            )
        } ?: emptyList()

        val baseLayout: List<Map<String, Any?>> = view.layout3D.map { section: ForgeBlackboardSection3D ->
            mapOf(
                "sectionId" to section.sectionId,
                "centerX" to section.centerX,
                "centerY" to section.centerY,
                "width" to section.width,
                "height" to section.height,
                "elevation" to section.elevation,
            )
        }
        val layout3D: List<Map<String, Any?>> = baseLayout + sessionSections + activitySections

        // Derive the full sections list from the layout to satisfy the init constraint.
        val sectionIds: List<String> = layout3D.mapNotNull { it["sectionId"] as? String }

        return mapOf(
            "surface" to view.surface,
            "sections" to sectionIds,
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
            "layout3D" to layout3D,
            // Jules surface projection — emitted even when null so the JS renderer
            // knows the field exists and can handle absence gracefully.
            "jules" to if (julesSurface != null) {
                val s = julesSurface
                mapOf(
                    "sessions" to s.sessions.map { sess: ForgeSurfaceSession ->
                        mapOf(
                            "id" to sess.id,
                            "state" to sess.state,
                            "title" to sess.title,
                            "patchBytes" to sess.patchBytes,
                            "source" to sess.source,
                            "updateTime" to sess.updateTime,
                            "sectionId" to sess.sectionId,
                        )
                    },
                    "activities" to s.activities.map { act: ForgeSurfaceActivity ->
                        mapOf(
                            "id" to act.id,
                            "sessionId" to act.sessionId,
                            "seq" to act.seq,
                            "createTime" to act.createTime,
                            "originator" to act.originator,
                            "kind" to act.kind,
                            "patchBytes" to act.patchBytes,
                            "excerpt" to act.excerpt,
                            "sectionId" to act.sectionId,
                        )
                    },
                    "updatedAt" to s.updatedAt,
                    "ttlMs" to s.ttlMs,
                    "stale" to JulesBlackboardAdapter.isStale(s),
                )
            } else null,
        )
    }
}
