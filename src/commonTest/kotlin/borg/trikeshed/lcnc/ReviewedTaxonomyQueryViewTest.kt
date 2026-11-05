package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.rdf.*
import kotlin.test.*

class ReviewedTaxonomyQueryViewTest {
    private fun map(value: Any?) = value as Map<*, *>
    private fun rows(value: Any?) = (value as List<*>).map(::map)

    /** Actual proof fixture -> actual quad projector -> existing query engine, without an HTTP/auth claim. */
    private fun execute(steps: Int, english: Boolean): Triple<Map<*, *>, ReviewedTaxonomyQueryView.Starter, Map<*, *>> {
        val report = reviewedTaxonomyProofGraphFixture(steps, english) +
            _m["reviewer" j "synthetic:query-view-fixture", "synthetic" j true]
        val proofId = rows(map(report["plan"])["proofs"]).single()["nodeId"] as String
        val starter = ReviewedTaxonomyQueryView.starter(report, proofId, english)
        val projection = ReviewedTaxonomyProofGraph.project(report)
        val parsed = SparqlGraph.lower(starter.program)
        assertEquals(if (english) 8 else 6, parsed.graph.patterns.size)
        val reply = SparqlSurface(projection.store).answer(parsed, projection.store.scan(), "json", "")
        assertEquals(200, reply.status, reply.body)
        val query = _m["kind" j "reviewed-taxonomy-quad-query/1", "schema" j ReviewedTaxonomyProofGraph.SCHEMA,
            "dataset" j ReviewedTaxonomyProofGraph.DATASET, "graph" j projection.graph, "query" j parsed.text,
            "results" j reifyStrict(reply.body), "physicalSource" j SparqlSource.STORE.descriptor(false),
            "provenance" j (projection.descriptor + _m["checkedAsOf" j 123L, "reviewer" j report["reviewer"], "synthetic" j true]),
            "persistence" j "request-local", "authority" j "authenticated-reviewed-read-not-a-sink-permit"]
        return Triple(report, starter, report + _m["quadQuery" j query])
    }
    private fun bindingRows(response: Map<*, *>) = rows(map(map(map(response["quadQuery"])["results"])["results"])["bindings"])
    private fun withRows(response: Map<*, *>, values: List<Map<*, *>>): Map<*, *> {
        val query = map(response["quadQuery"]); val results = map(query["results"])
        return response + _m["quadQuery" j (query + _m["results" j (results +
            _m["results" j (map(results["results"]) + _m["bindings" j values])])])]
    }

    @Test fun actualTwelvePremiseQueryDisplaysNumericOrderWithoutAnOrderingClaim() {
        val (report, starter, response) = execute(12, false)
        val answer = ReviewedTaxonomyQueryView.answer(report, withRows(response, bindingRows(response).reversed()), starter)
        assertEquals((0 until 12).toSeries().view.map { it }, answer.rows.map { it.ordinal })
        val premises = rows(map(report["plan"])["proofs"]).single()["premises"] as List<*>
        assertEquals(premises, answer.rows.map { it.sourceNodeId })
        assertFalse(starter.query.contains("ORDER BY"))
        for (row in answer.rows) {
            assertEquals("uri", map(row.bindings["source"])["type"])
            assertEquals(RdfVocab.XSD + "integer", map(row.bindings["ordinal"])["datatype"])
        }
    }

    @Test fun actualEightPatternEnglishQueryRetainsExactLiteralEvidenceAndSyntheticLineage() {
        val (report, starter, response) = execute(2, true)
        val answer = ReviewedTaxonomyQueryView.answer(report, response, starter)
        assertEquals(2, answer.rows.size)
        val origins = rows(report["englishWitnesses"]).associateBy { it["selectionId"] }
        val sources = rows(map(report["plan"])["sources"]).associateBy { it["nodeId"] }
        for (row in answer.rows) {
            val source = sources.getValue(row.sourceNodeId)
            assertEquals(source["text"], map(row.bindings["kifQuote"])["value"])
            assertEquals(map(origins.getValue(source["selectionId"])["englishQuotation"])["quote"], map(row.bindings["englishQuote"])["value"])
            assertEquals("literal", map(row.bindings["rule"])["type"])
            assertNull(map(row.bindings["rule"])["datatype"])
        }
        assertEquals(true, map(answer.value["provenance"])["synthetic"])
        val manual = execute(2, false).first
        val proof = rows(map(manual["plan"])["proofs"]).single()["nodeId"] as String
        assertFails { ReviewedTaxonomyQueryView.starter(manual, proof, true) }
        assertFails { ReviewedTaxonomyQueryView.starter(manual, ContentId.of("missing".encodeToByteArray()).value, false) }
    }

    @Test fun substitutedKindsSourcesRowsAndReviewProvenanceCannotBecomeDisplayBindings() {
        val (report, starter, response) = execute(2, true)
        val bindings = bindingRows(response)
        val first = bindings[0]
        val changed = s_[
            first + _m["source" j (map(first["source"]) + _m["type" j "literal"])],
            first + _m["source" j (map(first["source"]) + _m["value" j "urn:plausible:other-source"])],
            first + _m["ordinal" j (map(first["ordinal"]) + _m["datatype" j RdfVocab.XSD + "string"])],
            first + _m["rule" j (map(first["rule"]) + _m["value" j "invented-rule"])],
            first + _m["englishQuote" j (map(first["englishQuote"]) + _m["value" j "substituted quote"])]]
        for (row in changed.view) assertFails {
            ReviewedTaxonomyQueryView.answer(report, withRows(response, _l[row, bindings[1]]), starter)
        }
        assertFails { ReviewedTaxonomyQueryView.answer(report, withRows(response, bindings.take(1)), starter) }
        assertFails { ReviewedTaxonomyQueryView.answer(report, withRows(response, _l[first, first]), starter) }
        val query = map(response["quadQuery"])
        for ((key, value) in s_["approvalCid" j ContentId.of("other approval".encodeToByteArray()).value,
            "synthetic" j false, "reviewer" j "different-issuer"].view) assertFails {
            val bad = response + _m["quadQuery" j (query + _m["provenance" j (map(query["provenance"]) + _m[key j value])])]
            ReviewedTaxonomyQueryView.answer(report, bad, starter)
        }
        val badPhysical = response + _m["quadQuery" j (query +
            _m["physicalSource" j (map(query["physicalSource"]) + _m["reviewAuthority" j "approved"])])]
        assertFails { ReviewedTaxonomyQueryView.answer(report, badPhysical, starter) }
    }
}
