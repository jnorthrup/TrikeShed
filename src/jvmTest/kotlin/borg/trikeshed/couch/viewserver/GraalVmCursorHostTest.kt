package borg.trikeshed.couch.viewserver

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import org.junit.Assert.assertEquals
import org.junit.Test
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

class GraalVmCursorHostTest {
    @Test
    fun testReduceCursor() {
        // Create 50 rows, each with one Double and one String column
        val exemplarMeta: `ColumnMeta↻` = {
            ColumnMeta("value", IOMemento.IoDouble,
                ColumnMeta("name", IOMemento.IoString)
            )
        }

        val rows = 50
        val cursor: Cursor = rows j { i: Int ->
            val vals: Series<Any?> = 2 j { col: Int ->
                when (col) {
                    0 -> i.toDouble()
                    1 -> "row_$i"
                    else -> null
                }
            }
            val rowVec = vals j exemplarMeta
            rowVec as RowVec
        }

        // JS Script to double the 'value' column
        val jsScript = """
            (function(cursor) {
                var out = [];
                for (var i = 0; i < cursor.length; i++) {
                    var row = cursor[i];
                    out.push({
                        value: row.value * 2,
                        name: row.name
                    });
                }
                return out;
            })
        """.trimIndent()

        val resultCursor = GraalVmCursorHost.reduceCursor(cursor, jsScript)

        assertEquals(50, resultCursor.size) // Assert size

        // Check contents
        for (i in 0 until 50) {
            val resRow: RowVec = resultCursor[i]
            val vals = resRow.a as Series<Any?>
            assertEquals(i.toDouble() * 2.0, vals[0])
            assertEquals("row_$i", vals[1])
        }
    }
}
