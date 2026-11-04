package borg.trikeshed.narsese

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Actual single-writer admissions; invented sources, no model, global bag or daemon state. */
class BeliefAdmissionTest {
    private val a = ContentId.of("synthetic-source-A".encodeToByteArray())
    private val b = ContentId.of("synthetic-source-B".encodeToByteArray())
    private fun receipt(name: String) = ContentId.of("synthetic-receipt:$name".encodeToByteArray())
    private fun signal(angular: Long, evidence: EvidenceCoord = EvidenceCoord(1, 0)) =
        SemanticSignal(angular, evidence, RelationKind.CAUSALITY,
            receipt("tenant").value, receipt("landlord").value, provenanceCid = a.value)

    private suspend fun withBag(cas: CasStore? = CasStore.inMemory(), wal: DurableAppendLog? = null,
        capacity: Int = 4096, floor: Float = 0f, body: suspend (BeliefBagElement) -> Unit) {
        val bag = BeliefBagElement(capacity = capacity, cas = cas, wal = wal,
            priorityFloor = floor, parentJob = currentCoroutineContext()[Job])
        bag.open()
        try { body(bag) } finally {
            bag.drain()
            assertTrue(bag.worker?.isCompleted == true)
            assertTrue(bag.supervisor.isCompleted)
        }
    }

    private suspend fun mint(bag: BeliefBagElement, value: SemanticSignal, name: String,
        basis: EvidenceBasis? = EvidenceBasis.of(a), priority: Float = .8f, gloss: String = "original") =
        bag.submit(BeliefIntake.Mint(value, BudgetCoord(priority, .8f, .5f), receipt(name), basis, gloss))

    private suspend fun refused(issue: BeliefAdmissionIssue, action: suspend () -> Unit) {
        val error = try { action(); null } catch (failure: BeliefAdmissionRefused) { failure }
        assertNotNull(error, "An unsupported admission must not acknowledge success")
        assertEquals(issue, error.issue)
    }

    @Test fun structureRefusalPreservesResidentAndMetadataAndTheActorKeepsWorking() = runBlocking {
        withTimeout(10_000) { withBag { bag ->
            val original = signal(11).copy(temporal = TemporalSignal(TemporalGrade.YEAR, "2026"))
            mint(bag, original, "initial")
            val bytes = SignalCodec.encode(requireNotNull(bag.signalOf(11)))
            val budget = bag.budgetOf(11)
            val variants = s_[original.copy(subjectCid = original.objectCid!!, objectCid = original.subjectCid),
                original.copy(relation = RelationKind.MATCH),
                original.copy(temporal = original.temporal!!.copy(validFrom = "2027")),
                original.copy(temporal = original.temporal!!.copy(validUntil = "2027")),
                original.copy(temporal = null)]
            for (i in 0 until variants.size) {
                refused(BeliefAdmissionIssue.STRUCTURE_MISMATCH) {
                    mint(bag, variants[i], "refused-$i", EvidenceBasis.of(b), .99f, "wrong role")
                }
                assertContentEquals(bytes, SignalCodec.encode(requireNotNull(bag.signalOf(11))))
                assertEquals(budget, bag.budgetOf(11))
                assertEquals("original", bag.glossOf(11))
            }
            assertEquals(BeliefAdmissionIssue.STRUCTURE_MISMATCH, bag.lastAdmissionRefusal?.issue)
            // A refused receipt was not counted: valid independent support under it can still enter.
            mint(bag, original, "refused-0", EvidenceBasis.of(b))
            assertEquals(2L, bag.signalOf(11)?.evidence?.positive)
            mint(bag, signal(12), "healthy")
            bag.awaitProcessed()
            assertNotNull(bag.signalOf(12))
        } }
    }

