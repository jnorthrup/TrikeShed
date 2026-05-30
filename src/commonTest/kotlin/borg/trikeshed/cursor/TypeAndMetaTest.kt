@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST")
package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * IOMemento tests — ported from columnar's type memento tests
 */
class IOMementoTest {
    @Test
    fun `IOMemento has correct network sizes`() {
        assertEquals(1, IOMemento.IoBoolean.networkSize)
        assertEquals(4, IOMemento.IoInt.networkSize)
        assertEquals(8, IOMemento.IoLong.networkSize)
        assertEquals(4, IOMemento.IoFloat.networkSize)
        assertEquals(8, IOMemento.IoDouble.networkSize)
        assertEquals(10, IOMemento.IoLocalDate.networkSize)
    }

    @Test
    fun `IOMemento variable width types have null network size`() {
        assertNull(IOMemento.IoString.networkSize)
        assertNull(IOMemento.IoInstant.networkSize)
        assertNull(IOMemento.IoObject.networkSize)
        assertNull(IOMemento.IoArray.networkSize)
        assertNull(IOMemento.IoBytes.networkSize)
    }

    @Test
    fun `IOMemento kind dispatch`() {
        assertEquals(0, IOMemento.IoObject.kind)
        assertEquals(1, IOMemento.IoArray.kind)
        assertEquals(2, IOMemento.IoInt.kind)
        assertEquals(2, IOMemento.IoString.kind)
        assertEquals(2, IOMemento.IoDouble.kind)
    }
}

/**
 * ColumnMeta tests — ported from columnar's column metadata tests
 */
class ColumnMetaTest {
    @Test
    fun `ColumnMeta companion creates correct structure`() {
        val meta = ColumnMeta("test", IOMemento.IoInt)

        assertEquals("test", meta.name)
        assertEquals(IOMemento.IoInt, meta.type)
        assertNull(meta.child)
    }

    @Test
    fun `ColumnMeta with child`() {
        val child = ColumnMeta("inner", IOMemento.IoString)
        val parent = ColumnMeta("outer", IOMemento.IoObject, child)

        assertEquals("outer", parent.name)
        assertEquals(IOMemento.IoObject, parent.type)
        assertEquals(child, parent.child)
        assertEquals("inner", parent.child?.name)
    }

    @Test
    fun `ColumnMeta lazy supplier`() {
        var callCount = 0
        val lazyMeta: () -> ColumnMeta = {
            callCount++
            ColumnMeta("lazy", IOMemento.IoDouble)
        }

        assertEquals(0, callCount)
        val meta1 = lazyMeta()
        assertEquals(1, callCount)
        val meta2 = lazyMeta()
        assertEquals(2, callCount) // Each call creates a new instance
        assertEquals("lazy", meta1.name)
        assertEquals("lazy", meta2.name)
    }
}

/**
 * Series and Join integration tests
 */
class SeriesJoinIntegrationTest {
    @Test
    fun `join of two series`() {
        val left = s_[1, 2, 3]
        val right = s_["a", "b", "c"]

        val joined: Series<Join<Int, String>> = s_[left[0] j right[0], left[1] j right[1], left[2] j right[2]]

        assertEquals(3, joined.size)
        assertEquals(1, joined[0].a)
        assertEquals("a", joined[0].b)
    }

    @Test
    fun `nested joins`() {
        val inner = "value" j 42
        val outer = "key" j inner

        assertEquals("key", outer.a)
        assertEquals("value", outer.b.a)
        assertEquals(42, outer.b.b)
    }

    @Test
    fun `series map with join values`() {
        val series: Series<Join<Int, String>> = s_[1 j "a", 2 j "b", 3 j "c"]

        val keys = series α { it.a }
        val values = series α { it.b }

        assertEquals(3, keys.size)
        assertEquals(1, keys[0])
        assertEquals("a", values[0])
    }
}
