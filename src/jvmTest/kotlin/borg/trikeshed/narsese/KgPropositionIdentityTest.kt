package borg.trikeshed.narsese

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.cursor.query.rowsCursor
import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Synthetic source-context counterexamples through the actual bridge and owning bag. */
class KgPropositionIdentityTest {
    private val context = ContentId.of("synthetic-context-A".encodeToByteArray())
    private val otherContext = ContentId.of("synthetic-context-B".encodeToByteArray())
    private val a = ContentId.of("synthetic-independent-source-A".encodeToByteArray())
    private val b = ContentId.of("synthetic-independent-source-B".encodeToByteArray())
    private val tenant = ContentId.of("synthetic:Tenant".encodeToByteArray())
    private val landlord = ContentId.of("synthetic:Landlord".encodeToByteArray())
    private fun receipt(name: String) = ContentId.of("synthetic-receipt:$name".encodeToByteArray())
    private fun triplet(predicate: String = "pays") = KgTriplet("Tenant", predicate, "Landlord", tenant.value, landlord.value)
    private fun mapped(predicate: String = "pays") = KgNalBridge.map(triplet(predicate))
    private suspend fun withBag(cas: CasStore = CasStore.inMemory(), wal: DurableAppendLog? = null,
        floor: Float = 0f, body: suspend (BeliefBagElement) -> Unit) {
        val bag = BeliefBagElement(cas = cas, wal = wal, priorityFloor = floor, parentJob = currentCoroutineContext()[Job])
        bag.open()
        try { body(bag) } finally {
            bag.drain()
            assertTrue(bag.worker?.isCompleted == true && bag.supervisor.isCompleted)
        }
    }
    private suspend fun mint(bag: BeliefBagElement, signal: SemanticSignal, name: String,
        basis: EvidenceBasis = EvidenceBasis.of(a)) = bag.submit(BeliefIntake.Mint(signal,
        BudgetCoord(.2f, .8f, .5f), receipt(name), basis, "original pays"))
    private suspend fun refused(issue: BeliefAdmissionIssue, action: suspend () -> Unit) {
        val failure = try { action(); null } catch (failure: BeliefAdmissionRefused) { failure }
        assertNotNull(failure)
        assertEquals(issue, failure.issue)
    }

    @Test fun naturalPredicateCollisionRefusesBeforeSameReceiptReplayAndKeepsActorHealthy() = runBlocking {
        withTimeout(10_000) { withBag { bag ->
            val pays = mapped().signal(context.value)
            val owes = mapped("owes").signal(context.value)
            // This is the actual mapping collision, not a fixture-forced angular override.
            assertEquals(pays.angular, owes.angular)
            assertNotEquals(pays.propositionCid, owes.propositionCid)
            mint(bag, pays, "same-receipt")
            val before = SignalCodec.encode(requireNotNull(bag.signalOf(pays.angular)))
            val budget = bag.budgetOf(pays.angular)
            refused(BeliefAdmissionIssue.PROPOSITION_MISMATCH) { mint(bag, owes, "same-receipt", EvidenceBasis.of(b)) }
            assertContentEquals(before, SignalCodec.encode(requireNotNull(bag.signalOf(pays.angular))))
            assertEquals(budget, bag.budgetOf(pays.angular)); assertEquals("original pays", bag.glossOf(pays.angular))
            mint(bag, pays, "independent", EvidenceBasis.of(b))
            assertEquals(pays.evidence.positive * 2, bag.signalOf(pays.angular)?.evidence?.positive)
            bag.awaitProcessed(); assertTrue(bag.worker?.isActive == true)
        } }
    }

