package borg.trikeshed.reactor

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException

/**
 * The driving thread's record windows, shared by every session it drives: inbound joins a held partial record to
 * new ciphertext, app receives what unwrap decrypts, out receives what wrap encrypts. Each grows when the engine
 * reports overflow. [driving] marks a session call in progress on this thread: the windows hold bytes a sink is
 * reading, so a sink that drives a session again (this one or another) on the same thread is refused.
 */
private class TlsScratch {
    var inbound: ByteBuffer = ByteBuffer.allocate(17 * 1024)
    var app: ByteBuffer = ByteBuffer.allocate(17 * 1024)
    var out: ByteBuffer = ByteBuffer.allocate(18 * 1024)
    val empty: ByteBuffer = ByteBuffer.allocate(0)
    var driving = false
}

private val tlsScratch = ThreadLocal.withInitial(::TlsScratch)

/**
 * [TlsRecordSession] over one SSLEngine. Delegated handshake tasks run inline on the driving thread.
 *
 * Sends never spin. When a wrap takes no plaintext and produces nothing (the engine waits for the peer's handshake
 * data), [send] keeps the rest of its plaintext, in order, as [pendingPlaintext] and returns; each later [receive]
 * and [send] first encrypts what the engine then takes. At most [pendingLimit] bytes are held: a send that would
 * take [pendingPlaintext] past it is refused whole ([TlsSendOutcome.BACKPRESSURE]) and its bytes stay the caller's,
 * so no single send may be longer than [pendingLimit]. [close] sends close_notify after the held plaintext: while
 * some remains [closed] stays false and sends are refused as [TlsSendOutcome.CLOSED]. Sessions are not re-entrant:
 * a sink must not drive a session (this one or another) on its own thread, as the shared record windows hold the
 * bytes it is being given; such a call throws IllegalStateException.
 */
