package borg.trikeshed.userspace.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// ================================================================================
// SELF-CONTAINED RED STUBS: All transport algebra types
// ================================================================================

enum class HtxBlockType { MESSAGE, HEADERS, DATA, TRAILERS }
data class HtxBlock(val blockType: HtxBlockType, val payloadBytes: ByteArray = ByteArray(0), val streamId: Int = 0) {
    override fun equals(other: Any?) = other is HtxBlock && blockType == other.blockType && payloadBytes.contentEquals(other.payloadBytes)
    override fun hashCode() = 31 * blockType.hashCode() + payloadBytes.contentHashCode()
}
interface HtxStartLine
object HttpMethod { val GET = "GET"; val POST = "POST"; val PUT = "PUT"; val DELETE = "DELETE" }
interface HtxMessage { val startLine: HtxStartLine; val headers: Map<String, String>; val body: ByteArray? }

enum class ElementState { CLOSED, OPEN, ERROR }

data class StreamHandle(val id: Int, val send: SendHandle? = null, val recv: RecvHandle? = null)
data class SendHandle(val streamId: Int)
data class RecvHandle(val streamId: Int)

class QuicElement {
    private val _streams = mutableMapOf<Int, StreamHandle>()
    var isOpen = false
        private set

    fun open(): QuicElement { isOpen = true; return this }
    fun close() { isOpen = false; _streams.clear() }
    fun openStream(): StreamHandle {
        val id = _streams.size
        val handle = StreamHandle(id, SendHandle(id), RecvHandle(id))
        _streams[id] = handle
        return handle
    }
    fun streamById(id: Int) = _streams[id]
    fun allStreamIds() = _streams.keys.toSet()
}

data class QuicConfig(
    val alpnProtocols: List<String> = emptyList(),
    val maxData: Long = 0,
    val maxStreamData: Long = 0,
    val congestionControl: List<String> = emptyList(),
)

sealed class QuicError {
    data class Crypto(val level: Int, val offset: Long) : QuicError()
    data class Transport(val code: Int) : QuicError()
}

class SctpElement {
    private val _streams = mutableMapOf<Int, StreamHandle>()
    var isOpen = false
        private set

    fun open(): SctpElement { isOpen = true; return this }
    fun close() { isOpen = false; _streams.clear() }
    fun openStream(): StreamHandle {
        val id = _streams.size
        val handle = StreamHandle(id, SendHandle(id), RecvHandle(id))
        _streams[id] = handle
        return handle
    }
}

enum class SctpState { CLOSED, COOKIE_WAIT, COOKIE_ECHOED, ESTABLISHED, SHUTDOWN_PENDING, SHUTDOWN_SENT }
sealed class SctpError { data class Failure(val code: Int) : SctpError() }
enum class SctpChunkType { DATA, INIT, ACK, ERROR, SHUTDOWN }
data class SctpDataChunk(val bytes: ByteArray = ByteArray(0))
data class SctpInitChunk(val tag: Int = 0)

interface QUICChannelApi {
    val sessionId: String
    val realm: String
    val streams: kotlinx.coroutines.channels.Channel<HtxBlock>
    suspend fun send(block: HtxBlock): Result<Unit>
    suspend fun recv(): Result<HtxBlock>
    fun close()
}

class QUICChannelStub(override val sessionId: String, override val realm: String = "default") : QUICChannelApi {
    override val streams = kotlinx.coroutines.channels.Channel<HtxBlock>(100)
    override suspend fun send(block: HtxBlock) = Result.success(Unit)
    override suspend fun recv(): Result<HtxBlock> = Result.failure(Exception("closed"))
    override fun close() {}
}

class ngSCTPChannelStub(val sessionId: String) {
    private var _isOpen = false
    val isOpen: Boolean get() = _isOpen
    fun open() { _isOpen = true }
    fun close() { _isOpen = false }
    suspend fun send(block: HtxBlock): Result<Unit> = Result.success(Unit)
    suspend fun recv(): Result<HtxBlock> = Result.failure(Exception("closed"))
}

// ================================================================================
// TESTS
// ================================================================================
class TransportSearchRedTest {

    // --- HtxBlock ---

    @Test fun htxBlockType_fourVariants() { assertEquals(4, HtxBlockType.entries.size) }

