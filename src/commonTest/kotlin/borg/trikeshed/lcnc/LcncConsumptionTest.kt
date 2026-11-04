package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Observations must describe the inputs delivered by the existing executor, not every drawn edge. */
class LcncConsumptionTest {
    private fun source(id: String, value: String = id) = LcncNode(id, "test.source", _m["value" j value])
    private fun sink(id: String = "sink", type: String = "test.sink") = LcncNode(id, type)
    private val runners = _m[
        "test.source" j LcncNodeRunner { node, _ -> _m["value" j node.params["value"]] },
        "test.empty" j LcncNodeRunner { _, _ -> emptyMap() },
        "test.sink" j LcncNodeRunner { _, inputs -> _m["value" j inputs["x"]] },
        "display" j LcncNodeRunner { _, inputs -> _m["value" j inputs["x"]] },
        "test.items" j LcncNodeRunner { _, _ -> _m["value" j _l["first", "second"]] },
    ]
    private fun walker(ledger: LcncInvocationLedger) = LcncRunner(runners).apply { invocations = ledger }
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun rows(value: Any?) = value as List<Map<String, Any?>>
    private fun consumed(ledger: LcncInvocationLedger) = rows(ledger.snapshot()["observations"]).filter { it["phase"] == "consumed" }
    private fun cid(program: LcncProgram) = ContentId.of(LcncProgramConfix.toJson(program).encodeToByteArray()).value

    @Test fun singlePortRecordsOnlyTheLastFedWireIncludingDuplicateEndpoints() = runTest {
        val ledger = LcncInvocationLedger()
        val program = LcncProgram("single", s_[source("a"), source("b"), LcncNode("empty", "test.empty"), sink()], s_[
            LcncWire("a", "value", "sink", "x"), LcncWire("b", "value", "sink", "x"),
            LcncWire("a", "value", "sink", "x"), LcncWire("b", "value", "sink", "x"),
            LcncWire("empty", "value", "sink", "x")])
        assertEquals("b", walker(ledger).runAll(program).getValue("sink")["value"])
        val event = consumed(ledger).single()
        assertEquals(3, map(event["wire"])["index"])
        assertEquals("b", map(event["producer"])["nodeId"])
        assertEquals(2, map(event["producer"])["invocationOrdinal"])
        assertEquals(cid(program), event["documentCid"])
        assertEquals("one", map(event["selection"])["cardinality"])
        assertEquals(1, map(event["selection"])["count"])
        assertEquals("unavailable", map(event["evidence"])["status"])
    }

    @Test fun manyPortsRetainAuthoredPositionsAndTheirExistingSingleValueShape() = runTest {
        val ledger = LcncInvocationLedger()
        val program = LcncProgram("many", s_[source("a"), source("b"), sink("many", "display"), sink("one", "display")], s_[
            LcncWire("a", "value", "many", "x"), LcncWire("b", "value", "many", "x"),
            LcncWire("a", "value", "many", "x"), LcncWire("a", "value", "one", "x")])
        val outputs = walker(ledger).runAll(program)
        assertEquals(_l["a", "b", "a"], outputs.getValue("many")["value"])
        assertEquals("a", outputs.getValue("one")["value"])
        val many = consumed(ledger).filter { it["nodeId"] == "many" }
        assertEquals(_l[0, 1, 2], many.map { map(it["wire"])["index"] })
        assertEquals(_l[0, 1, 2], many.map { map(it["selection"])["position"] })
        assertTrue(many.all { map(it["selection"])["count"] == 3 && map(it["selection"])["representation"] == "list" })
        val one = consumed(ledger).single { it["nodeId"] == "one" }
        assertEquals("many", map(one["selection"])["cardinality"])
        assertEquals("scalar", map(one["selection"])["representation"])
    }

    @Test fun missingRequiredInputAndGuardedScopesDoNotEmitConsumedSuccess() = runTest {
        val ledger = LcncInvocationLedger()
        val ring = LcncNode("ring", LcncContracts.SCOPE, children = s_[sink("inside")])
        val program = LcncProgram("skip", s_[source("a"), source("false", "false"), LcncNode("empty", "test.empty"), sink(), ring], s_[
            LcncWire("a", "value", "sink", "x"), LcncWire("empty", "value", "sink", "y"),
            LcncWire("deleted", "value", "sink", "optional?"), LcncWire("false", "value", "ring", "when?")])
        val debug = LcncDebug(stepping = false)
        try {
            val result = withContext(debug) { walker(ledger).runAll(program) }
            assertFalse("sink" in result); assertFalse("ring" in result)
            assertTrue(consumed(ledger).isEmpty())
            val events = rows(debug.snapshot()["observations"])
            assertEquals(_s["sink", "ring"], events.filter { it["phase"] == "after" && it["status"] == "skipped" }.map { it["nodeId"] }.toSet())
            assertFalse(events.any { it["nodeId"] == "inside" })
        } finally { debug.close() }
    }

