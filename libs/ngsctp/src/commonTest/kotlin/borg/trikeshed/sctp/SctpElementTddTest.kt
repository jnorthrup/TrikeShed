package borg.trikeshed.sctp

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD spec for SctpElement: SCTP 4-way handshake state machine
 * and chunk codec encode/decode round-trips.
 *
 * SCTP association lifecycle (RFC 4960):
 *   Client: CLOSED → COOKIE_WAIT → COOKIE_ECHOED → ESTABLISHED
 *   Server: CLOSED → ... → ESTABLISHED (after COOKIE_ECHO handling)
 */
class SctpElementTddTest {

    // ── SctpElement is AsyncContextElement ─────────────────────────────────────

    @Test
    fun `SctpElement implements AsyncContextElement`() {
        val elem = SctpElement()
        assertTrue(elem is AsyncContextElement)
    }

    @Test
    fun `SctpElement key returns SctpElement.Key singleton`() {
        val elem = SctpElement()
        assertSame(SctpElement.Key, elem.key)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Test
    fun `SctpElement starts CREATED`() {
        val elem = SctpElement()
        assertEquals(ElementState.CREATED, elem.state)
    }

    @Test
    fun `open transitions CREATED to OPEN`() = runTest {
        val elem = SctpElement()
        elem.open()
        assertEquals(ElementState.OPEN, elem.state)
    }

    @Test
    fun `close transitions to CLOSED`() = runTest {
        val elem = SctpElement()
        elem.open()
        elem.close()
        assertEquals(ElementState.CLOSED, elem.state)
    }

    // ── StreamTransport ────────────────────────────────────────────────────────

    @Test
    fun `SctpElement implements StreamTransport`() {
        val elem = SctpElement()
        assertTrue(elem is borg.trikeshed.context.StreamTransport)
    }

    @Test
    fun `activeStreams is 0 at construction`() {
        val elem = SctpElement()
        assertEquals(0, elem.activeStreams)
    }

    @Test
    fun `openStream increments activeStreams`() = runTest {
        val elem = SctpElement()
        elem.open()
        elem.openStream()
        assertEquals(1, elem.activeStreams)
    }

    @Test
    fun `openStream requires OPEN state`() = runTest {
        val elem = SctpElement()
        val thrown = runCatching { elem.openStream() }
        assertTrue(thrown.isFailure)
    }

    // ── SCTP association IDs ───────────────────────────────────────────────────

    @Test
    fun `assocId is deterministic for same host+port`() {
        val elem = SctpElement()
        val id1 = elem.assocId("binance.com", 443)
        val id2 = elem.assocId("binance.com", 443)
        assertEquals(id1, id2)
    }

    @Test
    fun `assocId differs for different ports`() {
        val elem = SctpElement()
        val id443 = elem.assocId("binance.com", 443)
        val id80  = elem.assocId("binance.com", 80)
        assertNotEquals(id443, id80)
    }

    // ── Server: bind ──────────────────────────────────────────────────────────

    @Test
    fun `bind creates association in CLOSED state`() = runTest {
        val elem = SctpElement()
        elem.open()
        val assoc = elem.bind(8443)
        assertEquals(SctpState.CLOSED, assoc.state)
    }

    @Test
    fun `bind requires OPEN state`() = runTest {
        val elem = SctpElement()
        val thrown = runCatching { elem.bind(8443) }
        assertTrue(thrown.isFailure)
    }

    // ── Server: COOKIE_ECHO handling ───────────────────────────────────────────

    @Test
    fun `handleCookieEcho transitions CLOSED to ESTABLISHED`() = runTest {
        val elem = SctpElement()
        elem.open()
        val assoc = elem.bind(8443)
        val chunk = SctpCookieEchoChunk(byteArrayOf(1, 2, 3, 4))
        val newState = elem.handleCookieEcho(assoc.associationId, chunk)
        assertEquals(SctpState.ESTABLISHED, newState)
    }

    @Test
    fun `handleCookieEcho requires CLOSED state`() = runTest {
        val elem = SctpElement()
        elem.open()
        val assoc = elem.bind(8443)
        // Transition it already to ESTABLISHED
        elem.handleCookieEcho(assoc.associationId, SctpCookieEchoChunk(byteArrayOf()))
        val thrown = runCatching {
            elem.handleCookieEcho(assoc.associationId, SctpCookieEchoChunk(byteArrayOf()))
        }
        assertTrue(thrown.isFailure)
    }

    // ── Client: connect ───────────────────────────────────────────────────────

    @Test
    fun `connect transitions to COOKIE_WAIT`() = runTest {
        val elem = SctpElement()
        elem.open()
        val assoc = elem.connect("binance.com", 443)
        assertEquals(SctpState.COOKIE_WAIT, assoc.state)
    }

    @Test
    fun `connect requires OPEN state`() = runTest {
        val elem = SctpElement()
        val thrown = runCatching { elem.connect("binance.com", 443) }
        assertTrue(thrown.isFailure)
    }

    // ── Client: handleInitAck ──────────────────────────────────────────────────

    @Test
    fun `handleInitAck transitions COOKIE_WAIT to COOKIE_ECHOED`() = runTest {
        val elem = SctpElement()
        elem.open()
        val assoc = elem.connect("binance.com", 443)
        val initAck = SctpInitAckChunk(
            initiateTag = 123u,
            aRwnd = 1024u,
            outboundStreams = 10u,
            inboundStreams = 10u,
            initialTsn = 0u,
        )
        val newState = elem.handleInitAck(assoc.associationId, initAck, byteArrayOf(1, 2))
        assertEquals(SctpState.COOKIE_ECHOED, newState)
    }

    @Test
    fun `handleInitAck requires COOKIE_WAIT state`() = runTest {
        val elem = SctpElement()
        elem.open()
        val assoc = elem.connect("binance.com", 443)
        // Skip to COOKIE_ECHOED directly
        elem.handleInitAck(assoc.associationId, SctpInitAckChunk(0u, 0u, 0u, 0u, 0u), byteArrayOf())
        val thrown = runCatching {
            elem.handleInitAck(assoc.associationId, SctpInitAckChunk(0u, 0u, 0u, 0u, 0u), byteArrayOf())
        }
        assertTrue(thrown.isFailure)
    }

    // ── Client: handleCookieAck ────────────────────────────────────────────────

    @Test
    fun `handleCookieAck transitions COOKIE_ECHOED to ESTABLISHED`() = runTest {
        val elem = SctpElement()
        elem.open()
        val assoc = elem.connect("binance.com", 443)
        elem.handleInitAck(assoc.associationId, SctpInitAckChunk(0u, 0u, 0u, 0u, 0u), byteArrayOf())
        val newState = elem.handleCookieAck(assoc.associationId)
        assertEquals(SctpState.ESTABLISHED, newState)
    }

    @Test
    fun `handleCookieAck requires COOKIE_ECHOED state`() = runTest {
        val elem = SctpElement()
        elem.open()
        val assoc = elem.connect("binance.com", 443)
        val thrown = runCatching { elem.handleCookieAck(assoc.associationId) }
        assertTrue(thrown.isFailure)
    }

    // ── openSctpElement factory ────────────────────────────────────────────────

    @Test
    fun `openSctpElement creates and opens element`() = runTest {
        val elem = openSctpElement()
        assertEquals(ElementState.OPEN, elem.state)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Chunk encode/decode round-trip tests
// ══════════════════════════════════════════════════════════════════════════════

class SctpChunkCodecTest {

    @Test
    fun `SctpInitChunk encode then decode round-trip`() {
        val original = SctpInitChunk(
            initiateTag = 0xDEAD_BEEFu,
            aRwnd = 16384u,
            outboundStreams = 50u,
            inboundStreams = 50u,
            initialTsn = 0x1000u,
        )
        val encoded = original.encode()
        assertEquals(SctpInitChunk.CHUNK_FIXED_LENGTH.toInt(), encoded.size)
        val decoded = SctpInitChunk.decode(encoded)
        assertEquals(original.initiateTag, decoded.initiateTag)
        assertEquals(original.aRwnd, decoded.aRwnd)
        assertEquals(original.outboundStreams, decoded.outboundStreams)
        assertEquals(original.inboundStreams, decoded.inboundStreams)
        assertEquals(original.initialTsn, decoded.initialTsn)
    }

    @Test
    fun `SctpInitAckChunk encode then decode round-trip`() {
        val original = SctpInitAckChunk(
            initiateTag = 0xCAFEBABEu,
            aRwnd = 8192u,
            outboundStreams = 100u,
            inboundStreams = 100u,
            initialTsn = 0x2000u,
        )
        val encoded = original.encode()
        assertEquals(SctpInitAckChunk.CHUNK_FIXED_LENGTH.toInt(), encoded.size)
        val decoded = SctpInitAckChunk.decode(encoded)
        assertEquals(original.initiateTag, decoded.initiateTag)
        assertEquals(original.aRwnd, decoded.aRwnd)
        assertEquals(original.outboundStreams, decoded.outboundStreams)
        assertEquals(original.inboundStreams, decoded.inboundStreams)
        assertEquals(original.initialTsn, decoded.initialTsn)
    }

    @Test
    fun `SctpSackChunk encode then decode round-trip no gaps`() {
        val original = SctpSackChunk(
            cumulativeTsnAck = 0x1_0000u,
            aRwnd = 16384u,
            gapAckBlocks = emptyList(),
            duplicateTsns = emptyList(),
        )
        val encoded = original.encode()
        val decoded = SctpSackChunk.decode(encoded)
        assertEquals(original.cumulativeTsnAck, decoded.cumulativeTsnAck)
        assertEquals(original.aRwnd, decoded.aRwnd)
        assertEquals(0, decoded.gapAckBlocks.size)
        assertEquals(0, decoded.duplicateTsns.size)
    }

    @Test
    fun `SctpSackChunk encode then decode round-trip with gaps`() {
        val original = SctpSackChunk(
            cumulativeTsnAck = 0x2_0000u,
            aRwnd = 16384u,
            gapAckBlocks = listOf(
                SctpGapAckBlock(start = 1u, end = 5u),
                SctpGapAckBlock(start = 10u, end = 12u),
            ),
            duplicateTsns = listOf(0x1_FFFEu),
        )
        val encoded = original.encode()
        val decoded = SctpSackChunk.decode(encoded)
        assertEquals(original.cumulativeTsnAck, decoded.cumulativeTsnAck)
        assertEquals(original.aRwnd, decoded.aRwnd)
        assertEquals(2, decoded.gapAckBlocks.size)
        assertEquals(1, decoded.duplicateTsns.size)
    }

    @Test
    fun `SctpCookieEchoChunk encode then decode round-trip`() {
        val cookie = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val original = SctpCookieEchoChunk(cookie)
        val encoded = original.encode()
        val decoded = SctpCookieEchoChunk.decode(encoded)
        assertTrue(decoded.cookie.contentEquals(cookie))
    }

    @Test
    fun `SctpCookieAckChunk encode then decode is no-op`() {
        val encoded = SctpCookieAckChunk.encode()
        assertEquals(4, encoded.size)
        assertEquals(SctpChunkType.COOKIE_ACK.ordinal.toByte(), encoded[0])
        // decode throws on insufficient bytes but does nothing on success
        SctpCookieAckChunk.decode(encoded)
        assertTrue(true, "decode succeeded")
    }

    @Test
    fun `SctpChunkHeader flags default to 0`() {
        val init = SctpInitChunk(0u, 0u, 0u, 0u, 0u)
        assertEquals(0u, init.header.flags)
    }

    @Test
    fun `SctpChunkHeader length matches chunk fixed length`() {
        val init = SctpInitChunk(0u, 0u, 0u, 0u, 0u)
        assertEquals(SctpInitChunk.CHUNK_FIXED_LENGTH, init.header.length)
    }

    @Test
    fun `SctpChunkType enum covers all chunk types`() {
        assertEquals(7, SctpChunkType.entries.size)
        assertNotNull(SctpChunkType.DATA)
        assertNotNull(SctpChunkType.INIT)
        assertNotNull(SctpChunkType.INIT_ACK)
        assertNotNull(SctpChunkType.SACK)
        assertNotNull(SctpChunkType.HEARTBEAT)
        assertNotNull(SctpChunkType.COOKIE_ECHO)
        assertNotNull(SctpChunkType.COOKIE_ACK)
    }

    @Test
    fun `SctpState enum covers full handshake lifecycle`() {
        assertEquals(8, SctpState.entries.size)
        assertTrue(SctpState.entries.contains(SctpState.CLOSED))
        assertTrue(SctpState.entries.contains(SctpState.COOKIE_WAIT))
        assertTrue(SctpState.entries.contains(SctpState.COOKIE_ECHOED))
        assertTrue(SctpState.entries.contains(SctpState.ESTABLISHED))
    }
}
