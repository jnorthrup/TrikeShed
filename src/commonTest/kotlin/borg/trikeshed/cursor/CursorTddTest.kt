package borg.trikeshed.cursor

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * TDD tests for Cursor functions.
 * These tests define the expected API behavior — implementation follows.
 */
class CursorTddTest {

    // Helper: build a simple cursor from a lambda
    private fun makeCursor(rows: Int, fn: (Int) -> RowVec): Cursor =
        Series(rows) { fn(it) }

    // Helper: build a RowVec from list of key-value pairs with IoDouble meta
    private fun rowVecOf(vararg pairs: Pair<String, Any?>): RowVec {
        val keys = pairs.map { it.first }
        val vals = pairs.map { it.second }
        @Suppress("UNCHECKED_CAST")
        val cells = vals.toSeries() as Series<Any?>
        val metas = pairs.map { IOMemento.IoDouble.`↻` }
        @Suppress("UNCHECKED_CAST")
        val keysSeries = keys.toSeries() as Series<String>
        return cellsToRowVec(cells, keysSeries)
    }

    // Helper: make a cursor from rows
    private fun cursorOf(vararg rows: RowVec): Cursor =
        Series(rows.size) { rows[it] }

    // ── where ─────────────────────────────────────────────────────────────────

    @Test fun whereFiltersRowsByPredicate() {
        val c = makeCursor(3) { i ->
            rowVecOf("n" to i, "v" to (i * 10))
        }
        val result = c.where { r -> r.longValue("v") > 10 }
        assertEquals(1, result.size)
        assertEquals(20, result.at(0).longValue("v"))
    }

    @Test fun whereReturnsEmptyWhenNothingMatches() {
        val c = makeCursor(3) { i -> rowVecOf("n" to i) }
        assertEquals(0, c.where { r -> r.longValue("n") > 100 }.size)
    }

    @Test fun wherePreservesWhenAllMatch() {
        val c = makeCursor(3) { i -> rowVecOf("n" to i) }
        assertEquals(3, c.where { true }.size)
    }

    // ── take / drop ────────────────────────────────────────────────────────────

    @Test fun takeReturnsFirstNRows() {
        val c = makeCursor(5) { i -> rowVecOf("x" to i) }
        val result = c.take(2)
        assertEquals(2, result.size)
        assertEquals(0, result.at(0).longValue("x"))
        assertEquals(1, result.at(1).longValue("x"))
    }

    @Test fun takeReturnsAllWhenNExceedsSize() {
        val c = makeCursor(2) { i -> rowVecOf("x" to i) }
        assertEquals(2, c.take(100).size)
    }

    @Test fun dropSkipsFirstNRows() {
        val c = makeCursor(5) { i -> rowVecOf("x" to i) }
        val result = c.drop(2)
        assertEquals(3, result.size)
        assertEquals(2, result.at(0).longValue("x"))
        assertEquals(4, result.at(2).longValue("x"))
    }

    @Test fun dropReturnsEmptyWhenNEqualsSize() {
        val c = makeCursor(3) { i -> rowVecOf("x" to i) }
        assertEquals(0, c.drop(3).size)
    }

    @Test fun dropReturnsAllWhenNIsZero() {
        val c = makeCursor(3) { i -> rowVecOf("x" to i) }
        assertEquals(3, c.drop(0).size)
    }

    // ── orderBy ───────────────────────────────────────────────────────────────

    @Test fun orderBySortsAscending() {
        val c = makeCursor(3) { i ->
            rowVecOf("v" to (3 - i)) // 3, 2, 1
        }
        val result = c.orderBy("v")
        assertEquals(3, result.size)
        assertEquals(1, result.at(0).longValue("v"))
        assertEquals(2, result.at(1).longValue("v"))
        assertEquals(3, result.at(2).longValue("v"))
    }

    @Test fun orderBySortsDescending() {
        val c = makeCursor(3) { i ->
            rowVecOf("v" to i) // 0, 1, 2
        }
        val result = c.orderBy("v", desc = true)
        assertEquals(2, result.at(0).longValue("v"))
        assertEquals(0, result.at(2).longValue("v"))
    }

    // ── project ───────────────────────────────────────────────────────────────