class JvmTlsRecordSession(
    val engine: SSLEngine,
    override val pendingLimit: Int = DEFAULT_PENDING_LIMIT,
) : TlsRecordSession {
    init {
        require(pendingLimit > 0) { "pendingLimit $pendingLimit" }
    }

    private var partial: ByteArray? = null

    /** Held plaintext: [pendingFrom, pendingTo) of [pending], an array at most [pendingLimit] long. */
    private var pending: ByteArray? = null
    private var pendingFrom = 0
    private var pendingTo = 0
    private var closeAfterPending = false
    private var started = false
    private val sessions = TlsSessionCache()

    override var established: Boolean = false
        private set
    override var closed: Boolean = false
        private set

    /** Why the session failed, when it did. */
    var failure: SSLException? = null
        private set

    override val session: TlsSession? get() = if (established) sessions.of(engine) else null
    override val pendingCiphertext: Int get() = partial?.size ?: 0
    override val pendingPlaintext: Int get() = pendingTo - pendingFrom

    /** One session call on this thread at a time; the windows are borrowed for its duration. */
    private inline fun <T> driving(block: () -> T): T {
        val scratch = tlsScratch.get()
        check(!scratch.driving) { "a TlsRecordSession was driven from inside a sink callback on the same thread" }
        scratch.driving = true
        try {
            return block()
        } finally {
            scratch.driving = false
        }
    }

    override fun begin(sink: TlsRecordSink) = driving { start(sink) }

    private fun start(sink: TlsRecordSink) {
        if (started || closed) return
        started = true
        try {
            engine.beginHandshake()
            advance(sink)
        } catch (error: SSLException) {
            fail(error, sink)
        }
    }

    override fun receive(bytes: ByteArray, from: Int, to: Int, sink: TlsRecordSink): Boolean = driving {
        if (!started) start(sink)
        if (closed) return@driving false
        val scratch = tlsScratch.get()
        val held = partial
        val source = if (held == null) ByteBuffer.wrap(bytes, from, to - from) else {
            val size = held.size + (to - from)
            if (scratch.inbound.capacity() < size) scratch.inbound = ByteBuffer.allocate(size)
            scratch.inbound.clear()
            scratch.inbound.put(held).put(bytes, from, to - from).flip()
            scratch.inbound
        }
        partial = null
        try {
            while (true) {
                advance(sink)
                if (!source.hasRemaining()) break
                val app = scratch.app
                app.clear()
                val result = engine.unwrap(source, app)
                if (app.position() > 0) sink.plaintext(app.array(), 0, app.position())
                if (result.handshakeStatus == HandshakeStatus.FINISHED) established = true
                when (result.status) {
                    Status.BUFFER_UNDERFLOW -> break
                    Status.BUFFER_OVERFLOW ->
                        scratch.app = ByteBuffer.allocate(maxOf(app.capacity() * 2, engine.session.applicationBufferSize))
                    Status.CLOSED -> {
                        // The peer's close_notify: answer with ours; the session is done, and what waited is dropped.
                        dropPending()
                        engine.closeOutbound()
                        advance(sink)
                        closed = true
                        return@driving false
                    }
                    else -> if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) break
                }
            }
            if (source.hasRemaining()) partial = ByteArray(source.remaining()).also { source.get(it) }
            if (!established && engine.handshakeStatus == HandshakeStatus.NOT_HANDSHAKING) established = true
            // The engine may take plaintext again: what send kept goes now, and a close that waited for it follows.
            sendPending(sink)
            !closed
        } catch (error: SSLException) {
            fail(error, sink)
            false
        }
    }

    override fun send(bytes: ByteArray, from: Int, to: Int, sink: TlsRecordSink): TlsSendOutcome = driving {
        if (closed || closeAfterPending) return@driving TlsSendOutcome.CLOSED
        if (!established) return@driving TlsSendOutcome.NOT_ESTABLISHED
        try {
            sendPending(sink)
            if (closed) return@driving TlsSendOutcome.CLOSED
            // Decided before any of it is consumed: all of it may have to be held.
            if (to - from > pendingLimit - pendingPlaintext) return@driving TlsSendOutcome.BACKPRESSURE
            if (pendingPlaintext > 0) {
                keep(bytes, from, to) // behind what is already waiting
                return@driving TlsSendOutcome.ACCEPTED
            }
            val source = ByteBuffer.wrap(bytes, from, to - from)
            pump(source, sink)
            if (closed) return@driving TlsSendOutcome.CLOSED
            if (source.hasRemaining()) keep(bytes, source.position(), source.limit())
            TlsSendOutcome.ACCEPTED
        } catch (error: SSLException) {
            fail(error, sink)
            TlsSendOutcome.CLOSED
        }
    }

    override fun close(sink: TlsRecordSink) = driving {
        if (closed) return@driving
        try {
            sendPending(sink)
        } catch (error: SSLException) {
            fail(error, sink)
            return@driving
        }
        if (closed) return@driving
        if (pendingPlaintext > 0) closeAfterPending = true else finishClose(sink)
    }

    /**
     * Wraps [source] while the engine makes progress: it consumes plaintext, or produces handshake bytes (a finite
     * flight) and is asked again. It stops, leaving the rest in [source], when a wrap takes nothing and produces
     * nothing: the engine waits for the peer's handshake data. So this cannot spin.
     */
    private fun pump(source: ByteBuffer, sink: TlsRecordSink) {
        while (source.hasRemaining()) {
            val result = wrap(source, sink)
            if (result.status == Status.CLOSED) {
                closed = true
                return
            }
            advance(sink)
            if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) return
        }
    }

    /** Encrypts what [send] kept, as far as the engine takes it; then a close that waited for it. */
    private fun sendPending(sink: TlsRecordSink) {
        val held = pending
        if (held != null) {
            val source = ByteBuffer.wrap(held, pendingFrom, pendingTo - pendingFrom)
            pump(source, sink)
            if (closed) return
            pendingFrom = source.position()
            if (source.hasRemaining()) return
            dropPending()
        }
        if (closeAfterPending) finishClose(sink)
    }

    /**
     * Appends [from, to) of [bytes] behind the held plaintext; [send] checked it fits within [pendingLimit]. The
     * array doubles up to [pendingLimit] and is compacted in place when its tail is full.
     */
    private fun keep(bytes: ByteArray, from: Int, to: Int) {
        val n = to - from
        val waiting = pendingTo - pendingFrom
        var held = pending
        if (held == null || pendingTo + n > held.size) {
            val next = if (held != null && waiting + n <= held.size) held
                else ByteArray(minOf(pendingLimit, maxOf(waiting + n, 2 * (held?.size ?: 0), 4096)))
            if (held != null && waiting > 0) held.copyInto(next, 0, pendingFrom, pendingTo)
            held = next
            pending = next
            pendingFrom = 0
            pendingTo = waiting
        }
        bytes.copyInto(held, pendingTo, from, to)
        pendingTo += n
    }

    private fun dropPending() {
        pending = null
        pendingFrom = 0
        pendingTo = 0
    }

    private fun finishClose(sink: TlsRecordSink) {
        closed = true
        closeAfterPending = false
        partial = null
        engine.closeOutbound()
        try {
            flushOutbound(sink)
        } catch (_: SSLException) {
        }
    }

    /** Runs delegated tasks and wraps what the handshake owes the peer. */
    private fun advance(sink: TlsRecordSink) {
        while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_TASK -> while (true) (engine.delegatedTask ?: break).run()
                HandshakeStatus.NEED_WRAP -> {
                    val result = wrap(tlsScratch.get().empty, sink)
                    if (result.status == Status.CLOSED && result.bytesProduced() == 0) return
                    if (result.bytesProduced() == 0 && engine.handshakeStatus == HandshakeStatus.NEED_WRAP) return
                }
                else -> return
            }
        }
    }

    private fun wrap(source: ByteBuffer, sink: TlsRecordSink): SSLEngineResult {
        val scratch = tlsScratch.get()
        while (true) {
            val out = scratch.out
            out.clear()
            val result = engine.wrap(source, out)
            if (result.status == Status.BUFFER_OVERFLOW) {
                scratch.out = ByteBuffer.allocate(maxOf(out.capacity() * 2, engine.session.packetBufferSize))
                continue
            }
            if (out.position() > 0) sink.ciphertext(out.array(), 0, out.position())
            if (result.handshakeStatus == HandshakeStatus.FINISHED) established = true
            return result
        }
    }

    private fun flushOutbound(sink: TlsRecordSink) {
        while (!engine.isOutboundDone) if (wrap(tlsScratch.get().empty, sink).bytesProduced() == 0) break
    }

    /** The engine queues the alert describing [error]; wrapping sends it before the session ends. */
    private fun fail(error: SSLException, sink: TlsRecordSink) {
        failure = error
        closed = true
        partial = null
        dropPending()
        closeAfterPending = false
        try {
            engine.closeOutbound()
            flushOutbound(sink)
        } catch (_: SSLException) {
        }
    }

    companion object {
        /** Held plaintext per session by default: several responses' worth, allocated only while the engine waits. */
        const val DEFAULT_PENDING_LIMIT: Int = 64 * 1024
    }
}