    @Test fun repeatedNamedFramesUseTheirActualProducerAndDocumentIdentity() = runTest {
        val ledger = LcncInvocationLedger()
        val body = LcncProgram("body", s_[
            LcncNode("same", LcncContracts.SCOPE_IN, _m["name" j "item"]), sink("consumer"),
            LcncNode("yield", LcncContracts.SCOPE_OUT, _m["name" j "value"])], s_[
            LcncWire("same", "value", "consumer", "x"), LcncWire("consumer", "value", "yield", "value")])
        val root = LcncProgram("root", s_[source("same", "outer"), LcncNode("items", "test.items"),
            LcncNode("call", LcncContracts.SCOPE, subprogram = "body")], s_[LcncWire("items", "value", "call", "each?")])
        val runner = walker(ledger).apply { subprogramLoader = { if (it == "body") body else null } }
        assertEquals(_l["first", "second"], runner.runAll(root).getValue("call")["value"])
        val events = consumed(ledger).filter { it["nodeId"] == "consumer" }
        assertEquals(2, events.size)
        assertEquals(_l[0, 1], events.map { it["iteration"] })
        assertEquals(2, events.map { it["frameId"] }.toSet().size)
        assertTrue(events.all { it["path"] == _l["body"] && it["documentCid"] == cid(body) && it["parent"] == 3 })
        for (event in events) {
            val producer = map(event["producer"])
            assertEquals("same", producer["nodeId"])
            assertEquals(event["frameId"], producer["frameId"])
            assertEquals(event["iteration"], producer["iteration"])
            assertEquals(cid(body), producer["documentCid"])
            assertNotEquals(1, producer["invocationOrdinal"])
        }
        assertEquals(events.size, events.map { it["sequence"] }.toSet().size)
    }

    @Test fun inlineConsumptionReferencesTheEnclosingOutputOwner() = runTest {
        val ledger = LcncInvocationLedger()
        val root = LcncProgram("inline", s_[source("outside"),
            LcncNode("ring", LcncContracts.SCOPE, children = s_[sink("inside")])],
            s_[LcncWire("outside", "value", "inside", "x")])
        walker(ledger).runAll(root)
        val event = consumed(ledger).single()
        assertEquals(_l["ring"], event["path"])
        assertEquals(1, event["frameId"])
        assertEquals(0, map(event["producer"])["frameId"])
        assertEquals(emptyList<String>(), map(event["producer"])["path"])
        assertEquals(cid(root), event["documentCid"])
    }

    @Test fun omittedSelectionsAndWindowEvictionKeepExactCountsAndPositions() = runTest {
        val ledger = LcncInvocationLedger(observationLimit = 8)
        val program = LcncProgram("bounded", s_[source("a"), sink(type = "display")],
            300 j { _: Int -> LcncWire("a", "value", "sink", "x") })
        val result = walker(ledger).runAll(program)
        assertEquals(300, (result.getValue("sink")["value"] as List<*>).size)
        val report = ledger.snapshot()
        val events = consumed(ledger)
        assertEquals(8, events.size)
        assertEquals((292 until 300).toList(), events.map { map(it["selection"])["position"] })
        assertEquals(292L, report["observationsDropped"])
        val summary = map(report["observationSummary"])
        assertEquals(300L, summary["attempted"])
        assertEquals(293L, summary["firstSequence"])
        assertEquals(300L, summary["lastSequence"])
        assertEquals("partial", summary["coverage"])
        assertEquals("lcnc-observations/1", report["observationSchema"])
        assertTrue(events.all { map(it["selection"])["count"] == 300 })
    }

    private suspend fun held(debug: LcncDebug): Map<String, Any?> = withTimeout(5_000) {
        while (true) {
            val snapshot = debug.snapshot()
            if (snapshot["state"] == "paused") return@withTimeout snapshot
            yield()
        }
        error("unreachable")
    }

    @Test fun interventionReplacesWireAttributionAndSharesTheAcknowledgedControlSequence() = runTest {
        val ledger = LcncInvocationLedger()
        val debug = LcncDebug(stepping = false)
        debug.select(_s["sink"])
        val program = LcncProgram("intervention", s_[source("a"), sink()], s_[LcncWire("a", "value", "sink", "x")])
        try {
            val run = async(debug) { walker(ledger).runAll(program) }
            val paused = held(debug)
            val ordinal = paused["ordinal"] as Int
            assertTrue(debug.intervene(ordinal, inputs = _m["x" j "replacement"]))
            assertTrue(debug.command(ordinal, LcncDebug.Command.CONTINUE))
            assertEquals("replacement", run.await().getValue("sink")["value"])
            val event = consumed(ledger).single()
            assertEquals("intervention", event["origin"])
            assertFalse("wire" in event); assertFalse("producer" in event)
            val snapshot = debug.snapshot()
            val intervention = rows(snapshot["interventions"]).single()
            assertEquals("applied", intervention["status"])
            assertEquals(intervention["sequence"], event["interventionSequence"])
            assertEquals(rows(ledger.snapshot()["observations"]), rows(snapshot["observations"]))
            val events = rows(snapshot["observations"])
            assertEquals((1L..events.size.toLong()).toList(), events.map { it["sequence"] })
            assertTrue(events.zipWithNext().all { (a, b) -> (a["elapsedNanos"] as Long) <= (b["elapsedNanos"] as Long) })
            assertEquals("walk-start", map(snapshot["observationSummary"])["clockOrigin"])
        } finally { debug.close() }
    }

    @Test fun cancelledPausedConsumerDoesNotClaimWireDelivery() = runTest {
        val ledger = LcncInvocationLedger()
        val debug = LcncDebug(stepping = false)
        debug.select(_s["sink"])
        val program = LcncProgram("cancel", s_[source("a"), sink()], s_[LcncWire("a", "value", "sink", "x")])
        try {
            val run = async(debug) { walker(ledger).runAll(program) }
            held(debug)
            assertTrue(debug.cancel())
            assertFailsWith<LcncDebug.Cancelled> { run.await() }
            assertTrue(consumed(ledger).isEmpty())
            assertEquals("cancelled", rows(ledger.snapshot()["entries"]).last()["status"])
        } finally { debug.close() }
    }
}
