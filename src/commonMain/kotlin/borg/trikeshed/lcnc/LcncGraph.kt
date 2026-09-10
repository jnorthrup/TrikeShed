package borg.trikeshed.lcnc

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.filter
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries

/**
 * LcncGraph — the LCNC node/wire model as commonMain Kotlin (one author; no
 * browser-side object literals anywhere). Loaded once from a stored program
 * and never mutated thereafter, so per PRELOAD's categorical-idempotency rule
 * this stays a `Series`, not a `List`: `LcncNode`/`LcncWire` are the frozen
 * shape of a program, read many times, written never after load.
 */
data class LcncNode(
    val id: String,
    val type: String,
    val params: Map<String, String> = emptyMap(),
    val x: Double = 0.0,
    val y: Double = 0.0,
    /** Persisted presentation bounds; null retains the type's natural size. */
    val width: Double? = null,
    val height: Double? = null,
    /** Collapsed state — header shown, body hidden. */
    val collapsed: Boolean = false,
    /** Name of a stored subprogram defining this node's internals — a NAMED
     *  ring: the body lives in another stored document, loaded at entry and
     *  run under the same concentric rules as inline [children] (it still
     *  sees the enclosing environment — a named ring is lazy containment,
     *  not a call into a vacuum). ProgramNavigator dives the same chain the
     *  executor walks; popTo rejects, never clamps. */
    val subprogram: String? = null,
    /** CONCENTRIC CONTAINMENT — the ring's own statements. A node holding
     *  children IS a scope ring: one document, rings inside rings. Inner
     *  nodes may consume enclosing-ring outputs directly (wires cross ring
     *  boundaries INWARD; see LcncScopeViolation for the outward rule) and
     *  resolve `scope.in` names outward through the frame chain — nearest
     *  ring shadows. Only `scope.out` values leave the ring. */
    val children: Series<LcncNode> = emptySeriesOf(),
)

/** Viewport (pan/zoom) persisted with the program so a reload restores the exact camera. */
data class LcncView(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val zoom: Double = 1.0,
)

/** A wire connects one node's named output port to another's named input port. */
data class LcncWire(
    val fromNode: String,
    val fromPort: String,
    val toNode: String,
    val toPort: String,
)

enum class LcncCardinality { ONE, OPTIONAL, MANY }

/** A typed, editable connection point in the patch panel. */
data class LcncPatchMatingPoint(
    val id: String,
    val fromNode: String,
    val fromPort: String,
    val toNode: String,
    val toPort: String,
    val cardinality: LcncCardinality = LcncCardinality.ONE,
    val function: String = "identity",
) {
    fun validate(): LcncPatchMatingPoint {
        require(id.isNotBlank()) { "mating point id is required" }
        require(fromNode.isNotBlank() && fromPort.isNotBlank()) { "source node and port are required" }
        require(toNode.isNotBlank() && toPort.isNotBlank()) { "target node and port are required" }
        require(function.isNotBlank()) { "mating function is required" }
        return this
    }
}

/** Confix-owned oversight and mating state; UI gestures must lower to these operations. */
data class LcncConfixControls(
    val humanOversight: Boolean = true,
    val matingPoints: Series<LcncPatchMatingPoint> = emptySeriesOf(),
    /** Wiring specimens may be edited, but never dispatched as procedures. */
    val inspectionOnly: Boolean = false,
) {
    fun toggleOversight(enabled: Boolean): LcncConfixControls = copy(humanOversight = enabled)

    fun addMatingPoint(point: LcncPatchMatingPoint): LcncConfixControls {
        point.validate()
        require((0 until matingPoints.size).none { matingPoints[it].id == point.id }) { "duplicate mating point: ${point.id}" }
        // Bolt: Use zero-allocation 'j' constructor instead of intermediate List allocations and copies via .map { ... }.plus(...).toSeries()
        return copy(matingPoints = (matingPoints.size + 1) j { i -> if (i < matingPoints.size) matingPoints[i] else point })
    }

    fun updateMatingPoint(point: LcncPatchMatingPoint): LcncConfixControls {
        point.validate()
        require((0 until matingPoints.size).any { matingPoints[it].id == point.id }) { "unknown mating point: ${point.id}" }
        // Bolt: Use zero-allocation 'j' constructor instead of intermediate List allocations via .map { ... }.toSeries()
        return copy(matingPoints = matingPoints.size j { i -> if (matingPoints[i].id == point.id) point else matingPoints[i] })
    }

    fun removeMatingPoint(id: String): LcncConfixControls {
        require((0 until matingPoints.size).any { matingPoints[it].id == id }) { "unknown mating point: $id" }
        // Bolt: Use native Series.filter directly to avoid intermediate List allocations via .map { ... }.filter { ... }.toSeries()
        return copy(matingPoints = matingPoints.filter { it.id != id })
    }
}

data class LcncProgram(
    val name: String,
    val nodes: Series<LcncNode>,
    val wires: Series<LcncWire>,
    val controls: LcncConfixControls = LcncConfixControls(),
    /** Optional first-class Kanban orchestration graph persisted with this program. */
    val kanban: borg.trikeshed.kanban.KanbanGraph? = null,
    /** Persisted viewport (pan/zoom) — Kotlin owns the whole document. */
    val view: LcncView? = null,
    /** Frontend sequence counter — round-tripped so the browser's `n{seq}` ids never collide. */
    val seq: Int = 1,
) {
    companion object {
        val EMPTY: LcncProgram = LcncProgram("", emptySeriesOf(), emptySeriesOf())
    }
}

/** Wires whose target is [nodeId] — the inputs one node actually receives. */
fun LcncProgram.inputsOf(nodeId: String): Series<LcncWire> {
    val hits = ArrayList<LcncWire>()
    for (i in 0 until wires.size) if (wires[i].toNode == nodeId) hits.add(wires[i])
    return hits.toSeries()
}

class LcncCycleException(val nodeId: String) : Exception("cycle at $nodeId")

/**
 * Topological order over the wire DAG. A node with no matching entry in
 * [nodes] for a wire endpoint is skipped rather than crashing: a stale wire
 * from a since-deleted node must not break the whole program's execution
 * order.
 */
fun LcncProgram.topo(): Series<LcncNode> {
    val byId = LinkedHashMap<String, LcncNode>()
    for (i in 0 until nodes.size) byId[nodes[i].id] = nodes[i]

    val order = ArrayList<String>()
    val mark = HashMap<String, Char>() // 'p' = in progress, 't' = done

    fun visit(id: String) {
        if (mark[id] == 't') return
        if (mark[id] == 'p') throw LcncCycleException(id)
        if (id !in byId) return
        mark[id] = 'p'
        val ins = inputsOf(id)
        for (i in 0 until ins.size) visit(ins[i].fromNode)
        mark[id] = 't'
        order.add(id)
    }
    for (i in 0 until nodes.size) visit(nodes[i].id)
    return order.mapNotNull { byId[it] }.toSeries()
}
