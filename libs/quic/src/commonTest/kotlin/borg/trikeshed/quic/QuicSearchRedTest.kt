package borg.trikeshed.quic

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.StreamHandle
import borg.trikeshed.context.StreamTransport
import borg.trikeshed.lib.*
import borg.trikeshed.collections.s_
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * QUIC Comprehensive Search & Algebra TDD Tests
 * RED phase: tests define desired algebra before full implementation exists.
 *
 * QUIC algebra (libs/quic commonMain):
 * - QuicConfig — alpn: List<String>, maxIdleTimeoutMs: Long, maxUdpPayloadSize: Int
 * - QuicElement — AsyncContextElement + StreamTransport
 * - StreamHandle(id, send, recv) — per-stream channels
 *
 * SEARCH ALGEBRA:
 * - activeStreams(QuicElement) → Int
 * - streamById(QuicElement, Int) → StreamHandle?
 * - allStreamIds(QuicElement) → Set<Int>
 *
 * STREAM ALGEBRA:
 * - openStream(QuicElement) → StreamHandle
 * - openBidirectionalStream(QuicElement) → StreamHandle
 * - openUnidirectionalStream(QuicElement, role=Send|Recv) → StreamHandle
 * - closeStream(QuicElement, Int) → Result<Unit>
 *
 * CONFIG ALGEBRA:
 * - QuicConfig defaults
 * - withAlpn(QuicConfig, List<String>) → QuicConfig
 * - withTimeout(QuicConfig, Long) → QuicConfig
 */
class QuicSearchRedTest {

    // === QUIC ELEMENT SEARCH ALGEBRA ===

    @Test
    fun `QuicElement implements AsyncContextElement`() {
        val element = QuicElement()
        assertTrue(element is StreamTransport)
        val k = element.key
        assertSame(QuicElement.Key, k)
    }

    @Test
    fun `QuicConfig defaults match expected values`() {
        val config = QuicConfig()

        assertEquals(0, config.alpn.size)
        assertEquals(30000L, config.maxIdleTimeoutMs)
        assertEquals(1350, config.maxUdpPayloadSize)
    }

    @Test
    fun `QuicConfig withAlpn produces new config`() {
        val original = QuicConfig()
        val modified = original.withAlpn(s_["h3", "h2"])

        assertEquals(0, original.alpn.size)
        assertEquals(2, modified.alpn.size)
        assertEquals("h2", modified.alpn[1])
    }

    @Test
    fun `QuicConfig withTimeout produces new config`() {
        val original = QuicConfig()
        val modified = original.withTimeout(60000L)

        assertEquals(30000L, original.maxIdleTimeoutMs)
        assertEquals(60000L, modified.maxIdleTimeoutMs)
    }

    @Test
    fun `QuicConfig properties are structural`() {
        val a = QuicConfig(alpn = s_["h3"], maxIdleTimeoutMs = 30000)
        val b = QuicConfig(alpn = s_["h3"], maxIdleTimeoutMs = 30000)
        val c = QuicConfig(alpn = s_["h2"], maxIdleTimeoutMs = 30000)

        assertEquals(a.alpn[0], b.alpn[0])
        assertEquals(a.maxIdleTimeoutMs, b.maxIdleTimeoutMs)
        assertNotEquals(a.alpn[0], c.alpn[0])
    }

    @Test
    fun `QuicElement activeStreams returns 0 on construction`() {
        val element = QuicElement()
        assertEquals(0, element.activeStreams)
    }

    @Test
    fun `StreamHandle has id send recv`() {
        val send = Channel<ByteArray>(Channel.BUFFERED)
        val recv = Channel<ByteArray>(Channel.BUFFERED)
        val handle = StreamHandle(42, send, recv)

        assertEquals(42, handle.id)
        assertSame(send, handle.send)
        assertSame(recv, handle.recv)
    }

    @Test
    fun `QuicElement key is accessible`() {
        val element = QuicElement()
        assertSame(element.key, QuicElement.Key)
    }

    @Test
    fun `QuicConfig copy preserves other fields`() {
        val original = QuicConfig(alpn = s_["h3"], maxIdleTimeoutMs = 60000)
        val copied = original.copy()

        assertEquals("h3", copied.alpn[0])
        assertEquals(60000L, copied.maxIdleTimeoutMs)
    }

    @Test
    fun `QuicConfig withAlpn does not mutate original`() {
        val original = QuicConfig()
        original.withAlpn(s_["h2"])

        assertEquals(0, original.alpn.size)
    }
}

// === FREE FUNCTIONS ===

fun QuicConfig.withAlpn(alpn: Series<String>): QuicConfig = copy(alpn = alpn)

fun QuicConfig.withTimeout(ms: Long): QuicConfig = copy(maxIdleTimeoutMs = ms)
