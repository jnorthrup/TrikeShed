package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.context.ElementState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guard: the scopeless channel factory is not an escape hatch from CCEK.
 * A channel opened without a scope must still be an Element with its own
 * supervisor, and drain must settle the facade instead of abandoning it.
 */
class UringChannelElementTest {

    @Test
    fun scopelessOpenIsElementWithLifecycle() = runBlocking {
        val channel = UringChannels.open(entries = 4)
        try {
            assertEquals(ElementState.CREATED, channel.state)
            channel.open()
            assertEquals(ElementState.OPEN, channel.state)
            assertTrue(channel.supervisor.isActive)
        } finally {
            channel.drain()
        }
        assertEquals(ElementState.CLOSED, channel.state)
    }

    @Test
    fun scopedOpenInheritsCallerSupervision() = runBlocking {
        val channel = UringChannels.open(this@runBlocking.coroutineContext[kotlinx.coroutines.Job]!!.let {
            kotlinx.coroutines.CoroutineScope(it)
        }, entries = 4)
        channel.open()
        assertEquals(ElementState.OPEN, channel.state)
        channel.drain()
        assertEquals(ElementState.CLOSED, channel.state)
    }
}
