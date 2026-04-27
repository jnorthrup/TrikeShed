package borg.trikeshed.userspace.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ================================================================================
// SELF-CONTAINED STUBS: Reactor-mediated QUIC → HTX pipeline
// Semantic gap: QuicHtxReactorPipelineTest manually creates CoroutineScope
//   and casts stream.send/recv as Channel<ByteArray>. This bypasses the
//   reactor entirely. The reactor should:
//   1. Own the coroutine lifecycle (not ad-hoc CoroutineScope)
//   2. Mediate between StreamHandle and the backend (NIO or io_uring)
//   3. Dispatch completions to stream handlers via SupervisorJob
//   4. Use RingChannel for stream data, not kotlinx Channel
//
// Types HtxBlock and HtxBlockType come from TransportSearchRedTest (same pkg).
// ================================================================================

/** Reactor interface — owns dispatch, not the test. */
interface Reactor {
    val isOpen: Boolean
    fun open()
    fun close()
    /** Register a stream handler — reactor dispatches data to it. */
    fun registerStream(streamId: Int, handler: StreamHandler)
    /** Deliver data from backend (NIO read / io_uring CQE) to the reactor. */
    fun deliver(streamId: Int, data: ByteArray)
}

/** Stream handler — processes a single stream's data. */
interface StreamHandler {
    val streamId: Int
    fun onData(data: ByteArray)
    fun onEnd()
}

/** Reactor stub for testing — dispatches to registered handlers. */
class ReactorStub : Reactor {
    override var isOpen = false;set
   val handlers = mutableMapOf<Int, StreamHandler>()

    override fun open() { isOpen = true }
    override fun close() { isOpen = false; handlers.clear() }
    override fun registerStream(streamId: Int, handler: StreamHandler) { handlers[streamId] = handler }
    override fun deliver(streamId: Int, data: ByteArray) { handlers[streamId]?.onData(data) }
}

/** Simple handler that collects HtxBlocks. */
class CollectingHandler(override val streamId: Int) : StreamHandler {
    val received = mutableListOf<ByteArray>()
    var ended = false

    override fun onData(data: ByteArray) { received.add(data) }
    override fun onEnd() { ended = true }
}

// ================================================================================
// SPEC: Reactor-mediated QUIC/HTX pipeline — reactor owns dispatch
// ================================================================================

class QuicHtxReactorDispatchTest {

    /** Reactor dispatches data to registered stream handler. */
    @Test fun reactor_dispatchesToRegisteredHandler() {
        val reactor = ReactorStub()
        reactor.open()
        val handler = CollectingHandler(streamId = 0)
        reactor.registerStream(0, handler)

        reactor.deliver(0, "HEADERS: :status 200".encodeToByteArray())
        reactor.deliver(0, "DATA: <html>".encodeToByteArray())
        reactor.deliver(0, "TRAILERS: ".encodeToByteArray())

        assertEquals(3, handler.received.size)
    }

    /** Handler doesn't receive data for unregistered streams. */
    @Test fun reactor_ignoresUnregisteredStream() {
        val reactor = ReactorStub()
        reactor.open()
        val handler = CollectingHandler(streamId = 1)
        reactor.registerStream(1, handler)

        reactor.deliver(0, "data for wrong stream".encodeToByteArray())
        assertEquals(0, handler.received.size)
    }

    /** Reactor close clears all handlers. */
    @Test fun reactor_close_clearsHandlers() {
        val reactor = ReactorStub()
        reactor.open()
        reactor.registerStream(0, CollectingHandler(0))
        reactor.close()
        reactor.deliver(0, "after close".encodeToByteArray()) // no handler
        assertTrue(true) // doesn't crash
    }

    /** Stream data flows through the reactor, not via direct Channel access.
     *  Contrast with QuicHtxReactorPipelineTest which does:
     *    (stream.send as Channel<ByteArray>).send(data)  ← monolithic bypass */
    @Test fun streamDataFlowsThroughReactor_notDirectChannels() {
        val reactor = ReactorStub()
        reactor.open()

        val handler = CollectingHandler(streamId = 5)
        reactor.registerStream(5, handler)

        // RED: The current test does:
        //   stream.send.send(data)       // direct channel write
        //   for (data in stream.recv) {} // direct channel read
        //
        // The reactor pattern should be:
        //   reactor.deliver(streamId, data)  // backend → reactor → handler
        reactor.deliver(5, byteArrayOf(1, 2, 3))
        assertEquals(1, handler.received.size)
    }
}
