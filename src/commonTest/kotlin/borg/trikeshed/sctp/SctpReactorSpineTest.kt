package borg.trikeshed.sctp

import borg.trikeshed.context.ElementState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import borg.trikeshed.reactor.SctpReactorEndpoint
import borg.trikeshed.reactor.MeshActionResult
import borg.trikeshed.reactor.PeerAddress
import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid

class SctpReactorSpineTest {

    @Test
    fun testSctpSocketCreationAndBinding() = runTest {
        val element = openSctpElement()
        val assoc = element.bind(3000)
        assertEquals(SctpState.CLOSED, assoc.state)
        assertEquals(3000L, assoc.associationId)
        element.close()
    }

    @Test
    fun testConnectionEstablishmentAndBidirectionalMessaging() = runTest {
        val serverElement = openSctpElement()
        val clientElement = openSctpElement()

        // 1. Server binds
        val serverAssoc = serverElement.bind(8080)

        // 2. Client initiates connection
        val clientAssoc = clientElement.connect("127.0.0.1", 8080)
        assertEquals(SctpState.COOKIE_WAIT, clientAssoc.state)

        // Mocking the wire for now as we test the spine interactions:
        // Server sends INIT_ACK, client receives it
        val initAck = SctpInitAckChunk(0u, 0u, 10u, 10u, 0u)
        val stateAfterInitAck = clientElement.handleInitAck(clientAssoc.associationId, initAck, byteArrayOf(1,2,3))
        assertEquals(SctpState.COOKIE_ECHOED, stateAfterInitAck)

        // Client sends COOKIE_ECHO, server receives it
        val cookieEcho = SctpCookieEchoChunk(byteArrayOf(1,2,3))
        val serverState = serverElement.handleCookieEcho(serverAssoc.associationId, cookieEcho)
        assertEquals(SctpState.ESTABLISHED, serverState)

        // Server sends COOKIE_ACK, client receives it
        val clientFinalState = clientElement.handleCookieAck(clientAssoc.associationId)
        assertEquals(SctpState.ESTABLISHED, clientFinalState)

        serverElement.close()
        clientElement.close()
    }

    @Test
    fun testConnectionTeardown() = runTest {
        val element = openSctpElement()
        element.close()
        assertEquals(ElementState.CLOSED, element.lifecycleState)
    }

    @Test
    fun testConnectionTeardownExchange() = runTest {
        val serverElement = openSctpElement()
        val clientElement = openSctpElement()

        val serverAssoc = serverElement.bind(8081)
        val clientAssoc = clientElement.connect("127.0.0.1", 8081)

        // Setup ESTABLISHED
        val initAck = SctpInitAckChunk(0u, 0u, 10u, 10u, 0u)
        clientElement.handleInitAck(clientAssoc.associationId, initAck, byteArrayOf(1,2,3))

        val cookieEcho = SctpCookieEchoChunk(byteArrayOf(1,2,3))
        serverElement.handleCookieEcho(serverAssoc.associationId, cookieEcho)

        clientElement.handleCookieAck(clientAssoc.associationId)

        assertEquals(SctpState.ESTABLISHED, serverElement.associations[serverAssoc.associationId])
        assertEquals(SctpState.ESTABLISHED, clientElement.associations[clientAssoc.associationId])

        // 1. Client initiates shutdown
        val clientState1 = clientElement.shutdown(clientAssoc.associationId)
        assertEquals(SctpState.SHUTDOWN_SENT, clientState1)

        // 2. Server receives SHUTDOWN
        val serverState1 = serverElement.handleShutdown(serverAssoc.associationId, SctpShutdownChunk(0u))
        assertEquals(SctpState.SHUTDOWN_ACK_SENT, serverState1)

        // 3. Client receives SHUTDOWN_ACK
        val clientState2 = clientElement.handleShutdownAck(clientAssoc.associationId, SctpShutdownAckChunk)
        assertEquals(SctpState.CLOSED, clientState2)

        // 4. Server receives SHUTDOWN_COMPLETE
        val serverState2 = serverElement.handleShutdownComplete(serverAssoc.associationId, SctpShutdownCompleteChunk)
        assertEquals(SctpState.CLOSED, serverState2)

        serverElement.close()
        clientElement.close()
    }
}
