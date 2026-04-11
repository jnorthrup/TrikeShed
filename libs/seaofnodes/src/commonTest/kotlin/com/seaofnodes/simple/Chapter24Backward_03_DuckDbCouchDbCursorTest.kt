package com.seaofnodes.simple

import borg.literbike.couchdb.DatabaseInstance
import borg.literbike.couchdb.Document
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.zip
import com.seaofnodes.simple.ccek.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class Chapter24Backward_03_DuckDbCouchDbCursorTest {
    @Test
    fun `DuckCursor getSeries returns trikeshed Series with correct size`() {
        // RED: DuckCursor is in posixMain only, not resolvable from commonTest
        val cursor = borg.trikeshed.duck.DuckCursor()
        val series: Series<Any?> = cursor.getSeries()
        assertEquals(0, series.size)
    }

    @Test
    fun `DuckSeries query result converts to Cursor via RowVec join`() {
        val values: Series<Any?> = 3 j { i: Int -> "col$i" }
        val meta: Series<() -> ColumnMeta> =
            3 j { _: Int ->
                {
                    ColumnMeta(
                        "col",
                        object : TypeMemento {
                            override val networkSize: Int? = null
                        },
                    )
                }
            }
        val rowVec: RowVec = values.zip(meta)
        assertEquals(3, rowVec.size)

        // RED: DuckSeries does not expose ColumnMeta conversion to produce Cursor directly
        val duckSeries = borg.trikeshed.duck.DuckSeries()
        val cursor: Cursor = duckSeries.toCursor()
        assertEquals(rowVec.size, cursor[0].size)
    }

    @Test
    fun `CouchDB cursor document produces same Cursor shape as DuckDB query`() {
        val values: Series<Any?> = 2 j { i -> "v$i" }
        val meta: Series<() -> ColumnMeta> =
            2 j { _ ->
                {
                    ColumnMeta(
                        "name",
                        object : TypeMemento {
                            override val networkSize: Int? = null
                        },
                    )
                }
            }
        val expectedCursor: Cursor = 1 j { _ -> values.zip(meta) }
        assertEquals(1, expectedCursor.size)

        // RED: borg.literbike.couchdb.Cursor (pagination cursor) is NOT borg.trikeshed.cursor.Cursor
        // No factory exists to convert couchdb results to trikeshed Cursor
        val couchResult =
            borg.literbike.couchdb.Cursor(
                key = kotlinx.serialization.json.JsonPrimitive("test"),
                skip = 0u,
            )
        val fromCouch: Cursor = couchResult.toTrikeShedCursor()
        assertEquals(expectedCursor.size, fromCouch.size)
    }

    @Test
    fun `Compiler DAG nodes stored as CouchDB documents for MapReduce intermediary`() {
        val dagNodes =
            listOf(
                DagNode(0, "Start", emptyList(), emptyList(), "tuple"),
                DagNode(1, "Add", listOf(0, 0), listOf(0), "int"),
            )
        val ctx =
            compilerContext()
                .plus(ParserKey, ParserElement(ParserKey, dagNodes))
        val parser = ctx.get<ParserElement>()
        assertNotNull(parser)
        assertEquals(2, parser.dagNodes.size)

        val db = DatabaseInstance(name = "dag_intermediary")

        // RED: no DagNode serialization to CouchDB Document exists
        for (dag in dagNodes) {
            val doc = dag.toCouchDocument()
            val result = db.putDocument(doc)
            assertEquals(true, result.isSuccess)
        }
    }

    @Test
    fun `BRC cursor varieties should converge on identical Series output shape`() {
        // RED: BRC varieties (BrcPure, BrcMmap, etc.) from different libs
        // do not exist in this source set and no convergence test exists
        val brcPure =
            borg.trikeshed.duck.brc
                .BrcPure()
        val brcMmap =
            borg.trikeshed.duck.brc
                .BrcMmap()

        val seriesA: Series<Join<String, Join<Double, Double>>> = brcPure.execute()
        val seriesB: Series<Join<String, Join<Double, Double>>> = brcMmap.execute()

        assertEquals(seriesA.size, seriesB.size)
        for (i in 0 until seriesA.size) {
            assertEquals(seriesA[i].a, seriesB[i].a)
        }
    }
}

// RED: these extension functions do not exist
private fun DagNode.toCouchDocument(): Document = TODO()

private fun borg.literbike.couchdb.Cursor.toTrikeShedCursor(): Cursor = TODO()
