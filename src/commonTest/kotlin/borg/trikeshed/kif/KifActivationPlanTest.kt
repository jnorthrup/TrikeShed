package borg.trikeshed.kif

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.test.*

class KifActivationPlanTest {
    private fun source(id: String, text: String) =
        KifSourceAssertion(id, ContentId.of("source:$id".encodeToByteArray()), "source-v1", KifReview.APPROVED, text)

    @Test fun sourceIdentityAndAlternativeDependenciesSurviveRevisionsAndRetraction() {
        val one = source("one", "(subclass A B)")
        val two = source("two", "(subclass A B)")
        val old = KifTaxonomySnapshot.create(1, _l[one, two]).activationPlan("subclass", "A", "B")
        assertEquals(2, old.proofs.size)
        assertEquals(1, old.sources.view.map { it.assertionCid }.distinct().size)
        assertEquals(2, old.sources.view.map { it.nodeId }.distinct().size)
        assertEquals(old.sources.view.map { it.nodeId }.toSet(), old.proofs.view.map { it.premises[0] }.toSet())
        val next = KifTaxonomySnapshot.create(2, _l[two, source("unrelated", "(subclass C D)")])
            .activationPlan("subclass", "A", "B")
        assertEquals(old.queryId, next.queryId)
        assertNotEquals(old.frameId, next.frameId)
        assertEquals(old.proofs.view.single { it.premises[0] == old.sources[1].nodeId }.nodeId, next.proofs[0].nodeId)
        assertEquals(old.sources[1].nodeId, next.sources[0].nodeId)
        assertEquals(2, old.proofs.size)
        val last = KifTaxonomySnapshot.create(3, _l.get<KifSourceAssertion>()).activationPlan("subclass", "A", "B")
        assertEquals(KifExplanationStatus.UNKNOWN, last.status)
        assertEquals(0, last.proofs.size)
    }

    @Test fun diamondRulesAndExcludedSyntaxRemainSourceBacked() {
        val inputs = _l[source("ab", "(subclass A B)"), source("ac", "(subclass A C)"),
            source("bd", "(subclass B D)"), source("cd", "(subclass C D)"),
            source("negative", "(not (subclass A D))"),
            source("quantified", "(forall (?X) (instance ?X D))")]
        val snapshot = KifTaxonomySnapshot.create(4, inputs)
        val plan = snapshot.activationPlan("subclass", "A", "D")
        assertEquals(KifExplanationStatus.SUPPORTED, plan.status)
        assertFalse(plan.truncated)
        assertEquals(2, plan.proofs.size)
        for (proof in plan.proofs.view) {
            assertEquals(2, proof.premises.size)
            assertEquals("kif-taxonomy/v1/subclass-assertion", proof.rules[0])
            assertEquals("kif-taxonomy/v1/subclass-transitivity", proof.rules[1])
            for (id in proof.premises.view) assertNull(plan.sources.view.single { it.nodeId == id }.exclusion)
        }
        assertEquals(2, plan.sources.view.count { it.exclusion != null })
        assertEquals(inputs.single { it.id == "quantified" }.text,
            plan.sources.view.single { it.source.id == "quantified" }.source.text)
        assertEquals(snapshot.explain("subclass", "A", "D").steps, plan.steps)
    }

    @Test fun frameBindsReviewPolicyAndIncompleteResultsWithoutUpgradingThem() {
        val inputs = _l[source("one", "(subclass A B)"), source("two", "(subclass A B)")]
        val full = KifTaxonomySnapshot.create(5, inputs).activationPlan("subclass", "A", "B")
        val limited = KifTaxonomySnapshot.create(5, inputs, KifActivationLimits(maxProofs = 1))
            .activationPlan("subclass", "A", "B")
        assertEquals(KifExplanationStatus.SUPPORTED, limited.status)
        assertTrue(limited.truncated)
        assertNotEquals(full.frameId, limited.frameId)
        val bounded = KifTaxonomySnapshot.create(5, inputs, KifActivationLimits(maxSteps = 1))
            .activationPlan("subclass", "A", "B")
        assertEquals(KifExplanationStatus.BOUNDED, bounded.status)
        assertTrue(bounded.truncated)
        val rejected = KifTaxonomySnapshot.create(5, inputs.map { it.copy(review = KifReview.REJECTED) })
            .activationPlan("subclass", "A", "B")
        assertEquals(KifExplanationStatus.UNKNOWN, rejected.status)
        assertNotEquals(full.frameId, rejected.frameId)
        assertEquals(2, rejected.sources.view.count { it.exclusion != null })
        assertEquals(KifExplanationStatus.UNSUPPORTED,
            KifTaxonomySnapshot.create(5, inputs).activationPlan("disjoint", "A", "B").status)
    }

    @Test fun exportUsesUtf8LengthsAndRetainsRawSourceWithoutDelimiterAmbiguity() {
        val input = source("résumé:3", "; λ\n(subclass A B)")
        val plan = KifTaxonomySnapshot.create(6, _l[input]).activationPlan("subclass", "A", "B")
        val bytes = plan.export()
        val fields = mutableListOf<String>()
        var offset = 0
        while (offset < bytes.size) {
            var length = 0
            while (bytes[offset] != ':'.code.toByte()) length = length * 10 + (bytes[offset++].toInt() - '0'.code)
            offset++
            fields.add(bytes.decodeToString(offset, offset + length))
            offset += length
        }
        assertEquals(bytes.size, offset)
        assertEquals("kif-activation-plan/v1", fields[0])
        assertEquals(plan.queryId.value, fields[1])
        assertEquals(plan.frameId.value, fields[2])
        assertTrue(input.text in fields)
        assertTrue(input.id in fields)
        assertTrue(input.sourceCid.value in fields)
        assertContentEquals(bytes, plan.export())
        val empty = KifTaxonomySnapshot.create(7, _l.get<KifSourceAssertion>())
        assertNotEquals(empty.activationPlan("subclass", "a:b", "c").queryId,
            empty.activationPlan("subclass", "a", "b:c").queryId)
    }
}
