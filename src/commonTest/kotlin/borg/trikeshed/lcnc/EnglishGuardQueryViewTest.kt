package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.nlp.EnglishLegalPatterns
import borg.trikeshed.nlp.guardBaseline
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.rdf.*
import kotlin.test.*

/** Explicit reconstructed fixture -> actual quad engine -> display validation, not HTTP or parser authentication. */
class EnglishGuardQueryViewTest {
    private fun map(raw: Any?) = raw as Map<*, *>
    private fun rows(raw: Any?) = (raw as List<*>).map(::map)
    private fun execute(case: String): Triple<Map<String, Any?>, EnglishGuardQueryView.Starter, Map<String, Any?>> {
        val f = guardBaseline(case)
        assertNotEquals(f.originalBaselineInspectionCid, f.fixtureInspectionCid)
        val descriptor = _m["mode" j "snapshot", "historical" j true, "currentness" j "not-checked",
            "originalCid" j f.source.originalCid.value, "extractedTextCid" j f.source.extractedTextCid.value,
            "reviewStatus" j "unreviewed", "semanticAuthority" j "not-established",
            "producerAuthority" j "not-established", "parserExecution" j "not-reverified"]
        val payload = EnglishLegalPatterns.inspect(f.source, f.parsed, 0, f.text.length) + _m[
            "inspectionCid" j f.fixtureInspectionCid.value, "parserExecution" j "not-reverified",
            "producerAuthority" j "not-established", "unresolved" j emptyList<String>(), "source" j descriptor]
        val cid = ContentId.of(CanonicalCbor.encodeMap(payload))
        val candidate = payload + _m["receiptCid" j cid.value]
        val starter = EnglishGuardQueryView.starter(candidate)
        assertTrue(starter.program.controls.inspectionOnly)
        val parsed = SparqlGraph.lower(starter.program); assertEquals(8, parsed.graph.patterns.size)
        val store = EnglishLegalGraph.project(cid, f.fixtureInspectionCid, payload, f.source)
        val reply = SparqlSurface(store).answer(parsed, store.scan(), "json", "")
        assertEquals(200, reply.status, reply.body)
        val response = _m["dataset" j "english-legal-candidate-observations", "query" j parsed.text,
            "results" j reifyStrict(reply.body), "source" j (descriptor + _m[
                "selected" j "english-legal-candidate-observations", "receiptCid" j cid.value,
                "candidateCid" j cid.value, "inspectionCid" j f.fixtureInspectionCid.value,
                "namedGraph" j starter.graph, "quads" j store.size,
                "bindingProvenance" j "candidate-receipt-resources-with-parent-inspection-and-exact-source-spans"])]
        return Triple(candidate, starter, response)
    }
    private fun bindingRows(response: Map<*, *>) = rows(map(map(response["results"])["results"])["bindings"])
    private fun withRows(response: Map<String, Any?>, values: List<Map<*, *>>): Map<String, Any?> {
        val result = map(response["results"])
        return response + _m["results" j (result + _m["results" j (map(result["results"]) + _m["bindings" j values])])]
    }

    @Test fun eightPatternTypedRowsPreserveTheUnmarkedCoordinatedTargetAndFullSourceIdentity() {
        val (candidate, starter, response) = execute("G07")
        val answer = EnglishGuardQueryView.answer(candidate, response, starter)
        assertEquals(2, answer.rows.size)
        assertEquals(_l["7:0", "9:0"], answer.rows.map { it.attachment["subordinateNodeId"] })
        assertEquals(_l["approves", "Trustee"], answer.rows.map { map(it.bindings["subordinateWord"])["value"] })
        assertTrue(answer.rows.all { map(it.bindings["subordinate"])["type"] == "uri" && map(it.bindings["relation"])["type"] == "literal" })
        assertEquals(emptyList<Any?>(), answer.rows[1].attachment["markerBindingOrdinals"])
        assertTrue(answer.rows.all { map(it.bindings["normIssue"])["value"] == "COORDINATED" })
        for (row in answer.rows) {
            assertEquals(starter.graph + "/sentence/0/guard-attachment/${row.ordinal}", map(row.bindings["attachment"])["value"])
            assertEquals(starter.graph + "/sentence/0", map(row.bindings["sentence"])["value"])
        }
    }

    @Test fun matrixAndSubordinateCuesAndQuotationIndicatorsRemainSeparateFromNormDiagnostics() {
        for ((case, location, diagnostic) in s_[Triple("G03", "governor-head", "none-reported"), Triple("G04", "subordinate-head", "NEGATED")].view) {
            val (candidate, starter, response) = execute(case)
            val row = EnglishGuardQueryView.answer(candidate, response, starter).rows.single()
            assertEquals(location, rows(row.attachment["directNegationCueBindings"]).single()["location"])
            assertEquals(diagnostic, map(row.bindings["normIssue"])["value"])
        }
        val (quoted, starter, response) = execute("G13")
        val answer = EnglishGuardQueryView.answer(quoted, response, starter)
        assertEquals(_l["advcl:if", "advcl:without"], answer.rows.map { map(it.bindings["relation"])["value"] })
        assertTrue(answer.rows.all { map(it.bindings["normIssue"])["value"] == "NEGATED" })
        val sentence = rows(EnglishGuardQueryView.section(quoted)!!["sentences"]).single()
        assertEquals(_l["4:0", "8:0"], sentence["quotationIndicatorNodeIds"])
        assertTrue(answer.rows.all { rows(it.attachment["directNegationCueBindings"]).isEmpty() }, "Without does not become an invented lexical not cue")
    }

    @Test fun absentSectionIsLegacyWhileUnknownOmittedDuplicateOrUnboundTypedRowsRefuse() {
        val (candidate, starter, response) = execute("G07")
        val legacy = candidate - "guardAttachments"
        assertNull(EnglishGuardQueryView.section(legacy))
        assertFailsWith<IllegalArgumentException> { EnglishGuardQueryView.starter(legacy) }
        assertFailsWith<IllegalArgumentException> { EnglishGuardQueryView.section(candidate + _m["guardAttachments" j null]) }
        val section = map(candidate["guardAttachments"])
        assertFailsWith<IllegalArgumentException> { EnglishGuardQueryView.section(candidate + _m["guardAttachments" j (section + _m["profile" j "unknown/2"])]) }
        val sentence = rows(section["sentences"]).single()
        val incomplete = section + _m["sentences" j _l[sentence + _m["attachments" j rows(sentence["attachments"]).take(1)]]]
        assertFailsWith<IllegalArgumentException> { EnglishGuardQueryView.section(candidate + _m["guardAttachments" j incomplete]) }
        val actual = bindingRows(response)
        val wrongType = actual[0] + _m["subordinate" j (map(actual[0]["subordinate"]) + _m["type" j "literal"])]
        val wrongWord = actual[0] + _m["subordinateWord" j (map(actual[0]["subordinateWord"]) + _m["value" j "consents"])]
        for (changed in s_[withRows(response, actual.take(1)), withRows(response, _l[actual[0], actual[0]]),
            withRows(response, _l[wrongType, actual[1]]), withRows(response, _l[wrongWord, actual[1]]),
            response + _m["source" j (map(response["source"]) + _m["candidateCid" j ("sha256:" + "f".repeat(64))])],
            response + _m["query" j (starter.query + " ")]].view)
            assertFailsWith<IllegalArgumentException> { EnglishGuardQueryView.answer(candidate, changed, starter) }
    }
}
