package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.*

class CursorIndexTest {
    val category = s_["A", "A", "B", "A", "B", "B"]
    val stage = s_["X", "Y", "X", "Y", "Y", "X"]
    val metadata = s_[ColumnMeta("category", IOMemento.IoString), ColumnMeta("stage", IOMemento.IoString),
        ColumnMeta("payload", IOMemento.IoInt)]
    var payloadReads = 0
    val source: Cursor = 6 j { row: Int -> 3 j { column: Int ->
        val value: Any = when (column) { 0 -> category[row]; 1 -> stage[row]; else -> { payloadReads++; row } }
        value j { metadata[column] }
    } }

    @Test fun independentIndexesIntersectAndRefineWithoutReadingPayloads() {
        val base = CursorScope(source).index()
        val categories = base.groups(0)
        val stages = base.groups(1)
        val overlap = categories[0].intersect(stages[1])
        assertEquals(listOf(1, 3), overlap.ordinals.view.toList())
        assertEquals(listOf(1, 3), categories[0].groups(1)[1].ordinals.view.toList())
        assertEquals(listOf(0, 1, 3), categories[0].ordinals.view.toList())
        assertEquals(0, payloadReads)
        assertEquals(3, overlap.cursor[1].values[2])
        assertSame(metadata[2], overlap.cursor[1][2].b())
    }

    @Test fun selectionComposesSourceOrdinalsAndRetainsOrderAndDuplicates() {
        val base = CursorScope(source).index()
        val rows = intArrayOf(5, 3, 1, 0)
        val selection = base.select(rows.size j rows::get)
        rows.fill(4) // Caller mutation cannot rewrite an admitted ordinal vector.
        val nested = selection.groups(0)[1].select(s_[1, 0, 1])
        assertEquals(listOf(1, 3, 1), nested.ordinals.view.toList())
        assertEquals(listOf(1, 3, 1), nested.intersect(base.groups(1)[1]).ordinals.view.toList())
        assertEquals(0, payloadReads)
    }

    @Test fun descentChangesScopeAndUsesOriginalParentOrdinal() {
        val child = CursorScope(source).index().select(s_[4, 0]).cursor
        val parent: Cursor = 2 j { _: Int -> 1 j { _: Int -> (child as Any?) j { ColumnMeta("child", IOMemento.IoArray) } } }
        val base = CursorScope(parent).index()
        val first = base.descend(0, 0) { child }
        val same = base.select(s_[1, 0]).descend(1, 0) { child }
        val otherOccurrence = base.descend(1, 0) { child }
        assertEquals(first.scope, same.scope)
        assertEquals(first.scope.hashCode(), same.scope.hashCode())
        assertEquals(listOf(0, 1), first.intersect(same).ordinals.view.toList())
        assertEquals(0, same.scope.row)
        assertEquals("B", first.cursor[0].values[0])
        assertFailsWith<IllegalArgumentException> { first.intersect(otherOccurrence) }
        assertFailsWith<IllegalArgumentException> { first.intersect(base) }
    }

    @Test fun differentReadGenerationsCannotBeCombinedEvenWithTheSameSource() {
        val first = CursorScope(source).index()
        val second = CursorScope(source).index()
        assertFailsWith<IllegalArgumentException> { first.intersect(second) }
    }

    @Test fun emptyAndInvalidOrdinalsAreExplicit() {
        val base = CursorScope(source).index()
        assertEquals(0, base.select(emptySeriesOf()).groups(0).size)
        assertFailsWith<IllegalArgumentException> { base.select(s_[-1]) }
        assertFailsWith<IllegalArgumentException> { base.select(s_[6]) }
        assertFailsWith<IllegalArgumentException> { base.descend(6, 0) { source } }
        assertFailsWith<IllegalArgumentException> { base.descend(0, 3) { source } }
        assertFailsWith<IllegalArgumentException> { base.groups(3) }
    }
}
