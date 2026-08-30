package borg.trikeshed.couch

import borg.trikeshed.relaxfactory.CouchRequestFactory
import borg.trikeshed.relaxfactory.RelaxTransport
import borg.trikeshed.relaxfactory.RequestFactoryProxy
import borg.trikeshed.relaxfactory.ViewQuery
import borg.trikeshed.job.CasStore
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.viewserver.CouchDbCascadeTool
import borg.trikeshed.viewserver.ViewValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The confix cascade as a reducer option on the couch view path — the engine the rxfhtx service
 * actually serves. Before this it existed only as a `CommonViewServer` tool and as jvm-only
 * generated JavaScript, so no design doc and no RequestFactory `query` could select it.
 *
 * The load-bearing test is [cascadeAgreesWithTheViewServerToolItWasPortedFrom]: two independent
 * implementations of the same rollup must produce the same numbers, or "the cascade" is two
 * different things wearing one name.
 */
class CouchCascadeReducerTest {

    private fun reading(machine: String, cpu: Double, mem: Double): Map<String, Any?> = mapOf(
        "machine_id" to machine,
        "cpu_mhz" to cpu,
        "memory_mib" to mem,
        "reading_date" to "2026-08-30T00:00:00Z",
    )

    private fun docs(vararg bodies: Pair<String, Map<String, Any?>>): List<Document> =
        bodies.map { (id, body) -> Document(id, body.entries.map { Field(it.key, it.value!!) }) }

    private fun cascadeView(reduce: ReduceFunction = ReduceFunction.Cascade()) = ViewDefinition(
        ddoc = "_design/cascade",
        viewName = "byMachine",
        mapFn = MapFunction.Emit(KeyExpr.DocField("machine_id"), ValueExpr.DocValue),
        reduceFn = reduce,
    )

    @Suppress("UNCHECKED_CAST")
    private fun rollupOf(result: ViewResult, key: Any?): Map<String, Any?> {
        val row = (0 until result.size).map { result[it] }.first { it.key == key }
        return ((row.value as List<*>)[0] as Map<*, *>)
            .entries.associate { it.key.toString() to it.value }
    }

    private fun metric(rollup: Map<String, Any?>, field: String, stat: String): Double =
        ((rollup[field] as Map<*, *>)[stat] as Number).toDouble()

    // ── the two implementations must agree ────────────────────────

    @Test
    fun cascadeAgreesWithTheViewServerToolItWasPortedFrom() {
        val bodies = listOf(
            reading("m1", 100.0, 512.0),
            reading("m1", 300.0, 1536.0),
            reading("m1", 200.0, 1024.0),
        )

        // Path A: the couch ViewServer reducer, selected as a view's reduce option.
        val result = ViewServer().execute(
            cascadeView(),
            docs(*bodies.mapIndexed { i, b -> "d$i" to b }.toTypedArray()),
        )
        val mine = rollupOf(result, "m1")

        // Path B: CouchDbCascadeTool, the ViewServerTool this was ported from.
        val toolValues = bodies.map { body ->
            ViewValue.ObjectValue(body.mapValues { (_, v) ->
                when (v) {
                    is Number -> ViewValue.Number(v.toDouble())
                    else -> ViewValue.Text(v.toString())
                }
            })
        }
        val toolRollup = (CouchDbCascadeTool.reduce(toolValues) as ViewValue.ArrayValue)
            .values[0] as ViewValue.ObjectValue

        for (field in CouchCascade.METRICS) {
            val theirs = toolRollup.fields.getValue(field) as ViewValue.ObjectValue
            for (stat in listOf("sum", "avg", "min", "max")) {
                assertEquals(
                    (theirs.fields[stat] as ViewValue.Number).value,
                    metric(mine, field, stat),
                    "cascade disagrees with CouchDbCascadeTool on $field.$stat",
                )
            }
        }
        // and the count column
        @Suppress("UNCHECKED_CAST")
        val count = ((0 until result.size).map { result[it] }.first().value as List<*>)[1]
        assertEquals(3L, (count as Number).toLong())
    }

    @Test
    fun cascadeRollsUpPerKeyAndIgnoresNonNumericFields() {
        val result = ViewServer().execute(
            cascadeView(),
            docs(
                "a" to reading("m1", 100.0, 512.0),
                "b" to reading("m1", 300.0, 1536.0),
                "c" to reading("m2", 50.0, 256.0),
            ),
        )
        assertEquals(2, result.size, "one row per key")

        val m1 = rollupOf(result, "m1")
        assertEquals(400.0, metric(m1, "cpu_mhz", "sum"))
        assertEquals(200.0, metric(m1, "cpu_mhz", "avg"))
        assertEquals(100.0, metric(m1, "cpu_mhz", "min"))
        assertEquals(300.0, metric(m1, "cpu_mhz", "max"))
        assertEquals(2048.0, metric(m1, "memory_mib", "sum"))

        val m2 = rollupOf(result, "m2")
        assertEquals(50.0, metric(m2, "cpu_mhz", "sum"))

        // reading_date is a metric field but not a number; it contributes zeroes, never a throw.
        assertEquals(0.0, metric(m1, "reading_date", "sum"))
    }

    // ── rereduce: the group=false fold ────────────────────────────

