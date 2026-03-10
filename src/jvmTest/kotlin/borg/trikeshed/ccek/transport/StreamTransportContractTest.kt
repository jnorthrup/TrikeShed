package borg.trikeshed.ccek.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD contract tests for [StreamTransport.openStream].
 *
 * Current state: ALL tests are RED — [QuicChannelService.openStream] and [NgSctpService.openStream]
 * both throw [NotImplementedError] via TODO(). Tests fail at runtime with NotImplementedError,
 * documenting the desired contract for the green phase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamTransportContractTest {

    // ── QuicChannelService ────────────────────────────────────────────────

    @Test
    fun quicOpenStreamReturnsValidHandle() = runTest {
        val service = QuicChannelService()
        val handle = service.openStream() // NotImplementedError until implemented
        assertTrue(handle.id >= 0, "StreamHandle id must be non-negative")
    }

    @Test
    fun quicActiveStreamsIncrementsAfterOpen() = runTest {
        val service = QuicChannelService()
        val before = service.activeStreams
        service.openStream() // NotImplementedError until implemented
        assertTrue(service.activeStreams > before, "activeStreams must increment after openStream")
    }

    @Test
    fun quicStreamHandleSendChannelIsOpen() = runTest {
        val service = QuicChannelService()
        val handle = service.openStream() // NotImplementedError until implemented
        assertFalse(handle.send.isClosedForSend, "send channel must be open on new stream")
    }

    @Test
    fun quicStreamHandleRecvChannelIsOpen() = runTest {
        val service = QuicChannelService()
        val handle = service.openStream() // NotImplementedError until implemented
        assertFalse(handle.recv.isClosedForReceive, "recv channel must be open on new stream")
    }

    // ── NgSctpService ─────────────────────────────────────────────────────

    @Test
    fun sctpOpenStreamReturnsValidHandle() = runTest {
        val service = NgSctpService()
        val handle = service.openStream() // NotImplementedError until implemented
        assertTrue(handle.id >= 0, "StreamHandle id must be non-negative")
    }

    @Test
    fun sctpActiveStreamsIncrementsAfterOpen() = runTest {
        val service = NgSctpService()
        val before = service.activeStreams
        service.openStream() // NotImplementedError until implemented
        assertTrue(service.activeStreams > before, "activeStreams must increment after openStream")
    }
}
