package borg.trikeshed.kanban

import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.toList
import borg.trikeshed.parse.json.JsonSupport

/** Confix document for the orchestration graph; predicate bodies stay runtime-registered. */
object KanbanGraphConfix {
    fun toJson(graph: KanbanGraph): String = JsonSupport.stringify(linkedMapOf(
        "version" to 2,
        "boardId" to graph.boardId,
        "lanes" to graph.lanes.toList().map { lane -> linkedMapOf("id" to lane.id, "title" to lane.title, "order" to lane.order, "role" to lane.role, "inputs" to lane.inputs, "outputs" to lane.outputs) },
        "edges" to graph.edges.toList().map { edge -> linkedMapOf("id" to edge.id, "from" to edge.from, "to" to edge.to, "mode" to edge.mode.name, "group" to edge.group, "requiredBranches" to edge.requiredBranches, "maxIterations" to edge.maxIterations, "condition" to edge.condition?.let { mapOf("predicate" to it.predicate, "parameters" to it.parameters) }) },
        "cards" to graph.cards.toList().map { card -> linkedMapOf("id" to card.id, "owner" to card.owner, "lane" to card.lane, "state" to card.state, "revision" to card.revision, "io" to card.io, "effects" to card.effects.toList()) },
    ))

    fun fromJson(json: String): KanbanGraph {
        val root = JsonSupport.parse(json) as? Map<*, *> ?: error("graph document must be an object")
        fun map(v: Any?): Map<String, String> = (v as? Map<*, *>).orEmpty().mapNotNull { (k, value) -> k?.toString()?.let { it to (value?.toString() ?: "") } }.toMap()
        fun str(v: Any?, fallback: String = "") = v?.toString() ?: fallback
        fun integer(v: Any?) = v?.toString()?.toDoubleOrNull()?.toInt() ?: 0
        val lanes = (root["lanes"] as? List<*>).orEmpty().mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            KanbanLane(str(m["id"]), str(m["title"]), integer(m["order"]), str(m["role"]), map(m["inputs"]), map(m["outputs"]))
        }.toSeries()
        val edges = (root["edges"] as? List<*>).orEmpty().mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val c = (m["condition"] as? Map<*, *>)?.let { KanbanCondition(str(it["predicate"]), map(it["parameters"])) }
            KanbanEdge(str(m["id"]), str(m["from"]), str(m["to"]), c, runCatching { KanbanEdgeMode.valueOf(str(m["mode"], "DIRECT")) }.getOrDefault(KanbanEdgeMode.DIRECT), m["group"]?.toString(), integer(m["requiredBranches"]).coerceAtLeast(1), integer(m["maxIterations"]).coerceAtLeast(1))
        }.toSeries()
        val cards = (root["cards"] as? List<*>).orEmpty().mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val effects = (m["effects"] as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }.toSeries()
            KanbanCardState(str(m["id"]), str(m["owner"]), str(m["lane"]), str(m["state"]), m["revision"]?.toString()?.toDoubleOrNull()?.toLong() ?: 0L, m["io"] as? Map<String, Any?> ?: emptyMap(), effects)
        }.toSeries()
        return KanbanGraph(str(root["boardId"]), lanes, edges, cards)
    }
}
