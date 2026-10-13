package borg.trikeshed.context.nuid

import borg.trikeshed.lib.j
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

class NuidFanoutElementTest {

    @Test
    fun acceptedClaimsRetainIdentityBeyondTheChannelCapacity() = runTest {
        val fanout = NuidFanoutElement()
        fanout.open()
        val request = nuid(Capability.Process("test_process"), Nonce.RandomBytes(), Subnet.core)
        fanout.register(Workgroup("worker", Subnet.core,
            TraitSpace { 1 j { Capability.Process("test_process") } }))
        val slot = assertNotNull(fanout.slotOf("worker"))
        val accepted = mutableListOf<NuidFanoutElement.Claim>()
        // Start with a waiting production consumer, then send more claims than
        // the bounded accepted channel can hold without a reader.
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            repeat(256) { accepted += slot.consumeAccepted() }
        }
        try {
            val dispatched = mutableListOf<NuidFanoutElement.DispatchResult>()
            repeat(256) { ordinal -> dispatched += fanout.dispatch(request, ordinal) }
            consumer.join()
            assertEquals(256, accepted.size)
            dispatched.forEachIndexed { ordinal, result ->
                assertEquals("worker", result.winner)
                assertEquals(result.claimId, accepted[ordinal].claimId)
                assertEquals(ordinal, accepted[ordinal].payload)
                assertEquals(request, accepted[ordinal].nuid)
            }
        } finally {
            consumer.cancelAndJoin()
            fanout.close()
        }
    }

    @Test
    fun testFanout16Candidates() = runTest {
        val fanout = NuidFanoutElement()
        fanout.open()

        val nuid = nuid(Capability.Process("test_process"), Nonce.RandomBytes(), Subnet.core)

        // Register 16 candidates
        val jobs = mutableListOf<Job>()
        for (i in 1..16) {
            val wg = Workgroup(
                name = "worker-$i",
                scope = Subnet.core,
                traits = TraitSpace { 1 j { Capability.Process("test_process") } }
            )
            fanout.register(wg)

            val slot = fanout.slotOf("worker-$i")
            assertNotNull(slot)

            jobs.add(launch {
                delay(10) // tiny delay
                slot.consume()
            })
        }

        val result = withTimeoutOrNull(200.milliseconds) {
            fanout.dispatch(nuid, null, 100L)
        }

        assertNotNull(result)
        assertNotNull(result.winner)

        jobs.forEach { it.cancel() }
    }
}
