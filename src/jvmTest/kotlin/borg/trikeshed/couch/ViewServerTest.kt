package borg.trikeshed.couch

import borg.trikeshed.parse.confix.confixDoc
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class ViewServerTest {

    @Test
    fun `map function emits rows for each document`() {
        val server = ViewServer()

        // Define a view that emits doc.type as key
        val viewDef = ViewDefinition(
            ddoc = "_design/test",
            viewName = "byType",
            mapFn = MapFunction.Emit(
                key = KeyExpr.DocField("type"),
                value = ValueExpr.Const(1)
            )
        )

        // Create 3 documents: types A, B, A
        val docs = listOf(
            Document("doc-1", listOf(Field("type", "A"), Field("value", 10))),
            Document("doc-2", listOf(Field("type", "B"), Field("value", 20))),
            Document("doc-3", listOf(Field("type", "A"), Field("value", 30)))
        )

        val result = server.execute(viewDef, docs)

        // Should have 3 rows
        assertEquals(3, result.size)

        // Two rows with key "A", one with key "B"
        val keys = result.rows.map { it.key }.toSet()
        assertEquals(setOf("A", "B"), keys)

        val aRows = result.rows.filter { it.key == "A" }
        val bRows = result.rows.filter { it.key == "B" }
        assertEquals(2, aRows.size)
        assertEquals(1, bRows.size)

        // All values should be 1 (the constant emit value)
        for (row in result.rows) {
            assertEquals(1, row.value)
            assertTrue(row.docId.startsWith("doc-"))
        }
    }

    @Test
    fun `count reduce groups by key and counts emissions`() {
        val server = ViewServer()

        val viewDef = ViewDefinition(
            ddoc = "_design/test",
            viewName = "byType",
            mapFn = MapFunction.Emit(
                key = KeyExpr.DocField("type"),
                value = ValueExpr.Const(1)
            ),
            reduceFn = ReduceFunction.Builtin("_count")
        )

        val docs = listOf(
            Document("doc-1", listOf(Field("type", "A"), Field("value", 10))),
            Document("doc-2", listOf(Field("type", "B"), Field("value", 20))),
            Document("doc-3", listOf(Field("type", "A"), Field("value", 30)))
        )

        val result = server.execute(viewDef, docs)

        // After _count reduce, should have 2 rows (one per key)
        assertEquals(2, result.size)

        // Find rows for A and B
        val aRow = result.rows.firstOrNull { it.key == "A" }
        val bRow = result.rows.firstOrNull { it.key == "B" }

        assertTrue(aRow != null)
        assertTrue(bRow != null)
        assertEquals(2L, aRow!!.value)  // A appears twice
        assertEquals(1L, bRow!!.value)  // B appears once

        // Reduced rows have special docId
        assertEquals("_count", aRow.docId)
        assertEquals("_count", bRow.docId)
    }

    @Test
    fun `sum reduce groups by key and sums values`() {
        val server = ViewServer()

        val viewDef = ViewDefinition(
            ddoc = "_design/test",
            viewName = "byTypeSumValue",
            mapFn = MapFunction.Emit(
                key = KeyExpr.DocField("type"),
                value = ValueExpr.DocField("value")
            ),
            reduceFn = ReduceFunction.Builtin("_sum")
        )

        val docs = listOf(
            Document("doc-1", listOf(Field("type", "A"), Field("value", 10))),
            Document("doc-2", listOf(Field("type", "B"), Field("value", 20))),
            Document("doc-3", listOf(Field("type", "A"), Field("value", 30)))
        )

        val result = server.execute(viewDef, docs)

        assertEquals(2, result.size)

        val aRow = result.rows.firstOrNull { it.key == "A" }
        val bRow = result.rows.firstOrNull { it.key == "B" }

        assertTrue(aRow != null)
        assertTrue(bRow != null)
        assertEquals(40.0, aRow!!.value)  // 10 + 30
        assertEquals(20.0, bRow!!.value)  // 20
    }

    @Test
    fun `stats reduce computes count, sum, min, max, sumsqr`() {
        val server = ViewServer()

        val viewDef = ViewDefinition(
            ddoc = "_design/test",
            viewName = "byTypeStats",
            mapFn = MapFunction.Emit(
                key = KeyExpr.DocField("type"),
                value = ValueExpr.DocField("value")
            ),
            reduceFn = ReduceFunction.Builtin("_stats")
        )

        val docs = listOf(
            Document("doc-1", listOf(Field("type", "A"), Field("value", 10.0))),
            Document("doc-2", listOf(Field("type", "B"), Field("value", 20.0))),
            Document("doc-3", listOf(Field("type", "A"), Field("value", 30.0)))
        )

        val result = server.execute(viewDef, docs)

        assertEquals(2, result.size)

        val aRow = result.rows.firstOrNull { it.key == "A" }
        val bRow = result.rows.firstOrNull { it.key == "B" }

        assertTrue(aRow != null)
        assertTrue(bRow != null)

        val aStats = aRow!!.value as Map<*, *>
        assertEquals(2L, aStats["count"])
        assertEquals(40.0, aStats["sum"])
        assertEquals(10.0, aStats["min"])
        assertEquals(30.0, aStats["max"])
        assertEquals(1000.0, aStats["sumsqr"])  // 10^2 + 30^2 = 100 + 900 = 1000

        val bStats = bRow!!.value as Map<*, *>
        assertEquals(1L, bStats["count"])
        assertEquals(20.0, bStats["sum"])
        assertEquals(20.0, bStats["min"])
        assertEquals(20.0, bStats["max"])
        assertEquals(400.0, bStats["sumsqr"])
    }

    @Test
    fun `emit with doc._id as key`() {
        val server = ViewServer()

        val viewDef = ViewDefinition(
            ddoc = "_design/test",
            viewName = "byId",
            mapFn = MapFunction.Emit(
                key = KeyExpr.DocId,
                value = ValueExpr.DocValue
            )
        )

        val docs = listOf(
            Document("doc-1", listOf(Field("type", "A"))),
            Document("doc-2", listOf(Field("type", "B")))
        )

        val result = server.execute(viewDef, docs)

        assertEquals(2, result.size)

        val keys = result.rows.map { it.key }.toSet()
        assertEquals(setOf("doc-1", "doc-2"), keys)

        // Values should be the whole document (ConfixDoc)
        for (row in result.rows) {
            assertTrue(row.value != null)
        }
    }

    @Test
    fun `emit with constant key and value`() {
        val server = ViewServer()

        val viewDef = ViewDefinition(
            ddoc = "_design/test",
            viewName = "constant",
            mapFn = MapFunction.Emit(
                key = KeyExpr.Const("all"),
                value = ValueExpr.Const(1)
            )
        )

        val docs = listOf(
            Document("doc-1", listOf(Field("type", "A"))),
            Document("doc-2", listOf(Field("type", "B"))),
            Document("doc-3", listOf(Field("type", "C")))
        )

        val result = server.execute(viewDef, docs)

        assertEquals(3, result.size)

        // All rows should have key "all" and value 1
        for (row in result.rows) {
            assertEquals("all", row.key)
            assertEquals(1, row.value)
        }

        // With _count reduce, should have 1 row with count 3
        val reducedViewDef = viewDef.copy(reduceFn = ReduceFunction.Builtin("_count"))
        val reducedResult = server.execute(reducedViewDef, docs)

        assertEquals(1, reducedResult.size)
        assertEquals("all", reducedResult[0].key)
        assertEquals(3L, reducedResult[0].value)
    }

    @Test
    fun `ViewRow has correct properties`() {
        val row = ViewRow(
            key = "test-key",
            value = "test-value",
            docId = "doc-123",
            jsPath = "doc.type"
        )

        assertEquals("test-key", row.key)
        assertEquals("test-value", row.value)
        assertEquals("doc-123", row.docId)
        assertEquals("doc.type", row.jsPath)
    }

    @Test
    fun `ViewResult can be created and indexed`() {
        val rows = mutableSeriesOf(
            ViewRow("k1", 1, "doc-1"),
            ViewRow("k2", 2, "doc-2"),
            ViewRow("k3", 3, "doc-3")
        )
        val result = ViewResult(rows)

        assertEquals(3, result.size)
        assertEquals("k1", result[0].key)
        assertEquals("k2", result[1].key)
        assertEquals("k3", result[2].key)
    }

    @Test
    fun `custom reduce evaluates confix dsl ast`() {
        val server = ViewServer()

        val viewDef = ViewDefinition(
            ddoc = "_design/test",
            viewName = "byTypeCustom",
            mapFn = MapFunction.Emit(
                key = KeyExpr.DocField("type"),
                value = ValueExpr.DocField("value")
            ),
            reduceFn = ReduceFunction.Custom("""
                {
                  "op": "sum",
                  "map": {
                    "op": "*",
                    "args": [{"op": "value"}, 2]
                  }
                }
            """.trimIndent())
        )

        val docs = listOf(
            Document("doc-1", listOf(Field("type", "A"), Field("value", 10))),
            Document("doc-2", listOf(Field("type", "B"), Field("value", 20))),
            Document("doc-3", listOf(Field("type", "A"), Field("value", 30)))
        )

        val result = server.execute(viewDef, docs)

        assertEquals(2, result.size)

        val aRow = result.rows.firstOrNull { it.key == "A" }
        val bRow = result.rows.firstOrNull { it.key == "B" }

        assertTrue(aRow != null)
        assertTrue(bRow != null)

        // (10 * 2) + (30 * 2) = 80
        assertEquals(80.0, aRow!!.value)
        // (20 * 2) = 40
        assertEquals(40.0, bRow!!.value)
    }

}