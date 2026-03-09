package borg.trikeshed.net

import borg.trikeshed.context.currentHandlerRegistry
import borg.trikeshed.context.withHandlers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolRouterTest {
    @Test
    fun detectProtocolUsesTypedProtocolIds() {
        assertEquals(ProtocolId.HTTP, detectProtocol("GET / HTTP/1.1\r\n\r\n".encodeToByteArray()))
        assertEquals(ProtocolId.QUIC, detectProtocol("some quic datagram".encodeToByteArray()))
        assertEquals(ProtocolId.SSH, detectProtocol("ssh-2.0-client".encodeToByteArray()))
        assertEquals(ProtocolId.UNKNOWN, detectProtocol("opaque bytes".encodeToByteArray()))
    }

    @Test
    fun withHandlersOverlaysLaterProtocolHandlers() = runTest {
        val payload = "GET / HTTP/1.1\r\n\r\n".encodeToByteArray()
        val first: ProtocolHandler = { "first".encodeToByteArray() }
        val second: ProtocolHandler = { "second".encodeToByteArray() }

        val result = withHandlers(ProtocolId.HTTP to first) {
            withHandlers(ProtocolId.HTTP to second) {
                routeProtocol(payload)
            }
        }

        assertContentEquals("second".encodeToByteArray(), result)
    }

    @Test
    fun withHandlersRetainsCoexistingProtocolKeys() = runTest {
        val result = withHandlers(
            ProtocolId.HTTP to httpHandler,
            ProtocolId.QUIC to quicHandler,
            ProtocolId.SSH to sshHandler,
        ) {
            val registry = currentHandlerRegistry<ProtocolId, ProtocolHandler>()
            assertNotNull(registry)
            assertEquals(setOf(ProtocolId.HTTP, ProtocolId.QUIC, ProtocolId.SSH), registry.keys())
            routeProtocol("SSH banner".encodeToByteArray())
        }

        assertContentEquals(sshHandler("SSH banner".encodeToByteArray()), result)
    }

    @Test
    fun routeProtocolFailsExplicitlyWhenHandlerMissing() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            routeProtocol("unhandled".encodeToByteArray())
        }
        assertEquals("No handler for UNKNOWN", error.message)
    }
}
