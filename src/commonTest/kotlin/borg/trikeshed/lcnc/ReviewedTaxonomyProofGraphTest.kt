package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.rdf.*
import kotlin.test.*

/** Real fragment plans in synthetic host-report envelopes; these helpers grant no review authority. */
internal class ReviewedTaxonomyProofGraphFixture(val texts: List<String>, val limits: KifActivationLimits = KifActivationLimits()) {
    private val text = texts.joinToString("\n", postfix = "\n")
    private val source = ContentId.of(text.encodeToByteArray())
    val selections: Series<CurationReviewSelection>
    private val assertions: List<KifSourceAssertion>
    init {
        var offset = 0
        selections = texts.map { formula ->
            val begin = offset; offset += formula.length + 1
            CurationReviewSelection("kif:$begin:${offset - 1}", "relationship", emptyMap(),
                CurationReviewQuotation(source, source, begin, offset - 1, formula))
        }.toSeries()
        assertions = selections.view.map { KifSourceAssertion(it.id, source, "revision-1", KifReview.APPROVED, it.quotation.quote) }
    }
    fun report(predicate: String, subject: String, obj: String, offset: Int = 0, english: Boolean = false): Map<String, Any?> {
        val plan = ReviewedTaxonomyProofPaths.project(KifTaxonomySnapshot.create(1, assertions, limits)
            .activationPlan(predicate, subject, obj), selections)
        val page = _m["offset" j offset, "limit" j 1, "total" j texts.size, "returned" j 1]
        val result = linkedMapOf<String, Any?>("kind" to "reviewed-taxonomy-read/1", "mappingStatus" to DocumentTaxonomyCandidate.POLICY,
            "revision" to 1L, "sourceRevision" to "revision-1", "status" to plan["status"], "semantics" to plan["semantics"],
            "plan" to plan, "goal" to _m["predicate" j predicate, "subject" j subject, "object" j obj], "page" to page,
            "selected" to _l[_m["selectionId" j selections[offset].id, "quotation" j _m["quote" j texts[offset]]]])
        for (key in s_["snapshotCid", "approvalCid", "decisionCid", "readCid", "curationReceiptCid", "ontologyCid"].view)
            result[key] = ContentId.of(key.encodeToByteArray()).value
        if (english) {
            val declaration = ContentId.of("translation".encodeToByteArray()).value
            val inspection = ContentId.of("inspection".encodeToByteArray()).value
            val context = ContentId.of("context".encodeToByteArray()).value
            val witnesses = selections.view.mapIndexed { index, selection ->
                val formula = KifSemantic.decode(selection.quotation.quote).formula as KifFormula.Predicate
                val q = selection.quotation
                val quote = "📚 English π \"source\"\u0001 $index"
                _m["selectionId" j selection.id, "translationDecisionCid" j declaration, "inspectionCid" j inspection,
                    "contextKey" j context, "contextIri" j "urn:proof:context", "sentenceIndex" j index,
                    "kifQuotation" j _m["originalCid" j q.originalCid.value, "extractedTextCid" j q.extractedTextCid.value,
                        "begin" j q.begin, "end" j q.end, "quote" j q.quote],
                    "englishQuotation" j _m["originalCid" j ContentId.of("original.pdf".encodeToByteArray()).value,
                        "extractedTextCid" j ContentId.of("extracted.txt".encodeToByteArray()).value,
                        "begin" j 7, "end" j (7 + quote.length), "quote" j quote],
                    "declared" j _m["relation" j formula.name, "subjectIri" j (formula.arguments[0] as KifTerm.Constant).token,
                        "objectIri" j (formula.arguments[1] as KifTerm.Constant).token,
                        "subjectSort" j (if (formula.name == "instance") "individual" else "class"), "objectSort" j "class"])
            }
            result["englishOrigin"] = _m["translationDecisionCid" j declaration, "inspectionCid" j inspection,
                "contextKey" j context, "contextIri" j "urn:proof:context", "witnesses" j witnesses,
                "authority" j "explicit-reviewed-translation-assumptions-not-English-entailment", "independentDocumentsOrNarsEvidence" j false]
            result["englishWitnesses"] = witnesses
        }
        return frame(result)
    }
    companion object {
        fun frame(report: Map<String, Any?>): Map<String, Any?> {
            val plan = report["plan"] as Map<*, *>
            val fields = _m["kind" j "reviewed-kif-read-frame/1", "readCid" j report["readCid"],
                "page" j report["page"], "fragmentExportCid" j plan["exportCid"]] +
                if (report["englishOrigin"] == null) emptyMap() else _m["englishOrigin" j report["englishOrigin"]]
            return report + _m["readFrameCid" j ContentId.of(CanonicalCbor.encodeMap(fields)).value]
        }
    }
}

internal fun reviewedTaxonomyProofGraphFixture(steps: Int = 2, english: Boolean = false): Map<String, Any?> =
    ReviewedTaxonomyProofGraphFixture((0 until steps).toSeries().view.map { "(subclass urn:proof:C$it urn:proof:C${it + 1})" })
        .report("subclass", "urn:proof:C0", "urn:proof:C$steps", english = english)

