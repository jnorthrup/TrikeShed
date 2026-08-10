package borg.trikeshed.cas

import borg.trikeshed.context.ElementState
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CasReplicationElementTest {

    class TrackingHook : CasReplicationHook {
        val puts = mutableListOf<Pair<ContentId, ByteArray>>()
        var delayMs = 0L

        override suspend fun onPut(cid: ContentId, payload: ByteArray) {
            if (delayMs > 0) delay(delayMs)
            puts.add(cid to payload)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testElementLifecycleAndFanout() = runTest {
        val element = CasReplicationElement()
        assertEquals(ElementState.CREATED, element.lifecycleState)

        val hook1 = TrackingHook()
        val hook2 = TrackingHook()

        element.registerHook(hook1)
        element.registerHook(hook2)

        element.open()
        assertTrue(element.lifecycleState == ElementState.OPEN || element.lifecycleState == ElementState.ACTIVE)

        val payload = "hello".encodeToByteArray()
        val cid = ContentId.of(payload)

        element.replicate(cid, payload)

        element.drain()
        assertEquals(ElementState.CLOSED, element.lifecycleState)

        assertEquals(1, hook1.puts.size)
        assertEquals(cid, hook1.puts[0].first)
        assertEquals(1, hook2.puts.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testGracefulDrain() = runTest {
        val element = CasReplicationElement(capacity = 10)
        val hook = TrackingHook().apply { delayMs = 50 }

        element.registerHook(hook)
        element.open()

        val payload = "drain test".encodeToByteArray()
        val cid = ContentId.of(payload)

        // Queue it up
        element.replicate(cid, payload)

        // Drain should block until the hook finishes
        element.drain()

        assertEquals(1, hook.puts.size, "Hook should have completed before drain returned")
        assertEquals(ElementState.CLOSED, element.lifecycleState)
    }
}
