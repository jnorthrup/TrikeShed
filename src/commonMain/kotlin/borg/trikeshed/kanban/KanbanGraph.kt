package borg.trikeshed.kanban

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.forEach
<<<<<<< HEAD
import borg.trikeshed.lib.map
import borg.trikeshed.lib.view
<<<<<<< HEAD
=======
import borg.trikeshed.lib.filter
import borg.trikeshed.lib.j
import borg.trikeshed.lib.mapIndexed
import borg.trikeshed.lib.isEmpty
>>>>>>> origin/bolt-kanbangraph-series-opt-16370706827623048663
=======
>>>>>>> origin/bolt-optimize-kanban-graph-4412943324455884935

/** Hermes-compatible production role carried by a lane, without fixing its order. */
data class KanbanLane(
    val id: String,
    val title: String,
    val order: Int,
    val role: String,
    val inputs: Map<String, String> = emptyMap(),
    val outputs: Map<String, String> = emptyMap(),
)

data class KanbanCondition(val predicate: String, val parameters: Map<String, String> = emptyMap())

/**
 * Edge modes over the FSM. W4.2:
 *  - [LOOP] is a legal back-edge carrying [KanbanEdge.maxIterations]; the
 *    per-card iteration count lives in `KanbanCardState.io` and the engine
 *    refuses transitions past the bound, so a runaway simulation terminates
 *    by construction.
 *  - [ABORT] is an unguarded escape to a terminal lane from any state.
 */
enum class KanbanEdgeMode { DIRECT, FANOUT, JOIN, LOOP, ABORT }

data class KanbanEdge(
    val id: String,
    val from: String,
    val to: String,
    val condition: KanbanCondition? = null,
    val mode: KanbanEdgeMode = KanbanEdgeMode.DIRECT,
    val group: String? = null,
    val requiredBranches: Int = 1,
    /** LOOP bound — iterations allowed before the guard refuses. Must be > 0 when mode == LOOP. */
    val maxIterations: Int = 1,
)

data class KanbanCardState(
    val id: String,
    val owner: String,
    val lane: String,
    val state: String,
    val revision: Long = 0L,
    val io: Map<String, Any?> = emptyMap(),
    val effects: Series<Map<String, Any?>> = emptySeriesOf(),
)

data class KanbanGraph(
    val boardId: String,
    val lanes: Series<KanbanLane>,
    val edges: Series<KanbanEdge>,
    val cards: Series<KanbanCardState> = emptySeriesOf(),
) {
    companion object {
        /**
         * Hermes, recomposed from linkable EIP primitives instead of a flat
         * DIRECT pipe (the prior version: one lane in, one lane out, no
         * concurrency expressible at all — dead weight next to what
         * [borg.trikeshed.lcnc.LcncPresets] tribunal() already proves).
         *
         * ready is a Splitter (FANOUT, group "dispatch") onto a worker pool
         * of two running lanes; review is an Aggregator (JOIN, group
         * "collect", requiredBranches = 2) that waits on both before the
         * card is allowed through. Same five hermes ROLES as before
         * (intake/dispatcher/worker/human/settlement) — the worker role is
         * just no longer pretending to be single-threaded.
         */
        fun hermesDefault(boardId: String = "hermes"): KanbanGraph = KanbanGraph(
            boardId,
            listOf(
                KanbanLane("backlog", "Backlog", 0, "intake", outputs = mapOf("card" to "work")),
                KanbanLane("ready", "Ready", 1, "dispatcher", inputs = mapOf("card" to "work"), outputs = mapOf("card" to "work")),
                KanbanLane("running-a", "Running (worker a)", 2, "worker", inputs = mapOf("card" to "work"), outputs = mapOf("result" to "result")),
                KanbanLane("running-b", "Running (worker b)", 3, "worker", inputs = mapOf("card" to "work"), outputs = mapOf("result" to "result")),
                KanbanLane("review", "Review", 4, "human", inputs = mapOf("result" to "result"), outputs = mapOf("result" to "result")),
                KanbanLane("done", "Done", 5, "settlement", inputs = mapOf("result" to "result")),
            ).toSeries(),
            listOf(
                KanbanEdge("backlog-ready", "backlog", "ready"),
                // Splitter: one dispatch fans out onto the worker pool.
                KanbanEdge("ready-running-a", "ready", "running-a", mode = KanbanEdgeMode.FANOUT, group = "dispatch"),
                KanbanEdge("ready-running-b", "ready", "running-b", mode = KanbanEdgeMode.FANOUT, group = "dispatch"),
                // Aggregator: review waits on both branches of the same dispatch.
                KanbanEdge("running-a-review", "running-a", "review", mode = KanbanEdgeMode.JOIN, group = "collect", requiredBranches = 2),
                KanbanEdge("running-b-review", "running-b", "review", mode = KanbanEdgeMode.JOIN, group = "collect", requiredBranches = 2),
                KanbanEdge("review-done", "review", "done"),
            ).toSeries(),
        )
    }

    fun lane(id: String): KanbanLane? = lanes.view.firstOrNull { it.id == id }
    fun outgoing(id: String): Series<KanbanEdge> = edges.filter { it.from == id }

    /** W4.4: all edges in a FANOUT/JOIN group — the branches that must lower together. */
    fun edgesInGroup(group: String?): List<KanbanEdge> =
        if (group == null) emptyList()
        else edges.filter { it.group == group }.map { it }
}

