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
 * reports overflow.
 */
private class TlsScratch {
    var inbound: ByteBuffer = ByteBuffer.allocate(17 * 1024)
    var app: ByteBuffer = ByteBuffer.allocate(17 * 1024)
    var out: ByteBuffer = ByteBuffer.allocate(18 * 1024)
    val empty: ByteBuffer = ByteBuffer.allocate(0)
}

private val tlsScratch = ThreadLocal.withInitial(::TlsScratch)

/** [TlsRecordSession] over one SSLEngine. Delegated handshake tasks run inline on the driving thread. */
class JvmTlsRecordSession(val engine: SSLEngine) : TlsRecordSession {
    private var partial: ByteArray? = null
    private var started = false

    override var established: Boolean = false
        private set
    override var closed: Boolean = false
        private set

    /** Why the session failed, when it did. */
    var failure: SSLException? = null
        private set

    override val session: TlsSession? get() = if (established) sessionOf(engine) else null
    override val pendingCiphertext: Int get() = partial?.size ?: 0

    override fun begin(sink: TlsRecordSink) {
        if (started || closed) return
        started = true
        try {
            engine.beginHandshake()
            advance(sink)
        } catch (error: SSLException) {
            fail(error, sink)
        }
    }

    override fun receive(bytes: ByteArray, from: Int, to: Int, sink: TlsRecordSink): Boolean {
        if (!started) begin(sink)
        if (closed) return false
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
                        // The peer's close_notify: answer with ours; the session is done.
                        engine.closeOutbound()
                        advance(sink)
                        closed = true
                        return false
                    }
                    else -> if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) break
                }
            }
            if (source.hasRemaining()) partial = ByteArray(source.remaining()).also { source.get(it) }
            if (!established && engine.handshakeStatus == HandshakeStatus.NOT_HANDSHAKING) established = true
            return true
        } catch (error: SSLException) {
            fail(error, sink)
            return false
        }
    }

    override fun send(bytes: ByteArray, from: Int, to: Int, sink: TlsRecordSink): Boolean {
        if (closed || !established) return false
        val source = ByteBuffer.wrap(bytes, from, to - from)
        try {
            while (source.hasRemaining()) {
                if (wrap(source, sink).status == Status.CLOSED) {
                    closed = true
                    return false
                }
                advance(sink)
            }
            return true
        } catch (error: SSLException) {
            fail(error, sink)
            return false
        }
    }

    override fun close(sink: TlsRecordSink) {
        if (closed) return
        closed = true
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
        try {
            engine.closeOutbound()
            flushOutbound(sink)
        } catch (_: SSLException) {
        }
    }
}
