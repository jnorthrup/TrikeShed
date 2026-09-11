package borg.trikeshed.ipns

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.reactor.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** TLS codec only transforms bytes; every ciphertext byte travels through the owned uring stream. */
internal class Libp2pTlsStream private constructor(
    private val wire: Libp2pStream,
    private val element: TlsElement,
    private val endpoint: TlsEndpoint,
) : Libp2pStream {
    private var pending = byteArrayOf()
    private var closed = false

    private suspend fun frames(frames: TlsFrames) {
        for (i in 0 until frames.size) {
            val frame = frames[i]
            val bytes = frame.payload.toArray()
            when (frame.stage) {
                TlsFlowStage.UPSTREAM_CIPHERTEXT, TlsFlowStage.CLOSE_NOTIFY -> if (bytes.isNotEmpty()) wire.write(bytes)
                TlsFlowStage.DOWNSTREAM_PLAINTEXT -> {
                    require(pending.size + bytes.size <= 1048576) { "TLS plaintext buffer exceeds bound" }
                    pending += bytes
                }
                else -> Unit
            }
        }
    }

    override suspend fun read(maxBytes: Int): ByteArray {
        require(maxBytes in 1..1048576)
        check(!closed)
        while (pending.isEmpty()) {
            if (endpoint.flowState.lifecycle == TlsConnectionState.CLOSED) return byteArrayOf()
            val bytes = wire.read(65536)
            check(bytes.isNotEmpty()) { "TLS transport EOF without close_notify" }
            frames(endpoint.downstream(ByteSeries(bytes)))
        }
        val count = minOf(maxBytes, pending.size)
        val result = pending.copyOfRange(0, count)
        pending = pending.copyOfRange(count, pending.size)
        return result
    }

    override suspend fun write(bytes: ByteArray) {
        check(!closed)
        require(bytes.size <= 1048576)
        frames(endpoint.upstream(ByteSeries(bytes)))
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        // The peer may already have closed after yamux FIN/GOAWAY. Notification is advisory;
        // its real failed WRITE remains observable, while owned descriptor cleanup still settles.
        runCatching { withTimeoutOrNull(1000) { frames(endpoint.close()) } }
        try { element.close() } finally { wire.close() }
    }

    companion object {
        suspend fun open(wire: Libp2pStream, backend: TlsCodecBackend, host: String, port: Int): Libp2pTlsStream {
            val element = openTlsElement(config = TlsConfig(hostnameVerification = false), backend = backend)
            val endpoint = element.clientEndpoint(host, port)
            val stream = Libp2pTlsStream(wire, element, endpoint)
            try {
                stream.frames(endpoint.handshake())
                while (!endpoint.isHandshakeComplete) {
                    val bytes = wire.read(65536)
                    check(bytes.isNotEmpty()) { "Peer closed during TLS authentication" }
                    stream.frames(endpoint.downstream(ByteSeries(bytes)))
                }
                return stream
            } catch (failure: Throwable) {
                withContext(NonCancellable) { runCatching { stream.close() }.exceptionOrNull()?.let(failure::addSuppressed) }
                throw failure
            }
        }
    }
}
