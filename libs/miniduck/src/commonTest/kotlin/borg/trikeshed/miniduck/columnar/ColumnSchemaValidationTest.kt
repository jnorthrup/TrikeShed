package borg.trikeshed.miniduck.columnar

import kotlin.test.*

class ColumnSchemaValidationTest {
    @Test fun `ColumnSchema validates name and type`() {
        val schema = ColumnSchema(name = "x", type = ColumnType.Long)
        assertEquals("x", schema.name)
        assertEquals(ColumnType.Long, schema.type)
    }
    
    @Test fun `ColumnSchema allows null indexPluginName`() {
        val schema = ColumnSchema(name = "x", type = ColumnType.Long, indexPluginName = null)
        assertNull(schema.indexPluginName)
    }
    
    @Test fun `ColumnSchema rejects empty name`() {
        assertFailsWith<IllegalArgumentException> {
            ColumnSchema(name = "", type = ColumnType.Long)
        }
    }
    
    @Test fun `ColumnSchema rejects unsupported type`() {
        assertFailsWith<IllegalArgumentException> {
            ColumnSchema(name = "x", type = ColumnType.valueOf("UNSUPPORTED"))
        }
    }
}
