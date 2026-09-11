package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.*

/** Newly authored behavioral regressions for the workflows inventoried in docs/columnar-assimilation.md. */
class CursorColumnarTest {
    val schema = s_[
        ColumnMeta("group", IOMemento.IoInt),
        ColumnMeta("category", IOMemento.IoString),
        ColumnMeta("amount", IOMemento.IoDouble),
    ]

    fun fixture(vararg rows: Array<Any?>): Cursor = rows.size j { row ->
        schema.size j { column -> rows[row][column] j { schema[column] } }
    }

    val sum: RowReducer = { total, value -> (total as? Double ?: 0.0) + (value as? Double ?: 0.0) }

    @Test
    fun structuralKeysGroupGenericRowsInFirstOccurrenceOrder() {
        // Aa and BB collide under String.hashCode; equal content still determines membership.
        val source = fixture(arrayOf(7, "Aa", 1.0), arrayOf(2, "BB", 3.0), arrayOf(7, "Aa", 4.0))
        val groups = source.groupClusters(intArrayOf(0, 1))
        assertEquals(2, groups.size)
        assertContentEquals(intArrayOf(0, 2), groups[0])
        assertContentEquals(intArrayOf(1), groups[1])
        val grouped = source.groupBy(intArrayOf(0, 1), sum)
        assertEquals(7, grouped[0][0].a)
        assertEquals(5.0, grouped[0][2].a)
        assertEquals(3.0, grouped[1][2].a)
    }

    @Test
    fun nullKeysAndHashCollisionsRemainDistinct() {
        val source = fixture(arrayOf(1, "Aa", 2.0), arrayOf(1, "BB", 3.0), arrayOf(1, null, 5.0), arrayOf(1, null, 7.0))
        val groups = source.groupBy(intArrayOf(1), sum)
        assertEquals(3, groups.size)
        assertEquals("Aa", groups[0][1].a)
        assertEquals("BB", groups[1][1].a)
        assertNull(groups[2][1].a)
        assertEquals(12.0, groups[2][2].a)
    }

    @Test
    fun splitAndGenericRowsHaveIdenticalGroupSemantics() {
        val generic = fixture(arrayOf(3, "r", 2.0), arrayOf(3, "r", 6.0))
        val split: Cursor = generic.size j { row -> ReifiedSplitSeries2(generic[row].values, generic[row].metas) }
        assertEquals(8.0, split.groupBy(intArrayOf(0, 1), sum)[0][2].a)
        assertEquals(8.0, generic.groupBy(intArrayOf(0, 1), sum)[0][2].a)
    }

    @Test
    fun groupMembersAreLazySeriesWithArrayMetadata() {
        val amounts = doubleArrayOf(2.0, 5.0)
        var reads = 0
        val source: Cursor = 2 j { row -> 2 j { column ->
            if (column == 0) 9 j { schema[0] }
            else { reads++; amounts[row] j { schema[2] } }
        } }
        val grouped = source.groupBy(0)
        assertEquals(0, reads)
        val cell = grouped[0][1]
        val members = cell.a as Series<*>
        assertEquals(2, members.size)
        amounts[1] = 11.0
        assertEquals(11.0, members[1])
        assertEquals(IOMemento.IoArray, cell.b().type)
        assertSame(schema[2], cell.b().child)
        assertSame(schema[0], grouped[0][0].b())
    }

    @Test
    fun reducersStartAtNullAndFollowSourceOrder() {
        val source = fixture(arrayOf(1, "a", 4.0), arrayOf(1, "a", 2.0), arrayOf(1, "a", 1.0))
        val grouped = source.groupBy(intArrayOf(0, 1)) { acc, v -> if (acc == null) v else (acc as Double) - (v as Double) }
        assertEquals(1.0, grouped[0][2].a)
        assertSame(schema[2], grouped[0][2].b())
        assertEquals(7.0, source.select(2).groupBy(intArrayOf(), sum)[0][0].a)
    }

    @Test
    fun pivotThenReduceProducesSparseCrossTabulation() {
        val source = fixture(arrayOf(2, "west", 1.0), arrayOf(1, "east", 3.0), arrayOf(2, "east", 4.0), arrayOf(2, "west", 2.0))
        val pivot = source.pivot(intArrayOf(0), intArrayOf(1), intArrayOf(2))
        assertEquals(4, pivot.size)
        assertEquals(3, pivot.width)
        assertEquals("[category=west]:amount", pivot.columnNames[1])
        assertEquals("[category=east]:amount", pivot.columnNames[2])
        assertEquals(1.0, pivot[0][1].a)
        assertNull(pivot[0][2].a)
        val result = pivot.groupBy(intArrayOf(0), sum)
        assertEquals(2, result.size)
        assertEquals(2, result[0][0].a)
        assertEquals(3.0, result[0][1].a)
        assertEquals(4.0, result[0][2].a)
        assertEquals(0.0, result[1][1].a)
        assertEquals(3.0, result[1][2].a)
    }

