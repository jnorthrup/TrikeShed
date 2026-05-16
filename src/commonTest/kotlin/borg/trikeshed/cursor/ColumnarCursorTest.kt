package borg.trikeshed.cursor

import borg.trikeshed.lib.ColumnSchema
import borg.trikeshed.lib.ColumnType
import borg.trikeshed.lib.IndexCursor
import borg.trikeshed.lib.IsamCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Focused root/common tests for the minimal columnar shim surface.
 *
 * This file intentionally tests only the current reunited commonMain shim contract.
 * It does not pretend unimplemented columnar file/index engines already exist.
 */
class ColumnarShimContractTest {
    @Test
    fun `ColumnSchema preserves name type and optional plugin`() {
        val schema = ColumnSchema(name = "price", type = ColumnType.Double, indexPluginName = "zran")
        assertEquals("price", schema.name)
        assertEquals(ColumnType.Double, schema.type)
        assertEquals("zran", schema.indexPluginName)
    }

    @Test
    fun `ColumnSchema allows null plugin`() {
        val schema = ColumnSchema(name = "qty", type = ColumnType.Long, indexPluginName = null)
        assertNull(schema.indexPluginName)
    }

    @Test
    fun `ColumnSchema rejects empty name`() {
        assertFailsWith<IllegalArgumentException> {
            ColumnSchema(name = "", type = ColumnType.Long)
        }
    }

    @Test
    fun `IsamCursor open stays explicit stub`() {
        assertFailsWith<UnsupportedOperationException> {
            IsamCursor.open("/tmp/columnar")
        }
    }
}

class IndexCursorShimContractTest {
    private val stub = object : IndexCursor {
        override fun seek(blockOffset: Long) = error("Not implemented")
        override fun next(): Boolean = error("Not implemented")
        override fun current(): Long = error("Not implemented")
    }

    @Test
    fun `IndexCursor seek remains implementation-owned`() {
        assertFailsWith<IllegalStateException> { stub.seek(42) }
    }

    @Test
    fun `IndexCursor next remains implementation-owned`() {
        assertFailsWith<IllegalStateException> { stub.next() }
    }

    @Test
    fun `IndexCursor current remains implementation-owned`() {
        assertFailsWith<IllegalStateException> { stub.current() }
    }
}
