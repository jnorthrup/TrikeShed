package borg.trikeshed.narsese

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.kif.KifExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hindsight + replay scenario transcripts → SUMO/KIF banked knowledge →
 * Narsese bag signals for matching hermes curator impulses.
 */
class CuratorImpulseRecipientTest {

    private val sourceCid = "sha256:" + "a".repeat(64)

    private fun impulse(kind: CuratorImpulseKind, subject: String) =
        CuratorImpulse(kind, subject, rationale = "test rationale")

    private fun scenario(id: String, subject: String, vararg turns: String) = ReplayScenario(
        scenarioId = id,
        impulseSubject = subject,
        turns = turns.mapIndexed { i, t -> ReplayTurn(if (i % 2 == 0) "user" else "agent", t) }.toSeries(),
    )

    @Test
    fun hindsightReadsVerdictFromOutcomeMarkersOnly() {
        val impulses = listOf(impulse(CuratorImpulseKind.CONSOLIDATE, "skill-a")).toSeries()
        val scenarios = listOf(
            scenario("s1", "skill-a", "replaying consolidation", "outcome: [PASS] merge held"),
            scenario("s2", "skill-a", "second replay", "no marker here"),
        ).toSeries()
        val assessments = CuratorImpulseRecipient.assess(impulses, scenarios)
        assertEquals(2, assessments.size)
        val byScenario = assessments.toList().associateBy { it.scenarioId }
        assertEquals(HindsightVerdict.SUPPORTED, byScenario["s1"]!!.verdict)
        assertEquals(HindsightVerdict.NEUTRAL, byScenario["s2"]!!.verdict, "no marker = no verdict, never guessed")
        assertEquals(Nal.UNIT, byScenario["s1"]!!.evidence.positive)
    }

    @Test
    fun refutedVerdictCarriesNegativeEvidence() {
        val impulses = listOf(impulse(CuratorImpulseKind.PRUNE, "skill-b")).toSeries()
        val scenarios = listOf(scenario("s1", "skill-b", "replay", "[FAIL] the prune broke callers")).toSeries()
        val assessment = CuratorImpulseRecipient.assess(impulses, scenarios)[0]
        assertEquals(HindsightVerdict.REFUTED, assessment.verdict)
        assertEquals(Nal.UNIT, assessment.evidence.negative)
        assertEquals(0L, assessment.evidence.positive)
    }

    @Test
    fun lastMarkerInTranscriptWins() {
        val impulses = listOf(impulse(CuratorImpulseKind.PATCH, "skill-c")).toSeries()
        val scenarios = listOf(
            scenario("s1", "skill-c", "first try [PASS]", "later turn [FAIL] regressed"),
        ).toSeries()
        val assessment = CuratorImpulseRecipient.assess(impulses, scenarios)[0]
        assertEquals(HindsightVerdict.REFUTED, assessment.verdict, "later turns supersede earlier ones")
    }

    @Test
    fun bankIsSumoGroundedPredicateLogic() {
        val impulses = listOf(
            impulse(CuratorImpulseKind.CONSOLIDATE, "skill-a"),
            impulse(CuratorImpulseKind.PRUNE, "skill-b"),
        ).toSeries()
        val scenarios = listOf(
            scenario("s1", "skill-a", "x", "[PASS]"),
            scenario("s2", "skill-b", "x", "[FAIL]"),
        ).toSeries()
        val kb = CuratorImpulseRecipient.bank(CuratorImpulseRecipient.assess(impulses, scenarios))
        val kifText = kb.toKifFile()
        // SUMO upper spine is the ground theory
        assertTrue("(subclass Physical Entity)" in kifText)
        // impulses are SUMO Agents
        assertTrue("(instance impulse_consolidate_skill-a Agent)" in kifText)
        // verdicts are implications
        assertTrue("(=> (verdict impulse_consolidate_skill-a SUPPORTED) (outcome impulse_consolidate_skill-a keep))" in kifText)
        assertTrue("(=> (verdict impulse_prune_skill-b REFUTED) (outcome impulse_prune_skill-b drop))" in kifText)
        // the banked implication is queryable through the KB solver
        val hits = kb.query(KifExpr.parse("(=> (verdict impulse_consolidate_skill-a SUPPORTED) ?O)"))
        assertTrue(hits.isNotEmpty(), "banked knowledge must be queryable")
    }

    @Test
    fun signalsProjectSupportedAndRefutedOnly() {
        val impulses = listOf(
            impulse(CuratorImpulseKind.ADOPT, "skill-x"),
            impulse(CuratorImpulseKind.CREATE, "skill-y"),
            impulse(CuratorImpulseKind.PATCH, "skill-z"),
        ).toSeries()
        val scenarios = listOf(
            scenario("s1", "skill-x", "x", "[PASS]"),
            scenario("s2", "skill-y", "x", "[FAIL]"),
            scenario("s3", "skill-z", "x", "no verdict marker"),
        ).toSeries()
        val signals = CuratorImpulseRecipient.signals(
            CuratorImpulseRecipient.assess(impulses, scenarios),
            sourceCid,
        )
        assertEquals(2, signals.size, "NEUTRAL mints nothing — honesty over volume")
        val supported = signals.toList().first { it.relation == RelationKind.MATCH }
        val refuted = signals.toList().first { it.relation == RelationKind.CONTRADICTION }
        assertTrue(supported.evidence.positive > 0L)
        assertTrue(refuted.evidence.negative > 0L)
        assertTrue(Nal.truthOf(refuted.evidence).frequency < 0.5f, "refuted lands on the refutation front")
        assertTrue(signals.toList().all { it.provenanceCid == sourceCid })
    }

    @Test
    fun unmatchedImpulseGetsNoAssessment() {
        val impulses = listOf(impulse(CuratorImpulseKind.ADOPT, "skill-q")).toSeries()
        val scenarios = listOf(scenario("s1", "other-subject", "x", "[PASS]")).toSeries()
        assertEquals(0, CuratorImpulseRecipient.assess(impulses, scenarios).size)
    }
}
