package borg.trikeshed.graph

import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.kanban.KanbanBoardId
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumn
import borg.trikeshed.kanban.KanbanColumnId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

private val CAUSAL_READY_COLUMN = KanbanColumnId("col-causal-ready")
private val CAUSAL_BLOCKED_COLUMN = KanbanColumnId("col-causal-blocked")
private val AGENTIC_WORK_COLUMN = KanbanColumnId("col-agentic")

/**
 * Project deterministic causal graph nodes into a visual kanban preflight board.
 *
 * This is intentionally a projection, not a second source of truth: the graph
 * index owns causal identity; kanban owns the work-facing visual surface.
 */
fun CausalGraphNodeIndex.toKanbanBoard(
    blackboardId: String,
    name: String = "Causal preflight: $blackboardId",
): KanbanBoard {
    val positions = byBlackboard(blackboardId)
    val selected = (0 until positions.size)
        .map { this[positions[it]] }
        .sortedWith(compareBy<CausalGraphNode> { it.topoOrdinal }.thenBy { it.nodeId })
    val selectedIds = selected.map { it.nodeId }.toSet()

    val cards = selected.mapIndexed { order, node ->
        val knownParents = node.parentNodeIds.filter { it in selectedIds }
        KanbanCard(
            id = KanbanCardId(node.nodeId),
            title = node.nodeId,
            description = "${node.opId}@${node.opVersion}",
            columnId = if (knownParents.isEmpty()) CAUSAL_READY_COLUMN else CAUSAL_BLOCKED_COLUMN,
            order = order,
            priority = if (knownParents.isEmpty()) CardPriority.HIGH else CardPriority.MEDIUM,
            dependencies = knownParents.map(::KanbanCardId),
            tags = setOf("causal-node", node.opId),
            metadata = mapOf(
                "blackboardId" to node.blackboard.id,
                "causalKey" to node.causalKey,
                "opId" to node.opId,
                "opVersion" to node.opVersion,
                "topoOrdinal" to node.topoOrdinal.toString(),
            ),
        )
    }

    return KanbanBoard(
        id = KanbanBoardId(blackboardId),
        name = name,
        columns = listOf(
            KanbanColumn(CAUSAL_READY_COLUMN, "Causal Ready", 0, metadata = mapOf("phase" to "pre-agent")),
            KanbanColumn(CAUSAL_BLOCKED_COLUMN, "Causal Blocked", 1, metadata = mapOf("phase" to "pre-agent")),
            KanbanColumn(AGENTIC_WORK_COLUMN, "Agentic Work", 2, metadata = mapOf("phase" to "agent")),
        ),
        cards = cards,
        metadata = mapOf("blackboardId" to blackboardId, "source" to "CausalGraphNodeIndex"),
    )
}
