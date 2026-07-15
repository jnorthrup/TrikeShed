package borg.trikeshed.viewserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommonViewServerTest {
    @Test
    fun capturesMapFunctionAndEmitsDocumentFields() {
        val server = CommonViewServer()
        server.addFunction("function(doc) { emit(doc.type, doc.amount); }")

        val result = server.mapDocument(
            mapOf(
                "type" to ViewValue.Text("invoice"),
                "amount" to ViewValue.Number(42.5),
            ),
        )

        assertEquals(
            listOf(listOf(ViewEmission(ViewValue.Text("invoice"), ViewValue.Number(42.5)))),
            result,
        )
    }

    @Test
    fun resetRemovesCapturedFunctions() {
        val server = CommonViewServer()
        server.addFunction("doc => emit(doc.type, doc.amount)")

        server.reset()

        assertEquals(0, server.functionCount)
        assertEquals(emptyList(), server.mapDocument(emptyMap()))
    }

    @Test
    fun rejectsArbitraryJavaScriptInsteadOfEvaluatingIt() {
        val server = CommonViewServer()

        assertFailsWith<IllegalArgumentException> {
            server.addFunction("function(doc) { process.exit(1); }")
        }
    }

    @Test
    fun couchBuiltinsReduceAndRereduceConfixJsonValues() {
        val server = CommonViewServer()
        val left = server.reduce(
            "_stats",
            listOf(ViewValue.Number(10.0), ViewValue.Number(30.0)),
        )
        val right = server.reduce("_stats", listOf(ViewValue.Number(20.0)))

        assertEquals(ViewValue.Number(60.0), server.reduce("_sum", listOf(
            ViewValue.Number(10.0), ViewValue.Number(20.0), ViewValue.Number(30.0),
        )))
        assertEquals(ViewValue.Number(3.0), server.reduce("_count", listOf(
            ViewValue.Text("a"), ViewValue.Text("b"), ViewValue.Text("c"),
        )))
        assertEquals(
            ViewValue.ObjectValue(
                mapOf(
                    "sum" to ViewValue.Number(60.0),
                    "count" to ViewValue.Number(3.0),
                    "min" to ViewValue.Number(10.0),
                    "max" to ViewValue.Number(30.0),
                    "sumsqr" to ViewValue.Number(1400.0),
                ),
            ),
            server.rereduce("_stats", listOf(left, right)),
        )
    }

    @Test
    fun capturesCouchDbCascadeCompositeMapAndMetricRollup() {
        val server = CommonViewServer()
        server.addFunction("tool:couchdbcascade/byOrganization")
        val first = mapOf(
            "organization_id" to ViewValue.Text("org-1"),
            "machine_id" to ViewValue.Text("machine-1"),
            "reading_date" to ViewValue.Text("2026-07-15T12:34:00Z"),
            "cpu_mhz" to ViewValue.Number(100.0),
            "memory_mib" to ViewValue.Number(200.0),
        )
        val second = first + mapOf(
            "reading_date" to ViewValue.Text("2026-07-15T12:35:00Z"),
            "cpu_mhz" to ViewValue.Number(300.0),
            "memory_mib" to ViewValue.Number(400.0),
        )

        val emission = server.mapDocument(first).single().single()
        assertEquals(
            ViewValue.ArrayValue(
                listOf(
                    ViewValue.Text("org-1"), ViewValue.Text("machine-1"),
                    ViewValue.Number(2026.0), ViewValue.Number(7.0), ViewValue.Number(15.0),
                    ViewValue.Number(12.0), ViewValue.Number(34.0),
                ),
            ),
            emission.key,
        )

        val rollup = server.reduce(
            "tool:couchdbcascade",
            listOf(ViewValue.ObjectValue(first), ViewValue.ObjectValue(second)),
        ) as ViewValue.ArrayValue
        val metrics = rollup.values[0] as ViewValue.ObjectValue
        val cpu = metrics.fields.getValue("cpu_mhz") as ViewValue.ObjectValue
        assertEquals(ViewValue.Number(400.0), cpu.fields["sum"])
        assertEquals(ViewValue.Number(200.0), cpu.fields["avg"])
        assertEquals(ViewValue.Number(100.0), cpu.fields["min"])
        assertEquals(ViewValue.Number(300.0), cpu.fields["max"])
        assertEquals(ViewValue.Number(2.0), rollup.values[1])
    }

    @Test
    fun bundlesNamedCouchDbCascadeViewsIntoTheRollup() {
        val server = CommonViewServer()
        server.addFunction("tool:couchdbcascade/byBillingGroup")

        val emission = server.mapDocument(
            mapOf(
                "billing_group_id" to ViewValue.Text("billing-1"),
                "machine_id" to ViewValue.Text("machine-1"),
                "reading_date" to ViewValue.Text("2026-07-15T12:34:00Z"),
            ),
        ).single().single()

        assertEquals(
            ViewValue.ArrayValue(
                listOf(
                    ViewValue.Text("billing-1"), ViewValue.Text("machine-1"),
                    ViewValue.Number(2026.0), ViewValue.Number(7.0), ViewValue.Number(15.0),
                    ViewValue.Number(12.0), ViewValue.Number(34.0),
                ),
            ),
            emission.key,
        )
    }

    @Test
    fun cascadeIsAViewServerToolNotABuiltinReducer() {
        val server = CommonViewServer()
        val documents = listOf(
            ViewValue.ObjectValue(mapOf("cpu_mhz" to ViewValue.Number(100.0))),
            ViewValue.ObjectValue(mapOf("cpu_mhz" to ViewValue.Number(300.0))),
        )

        assertFailsWith<IllegalArgumentException> {
            server.reduce("_cascade", documents)
        }

        server.addFunction("tool:couchdbcascade/byMachine")
        val rollup = server.reduce("tool:couchdbcascade", documents) as ViewValue.ArrayValue
        val metrics = rollup.values[0] as ViewValue.ObjectValue
        val cpu = metrics.fields.getValue("cpu_mhz") as ViewValue.ObjectValue
        assertEquals(ViewValue.Number(400.0), cpu.fields["sum"])
    }
}
