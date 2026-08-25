package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A1 gate: evidential basis Bloom (fold/union/codec) + element seams (afterTick, temperatureNow). */
class BasisAndSeamsTest {

    private fun signal(angular: Long, bloom: Long = 0L, positive: Long = Nal.UNIT) =
        SemanticSignal(
            angular = angular,
            evidence = EvidenceCoord(positive, 0L),
            relation = RelationKind.CAUSALITY,
            subjectCid = ContentId.of("subject-$angular".encodeToByteArray()).value,
            basisBloom = bloom,
        )

    private suspend fun BeliefBagElement.settle() {
        // single-consumer intake: wait until the channel stays empty across polls
        var quiet = 0
        var spins = 0
        while (spins++ < 400 && quiet < 3) {
            delay(10)
            if (intake.isEmpty) quiet++ else quiet = 0
        }
        delay(25)
    }

    @Test
    fun basisBloomFoldIsDeterministicAndUnions() {
        val a = basisBloomOf("cid-alpha")
        val b = basisBloomOf("cid-beta")
        // deterministic: same cids, same bloom
        assertEquals(a, basisBloomOf("cid-alpha"))
        assertEquals(basisBloomOf("cid-alpha", "cid-beta"), basisBloomOf("cid-alpha", "cid-beta"))
        // fold across cids IS the OR of the singleton blooms (classic Bloom insert)
        assertEquals(a or b, basisBloomOf("cid-alpha", "cid-beta"))
        // k=3: a single cid sets at most 3 bits, at least 1
        assertTrue(a.countOneBits() in 1..3, "k=3 insert set ${a.countOneBits()} bits")
        assertTrue(b.countOneBits() in 1..3, "k=3 insert set ${b.countOneBits()} bits")
        // distinct cids should not collide on all bits (sanity, not a Bloom guarantee)
        assertTrue(a != b, "distinct cids folded to identical blooms")
        assertEquals(0L, basisBloomOf(), "empty basis is the empty bloom")
    }

    @Test
    fun reviseIntoUnionsBlooms() {
        val bloomA = basisBloomOf("receipt-a")
        val bloomB = basisBloomOf("receipt-b")
        val key = 42L j 0L
        val bag = emptyMap<Join<Long, Long>, SemanticSignal>()
            .reviseInto(key, signal(42L, bloom = bloomA))
            .reviseInto(key, signal(42L, bloom = bloomB))
        assertEquals(bloomA or bloomB, bag.getValue(key).basisBloom, "revision must Bloom-union bases")
    }

    @Test
    fun mintMergeUnionsBloomsThroughElement() = runBlocking {
        val bloomA = basisBloomOf("receipt-a")
        val bloomB = basisBloomOf("receipt-b")
        val bag = BeliefBagElement(capacity = 16)
        bag.open()
        bag.intake.send(BeliefIntake.Mint(signal(42L, bloom = bloomA), BudgetCoord(0.8f, 0.5f, 0.5f)))
        bag.intake.send(BeliefIntake.Mint(signal(42L, bloom = bloomB), BudgetCoord(0.8f, 0.5f, 0.5f)))
        bag.settle()
        assertEquals(1, bag.size, "same angular must revise, not duplicate")
        val merged = bag.snapshot().values.single()
        assertEquals(bloomA or bloomB, merged.basisBloom, "mint-merge must Bloom-union bases")
        assertEquals(2 * Nal.UNIT, merged.evidence.positive, "evidence bases must still union")
        bag.drain()
    }

    @Test
    fun codecLegacy10FieldDecodesBloomZero() {
        val s = signal(-7L, bloom = 0L)
        // legacy pre-basisBloom payload: the same length-delimited discipline, 10 fields
        val legacy = buildString {
            fun field(v: String) { append(v.length).append(':').append(v).append(';') }
            field(s.angular.toString())
            field(s.evidence.packed.toString())
            field(s.relation.name)
            field(s.subjectCid)
            field(s.objectCid ?: "")
            field("") // temporal grade
            field("") // validFrom
            field("") // validUntil
            field("") // sourceCid
            field(s.provenanceCid ?: "")
        }.encodeToByteArray()
        assertEquals(s, SignalCodec.decode(legacy), "10-field WAL/CAS records must keep decoding, bloom=0")
    }

    @Test
    fun codec11FieldRoundTripsBloom() {
        val s = signal(1234L, bloom = basisBloomOf("r1", "r2", "r3"))
        assertEquals(s, SignalCodec.decode(SignalCodec.encode(s)))
        // bloom-carrying and bloom-less signals of identical content differ at rest
        assertTrue(
            !SignalCodec.encode(s).contentEquals(SignalCodec.encode(s.copy(basisBloom = 0L))),
            "basisBloom must reach the canonical bytes",
        )
    }

    @Test
    fun afterTickFiresOncePerDecayTick() = runBlocking {
        var ticks = 0
        val bag = BeliefBagElement(capacity = 16, afterTick = { ticks++ })
        bag.open()
        bag.intake.send(BeliefIntake.Mint(signal(10L), BudgetCoord(0.9f, 0.5f, 0.5f)))
        bag.settle()
        assertEquals(0, ticks, "mint must not fire the post-tick seam")
        bag.intake.send(BeliefIntake.DecayTick)
        bag.settle()
        assertEquals(1, ticks, "exactly one afterTick per DecayTick")
        bag.intake.send(BeliefIntake.DecayTick)
        bag.settle()
        assertEquals(2, ticks, "exactly one afterTick per DecayTick")
        bag.drain()
    }

    @Test
    fun temperatureNowReadsSupplier() = runBlocking {
        var beta = 0.25f
        val bag = BeliefBagElement(capacity = 16, temperature = { beta })
        assertEquals(0.25f, bag.temperatureNow(), 1e-6f)
        beta = 2.5f
        assertEquals(2.5f, bag.temperatureNow(), 1e-6f, "supplier is live, not cached")
        assertEquals(1f, BeliefBagElement(capacity = 16).temperatureNow(), 1e-6f, "default β = 1")
    }
}
