package borg.trikeshed.sctp

import borg.trikeshed.context.ElementState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.channels.Channel

class SctpElementTest {

    @Test
    fun testSctpSocketOperations() = runTest {
        val sctp = openSctpElement()
        assertEquals(ElementState.OPEN, sctp.state)

        val clientAssoc = sctp.connect("127.0.0.1", 1234)
        assertEquals(SctpState.COOKIE_WAIT, clientAssoc.state)

        val serverAssoc = sctp.bind(1234)
        assertEquals(SctpState.CLOSED, serverAssoc.state)

        // Progress the server handshake
        val echoChunk = SctpCookieEchoChunk(byteArrayOf(1, 2, 3))
        val newState = sctp.handleCookieEcho(serverAssoc.associationId, echoChunk)
        assertEquals(SctpState.ESTABLISHED, newState)

        // Progress the client handshake
        val initAck = SctpInitAckChunk(1u, 2u, 3u, 4u, 5u)
        sctp.handleInitAck(clientAssoc.associationId, initAck, byteArrayOf(1, 2, 3))
        val estState = sctp.handleCookieAck(clientAssoc.associationId)
        assertEquals(SctpState.ESTABLISHED, estState)
    }

    @Test
    fun testStreamHandling() = runTest {
        val sctp = openSctpElement()
        val stream = sctp.openStream()

        assertEquals(0, stream.id)
        assertNotNull(stream.send)
        assertNotNull(stream.recv)
        assertEquals(1, sctp.activeStreams)
    }

    @Test
    fun testMessageDelivery() = runTest {
        val sctp = openSctpElement()
        val stream = sctp.openStream()

        val msg = byteArrayOf(42, 43, 44)
        (stream.send as Channel<ByteArray>).send(msg)

        // Simulating echo back
        (stream.recv as Channel<ByteArray>).send(msg)

        val recvd = (stream.recv as Channel<ByteArray>).receive()
        assertTrue(msg.contentEquals(recvd))
    }
}