class ReviewedTaxonomyProofGraphTest {
    private fun rows(value: Any?) = (value as List<*>).map { it as Map<*, *> }
    private fun quads(p: ReviewedTaxonomyProofGraph.Projection) = p.store.toGraph().quads
    private fun predicate(name: String) = RdfTerm.Iri(ReviewedTaxonomyProofGraph.VOCAB + name)
    private fun limit(action: () -> Unit) {
        val e = assertFailsWith<ReviewedTaxonomyProofGraph.SourceProfileRefused>(block = action)
        assertEquals(413, e.status); assertEquals("reviewed_taxonomy_source_profile_limit", e.code)
    }

    @Test fun fullOffPageBasisFeedsActualTypedQuadQuery() {
        val f = ReviewedTaxonomyProofGraphFixture(_l["(instance Tenant Person)", "(subclass Person Agent)", "(subclass Other Unrelated)"])
        val report = f.report("instance", "Tenant", "Agent", 2)
        val projection = ReviewedTaxonomyProofGraph.project(report)
        val plan = report["plan"] as Map<*, *>
        val sources = rows(plan["sources"])
        assertEquals(3, quads(projection).count { it.s == RdfTerm.Iri(ReviewedTaxonomyProofGraph.frameIri(projection.graph)) && it.p == predicate("source") })
        val proof = rows(plan["proofs"]).single()
        val iri = ReviewedTaxonomyProofGraph.proofIri(projection.graph, proof["nodeId"] as String)
        val v = ReviewedTaxonomyProofGraph.VOCAB
        val query = "SELECT ?ordinal ?source ?rule ?kifQuote WHERE { GRAPH <${projection.graph}> { <$iri> <${v}premiseUse> ?use . ?use <${v}source> ?source . ?use <${v}ordinal> ?ordinal . ?use <${v}rule> ?rule . ?source <${v}kifQuotation> ?kif . ?kif <${v}quote> ?kifQuote . } }"
        val reply = SparqlSurface(projection.store).answer(query, emptyList(), emptyList(), "json", "")
        assertEquals(200, reply.status)
        val values = rows((reifyStrict(reply.body) as Map<*, *>)["results"].let { (it as Map<*, *>)["bindings"] })
            .sortedBy { ((it["ordinal"] as Map<*, *>)["value"] as String).toInt() }
        assertEquals(2, values.size)
        val premises = proof["premises"] as List<*>
        for ((index, row) in values.withIndex()) {
            assertEquals(_m["type" j "literal", "datatype" j (RdfVocab.XSD + "integer"), "value" j index.toString()], row["ordinal"])
            assertEquals("uri", (row["source"] as Map<*, *>)["type"])
            assertEquals(ReviewedTaxonomyProofGraph.sourceIri(projection.graph, premises[index] as String), (row["source"] as Map<*, *>)["value"])
            val source = sources.single { it["nodeId"] == premises[index] }
            assertEquals(source["text"], (row["kifQuote"] as Map<*, *>)["value"])
            assertEquals((proof["rules"] as List<*>)[index], (row["rule"] as Map<*, *>)["value"])
        }
        val otherPage = ReviewedTaxonomyProofGraph.project(f.report("instance", "Tenant", "Agent", 0))
        assertNotEquals(projection.graph, otherPage.graph)
        assertEquals(projection.quadCount, otherPage.quadCount)
        assertEquals(projection.descriptor["planFrameId"], otherPage.descriptor["planFrameId"])
        assertEquals(projection.descriptor["exportCid"], otherPage.descriptor["exportCid"])
    }

    @Test fun alternativeUsesSharedSourcesReflexivityAndIncompleteStatusesRetainTheirMeaning() {
        val f = ReviewedTaxonomyProofGraphFixture(_l["(subclass A B)", "(subclass A B)", "(subclass B D)"])
        val projection = ReviewedTaxonomyProofGraph.project(f.report("subclass", "A", "D"))
        assertEquals(2, quads(projection).count { it.p == predicate("proof") })
        assertEquals(4, quads(projection).count { it.p == predicate("premiseUse") })
        assertEquals(3, quads(projection).count { it.p == predicate("selectionId") })
        val shared = quads(projection).filter { it.p == predicate("source") && "/use/" in (it.s as RdfTerm.Iri).iri }
        assertEquals(4, shared.size); assertEquals(3, shared.map { it.o }.distinct().size)
        val reflexive = ReviewedTaxonomyProofGraph.project(f.report("subclass", "B", "B"))
        assertTrue(quads(reflexive).any { it.p == predicate("rule") && it.o == RdfTerm.Literal("kif-taxonomy/v1/class-reflexivity") })
        val unknown = ReviewedTaxonomyProofGraph.project(f.report("subclass", "Absent", "Absent"))
        assertEquals("UNKNOWN", unknown.descriptor["status"]); assertFalse(quads(unknown).any { it.p == predicate("premiseUse") })
        val bounded = ReviewedTaxonomyProofGraphFixture(f.texts, KifActivationLimits(maxSteps = 1)).report("subclass", "A", "D")
        val partial = ReviewedTaxonomyProofGraph.project(bounded)
        assertEquals("BOUNDED", partial.descriptor["status"]); assertEquals(true, partial.descriptor["truncated"])
        assertFalse(quads(partial).any { it.p == predicate("premiseUse") })
    }

