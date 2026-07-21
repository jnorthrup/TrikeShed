package borg.trikeshed.wireproto

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.reactor.ReactorAction
import borg.trikeshed.lib.j
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfixWorkerTest {
    @Test
    fun testConfixWorkerRoundTrip() = runTest {
        val worker = ConfixWorker()
        val testNuid = nuid(Capability.Process("test"), Nonce.Restored(ByteArray(0)), Subnet.core)
        val action: ReactorAction = testNuid j ("test.verb" j "test_payload".encodeToByteArray())

        val result = worker.invoke(action)
        assertEquals(action.a, result.a)
        assertEquals(action.b.a, result.b.a)
        assertTrue(action.b.b.contentEquals(result.b.b))
    }
}