    @Test fun unknownEmptyAndLegacyAncestryCannotBecomeIndependentSupport() = runBlocking {
        withTimeout(10_000) { withBag { bag ->
            mint(bag, signal(21), "based")
            for ((name, basis) in s_["null" j null, "empty" j EvidenceBasis.of()].view) {
                refused(BeliefAdmissionIssue.UNRESOLVED_BASIS) {
                    mint(bag, signal(21, EvidenceCoord(100, 0)), name, basis)
                }
                assertEquals(EvidenceCoord(1, 0), bag.signalOf(21)?.evidence)
            }
            mint(bag, signal(22), "legacy", basis = null)
            // Replaying the same receipt with newly supplied ancestry does not authenticate old history.
            mint(bag, signal(22, EvidenceCoord(100, 0)), "legacy", EvidenceBasis.of(a))
            assertEquals(EvidenceCoord(1, 0), bag.signalOf(22)?.evidence)
            refused(BeliefAdmissionIssue.UNRESOLVED_BASIS) {
                mint(bag, signal(22), "legacy-new-source", EvidenceBasis.of(b))
            }
            assertEquals(EvidenceCoord(1, 0), bag.signalOf(22)?.evidence)
        } }
    }

    @Test fun onlyNonemptyDisjointExactLeavesIncreaseEvidence() = runBlocking {
        withTimeout(10_000) { withBag { bag ->
            mint(bag, signal(31), "base")
            repeat(20) { mint(bag, signal(31), "base") }
            mint(bag, signal(31, EvidenceCoord(100, 50)), "same-source-larger-claim", EvidenceBasis.of(a))
            assertEquals(EvidenceCoord(1, 0), bag.signalOf(31)?.evidence)
            // A forged/legacy empty Bloom hint cannot override an exact shared leaf.
            mint(bag, signal(31, EvidenceCoord(100, 0)), "incomplete-bloom", EvidenceBasis(s_[a], 0L))
            assertEquals(EvidenceCoord(1, 0), bag.signalOf(31)?.evidence)
            mint(bag, signal(31), "independent", EvidenceBasis.of(b))
            assertEquals(EvidenceCoord(2, 0), bag.signalOf(31)?.evidence)
            mint(bag, signal(31, EvidenceCoord(100, 0)), "partial-overlap", EvidenceBasis.of(a, b))
            assertEquals(EvidenceCoord(2, 0), bag.signalOf(31)?.evidence)
            val bytes = SignalCodec.encode(requireNotNull(bag.signalOf(31)))
            bag.intake.send(BeliefIntake.Attend(31, BudgetCoord(.1f, .1f, .1f)))
            bag.awaitProcessed()
            assertContentEquals(bytes, SignalCodec.encode(requireNotNull(bag.signalOf(31))))
        } }
    }

    @Test fun spillRevivalChecksPriorIdentityAndRevisesExactlyOnce() = runBlocking {
        withTimeout(10_000) { withBag(floor = .5f) { bag ->
            mint(bag, signal(41), "spilled", priority = .2f)
            bag.intake.send(BeliefIntake.DecayTick); bag.awaitProcessed()
            assertNull(bag.signalOf(41))
            refused(BeliefAdmissionIssue.STRUCTURE_MISMATCH) {
                mint(bag, signal(41).copy(subjectCid = receipt("other").value), "wrong-spill", EvidenceBasis.of(b))
            }
            refused(BeliefAdmissionIssue.UNRESOLVED_BASIS) { mint(bag, signal(41), "unbased-spill", null) }
            mint(bag, signal(41), "spilled")
            assertEquals(EvidenceCoord(1, 0), bag.signalOf(41)?.evidence)
            bag.intake.send(BeliefIntake.Attend(41, BudgetCoord(.2f, .8f, .5f)))
            bag.intake.send(BeliefIntake.DecayTick); bag.awaitProcessed()
            assertNull(bag.signalOf(41))
            mint(bag, signal(41), "independent-after-spill", EvidenceBasis.of(b))
            assertEquals(EvidenceCoord(2, 0), bag.signalOf(41)?.evidence)
        } }
    }

