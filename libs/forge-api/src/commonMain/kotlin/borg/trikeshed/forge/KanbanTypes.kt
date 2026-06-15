package borg.trikeshed.forge

import kotlinx.serialization.Serializable

/**
 * Kanban board — task visualization backed by Forge workspace.
 * 
 * Maps to CascadeGraph for graphviz/HTML rendering:
 * - Columns → SOURCE/SINK nodes
 * - Cards → intermediate nodes
 * - Dependencies → edges
 * - Swimlanes → color-coded edge groups
 */
@Serializable
data class KanbanBoard(
    val id: KanbanBoardId,
    val name: String,
    val columns: List<KanbanColumn>,
    val cards: List<KanbanCard>,
    val swimlanes: List<Swimlane> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
@JvmInline
value class KanbanBoardId(val value: String) {
    companion object {
        fun generate(): KanbanBoardId = KanbanBoardId(java.util.UUID.randomUUID().toString())
    }
}

@Serializable
data class KanbanColumn(
    val id: KanbanColumnId,
    val name: String,
    val order: Int,
    val wipLimit: Int? = null,  // Work-in-progress limit
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
@JvmInline
value class KanbanColumnId(val value: String) {
    companion object {
        fun generate(): KanbanColumnId = KanbanColumnId(java.util.UUID.randomUUID().toString())
    }
}

@Serializable
data class KanbanCard(
    val id: KanbanCardId,
    val title: String,
    val description: String = "",
    val columnId: KanbanColumnId,
    val order: Int = 0,
    val assignee: String? = null,
    val priority: CardPriority = CardPriority.MEDIUM,
    val dependencies: List<KanbanCardId> = emptyList(),  // blocks other cards
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
@JvmInline
value class KanbanCardId(val value: String) {
    companion object {
        fun generate(): KanbanCardId = KanbanCardId(java.util.UUID.randomUUID().toString())
    }
}

@Serializable
enum class CardPriority { LOW, MEDIUM, HIGH, CRITICAL }

@Serializable
data class Swimlane(
    val id: SwimlaneId,
    val name: String,
    val color: String,  // hex color
    val cardIds: List<KanbanCardId> = emptyList(),
)

@Serializable
@JvmInline
value class SwimlaneId(val value: String) {
    companion object {
        fun generate(): SwimlaneId = SwimlaneId(java.util.UUID.randomUUID().toString())
    }
}

/**
 * Convert KanbanBoard to CascadeGraph for visualization.
 */
fun KanbanBoard.toCascadeGraph(): CascadeGraph {
    val nodes = mutableListOf<CascadeNode>()
    val edges = mutableListOf<CascadeEdge>()
    
    // Column nodes as SOURCE/SINK
    columns.forEach { col ->
        nodes.add(CascadeNode(
            id = col.id.value,
            type = CascadeStageType.SOURCE,
            label = col.name,
            config = mapOf("wipLimit" to (col.wipLimit?.toString() ?: "unlimited")),
        ))
    }
    
    // Card nodes as intermediate stages
    cards.forEach { card ->
        val stageType = when (card.priority) {
            CardPriority.CRITICAL -> CascadeStageType.MAP  // highlighted
            CardPriority.HIGH -> CascadeStageType.MAP
            else -> CascadeStageType.FILTER
        }
        nodes.add(CascadeNode(
            id = card.id.value,
            type = stageType,
            label = card.title,
            config = mapOf(
                "column" to card.columnId.value,
                "priority" to card.priority.name,
                "assignee" to (card.assignee ?: ""),
            ),
        ))
        
        // Edges: card -> column
        edges.add(CascadeEdge(
            from = card.id.value,
            to = card.columnId.value,
            dataFlow = "belongs-to",
        ))
        
        // Edges: dependencies
        card.dependencies.forEach { depId ->
            edges.add(CascadeEdge(
                from = depId.value,
                to = card.id.value,
                dataFlow = "blocks",
            ))
        }
    }
    
    return CascadeGraph(
        cascadeId = CascadeId(id.value),
        nodes = nodes,
        edges = edges,
    )
}

/**
 * Render KanbanBoard as Mermaid diagram.
 */
fun KanbanBoard.toMermaid(): String {
    val graph = toCascadeGraph()
    return buildString {
        appendLine("graph LR")
        graph.nodes.forEach { node ->
            val shape = when (node.type) {
                CascadeStageType.SOURCE -> "[${node.label}]"
                CascadeStageType.SINK -> "]${node.label}["
                else -> "(${node.label})"
            }
            appendLine("  ${node.id}$shape")
        }
        graph.edges.forEach { edge ->
            val arrow = when (edge.dataFlow) {
                "blocks" -> "-->|blocked|"
                else -> "-->"
            }
            appendLine("  ${edge.from} ${arrow} ${edge.to}")
        }
    }
}

/**
 * Render KanbanBoard as Graphviz DOT.
 */
fun KanbanBoard.toDot(): String {
    val graph = toCascadeGraph()
    return buildString {
        appendLine("digraph ${id.value} {")
        appendLine("  rankdir=LR;")
        appendLine("  node [shape=box];")
        
        graph.nodes.forEach { node ->
            val style = when (node.type) {
                CascadeStageType.SOURCE -> "shape=doubleoctagon,"
                CascadeStageType.SINK -> "shape=doublecircle,"
                else -> ""
            }
            val priority = node.config["priority"]
            val color = when (priority) {
                "CRITICAL" -> "red"
                "HIGH" -> "orange"
                else -> "black"
            }
            appendLine("  ${node.id} [${style}label=\"${node.label}\",color=$color];")
        }
        
        graph.edges.forEach { edge ->
            val style = when (edge.dataFlow) {
                "blocks" -> "style=bold,color=red,"
                else -> ""
            }
            appendLine("  ${edge.from} -> ${edge.to} [${style}label=\"${edge.dataFlow}\";")
        }
        appendLine("}")
    }
}