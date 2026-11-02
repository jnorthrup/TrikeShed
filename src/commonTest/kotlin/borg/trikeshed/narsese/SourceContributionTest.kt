package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.test.*

class SourceContributionTest {
    private fun id(s: String) = ContentId.of(s.encodeToByteArray())
    private val proposition = id("proposition")
    private fun contribution(occurrence: String, basis: List<ContentId>, positive: Long = 1_000, negative: Long = 0) =
        SourceContribution(proposition, id(occurrence), id("extractor"), basis.sortedBy { it.value }, EvidenceCoord(positive, negative))

    @Test
    fun canonicalRoundTripAndMutableBasisRevalidation() {
        val basis = mutableListOf(id("source"))
        val c = contribution("one", basis)
        val encoded = SourceContributionCodec.encode(c)
        val decoded = SourceContributionCodec.decode(encoded)
        assertEquals(c, decoded)
        assertEquals(c.cid, ContentId.of(encoded))
        encoded.fill(0)
        assertEquals(c, decoded, "decoded content must not retain the mutable input bytes")
        assertEquals(c.cid, ContentId.of(SourceContributionCodec.encode(decoded)))
        assertFailsWith<IllegalArgumentException> { SourceContributionCodec.decode(ByteArray(16_385)) }
        val exposed = mutableListOf(id("source"))
        val mutable = SourceContribution(proposition, id("mutable"), id("extractor"), exposed, EvidenceCoord(1, 0))
        exposed.add(exposed[0])
        assertFailsWith<IllegalArgumentException> { SourceContributionCodec.encode(mutable) }
        val huge = mutableListOf(id("initial"))
        val changing = SourceContribution(proposition, id("changing"), id("extractor"), huge, EvidenceCoord(1, 0))
        repeat(10_000) { huge.add(id("tail-$it")) }
        assertFailsWith<IllegalArgumentException> { SourceContributionCodec.encode(changing) }
        assertFailsWith<IllegalArgumentException> { reduceContributions(proposition, _l[changing], emptyList()) }
    }

    @Test
    fun duplicateConflictAndMissingBasisRemainDistinct() {
        val a = contribution("one", _l[id("source")])
        val duplicate = reduceContributions(proposition, _l[a, a], emptyList())
        assertEquals(ContributionStatus.READY, duplicate.status)
        assertEquals(1, duplicate.duplicates)
        assertEquals(a.evidence, duplicate.evidence)
        val changed = a.copy(evidence = EvidenceCoord(0, 1_000))
        val conflict = reduceContributions(proposition, _l[a, changed], emptyList())
        assertEquals(ContributionStatus.CONFLICT, conflict.status)
        assertEquals(_l[a.occurrence], conflict.conflicts)
        assertNull(conflict.evidence)
        assertEquals(ContributionStatus.UNRESOLVED,
            reduceContributions(proposition, _l[contribution("unknown", emptyList())], emptyList()).status)
    }

    @Test
    fun independentAggregationRequiresExplicitDecisionAndDisjointBasis() {
        val a = contribution("one", _l[id("source-a")])
        val b = contribution("two", _l[id("source-b")], 0, 500)
        assertEquals(ContributionStatus.UNRESOLVED, reduceContributions(proposition, _l[a, b], emptyList()).status)
        val decision = ContributionIndependence(a.cid, b.cid, id("decision"))
        val ready = reduceContributions(proposition, _l[b, a], _l[decision])
        assertEquals(ContributionStatus.READY, ready.status)
        assertEquals(EvidenceCoord(1_000, 500), ready.evidence)
        assertEquals(_l[decision.decisionReceipt], ready.decisionReceipts)
        val overlap = b.copy(basis = a.basis)
        assertEquals(ContributionStatus.UNRESOLVED,
            reduceContributions(proposition, _l[a, overlap], _l[ContributionIndependence(a.cid, overlap.cid, id("decision"))]).status)
    }

    @Test
    fun weightsNeverWrapAndBoundsFailClosed() {
        val a = contribution("one", _l[id("source-a")], 0xffffffffL)
        val b = contribution("two", _l[id("source-b")], 1)
        val decision = ContributionIndependence(a.cid, b.cid, id("decision"))
        val overflow = reduceContributions(proposition, _l[a, b], _l[decision])
        assertEquals(ContributionStatus.OVERFLOW, overflow.status)
        assertNull(overflow.evidence)
        val limited = reduceContributions(proposition, List(1_025) { a }, emptyList())
        assertEquals(ContributionStatus.LIMIT, limited.status)
        assertNull(limited.evidence)
    }
}
