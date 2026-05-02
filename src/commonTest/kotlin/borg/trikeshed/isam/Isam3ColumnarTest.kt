package borg.trikeshed.isam

import borg.trikeshed.common.Files
import borg.trikeshed.common.mktemp
import borg.trikeshed.common.rm
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.zip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private data class CellSpec(
    val name: String,
    val value: Any?,
    val type: IOMemento,
)

class Isam3ColumnarTest {
    @Test
    fun parsesPackedIoMetaColumns() {
        val layout = Isam3Layout.parse(
            """
            isam: 3
            klines:
              klines.time:
                IoInstant: [Open_time, Close_time]
              klines.price:
                IoDouble: [Open, High, Low, Close]
            views:
              klines: [Open_time, Close_time, Open, High, Low, Close]
            """.trimIndent(),
        )

        assertEquals(3, layout.version)
        assertNotNull(layout.partition("klines"))
        assertNotNull(layout.file("klines.time"))
        assertNotNull(layout.file("klines.price"))
        assertEquals(
            listOf("Open_time", "Close_time", "Open", "High", "Low", "Close"),
            layout.logicalNames("klines").toList(),
        )
        assertEquals(
            listOf(12, 12, 8, 8, 8, 8),
            layout.recordMeta("klines").toList().map { it.end - it.begin },
        )
    }

    @Test
    fun parsesSparseCoordinatesAndStoreFlags() {
        val layout = Isam3Layout.parse(
            """
            isam: 3
            klines:
              flags: [coords]
              klines.row:
                flags: [index, readonly]
                IoInstant:
                  Open_time: 0
                  Close_time: 12
                IoDouble:
                  Open: 24
                  Close: 48
            views:
              all: [Open_time, Close_time, Open, Close]
              price: [Open, Close]
            """.trimIndent(),
        )

        assertEquals(listOf("index", "readonly"), layout.file("klines.row")!!.flags.toList())
        assertEquals(listOf("Open", "Close"), layout.logicalNames("price").toList())
        assertEquals(listOf(0, 12, 24, 48), layout.recordMeta("all").toList().map { it.begin })
    }

    @Test
    fun rejectsWidthGuardsForFixedAndVariableColumns() {
        assertFailsWith<IllegalArgumentException> {
            Isam3Layout.parse(
                """
                isam: 3
                ticker:
                  ticker.data:
                    IoDouble: [4, Close]
                """.trimIndent(),
            )
        }

        assertFailsWith<IllegalStateException> {
            Isam3Layout.parse(
                """
                isam: 3
                ticker:
                  ticker.data:
                    IoString: [Symbol]
                """.trimIndent(),
            )
        }

        val ok = Isam3Layout.parse(
            """
            isam: 3
            ticker:
              ticker.data:
                IoString: [16, Symbol]
                IoDouble: [Close]
            """.trimIndent(),
        )
        assertEquals(listOf("Symbol", "Close"), ok.logicalNames().toList())
    }

    @Test
    fun parsesViewsAsCursorSurfaces() {
        val layout = Isam3Layout.parse(
            """
            isam: 3
            klines:
              klines.time:
                IoInstant: [Open_time, Close_time]
              klines.price:
                IoDouble: [Open, High, Low, Close]
            views:
              price: [Open, Close]
              time: [Open_time, Close_time]
            """.trimIndent(),
        )

        assertEquals(listOf("Open", "Close"), layout.logicalNames("price").toList())
        assertEquals(listOf("Open_time", "Close_time"), layout.logicalNames("time").toList())
    }

    @Test
    fun columnarWriteCreatesLayoutAndRoundTripsPackedClusters() {
        val base = mktemp()
        rm(base)
        try {
            val cursor = cursorOf(
                rowVecOf(
                    CellSpec("Open_time", Instant.fromEpochSeconds(0, 0), IOMemento.IoInstant),
                    CellSpec("Close_time", Instant.fromEpochSeconds(5, 0), IOMemento.IoInstant),
                    CellSpec("Open", 100.0, IOMemento.IoDouble),
                    CellSpec("Close", 110.0, IOMemento.IoDouble),
                ),
                rowVecOf(
                    CellSpec("Open_time", Instant.fromEpochSeconds(60, 0), IOMemento.IoInstant),
                    CellSpec("Close_time", Instant.fromEpochSeconds(65, 0), IOMemento.IoInstant),
                    CellSpec("Open", 101.5, IOMemento.IoDouble),
                    CellSpec("Close", 111.5, IOMemento.IoDouble),
                ),
            )

            ColumnarIsam.write(cursor, base)

            val layoutPath = "$base.isam3.yaml"
            assertTrue(Files.exists(layoutPath))
            assertTrue(Files.readString(layoutPath).contains("isam: 3"))
            assertTrue(Files.exists("$base.time.col"))
            assertTrue(Files.exists("$base.price.col"))

            val reopened = openColumnarIsam(base)
            assertEquals(2, reopened.size)
            assertEquals(
                listOf(
                    Instant.fromEpochSeconds(0, 0),
                    Instant.fromEpochSeconds(5, 0),
                    100.0,
                    110.0,
                ),
                reopened[0].toList().map { it.a },
            )
            assertEquals(
                listOf(
                    Instant.fromEpochSeconds(60, 0),
                    Instant.fromEpochSeconds(65, 0),
                    101.5,
                    111.5,
                ),
                reopened[1].toList().map { it.a },
            )
        } finally {
            rm("$base.isam3.yaml")
            rm("$base.time.col")
            rm("$base.price.col")
        }
    }
}

private fun rowVecOf(vararg cells: CellSpec): RowVec {
    val values: Series<Any?> = cells.size j { cells[it].value }
    val metas: Series<() -> ColumnMeta> = cells.size j { { RecordMeta(cells[it].name, cells[it].type) } }
    return values.zip(metas) as RowVec
}

private fun cursorOf(vararg rows: RowVec): Cursor = rows.toList().toSeries()
