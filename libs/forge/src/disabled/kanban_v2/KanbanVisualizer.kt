
@file:Suppress("unused")

package borg.trikeshed.forge.kanban

import borg.trikeshed.forge.kanban.BoardCard
import borg.trikeshed.forge.kanban.BoardColumn
import kotlinx.serialization.Serializable

@Serializable
data class KanbanRenderModel(
    val columns: List<ColumnRender>,
    val edges: List<CardEdge>,
    val stats: BoardStats,
) {
    @Serializable data class ColumnRender(
        val id: String, val name: String, val ordinalValue: Int, val color: String,
        val wipLimit: Int? = null, val cards: List<CardRender>,
    )
    @Serializable data class CardRender(
        val id: String, val title: String, val columnId: String, val assignee: String?,
        val priority: Int, val priorityColor: String, val logHandle: String, val tags: List<String>,
    )
    @Serializable data class CardEdge(val from: String, val to: String, val kind: String)
    @Serializable data class BoardStats(
        val totalCards: Int, val todo: Int, val doing: Int, val done: Int,
        val blocked: Int, val assigned: Int, val unassigned: Int,
    )
}

private val COLUMN_COLORS = mapOf(
    BoardColumn.TODO to "#fbbf24", BoardColumn.DOING to "#60a5fa",
    BoardColumn.DONE to "#4ade80", BoardColumn.BLOCKED to "#f87171",
)
private fun priorityColor(p: Int) = when { p >= 3 -> "#f87171"; p >= 2 -> "#fbbf24"; else -> "#4ade80" }

fun buildRenderModel(cards: List<BoardCard>): KanbanRenderModel {
    val columns = BoardColumn.entries.α { col ->
        KanbanRenderModel.ColumnRender(
            id = col.name.lowercase(), name = col.name, ordinalValue = col.ordinalValue,
            color = COLUMN_COLORS[col] ?: "#888888", wipLimit = null,
            cards = cards.view.filter { it.column == col }.α { c ->
                KanbanRenderModel.CardRender(c.id, c.title, col.name.lowercase(), c.assignee,
                    c.priority, priorityColor(c.priority), c.logHandle(), c.tags.toList())
            },
        )
    }
    val edges = cards.view.flatMap { card ->
        card.dependencies.α { dep -> KanbanRenderModel.CardEdge(dep, card.id, "blocks") }
    }
    val stats = KanbanRenderModel.BoardStats(
        cards.size,
        cards.view.count { it.column == BoardColumn.TODO },
        cards.view.count { it.column == BoardColumn.DOING },
        cards.view.count { it.column == BoardColumn.DONE },
        cards.view.count { it.column == BoardColumn.BLOCKED },
        cards.view.count { it.assignee != null },
        cards.view.count { it.assignee == null },
    )
    return KanbanRenderModel(columns, edges, stats)
}
}

fun KanbanRenderModel.toMermaid(): String = buildString {
    appendLine("graph LR")
    for (col in columns) {
        appendLine("  subgraph ${col.id} [${col.name}]")
        for (card in col.cards) appendLine("    ${card.id}${if (card.priority >= 3) "((${card.id}))" else "[${card.id}]"}")
        appendLine("  end")
    }
    for (edge in edges) appendLine("  ${edge.from} -->|${edge.kind}| ${edge.to}")
    for (col in columns) appendLine("  style ${col.id} fill:${col.color}22,stroke:${col.color},stroke-width:2px")
}

fun KanbanRenderModel.toDot(): String = buildString {
    appendLine("digraph kanban { rankdir=LR; node [shape=box,fontname=Helvetica];")
    for (col in columns) {
        appendLine("  subgraph cluster_${col.id} { label=\"${col.name}\"; style=filled; color=\"${col.color}22\"; fontcolor=\"${col.color}\";")
        for (card in col.cards) appendLine("    ${card.id} [label=\"${card.title}\\n${card.logHandle}\",color=\"${card.priorityColor}\"];")
        appendLine("  }")
    }
    for (edge in edges) appendLine("  ${edge.from} -> ${edge.to} [label=\"${edge.kind}\",style=bold,color=red];")
    appendLine("}")
}

fun KanbanRenderModel.toHtml(): String = buildString {
    appendLine("""<!DOCTYPE html><html><head><meta charset="utf-8"><style>
* { box-sizing:border-box;margin:0;padding:0 } body {font-family:-apple-system,sans-serif;background:#0f1117;color:#e6e6e6}
header {background:#1a1d27;padding:1rem 2rem;border-bottom:1px solid #2a2d3a}
header h1 {color:#7ee787;font-size:1.25rem} .stats {display:flex;gap:1.5rem;padding:1rem 2rem;border-bottom:1px solid #2a2d3a}
.stat {text-align:center} .stat .v {font-size:1.5rem;font-weight:700;color:#79c0ff}
.stat .l {font-size:0.65rem;color:#8b949e;text-transform:uppercase}
.board {display:grid;grid-template-columns:repeat(${columns.size},1fr);gap:1rem;padding:1rem 2rem}
.col {background:#1a1d27;border-radius:8px;border:1px solid #2a2d3a;overflow:hidden}
.col-h {padding:0.6rem 1rem;font-size:0.75rem;font-weight:600;text-transform:uppercase}
.col-c {padding:0.4rem;min-height:150px} .card {background:#0f1117;border:1px solid #2a2d3a;border-radius:4px;padding:0.4rem 0.6rem;margin-bottom:0.4rem;font-size:0.75rem}
.card-id {font-weight:600} .card-meta {font-size:0.65rem;color:#8b949e}
.dot {display:inline-block;width:6px;height:6px;border-radius:50%;margin-right:4px}
</style></head><body>""")
    appendLine("""<header><h1>Kanban -- TrikeShed Forge</h1></header>""")
    appendLine("""<div class="stats"><div class="stat"><div class="v">${stats.totalCards}</div><div class="l">Total</div></div><div class="stat"><div class="v">${stats.todo}</div><div class="l">Todo</div></div><div class="stat"><div class="v">${stats.doing}</div><div class="l">Doing</div></div><div class="stat"><div class="v">${stats.done}</div><div class="l">Done</div></div><div class="stat"><div class="v">${stats.blocked}</div><div class="l">Blocked</div></div></div>""")
    appendLine("""<div class="board">""")
    for (col in columns) {
        appendLine("""<div class="col"><div class="col-h" style="background:${col.color}22;color:${col.color}">${col.name} (${col.cards.size})</div><div class="col-c">""")
        for (card in col.cards) appendLine("""<div class="card"><span class="dot" style="background:${card.priorityColor}"></span><span class="card-id">${card.id}</span><div class="card-meta">${card.logHandle}${if (card.assignee != null) " &rarr; " + card.assignee else ""}</div></div>""")
        appendLine("""</div></div>""")
    }
    appendLine("""</div></body></html>""")
}
