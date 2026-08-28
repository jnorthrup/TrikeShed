package borg.trikeshed.lcnc

import borg.trikeshed.forge.sheet.SheetSeed
import borg.trikeshed.forge.sheet.confixSheets
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.kanban.BoardIntake
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.KanbanGraph
import borg.trikeshed.kanban.KanbanGraphConfix
import borg.trikeshed.kanban.toBoardMap
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.CompletableDeferred
import borg.trikeshed.lib.toList

/**
 * The LCNC-only Kanban surface. It owns no shadow board state: commands enter
 * [BoardStoreElement]'s single-writer intake and every tree-sheet is freshly
 * projected from that same operational store after the command commits.
 *
 * JSON is only an ingress value here. `confix.sheets` immediately turns it
 * into a Confix document and then into a grid-in-cell [SheetSeed] family.
 * The panel graph therefore operates on Kotlin values, not browser objects.
 */
class LcncKanbanExperience(
    private val store: BoardStoreElement,
    private val graph: () -> KanbanGraph = { KanbanGraph.hermesDefault() },
) {

    /** Current board plus the two useful concentric partitions. */
    fun activeSheets(): Map<String, Any?> {
        // One committed store projection feeds both the gesture surface and the
        // concentric sheets. Keeping this boundary in Kotlin means the browser
        // never groups cards, invents statuses, or reshapes commands.
        val board = borg.trikeshed.kanban.BoardCursor.of(store.cards())
        val orchestration = graph()
        val orchestrationMap = JsonSupport.parse(KanbanGraphConfix.toJson(orchestration))
        return mapOf(
            "board" to LcncOperationalSheets.board(store).toLcncMap(),
            "byStatus" to LcncOperationalSheets.byStatus(store).map(SheetSeed::toLcncMap),
            "byPriority" to LcncOperationalSheets.byPriority(store).map(SheetSeed::toLcncMap),
            "boardView" to board.toBoardMap(store.lastSequence, "Kanban board (live)"),
            "orchestration" to orchestrationMap,
            "laneOrder" to orchestration.lanes.toList().sortedBy { it.order }.map { it.id },
            "conditions" to orchestration.edges.toList().mapNotNull { it.condition?.let { c -> mapOf("edge" to it.id, "predicate" to c.predicate, "parameters" to c.parameters) } },
        )
    }

    /** Registry for a complete in-process Kanban panel program. */
    fun registry(): Map<String, LcncNodeRunner> =
        sheetLcncRegistry() + kanbanLcncRegistry() + mapOf(
            "kanban.activeSheets" to LcncNodeRunner { _, _ -> activeSheets() },
            // A wired `command` map (the gesture, shaped upstream) overrides params —
            // same inputs-over-params precedence confix.sheets already honours. Params
            // remain the no-wire path: type a jobId, click run.
            "kanban.submit" to LcncNodeRunner { node, inputs ->
                val c = wiredCommand(inputs)
                command(
                    mapOf(
                        "type" to "submit",
                        "jobId" to required(node, c, "jobId"),
                        "title" to (c["title"]?.toString() ?: node.params["title"] ?: required(node, c, "jobId")),
                        "priority" to (c["priority"]?.toString()?.toDoubleOrNull()?.toInt()
                            ?: node.params["priority"]?.toIntOrNull() ?: 2),
                        "idempotencyKey" to required(node, c, "idempotencyKey"),
                    ),
                )
            },
            "kanban.move" to LcncNodeRunner { node, inputs ->
                val c = wiredCommand(inputs)
                command(
                    mapOf(
                        "type" to "move",
                        "jobId" to required(node, c, "jobId"),
                        "toColumn" to required(node, c, "toColumn"),
                        // JSON numbers may arrive as 3.0; the reducer consumes a long.
                        "expectedRevision" to required(node, c, "expectedRevision").toDouble().toLong(),
                        "idempotencyKey" to required(node, c, "idempotencyKey"),
                    ),
                )
            },
            "confix.sheets" to LcncNodeRunner { node, inputs ->
                val json = (inputs["json"] ?: node.params["json"])
                    ?.toString()
                    ?: error("confix.sheets: json input or param required")
                val id = node.params["id"] ?: "confix"
                val title = node.params["title"] ?: id
                val family = confixSheets(id, title, confixDoc(json))
                mapOf(
                    "sheets" to family.map(SheetSeed::toLcncMap),
                    "sheet" to family.firstOrNull()?.toLcncMap(),
                )
            },
            "confix.pickPath" to LcncNodeRunner { node, inputs ->
                val json = (inputs["json"] ?: node.params["json"])
                    ?.toString()
                    ?: error("confix.pickPath: json input or param required")
                val path = required(node, "path")
                val selected = resolveJsonPath(JsonSupport.parse(json), path)
                if (selected == null) {
                    mapOf("found" to false, "path" to path, "sheets" to emptyList<Any?>(), "sheet" to null)
                } else {
                    // A scalar still needs a visible Confix row, while objects/arrays retain their native nesting.
                    val shown = if (selected is Map<*, *> || selected is List<*>) selected else mapOf("value" to selected)
                    val family = confixSheets("confix/$path", path, confixDoc(JsonSupport.stringify(shown)))
                    mapOf(
                        "found" to true,
                        "path" to path,
                        "sheets" to family.map(SheetSeed::toLcncMap),
                        "sheet" to family.firstOrNull()?.toLcncMap(),
                    )
                }
            },
        )

    private suspend fun command(raw: Map<String, Any?>): Map<String, Any?> {
        val reply = CompletableDeferred<BoardApply>()
        store.intake.send(BoardIntake(raw, reply))
        return when (val applied = reply.await()) {
            is BoardApply.Committed -> mapOf(
                "accepted" to true,
                "jobId" to applied.jobId,
                "sequence" to applied.sequence,
                "revision" to applied.revision,
                "idempotencyKey" to applied.idempotencyKey,
                "cid" to applied.cid.value,
                // This is deliberately re-projected after the commit, not a pre-command cache.
                "sheets" to activeSheets(),
            )

            is BoardApply.Rejected -> mapOf(
                "accepted" to false,
                "idempotencyKey" to applied.idempotencyKey,
                "reason" to applied.reason,
                "sheets" to activeSheets(),
            )
        }
    }

    private fun required(node: LcncNode, name: String): String =
        node.params[name] ?: error("${node.type}: $name param required")

    private fun required(node: LcncNode, wired: Map<*, *>, name: String): String =
        wired[name]?.toString() ?: node.params[name] ?: error("${node.type}: $name required (command input or param)")

    private fun wiredCommand(inputs: Map<String, Any?>): Map<*, *> =
        (inputs["command"] ?: inputs["command?"]) as? Map<*, *> ?: emptyMap<String, Any?>()

    /** No stale parent-port preview: only the requested JSON path is projectable. */
    private fun resolveJsonPath(root: Any?, rawPath: String): Any? {
        var value = root
        val path = rawPath.trim().removePrefix("/")
        if (path.isEmpty()) return value
        for (segment in path.replace('/', '.').split('.')) {
            if (segment.isEmpty()) continue
            value = when (value) {
                is Map<*, *> -> value[segment]
                is List<*> -> value.getOrNull(segment.toIntOrNull() ?: return null)
                else -> return null
            } ?: return null
        }
        return value
    }
}