    @Test fun projectSelectsNamedColumns() {
        val c = makeCursor(1) { rowVecOf("a" to 1, "b" to 2, "c" to 3) }
        val result = c.project("a", "c")
        assertEquals(2, result.at(0).size)
        assertEquals(1, result.at(0).getValue("a"))
        assertNull(result.at(0).getValue("b"))
        assertEquals(3, result.at(0).getValue("c"))
    }

    // ── RowVec.getValue ─────────────────────────────────────────────────────────

    @Test fun getValueReturnsValueByColumnName() {
        val rv = rowVecOf("name" to "Alice", "age" to 30)
        assertEquals("Alice", rv.getValue("name"))
        assertEquals(30, rv.getValue("age"))
    }

    @Test fun getValueReturnsNullForUnknownColumn() {
        val rv = rowVecOf("x" to 1)
        assertNull(rv.getValue("unknown"))
    }

    // ── RowVec longValue / doubleValue / intValue ───────────────────────────────

    @Test fun RowVecLongValueReturnsLongOrZero() {
        val rv = rowVecOf("n" to 42L, "s" to "99")
        assertEquals(42L, rv.longValue("n"))
        assertEquals(99L, rv.longValue("s"))
        assertEquals(0L, rv.longValue("missing"))
    }

    @Test fun RowVecDoubleValueReturnsDoubleOrZero() {
        val rv = rowVecOf("n" to 3.14, "s" to "2.71")
        assertEquals(3.14, rv.doubleValue("n"), 0.001)
        assertEquals(2.71, rv.doubleValue("s"), 0.001)
        assertEquals(0.0, rv.doubleValue("missing"), 0.001)
    }

    @Test fun RowVecIntValueReturnsIntOrZero() {
        val rv = rowVecOf("n" to 7, "s" to "11")
        assertEquals(7, rv.intValue("n"))
        assertEquals(11, rv.intValue("s"))
        assertEquals(0, rv.intValue("missing"))
    }

    @Test fun RowVecStringValueReturnsStringOrDefault() {
        val rv = rowVecOf("s" to "hello")
        assertEquals("hello", rv.stringValue("s", "default"))
        assertEquals("default", rv.stringValue("missing", "default"))
    }

    // ── isNumerical ─────────────────────────────────────────────────────────────

    @Test fun isNumericalTrueWhenAllNumeric() {
        val rv = rowVecOf("a" to 1, "b" to 2.0, "c" to 3L)
        val c = cursorOf(rv)
        assertTrue(c.isNumerical)
    }

    @Test fun isNumericalFalseWhenAnyNonNumeric() {
        val rv = rowVecOf("a" to 1, "b" to "string")
        val c = cursorOf(rv)
        assertFalse(c.isNumerical)
    }

    // ── combine ────────────────────────────────────────────────────────────────

    @Test fun combineConcatenatesTwoCursors() {
        val a = makeCursor(2) { rowVecOf("n" to it) }
        val b = makeCursor(2) { rowVecOf("n" to (it + 10)) }
        val result = combine(a, b)
        assertEquals(4, result.size)
        assertEquals(0, result.at(0).longValue("n"))
        assertEquals(10, result.at(2).longValue("n"))
        assertEquals(11, result.at(3).longValue("n"))
    }

    // ── Cursor.at / Cursor.row ──────────────────────────────────────────────────

    @Test fun atReturnsRowAtPositiveIndex() {
        val c = makeCursor(3) { rowVecOf("i" to it) }
        assertEquals(1, c.at(1).longValue("i"))
    }

    @Test fun atSupportsNegativeIndexing() {
        val c = makeCursor(3) { rowVecOf("i" to it) }
        assertEquals(2, c.at(-1).longValue("i"))
        assertEquals(1, c.at(-2).longValue("i"))
        assertEquals(0, c.at(-3).longValue("i"))
    }

    @Test fun rowIsAliasForAt() {
        val c = makeCursor(2) { rowVecOf("v" to it) }
        assertEquals(0, c.row(0).longValue("v"))
        assertEquals(1, c.row(1).longValue("v"))
    }

    // ── Cursor meta ─────────────────────────────────────────────────────────────

