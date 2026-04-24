package borg.trikeshed.couch.miniduck.columnar

import kotlin.test.*

class ColumnSchemaValidationTest {
    @Test fun `ColumnSchema validates name and type`() {
        val schema = ColumnSchema("x", ColumnType.Long)
        assertEquals("x", schema.name)
        assertEquals(ColumnType.Long, schema.type)
    }
    
    @Test fun `ColumnSchema allows null indexPluginName`() {
        val schema = ColumnSchema("x", ColumnType.Long, null)
        assertNull(schema.indexPluginName)
    }
    
    @Test fun `ColumnSchema rejects empty name`() {
        assertFailsWith<IllegalArgumentException> {
            ColumnSchema("", ColumnType.Long)
        }
    }
    
    @Test fun `ColumnSchema rejects unsupported type`() {
        assertFailsWith<IllegalArgumentException> {
            ColumnSchema("x", ColumnType.valueOf("UNSUPPORTED"))
        }
    }
}
