package borg.trikeshed.kif

import borg.trikeshed.job.ContentId
import borg.trikeshed.ontology.SumoClassifier
import kotlin.test.*

class KifTaxonomySnapshotTest {
    private fun source(id: String, text: String, review: KifReview = KifReview.APPROVED) =
        KifSourceAssertion(id, ContentId.of("source:$id".encodeToByteArray()), "source-v1", review, text)

    @Test fun diamondHasIndependentSourceBackedProofsAndRetractionNeedsLastSupport() {
        val sources = listOf(source("ab", "(subclass A B)"), source("ac", "(subclass A C)"),
            source("bd", "(subclass B D)"), source("cd", "(subclass C D)"))
        val old = KifTaxonomySnapshot.create(1, sources)
        val answer = old.explain("subclass", "A", "D")
        assertEquals(KifExplanationStatus.SUPPORTED, answer.status)
        assertFalse(answer.truncated)
        assertEquals(setOf(listOf("ab", "bd"), listOf("ac", "cd")), answer.proofs.map { it.assertionIds }.toSet())
        for (proof in answer.proofs) {
            assertEquals(listOf("kif-taxonomy/v1/subclass-assertion", "kif-taxonomy/v1/subclass-transitivity"), proof.ruleIds)
            for (id in proof.assertionIds) {
                val retained = assertNotNull(old.assertion(id))
                assertEquals("source-v1", retained.source.sourceRevision)
                assertEquals(sources.single { it.id == id }.sourceCid, retained.source.sourceCid)
            }
        }
        val next = KifTaxonomySnapshot.create(2, sources.filter { it.id != "ab" })
        assertEquals(listOf("ac", "cd"), next.explain("subclass", "A", "D").proofs.single().assertionIds)
        val last = KifTaxonomySnapshot.create(3, sources.filter { it.id !in setOf("ab", "ac") })
        assertEquals(KifExplanationStatus.UNKNOWN, last.explain("subclass", "A", "D").status)
        assertEquals(2, old.explain("subclass", "A", "D").proofs.size, "Old immutable context must remain unchanged")
        assertEquals(3, last.revision)
    }

    @Test fun cyclicTaxonomyAndInstancesMatchClassifierWithoutClassInstanceCollapse() {
        val sources = listOf(source("ab", "(subclass A B)"), source("ba", "(subclass B A)"),
            source("bc", "(subclass B C)"), source("item", "(instance Item A)"))
        val snapshot = KifTaxonomySnapshot.create(4, sources)
        val classifier = SumoClassifier.of(sources.map { KifExpr.parse(it.text) })
        for (a in listOf("A", "B", "C")) for (b in listOf("A", "B", "C"))
            assertEquals(classifier.subclassOf(a, b), snapshot.explain("subclass", a, b).status == KifExplanationStatus.SUPPORTED)
        for (b in listOf("A", "B", "C")) {
            val result = snapshot.explain("instance", "Item", b)
            assertEquals(classifier.isA("Item", b), result.status == KifExplanationStatus.SUPPORTED)
            assertFalse(result.truncated)
        }
        assertEquals(KifExplanationStatus.UNKNOWN, snapshot.explain("subclass", "Item", "A").status)
        assertEquals(KifExplanationStatus.UNKNOWN, snapshot.explain("instance", "A", "C").status)
        assertEquals(KifExplanationStatus.UNKNOWN, snapshot.explain("subclass", "Missing", "Missing").status)
    }

    @Test fun duplicateIndependentAssertionsAndUnreviewedRulesKeepTheirIdentities() {
        val sources = listOf(source("one", "(subclass A B)"), source("two", "(subclass A B)"),
            source("unverified", "(subclass B C)", KifReview.UNVERIFIED),
            source("negative", "(not (subclass A B))"),
            source("quantified", "(forall (?X) (=> (instance ?X B) (instance ?X C)))"),
            source("functional", "(subclass (Fn A) C)"))
        val snapshot = KifTaxonomySnapshot.create(5, sources)
        assertEquals(setOf(listOf("one"), listOf("two")), snapshot.explain("subclass", "A", "B").proofs.map { it.assertionIds }.toSet())
        assertEquals(KifExplanationStatus.UNKNOWN, snapshot.explain("subclass", "A", "C").status)
        assertEquals(4, snapshot.assertions.count { it.exclusion != null })
        val supported = snapshot.explain("subclass", "A", "B")
        assertEquals("positive-ground-taxonomy-support/v1", supported.semantics)
        assertEquals(setOf("unverified", "negative", "quantified", "functional"), supported.excludedAssertionIds.toSet())
        assertEquals(KifExplanationStatus.SUPPORTED, supported.status)
        assertEquals("(not (subclass A B))", snapshot.assertion("negative")?.source?.text)
        assertNotNull(snapshot.assertion("negative")?.exclusion)
        assertEquals(KifExplanationStatus.UNSUPPORTED, snapshot.explain("not", "A", "B").status)
        assertNotNull(snapshot.assertion("quantified")?.decoded)
        assertFailsWith<IllegalArgumentException> { KifTaxonomySnapshot.create(6, sources + sources.first()) }
    }

    @Test fun boundsNeverTurnIncompleteSearchIntoNegativeEvidence() {
        val sources = listOf(source("ab", "(subclass A B)"), source("bc", "(subclass B C)"))
        val bounded = KifTaxonomySnapshot.create(7, sources, KifActivationLimits(maxSteps = 1))
        val result = bounded.explain("subclass", "A", "C")
        assertEquals(KifExplanationStatus.BOUNDED, result.status)
        assertTrue(result.truncated)
        assertTrue(result.proofs.isEmpty())
        val duplicate = sources + source("ab2", "(subclass A B)")
        val proofBound = KifTaxonomySnapshot.create(8, duplicate, KifActivationLimits(maxProofs = 1))
            .explain("subclass", "A", "B")
        assertEquals(KifExplanationStatus.SUPPORTED, proofBound.status)
        assertTrue(proofBound.truncated)
        assertEquals(1, proofBound.proofs.size)
    }
}
