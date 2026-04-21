package borg.trikeshed.ccek.transport

import borg.trikeshed.quic.QuicElement
import borg.trikeshed.sctp.SctpElement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD contract tests for [StreamTransport.openStream].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamTransportContractTest {

    // ── QuicElement ────────────────────────────────────────────────

    @Test
    fun quicOpenStreamReturnsValidHandle() = runTest {
        val service = QuicElement()
        service.open()
        val handle = service.openStream()
        assertTrue(handle.id >= 0, "StreamHandle id must be non-negative")
    }

    @Test
    fun quicActiveStreamsIncrementsAfterOpen() = runTest {
        val service = QuicElement()
        service.open()
        val before = service.activeStreams
        service.openStream()
        assertTrue(service.activeStreams > before, "activeStreams must increment after openStream")
    }

    @Test
    fun quicStreamHandleSendChannelIsOpen() = runTest {
        val service = QuicElement()
        service.open()
        val handle = service.openStream()
        assertFalse(handle.send.isClosedForSend, "send channel must be open on new stream")
    }

    @Test
    fun quicStreamHandleRecvChannelIsOpen() = runTest {
        val service = QuicElement()
        service.open()
        val handle = service.openStream()
        assertFalse(handle.recv.isClosedForReceive, "recv channel must be open on new stream")
    }

    // ── SctpElement ─────────────────────────────────────────────────────

    @Test
    fun sctpOpenStreamReturnsValidHandle() = runTest {
        val service = SctpElement()
        service.open()
        val handle = service.openStream()
        assertTrue(handle.id >= 0, "StreamHandle id must be non-negative")
    }

    @Test
    fun sctpActiveStreamsIncrementsAfterOpen() = runTest {
        val service = SctpElement()
        service.open()
        val before = service.activeStreams
        service.openStream()
        assertTrue(service.activeStreams > before, "activeStreams must increment after openStream")
    }
}
