package borg.trikeshed.userspace.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Tests for QUIC channel algebra — uses stubs from TransportSearchRedTest. */
class QUICChannelTest {

    @Test fun quicChannel_stub_sessionId() {
        val ch = QUICChannelStub("abc123", "test-realm")
        assertEquals("abc123", ch.sessionId)
    }

    @Test fun quicChannel_stub_realm() {
        val ch = QUICChannelStub("s1", "realm-x")
        assertEquals("realm-x", ch.realm)
    }

    @Test fun quicChannel_stub_realm_distinguishes_sessions() {
        val ch1 = QUICChannelStub("s1", "alpha")
        val ch2 = QUICChannelStub("s1", "beta")
        assertTrue(ch1.realm != ch2.realm)
    }

    @Test fun quicChannel_stub_streams_channel() {
        val ch = QUICChannelStub("s1")
        assertNotNull(ch.streams)
    }

    @Test fun quicChannel_stub_close_idempotent() {
        val ch = QUICChannelStub("s1")
        ch.close()
        ch.close()
    }

    @Test fun quicChannel_stub_send_returnsSuccess() = runTest {
        val ch = QUICChannelStub("s1")
        val block = HtxBlock(HtxBlockType.DATA, byteArrayOf(0xFE.toByte()), streamId = 5)
        val r = ch.send(block)
        assertTrue(r.isSuccess)
    }

    @Test fun quicChannel_stub_recv_returnsFailure() = runTest {
        val ch = QUICChannelStub("s1")
        val r = ch.recv()
        assertTrue(r.isFailure)
    }

    @Test fun quicChannel_stub_sendHtxBlock_message() = runTest {
        val ch = QUICChannelStub("s1")
        val block = HtxBlock(HtxBlockType.MESSAGE, byteArrayOf(1, 2, 3))
        assertTrue(ch.send(block).isSuccess)
    }

    @Test fun quicChannel_stub_sendHtxBlock_headers() = runTest {
        val ch = QUICChannelStub("s1")
        val block = HtxBlock(HtxBlockType.HEADERS, byteArrayOf())
        assertTrue(ch.send(block).isSuccess)
    }

    @Test fun quicChannel_stub_sendHtxBlock_trailers() = runTest {
        val ch = QUICChannelStub("s1")
        val block = HtxBlock(HtxBlockType.TRAILERS, byteArrayOf())
        assertTrue(ch.send(block).isSuccess)
    }

    @Test fun quicChannel_stub_quicError_transport() {
        val e = QuicError.Transport(42)
        assertIs<QuicError.Transport>(e)
        assertEquals(42, e.code)
    }

    @Test fun quicChannel_stub_quicError_crypto() {
        val e = QuicError.Crypto(2, 1000L)
        assertIs<QuicError.Crypto>(e)
        assertEquals(2, e.level)
        assertEquals(1000L, e.offset)
    }

    @Test fun quicChannel_stub_quicConfig_congestionControl() {
        val cfg = QuicConfig(congestionControl = listOf("bbr"))
        assertEquals("bbr", cfg.congestionControl[0])
    }
}
