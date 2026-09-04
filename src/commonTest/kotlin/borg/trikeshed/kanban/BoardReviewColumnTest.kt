package borg.trikeshed.kanban

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The REVIEW column: appended LAST in the enum so every ColId the WAL already
 * holds keeps meaning what it meant, rendered by `order` so it sits where a
 * reader expects it (between blocked and done).
 */
class BoardReviewColumnTest {

    @Test
    fun persistedColIdsAreStable() {
        // The seven ordinals the WAL persisted before REVIEW existed — frozen.
        assertEquals(0, BoardCol.TRIAGE.id.value.toInt())
        assertEquals(1, BoardCol.TODO.id.value.toInt())
        assertEquals(2, BoardCol.READY.id.value.toInt())
        assertEquals(3, BoardCol.RUNNING.id.value.toInt())
        assertEquals(4, BoardCol.BLOCKED.id.value.toInt())
        assertEquals(5, BoardCol.DONE.id.value.toInt(), "DONE's ColId must never shift")
        assertEquals(6, BoardCol.ARCHIVED.id.value.toInt(), "ARCHIVED's ColId must never shift")
        assertEquals(7, BoardCol.REVIEW.ordinal, "REVIEW is the eighth entry, appended last")
        assertEquals(BoardCol.REVIEW, BoardCol.fromId(ColId(7)))
        assertEquals(BoardCol.DONE, BoardCol.fromId(ColId(5)))
    }

    @Test
    fun renderOrderPutsReviewBetweenBlockedAndDone() {
        val wires = BoardCol.rendered.map { it.wire }
        assertEquals(listOf("triage", "todo", "ready", "running", "blocked", "review", "done", "archived"), wires)
        assertEquals(wires, BoardCol.columns().map { it.id.value }, "columns() renders in order, not declaration")
        assertEquals(5, BoardCol.REVIEW.order)
        assertEquals(6, BoardCol.DONE.order)
        assertEquals(7, BoardCol.ARCHIVED.order)
        // orders are a permutation: no two columns share a slot
        assertEquals(BoardCol.entries.size, BoardCol.entries.map { it.order }.toSet().size)
    }

    @Test
    fun boardMapListsColumnsInRenderOrder() {
        val rows = listOf(
            CardRow("d", "d", BoardCol.DONE, 1, 1, 0, 2, 0, emptyList(), emptyList()),
            CardRow("r", "r", BoardCol.REVIEW, 1, 2, 0, 2, 0, emptyList(), emptyList()),
            CardRow("t", "t", BoardCol.TRIAGE, 1, 3, 0, 2, 0, emptyList(), emptyList()),
        )
        val map = BoardCursor.of(rows).toBoardMap(3)
        val columns = (map["columns"] as List<*>).map { (it as Map<*, *>)["id"] }
        assertEquals(BoardCol.rendered.map { it.wire }, columns)
        // the SoA freeze sorts by render order too: triage, review, done
        val items = (map["items"] as List<*>).map { (it as Map<*, *>)["id"] }
        assertEquals(listOf("t", "r", "d"), items)
        val counts = (map["columns"] as List<*>).associate { (it as Map<*, *>)["id"] to it["count"] }
        assertEquals(1, counts["review"])
        assertEquals(1, counts["done"])
    }

    @Test
    fun reviewIsInProgressToTheStatusFold() {
        assertEquals("in-progress", BoardCol.statusFor("review"))
        assertEquals("in-progress", BoardCol.statusFor("running"))
        assertEquals("done", BoardCol.statusFor("done"))
        assertEquals(BoardCol.REVIEW, BoardCol.fromWire("review"))
        assertEquals(BoardCol.REVIEW, BoardCol.legacyCol("review"))
        assertTrue(BoardCol.REVIEW.wipLimit == null, "review has no WIP limit: a human drains it")
    }
}