    @Test
    fun pivotHandlesCompositeKeysRepeatedFanoutAndRowSpecificMetadata() {
        val child = ColumnMeta("unit", IOMemento.IoString)
        val source: Cursor = 2 j { row -> 3 j { column ->
            when (column) {
                0 -> row j { schema[0] }
                1 -> null j { schema[1] }
                else -> (row + 0.5) j { ColumnMeta("a$row", IOMemento.IoDouble, child) }
            }
        } }
        val pivot = source.pivot(intArrayOf(), intArrayOf(0, 1), intArrayOf(2, 2))
        assertEquals(4, pivot.width)
        assertEquals(0.5, pivot[0][0].a)
        assertEquals(0.5, pivot[0][1].a)
        assertNull(pivot[1][0].a)
        assertEquals(1.5, pivot[1][2].a)
        assertEquals("[group=1,category=null]:a1", pivot[1][2].b().name)
        assertSame(child, pivot[1][2].b().child)
        assertEquals(IOMemento.IoDouble, pivot[1][2].b().type)
    }

    @Test
    fun pivotDefersNonKeyValuesUntilMatchingCellRead() {
        var reads = 0
        val source: Cursor = 2 j { row -> 2 j { column ->
            if (column == 0) row j { schema[0] }
            else { reads++; row.toDouble() j { schema[2] } }
        } }
        val pivot = source.pivot(intArrayOf(), intArrayOf(0), intArrayOf(1))
        assertEquals(0, reads)
        assertNull(pivot[1][0].a)
        assertEquals(0, reads)
        assertEquals(1.0, pivot[1][1].a)
        assertEquals(1, reads)
    }

    @Test
    fun emptyAndDegenerateAxesDoNotInventRows() {
        val empty: Cursor = emptySeriesOf()
        assertEquals(0, empty.groupBy(0).size)
        assertEquals(0, empty.groupBy(intArrayOf(), sum).size)
        assertEquals(0, empty.pivot(intArrayOf(), intArrayOf(0), intArrayOf(1)).size)
        val source = fixture(arrayOf(1, "a", 5.0))
        assertEquals(1, source.pivot(intArrayOf(0), intArrayOf(1), intArrayOf()).width)
        assertEquals(5.0, source.pivot(intArrayOf(), intArrayOf(), intArrayOf(2))[0][0].a)
        assertFailsWith<IllegalArgumentException> { source.groupBy(3) }
        assertFailsWith<IllegalArgumentException> { source.pivot(intArrayOf(), intArrayOf(-1), intArrayOf(2)) }
    }

    @Test
    fun explicitNumericOrderingIsStableAndPreservesMetadata() {
        val source = fixture(arrayOf(10, "a", 1.0), arrayOf(2, "b", 2.0), arrayOf(2, "c", 3.0))
        val ordered = source.ordered(intArrayOf(0), Comparator { a, b -> (a[0] as Int).compareTo(b[0] as Int) })
        assertEquals("b", ordered[0][1].a)
        assertEquals("c", ordered[1][1].a)
        assertEquals("a", ordered[2][1].a)
        assertSame(schema[1], ordered[0][1].b())
    }

    @Test
    fun projectionExclusionAndMirrorPreservePairs() {
        val source = fixture(arrayOf(4, "north", 2.5))
        val selected = source.select(StringBuilder("amount"), StringBuilder("group"), "amount")
        assertEquals(2.5, selected[0][0].a)
        assertEquals(4, selected[0][1].a)
        assertEquals(2.5, selected[0][2].a)
        assertSame(schema[2], selected[0][2].b())
        val excluded = source[-"group", -"category"]
        assertEquals(1, excluded.width)
        assertEquals(2.5, excluded[0][0].a)
        assertEquals(1, source.without(StringBuilder("group"), "amount").width)
        val mirrored = source.mirror()
        assertEquals(2.5, mirrored[0][0].a)
        assertEquals(4, mirrored[0][2].a)
        assertSame(schema[0], mirrored[0][2].b())
        assertFailsWith<IllegalStateException> { source.select("missing") }
    }

    @Test
    fun resamplingAppendsOnlyMissingKeysAndPreservesMetadata() {
        val source = fixture(arrayOf(2, "a", 7.0), arrayOf(2, "b", 8.0), arrayOf(4, "c", 9.0))
        val result = source.resample(0, s_[1, 2, 3, 3, 4])
        assertEquals(5, result.size)
        assertEquals(2, result[0][0].a)
        assertEquals(2, result[1][0].a)
        assertEquals(1, result[3][0].a)
        assertEquals(3, result[4][0].a)
        assertNull(result[4][2].a)
        assertSame(schema[2], result[4][2].b())
    }
}
