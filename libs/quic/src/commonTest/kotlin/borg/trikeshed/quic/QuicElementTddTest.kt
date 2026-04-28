package borg.trikeshed.quic

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.StreamTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD spec for QUIC element lifecycle, varint encoding, and packet header algebra.
 *
 * Extends QuicSearchRedTest which covers the basic element/key/config algebra.
 * This file covers:
 * - QuicElement lifecycle and stream management
 * - QuicVarInt encode/decode round-trips
 * - QuicPacketHeader sealed class hierarchy
 * - QUIC version constants
 */
class QuicElementTddTest {

    // ── QuicElement AsyncContextElement ────────────────────────────────────────

    @Test
    fun `QuicElement implements AsyncContextElement`() {
        val elem = QuicElement()
        assertTrue(elem is AsyncContextElement)
    }

    @Test
    fun `QuicElement implements StreamTransport`() {
        val elem = QuicElement()
        assertTrue(elem is StreamTransport)
    }

    @Test
    fun `QuicElement key returns QuicElement.Key singleton`() {
        val elem = QuicElement()
        assertSame(QuicElement.Key, elem.key)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Test
    fun `QuicElement starts CREATED`() {
        val elem = QuicElement()
        assertEquals(ElementState.CREATED, elem.state)
    }

    @Test
    fun `open transitions CREATED to OPEN`() = runTest {
        val elem = QuicElement()
        elem.open()
        assertEquals(ElementState.OPEN, elem.state)
    }

    @Test
    fun `close transitions to CLOSED`() = runTest {
        val elem = QuicElement()
        elem.open()
        elem.close()
        assertEquals(ElementState.CLOSED, elem.state)
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    @Test
    fun `activeStreams is 0 at construction`() {
        val elem = QuicElement()
        assertEquals(0, elem.activeStreams)
    }

    @Test
    fun `openStream increments activeStreams`() = runTest {
        val elem = QuicElement()
        elem.open()
        elem.openStream()
        assertEquals(1, elem.activeStreams)
    }

    @Test
    fun `openStream assigns sequential ids starting at 0`() = runTest {
        val elem = QuicElement()
        elem.open()
        val h0 = elem.openStream()
        val h1 = elem.openStream()
        val h2 = elem.openStream()
        assertEquals(0, h0.id)
        assertEquals(1, h1.id)
        assertEquals(2, h2.id)
    }

    @Test
    fun `openStream requires OPEN state`() = runTest {
        val elem = QuicElement()
        val thrown = runCatching { elem.openStream() }
        assertTrue(thrown.isFailure)
    }

    // ── openQuicElement factory ────────────────────────────────────────────────

    @Test
    fun `openQuicElement creates and opens element`() = runTest {
        val elem = openQuicElement()
        assertEquals(ElementState.OPEN, elem.state)
    }

    @Test
    fun `openQuicElement applies custom config`() = runTest {
        val elem = openQuicElement(QuicConfig(alpn = listOf("h3")))
        assertEquals(1, elem.config.alpn.size)
        assertEquals("h3", elem.config.alpn[0])
    }

    // ── QuicKey alias ─────────────────────────────────────────────────────────

    @Test
    fun `QuicKey is AsyncContextKey<QuicElement>`() {
        assertTrue(QuicKey is borg.trikeshed.context.AsyncContextKey<QuicElement>)
    }

    @Test
    fun `QuicKey resolves QuicElement from context`() = runTest {
        val elem = openQuicElement()
        val ctx = elem as kotlin.coroutines.CoroutineContext
        assertSame(elem, ctx[QuicKey])
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// QuicVarInt encode/decode round-trip
// ══════════════════════════════════════════════════════════════════════════════

class QuicVarIntCodecTest {

    @Test
    fun `encodedLen 1-byte for values 0..63`() {
        for (v in listOf(0uL, 1uL, 32uL, 63uL)) {
            assertEquals(1, QuicVarInt.encodedLen(v), "v=$v")
        }
    }

    @Test
    fun `encodedLen 2-byte for values 64..16383`() {
        for (v in listOf(64uL, 100uL, 8_192uL, 16_383uL)) {
            assertEquals(2, QuicVarInt.encodedLen(v), "v=$v")
        }
    }

    @Test
    fun `encodedLen 4-byte for values 16384..1073741823`() {
        for (v in listOf(16_384uL, 1_000_000uL, 1_073_741_823uL)) {
            assertEquals(4, QuicVarInt.encodedLen(v), "v=$v")
        }
    }

    @Test
    fun `encodedLen 8-byte for values 1073741824..MAX_VALUE`() {
        for (v in listOf(1_073_741_824uL, 1_000_000_000_000uL, QuicVarInt.MAX_VALUE)) {
            assertEquals(8, QuicVarInt.encodedLen(v), "v=$v")
        }
    }

    @Test
    fun `encode then decode round-trip 1-byte`() {
        for (v in listOf(0uL, 1uL, 32uL, 63uL)) {
            val buf = ByteArray(8)
            val n = QuicVarInt.encode(v, buf)
            assertEquals(1, n)
            val (decoded, consumed) = QuicVarInt.decode(buf)
            assertEquals(1, consumed)
            assertEquals(v, decoded)
        }
    }

    @Test
    fun `encode then decode round-trip 2-byte`() {
        for (v in listOf(64uL, 100uL, 8_000uL, 16_383uL)) {
            val buf = ByteArray(8)
            QuicVarInt.encode(v, buf)
            val (decoded, consumed) = QuicVarInt.decode(buf)
            assertEquals(2, consumed)
            assertEquals(v, decoded)
        }
    }

    @Test
    fun `encode then decode round-trip 4-byte`() {
        for (v in listOf(16_384uL, 500_000uL, 1_073_741_823uL)) {
            val buf = ByteArray(8)
            QuicVarInt.encode(v, buf)
            val (decoded, consumed) = QuicVarInt.decode(buf)
            assertEquals(4, consumed)
            assertEquals(v, decoded)
        }
    }

    @Test
    fun `encode then decode round-trip 8-byte`() {
        for (v in listOf(1_073_741_824uL, 1_000_000_000_000uL, QuicVarInt.MAX_VALUE)) {
            val buf = ByteArray(8)
            QuicVarInt.encode(v, buf)
            val (decoded, consumed) = QuicVarInt.decode(buf)
            assertEquals(8, consumed)
            assertEquals(v, decoded)
        }
    }

    @Test
    fun `encode with offset does not clobber following bytes`() {
        val buf = byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
        QuicVarInt.encode(42uL, buf, offset = 2)
        // First two bytes untouched, bytes 2-3 = 42, bytes 4-7 untouched
        assertEquals(0, buf[0])
        assertEquals(0, buf[1])
        assertEquals(42, buf[2].toInt() and 0xFF)
        assertEquals(0, buf[3])
        assertEquals(0xFF.toByte(), buf[4])
        assertEquals(0xFF.toByte(), buf[5])
    }

    @Test
    fun `decode from non-zero offset reads correct bytes`() {
        val buf = byteArrayOf(0, 0, 42, 0, 0, 0, 0, 0)
        val (decoded, consumed) = QuicVarInt.decode(buf, offset = 2)
        assertEquals(1, consumed)
        assertEquals(42uL, decoded)
    }

    @Test
    fun `MAX_VALUE encodes to 8 bytes`() {
        assertEquals(8, QuicVarInt.encodedLen(QuicVarInt.MAX_VALUE))
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// QuicPacketHeader sealed class hierarchy
// ══════════════════════════════════════════════════════════════════════════════

class QuicPacketHeaderTest {

    @Test
    fun `Long Initial dstConnectionId preserved`() {
        val hdr = QuicPacketHeader.Long.Initial(
            token = byteArrayOf(),
            packetNumber = 0u,
            payload = byteArrayOf(),
            version = QuicVersions.VERSION_1,
            dstConnectionId = byteArrayOf(0x01, 0x02, 0x03),
            srcConnectionId = byteArrayOf(0x04, 0x05),
        )
        assertEquals(3, hdr.dstConnectionId.size)
        assertEquals(2, hdr.srcConnectionId.size)
        assertEquals(QuicVersions.VERSION_1, hdr.version)
    }

    @Test
    fun `Long ZeroRtt dstConnectionId preserved`() {
        val hdr = QuicPacketHeader.Long.ZeroRtt(
            packetNumber = 5u,
            payload = byteArrayOf(1, 2, 3),
            version = QuicVersions.VERSION_1,
            dstConnectionId = byteArrayOf(0xAA.toByte()),
            srcConnectionId = byteArrayOf(),
        )
        assertEquals(1, hdr.dstConnectionId.size)
        assertEquals(5uL, hdr.packetNumber)
    }

    @Test
    fun `Long Handshake dstConnectionId preserved`() {
        val hdr = QuicPacketHeader.Long.Handshake(
            packetNumber = 0xFF_FFFFuL,
            payload = byteArrayOf(),
            version = QuicVersions.VERSION_1,
            dstConnectionId = byteArrayOf(),
            srcConnectionId = byteArrayOf(),
        )
        assertEquals(0xFF_FFFFuL, hdr.packetNumber)
    }

    @Test
    fun `Long Retry has retryToken and integrity tag`() {
        val hdr = QuicPacketHeader.Long.Retry(
            retryToken = byteArrayOf(1, 2, 3),
            retryIntegrityTag = byteArrayOf(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            version = QuicVersions.VERSION_1,
            dstConnectionId = byteArrayOf(),
            srcConnectionId = byteArrayOf(),
        )
        assertEquals(3, hdr.retryToken.size)
        assertEquals(12, hdr.retryIntegrityTag.size)
    }

    @Test
    fun `Short header preserves dstConnectionId`() {
        val cid = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val hdr = QuicPacketHeader.Short(
            dstConnectionId = cid,
            packetNumber = 42u,
        )
        assertEquals(4, hdr.dstConnectionId.size)
        assertEquals(42uL, hdr.packetNumber)
        assertEquals(false, hdr.spinBit)
    }

    @Test
    fun `Short spinBit defaults false`() {
        val hdr = QuicPacketHeader.Short(byteArrayOf(1), packetNumber = 0u)
        assertFalse(hdr.spinBit)
    }

    @Test
    fun `QuicLongPacketType codes are distinct`() {
        val codes = QuicLongPacketType.entries.map { it.code }.toSet()
        assertEquals(4, codes.size)
    }

    @Test
    fun `QuicLongPacketType covers all four long header types`() {
        assertEquals(4, QuicLongPacketType.entries.size)
        assertNotEquals(QuicLongPacketType.INITIAL, QuicLongPacketType.ZERO_RTT)
        assertNotEquals(QuicLongPacketType.ZERO_RTT, QuicLongPacketType.HANDSHAKE)
        assertNotEquals(QuicLongPacketType.HANDSHAKE, QuicLongPacketType.RETRY)
    }

    @Test
    fun `QuicVersions VERSION_1 is 0x00000001`() {
        assertEquals(0x0000_0001u, QuicVersions.VERSION_1)
    }

    @Test
    fun `QuicVersions NEGOTIATION is 0x00000000`() {
        assertEquals(0x0000_0000u, QuicVersions.NEGOTIATION)
    }

    @Test
    fun `QuicShortPacketType ONE_RTT and ONE_RTT_NO_SPIN are distinct`() {
        assertNotEquals(QuicShortPacketType.ONE_RTT, QuicShortPacketType.ONE_RTT_NO_SPIN)
    }
}
