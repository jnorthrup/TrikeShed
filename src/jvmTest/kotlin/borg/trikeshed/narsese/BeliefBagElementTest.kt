package borg.trikeshed.narsese

import borg.trikeshed.context.ElementState
import borg.trikeshed.couch.isam.JvmDurableAppendLog
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Phase-2 gate: bag lifecycle, revise/attend/evict/revive, WAL replay, codec locality. */
class BeliefBagElementTest {

    private class MapCas : CasStore() {
        val blobs = HashMap<ContentId, ByteArray>()
        override fun put(bytes: ByteArray): ContentId = ContentId.of(bytes).also { blobs[it] = bytes }
        override fun get(cid: ContentId): ByteArray? = blobs[cid]
    }

    private fun signal(angular: Long, positive: Long = Nal.UNIT, relation: RelationKind = RelationKind.CAUSALITY) =
        SemanticSignal(
            angular = angular,
            evidence = EvidenceCoord(positive, 0L),
            relation = relation,
            subjectCid = ContentId.of("subject-$angular".encodeToByteArray()).value,
        )

    private suspend fun BeliefBagElement.settle() {
        // single-consumer intake: wait until the channel stays empty across two polls
        var quiet = 0
        var spins = 0
        while (spins++ < 400 && quiet < 3) {
            delay(10)
            if (intake.isEmpty) quiet++ else quiet = 0
        }
        delay(25)
    }

    @Test
    fun mintReviseSupersedeFold() = runBlocking {
        val bag = BeliefBagElement(capacity = 16)
        bag.open()
        assertEquals(ElementState.ACTIVE, bag.state)
        bag.intake.send(BeliefIntake.Mint(signal(42L), BudgetCoord(0.8f, 0.5f, 0.5f)))
        bag.intake.send(BeliefIntake.Mint(signal(42L), BudgetCoord(0.8f, 0.5f, 0.5f)))
        bag.settle()
        assertEquals(1, bag.size, "same angular must revise, not duplicate")
        val entry = bag.snapshot().values.single()
        assertEquals(2 * Nal.UNIT, entry.evidence.positive, "evidence bases must union")
        bag.drain()
        assertEquals(ElementState.CLOSED, bag.state, "full lifecycle must reach CLOSED")
    }

    @Test
    fun attendRekeysWithoutEvidenceChange() = runBlocking {
        val bag = BeliefBagElement(capacity = 16)
        bag.open()
        bag.intake.send(BeliefIntake.Mint(signal(7L), BudgetCoord(0.9f, 0.5f, 0.5f)))
        bag.settle()
        val before = bag.snapshot().values.single().evidence
        bag.intake.send(BeliefIntake.Attend(7L, BudgetCoord(0.1f, 0.5f, 0.5f)))
        bag.settle()
        assertEquals(before, bag.snapshot().values.single().evidence)
        assertEquals(0.1f, bag.budgetOf(7L)!!.pf, 1e-3f)
        bag.drain()
    }

    @Test
    fun hijackBoundsAttentionAndSpillsPreserveEvidence() = runBlocking {
        // Hijack contract (narchy × Krapivin): the table bounds attention; losers
        // of the funnel roulette SPILL to CAS (evidence preserved), and a re-mint
        // of a spilled angular revives by evidence union. Placement is stochastic,
        // so assertions are about invariants, not specific victims.
        val cas = MapCas()
        val bag = BeliefBagElement(capacity = 8, cas = cas)
        bag.open()
        for (a in 1L..32L) {
            bag.intake.send(BeliefIntake.Mint(signal(a, positive = a * Nal.UNIT), BudgetCoord(0.5f + (a % 5) * 0.1f, 0.5f, 0.5f)))
        }
        bag.settle()
        assertTrue(bag.size <= 8, "attention must stay bounded, got ${bag.size}")
        assertTrue(cas.blobs.isNotEmpty(), "funnel losers must spill to CAS")
        // pick a spilled angular and revive it: retry until the roulette admits it
        val spilled = (1L..32L).first { bag.budgetOf(it) == null }
        var attempts = 0
        while (bag.budgetOf(spilled) == null && attempts++ < 64) {
            bag.intake.send(BeliefIntake.Mint(signal(spilled, positive = 50 * Nal.UNIT), BudgetCoord(0.99f, 0.9f, 0.9f)))
            bag.settle()
        }
        assertTrue(bag.budgetOf(spilled) != null, "a strong revive must eventually win the roulette")
        val revived = bag.snapshot().entries.first { it.key.a == spilled }.value
        assertTrue(
            revived.evidence.positive > 50 * Nal.UNIT,
            "revive must union spilled evidence (>50 units), got ${revived.evidence.positive}",
        )
        bag.drain()
    }

