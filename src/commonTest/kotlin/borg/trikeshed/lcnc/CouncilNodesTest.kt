package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The council node family under scripted dialogs and in-memory seams —
 * zero model spend, every behavior the geometry leans on pinned: the seat's
 * labeled header and never-throws degrade, the folds' scalar-unwrap
 * tolerance, ruling.parse's strict-false booleans (ring guards must never
 * see truthy garbage), coalesce's blank-loses rule, and council.record's
 * commit-time cid verification + mistrial rule + index/kif/lifecycle
 * side effects, round-tripped back out through council.case.
 */
class CouncilNodesTest {

    private fun seatNode(
        id: String = "p1.e1",
        panel: String = "p1",
        seat: String = "e1",
        role: String = "expert",
        round: String = "1",
        model: String = "z-ai/glm-5.2",
    ): LcncNode = LcncNode(
        id, "council.seat",
        params = mapOf(
            "panel" to panel, "seat" to seat, "role" to role, "round" to round,
            "charge" to "the charge", "system" to "the system", "model" to model,
            "maxTokens" to "500", "temperature" to "0.8",
            "contextId" to "council/default/$panel/$seat", "caseId" to "default",
        ),
    )

    private fun registry(
        dialog: CouncilDialog,
        store: InMemoryRecordStore = InMemoryRecordStore(),
    ): Map<String, LcncNodeRunner> = CouncilNodes.registry(dialog, RecordSeams.inMemory(store))

    private val okDialog = CouncilDialog { SeatOutcome.Ok("the take", "z-ai/glm-5.2") }

    // ── council.seat ─────────────────────────────────────────────────────

    @Test
    fun seatOkLabelIsPinnedExactly() = runTest {
        val out = registry(okDialog).getValue("council.seat")
            .run(seatNode(), mapOf("prompt" to "Q"))
        assertEquals("the take", out["content"])
        assertEquals(
            "[p1.e1 · expert · round 1 · z-ai/glm-5.2]\nthe take",
            out["labeled"],
        )
        assertEquals("z-ai/glm-5.2", out["model"])
        val record = out["record"] as Map<*, *>
        assertEquals("ok", record["status"])
        assertEquals("e1", record["seat"])
        assertEquals("p1", record["panel"])
        assertEquals(ContentId.of("the take".encodeToByteArray()).value, record["cid"])
        assertEquals("council/default/p1/e1", record["contextId"])
    }

    @Test
    fun seatReadsPromptFromEitherPortSpelling() = runTest {
        val calls = ArrayList<SeatCall>()
        val reg = registry(CouncilDialog { c -> calls.add(c); SeatOutcome.Ok("x", "m") })
        reg.getValue("council.seat").run(seatNode(), mapOf("prompt?" to "via optional"))
        assertEquals("via optional", calls.single().prompt)
        assertEquals("z-ai/glm-5.2", calls.single().preferredModel)
        assertEquals(500, calls.single().maxTokens)
        assertEquals(0.8, calls.single().temperature)
    }

    @Test
    fun throwingDialogBecomesRefusedBannerNeverThrows() = runTest {
        val reg = registry(CouncilDialog { throw IllegalStateException("boom") })
        val out = reg.getValue("council.seat").run(seatNode(), mapOf("prompt" to "Q"))
        val content = out["content"] as String
        assertTrue(content.startsWith("[SEAT FAILED: p1.e1] boom"), "banner, got: $content")
        assertEquals("", out["model"])
        val record = out["record"] as Map<*, *>
        assertEquals("refused", record["status"])
        assertEquals("boom", record["error"])
        // The banner itself is the recorded turn text — cid is byte-exact over it.
        assertEquals(ContentId.of(content.encodeToByteArray()).value, record["cid"])
    }

