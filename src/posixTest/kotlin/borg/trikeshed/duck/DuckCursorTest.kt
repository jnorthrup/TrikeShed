package borg.trikeshed.duck

import borg.trikeshed.lib.Series
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuckCursorTest {
    @Test
    fun testMemoryConnectionAndSimpleQuery() {
        DuckConnection.memory().use { conn ->
            conn.execute("CREATE TABLE test (id INTEGER, val DOUBLE, name VARCHAR, flag BOOLEAN)")
            conn.execute("INSERT INTO test VALUES (1, 1.5, 'hello', true), (2, 2.5, 'world', false)")
            
            conn.query("SELECT * FROM test ORDER BY id").use { cursor ->
                assertEquals(2, cursor.rowCount)
                assertEquals(4, cursor.columnCount)
                
                val map = cursor.toSeriesMap()
                
                val ids = Array(cursor.rowCount) { map["id"]!!.b(it) as Int }
                assertEquals(1, ids[0])
                assertEquals(2, ids[1])
                
                val vals = Array(cursor.rowCount) { map["val"]!!.b(it) as Double }
                assertEquals(1.5, vals[0])
                assertEquals(2.5, vals[1])
                
                val names = Array(cursor.rowCount) { map["name"]!!.b(it) as String }
                assertEquals("hello", names[0])
                assertEquals("world", names[1])
                
                val flags = Array(cursor.rowCount) { map["flag"]!!.b(it) as Boolean }
                assertTrue(flags[0])
                assertTrue(!flags[1])
            }
        }
    }
}
