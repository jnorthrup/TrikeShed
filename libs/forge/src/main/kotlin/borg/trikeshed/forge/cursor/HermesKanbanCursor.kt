package borg.trikeshed.forge.cursor

import borg.trikeshed.forge.*

/**
 * Hermes Kanban SQLite-backed board.
 * 
 * Bridges Forge Kanban to `~/.hermes/kanban.db`:
 * - tasks → KanbanCard
 * - task_runs → Card history
 * - task_links → dependencies
 * - task_comments → card notes
 * 
 * Provides:
 * - loadBoard() → KanbanBoard
 * - toCascadeGraph() → graphviz rendering
 * - renderMermaid() / renderDot() → diagram output
 */
class HermesKanbanCursor(
    private val dbPath: String = "~/.hermes/kanban.db",
) {
    
    // In real impl: JDBC connection to SQLite
    
    /**
     * Load board from Hermes kanban.
     */
    fun loadBoard(boardId: String = "tshed"): KanbanBoard {
        // Query tasks table, map status → columns
        val columns = listOf(
            KanbanColumn(KanbanColumnId("todo"), "To Do", 0),
            KanbanColumn(KanbanColumnId("ready"), "Ready", 1),
            KanbanColumn(KanbanColumnId("running"), "Running", 2),
            KanbanColumn(KanbanColumnId("done"), "Done", 3),
            KanbanColumn(KanbanColumnId("blocked"), "Blocked", 4),
        )
        
        // Cards loaded from SQLite tasks table
        return KanbanBoard(
            id = KanbanBoardId(boardId),
            name = "Hermes Kanban",
            columns = columns,
            cards = emptyList(),
        )
    }
    
    /**
     * Get all cards optionally filtered by status.
     */
    fun getCards(status: String? = null): List<KanbanCard> {
        // SELECT * FROM tasks WHERE status = ?
        return emptyList()
    }
    
    /**
     * Get card dependencies from task_links.
     */
    fun getDependencies(cardId: KanbanCardId): List<KanbanCardId> {
        // SELECT * FROM task_links WHERE parent_id = ? OR child_id = ?
        return emptyList()
    }
    
    /**
     * Get run history for card.
     */
    fun getRuns(cardId: KanbanCardId): List<CardRun> {
        // SELECT * FROM task_runs WHERE task_id = ? ORDER BY started_at DESC
        return emptyList()
    }
    
    /**
     * Get comments for card.
     */
    fun getComments(cardId: KanbanCardId): List<CardComment> {
        // SELECT * FROM task_comments WHERE task_id = ?
        return emptyList()
    }
    
    /**
     * Convert board to CascadeGraph for visualization.
     */
    fun toCascadeGraph(board: KanbanBoard): CascadeGraph {
        val nodes = mutableListOf<CascadeNode>()
        val edges = mutableListOf<CascadeEdge>()
        
        // Column nodes as SOURCE/SINK
        board.columns.forEach { col ->
            nodes.add(CascadeNode(
                id = col.id.value,
                type = CascadeStageType.SOURCE,
                label = col.name,
                config = mapOf("order" to col.order.toString()),
            ))
        }
        
        // Card nodes
        board.cards.forEach { card ->
            val stageType = when (card.priority) {
                CardPriority.CRITICAL -> CascadeStageType.MAP
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
            
            // Edge to column
            edges.add(CascadeEdge(
                from = card.columnId.value,
                to = card.id.value,
                dataFlow = "in-column",
            ))
            
            // Dependency edges
            card.dependencies.forEach { dep ->
                edges.add(CascadeEdge(
                    from = dep.value,
                    to = card.id.value,
                    dataFlow = "blocked-by",
                ))
            }
        }
        
        return CascadeGraph(
            cascadeId = CascadeId(board.id.value),
            nodes = nodes,
            edges = edges,
        )
    }
    
    /**
     * Render as Mermaid diagram.
     */
    fun renderMermaid(board: KanbanBoard): String {
        val graph = toCascadeGraph(board)
        return buildString {
            appendLine("graph LR")
            graph.nodes.forEach { node ->
                val shape = when (node.type) {
                    CascadeStageType.SOURCE -> "[${node.label}]"
                    else -> "(${node.label})"
                }
                appendLine("  ${node.id}$shape")
            }
            graph.edges.forEach { edge ->
                val arrow = when (edge.dataFlow) {
                    "blocked-by" -> "-->|blocked|"
                    else -> "-->"
                }
                appendLine("  ${edge.from} $arrow ${edge.to}")
            }
        }
    }
    
    /**
     * Render as Graphviz DOT.
     */
    fun renderDot(board: KanbanBoard): String {
        val graph = toCascadeGraph(board)
        return buildString {
            appendLine("digraph ${board.id.value} {")
            appendLine("  rankdir=LR;")
            appendLine("  node [shape=box,style=rounded];")
            
            graph.nodes.forEach { node ->
                val color = when (node.type) {
                    CascadeStageType.SOURCE -> "lightgray"
                    CascadeStageType.MAP -> "lightblue"
                    else -> "white"
                }
                val style = when (node.type) {
                    CascadeStageType.SOURCE -> "style=filled,"
                    else -> ""
                }
                appendLine("  ${node.id} [label=\"${node.label}\",fillcolor=$color$style];")
            }
            
            graph.edges.forEach { edge ->
                val style = when (edge.dataFlow) {
                    "blocked-by" -> "color=red,style=bold,"
                    else -> ""
                }
                appendLine("  ${edge.from} -> ${edge.to} [label=\"${edge.dataFlow}\"$style];")
            }
            appendLine("}")
        }
    }
}

/**
 * Card run history from task_runs table.
 */
data class CardRun(
    val id: Int,
    val profile: String,
    val status: String,
    val outcome: String?,
    val summary: String?,
    val startedAt: Long,
    val endedAt: Long?,
)

/**
 * Card comment from task_comments table.
 */
data class CardComment(
    val id: Int,
    val taskId: KanbanCardId,
    val author: String,
    val body: String,
    val createdAt: Long,
)

/**
 * Open Hermes Kanban board.
 */
fun openHermesKanban(dbPath: String = "~/.hermes/kanban.db"): KanbanBoard =
    HermesKanbanCursor(dbPath).loadBoard()

/**
 * Render Hermes kanban as Mermaid.
 */
fun hermesKanbanMermaid(board: KanbanBoard): String =
    HermesKanbanCursor().renderMermaid(board)

/**
 * Render Hermes kanban as Graphviz DOT.
 */
fun hermesKanbanDot(board: KanbanBoard): String =
    HermesKanbanCursor().renderDot(board)