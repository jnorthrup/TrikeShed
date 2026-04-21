package borg.trikeshed.userspace.nio

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.loadUserspaceNioSpi
import borg.trikeshed.htx.client.HtxKey
import borg.trikeshed.quic.QuicKey
import borg.trikeshed.sctp.SctpKey
import borg.trikeshed.server.buildServerContext
import borg.trikeshed.server.closeServerContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private class RecordingListener(
    private val sink: Channel<Any>,
) : AsyncContextElement(), UserspaceNioProvider.EventListener {
    companion object Key : AsyncContextKey<RecordingListener>()

    override val key: AsyncContextKey<RecordingListener>
        get() = Key

    override suspend fun open() {
        super.open()
    }

    override suspend fun close() {
        super.close()
    }

    override suspend fun onEvent(event: Any) {
        sink.send(event)
    }
}

class UserspaceNioEndToEndTest {
    @Test
    fun fanoutDeliversToAllListenersBeforeProducerResumesAndContextCloses() = runTest {
        withTimeout(5_000) {
            val serverContext = buildServerContext()
            val scope = CoroutineScope(coroutineContext + serverContext)
            val nio = loadUserspaceNioSpi()
            val sinkA = Channel<Any>(capacity = 1)
            val sinkB = Channel<Any>(capacity = 1)
            val listenerA = RecordingListener(sinkA).also { it.open() }
            val listenerB = RecordingListener(sinkB).also { it.open() }
            val event = "synthetic-event"

            scope.async {
                nio.fanout(event, listOf(listenerA, listenerB))
            }.await()

            assertEquals(event, sinkA.tryReceive().getOrNull())
            assertEquals(event, sinkB.tryReceive().getOrNull())

            closeServerContext(serverContext)
            listenerA.close()
            listenerB.close()

            val quic = serverContext[QuicKey] as? AsyncContextElement
            val sctp = serverContext[SctpKey] as? AsyncContextElement
            val htx = serverContext[HtxKey] as? AsyncContextElement
            assertNotNull(quic)
            assertNotNull(sctp)
            assertNotNull(htx)
            assertEquals(ElementState.CLOSED, quic.state)
            assertEquals(ElementState.CLOSED, sctp.state)
            assertEquals(ElementState.CLOSED, htx.state)
            assertEquals(ElementState.CLOSED, listenerA.state)
            assertEquals(ElementState.CLOSED, listenerB.state)
        }
    }
}
