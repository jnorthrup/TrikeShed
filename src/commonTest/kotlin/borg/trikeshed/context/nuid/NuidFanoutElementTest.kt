package borg.trikeshed.context.nuid

import borg.trikeshed.lib.j
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

class NuidFanoutElementTest {

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