    @Test
    fun refusedLabelCarriesTheFailoverTrail() = runTest {
        val trail = listOf("groq/llama: HTTP 401", "openai/gpt: no key")
        val reg = registry(CouncilDialog { SeatOutcome.Refused("no provider answered", trail) })
        val out = reg.getValue("council.seat").run(seatNode(), mapOf("prompt" to "Q"))
        val labeled = out["labeled"] as String
        assertTrue(
            labeled.startsWith("[p1.e1 · expert · round 1 · FAILED: groq/llama: HTTP 401 -> openai/gpt: no key]"),
            "trail in header, got: $labeled",
        )
        assertTrue((out["content"] as String).contains("attempted: groq/llama: HTTP 401 -> openai/gpt: no key"))
        val record = out["record"] as Map<*, *>
        assertEquals(trail, record["attempted"])
    }

    // ── text.fold / record.fold ──────────────────────────────────────────

    @Test
    fun textFoldAcceptsScalarAndListSkipsBlanksKeepsBracketHeaders() = runTest {
        val fold = registry(okDialog).getValue("text.fold")
        // Single-wire scalar unwrap: the value arrives bare, not in a list.
        val scalar = fold.run(
            LcncNode("f", "text.fold", params = mapOf("label" to "L")),
            mapOf("parts" to "only part"),
        )
        assertEquals("== L ==\n\n---\n\n(1) only part", scalar["text"])
        // MANY wires: blanks skipped, bracket-headed parts keep their header.
        val many = fold.run(
            LcncNode("f", "text.fold", params = mapOf("label" to "L")),
            mapOf("parts" to listOf("[p1.e1 · expert]\ntake", "", "plain part")),
        )
        val text = many["text"] as String
        assertTrue(text.contains("[p1.e1 · expert]\ntake"))
        assertFalse(text.contains("(1) ["), "bracket-headed part must not be numbered")
        assertTrue(text.contains("(2) plain part"), "plain part numbered by ordinal, got: $text")
        assertFalse(text.contains("\n\n---\n\n\n"), "blank part must be dropped, not joined")
        // numbered=false switches numbering off; a map part stringifies.
        val plain = fold.run(
            LcncNode("f", "text.fold", params = mapOf("numbered" to "false")),
            mapOf("parts" to listOf("a", mapOf("k" to "v"))),
        )
        assertEquals("a\n\n---\n\n{\"k\":\"v\"}", plain["text"])
    }

    @Test
    fun recordFoldFlattensNestedListsInOrder() = runTest {
        val fold = registry(okDialog).getValue("record.fold")
        val out = fold.run(
            LcncNode("rf", "record.fold"),
            mapOf("parts" to listOf(
                mapOf("seat" to "a"),
                listOf(mapOf("seat" to "b"), mapOf("seat" to "c")),
            )),
        )
        val turns = out["turns"] as List<*>
        assertEquals(listOf("a", "b", "c"), turns.map { (it as Map<*, *>)["seat"] })
        // Scalar unwrap: a single map arrives bare.
        val one = fold.run(LcncNode("rf", "record.fold"), mapOf("parts" to mapOf("seat" to "solo")))
        assertEquals(1, (one["turns"] as List<*>).size)
    }

    // ── ruling.parse ─────────────────────────────────────────────────────

    @Test
    fun rulingParseExtractsTrailingJsonWithStrictBooleans() = runTest {
        val parse = registry(okDialog).getValue("ruling.parse")
        val text = "Weighing the record, the motion has merit.\n" +
            "{\"disposition\":\"granted\",\"needsClarification\":true," +
            "\"clarificationQuestion\":\"what remedy?\",\"mistrial\":false}"
        val out = parse.run(LcncNode("parse", "ruling.parse"), mapOf("text" to text))
        assertEquals("granted", (out["verdict"] as Map<*, *>)["disposition"])
        assertEquals(true, out["needsClarification"])
        assertEquals("what remedy?", out["clarificationQuestion"])
        assertEquals(false, out["mistrial"])
        assertEquals(text, out["text"])
    }

