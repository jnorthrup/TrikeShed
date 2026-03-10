package borg.trikeshed.net

import borg.trikeshed.net.channelization.ChannelSessionState
import borg.trikeshed.net.spi.IngressSteeringDecision
import borg.trikeshed.net.spi.TransportBackendKind
import borg.trikeshed.net.spi.TransportCapabilities
import one.xio.spi.SelectorSessionProjection
import one.xio.spi.SelectorTransportBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Focused tests for the selector backend projection layer.
 *
 * These tests verify that:
 * 1. SelectorTransportBackend.classifyIngress() returns HTTP for HTTP GET request bytes
 * 2. SelectorSessionProjection creates a ChannelSession with the correct protocol
 * 3. SelectorTransportBackend capabilities() reports correct backend kind
 * 4. The projection does not expose NIO types in its public API
 */
class SelectorBackendProjectionTest {

    private val backend = SelectorTransportBackend()

    // HTTP GET request bytes for testing
    private val httpGetBytes = "GET / HTTP/1.1\r\nHost: localhost:8080\r\n\r\n".encodeToByteArray()

    // HTTP POST request bytes for testing
    private val httpPostBytes = "POST /api/data HTTP/1.1\r\nHost: localhost:8080\r\nContent-Type: application/json\r\n\r\n{\"test\":\"data\"}".encodeToByteArray()

    // QUIC initial packet (first byte 0x00)
    private val quicBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03)

    // SSH identification string
    private val sshBytes = "SSH-2.0-OpenSSH_8.9\r\n".encodeToByteArray()

    @Test
    fun `classifyIngress returns HTTP for HTTP GET request bytes`() {
        val decision = backend.classifyIngress(httpGetBytes)
        assertEquals(ProtocolId.HTTP, decision.protocol)
    }

    @Test
    fun `classifyIngress returns HTTP for HTTP POST request bytes`() {
        val decision = backend.classifyIngress(httpPostBytes)
        assertEquals(ProtocolId.HTTP, decision.protocol)
    }

    @Test
    fun `classifyIngress returns QUIC for QUIC packet bytes`() {
        val decision = backend.classifyIngress(quicBytes)
        assertEquals(ProtocolId.QUIC, decision.protocol)
    }

    @Test
    fun `classifyIngress returns SSH for SSH identification bytes`() {
        val decision = backend.classifyIngress(sshBytes)
        assertEquals(ProtocolId.SSH, decision.protocol)
    }

    @Test
    fun `classifyIngress returns UNKNOWN for unrecognized bytes`() {
        val unknownBytes = "INVALID DATA".encodeToByteArray()
        val decision = backend.classifyIngress(unknownBytes)
        assertEquals(ProtocolId.UNKNOWN, decision.protocol)
    }

    @Test
    fun `capabilities reports correct backend kind`() {
        val capabilities = backend.capabilities()
        assertEquals(TransportBackendKind.SELECTOR, capabilities.backendKind)
        assertFalse(capabilities.nativeLinux)
        assertFalse(capabilities.ioUringRequested)
        assertFalse(capabilities.ioUringAvailable)
        assertFalse(capabilities.xdpSteeringRequested)
        assertFalse(capabilities.xdpAvailable)
    }

    @Test
    fun `SelectorSessionProjection creates HTTP ChannelSession for HTTP bytes`() {
        val projection = SelectorSessionProjection(backend)
        val session = projection.projectSession(httpGetBytes)

        assertNotNull(session)
        assertEquals(ProtocolId.HTTP, session.protocol)
        assertTrue(session.id.raw.startsWith("session-HTTP-"))
    }

    @Test
    fun `SelectorSessionProjection creates QUIC ChannelSession for QUIC bytes`() {
        val projection = SelectorSessionProjection(backend)
        val session = projection.projectSession(quicBytes)

        assertNotNull(session)
        assertEquals(ProtocolId.QUIC, session.protocol)
        assertTrue(session.id.raw.startsWith("session-QUIC-"))
    }

    @Test
    fun `SelectorSessionProjection creates SSH ChannelSession for SSH bytes`() {
        val projection = SelectorSessionProjection(backend)
        val session = projection.projectSession(sshBytes)

        assertNotNull(session)
        assertEquals(ProtocolId.SSH, session.protocol)
        assertTrue(session.id.raw.startsWith("session-SSH-"))
    }

    @Test
    fun `SelectorSessionProjection session has correct initial state`() {
        val projection = SelectorSessionProjection(backend)
        val session = projection.projectSession(httpGetBytes)

        assertEquals(ChannelSessionState.Initialized, session.state)
        assertFalse(session.canAcceptFrames())
    }

    @Test
    fun `SelectorSessionProjection session can transition to Active`() {
        val projection = SelectorSessionProjection(backend)
        val session = projection.projectSession(httpGetBytes)

        session.transitionTo(ChannelSessionState.Active)
        assertEquals(ChannelSessionState.Active, session.state)
        assertTrue(session.canAcceptFrames())
    }

    @Test
    fun `SelectorSessionProjection returns steering decision with session`() {
        val projection = SelectorSessionProjection(backend)
        val (session, decision) = projection.projectSessionWithDecision(httpGetBytes)

        assertNotNull(session)
        assertNotNull(decision)
        assertEquals(ProtocolId.HTTP, decision.protocol)
        assertEquals(session.protocol, decision.protocol)
    }

    @Test
    fun `SelectorSessionProjection backendKind returns SELECTOR`() {
        val projection = SelectorSessionProjection(backend)
        assertEquals("SELECTOR", projection.backendKind())
    }

    @Test
    fun `SelectorSessionProjection isAvailable returns true`() {
        val projection = SelectorSessionProjection(backend)
        assertTrue(projection.isAvailable())
    }

    @Test
    fun `ingress steering decision contains queue and worker ids`() {
        val decision = backend.classifyIngress(httpGetBytes)

        // HTTP should be queue 0
        assertEquals(0, decision.queueId)
        // Worker should be assigned
        assertEquals(0, decision.workerId)
    }

    @Test
    fun `projection does not expose NIO types in session`() {
        val projection = SelectorSessionProjection(backend)
        val session = projection.projectSession(httpGetBytes)

        // Verify we can work with the session without any NIO imports
        // The session should only expose channelization types, not NIO
        assertNotNull(session.id)
        assertNotNull(session.protocol)
        assertNotNull(session.state)

        // Transition through states
        session.transitionTo(ChannelSessionState.Active)
        assertTrue(session.canAcceptFrames())

        session.transitionTo(ChannelSessionState.Draining)
        assertFalse(session.canAcceptFrames())

        session.transitionTo(ChannelSessionState.Terminated)
        assertEquals(ChannelSessionState.Terminated, session.state)
    }
}
