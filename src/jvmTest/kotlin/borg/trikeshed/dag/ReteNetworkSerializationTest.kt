package borg.trikeshed.dag

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The network serializes its writers (couch tendon, board element, job
 * supervisor, every plane bridge) and tells observers about each op exactly
 * once. Two real dispatchers hammer one network; nothing may be lost and the
 * interest counters must come out exact — they are what decides whether a
 * production is evaluated at all.
 */
class ReteNetworkSerializationTest {

    private val partition = "probe"
    private val board = BlackboardContext(partition)
    private val perSide = 100

    private fun cidOf(vararg parts: Any?): ContentId = ContentId.of(parts.joinToString("|").encodeToByteArray())

    /** A production interested in `kind=probe` that only records WHEN it was evaluated. */
    private class Recorder : ReteProduction {
        override val ruleId = "recorder"
        override val salience = 0
        override val interests: Series<Join<String, Any?>> = 1 j { _: Int -> "kind" j ("probe" as Any?) }
        val evaluatedPartitions = ArrayList<String>()
        override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
            evaluatedPartitions.add(partitionId)
        }
    }

    @Test
    fun concurrentAssertsAndModifiesFromTwoDispatchersLoseNothingAndObserveEveryOpOnce() = runBlocking {
        val net = ReteNetwork()
        val recorder = Recorder()
        net.register(recorder)

        // A plain ArrayList on purpose: observers are invoked under the write
        // lock, so a non-thread-safe sink must still come out intact.
        val observed = ArrayList<Pair<ReteOp, FactId>>()
        net.observe { op, fact -> observed.add(op to fact.factId) }

        // Phase 1: 200 asserts, half from Default, half from IO, interleaved by yields.
        coroutineScope {
            for (i in 0 until perSide) {
                launch(Dispatchers.Default) {
                    yield()
                    net.assert(FactId(partition, "f-$i"), mapOf("kind" to "probe", "n" to i), cidOf("v1", i), board)
                }
                launch(Dispatchers.IO) {
                    val k = i + perSide
                    net.assert(FactId(partition, "f-$k"), mapOf("kind" to "probe", "n" to k), cidOf("v1", k), board)
                    yield()
                }
            }
        }
        val total = perSide * 2
        val afterAsserts = net.snapshot()
        assertEquals(total, afterAsserts.size, "no assert lost")
        assertEquals(total, afterAsserts.map { it.factId.localId }.toSet().size)
        assertEquals(total, net.workingMemory.query(board, "kind" to "probe").size)
        assertEquals(total, observed.size, "one observation per assert")
        assertTrue(observed.all { it.first == ReteOp.ASSERT })
        assertEquals((0 until total).map { "f-$it" }.toSet(), observed.map { it.second.localId }.toSet())
        assertEquals(total, recorder.evaluatedPartitions.size, "every new fact evaluated the interested production once")

        // Phase 2: every fact modified from BOTH dispatchers concurrently (400 modifies).
        observed.clear()
        coroutineScope {
            for (i in 0 until total) {
                val id = FactId(partition, "f-$i")
                launch(Dispatchers.Default) { net.modify(id, mapOf("kind" to "probe", "n" to i, "side" to "default"), cidOf("default", i)) }
                launch(Dispatchers.IO) { net.modify(id, mapOf("kind" to "probe", "n" to i, "side" to "io"), cidOf("io", i)) }
            }
        }
        assertEquals(total * 2, observed.size, "every modify observed")
        assertTrue(observed.all { it.first == ReteOp.MODIFY })
        assertEquals(total, net.snapshot().size, "modify never duplicates a fact")
        assertEquals(total, net.workingMemory.query(board, "kind" to "probe").size)
        // each fact ends in one of the two versions, never a torn one
        for (f in net.snapshot()) {
            val side = f.fields["side"]
            assertTrue(side == "default" || side == "io", "torn fact ${f.factId}: $side")
            assertEquals(cidOf(side, f.fields["n"]), f.versionCid)
        }

        // Re-assert of the identical current version is a no-op the observer does NOT see.
        observed.clear()
        val current = net.snapshot().first()
        net.assert(current.factId, current.fields, current.versionCid, board)
        assertTrue(observed.isEmpty(), "identical re-assert is silent")
        assertEquals(total, net.snapshot().size)

        // Phase 3: retract everything concurrently; counters must reach exactly zero.
        recorder.evaluatedPartitions.clear()
        coroutineScope {
            for (i in 0 until total) {
                val id = FactId(partition, "f-$i")
                launch(if (i % 2 == 0) Dispatchers.Default else Dispatchers.IO) { net.retract(id) }
            }
        }
        assertEquals(total, observed.size)
        assertTrue(observed.all { it.first == ReteOp.RETRACT })
        assertEquals(0, net.snapshot().size)
        // counters are decremented BEFORE evaluation, so the final retract (count 0) is the one that does not evaluate
        assertEquals(total - 1, recorder.evaluatedPartitions.size, "a retract with live interest still evaluates")

        // Retracting an absent fact is not an op.
        observed.clear()
        net.retract(FactId(partition, "f-0"))
        assertTrue(observed.isEmpty())

        // The proof the counters were serialized: with every probe fact gone the
        // interest count is 0, so an unrelated fact in the partition must NOT
        // evaluate the recorder. A lost decrement would leave it > 0.
        recorder.evaluatedPartitions.clear()
        net.assert(FactId(partition, "other"), mapOf("kind" to "other"), cidOf("other"), board)
        assertTrue(recorder.evaluatedPartitions.isEmpty(), "interest counter drifted: ${recorder.evaluatedPartitions}")
        assertEquals(listOf(ReteOp.ASSERT to FactId(partition, "other")), observed)
    }

    @Test
    fun snapshotSpansPartitionsAndIsOrdered() = runBlocking {
        val net = ReteNetwork()
        net.assert(FactId("b", "2"), mapOf("x" to 1), cidOf("b2"), BlackboardContext("b"))
        net.assert(FactId("a", "9"), mapOf("x" to 2), cidOf("a9"), BlackboardContext("a"))
        net.assert(FactId("b", "1"), mapOf("x" to 3), cidOf("b1"), BlackboardContext("b"))
        assertEquals(
            listOf(FactId("a", "9"), FactId("b", "1"), FactId("b", "2")),
            net.snapshot().map { it.factId },
        )
    }

    @Test
    fun observerFailuresAreCountedNotPropagatedAndDisposerStopsCallbacks() = runBlocking {
        val net = ReteNetwork()
        val seen = ArrayList<ReteOp>()
        val handle = net.observe { op, _ -> seen.add(op) }
        net.observe { _, _ -> error("boom") }

        net.assert(FactId("p", "1"), mapOf("k" to "v"), cidOf(1), BlackboardContext("p"))
        assertEquals(listOf(ReteOp.ASSERT), seen)
        assertEquals(1L, net.observerFailures)
        assertEquals(1, net.snapshot().size, "the op applied even though an observer threw")

        handle.close()
        net.modify(FactId("p", "1"), mapOf("k" to "w"), cidOf(2))
        assertEquals(listOf(ReteOp.ASSERT), seen, "disposed observer hears nothing more")
        assertEquals(2L, net.observerFailures)
    }

    @Test
    fun observerSeesTheRetractedFactAsItWas() = runBlocking {
        val net = ReteNetwork()
        var retracted: ReteStoredFact? = null
        net.observe { op, fact -> if (op == ReteOp.RETRACT) retracted = fact }
        val id = FactId("p", "1")
        net.assert(id, mapOf("k" to "v"), cidOf(1), BlackboardContext("p"))
        net.modify(id, mapOf("k" to "w"), cidOf(2))
        net.retract(id)
        assertEquals(mapOf("k" to "w"), retracted?.fields)
        assertEquals(cidOf(2), retracted?.versionCid)
        assertFalse(net.snapshot().any { it.factId == id })
    }

    @Test
    fun concurrentSnapshotsNeverSeeATornWorkingMemory() = runBlocking {
        val net = ReteNetwork()
        val b = BlackboardContext("s")
        val writers = (0 until 50).map { i ->
            async(Dispatchers.Default) {
                net.assert(FactId("s", "w-$i"), mapOf("i" to i), cidOf(i), b)
                net.modify(FactId("s", "w-$i"), mapOf("i" to i, "m" to true), cidOf(i, "m"))
            }
        }
        val readers = (0 until 50).map {
            async(Dispatchers.IO) { net.snapshot().size }
        }
        writers.awaitAll()
        val sizes = readers.awaitAll()
        assertTrue(sizes.all { it in 0..50 }, "snapshot sizes: $sizes")
        assertEquals(50, net.snapshot().size)
    }
}