    @Test
    fun rulingParseTreatsTrueStringAsTrue() = runTest {
        val parse = registry(okDialog).getValue("ruling.parse")
        val out = parse.run(
            LcncNode("parse", "ruling.parse"),
            mapOf("text" to "{\"disposition\":\"denied\",\"needsClarification\":\"true\",\"mistrial\":\"nope\"}"),
        )
        assertEquals(true, out["needsClarification"])
        assertEquals(false, out["mistrial"], "a non-'true' string is strict false")
    }

    @Test
    fun rulingParseMalformedJsonIsStrictFalseWithRawDisposition() = runTest {
        val parse = registry(okDialog).getValue("ruling.parse")
        val raw = "no verdict here { this is not json"
        val out = parse.run(LcncNode("parse", "ruling.parse"), mapOf("text" to raw))
        assertEquals(mapOf("disposition" to raw), out["verdict"])
        assertEquals(false, out["needsClarification"])
        assertEquals(false, out["mistrial"])
        assertEquals("", out["clarificationQuestion"])
    }

    @Test
    fun rulingParseSeatFailureBannerIsStrictFalseEvenWithEmbeddedJson() = runTest {
        val parse = registry(okDialog).getValue("ruling.parse")
        val banner = "[SEAT FAILED: council.ruling] no route; attempted: {\"mistrial\":true}"
        val out = parse.run(LcncNode("parse", "ruling.parse"), mapOf("text" to banner))
        assertEquals(false, out["needsClarification"])
        assertEquals(false, out["mistrial"])
        assertEquals(mapOf("disposition" to banner), out["verdict"])
    }

    // ── coalesce ─────────────────────────────────────────────────────────

    @Test
    fun coalesceBlankAPicksBAndMapAWins() = runTest {
        val pick = registry(okDialog).getValue("coalesce")
        val node = LcncNode("pick", "coalesce")
        assertEquals("orig", pick.run(node, mapOf("a?" to "", "b" to "orig"))["value"])
        assertEquals("orig", pick.run(node, mapOf("b" to "orig"))["value"])
        val a = mapOf("disposition" to "denied")
        assertEquals(a, pick.run(node, mapOf("a?" to a, "b" to mapOf("disposition" to "granted")))["value"])
        assertEquals("clarified", pick.run(node, mapOf("a?" to "clarified", "b" to "orig"))["value"])
    }

    // ── council.convene ──────────────────────────────────────────────────

    @Test
    fun conveneEmptyConfigEmitsTheDefaultGeometry() = runTest {
        val out = registry(okDialog).getValue("council.convene")
            .run(LcncNode("cv", "council.convene"), emptyMap())
        val summary = out["summary"] as Map<*, *>
        assertEquals("preset-council", summary["name"])
        assertEquals(3, summary["panels"])
        assertEquals(5, summary["expertsPerPanel"])
        assertEquals(2, summary["rounds"])
        assertEquals(38, summary["seats"], "34 council seats + 4 in the clarify ring")
        assertEquals(98, summary["nodes"])
        // The document itself carries no name (toJson's shape is
        // {nodes,wires,controls,kanban,view,seq}; the name rides outside) —
        // but it IS the builder's, byte-identical once re-stringified.
        val program = out["program"] as Map<*, *>
        assertEquals(
            LcncProgramConfix.toJson(CouncilProgram.build(CouncilConfig.DEFAULT_3x5)),
            JsonSupport.stringify(program),
        )
    }

    @Test
    fun conveneParsesConfigAndPropagatesBoundViolations() = runTest {
        val convene = registry(okDialog).getValue("council.convene")
        val out = convene.run(
            LcncNode("cv", "council.convene"),
            mapOf("config" to mapOf(
                "caseId" to "case-9",
                "panels" to listOf(
                    mapOf("name" to "merits", "charge" to "c1", "experts" to 2),
                    mapOf("name" to "remedies", "charge" to "c2", "experts" to 2),
                ),
                "rounds" to 1,
                "clarify" to false,
                "mistrial" to false,
            )),
        )
        val summary = out["summary"] as Map<*, *>
        assertEquals(2, summary["panels"])
        assertEquals(2, summary["expertsPerPanel"])
        assertEquals(1, summary["rounds"])
        // rounds out of bounds: the builder's loud require propagates.
        val thrown = assertFailsWith<IllegalArgumentException> {
            convene.run(LcncNode("cv", "council.convene"), mapOf("config" to "{\"rounds\":5}"))
        }
        assertTrue(thrown.message!!.contains("rounds"), "bound named, got: ${thrown.message}")
    }