    @Test
    fun decayTickAppliesDecayFnAndFloors() = runBlocking {
        val bag = BeliefBagElement(
            capacity = 16,
            decayFn = { BudgetCoord(it.pf * 0.5f, it.df, it.qf) },
            priorityFloor = 0.3f,
        )
        bag.open()
        bag.intake.send(BeliefIntake.Mint(signal(10L), BudgetCoord(1.0f, 0.5f, 0.5f)))
        bag.intake.send(BeliefIntake.Mint(signal(11L), BudgetCoord(0.4f, 0.5f, 0.5f)))
        bag.settle()
        bag.intake.send(BeliefIntake.DecayTick)
        bag.settle()
        assertEquals(0.5f, bag.budgetOf(10L)!!.pf, 1e-3f)
        assertEquals(null, bag.budgetOf(11L), "0.2 priority is below the 0.3 floor — evicted")
        bag.drain()
    }

    @Test
    fun walReplayRestoresBag() = runBlocking {
        val walFile = File.createTempFile("belief", ".wal")
        walFile.deleteOnExit()
        run {
            val bag = BeliefBagElement(capacity = 16, wal = JvmDurableAppendLog(walFile), flushEvery = 1)
            bag.open()
            bag.intake.send(BeliefIntake.Mint(signal(100L, positive = 3 * Nal.UNIT), BudgetCoord(0.7f, 0.5f, 0.5f)))
            bag.intake.send(BeliefIntake.Mint(signal(200L), BudgetCoord(0.6f, 0.5f, 0.5f)))
            bag.intake.send(BeliefIntake.Attend(200L, BudgetCoord(0.2f, 0.5f, 0.5f)))
            bag.settle()
            bag.drain()
        }
        val reborn = BeliefBagElement(capacity = 16, wal = JvmDurableAppendLog(walFile))
        reborn.open()
        assertEquals(2, reborn.size)
        assertEquals(3 * Nal.UNIT, reborn.snapshot().entries.first { it.key.a == 100L }.value.evidence.positive)
        assertEquals(0.2f, reborn.budgetOf(200L)!!.pf, 1e-3f)
        reborn.drain()
    }

    @Test
    fun signalCodecRoundTrips() {
        val s = SemanticSignal(
            angular = -12345L,
            evidence = EvidenceCoord(7, 3),
            relation = RelationKind.GAP,
            subjectCid = ContentId.of("s".encodeToByteArray()).value,
            objectCid = ContentId.of("o".encodeToByteArray()).value,
            temporal = TemporalSignal(TemporalGrade.QUARTER, "2026-Q3", null, null),
            provenanceCid = ContentId.of("p".encodeToByteArray()).value,
        )
        assertEquals(s, SignalCodec.decode(SignalCodec.encode(s)))
        val bare = signal(0L)
        assertEquals(bare, SignalCodec.decode(SignalCodec.encode(bare)))
    }

    @Test
    fun angularCodecPreservesLocality() {
        val a = AngularCodec.encode(RelationKind.CAUSALITY, taxonomyKey = "skills/coding", subjectTerm = "kotlin gradle build")
        val b = AngularCodec.encode(RelationKind.CAUSALITY, taxonomyKey = "skills/coding", subjectTerm = "kotlin gradle builds")
        val c = AngularCodec.encode(RelationKind.GAP, taxonomyKey = "memories/user", subjectTerm = "prefers dark chocolate")
        val near = hamming(a, b)
        val far = hamming(a, c)
        assertTrue(near < far, "similar signals must be hamming-nearer: near=$near far=$far")
        assertTrue(near <= 8, "near-duplicates should differ in few bits, got $near")
    }

    @Test
    fun recallTopRanksByExpectationTimesPriority() = runBlocking {
        val bag = BeliefBagElement(capacity = 16)
        bag.open()
        bag.intake.send(BeliefIntake.Mint(signal(1L, positive = 10 * Nal.UNIT), BudgetCoord(0.1f, 0.5f, 0.5f)))
        bag.intake.send(BeliefIntake.Mint(signal(2L, positive = 10 * Nal.UNIT), BudgetCoord(0.9f, 0.5f, 0.5f)))
        bag.settle()
        val top = bag.recallTop(2)
        assertEquals(2, top.size)
        assertEquals(2L, top[0].a.angular)
        bag.drain()
    }
}
