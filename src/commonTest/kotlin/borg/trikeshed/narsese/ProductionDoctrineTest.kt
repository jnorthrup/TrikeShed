package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class ProductionDoctrineTest {
    private fun cid(text: String): ContentId = ContentId.of(text.encodeToByteArray())

    @Test
    fun causalRecordIsCasAnchoredAndCarriesLeafBasis() {
        val event = HermesProductionEvent(
            eventId = cid("event"),
            eventKind = HermesEventKind.TOOL_RESULT,
            sourceCid = cid("source"),
            outcomeCid = cid("outcome"),
            actor = "agent",
            tool = "tool",
            timestampMs = 1L,
        )
        val record = event.toCausalRecord("fire", "causes", "smoke")
        assertTrue(record.recordCid.value.startsWith("sha256:"))
        assertEquals(2, record.basis.leaves.size)
        assertTrue(record.basis.contains(event.sourceCid))
        assertTrue(record.basis.contains(event.outcomeCid!!))
    }

    @Test
    fun overlapRevisionDoesNotDoubleCount() {
        val a = cid("a")
        val b = cid("b")
        val receiptA = cid("receipt-a")
        val receiptB = cid("receipt-b")
        val independent = OverlapSafeRevision.revise(
            receiptA,
            receiptB,
            EvidenceCoord(100L, 0L),
            EvidenceCoord(50L, 0L),
            EvidenceBasis.of(a),
            EvidenceBasis.of(b),
        )
        assertEquals(EvidenceDependence.INDEPENDENT, independent.dependence)
        assertEquals(150L, independent.evidence.positive)

        val dependent = OverlapSafeRevision.revise(
            receiptA,
            receiptB,
            EvidenceCoord(100L, 0L),
            EvidenceCoord(50L, 0L),
            EvidenceBasis.of(a),
            EvidenceBasis.of(a, b),
        )
        assertEquals(EvidenceDependence.DEPENDENT, dependent.dependence)
        assertEquals(100L, dependent.evidence.positive)

        val duplicate = OverlapSafeRevision.revise(
            receiptA,
            receiptA,
            EvidenceCoord(100L, 0L),
            EvidenceCoord(50L, 0L),
            EvidenceBasis.of(a),
            EvidenceBasis.of(a),
        )
        assertEquals(EvidenceDependence.DUPLICATE, duplicate.dependence)
        assertEquals(100L, duplicate.evidence.positive)
    }

    @Test
    fun activationAgesWithoutChangingRuleIdentityOrEvidence() {
        val rule = EternalRule("fire", "smoke", NalCopula.IMPLICATION, EvidenceCoord(1000L, 20L))
        val beforeCid = rule.ruleCid
        val beforeEvidence = rule.evidence
        val aged = RuleActivationBudget(beforeCid, 1f, 0f, 0L).age(1L, lambda = 0.5f)
        assertEquals(0.5f, aged.priority)
        assertEquals(beforeCid, rule.ruleCid)
        assertEquals(beforeEvidence, rule.evidence)
        assertNotEquals(aged.activationState, ActivationState.ARCHIVED)
    }

    @Test
    fun bridgeProducesRulesOnlyThroughExplicitSources() {
        val kifRules = KgNalBridge.bridgeToRules("(=> fire smoke)")
        assertEquals(1, kifRules.size)
        assertEquals(NalCopula.IMPLICATION, kifRules[0].copula)

        val sumo = KgNalBridge.emitSumoSpine()
        assertTrue(sumo.size > 0)
        assertTrue(sumo[0].ruleCid.value.startsWith("sha256:"))

        val cycl = KgNalBridge.cyclToEternalRules("(#\$isa Fido #\$Dog)")
        assertEquals(1, cycl.size)
        assertEquals(NalCopula.INHERITANCE, cycl[0].copula)
    }

    @Test
    fun admissionReceiptIsThePromotionBoundary() {
        val rule = EternalRule("fire", "smoke", NalCopula.IMPLICATION, EvidenceCoord(Nal.UNIT, 0L))
        val candidate = rule.ruleCid
        val receipt = RuleAdmissionReceipt.create(
            candidateRuleCid = candidate,
            authority = AdmissionAuthority.HUMAN,
            policyCid = null,
            independentLeafBasisCount = 1,
            supportingReceiptCids = listOf(cid("support")).toSeries(),
            refutingReceiptCids = emptyList<ContentId>().toSeries(),
            rationale = "explicitly admitted",
            timestampMs = 1L,
        )
        val version = RuleSetVersion.create(listOf(rule).toSeries(), listOf(receipt).toSeries(), createdAtMs = 1L)
        assertEquals(1, version.rules.size)
        assertEquals(receipt.receiptCid, version.admissionReceiptCids[0])
    }

    @Test
    fun bagUsesExactBasisForCausalRevision() = runBlocking {
        val bag = BeliefBagElement(capacity = 16)
        bag.open()
        val subject = cid("subject")
        val first = cid("leaf-first")
        val second = cid("leaf-second")
        val third = cid("leaf-third")
        val signal = SemanticSignal(1L, EvidenceCoord(100L, 0L), RelationKind.CAUSALITY, subject.value)
        val budget = BudgetCoord(1f, 1f, 1f)
        bag.intake.send(BeliefIntake.Mint(signal, budget, cid("receipt-1"), EvidenceBasis.of(first)))
        bag.intake.send(BeliefIntake.Mint(signal.copy(evidence = EvidenceCoord(50L, 0L)), budget, cid("receipt-2"), EvidenceBasis.of(first, second)))
        repeat(10) { delay(10) }
        assertEquals(100L, bag.snapshot().values.single().evidence.positive)

        bag.intake.send(BeliefIntake.Mint(signal.copy(evidence = EvidenceCoord(25L, 0L)), budget, cid("receipt-3"), EvidenceBasis.of(third)))
        repeat(10) { delay(10) }
        assertEquals(125L, bag.snapshot().values.single().evidence.positive)
        bag.drain()
    }
}