fun interface KanbanPredicate { fun test(card: KanbanCardState, edge: KanbanEdge): Boolean }

class KanbanPredicateRegistry(private val entries: Map<String, KanbanPredicate> = emptyMap()) {
    fun resolve(name: String): KanbanPredicate? = entries[name]
    fun plus(name: String, predicate: KanbanPredicate): KanbanPredicateRegistry =
        KanbanPredicateRegistry(entries + (name to predicate))
    fun names(): Set<String> = entries.keys
}

sealed class KanbanGraphError {
    data class MissingEndpoint(val edge: String, val lane: String) : KanbanGraphError()
    data class DuplicateEdge(val edge: String) : KanbanGraphError()
    data class IncompatibleIo(val edge: String, val reason: String) : KanbanGraphError()
    data class ForbiddenCycle(val lanes: List<String>) : KanbanGraphError()
    data class UnresolvedPredicate(val edge: String, val predicate: String) : KanbanGraphError()
    data class InvalidCard(val card: String, val reason: String) : KanbanGraphError()
}

data class KanbanGraphValidation(val errors: List<KanbanGraphError>) { val valid get() = errors.isEmpty() }

fun KanbanGraph.validate(predicates: KanbanPredicateRegistry = KanbanPredicateRegistry()): KanbanGraphValidation {
    val errors = mutableListOf<KanbanGraphError>()
<<<<<<< HEAD
    // Bolt: avoid O(N) allocation when iterating Series by removing .toList()
=======
>>>>>>> origin/bolt-kanbangraph-series-opt-16370706827623048663
    val laneIds = lanes.map { it.id }
    val seenOrders = mutableSetOf<Int>()
<<<<<<< HEAD
    lanes.forEach { if (!seenOrders.add(it.order)) errors += KanbanGraphError.IncompatibleIo("lane:${it.id}", "duplicate lane order ${it.order}") }
    val seenIds = mutableSetOf<String>()
    val seenShapes = mutableSetOf<String>()
=======

    // Bolt: Use inline forEach to prevent O(N) allocation of an intermediate ArrayList and lambda object
    lanes.forEach { if (!seenOrders.add(it.order)) errors += KanbanGraphError.IncompatibleIo("lane:${it.id}", "duplicate lane order ${it.order}") }
    val seenIds = mutableSetOf<String>()
    val seenShapes = mutableSetOf<String>()

    // Bolt: Use inline forEach to prevent O(N) allocation of an intermediate ArrayList and lambda object
>>>>>>> origin/bolt-optimize-kanban-graph-4412943324455884935
    edges.forEach { edge ->
        if (!seenIds.add(edge.id)) errors += KanbanGraphError.DuplicateEdge(edge.id)
        if (!laneIds.contains(edge.from)) errors += KanbanGraphError.MissingEndpoint(edge.id, edge.from)
        if (!laneIds.contains(edge.to)) errors += KanbanGraphError.MissingEndpoint(edge.id, edge.to)
        val shape = "${edge.from}|${edge.to}|${edge.condition?.predicate}|${edge.mode}|${edge.group}"
        if (!seenShapes.add(shape)) errors += KanbanGraphError.DuplicateEdge(edge.id)
        edge.condition?.let { if (predicates.resolve(it.predicate) == null) errors += KanbanGraphError.UnresolvedPredicate(edge.id, it.predicate) }
        if (edge.requiredBranches < 1) errors += KanbanGraphError.IncompatibleIo(edge.id, "requiredBranches must be positive")
        // Group coordination is a FANOUT/JOIN concern; LOOP is a single bounded
        // back-edge and ABORT an unguarded escape — neither joins a branch group.
        if ((edge.mode == KanbanEdgeMode.FANOUT || edge.mode == KanbanEdgeMode.JOIN) && edge.group.isNullOrBlank()) errors += KanbanGraphError.IncompatibleIo(edge.id, "${edge.mode} edges require a group")
        // W4.2: LOOP edges must declare a positive bound; ABORT edges must land on a lane
        // with no outgoing edges (a terminal), so abort can never be a waypoint.
        if (edge.mode == KanbanEdgeMode.LOOP && edge.maxIterations < 1) errors += KanbanGraphError.IncompatibleIo(edge.id, "LOOP requires maxIterations >= 1")
        if (edge.mode == KanbanEdgeMode.ABORT && edge.condition != null) errors += KanbanGraphError.IncompatibleIo(edge.id, "ABORT is unguarded by design")
        val source = lane(edge.from)
        val target = lane(edge.to)
        if (source != null && target != null && source.outputs.isNotEmpty() && target.inputs.isNotEmpty() &&
            source.outputs.values.none { it in target.inputs.values }) {
            errors += KanbanGraphError.IncompatibleIo(edge.id, "${source.id} outputs ${source.outputs.values} do not mate ${target.id} inputs ${target.inputs.values}")
        }
    }
    edges.view.groupBy { it.group }.values.forEach { grouped ->
        val first = grouped.firstOrNull() ?: return@forEach
        if (first.mode == KanbanEdgeMode.FANOUT && grouped.size < 2) errors += KanbanGraphError.IncompatibleIo(first.id, "fanout requires at least two branches")
        if (first.mode == KanbanEdgeMode.JOIN && grouped.size < first.requiredBranches) errors += KanbanGraphError.IncompatibleIo(first.id, "join requires ${first.requiredBranches} branches")
    }
<<<<<<< HEAD
=======

    // Bolt: Use inline forEach to prevent O(N) allocation of an intermediate ArrayList and lambda object
>>>>>>> origin/bolt-optimize-kanban-graph-4412943324455884935
    cards.forEach { card -> if (lane(card.lane) == null) errors += KanbanGraphError.InvalidCard(card.id, "missing lane ${card.lane}") }
    // W4.3: cycles become opt-in. A back-edge is forbidden only when it is NOT
    // declared as a LOOP edge. LOOP edges were already required to carry a
    // positive maxIterations above, so the iteration guard bounds any loop.
    val marks = mutableMapOf<String, Int>()
    fun visit(id: String, path: List<String>) {
        if (marks[id] == 2) return
        if (marks[id] == 1) {
            // Cycle detected along `path`. Legal iff every edge closing it is a LOOP.
            // The offending back-edge is path.first() -> ... the lane being re-entered.
            val cycle = path.subList(path.indexOf(id), path.size) + id
            val closes = edges.view.any { edge ->
                edge.mode == KanbanEdgeMode.LOOP && edge.from == path.last() && edge.to == id
            }
            if (!closes) errors += KanbanGraphError.ForbiddenCycle(cycle)
            return
        }
        marks[id] = 1
        edges.forEach { if (it.from == id) visit(it.to, path + id) }
        marks[id] = 2
    }
    laneIds.forEach { visit(it, emptyList()) }
    return KanbanGraphValidation(errors)
}