    @Test fun metaReturnsColumnMetadata() {
        val c = makeCursor(1) { rowVecOf("name" to "Alice", "age" to 30) }
        val m = c.meta
        assertEquals(2, m.size)
        assertEquals("name", m.at(0).name)
        assertEquals("age", m.at(1).name)
    }

    @Test fun metaByNamesReturnsColumnIndices() {
        val c = makeCursor(1) { rowVecOf("a" to 1, "b" to 2, "c" to 3) }
        val indices = c.meta("a", "c")
        assertEquals(2, indices.size)
        assertEquals(0, indices.at(0))
        assertEquals(2, indices.at(1))
    }

    // ── Cursor.get by String vararg ─────────────────────────────────────────

    @Test fun getByStringVarargSelectsColumns() {
        val c = makeCursor(1) { rowVecOf("x" to 1, "y" to 2, "z" to 3) }
        val result = c["x", "z"]
        assertEquals(2, result.at(0).size)
        assertEquals(1, result.at(0).getValue("x"))
        assertEquals(3, result.at(0).getValue("z"))
    }

    // ── cellsToRowVec ──────────────────────────────────────────────────────────

    @Test fun cellsToRowVecCreatesRowVec() {
        @Suppress("UNCHECKED_CAST")
        val cells = listOf("Alice", 30).toSeries() as Series<Any?>
        @Suppress("UNCHECKED_CAST")
        val keys = listOf("name", "age").toSeries() as Series<String>
        val rv = cellsToRowVec(cells, keys)
        assertEquals(2, rv.size)
        assertEquals("Alice", rv.getValue("name"))
        assertEquals(30, rv.getValue("age"))
    }

    // ── RowVec keys ────────────────────────────────────────────────────────────

    @Test fun RowVecKeysExtractsColumnNames() {
        val rv = rowVecOf("a" to 1, "b" to 2)
        val k = rv.keys
        assertEquals(2, k.size)
        assertEquals("a", k.at(0))
        assertEquals("b", k.at(1))
    }

    // ── RowVec values ──────────────────────────────────────────────────────────

    @Test fun RowVecValuesExtractsLeftSeries() {
        val rv = rowVecOf("a" to 1, "b" to 2)
        @Suppress("UNCHECKED_CAST")
        val v = rv.values as Series<Any?>
        assertEquals(2, v.size)
        assertEquals(1, v.at(0))
        assertEquals(2, v.at(1))
    }

    // ── isHomoMorphic ─────────────────────────────────────────────────────────

    @Test fun isHomoMorphicTrueWhenAllSameType() {
        val rv = rowVecOf("a" to 1, "b" to 2)
        val c = cursorOf(rv)
        assertTrue(c.isHomoMorphic)
    }

    @Test fun isHomoMorphicFalseWhenTypesDiffer() {
        val rv = rowVecOf("a" to 1, "b" to "string")
        val c = cursorOf(rv)
        assertFalse(c.isHomoMorphic)
    }

    // ── ColumnExclusion ────────────────────────────────────────────────────────

    @Test fun stringUnaryMinusCreatesColumnExclusion() {
        val excl = -"age"
        assertTrue(excl is ColumnExclusion)
        assertEquals("age", excl.name)
    }

    // ── Cursor / RowVec size ──────────────────────────────────────────────────

    @Test fun cursorSizeReturnsRowCount() {
        val c = makeCursor(5) { rowVecOf("x" to it) }
        assertEquals(5, c.size)
    }

    @Test fun rowVecSizeReturnsColumnCount() {
        val rv = rowVecOf("a" to 1, "b" to 2, "c" to 3)
        assertEquals(3, rv.size)
    }

    // ── empty cursor ──────────────────────────────────────────────────────────

    @Test fun emptyCursorHasSizeZero() {
        val c: Cursor = emptySeries()
        assertEquals(0, c.size)
    }

    @Test fun whereOnEmptyCursorReturnsEmpty() {
        val c: Cursor = emptySeries()
        assertEquals(0, c.where { true }.size)
    }

    @Test fun takeOnEmptyCursorReturnsEmpty() {
        val c: Cursor = emptySeries()
        assertEquals(0, c.take(5).size)
    }

    @Test fun orderByOnEmptyCursorReturnsEmpty() {
        val c: Cursor = emptySeries()
        assertEquals(0, c.orderBy("x").size)
    }
}