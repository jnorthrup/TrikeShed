package one.xio.spi

import borg.trikeshed.net.spi.ReadinessInterest
import borg.trikeshed.net.spi.TransportBackendKind
import borg.trikeshed.net.spi.TransportRegistration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import java.nio.channels.Pipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SelectorTransportBackendTest {
    @Test
    fun selectorBackendRegistersAndDispatchesReadyKeys() = runTest {
        val backend = SelectorTransportBackend()
        val selector = backend.openSelector()
        val pipe = Pipe.open()
        val source = pipe.source()
        val sink = pipe.sink()
        backend.register(source, selector, TransportRegistration(setOf(ReadinessInterest.READ), "pipe"))

        backend.write(sink, ByteBuffer.wrap("hello".encodeToByteArray()))

        var readyAttachment: Any? = null
        var readyInterests = emptySet<ReadinessInterest>()
        val ready = backend.dispatch(selector, timeoutMillis = 100) { key ->
            val event = backend.readyEvent(key)
            readyAttachment = event.attachment
            readyInterests = event.interests
        }

        assertEquals(1, ready)
        assertEquals("pipe", readyAttachment)
        assertTrue(ReadinessInterest.READ in readyInterests)

        sink.close()
        source.close()
        selector.close()
    }

    @Test
    fun selectorBackendReadsAndWritesUsingRawByteBuffers() = runTest {
        val backend = SelectorTransportBackend()
        val pipe = Pipe.open()
        val sink = pipe.sink()
        val source = pipe.source()

        val written = backend.write(sink, ByteBuffer.wrap("nio".encodeToByteArray()))
        val buffer = ByteBuffer.allocate(16)
        val read = backend.read(source, buffer)
        buffer.flip()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        assertEquals(3, written)
        assertEquals(3, read)
        assertEquals("nio", bytes.decodeToString())
        assertTrue(backend.isOpen(source))
        assertEquals(TransportBackendKind.SELECTOR, backend.capabilities().backendKind)

        sink.close()
        source.close()
    }
}