data class KanbanTransitionRequest(val cardId: String, val expectedRevision: Long, val requestedTarget: String? = null)

/**
 * W4.4: a lowered command the engine emits into the store's intake.
 * FANOUT → N Submits (one per branch edge); JOIN → one Submit with
 * dependencies on those branch jobIds. Idempotency keys follow the
 * existing "$jobId#$ruleId#$rev" convention.
 */
data class LoweredCommand(
    val type: String,
    val jobId: String,
    val idempotencyKey: String,
    val dependencies: List<String> = emptyList(),
    val toColumn: String? = null,
    val expectedRevision: Long? = null,
)

sealed class KanbanTransitionResult {
    data class Committed(
        val graph: KanbanGraph,
        val edgeIds: List<String>,
        val lowered: List<LoweredCommand> = emptyList(),
    ) : KanbanTransitionResult()
    data class Rejected(val reason: String) : KanbanTransitionResult()
}

object KanbanGraphEngine {
    /** io key carrying the per-card loop iteration count for a specific edge id. */
    private fun iterKey(edgeId: String) = "loop.iterations.$edgeId"

    fun transition(graph: KanbanGraph, request: KanbanTransitionRequest, predicates: KanbanPredicateRegistry): KanbanTransitionResult {
        val validation = graph.validate(predicates)
        if (!validation.valid) return KanbanTransitionResult.Rejected(validation.errors.joinToString { it.toString() })
        val card = graph.cards.view.firstOrNull { it.id == request.cardId } ?: return KanbanTransitionResult.Rejected("missing card ${request.cardId}")
        if (card.revision != request.expectedRevision) return KanbanTransitionResult.Rejected("revision mismatch")
        val candidates = graph.outgoing(card.lane).filter { edge ->
            (request.requestedTarget == null || edge.to == request.requestedTarget) &&
                // ABORT is an unguarded escape — it bypasses predicates by design.
                (edge.mode == KanbanEdgeMode.ABORT ||
                    (edge.condition == null || predicates.resolve(edge.condition.predicate)!!.test(card, edge)))
        }
        if (candidates.isEmpty()) return KanbanTransitionResult.Rejected("no permitted transition from ${card.lane}")
        if (request.requestedTarget == null && candidates.size > 1) {
            // An available ABORT edge breaks the ambiguity: abort wins over branching.
            val aborts = candidates.filter { it.mode == KanbanEdgeMode.ABORT }
            if (aborts.size != 1) return KanbanTransitionResult.Rejected("branch target required")
            return commit(graph, card, aborts[0])
        }
        val chosen = candidates[0]
        // W4.2 iteration guard: a LOOP edge refuses transitions past its bound.
        // The count is per-card, per-edge, living in the card's own io map.
        if (chosen.mode == KanbanEdgeMode.LOOP) {
            val used = (card.io[iterKey(chosen.id)] as? Number)?.toInt() ?: 0
            if (used >= chosen.maxIterations) {
                return KanbanTransitionResult.Rejected(
                    "loop ${chosen.id} exhausted: $used/${chosen.maxIterations} iterations",
                )
            }
        }
        return commit(graph, card, chosen)
    }