    // ── council.record + council.case ────────────────────────────────────

    private fun okTurn(seat: String, text: String): Map<String, Any?> = mapOf(
        "seat" to seat, "panel" to "p1", "role" to "expert", "round" to 1,
        "status" to "ok", "text" to text,
        "cid" to ContentId.of(text.encodeToByteArray()).value,
    )

    private fun refusedTurn(seat: String): Map<String, Any?> {
        val banner = "[SEAT FAILED: p1.$seat] no route; attempted: "
        return mapOf(
            "seat" to seat, "panel" to "p1", "role" to "expert", "round" to 1,
            "status" to "refused", "text" to banner,
            "cid" to ContentId.of(banner.encodeToByteArray()).value,
        )
    }

    private suspend fun record(
        store: InMemoryRecordStore,
        verdict: Any?,
        turns: List<Map<String, Any?>>,
        documentCid: String? = ContentId.of("the document".encodeToByteArray()).value,
    ): Map<*, *> {
        val out = registry(okDialog, store).getValue("council.record").run(
            LcncNode("record", "council.record", params = mapOf("caseId" to "default")),
            buildMap {
                put("verdict", verdict)
                put("transcript", listOf("panel one transcript", "panel positions"))
                put("turns", turns)
                put("caseId?", "case-7")
                if (documentCid != null) put("documentCid?", documentCid)
            },
        )
        return out["report"] as Map<*, *>
    }

    @Test
    fun recordHappyPathLandsEveryPlane() = runTest {
        val store = InMemoryRecordStore()
        val docCid = ContentId.of("the document".encodeToByteArray()).value
        val verdict = mapOf("disposition" to "granted", "needsClarification" to false, "mistrial" to false)
        val report = record(store, verdict, listOf(okTurn("e1", "take one"), okTurn("e2", "take two")))

        assertEquals("case-7", report["caseId"])
        assertEquals("ruled", report["status"])
        assertEquals(0, report["seatFailures"])
        assertEquals(2, report["seatCount"])
        assertEquals(0, report["cidMismatches"])
        val verdictCid = report["verdictCid"] as String
        val transcriptCid = report["transcriptCid"] as String
        val caseCid = report["caseCid"] as String
        assertTrue(verdictCid.startsWith("sha256:") && transcriptCid.startsWith("sha256:") && caseCid.startsWith("sha256:"))
        assertTrue((report["recorded"] as String).isNotBlank())

        // CAS: per-turn bytes, transcript, verdict, case doc all present.
        assertNotNull(store.cas[ContentId.of("take one".encodeToByteArray()).value])
        assertNotNull(store.cas[ContentId.of("take two".encodeToByteArray()).value])
        assertEquals(JsonSupport.stringify(verdict), store.cas[verdictCid]!!.decodeToString())
        assertTrue(store.cas[transcriptCid]!!.decodeToString().contains("panel one transcript"))

        // Lifecycle: recordRuling spied once with matching cids, no mistrial.
        assertEquals(listOf(Triple("case-7", verdictCid, transcriptCid)), store.rulings)
        assertTrue(store.mistrials.isEmpty())

        // Blackboard index fact — the graft-#7 shape, pinned.
        assertEquals(
            mapOf("caseCid" to caseCid, "verdictCid" to verdictCid, "documentCid" to docCid, "status" to "ruled"),
            store.blackboard["council-case/case-7"],
        )
        assertEquals("council.record", store.blackboardSources["council-case/case-7"])

        // Couch doc + the (ruling …) KIF fact.
        assertEquals("case-7", store.couch["council-case/case-7"]!!["caseId"])
        val kif = store.kif.single()
        assertEquals(
            "(ruling case_case-7 doc_${docCid.removePrefix("sha256:")} ${verdictCid.removePrefix("sha256:")})",
            kif,
        )
    }

