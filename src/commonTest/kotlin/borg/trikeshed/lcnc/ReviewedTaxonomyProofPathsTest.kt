package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import kotlin.test.*

class ReviewedTaxonomyProofPathsTest {
    private class Theory(texts: List<String>) {
        val text = texts.joinToString("\n", postfix = "\n")
        val cid = ContentId.of(text.encodeToByteArray())
        val selections: Series<CurationReviewSelection>
        val assertions: List<KifSourceAssertion>
        init {
            var offset = 0
            selections = texts.map { formula ->
                val begin = offset; offset += formula.length + 1
                CurationReviewSelection("kif:$begin:${offset - 1}", "relationship", emptyMap(),
                    CurationReviewQuotation(cid, cid, begin, offset - 1, formula))
            }.toSeries()
            assertions = selections.view.map { KifSourceAssertion(it.id, cid, "revision-1", KifReview.APPROVED, it.quotation.quote) }
        }
        fun plan(predicate: String, subject: String, obj: String, limits: KifActivationLimits = KifActivationLimits()) =
            ReviewedTaxonomyProofPaths.project(KifTaxonomySnapshot.create(1, assertions, limits)
                .activationPlan(predicate, subject, obj), selections)
        fun frozen(): List<Map<*, *>> = selections.view.map { item ->
            val q = item.quotation
            _m["id" j item.id, "quotation" j _m["originalCid" j q.originalCid.value,
                "extractedTextCid" j q.extractedTextCid.value, "begin" j q.begin, "end" j q.end, "quote" j q.quote]]
        }
    }
    private fun rows(value: Any?) = (value as List<*>).map { it as Map<*, *> }

    @Test fun actualPathsRetainOnlyTheirOrderedPremisesAndAllSelectedSources() {
        val t = Theory(_l["(instance Tenant Person)", "(subclass Person Agent)", "(subclass Other Unrelated)"])
        val plan = t.plan("instance", "Tenant", "Agent")
        ReviewedTaxonomyProofPaths.validate(plan, t.frozen())
        val sources = rows(plan["sources"]).associateBy { it["nodeId"] }
        assertEquals(3, sources.size)
        val proof = rows(plan["proofs"]).single()
        assertEquals(_l["(instance Tenant Person)", "(subclass Person Agent)"],
            (proof["premises"] as List<*>).map { sources.getValue(it)["text"] })
        assertEquals(_l["kif-taxonomy/v1/instance-assertion", "kif-taxonomy/v1/instance-subclass"], proof["rules"])
    }

    @Test fun alternativesDuplicatesAndReflexivityRetainActualOccurrenceWitnesses() {
        val t = Theory(_l["(subclass A B)", "(subclass A C)", "(subclass B D)", "(subclass C D)"])
        val plan = t.plan("subclass", "A", "D")
        val sources = rows(plan["sources"]).associateBy { it["nodeId"] }
        assertEquals(_s[_l["(subclass A B)", "(subclass B D)"], _l["(subclass A C)", "(subclass C D)"]],
            rows(plan["proofs"]).map { p -> (p["premises"] as List<*>).map { sources.getValue(it)["text"] } }.toSet())
        val duplicate = Theory(_l["(subclass A B)", "(subclass A B)"]).plan("subclass", "A", "B")
        assertEquals(2, rows(duplicate["proofs"]).size)
        assertEquals(1, rows(duplicate["sources"]).map { it["assertionCid"] }.distinct().size)
        assertEquals(2, rows(duplicate["sources"]).map { it["nodeId"] }.distinct().size)
        val reflexive = t.plan("subclass", "B", "B")
        for (proof in rows(reflexive["proofs"])) {
            assertEquals(1, (proof["premises"] as List<*>).size)
            assertEquals(_l["kif-taxonomy/v1/class-reflexivity"], proof["rules"])
        }
        assertTrue(rows(reflexive["proofs"]).isNotEmpty())
        val unknown = t.plan("subclass", "Missing", "Missing")
        assertEquals("UNKNOWN", unknown["status"]); assertTrue(rows(unknown["proofs"]).isEmpty())
    }

    @Test fun actualBoundsAndNonBmpByteFramingSurviveProjection() {
        val t = Theory(_l["(subclass 😀 B)", "(subclass 😀 B)"])
        val plan = t.plan("subclass", "😀", "B", KifActivationLimits(maxProofs = 1))
        assertEquals("SUPPORTED", plan["status"]); assertEquals(true, plan["truncated"])
        assertEquals(1, rows(plan["proofs"]).size)
        ReviewedTaxonomyProofPaths.validate(plan, t.frozen())
        val bounded = t.plan("subclass", "😀", "B", KifActivationLimits(maxSteps = 1))
        assertEquals("BOUNDED", bounded["status"]); assertEquals(true, bounded["truncated"])
    }

    @Test fun existingCharacterProfileAdmitsUtf8ReceiptsLargerThanOneMiB() {
        val formula = "(subclass ${"中".repeat(60_000)} B)"
        val t = Theory((0 until 16).toSeries().view.map { formula })
        assertTrue(t.assertions.sumOf { it.text.length } < KifActivationLimits().maxTotalChars)
        val actual = KifTaxonomySnapshot.create(1, t.assertions).activationPlan("subclass", "B", "B")
        assertTrue(actual.export().size > 1024 * 1024)
        val projection = ReviewedTaxonomyProofPaths.project(actual, t.selections)
        ReviewedTaxonomyProofPaths.validate(projection, t.frozen())
        assertEquals("SUPPORTED", projection["status"])
        assertEquals(16, rows(projection["sources"]).size)
        assertEquals(16, rows(projection["proofs"]).size)
    }

    @Test fun alteredLinksQuotesAndOccurrenceMetadataAreRefused() {
        val t = Theory(_l["(instance Tenant Person)", "(subclass Person Agent)"])
        val plan = t.plan("instance", "Tenant", "Agent")
        val sources = rows(plan["sources"])
        val proofs = rows(plan["proofs"])
        fun changed(key: String, value: Any?) = plan + _m[key j value]
        fun sourceChange(change: Map<String, Any?>) = changed("sources", _l[sources[0] + change, sources[1]])
        val proof = proofs.single()
        for (bad in s_[
            changed("sources", _l[sources[0], sources[0]]),
            changed("proofs", _l[proof + _m["premises" j (proof["premises"] as List<*>).reversed()]]),
            changed("proofs", _l[proof + _m["premises" j _l[ContentId.of("missing".encodeToByteArray()).value]]]),
            sourceChange(_m["text" j "(instance Wrong Person)"]),
            sourceChange(_m["nodeId" j ContentId.of("substitution".encodeToByteArray()).value]),
            plan + _m["export" j ((plan["export"] as String) + "trailing")]
        ].view) assertFails { ReviewedTaxonomyProofPaths.validate(bad, t.frozen()) }
        // Original-document identity is outside the fragment export. The full frozen join must catch it.
        val q = sources[0]["quotation"] as Map<*, *>
        val original = sourceChange(_m["quotation" j (q + _m["originalCid" j ContentId.of("other".encodeToByteArray()).value])])
        ReviewedTaxonomyProofPaths.validate(original)
        assertFails { ReviewedTaxonomyProofPaths.validate(original, t.frozen()) }
        assertFails { ReviewedTaxonomyProofPaths.validate(plan, t.frozen().take(1)) }
    }
}
