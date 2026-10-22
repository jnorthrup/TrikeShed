package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import kotlin.test.*

class ColumnCursorTest {
    fun sample() = ColumnCursor.of(
        arrayOf(ColumnMeta("q", IOMemento.IoDouble), ColumnMeta("k", IOMemento.IoLong), ColumnMeta("flag", IOMemento.IoString)),
        arrayOf(
            DenseColumn(doubleArrayOf(1.5, 2.5, 3.5)),
            DenseColumn(longArrayOf(10, 20, 30), booleanArrayOf(true, false, true)),
            DenseColumn(DictColumn.of(arrayOf("a", "b", "a"))),
        ),
    )

    @Test fun denseOfAColumnCursorIsTheSameArray() {
        val c = sample()
        assertSame(c.dense(0).data, (c as Cursor).dense(0).data)
    }

    @Test fun rowsBoxLazilyAndHonourValidity() {
        val c = sample()
        assertEquals(3, c.size)
        val row = c[1]
        assertEquals(listOf(2.5, null, "b"), (0 until 3).map { row.values[it] })
        assertEquals("k", row.metas[1]().name)
    }

    @Test fun takeGathersOnlyTouchedColumns() {
        val c = sample()
        val t = c.take(intArrayOf(2, 0), 2)
        assertEquals(2, t.size)
        assertEquals(listOf(3.5, 1.5), (0 until 2).map { t[it].values[0] })
        assertEquals(listOf("a", "a"), (0 until 2).map { t[it].values[2] })
        assertEquals(listOf(30L, 10L), (0 until 2).map { t[it].values[1] })
    }

    @Test fun denseOfAConfixCursorTypesByMemento() {
        val cursor = confixDoc("""[{"n": 1, "x": 1.5, "s": "p"}, {"n": 2, "s": "q"}, {"n": 3, "x": 2.5, "s": "p"}]""")
            .index.facet(ConfixIndexK.Columns)
        val n = cursor.dense(0)
        assertContentEquals(longArrayOf(1, 2, 3), n.data as LongArray)
        assertNull(n.valid)
        val x = cursor.dense(1)
        assertEquals(1.5, (x.data as DoubleArray)[0])
        assertContentEquals(booleanArrayOf(true, false, true), x.valid)
        val s = cursor.dense(2).data as DictColumn
        assertContentEquals(intArrayOf(0, 1, 0), s.codes)
        assertContentEquals(arrayOf("p", "q"), s.dictionary)
    }

    @Test fun widenPairsRowsAcrossBothSides() {
        val l = sample()
        val r = ColumnCursor.of(arrayOf(ColumnMeta("v", IOMemento.IoLong)), arrayOf(DenseColumn(longArrayOf(7, 8))))
        val w = ColumnCursor.widen(l, intArrayOf(2, 0, 0), r, intArrayOf(1, 0, 1), 3)
        assertEquals(listOf(3.5, 8L), listOf(w[0].values[0], w[0].values[3]))
        assertEquals(listOf(1.5, 7L), listOf(w[1].values[0], w[1].values[3]))
    }
}