    @Test fun literalTokensAndSeparateEnglishKifQuotationsRoundTripWithoutEntityPromotion() {
        val text = "(subclass urn:outside:A https://outside.example/B) ; π \"quote\"\u0001 😀"
        val report = ReviewedTaxonomyProofGraphFixture(_l[text]).report("subclass", "urn:outside:A", "https://outside.example/B", english = true)
        val projection = ReviewedTaxonomyProofGraph.project(report)
        assertTrue(projection.englishAvailable)
        val all = quads(projection)
        assertTrue(all.any { it.p == predicate("subjectToken") && it.o == RdfTerm.Literal("urn:outside:A") })
        assertTrue(all.any { it.p == predicate("objectToken") && it.o == RdfTerm.Literal("https://outside.example/B") })
        assertFalse(all.any { it.o == RdfTerm.Iri("urn:outside:A") || it.o == RdfTerm.Iri("https://outside.example/B") })
        assertTrue(all.all { it.p.iri.startsWith(ReviewedTaxonomyProofGraph.VOCAB) })
        assertTrue(all.any { it.p == predicate("quote") && it.o == RdfTerm.Literal(text) })
        val english = all.single { it.p == predicate("englishQuotation") }.o
        val englishRows = all.filter { it.s == english }
        assertNotEquals(englishRows.single { it.p == predicate("originalCid") }.o, englishRows.single { it.p == predicate("extractedTextCid") }.o)
        val quote = (englishRows.single { it.p == predicate("quote") }.o as RdfTerm.Literal).lexical
        assertEquals(RdfTerm.Literal((7 + quote.length).toString(), RdfVocab.XSD + "integer"), englishRows.single { it.p == predicate("end") }.o)
        assertEquals(RdfTerm.Literal("utf16-code-unit-half-open"), englishRows.single { it.p == predicate("unit") }.o)
        assertTrue(all.any { it.p == predicate("derivedKifCid") })
        val origin = report["englishOrigin"] as Map<*, *>
        val wrong = report + _m["englishOrigin" j (origin + _m["witnesses" j emptyList<Any?>()])]
        assertFailsWith<IllegalArgumentException> { ReviewedTaxonomyProofGraph.project(ReviewedTaxonomyProofGraphFixture.frame(wrong)) }
        assertFailsWith<IllegalArgumentException> { ReviewedTaxonomyProofGraph.project(report + _m["readFrameCid" j ContentId.of("other".encodeToByteArray()).value]) }
    }

    @Test fun optionalProfileRefusesCompleteOversizeGraphsAndEscapedTermsWithoutClipping() {
        fun padded(size: Int) = "(subclass A B) ; ".padEnd(size, 'x')
        assertTrue(ReviewedTaxonomyProofGraph.project(ReviewedTaxonomyProofGraphFixture(_l[padded(4094)]).report("subclass", "A", "B")).quadCount > 0)
        for (formula in s_[padded(4095), padded(5000), "(subclass A B) ; " + "\"".repeat(2100)].view)
            limit { ReviewedTaxonomyProofGraph.project(ReviewedTaxonomyProofGraphFixture(_l[formula]).report("subclass", "A", "B")) }
        val branches = (0 until 64).toSeries().view.map { "(subclass A B)" } +
            (0 until 64).toSeries().view.map { "(subclass B C)" } + _l["(subclass C D)"]
        val complete = ReviewedTaxonomyProofGraphFixture(branches, KifActivationLimits(maxProofs = 1024, maxSteps = 1_000_000)).report("subclass", "A", "D")
        assertTrue(rows((complete["plan"] as Map<*, *>)["proofs"]).size > 100)
        limit { ReviewedTaxonomyProofGraph.project(complete) }
        val characterHeavy = ReviewedTaxonomyProofGraphFixture((0 until 256).toSeries().view.map { padded(4094) },
            KifActivationLimits(maxProofs = 1024, maxSteps = 1_000_000)).report("subclass", "B", "B")
        // Every term fits and 256 one-use proofs fit the quad bound; the complete lexeme matrix does not fit 4M characters.
        limit { ReviewedTaxonomyProofGraph.project(characterHeavy) }
        // The optional profile does not alter the original, still valid complete proof display.
        ReviewedTaxonomyProofPaths.validate(complete["plan"] as Map<*, *>)
        val refused = assertFailsWith<ReviewedTaxonomyProofGraph.SourceProfileRefused> {
            ReviewedTaxonomyProofGraph.project(complete + _m["mappingStatus" j "asserted-taxonomy-source-format-unavailable"])
        }
        assertEquals(409, refused.status); assertEquals("reviewed_taxonomy_source_unavailable", refused.code)
    }
}