    @Test
    fun recordTamperedTurnCidCountsMismatchWithoutThrowing() = runTest {
        val store = InMemoryRecordStore()
        val tampered = okTurn("e1", "take one").toMutableMap()
        tampered["cid"] = ContentId.of("something else".encodeToByteArray()).value
        val report = record(
            store,
            mapOf("disposition" to "granted"),
            listOf(tampered, okTurn("e2", "take two")),
        )
        assertEquals(1, report["cidMismatches"])
        assertEquals("ruled", report["status"])
    }

    @Test
    fun recordAllSeatsRefusedIsMistrial() = runTest {
        val store = InMemoryRecordStore()
        val report = record(
            store,
            mapOf("disposition" to "granted"),
            listOf(refusedTurn("e1"), refusedTurn("e2")),
        )
        assertEquals("mistrial", report["status"])
        assertEquals(2, report["seatFailures"])
        assertEquals("case-7", store.mistrials.single().first)
        assertTrue(store.rulings.isEmpty())
        assertEquals("mistrial", store.blackboard["council-case/case-7"]!!["status"])
    }

    @Test
    fun recordBannerVerdictIsMistrial() = runTest {
        val store = InMemoryRecordStore()
        val report = record(
            store,
            "[SEAT FAILED: council.ruling] no route; attempted: ",
            listOf(okTurn("e1", "take one")),
        )
        assertEquals("mistrial", report["status"])
        assertEquals(1, store.mistrials.size)
    }

    @Test
    fun recordVerdictDeclaringMistrialIsMistrial() = runTest {
        val store = InMemoryRecordStore()
        val report = record(
            store,
            mapOf("disposition" to "void", "mistrial" to true),
            listOf(okTurn("e1", "take one")),
        )
        assertEquals("mistrial", report["status"])
        assertEquals(1, store.mistrials.size)
    }

    @Test
    fun recordWithoutDocumentCidAssertsNoKifFact() = runTest {
        val store = InMemoryRecordStore()
        record(store, mapOf("disposition" to "granted"), listOf(okTurn("e1", "t")), documentCid = null)
        assertTrue(store.kif.isEmpty(), "no document, no (ruling …) fact")
        assertEquals("", store.blackboard["council-case/case-7"]!!["documentCid"])
    }

    @Test
    fun councilCaseRoundTripsWhatRecordWrote() = runTest {
        val store = InMemoryRecordStore()
        val verdict = mapOf("disposition" to "granted", "mistrial" to false)
        record(store, verdict, listOf(okTurn("e1", "take one")))

        val reg = registry(okDialog, store)
        val out = reg.getValue("council.case")
            .run(LcncNode("cc", "council.case", params = mapOf("caseId" to "case-7")), emptyMap())
        val case = out["case"] as Map<*, *>
        assertEquals("case-7", case["caseId"])
        assertEquals(JsonSupport.stringify(verdict), case["verdict"])
        assertTrue((case["transcript"] as String).contains("panel one transcript"))
        assertEquals("ruled", (case["index"] as Map<*, *>)["status"])

        // Blackboard-only path: no couch doc, transcript recovered through
        // the CAS-stored case doc (the index fact carries no transcriptCid).
        store.couch.clear()
        val bare = (reg.getValue("council.case")
            .run(LcncNode("cc", "council.case"), mapOf("caseId?" to "case-7"))["case"]) as Map<*, *>
        assertTrue((bare["transcript"] as String).contains("panel one transcript"))
        assertEquals(JsonSupport.stringify(verdict), bare["verdict"])

        // Unknown case: a loud not_found, never a silent empty body.
        val missing = (reg.getValue("council.case")
            .run(LcncNode("cc", "council.case"), mapOf("caseId?" to "case-404"))["case"]) as Map<*, *>
        assertEquals("not_found", missing["error"])
        assertEquals("case-404", missing["caseId"])
        assertNull(missing["transcript"])
    }
}
