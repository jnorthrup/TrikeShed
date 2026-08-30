package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * THE scripted-dialog council e2e (design brief: "Test seam" + "Degrade
 * loudly"): the full 3x5 preset walks through the ONE concentric executor
 * with a canned [CouncilDialog] and in-memory record seams — ZERO model
 * spend. Pinned: the seat census (34 main, +4 only when clarify sits), the
 * evidence-closed prompt diet, the judge-diet fold (brief + positions), both
 * guarded rings in both directions, coalesce's clarified-over-original pick,
 * the loud per-seat failure banner flowing downstream, the commit-time
 * mistrial rule, and the full record (CAS + blackboard index + KIF + case
 * lifecycle spies).
 */
class LcncCouncilTest {

    private val DOC = "The lessee seeks return of the deposit; the lessor pleads setoff."

    /** Recording dialog: every SeatCall captured, answers by [answer]. */
    private class Script(val answer: (SeatCall) -> SeatOutcome) : CouncilDialog {
        val calls = ArrayList<SeatCall>()
        override suspend fun seat(call: SeatCall): SeatOutcome {
            calls.add(call)
            return answer(call)
        }
    }

    private fun rulingJson(
        disposition: String,
        needsClarification: Boolean = false,
        question: String = "",
        mistrial: Boolean = false,
    ): String = "I have weighed the record before the council.\n" +
        "{\"disposition\":\"$disposition\",\"needsClarification\":$needsClarification," +
        "\"clarificationQuestion\":\"$question\",\"mistrial\":$mistrial}"

    private fun take(call: SeatCall): SeatOutcome =
        SeatOutcome.Ok("[${call.panel}/${call.seat}] take on: ${call.role}", call.preferredModel ?: "scripted-model")

    /** council runners over spyable in-memory seams + stubs for the two legal nodes. */
    private fun registryFor(dialog: CouncilDialog, store: InMemoryRecordStore): Map<String, LcncNodeRunner> =
        CouncilNodes.registry(dialog, RecordSeams.inMemory(store)) + mapOf(
            // legal.ingest stub: reads nothing (the preset feeds it the root
            // `document` binding via its brief param in production); emits a
            // deterministic documentCid and a marked brief.
            "legal.ingest" to LcncNodeRunner { _, _ ->
                mapOf(
                    "documentCid" to ContentId.of(DOC.encodeToByteArray()).value,
                    "citations" to emptyList<Any?>(),
                    "elements" to emptyList<Any?>(),
                    "brief" to "BRIEF-MARK $DOC",
                )
            },
            // legal.evidence stub: folds a corpus marker into the brief.
            "legal.evidence" to LcncNodeRunner { _, inputs ->
                val brief = (inputs["brief?"] ?: inputs["brief"])?.toString().orEmpty()
                mapOf("brief" to "$brief\nEVIDENCE-MARK banked facts")
            },
        )

    private suspend fun convene(
        dialog: CouncilDialog,
        store: InMemoryRecordStore,
        caseId: String = "case-7",
    ): LcncRunner.ScopeResult =
        LcncRunner(registryFor(dialog, store)).runProcedure(
            CouncilProgram.build(CouncilConfig.DEFAULT_3x5),
            mapOf("document" to DOC, "caseId" to caseId),
        )

    private fun report(result: LcncRunner.ScopeResult): Map<*, *> {
        val r = result.returns["ruling"]
        assertNotNull(r, "the walk must yield returns.ruling — never a silent empty ruling")
        return r as Map<*, *>
    }

    // ── Variant A: the happy 3x5 walk — census, diet, record, zero spend ─

    @Test
    fun fullCouncilRulesOnTheRecordWithZeroSpend() = runTest {
        val store = InMemoryRecordStore()
        val script = Script { call ->
            if (call.role == "ruling") SeatOutcome.Ok(rulingJson("granted"), call.preferredModel ?: "scripted-model")
            else take(call)
        }
        val result = convene(script, store)

        // (1) exactly 34 seat calls; the clarify ring never sat.
        assertEquals(34, script.calls.size, "3x(5+5+1) + 1 ruling = 34 seats")
        assertTrue(
            script.calls.none { it.seat.startsWith("clarify") || it.seat == "ruling-final" },
            "clarify ring must be skipped on a strict-false guard",
        )

        // (2) the report: ruled, fully counted, cids minted.
        val rep = report(result)
        assertEquals("ruled", rep["status"])
        assertEquals(0, rep["seatFailures"])
        assertEquals(34, rep["seatCount"])
        assertEquals(0, rep["cidMismatches"])
        val verdictCid = rep["verdictCid"] as String
        val transcriptCid = rep["transcriptCid"] as String
        val caseCid = rep["caseCid"] as String
        assertTrue(verdictCid.isNotBlank() && transcriptCid.isNotBlank() && caseCid.isNotBlank())

        // (3) EVIDENCE-CLOSED: the first expert argues over the banked brief,
        // and the judge's diet is evidence AND all three panel positions.
        val e1 = script.calls.first { it.panel == "p1" && it.seat == "e1" }
        assertTrue("EVIDENCE-MARK" in e1.prompt, "expert prompts carry legal.evidence output")
        assertTrue("BRIEF-MARK" in e1.prompt, "expert prompts carry the ingest brief")
        val ruling = script.calls.first { it.role == "ruling" }
        assertTrue("EVIDENCE-MARK" in ruling.prompt, "judge-diet graft: the evidence brief reaches the ruling")
        for (p in 1..3) assertTrue(
            "[p$p.synth · synthesis" in ruling.prompt,
            "judge-diet graft: panel p$p's attributed position reaches the ruling",
        )

        // (4) the record: CAS bytes, blackboard index fact, KIF assertion,
        // lifecycle spy — the graft-#7 shape.
        assertTrue(store.cas.containsKey(verdictCid), "verdict bytes on CAS")
        assertTrue(store.cas.containsKey(transcriptCid), "transcript bytes on CAS")
        assertTrue(store.cas.containsKey(caseCid), "case doc bytes on CAS")
        val index = store.blackboard["council-case/case-7"]
        assertNotNull(index, "index fact landed")
        assertEquals(caseCid, index["caseCid"])
        assertEquals(verdictCid, index["verdictCid"])
        assertEquals(ContentId.of(DOC.encodeToByteArray()).value, index["documentCid"])
        assertEquals("ruled", index["status"])
        assertTrue(store.couch.containsKey("council-case/case-7"), "durable couch case doc")
        assertTrue(
            store.kif.any { it.startsWith("(ruling case_case-7 doc_") },
            "the (ruling …) fact is asserted (its reader is legal.evidence's corpus mode): ${store.kif}",
        )
        assertEquals(1, store.rulings.size, "recordRuling spied exactly once")
        assertEquals(Triple("case-7", verdictCid, transcriptCid), store.rulings[0])
        assertTrue(store.mistrials.isEmpty(), "no mistrial on the happy path")
    }

    // ── Variant B: clarify ring — entered once, coalesce picks clarified ─

    @Test
    fun clarifyRingSitsOnceAndTheClarifiedVerdictWins() = runTest {
        val store = InMemoryRecordStore()
        val script = Script { call ->
            when (call.seat) {
                "ruling" -> SeatOutcome.Ok(
                    rulingJson("", needsClarification = true, question = "What is the standard of proof?"),
                    "m",
                )
                "ruling-final" -> SeatOutcome.Ok(rulingJson("denied"), "m")
                else -> take(call)
            }
        }
        val result = convene(script, store)

        assertEquals(38, script.calls.size, "34 main seats + 3 clarify voices + 1 final ruling")
        for (seat in listOf("clarify1", "clarify2", "clarify3", "ruling-final")) {
            assertEquals(1, script.calls.count { it.seat == seat },
                "structural bound of 1: '$seat' sits exactly once")
        }
        assertTrue(
            "standard of proof" in script.calls.first { it.seat == "clarify1" }.prompt,
            "the presiding question reaches the clarify voices",
        )

        val rep = report(result)
        assertEquals("ruled", rep["status"])
        assertEquals(38, rep["seatCount"], "clarify turns are on the record")
        val verdictBytes = store.cas.getValue(rep["verdictCid"] as String).decodeToString()
        assertTrue("denied" in verdictBytes, "coalesce picked the CLARIFIED verdict: $verdictBytes")
        val transcript = store.cas.getValue(rep["transcriptCid"] as String).decodeToString()
        assertTrue("denied" in transcript, "the clarified ruling text is on the transcript")
    }

    // ── Variant C: one refused seat — loud banner, walk survives ─────────

    @Test
    fun oneRefusedSeatDegradesLoudlyAndTheWalkStillRules() = runTest {
        val store = InMemoryRecordStore()
        val script = Script { call ->
            when {
                call.panel == "p2" && call.seat == "e3" ->
                    SeatOutcome.Refused("no key for provider", listOf("nvidia/kimi: no key", "openai/gpt: 401"))
                call.role == "ruling" -> SeatOutcome.Ok(rulingJson("granted"), "m")
                else -> take(call)
            }
        }
        val result = convene(script, store)
        val rep = report(result)
        assertEquals("ruled", rep["status"], "one dead seat does not void the proceedings")
        assertEquals(1, rep["seatFailures"])
        assertEquals(34, rep["seatCount"])

        val transcript = store.cas.getValue(rep["transcriptCid"] as String).decodeToString()
        assertTrue(
            "[p2.e3 · expert · round 1 · FAILED:" in transcript,
            "the failure banner with its failover trail is ON the record",
        )
        // (round-2 filter alone would also catch p2.synth, whose prompt is the
        // round-2 fold — the banner rides ROUND-1's fold into the rebuttals)
        val rebuttals = script.calls.filter { it.panel == "p2" && it.role == "rebuttal" }
        assertTrue(rebuttals.isNotEmpty())
        assertTrue(
            rebuttals.all { "[SEAT FAILED: p2.e3]" in it.prompt },
            "the banner flows into every downstream rebuttal prompt — degrade loudly",
        )
    }

    // ── Variant D: every seat refused — commit-time mistrial, never silent ─

    @Test
    fun allSeatsRefusedIsAMistrialOnTheRecord() = runTest {
        val store = InMemoryRecordStore()
        val script = Script {
            SeatOutcome.Refused("no provider answered", listOf("nvidia/deepseek: no key"))
        }
        val result = convene(script, store)
        assertEquals(34, script.calls.size, "banners still walk every seat — no ring is skipped by failure")

        val rep = report(result)
        assertEquals("mistrial", rep["status"])
        assertEquals(34, rep["seatFailures"])
        assertEquals(34, rep["seatCount"])
        assertEquals(1, store.mistrials.size, "recordMistrial spied")
        assertEquals("case-7", store.mistrials[0].first)
        assertTrue(store.rulings.isEmpty(), "no ruling recorded for a dead council")
        val verdictBytes = store.cas.getValue(rep["verdictCid"] as String).decodeToString()
        assertTrue("[SEAT FAILED: council.ruling]" in verdictBytes, "the ruling banner IS the recorded verdict")
    }

    // ── Variant E: the judge declares mistrial — the guarded ring sits ───

    @Test
    fun judgeDeclaredMistrialEntersTheMistrialRing() = runTest {
        val store = InMemoryRecordStore()
        val script = Script { call ->
            if (call.role == "ruling") SeatOutcome.Ok(rulingJson("", mistrial = true), "m")
            else take(call)
        }
        val result = convene(script, store)
        assertEquals(34, script.calls.size, "the mistrial ring seats no models")

        val rep = report(result)
        assertEquals("mistrial", rep["status"])
        assertEquals(1, store.mistrials.size, "recordMistrial spied")
        val transcript = store.cas.getValue(rep["transcriptCid"] as String).decodeToString()
        assertTrue(
            "MISTRIAL — proceedings void" in transcript,
            "the mistrial ring's fold lands on the transcript",
        )
    }
}
