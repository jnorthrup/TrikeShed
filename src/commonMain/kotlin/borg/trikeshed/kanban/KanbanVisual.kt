package borg.trikeshed.kanban

/** Render a kanban board as a Mermaid dependency graph grouped by columns. */
fun KanbanBoard.toMermaidCausal(): String = buildString {
    appendLine("flowchart LR")
    val columnsById = columns.sortedBy { it.order }
    for (column in columnsById) {
        appendLine("  subgraph ${mermaidId(column.id.value)}[\"${mermaidLabel(column.name)}\"]")
        for (card in cardsInColumn(column.id)) {
            appendLine("    ${cardMermaidId(card.id)}[\"${mermaidLabel(card.title)}\"]")
        }
        appendLine("  end")
    }
    for (card in cards.sortedBy { it.order }) {
        for (parent in card.dependencies) {
            if (cards.any { it.id == parent }) {
                appendLine("  ${cardMermaidId(parent)} --> ${cardMermaidId(card.id)}")
            }
        }
    }
}

private fun cardMermaidId(id: KanbanCardId): String = "card_${mermaidId(id.value)}"

private fun mermaidId(raw: String): String = buildString(raw.length + 1) {
    if (raw.isNotEmpty() && raw[0].isDigit()) append('_')
    for (i in 0 until raw.length) {
        val ch = raw[i]
        append(if (ch.isLetterOrDigit()) ch else '_')
    }
}

private fun mermaidLabel(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"")
