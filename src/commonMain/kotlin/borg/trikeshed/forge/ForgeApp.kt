package borg.trikeshed.forge

import borg.trikeshed.blackboard.BlackboardSurface
import borg.trikeshed.blackboard.BlackboardSurfaceRow
import borg.trikeshed.blackboard.LcncEntitySurface
import borg.trikeshed.forge.blackboard.ForgeBlackboardView
import borg.trikeshed.forge.gallery.ForgeGalleryCatalog
import borg.trikeshed.forge.gallery.ForgeGalleryRenderer
import borg.trikeshed.graph.CausalGraphNodeDTO
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.kanban.ForgeBoardPersistence
import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.reactor.KanbanFSM
import kotlinx.serialization.Serializable

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
    @kotlinx.serialization.Transient
    val lcncEntities: List<borg.trikeshed.lcnc.isam.LcncBlock> = emptyList(),
    val blackboardId: String = "",
    /** Canonical blackboard cursor rows; legacy DTO fields remain compatible views. */
    val surfaceRows: List<BlackboardSurfaceRow> = emptyList(),
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
        ForgeKanbanIngest.reduce(ForgeBoardPersistence.load(userId).getOrThrow())
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
    val persistedDoc = confixDoc(ForgeBoardPersistence.encode(reduction.source))
    val causalIndex = CausalGraphNodeIndex().also { index ->
        reduction.causalNodes.forEach(index::addOrGet)
    }
    val surface = BlackboardSurface.project(
        blackboardId = board.id.value,
        index = causalIndex,
        document = persistedDoc,
        entities = reduction.correlations.map { correlation ->
            val card = board.cards.first { it.id.value == correlation.taskId }
            LcncEntitySurface(
                entityId = "task:${correlation.taskId}",
                lcncKind = "work-package",
                lane = card.columnId.value,
                facet = if (correlation.ready) "ready" else "dependency-gated",
                causalKey = correlation.causalKey,
                title = card.title,
                description = card.description,
            )
        },
    )
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
        // Legacy DTO view retained unchanged for existing seed consumers.
        lcncEntities = reduction.correlations.map { correlation ->
            val card = board.cards.first { it.id.value == correlation.taskId }
            borg.trikeshed.lcnc.isam.LcncBlock(
                id = "task:${correlation.taskId}",
                type = "work-package",
                parentId = null,
                content = mapOf(
                    "lane" to card.columnId.value,
                    "facet" to if (correlation.ready) "ready" else "dependency-gated",
                    "causalKey" to correlation.causalKey,
                    "title" to card.title,
                    "description" to card.description,
                )
            )
        },
        blackboardId = board.id.value,
        surfaceRows = surface.rows,
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
        val content = it.content as? Map<*, *>
        linkedMapOf(
            "entityId" to it.id,
            "lcncKind" to it.type,
            "lane" to content?.get("lane"),
            "facet" to content?.get("facet"),
            "causalKey" to content?.get("causalKey"),
            "title" to content?.get("title"),
            "description" to content?.get("description"),
        )
    },
    "blackboardId" to blackboardId,
    "surfaceRows" to surfaceRows.map {
        linkedMapOf(
            "cardId" to it.cardId,
            "lane" to it.lane,
            "phase" to it.phase,
            "facet" to it.facet,
            "provenance" to it.provenance,
            "causalKey" to it.causalKey,
            "lcncKind" to it.lcncKind,
        )
    },
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

    return borg.trikeshed.forge.generated.ForgeAssets.indexHtml
        .replace("{{STYLES}}", forgeAppStyles())
        .replace("{{GALLERY}}", galleryHtml())
        .replace("{{SEED}}", seed)
        .replace("{{SCRIPT}}", forgeAppScript())
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