    @Test
    fun rereduceFoldsPartialsInsteadOfReturningThem() {
        val grouped = ViewServer().execute(
            cascadeView(),
            docs(
                "a" to reading("m1", 100.0, 512.0),
                "b" to reading("m1", 300.0, 1536.0),
                "c" to reading("m2", 50.0, 256.0),
            ),
        )
        val folded = ViewQuery.rereduce(ReduceFunction.Cascade(), grouped)

        // Before this existed, `else -> values` handed back the per-key partial list — a list where
        // a total belongs. It must be one `[rollup, count]`.
        assertTrue(folded is List<*> && folded.size == 2, "rereduce did not fold: $folded")
        @Suppress("UNCHECKED_CAST")
        val rollup = ((folded as List<*>)[0] as Map<*, *>).entries.associate { it.key.toString() to it.value }
        assertEquals(450.0, metric(rollup, "cpu_mhz", "sum"), "sum across every key")
        assertEquals(3L, (folded[1] as Number).toLong())
        assertEquals(150.0, metric(rollup, "cpu_mhz", "avg"), "avg recomputed from totals, not averaged")
        assertEquals(50.0, metric(rollup, "cpu_mhz", "min"))
        assertEquals(300.0, metric(rollup, "cpu_mhz", "max"))
    }

    @Test
    fun rollupCountAlsoFoldsNowRatherThanReturningItsPartials() {
        val def = ViewDefinition(
            "_design/c", "byMachine",
            MapFunction.Emit(KeyExpr.DocField("machine_id"), ValueExpr.DocField("cpu_mhz")),
            ReduceFunction.Builtin("rollup-count"),
        )
        val grouped = ViewServer().execute(
            def,
            docs(
                "a" to reading("m1", 100.0, 512.0),
                "b" to reading("m1", 300.0, 1536.0),
                "c" to reading("m2", 50.0, 256.0),
            ),
        )
        val folded = ViewQuery.rereduce(ReduceFunction.Builtin("rollup-count"), grouped)
        assertTrue(folded is List<*> && folded.size == 2, "rollup-count did not fold: $folded")
        assertEquals(450.0, ((folded as List<*>)[0] as Number).toDouble())
        assertEquals(3L, (folded[1] as Number).toLong())
    }

    // ── the metric list has to be the identity, not decoration ────

    @Test
    fun theMetricListReachesTheProofReceipt() {
        val documents = docs("a" to reading("m1", 100.0, 512.0), "b" to reading("m1", 300.0, 1536.0))
        val all = ViewServer().executeWithProof(cascadeView(), documents)
        val narrowed = ViewServer().executeWithProof(cascadeView(ReduceFunction.Cascade(listOf("cpu_mhz"))), documents)

        assertNotEquals(
            all.receipt.contentId, narrowed.receipt.contentId,
            "two cascades over different metric sets minted the same receipt",
        )
        // and the narrowed one really did roll up only what it named
        assertEquals(setOf("cpu_mhz"), rollupOf(narrowed.result, "m1").keys)
    }

    // ── selectable as an option, from a design doc and the envelope ──

    @Test
    fun cascadeIsSelectableFromADesignDocAndFromTheEnvelope() = runTest {
        val cas = CasStore.inMemory()
        val db = CouchDatabase("trikeshed", CouchStoreFactory.casBacked(cas), cas)
        db.put("a", reading("m1", 100.0, 512.0), null)
        db.put("b", reading("m1", 300.0, 1536.0), null)
        val router = CouchWireRouter(db, "projects/trikeshed/")

        // A design doc names the reducer the way any other reducer is named.
        db.put(
            "_design/cascade",
            mapOf("views" to mapOf("byMachine" to mapOf(
                "map" to mapOf("key" to "machine_id", "value" to "doc"),
                "reduce" to "_cascade",
            ))),
            null,
        )
        val route = router.handle("GET", "/trikeshed/_design/cascade/_view/byMachine?group=true", ByteArray(0))!!
        assertEquals(200, route.status)
        @Suppress("UNCHECKED_CAST")
        val routeJson = JsonSupport.parse(route.bytes.decodeToString()) as Map<String, Any?>
        val routeRows = CouchDatabase.asList(routeJson["rows"])!!.map { it as Map<*, *> }
        assertEquals(1, routeRows.size)
        @Suppress("UNCHECKED_CAST")
        val routeRollup = ((routeRows[0]["value"] as List<*>)[0] as Map<*, *>)
            .entries.associate { it.key.toString() to it.value }
        assertEquals(400.0, metric(routeRollup, "cpu_mhz", "sum"))

        // And the envelope selects it inline, with the narrowing form.
        val proxy = RequestFactoryProxy(RelaxTransport.local(db))
        val receipt = proxy.query(
            mapOf(
                "name" to "byMachine", "key" to "machine_id", "value" to "doc",
                "reduce" to mapOf("cascade" to mapOf("metrics" to listOf("cpu_mhz"))),
                "group" to true,
            ),
        )
        assertTrue(receipt.ok, "envelope cascade query failed: $receipt")
        @Suppress("UNCHECKED_CAST")
        val envRollup = ((receipt.rows[0]["value"] as List<*>)[0] as Map<*, *>)
            .entries.associate { it.key.toString() to it.value }
        assertEquals(setOf("cpu_mhz"), envRollup.keys)
        assertEquals(400.0, metric(envRollup, "cpu_mhz", "sum"))
        assertTrue(!receipt.proofCid.isNullOrEmpty(), "a cascade query still carries its proof")
    }

    @Test
    fun theCascadeVocabularyHasOneDefinition() {
        // CouchDbCascadeTool reads the canonical list rather than keeping its own copy; if someone
        // reintroduces a second list, the tool and the reducer stop agreeing and this catches it.
        val toolMapper = CouchDbCascadeTool.mapper("byMachine")
        assertTrue(CouchCascade.VIEWS.containsKey("byMachine"))
        assertEquals(10, CouchCascade.METRICS.size)
        val emitted = toolMapper.map(
            mapOf(
                "machine_id" to ViewValue.Text("m1"),
                "reading_date" to ViewValue.Text("2026-08-30T12:34:00Z"),
            ),
        )
        assertEquals(1, emitted.size, "the tool still maps through the shared view table")
    }
}