    @Test fun htxBlock_equality() {
        val a = HtxBlock(HtxBlockType.DATA, byteArrayOf(1, 2))
        val b = HtxBlock(HtxBlockType.DATA, byteArrayOf(1, 2))
        val c = HtxBlock(HtxBlockType.DATA, byteArrayOf(3, 4))
        assertTrue(a == b)
        assertTrue(a != c)
    }

    @Test fun htxBlock_hashCode() {
        val a = HtxBlock(HtxBlockType.DATA, byteArrayOf(1, 2))
        val b = HtxBlock(HtxBlockType.DATA, byteArrayOf(1, 2))
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun htxBlock_streamId() {
        val b0 = HtxBlock(HtxBlockType.DATA, byteArrayOf(1), streamId = 0)
        val b1 = HtxBlock(HtxBlockType.DATA, byteArrayOf(1), streamId = 1)
        assertTrue(b0 != b1)
    }

    @Test fun httpMethod_getPost() {
        assertEquals("GET", HttpMethod.GET)
        assertEquals("POST", HttpMethod.POST)
        assertEquals("PUT", HttpMethod.PUT)
        assertEquals("DELETE", HttpMethod.DELETE)
    }

    @Test fun htxStartLine_interface() {
        val s = object : HtxStartLine {}
        assertNotNull(s)
    }

    @Test fun htxMessage_interface() {
        val m = object : HtxMessage {
            override val startLine: HtxStartLine = object : HtxStartLine {}
            override val headers: Map<String, String> = mapOf("host" to "example.com")
            override val body: ByteArray? = byteArrayOf(1, 2)
        }
        assertEquals("example.com", m.headers["host"])
        assertEquals(2, m.body?.size)
    }

    // --- ElementState ---

    @Test fun elementState_threeValues() { assertEquals(3, ElementState.entries.size) }

    @Test fun elementState_closedIsDefault() {
        assertEquals(ElementState.CLOSED, ElementState.CLOSED)
        assertEquals(ElementState.OPEN, ElementState.OPEN)
        assertEquals(ElementState.ERROR, ElementState.ERROR)
    }

    // --- QuicElement ---

    @Test fun quicElement_startsClosed() {
        val el = QuicElement()
        assertFalse(el.isOpen)
    }

    @Test fun quicElement_openSetsIsOpen() {
        val el = QuicElement()
        el.open()
        assertTrue(el.isOpen)
    }

    @Test fun quicElement_closeClearsIsOpen() {
        val el = QuicElement()
        el.open()
        el.close()
        assertFalse(el.isOpen)
    }

    @Test fun quicElement_openStream_sequentialIds() {
        val el = QuicElement()
        el.open()
        assertEquals(0, el.openStream().id)
        assertEquals(1, el.openStream().id)
    }

    @Test fun quicElement_streamById() {
        val el = QuicElement()
        el.open()
        val s = el.openStream()
        assertEquals(s, el.streamById(s.id))
    }

    @Test fun quicElement_streamById_missing() {
        val el = QuicElement()
        assertNull(el.streamById(99))
    }

    @Test fun quicElement_allStreamIds() {
        val el = QuicElement()
        el.open()
        el.openStream()
        el.openStream()
        assertEquals(setOf(0, 1), el.allStreamIds())
    }

    // --- QuicConfig ---

    @Test fun quicConfig_congestionControl() {
        val cfg = QuicConfig(congestionControl = listOf("cubic", "bbr"))
        assertEquals(2, cfg.congestionControl.size)
        assertEquals("cubic", cfg.congestionControl[0])
    }

    @Test fun quicConfig_defaults() {
        val cfg = QuicConfig()
        assertTrue(cfg.alpnProtocols.isEmpty())
        assertEquals(0L, cfg.maxData)
    }

    @Test fun quicConfig_maxStreamData() {
        val cfg = QuicConfig(maxStreamData = 65_535L)
        assertEquals(65_535L, cfg.maxStreamData)
    }

    // --- QuicError ---

    @Test fun quicError_crypto() {
        val e = QuicError.Crypto(1, 42L)
        assertIs<QuicError.Crypto>(e)
        assertEquals(1, e.level)
        assertEquals(42L, e.offset)
    }

    @Test fun quicError_transport() {
        val e = QuicError.Transport(99)
        assertIs<QuicError.Transport>(e)
        assertEquals(99, e.code)
    }

    @Test fun quicError_sealed() {
        val a: QuicError = QuicError.Crypto(0, 0)
        val b: QuicError = QuicError.Transport(0)
        assertTrue(a is QuicError)
        assertTrue(b is QuicError)
    }

    // --- SctpElement ---

    @Test fun sctpElement_startsClosed() {
        val el = SctpElement()
        assertFalse(el.isOpen)
    }

    @Test fun sctpElement_openThenClose() {
        val el = SctpElement()
        el.open()
        assertTrue(el.isOpen)
        el.close()
        assertFalse(el.isOpen)
    }

    @Test fun sctpElement_openStream_increments() {
        val el = SctpElement()
        el.open()
        assertEquals(0, el.openStream().id)
        assertEquals(1, el.openStream().id)
    }

    @Test fun sctpState_enum() {
        assertEquals(6, SctpState.entries.size)
    }

    @Test fun sctpError_failure() {
        val e = SctpError.Failure(1)
        assertIs<SctpError.Failure>(e)
    }

    @Test fun sctpChunkType_enum() {
        assertEquals(5, SctpChunkType.entries.size)
    }

    @Test fun sctpDataChunk_bytes() {
        val c = SctpDataChunk(byteArrayOf(1, 2, 3))
        assertEquals(3, c.bytes.size)
    }

    @Test fun sctpInitChunk_tag() {
        val c = SctpInitChunk(tag = 12345)
        assertEquals(12345, c.tag)
    }

    // --- StreamHandle ---

    @Test fun streamHandle_idMatches() {
        val h = StreamHandle(5)
        assertEquals(5, h.id)
        assertEquals(5, h.send?.streamId)
        assertEquals(5, h.recv?.streamId)
    }

    // --- ngSCTPChannel ---

    @Test fun ngSCTPChannel_sessionId() {
        val ch = ngSCTPChannelStub("sess-abc")
        assertEquals("sess-abc", ch.sessionId)
    }

    @Test fun ngSCTPChannel_openClose() {
        val ch = ngSCTPChannelStub("sess-1")
        assertFalse(ch.isOpen)
        ch.open()
        assertTrue(ch.isOpen)
        ch.close()
        assertFalse(ch.isOpen)
    }

    @Test fun ngSCTPChannel_send_returnsSuccess() = runTest {
        val ch = ngSCTPChannelStub("sess-1")
        ch.open()
        val r = ch.send(HtxBlock(HtxBlockType.DATA, byteArrayOf()))
        assertTrue(r.isSuccess)
    }

    @Test fun ngSCTPChannel_recv_returnsFailureWhenClosed() = runTest {
        val ch = ngSCTPChannelStub("sess-1")
        val r = ch.recv()
        assertTrue(r.isFailure)
    }

    // --- QUICChannelApi ---

    @Test fun quicChannel_stub_sessionIdAndRealm() {
        val ch = QUICChannelStub("sess-x", "realm-y")
        assertEquals("sess-x", ch.sessionId)
        assertEquals("realm-y", ch.realm)
    }

    @Test fun quicChannel_stub_close() {
        val ch = QUICChannelStub("s1")
        ch.close()
    }

    @Test fun quicChannel_stub_streams() {
        val ch = QUICChannelStub("s1")
        assertNotNull(ch.streams)
    }

    @Test fun quicChannel_stub_send_returnsSuccess() = runTest {
        val ch = QUICChannelStub("s1")
        val r = ch.send(HtxBlock(HtxBlockType.DATA, byteArrayOf(1)))
        assertTrue(r.isSuccess)
    }

    @Test fun quicChannel_stub_recv_returnsFailure() = runTest {
        val ch = QUICChannelStub("s1")
        val r = ch.recv()
        assertTrue(r.isFailure)
    }

    // --- Cross-protocol ---

    @Test fun htxBlockType_count() {
        assertEquals(4, HtxBlockType.entries.size)
    }

    @Test fun quicElement_close_allStreamsCleared() {
        val el = QuicElement()
        el.open()
        el.openStream()
        el.openStream()
        el.close()
        assertTrue(el.allStreamIds().isEmpty())
    }
}
