package borg.trikeshed.miniduck

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.getValue
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Algebraic composition tests for CursorOps.
 *
 * Verifies: take, drop, where, project, orderBy, columns, minus, then
 * all compose correctly over MiniCursor with DocRowVec rows.
 */
class CursorOpsCompositionTest {

    /** Build a 5-row cursor with keys "name" and "age". */
    private fun sampleCursor(): Cursor = 5 j { i ->
        DocRowVec(
            keys = listOf("name", "age"),
            cells = listOf(names[i], ages[i]),
            child = null,
        ).toRowVec()
    }

    companion object {
        private val names = listOf("alice", "bob", "carol", "dave", "eve")
        private val ages  = listOf(30, 25, 35, 25, 40)
    }

    @Test
    fun takeReturnsFirstN() {
        val c = sampleCursor().take(3)
        assertEquals(3, c.size)
        assertEquals("alice", (c at 0).getValue("name"))
        assertEquals("carol", (c at 2).getValue("name"))
    }

    @Test
    fun takeBeyondSizeClamps() {
        val c = sampleCursor().take(100)
        assertEquals(5, c.size)
    }

    @Test
    fun dropSkipsFirstN() {
        val c = sampleCursor().drop(2)
        assertEquals(3, c.size)
        assertEquals("carol", (c at 0).getValue("name"))
        assertEquals("eve", (c at 2).getValue("name"))
    }

    @Test
    fun dropBeyondSizeReturnsEmpty() {
        val c = sampleCursor().drop(10)
        assertEquals(0, c.size)
    }

    @Test
    fun whereFiltersByEqPredicate() {
        val c = sampleCursor().where(Eq("age", 25))
        assertEquals(2, c.size)
        assertEquals("bob", (c at 0).getValue("name"))
        assertEquals("dave", (c at 1).getValue("name"))
    }

    @Test
    fun projectSelectsColumns() {
        val c = sampleCursor().project("name")
        assertEquals(5, c.size)
        val row0 = c at 0
        assertEquals("alice", row0.getValue("name"))
        assertEquals(listOf("name"), (row0 as DocRowVec).keys.toList())
    }

    @Test
    fun orderByAscending() {
        val c = sampleCursor().orderBy("age")
        assertEquals(5, c.size)
        assertEquals("bob", (c at 0).getValue("name"))   // age 25
        assertEquals("dave", (c at 1).getValue("name"))  // age 25
        assertEquals("eve", (c at 4).getValue("name"))   // age 40
    }

    @Test
    fun orderByDescending() {
        val c = sampleCursor().orderBy(OrderSpec("age", desc = true))
        assertEquals(5, c.size)
        assertEquals("eve", (c at 0).getValue("name"))   // age 40
    }

    @Test
    fun minusDropsColumn() {
        val c = sampleCursor() - "age"
        assertEquals(5, c.size)
        val row0 = c at 0
        assertEquals(listOf("name"), (row0 as DocRowVec).keys.toList())
    }

    @Test
    fun takeThenDrop() {
        val c = sampleCursor().take(4).drop(1)
        assertEquals(3, c.size)
        assertEquals("bob", (c at 0).getValue("name"))
    }

    @Test
    fun whereThenOrderBy() {
        val c = sampleCursor()
            .where(Ge("age", 25))
            .orderBy("name")
        assertEquals(5, c.size)
        assertEquals("alice", (c at 0).getValue("name"))
        assertEquals("eve", (c at 4).getValue("name"))
    }

    @Test
    fun thenChainsTransforms() {
        val c = sampleCursor() then { it.take(3) } then { it.drop(1) }
        assertEquals(2, c.size)
        assertEquals("bob", (c at 0).getValue("name"))
    }

    @Test
    fun negativeIndexAt() {
        val c = sampleCursor()
        assertEquals("eve", (c at -1).getValue("name"))
        assertEquals("dave", (c at -2).getValue("name"))
    }

    @Test
    fun emptyCursorStaysEmpty() {
        val c = emptyCursor()
        assertEquals(0, c.size)
        val taken = c.take(5)
        assertEquals(0, taken.size)
    }

    @Test
    fun columnsProjection() {
        val c = sampleCursor().columns(0) // project first column "name"
        assertEquals(5, c.size)
        val row0 = c at 0
        assertEquals(1, (row0 as DocRowVec).keys.size)
    }
}