    @Test fun identityPreservesPredicateOrderedRolesTemporalAndDeclaredContextButExcludesEvidence() {
        val base = mapped().propositionCid(context.value)
        val same = KgNalBridge.map(triplet().copy(confidence = .1f)).signal(context.value, receipt("different-observation").value)
        assertEquals(base.value, same.propositionCid)
        val variants = s_[triplet("owes"), triplet().copy(subject = "Landlord", obj = "Tenant"),
            triplet().copy(subjectCid = landlord.value, objectCid = tenant.value),
            triplet().copy(temporal = TemporalSignal(TemporalGrade.YEAR, "2026")),
            triplet().copy(temporal = TemporalSignal(TemporalGrade.YEAR, "2026", "2027"))]
        for (variant in variants.view) assertNotEquals(base, KgNalBridge.map(variant).propositionCid(context.value))
        assertNotEquals(base, mapped().propositionCid(otherContext.value))
        assertEquals(base.value, same.copy(evidence = EvidenceCoord(100, 100), basisBloom = 123L).propositionCid)
        assertNotEquals(base, KgNalBridge.map(triplet().copy(predicate = "pays\u0000")).propositionCid(context.value))
        for (invalid in s_["\uD800", "\uD801", "\uDC00"].view) {
            val malformed = s_[triplet().copy(subject = invalid), triplet().copy(predicate = invalid),
                triplet().copy(obj = invalid), triplet().copy(subjectCid = invalid), triplet().copy(objectCid = invalid),
                triplet().copy(temporal = TemporalSignal(TemporalGrade.YEAR, invalid)),
                triplet().copy(temporal = TemporalSignal(TemporalGrade.YEAR, "2026", invalid)),
                triplet().copy(temporal = TemporalSignal(TemporalGrade.YEAR, "2026", sourceCid = invalid))]
            for (variant in malformed.view) assertFailsWith<CharacterCodingException> { KgNalBridge.map(variant).propositionCid(context.value) }
            assertFailsWith<CharacterCodingException> { mapped().propositionCid(invalid) }
        }
    }

    @Test fun knownUnknownMixingCannotUpgradeOrEraseIdentityInEitherDirection() = runBlocking {
        withTimeout(10_000) {
            val known = mapped().signal(context.value)
            val legacy = known.copy(propositionCid = null)
            for (first in s_[known, legacy].view) withBag { bag ->
                mint(bag, first, "first")
                val before = SignalCodec.encode(requireNotNull(bag.signalOf(first.angular)))
                val second = if (first.propositionCid == null) known else legacy
                refused(BeliefAdmissionIssue.UNRESOLVED_PROPOSITION) { mint(bag, second, "first") }
                refused(BeliefAdmissionIssue.UNRESOLVED_PROPOSITION) { mint(bag, second, "other", EvidenceBasis.of(b)) }
                assertContentEquals(before, SignalCodec.encode(requireNotNull(bag.signalOf(first.angular))))
                bag.awaitProcessed(); assertTrue(bag.worker?.isActive == true)
            }
        }
    }

