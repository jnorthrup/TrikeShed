package borg.trikeshed.cursor

import borg.trikeshed.lib.j
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ColKTest {
    @Test
    fun testRowVecAsFaceted() {
        val meta0 = ColumnMeta("id", IOMemento.IoInt)
        val meta1 = ColumnMeta("name", IOMemento.IoString)

        // RowVec is a Series of Join<Any?, ColumnMeta↻>
        val row: RowVec = 2 j { i: Int ->
            when (i) {
                0 -> (42 as Any?) j { meta0 }
                1 -> ("Alice" as Any?) j { meta1 }
                else -> throw IndexOutOfBoundsException()
            }
        }

        val faceted = row.asFaceted()

        // Test Width
        assertEquals(2, faceted.b(ColK.Width))

        // Test ByIndex
        assertEquals(42, faceted.b(ColK.ByIndex(0)))
        assertEquals("Alice", faceted.b(ColK.ByIndex(1)))

        // Test ByName
        assertEquals(42, faceted.b(ColK.ByName("id")))
        assertEquals("Alice", faceted.b(ColK.ByName("name")))

        // Test Meta
        val metas = faceted.b(ColK.Meta) as Series<ColumnMeta>
        assertEquals(2, metas.a)
        assertEquals(meta0, metas.b(0))
        assertEquals(meta1, metas.b(1))

        // Test missing name
        assertFailsWith<NoSuchElementException> {
            faceted.b(ColK.ByName("missing"))
        }

        // Test reverse: asRowVec()
        val row2 = faceted.asRowVec()
        assertEquals(2, row2.a)
        assertEquals(42, row2.b(0).a)
        assertEquals("Alice", row2.b(1).a)
        assertEquals(meta0, row2.b(0).b())
        assertEquals(meta1, row2.b(1).b())
    }
}
