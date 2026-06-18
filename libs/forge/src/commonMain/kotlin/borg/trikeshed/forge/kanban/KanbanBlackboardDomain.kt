@file:Suppress("unused")

package borg.trikeshed.forge.kanban

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.s_
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.cursor.CellOverlay
import borg.trikeshed.cursor.ColumnOverlay
import borg.trikeshed.cursor.OverlayRole
import borg.trikeshed.cursor.Provenance
import borg.trikeshed.cursor.Evidence
import borg.trikeshed.cursor.DependencyHandle
import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.cursor.columnOverlay
import borg.trikeshed.cursor.evidence
import borg.trikeshed.cursor.provenance
import borg.trikeshed.forge.platform.platformUtils

object KanbanBlackboardDomain {
    object Cols {
        const val ID = 0
        const val TITLE = 1
        const val COLUMN = 2
        const val ASSIGNEE = 3
        const val PRIORITY = 4
        const val DEPS = 5
        const val EVIDENCE = 6
        const val PROVENANCE = 7
    }

    val columnSchema: Map<Int, ColumnOverlay> = mapOf(
        Cols.ID to columnOverlay(name = "id", defaultRole = OverlayRole.CONTROL, description = "Card identity (stable handle)"),
        Cols.TITLE to columnOverlay(name = "title", defaultRole = OverlayRole.OBSERVATION, description = "Human-readable card title"),
        Cols.COLUMN to columnOverlay(name = "column", defaultRole = OverlayRole.CONTROL, constraints = listOf("IntEnum: TODO=0, DOING=1, DONE=2, BLOCKED=3"), description = "Board column as integer enum"),
        Cols.ASSIGNEE to columnOverlay(name = "assignee", defaultRole = OverlayRole.METADATA, description = "Agent or profile assigned"),
        Cols.PRIORITY to columnOverlay(name = "priority", defaultRole = OverlayRole.CONTROL, description = "Dispatch priority weight"),
        Cols.DEPS to columnOverlay(name = "deps", defaultRole = OverlayRole.PROVENANCE, description = "Blocking dependencies"),
        Cols.EVIDENCE to columnOverlay(name = "evidence", defaultRole = OverlayRole.HYPOTHESIS, description = "Confidence metrics from runs"),
        Cols.PROVENANCE to columnOverlay(name = "provenance", defaultRole = OverlayRole.PROVENANCE, description = "Audit trail"),
    )

    fun cardToContext(card: BoardCard): BlackboardContext {
        val now = platformUtils.currentTimeMillis()
        return blackboardContext(
            id = "kanban:${card.id}",
            columnOverlays = columnSchema,
            provenance = provenance(source = "kanban:${card.column.name.lowercase()}", timestamp = now, creator = card.assignee),
            tags = mapOf("domain" to "kanban", "column" to card.column.name.lowercase(), "logHandle" to card.logHandle()),
        )
    }

    fun cardToRow(card: BoardCard): List<CellOverlay<*>> {
        val now = platformUtils.currentTimeMillis()
        val prov = provenance(source = "kanban:${card.id}", timestamp = now)
        val ev = when (card.column) {
            BoardColumn.DONE -> evidence(confidence = 1.0, supportCount = 1)
            BoardColumn.BLOCKED -> evidence(confidence = 0.0, supportCount = 0)
            else -> null
        }
        return listOf(
            CellOverlay(card.id, OverlayRole.CONTROL, prov),
            CellOverlay(card.title, OverlayRole.OBSERVATION, prov),
            CellOverlay(card.column.ordinalValue, OverlayRole.CONTROL, prov, description = card.column.logHandle()),
            CellOverlay(card.assignee ?: "", OverlayRole.METADATA, prov),
            CellOverlay(card.priority, OverlayRole.CONTROL, prov),
            CellOverlay(card.dependencies, OverlayRole.PROVENANCE, prov),
            CellOverlay(ev, OverlayRole.HYPOTHESIS, prov),
            CellOverlay(card.tags, OverlayRole.PROVENANCE, prov),
        )
    }

    fun boardToContexts(cards: List<BoardCard>) = cards.map { cardToContext(it) }
    fun boardToRows(cards: List<BoardCard>) = cards.map { cardToRow(it) }

    fun dependencyHandles(cards: List<BoardCard>): List<Join<String, String>> {
        val handles = mutableListOf<Join<String, String>>()
        val idIndex = cards.mapIndexed { i, c -> c.id to i }.toMap()
        for (card in cards) for (dep in card.dependencies) if (idIndex.contains(dep)) handles.add(dep j card.id)
        return handles
    }
}

interface BlackboardDomain {
    val domainId: String
    val columnSchema: Map<Int, ColumnOverlay>
    fun toContext(card: BoardCard): BlackboardContext
    fun toRow(card: BoardCard): List<CellOverlay<*>>
}

object KanbanDomain : BlackboardDomain {
    override val domainId = "kanban"
    override val columnSchema = KanbanBlackboardDomain.columnSchema
    override fun toContext(card: BoardCard) = KanbanBlackboardDomain.cardToContext(card)
    override fun toRow(card: BoardCard) = KanbanBlackboardDomain.cardToRow(card)
}
