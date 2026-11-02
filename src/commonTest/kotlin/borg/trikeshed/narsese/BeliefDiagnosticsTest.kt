package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BeliefDiagnosticsTest {
    private fun signal(angular: Long, relation: RelationKind, positive: Long, negative: Long) =
        SemanticSignal(angular, EvidenceCoord(positive, negative), relation, "source")

    @Test
    fun residentDistributionAndEvidenceAreMeasuredSeparately() {
        val summary = beliefDiagnostics(_l[
            signal(1, RelationKind.MATCH, Nal.UNIT, 0),
            signal(2, RelationKind.MATCH, 0, Nal.UNIT),
            signal(2, RelationKind.ATTRACTION, Nal.UNIT, Nal.UNIT),
            signal(3, RelationKind.CAUSALITY, 0, 0),
        ])
        assertEquals(4, summary.snapshotEntries)
        assertEquals(3, summary.coordinates)
        assertEquals(2, summary.relations[RelationKind.MATCH])
        assertEquals(1, summary.relations[RelationKind.ATTRACTION])
        assertEquals(1, summary.positiveOnly)
        assertEquals(1, summary.negativeOnly)
        assertEquals(1, summary.mixed)
        assertEquals(1, summary.noEvidence)
        assertEquals(0.5f, summary.confidence!!.min, 0.001f)
        assertEquals(0.5f, summary.confidence.median, 0.001f)
        assertEquals(2f / 3f, summary.confidence.max, 0.001f)
        assertNull(summary.derivations)
    }

    @Test
    fun receiptsAreCountedOnceAndNeverInferredFromSignals() {
        val source = ContentId.of("source".encodeToByteArray())
        val evaluator = ContentId.of("evaluator".encodeToByteArray())
        val observation = DerivationReceipt.observation(TermIdentity(1), TermIdentity(2), source, source,
            Nal.observe(true), evaluator)
        val premiseA = PremiseReceipt(source, TermIdentity(1), TermIdentity(2))
        val premiseB = PremiseReceipt(source, TermIdentity(1), TermIdentity(3))
        val induction = DerivationReceipt.induction(premiseA, premiseB, Nal.observe(true), evaluator)
        val summary = beliefDiagnostics(emptyList(), _l[observation, observation, induction])
        assertNull(summary.confidence)
        assertEquals(2, summary.derivations!!.distinct)
        assertEquals(1, summary.derivations.duplicates)
        assertEquals(1, summary.derivations.rules[RuleIdentity.OBSERVATION])
        assertEquals(1, summary.derivations.rules[RuleIdentity.INDUCTION])
        assertEquals(0, beliefDiagnostics(emptyList(), emptyList()).derivations!!.distinct)
    }
}