    /** Real bag frame replay with an in-memory log; does not claim disk/CRC durability. */
    private class Frames : DurableAppendLog {
        val frames = mutableListOf<Join<Long, ByteArray>>()
        override fun append(sequence: Long, payload: ByteArray): Long {
            frames.add(sequence j payload.copyOf()); return sequence
        }
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            for (frame in frames) onFrame(frame.a, frame.b.copyOf())
            return frames.lastOrNull()?.a ?: 0L
        }
        override fun flush() {}
        override fun injectCorruptionAfter(sequence: Long) { error("This fixture does not emulate CRC corruption") }
    }

    @Test fun replayPreservesHistoryButDoesNotInventLostExactAncestry() = runBlocking {
        withTimeout(10_000) {
            val cas = CasStore.inMemory(); val wal = Frames()
            withBag(cas, wal) { bag -> mint(bag, signal(51), "before-restart") }
            val beforeFrames = wal.frames.size
            withBag(cas, wal) { bag ->
                refused(BeliefAdmissionIssue.STRUCTURE_MISMATCH) {
                    mint(bag, signal(51).copy(objectCid = receipt("other-role").value), "after-wrong-role", EvidenceBasis.of(b))
                }
                refused(BeliefAdmissionIssue.UNRESOLVED_BASIS) {
                    mint(bag, signal(51), "after-restart-new-source", EvidenceBasis.of(b))
                }
                assertEquals(beforeFrames, wal.frames.size, "Refusals must append no receipt/state frames")
                mint(bag, signal(51, EvidenceCoord(100, 0)), "before-restart", EvidenceBasis.of(a))
                assertEquals(EvidenceCoord(1, 0), bag.signalOf(51)?.evidence)
                refused(BeliefAdmissionIssue.UNRESOLVED_BASIS) {
                    mint(bag, signal(51), "still-unresolved", EvidenceBasis.of(b))
                }
                mint(bag, signal(52), "actor-alive")
                assertNotNull(bag.signalOf(52))
            }
        }
    }

    private class PublishedCas : CasStore() {
        val published = mutableListOf<ByteArray>()
        override fun put(bytes: ByteArray): ContentId { published.add(bytes.copyOf()); return super.put(bytes) }
        fun lastSignal(angular: Long): SemanticSignal? = published.asReversed().firstNotNullOfOrNull {
            runCatching { SignalCodec.decode(it) }.getOrNull()?.takeIf { signal -> signal.angular == angular }
        }
    }

    @Test fun receiptArchivedAfterPlacementRejectionCannotBeCountedAgain() = runBlocking {
        withTimeout(10_000) {
            val cas = PublishedCas()
            withBag(cas = cas, capacity = 4) { bag ->
                for (i in 1L..4L) mint(bag, signal(i), "resident-$i", priority = 1f)
                // Zero-priority incoming loses to every nonzero resident deterministically.
                mint(bag, signal(61), "archived-incoming", priority = 0f)
                assertNull(bag.signalOf(61))
                repeat(20) { mint(bag, signal(61), "archived-incoming", priority = 0f) }
                assertEquals(EvidenceCoord(1, 0), cas.lastSignal(61)?.evidence)
                refused(BeliefAdmissionIssue.STRUCTURE_MISMATCH) {
                    mint(bag, signal(61).copy(relation = RelationKind.MATCH), "wrong-archived", EvidenceBasis.of(b))
                }
                assertEquals(EvidenceCoord(1, 0), cas.lastSignal(61)?.evidence)
                assertEquals(4, bag.size)
            }
        }
    }

    @Test fun unavailableHistoryCannotBeResetByAnIncomingObservation() = runBlocking {
        withTimeout(10_000) {
            val cas = CasStore.inMemory()
            withBag(cas = cas, floor = .5f) { bag ->
                val original = signal(71)
                mint(bag, original, "archive", priority = .2f)
                bag.intake.send(BeliefIntake.DecayTick); bag.awaitProcessed()
                val bytes = SignalCodec.encode(original); val cid = ContentId.of(bytes)
                assertNotNull(cas.get(cid)); cas.corrupt(cid)
                refused(BeliefAdmissionIssue.ARCHIVE_UNAVAILABLE) { mint(bag, original, "archive") }
                assertNull(bag.signalOf(71))
                // Refusal preserved the archive reference, so restoring its original bytes permits safe replay.
                assertEquals(cid, cas.put(bytes))
                mint(bag, original, "archive")
                assertEquals(EvidenceCoord(1, 0), bag.signalOf(71)?.evidence)
            }
            withBag(cas = null, floor = .5f) { bag ->
                mint(bag, signal(72), "without-cas", priority = .2f)
                bag.intake.send(BeliefIntake.DecayTick); bag.awaitProcessed()
                refused(BeliefAdmissionIssue.ARCHIVE_UNAVAILABLE) { mint(bag, signal(72), "without-cas") }
                mint(bag, signal(73), "still-alive")
                assertNotNull(bag.signalOf(73))
            }
        }
    }

    @Test fun reinforcementRequiresReceiptsAndNeverTurnsRepeatedSourcesIntoNewEvidence() = runBlocking {
        withTimeout(10_000) { withBag { bag ->
            mint(bag, signal(81), "reinforce-seed")
            val budget = bag.budgetOf(81)
            refused(BeliefAdmissionIssue.RECEIPT_REQUIRED) {
                bag.submit(BeliefIntake.Reinforce(81, EvidenceCoord(100, 0)))
            }
            for (basis in s_[null, EvidenceBasis.of()].view) refused(BeliefAdmissionIssue.UNRESOLVED_BASIS) {
                bag.submit(BeliefIntake.Reinforce(81, EvidenceCoord(100, 0), receipt("unbased-reinforce"), basis))
            }
            refused(BeliefAdmissionIssue.RESIDENT_REQUIRED) {
                bag.submit(BeliefIntake.Reinforce(999, EvidenceCoord(1, 0), receipt("nonresident"), EvidenceBasis.of(b)))
            }
            assertEquals(EvidenceCoord(1, 0), bag.signalOf(81)?.evidence)
            // An explicitly sourced independent negative observation is still accepted.
            bag.submit(BeliefIntake.Reinforce(81, EvidenceCoord(0, 1), receipt("independent-negative"), EvidenceBasis.of(b)))
            assertEquals(EvidenceCoord(1, 1), bag.signalOf(81)?.evidence)
            assertEquals(receipt("independent-negative").value, bag.signalOf(81)?.provenanceCid)
            repeat(20) {
                bag.submit(BeliefIntake.Reinforce(81, EvidenceCoord(100, 100), receipt("independent-negative"), EvidenceBasis.of(b)))
            }
            bag.submit(BeliefIntake.Reinforce(81, EvidenceCoord(100, 100), receipt("same-source-new-receipt"), EvidenceBasis.of(b)))
            assertEquals(EvidenceCoord(1, 1), bag.signalOf(81)?.evidence)
            val c = receipt("synthetic-source-C")
            bag.submit(BeliefIntake.Reinforce(81, EvidenceCoord(1, 0), receipt("independent-positive"), EvidenceBasis.of(c)))
            assertEquals(EvidenceCoord(2, 1), bag.signalOf(81)?.evidence)
            assertEquals(budget, bag.budgetOf(81))
            assertEquals("original", bag.glossOf(81))
        } }
    }

    @Test fun reinforcementCannotBypassUnknownAncestryAfterLegacyAdmissionOrWalReplay() = runBlocking {
        withTimeout(10_000) {
            withBag { bag ->
                mint(bag, signal(91), "legacy-reinforce", basis = null)
                refused(BeliefAdmissionIssue.UNRESOLVED_BASIS) {
                    bag.submit(BeliefIntake.Reinforce(91, EvidenceCoord(10, 0), receipt("legacy-new-receipt"), EvidenceBasis.of(b)))
                }
                assertEquals(EvidenceCoord(1, 0), bag.signalOf(91)?.evidence)
            }
            val cas = CasStore.inMemory(); val wal = Frames()
            withBag(cas, wal) { bag -> mint(bag, signal(92), "before-reinforce-replay") }
            withBag(cas, wal) { bag ->
                // Same-receipt replay is a no-op, not a way to assign new ancestry to old history.
                bag.submit(BeliefIntake.Reinforce(92, EvidenceCoord(100, 0), receipt("before-reinforce-replay"), EvidenceBasis.of(a)))
                refused(BeliefAdmissionIssue.UNRESOLVED_BASIS) {
                    bag.submit(BeliefIntake.Reinforce(92, EvidenceCoord(1, 0), receipt("after-replay-new"), EvidenceBasis.of(b)))
                }
                bag.intake.send(BeliefIntake.Reinforce(92, EvidenceCoord(100, 0)))
                bag.awaitProcessed()
                assertEquals(BeliefAdmissionIssue.RECEIPT_REQUIRED, bag.lastAdmissionRefusal?.issue)
                assertEquals(EvidenceCoord(1, 0), bag.signalOf(92)?.evidence)
                mint(bag, signal(93), "healthy-after-reinforce-refusal")
                assertNotNull(bag.signalOf(93))
            }
        }
    }

    @Test fun lcncReinforcementRequiresSourceFieldsAndReportsOnlyProcessedCommands() = runBlocking {
        withTimeout(10_000) { withBag { bag ->
            mint(bag, signal(101), "lcnc-seed")
            val runner = BeliefsNodes.reinforceRunner(bag)
            val node = LcncNode("revision", "nal.reinforce", _m["angular" j "101", "wPlus" j "0", "wMinus" j "1"])
            assertFailsWith<IllegalArgumentException> { runner.run(node, emptyMap()) }
            assertFailsWith<IllegalArgumentException> { runner.run(node, _m["receiptCid" j receipt("node-negative").value]) }
            val inputs = _m["receiptCid" j receipt("node-negative").value, "basis" j _l[b.value]]
            for (invalid in s_[101.5, Double.NaN, 9_007_199_254_740_992.0].view)
                assertFailsWith<IllegalArgumentException> { runner.run(node, inputs + _m["angular" j invalid]) }
            for (invalid in s_["-1", "NaN", "Infinity", "999999999999"].view)
                assertFailsWith<IllegalArgumentException> { runner.run(node.copy(params = node.params + _m["wPlus" j invalid]), inputs) }
            assertEquals(EvidenceCoord(1, 0), bag.signalOf(101)?.evidence)
            val output = runner.run(node, inputs)["revised"] as Map<*, *>
            assertEquals(true, output["processed"])
            assertEquals(Nal.UNIT, output["requestedWMinus"])
            assertEquals(EvidenceCoord(1, Nal.UNIT), bag.signalOf(101)?.evidence)
            runner.run(node, inputs)
            assertEquals(EvidenceCoord(1, Nal.UNIT), bag.signalOf(101)?.evidence)
            val parameterNode = node.copy(params = node.params + _m["receiptCid" j receipt("node-negative").value,
                "basis" j borg.trikeshed.parse.jsonOf(_l[b.value])])
            assertEquals(true, (runner.run(parameterNode, emptyMap())["revised"] as Map<*, *>)["processed"])
            assertEquals(EvidenceCoord(1, Nal.UNIT), bag.signalOf(101)?.evidence)
            assertFailsWith<IllegalArgumentException> { runner.run(node, inputs + _m["basis" j emptyList<String>()]) }
        } }
    }
}