    private fun commit(graph: KanbanGraph, card: KanbanCardState, chosen: KanbanEdge): KanbanTransitionResult.Committed {
        // Loop bookkeeping lives in io, keyed by edge, so one card can traverse
        // several loops without cross-talk.
        val nextIo = if (chosen.mode == KanbanEdgeMode.LOOP) {
            val key = iterKey(chosen.id)
            val used = (card.io[key] as? Number)?.toInt() ?: 0
            card.io + (key to (used + 1))
        } else card.io
        val effect = mapOf<String, Any?>(
            "edge" to chosen.id, "from" to card.lane, "to" to chosen.to,
            "mode" to chosen.mode.name,
            "iteration" to ((nextIo[iterKey(chosen.id)] as? Number)?.toInt() ?: 0).takeIf { chosen.mode == KanbanEdgeMode.LOOP },
        )
        val moved = card.copy(
            lane = chosen.to, state = chosen.to, revision = card.revision + 1,
            io = nextIo,
            effects = card.effects.view.plus(effect).toSeries(),
        )
        val cards = graph.cards.map { if (it.id == card.id) moved else it }.toSeries()

        // W4.4: FANOUT lowers to N Submits (one per FANOUT branch in the group);
        // JOIN lowers to one Submit whose dependencies are the FANOUT branch jobIds.
        val lowered = when (chosen.mode) {
            KanbanEdgeMode.FANOUT -> {
                val branches = graph.edgesInGroup(chosen.group).filter { it.mode == KanbanEdgeMode.FANOUT }
                branches.mapIndexed { i, edge ->
                    LoweredCommand(
                        type = "submit",
                        jobId = "${card.id}#${edge.group}#$i",
                        idempotencyKey = "${card.id}#${edge.id}#${card.revision}",
                        toColumn = edge.to,
                    )
                }
            }
            KanbanEdgeMode.JOIN -> {
                val fanoutBranches = graph.edgesInGroup(chosen.group).filter { it.mode == KanbanEdgeMode.FANOUT }
                val branchIds = fanoutBranches.mapIndexed { i, edge -> "${card.id}#${edge.group}#$i" }
                listOf(
                    LoweredCommand(
                        type = "submit",
                        jobId = "${card.id}#${chosen.group}#join",
                        idempotencyKey = "${card.id}#${chosen.id}#${card.revision}",
                        dependencies = branchIds,
                        toColumn = chosen.to,
                    ),
                )
            }
            else -> emptyList()
        }

        return KanbanTransitionResult.Committed(graph.copy(cards = cards), listOf(chosen.id), lowered)
    }
}
