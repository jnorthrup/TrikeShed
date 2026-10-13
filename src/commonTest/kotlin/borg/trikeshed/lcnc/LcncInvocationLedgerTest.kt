package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.parse.ValueBudget
import borg.trikeshed.runBlocking
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class LcncInvocationLedgerTest {
    private fun rows(ledger: LcncInvocationLedger) =
        (ledger.snapshot().getValue("entries") as List<*>).map { it as Map<*, *> }

    @Test fun nestedIterationsKeepBothAttemptsAndTheOriginalFailure(): Unit = runBlocking {
        val body = LcncProgram("body", s_[
            LcncNode("in", "scope.in", mapOf("name" to "item")),
            LcncNode("work", "test.work"),
            LcncNode("out", "scope.out", mapOf("name" to "value")),
        ], s_[LcncWire("in", "value", "work", "x"), LcncWire("work", "y", "out", "value")])
        val program = LcncProgram("root", s_[LcncNode("source", "test.items"), LcncNode("ring", "scope", subprogram = "body")],
            s_[LcncWire("source", "items", "ring", "each?")])
        val failure = IllegalStateException("second item failed")
        val ledger = LcncInvocationLedger()
        val runner = LcncRunner(mapOf(
            "test.items" to LcncNodeRunner { _, _ -> mapOf("items" to listOf("first", "second")) },
            "test.work" to LcncNodeRunner { _, inputs ->
                if (inputs["x"] == "second") throw failure
                mapOf("y" to inputs["x"])
            },
        )).apply { invocations = ledger; subprogramLoader = { body } }
        assertSame(failure, assertFailsWith<IllegalStateException> { runner.runProcedure(program) })
        val all = rows(ledger)
        val calls = all.filter { it["nodeId"] == "work" }
        assertEquals(listOf(0, 1), calls.map { it["iteration"] })
        assertEquals(listOf("first", "second"), calls.map { (it["inputs"] as Map<*, *>)["x"] })
        assertEquals(listOf("completed", "failed"), calls.map { it["status"] })
        assertTrue(calls.all { it["path"] == listOf("body") })
        val ring = all.single { it["nodeId"] == "ring" }
        assertEquals("failed", ring["status"])
        assertTrue(calls.all { it["parent"] == ring["ordinal"] })
        assertFalse(calls.last().containsKey("outputs"), "failure cannot borrow an earlier iteration's outputs")
        assertEquals("second item failed", calls.last()["reason"])
        assertEquals(all.size, all.map { it["ordinal"] }.distinct().size)
        assertEquals(7, ledger.attempted)
    }

    @Test fun snapshotsDoNotAliasMutableInputsOrOutputs(): Unit = runBlocking {
        val value = mutableListOf("before")
        val ledger = LcncInvocationLedger()
        val runner = LcncRunner(mapOf(
            "source" to LcncNodeRunner { _, _ -> mapOf("x" to value) },
            "mutate" to LcncNodeRunner { _, _ -> value[0] = "after"; mapOf("x" to value) },
        )).apply { invocations = ledger }
        val result = runner.runProcedure(LcncProgram("mutation", s_[LcncNode("s", "source"), LcncNode("m", "mutate")],
            s_[LcncWire("s", "x", "m", "x")]))
        value[0] = "later"
        val all = rows(ledger)
        assertEquals(listOf("before"), (all[0]["outputs"] as Map<*, *>)["x"])
        assertEquals(listOf("before"), (all[1]["inputs"] as Map<*, *>)["x"])
        assertEquals(listOf("after"), (all[1]["outputs"] as Map<*, *>)["x"])
        assertSame(value, result.nodeOutputs.getValue("m")["x"], "observing must not replace the executed value")
    }

    @Test fun boundedTailAndValueOmissionsDoNotChangeExecution(): Unit = runBlocking {
        val ledger = LcncInvocationLedger(limit = 2)
        val opaque = object { override fun toString(): String = error("must not stringify a host object") }
        val output = mapOf("host" to opaque)
        val runner = LcncRunner(mapOf("source" to LcncNodeRunner { _, _ -> output })).apply { invocations = ledger }
        val result = runner.runProcedure(LcncProgram("bounded", (1..4).map { LcncNode("n$it", "source") }.toSeries(), emptySeriesOf()))
        assertSame(opaque, result.nodeOutputs.getValue("n4")["host"])
        assertEquals(listOf(3, 4), rows(ledger).map { it["ordinal"] })
        assertTrue(rows(ledger).all { it["outputsOmitted"] == "unsupported_value" && !it.containsKey("outputs") })
        assertEquals(2, ledger.snapshot()["dropped"])
        assertEquals(true, ledger.snapshot()["truncated"])
        assertNull(ValueBudget().violation(ledger.snapshot()))
        runner.runProcedure(LcncProgram("empty", emptySeriesOf(), emptySeriesOf()))
        assertEquals(0, ledger.attempted)
        assertTrue(rows(ledger).isEmpty())
    }

    @Test fun cancellationAndSkippedNodesAreNotReportedCompleted(): Unit = runBlocking {
        val ledger = LcncInvocationLedger()
        val cancelled = CancellationException("fixture cancellation")
        val runner = LcncRunner(mapOf("cancel" to LcncNodeRunner { _, _ -> throw cancelled })).apply { invocations = ledger }
        val program = LcncProgram("cancel", s_[LcncNode("unbound", "scope.in", mapOf("name" to "x")),
            LcncNode("yield", "scope.out", mapOf("name" to "x")), LcncNode("work", "cancel")], emptySeriesOf())
        assertSame(cancelled, assertFailsWith<CancellationException> { runner.runProcedure(program) })
        assertEquals(listOf("skipped", "skipped", "cancelled"), rows(ledger).map { it["status"] })
        assertEquals(listOf("unbound", "unfed", "fixture cancellation"), rows(ledger).map { it["reason"] })
    }

    @Test fun totalBudgetAndNonJsonValuesRemainExplicit() {
        val ledger = LcncInvocationLedger()
        repeat(128) { i ->
            ledger.begin(i + 1, LcncNode("n$i", "source"), emptyList(), null, null)!!.apply {
                capture("inputs", mapOf("text" to "x".repeat(1900)))
                finish("completed", null, mapOf("text" to "y".repeat(1900)))
            }
        }
        assertNull(ValueBudget().violation(ledger.snapshot()))
        assertTrue((ledger.snapshot()["dropped"] as Int) > 0)
        assertEquals(128, rows(ledger).last()["ordinal"])
        ledger.begin(129, LcncNode("invalid", "source"), emptyList(), null, null)!!.apply {
            capture("inputs", mapOf("x" to Double.NaN))
            finish("completed", null, mapOf("x" to "x".repeat(3000)))
        }
        val last = rows(ledger).last()
        assertEquals("unsupported_or_unreadable_value", last["inputsOmitted"])
        assertEquals("payload_limit", last["outputsOmitted"])
    }
}
