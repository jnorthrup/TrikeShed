package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** A clarification request is not a terminal ruling, even at the round bound. */
class CouncilClarificationTest {
    private fun assertUnpublished(store: InMemoryRecordStore) {
        assertTrue(store.cas.isEmpty(), "no durable bytes before decision admission")
        assertTrue(store.couch.isEmpty(), "no prepared or published case")
        assertTrue(store.blackboard.isEmpty(), "no index fact")
        assertTrue(store.kif.isEmpty(), "no prior-ruling evidence")
        assertTrue(store.rulings.isEmpty(), "no completed lifecycle")
        assertTrue(store.mistrials.isEmpty(), "clarification is not an inferred mistrial")
    }

    private fun registry(store: InMemoryRecordStore, dialog: CouncilDialog): Map<String, LcncNodeRunner> =
        CouncilNodes.registry(dialog, RecordSeams.inMemory(store)) + mapOf(
            "legal.ingest" to LcncNodeRunner { _, _ -> mapOf(
                "documentCid" to ContentId.of("synthetic evidence".encodeToByteArray()).value,
                "brief" to "synthetic evidence",
            ) },
            "legal.evidence" to LcncNodeRunner { _, inputs -> mapOf(
                "brief" to (inputs["brief?"] ?: inputs["brief"]),
            ) },
        )

    @Test
    fun recordRefusesUnresolvedClarificationBeforePersistence() = runTest {
        for (flag in listOf<Any>(true, "true")) {
            val store = InMemoryRecordStore()
            val runners = registry(store, CouncilDialog { error("no model dispatch") })
            val failure = assertFailsWith<IllegalArgumentException> {
                runners.getValue("council.record").run(
                    LcncNode("record", "council.record"),
                    mapOf("verdict" to mapOf("disposition" to "pending",
                        "needsClarification" to flag, "clarificationQuestion" to "Missing evidence?")),
                )
            }
            assertTrue(failure.message.orEmpty().contains("unresolved clarification"))
            assertUnpublished(store)
        }
    }

    @Test
    fun unresolvedFinalRoundDoesNotPublishOrExtendTheBound() = runTest {
        val store = InMemoryRecordStore()
        val calls = ArrayList<SeatCall>()
        val dialog = CouncilDialog { call ->
            calls.add(call)
            SeatOutcome.Ok(if (call.role == "ruling") jsonOf(mapOf(
                "disposition" to "pending", "needsClarification" to true,
                "clarificationQuestion" to "Missing evidence?", "mistrial" to false,
            )) else "synthetic testimony", "fixture")
        }
        val program = CouncilProgram.build(CouncilConfig(
            caseId = "clarification-bound", panels = listOf(PanelSpec("record", "record sufficiency", listOf("skeptic"))),
            rounds = 1, roster = listOf("fixture"),
        ))
        val failure = assertFailsWith<IllegalArgumentException> {
            LcncRunner(registry(store, dialog)).runProcedure(program)
        }
        assertTrue(failure.message.orEmpty().contains("unresolved clarification"))
        assertEquals(5, calls.size, "expert, synthesis, initial judge, clarification, final judge")
        assertEquals(1, calls.count { it.seat == "ruling-final" })
        assertEquals(1, calls.count { it.role == "clarify" })
        assertUnpublished(store)
    }

    @Test
    fun disablingClarificationDoesNotTurnItsRequestIntoARuling() = runTest {
        val store = InMemoryRecordStore()
        val calls = ArrayList<SeatCall>()
        val dialog = CouncilDialog { call ->
            calls.add(call)
            SeatOutcome.Ok(if (call.role == "ruling") jsonOf(mapOf(
                "disposition" to "pending", "needsClarification" to true,
                "clarificationQuestion" to "Missing evidence?", "mistrial" to false,
            )) else "synthetic testimony", "fixture")
        }
        val program = CouncilProgram.build(CouncilConfig(
            caseId = "clarification-disabled", panels = listOf(PanelSpec("record", "record sufficiency", listOf("skeptic"))),
            rounds = 1, clarify = false, roster = listOf("fixture"),
        ))
        assertFailsWith<IllegalArgumentException> { LcncRunner(registry(store, dialog)).runProcedure(program) }
        assertEquals(3, calls.size)
        assertTrue(calls.none { it.role == "clarify" || it.seat == "ruling-final" })
        assertUnpublished(store)
    }

    @Test
    fun explicitMistrialStillRecordsFailureWithoutRulingEvidence() = runTest {
        val store = InMemoryRecordStore()
        val output = registry(store, CouncilDialog { error("no model dispatch") }).getValue("council.record").run(
            LcncNode("record", "council.record", params = mapOf("caseId" to "void")),
            mapOf("verdict" to mapOf("disposition" to "void", "needsClarification" to true, "mistrial" to true),
                "documentCid?" to ContentId.of("synthetic evidence".encodeToByteArray()).value),
        )
        assertEquals("mistrial", (output["report"] as Map<*, *>)["status"])
        assertEquals(1, store.mistrials.size)
        assertTrue(store.rulings.isEmpty())
        assertTrue(store.kif.isEmpty())
    }

    @Test
    fun explicitMistrialCannotBeOverriddenByAClarificationRound() = runTest {
        val store = InMemoryRecordStore()
        val calls = ArrayList<SeatCall>()
        val dialog = CouncilDialog { call ->
            calls.add(call)
            SeatOutcome.Ok(when (call.seat) {
                "ruling" -> jsonOf(mapOf("disposition" to "void", "needsClarification" to true,
                    "clarificationQuestion" to "Missing evidence?", "mistrial" to true))
                "ruling-final" -> jsonOf(mapOf("disposition" to "granted", "needsClarification" to false,
                    "clarificationQuestion" to "", "mistrial" to false))
                else -> "synthetic testimony"
            }, "fixture")
        }
        val program = CouncilProgram.build(CouncilConfig(
            caseId = "mistrial-precedence", panels = listOf(PanelSpec("record", "record sufficiency", listOf("skeptic"))),
            rounds = 1, roster = listOf("fixture"),
        ))
        val result = LcncRunner(registry(store, dialog)).runProcedure(program)
        assertEquals(3, calls.size, "a declared mistrial is terminal, not another model round")
        val report = result.returns["ruling"] as Map<*, *>
        assertEquals("mistrial", report["status"])
        val retained = reify(store.cas.getValue(report["verdictCid"] as String).decodeToString()) as Map<*, *>
        assertEquals(true, retained["needsClarification"], "control guards must not rewrite evidence")
        assertEquals(true, retained["mistrial"])
        assertEquals("void", retained["disposition"])
        assertEquals(1, store.mistrials.size)
        assertTrue(store.rulings.isEmpty())
        assertTrue(store.kif.isEmpty())
    }
}
