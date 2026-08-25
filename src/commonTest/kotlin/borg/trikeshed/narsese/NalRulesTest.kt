package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Phase-1 gate: NAL truth-function numerics, receipt CID determinism, middle-term guards. */
class NalRulesTest {

    private fun cid(s: String) = ContentId.of(s.encodeToByteArray())
    private val evaluator = cid("evaluator")

    // ── truth functions ──────────────────────────────────────────────

    @Test
    fun observeMintsOneUnit() {
        assertEquals(Nal.UNIT, Nal.observe(true).positive)
        assertEquals(0L, Nal.observe(true).negative)
        assertEquals(Nal.UNIT, Nal.observe(false).negative)
        assertEquals(0L, Nal.observe(false).positive)
    }

    @Test
    fun deductionMatchesNalTable() {
        // f1=1, c1=0.9; f2=1, c2=0.9 → f=1, c=0.81 → w = c/(1−c) ≈ 4.263, all positive
        val e = Nal.deduce(TruthCoord(1f, 0.9f), TruthCoord(1f, 0.9f))
        val t = Nal.truthOf(e)
        assertEquals(1f, t.frequency, 1e-3f)
        assertEquals(0.81f, t.confidence, 1e-2f)
    }

    @Test
    fun inductionIsWeak() {
        // full-confidence premises: w = f2·c1·c2 = 1 → c = w/(w+1) = 0.5 max
        val e = Nal.induce(TruthCoord(1f, 1f), TruthCoord(1f, 1f))
        val t = Nal.truthOf(e)
        assertEquals(1f, t.frequency, 1e-3f)
        assertTrue(t.confidence <= 0.5f + 1e-3f, "induction confidence must be weak, got ${t.confidence}")
    }

    @Test
    fun abductionIsWeak() {
        val e = Nal.abduce(TruthCoord(1f, 0.9f), TruthCoord(1f, 0.9f))
        val t = Nal.truthOf(e)
        assertTrue(t.confidence < 0.5f, "abduction confidence must be weak, got ${t.confidence}")
        // w = f1·c1·c2 = 0.81 → c = 0.81/1.81 ≈ 0.4475
        assertEquals(0.4475f, t.confidence, 1e-2f)
    }

    @Test
    fun observationsAccumulateThroughRevise() {
        var e = EvidenceCoord.EMPTY
        repeat(9) { e = revise(e, Nal.observe(true)) }
        e = revise(e, Nal.observe(false))
        val t = Nal.truthOf(e)
        assertEquals(0.9f, t.frequency, 1e-3f)
        // w = 10 observations = 10×UNIT milli-units; c = w/(w+k) with k=1 obs... horizon in same units
        assertTrue(t.expectation() > 0.85f)
    }

    // ── receipts ─────────────────────────────────────────────────────

    private fun premise(cidSeed: String, subj: Long, pred: Long) =
        PremiseReceipt(cid(cidSeed), TermIdentity(subj), TermIdentity(pred))

    @Test
    fun inductionRequiresSharedSubject() {
        val r = DerivationReceipt.induction(
            premise("p1", subj = 7, pred = 1),  // M→P
            premise("p2", subj = 7, pred = 2),  // M→S
            Nal.observe(true), evaluator,
        )
        assertEquals(RuleIdentity.INDUCTION, r.ruleId)
        assertEquals(TermIdentity(2), r.conclusionSubject)   // S
        assertEquals(TermIdentity(1), r.conclusionPredicate) // P
        assertFailsWith<IllegalArgumentException> {
            DerivationReceipt.induction(premise("p1", 7, 1), premise("p2", 8, 2), Nal.observe(true), evaluator)
        }
    }

    @Test
    fun abductionRequiresSharedPredicate() {
        val r = DerivationReceipt.abduction(
            premise("p1", subj = 1, pred = 7),  // P→M
            premise("p2", subj = 2, pred = 7),  // S→M
            Nal.observe(true), evaluator,
        )
        assertEquals(TermIdentity(2), r.conclusionSubject)
        assertEquals(TermIdentity(1), r.conclusionPredicate)
        assertFailsWith<IllegalArgumentException> {
            DerivationReceipt.abduction(premise("p1", 1, 7), premise("p2", 2, 8), Nal.observe(true), evaluator)
        }
    }

    @Test
    fun revisionMergesEvidenceAndGuardsStatement() {
        val e1 = EvidenceCoord(3000, 1000)
        val e2 = EvidenceCoord(2000, 2000)
        val r = DerivationReceipt.revision(premise("p1", 1, 2), premise("p2", 1, 2), e1, e2, evaluator)
        assertEquals(5000L, r.evidence.positive)
        assertEquals(3000L, r.evidence.negative)
        assertFailsWith<IllegalArgumentException> {
            DerivationReceipt.revision(premise("p1", 1, 2), premise("p2", 1, 3), e1, e2, evaluator)
        }
    }

    @Test
    fun observationReceiptShape() {
        val r = DerivationReceipt.observation(
            TermIdentity(11), TermIdentity(22), cid("ctx"), cid("outcome"), Nal.observe(true), evaluator,
        )
        assertEquals(RuleIdentity.OBSERVATION, r.ruleId)
        assertEquals(cid("ctx"), r.premises.a)
        assertEquals(cid("outcome"), r.premises.b)
    }

    @Test
    fun receiptCidsAreDeterministicAndRuleDistinct() {
        fun mk(rule: (PremiseReceipt, PremiseReceipt, EvidenceCoord, ContentId) -> DerivationReceipt) =
            rule(premise("p1", 7, 1), premise("p2", 7, 1), Nal.observe(true), evaluator)
        val a = mk(DerivationReceipt.Companion::induction)
        val b = mk(DerivationReceipt.Companion::induction)
        assertEquals(a.canonicalCid, b.canonicalCid, "same inputs must yield same CID")
        // DEDUCTION receipt over the same terms must differ by rule name in the canonical form
        val d = DerivationReceipt.deduction(premise("p1", 7, 1), premise("p2", 1, 9), Nal.observe(true), evaluator)
        assertTrue(a.canonicalCid != d.canonicalCid)
    }
}
