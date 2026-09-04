package borg.trikeshed.kanban

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 8 gate, W6.6: ONE column vocabulary. The two producers that used to
 * ship incompatible hardcoded lists (ForgeKanbanIngest's seven, ForgeBoardFSM
 .loadDefault's four) now render from BoardCol.entries via columns() — this
 * test pins them together so they cannot drift again.
 */
class BoardVocabularyConsistencyTest {

    @Test
    fun boardColColumnsMatchTheClosedVocabularyExactly() {
        val cols = BoardCol.columns()
        assertEquals(BoardCol.entries.size, cols.size, "every BoardCol renders exactly one KanbanColumn")
        // columns() renders in BoardCol.order, not declaration order: REVIEW is
        // declared last (its ColId must not shift DONE/ARCHIVED) but sits before DONE.
        for ((i, col) in cols.withIndex()) {
            val entry = BoardCol.rendered[i]
            assertEquals(entry.wire, col.id.value, "wire id is the canonical string")
            assertEquals(entry.order, col.order, "order matches render position")
            assertEquals(entry.wipLimit, col.wipLimit, "wip limits carry through (RUNNING=3)")
        }
    }

    @Test
    fun ingestColumnsAreFoldedOntoBoardCol() {
        // The seven-string list historically duplicated in ForgeKanbanIngest.
        val expectedWires = BoardCol.entries.map { it.wire }.toSet()
        val inCols = invokeIngestColumns()
        assertEquals(expectedWires, inCols, "ForgeKanbanIngest renders BoardCol.entries")
    }

    @Test
    fun loadDefaultBoardUsesOnlyCanonicalColumnIds() {
        ForgeBoardFSM.reset()
        try {
            ForgeBoardFSM.loadDefault()
            val state = ForgeBoardFSM.current()
            val board = state.boards.values.firstOrNull()
                ?: throw AssertionError("loadDefault must produce a board")
            val ids = board.columns.map { it.id.value }.toSet()
            assertEquals(BoardCol.entries.map { it.wire }.toSet(), ids,
                "the default board's columns ARE the closed vocabulary")
            // Cards land only on existing columns:
            for (card in board.cards) {
                assertTrue(board.columns.any { col -> col.id == card.columnId },
                    "card ${card.id.value} references a real column")
            }
        } finally {
            ForgeBoardFSM.reset()
        }
    }

    /** ForgeKanbanIngest.columns is private; read it reflectively to pin its content. */
    private fun invokeIngestColumns(): Set<String> {
        val field = ForgeKanbanIngest::class.java.getDeclaredField("columns")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cols = field.get(null) as List<KanbanColumn>
        return cols.map { it.id.value }.toSet()
    }
}