    @Test fun codecKeepsHistoricalNullBytesAndReadsTenElevenAndTwelveFields() {
        val legacy = SemanticSignal(42, EvidenceCoord(1, 0), RelationKind.MATCH, "subject", "object",
            TemporalSignal(TemporalGrade.YEAR, "2026"), "receipt", 7L)
        val historical = "2:42;10:4294967296;5:MATCH;7:subject;6:object;4:YEAR;4:2026;0:;0:;7:receipt;1:7;"
        assertContentEquals(historical.encodeToByteArray(), SignalCodec.encode(legacy))
        assertEquals(legacy, SignalCodec.decode(historical.encodeToByteArray()))
        assertEquals(legacy.copy(basisBloom = 0), SignalCodec.decode(historical.removeSuffix("1:7;").encodeToByteArray()))
        val identified = legacy.copy(propositionCid = mapped().propositionCid(context.value).value)
        val bytes = SignalCodec.encode(identified)
        assertTrue(bytes.decodeToString().startsWith(historical))
        assertEquals(identified, SignalCodec.decode(bytes))
        assertFailsWith<IllegalArgumentException> { SignalCodec.decode((historical + "0:;").encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { legacy.copy(propositionCid = "sha256:short") }
        assertEquals(legacy.copy(subjectCid = "subject\u0000"), SignalCodec.decode(SignalCodec.encode(legacy.copy(subjectCid = "subject\u0000"))))
        assertFailsWith<CharacterCodingException> { SignalCodec.encode(legacy.copy(subjectCid = "\uD800")) }
        assertFailsWith<CharacterCodingException> { SignalCodec.decode(bytes + _a[0xff.toByte()]) }
    }

    @Test fun strictKifRetainsBinaryGroundLiteralsAndRejectsScopedOrLossyForms() {
        val accepted = KgNalBridge.parseKif("; comment )\n(pays Tenant Landlord)\n(labels Tenant \"notice; (exact)\")")
        assertEquals(2, accepted.size)
        assertEquals("\"notice; (exact)\"", accepted[1].obj)
        assertEquals(2, KifSemantic.decodeAll("(pays Tenant Landlord) (owes Tenant Landlord)").size)
        val refused = s_["(transfers Tenant Landlord Dollar)", "(transfers Tenant Landlord Euro)",
            "(pays Tenant)", "(not (pays Tenant Landlord))", "(necessarily (pays Tenant Landlord))",
            "(holdsDuring ContractA (pays Tenant Landlord))", "(=> (noticeGiven Tenant) (pays Tenant Landlord))",
            "(pays ?tenant Landlord)", "(pays (agentOf Tenant) Landlord)", "'(pays Tenant Landlord)",
            "(pays Tenant Landlord) (owes Tenant", "(pays Tenant Landlord) unexpected", "(pays Tenant \"unterminated)",
            " ".repeat(65_537) + "(pays Tenant Landlord)", "'".repeat(129) + "(pays Tenant Landlord)"]
        for (text in refused.view) assertFailsWith<IllegalArgumentException>(text.take(80)) { KgNalBridge.parseKif(text) }
        assertTrue(KifSemantic.decode("(not (pays Tenant Landlord))").formula is KifFormula.Not)
        assertTrue(KifSemantic.decode("(holdsDuring ContractA (pays Tenant Landlord))").formula is KifFormula.Unsupported)
        assertTrue(KifSemantic.decode("(=> (noticeGiven Tenant) (pays Tenant Landlord))").formula is KifFormula.Implies)
    }

    @Test fun realTextAndCursorConsumersCannotCrossPredicatesOrPartiallyMintMalformedKif() = runBlocking {
        withTimeout(10_000) {
            withBag { bag ->
                assertFailsWith<IllegalArgumentException> { bag.ingestKg("(pays Tenant Landlord) (not (pays Tenant Landlord))") }
                assertEquals(0, bag.size)
                val report = bag.ingestKg("(pays Tenant Landlord)")
                assertEquals(1, report["statements"])
                assertNotNull(bag.snapshot().values.single().propositionCid)
                bag.awaitProcessed()
            }
            withBag { bag ->
                val rows = rowsCursor(_l["subject", "predicate", "object"], _l[
                    _m["subject" j "Tenant", "predicate" j "pays", "object" j "Landlord"],
                    _m["subject" j "Tenant", "predicate" j "owes", "object" j "Landlord"]])
                refused(BeliefAdmissionIssue.PROPOSITION_MISMATCH) { bag.ingestKg(rows, receipt("cursor")) }
                assertEquals(1, bag.size)
                assertEquals(KgNalBridge.map(KgTriplet("Tenant", "pays", "Landlord")).propositionCid(receipt("cursor").value).value,
                    bag.snapshot().values.single().propositionCid)
                bag.awaitProcessed(); assertTrue(bag.worker?.isActive == true)
            }
        }
    }

    private class Frames : DurableAppendLog {
        val rows = mutableListOf<Join<Long, ByteArray>>()
        override fun append(sequence: Long, payload: ByteArray): Long { rows.add(sequence j payload.copyOf()); return sequence }
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            for (row in rows) onFrame(row.a, row.b.copyOf())
            return rows.lastOrNull()?.a ?: 0L
        }
        override fun flush() {}
        override fun injectCorruptionAfter(sequence: Long) { error("Not a disk/CRC fixture") }
    }

    @Test fun knownIdentitySurvivesCasAndWalAndRefusalsDoNotRewriteHistory() = runBlocking {
        withTimeout(10_000) {
            val pays = mapped().signal(context.value); val owes = mapped("owes").signal(context.value)
            withBag(floor = .5f) { bag ->
                mint(bag, pays, "archived")
                bag.intake.send(BeliefIntake.DecayTick); bag.awaitProcessed(); assertNull(bag.signalOf(pays.angular))
                refused(BeliefAdmissionIssue.PROPOSITION_MISMATCH) { mint(bag, owes, "archived") }
                refused(BeliefAdmissionIssue.UNRESOLVED_PROPOSITION) { mint(bag, pays.copy(propositionCid = null), "archived") }
                mint(bag, pays, "archived")
                assertEquals(pays, bag.signalOf(pays.angular))
            }
            val cas = CasStore.inMemory(); val wal = Frames()
            withBag(cas, wal) { bag -> mint(bag, pays, "before-replay") }
            val count = wal.rows.size
            withBag(cas, wal) { bag ->
                assertEquals(pays, bag.signalOf(pays.angular))
                refused(BeliefAdmissionIssue.PROPOSITION_MISMATCH) { mint(bag, owes, "before-replay") }
                refused(BeliefAdmissionIssue.UNRESOLVED_PROPOSITION) { mint(bag, pays.copy(propositionCid = null), "before-replay") }
                assertEquals(count, wal.rows.size)
                mint(bag, pays, "before-replay")
                refused(BeliefAdmissionIssue.UNRESOLVED_BASIS) { mint(bag, pays, "new-source", EvidenceBasis.of(b)) }
                assertEquals(pays, bag.signalOf(pays.angular))
                bag.awaitProcessed(); assertTrue(bag.worker?.isActive == true)
            }
        }
    }
}
