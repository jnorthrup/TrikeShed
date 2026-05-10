package borg.trikeshed.ws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Rfc6455HandshakeTest {
    @Test
    fun computeAccept_matchesRfcExample() {
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            Rfc6455Handshake.computeAccept("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }

    @Test
    fun validateUpgradeResponse_acceptsRfcExample() {
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"

        assertTrue(Rfc6455Handshake.validateUpgradeResponse(response, "dGhlIHNhbXBsZSBub25jZQ=="))
    }
}
