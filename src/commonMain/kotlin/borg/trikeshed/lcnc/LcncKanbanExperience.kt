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
        sheetLcncRegistry() + kanbanLcncRegistry() + PanelVoteNode.registry() + mapOf(
            "kanban.activeSheets" to LcncNodeRunner { _, _ -> activeSheets() },
            // A wired `command` map (the gesture, shaped upstream) overrides params —
            // same inputs-over-params precedence confix.sheets already honours. Params
            // remain the no-wire path: type a jobId, click run.
            "kanban.submit" to LcncNodeRunner { node, inputs ->
                val c = wiredCommand(inputs)
                val title = c["title"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.params["title"]?.takeIf { it.isNotBlank() }
                // A title without a jobId is a CREATE, not a malformed gesture: mint a
                // deterministic id (the panels modal and no-wire lane submit exactly
                // this shape — blank jobId + typed title — and used to silently lose
                // the card while reporting success).
                val jobId = (c["jobId"]?.toString() ?: node.params["jobId"])?.takeIf { it.isNotBlank() }
                    ?: title?.let { t ->
                        "card-" + borg.trikeshed.job.ContentId.of(t.encodeToByteArray()).hex.take(12)
                    }
                // No jobId AND no title = a gesture node without a gesture (headless
                // program run): NO-OP with a reason — silent degrade, not error.
                if (jobId.isNullOrBlank()) mapOf("accepted" to false, "reason" to "no gesture: jobId/title absent")
                else command(
                    buildMap {
                        put("type", "submit")
                        put("jobId", jobId)
                        // Absent stays absent, same as priority/tags/owner below.
                        // `title ?: jobId` meant an edit that only touched tags
                        // OVERWROTE the card's title with its own id — advanceRow's
                        // `?: prev?.title` was unreachable for the same reason the
                        // priority fallback was. A create still gets a title: the
                        // store falls back to the jobId when there is no prev.
                        title?.let { put("title", it) }
                        // Absent stays absent — the same rule the tags/owner lines
                        // below keep. This used to default to 2 on EVERY submit, so
                        // re-submitting a card to add a tag silently reset a p0 to p2:
                        // advanceRow's `?: prev?.priority` could never be reached
                        // because the key was always present. A new card still lands
                        // at 2; the store owns that default, not this runner.
                        (c["priority"]?.toString()?.toDoubleOrNull()?.toInt()
                            ?: node.params["priority"]?.toIntOrNull())
                            ?.let { put("priority", it) }
                        put(
                            "idempotencyKey",
                            c["idempotencyKey"]?.toString()?.takeIf { it.isNotBlank() }
                                ?: node.params["idempotencyKey"]?.takeIf { it.isNotBlank() }
                                ?: "submit#$jobId",
                        )
                        // The store already persists tags/dependencies/owner off the
                        // raw command (BoardStoreElement.advanceRow reads all three),
                        // but this runner dropped them on the floor — so a card
                        // submitted through LCNC could never carry a dependency, and
                        // the cycle guard had nothing to guard. Forward when the
                        // gesture supplies them; absent stays absent, so an existing
                        // card keeps the values it has.
                        listish(c["tags"])?.let { put("tags", it) }
                        listish(c["dependencies"])?.let { put("dependencies", it) }
                        c["owner"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { put("owner", it) }
                    },
                )
            },
            "kanban.move" to LcncNodeRunner { node, inputs ->
                val c = wiredCommand(inputs)
                val jobId = c["jobId"]?.toString() ?: c["itemId"]?.toString() ?: node.params["jobId"]
                if (jobId.isNullOrBlank()) mapOf("accepted" to false, "reason" to "no gesture: command input or jobId param absent")
                else command(
                    buildMap {
                        put("type", "move")
                        put("jobId", jobId)
                        put("toColumn", required(node, c, "toColumn"))
                        // JSON numbers may arrive as 3.0; the reducer consumes a long.
                        put("expectedRevision", required(node, c, "expectedRevision").toDouble().toLong())
                        put("idempotencyKey", required(node, c, "idempotencyKey"))
                        // Positional insert: land BEFORE this card in the target column.
                        c["beforeJobId"]?.toString()?.takeIf { it.isNotBlank() }?.let { put("beforeJobId", it) }
                    },
                )
            },
            // Plan-doc import as a lego: bullets in, cards out — the node form of
            // POST /api/board/import (same parse rules, same content-hash dedupe).
            "kanban.import" to LcncNodeRunner { node, inputs ->
                val text = (inputs["text"] ?: node.params["text"])?.toString().orEmpty()
                val bullets = text.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("- ") || it.startsWith("* ") || Regex("^\\d+\\.\\s").containsMatchIn(it) }
                    .map { it.removePrefix("- ").removePrefix("* ").replace(Regex("^\\d+\\.\\s+"), "").trim() }
                    .map { it.removePrefix("[ ]").removePrefix("[x]").trim() }
                    .filter { it.length in 3..200 }
                    .take(100)
                    .toList()
                var imported = 0
                var duplicates = 0
                val jobIds = ArrayList<String>()
                for (title in bullets) {
                    val hex = borg.trikeshed.job.ContentId.of(title.encodeToByteArray()).hex
                    val jobId = "card-" + hex.take(12)
                    val r = command(
                        mapOf(
                            "type" to "submit", "jobId" to jobId,
                            "title" to title, "idempotencyKey" to "import#" + hex.take(16),
                        ),
                    )
                    if (r["accepted"] == true) { imported++; jobIds.add(jobId) } else duplicates++
                }
                mapOf("parsed" to bullets.size, "imported" to imported, "duplicates" to duplicates, "jobIds" to jobIds)
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

    /** Non-empty string list, or null so the caller can leave the field unset.
     *  Backends disagree on List vs Array; [BoardIntake]'s lowering already
     *  normalizes both, so reuse its rule rather than inventing a second one. */
    private fun listish(value: Any?): List<String>? =
        borg.trikeshed.kanban.InvokeLowering.listishOf(value)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            ?.takeIf { it.isNotEmpty() }

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
