package borg.trikeshed.litebike

import borg.trikeshed.litebike.taxonomy.Protocol
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.channels.*
import kotlinx.coroutines.*

/**
 * The litebike listener's bind adapter on POSIX targets, over the userspace facade's [ServerSocketChannel]: the
 * listening socket as tokio's TcpListener::bind makes it (SO_REUSEADDR, backlog 1024), then one coroutine per accepted
 * connection under the listener's supervisor. A connection frames one HTTP/1.1 request on Content-Length as
 * [httpFrameComplete] does, hands it to [LitebikeListenerElement.accept] and closes once the worker's answer is
 * written, the JVM adapter's Connection: close exchange. Every socket is non-blocking; an idle wait backs off from 1 to
 * 10 ms, and cancellation closes the listening socket.
 */
object PosixLitebikeBindAdapter {
    /** Bind [host] (an IPv4 or IPv6 literal) and [port], and serve [element] until cancelled. */
    suspend fun bindAndServe(element: LitebikeListenerElement, port: Int, host: String = "0.0.0.0") {
        val ipv6 = ':' in host
        val server = ServerSocketChannel.open(if (ipv6) "INET6" else "INET")
        try {
            server.setOption("SO_REUSEADDR", true)
            server.bind(if (ipv6) "[$host]:$port" else "$host:$port", 1024)
            server.configureBlocking(false)
            var idle = 0
            while (true) {
                val connection = server.accept()
                if (connection == null) {
                    delay(if (idle++ < 10) 1L else 10L)
                    continue
                }
                idle = 0
                // Started even when the listener has stopped, so the connection is closed either way.
                CoroutineScope(element.supervisor).launch(start = CoroutineStart.UNDISPATCHED) { serve(connection, element) }
            }
        } finally {
            server.close()
        }
    }

    /** One request off [connection] to [element], its answer back, then close; a peer that leaves ends it quietly. */
    suspend fun serve(connection: SocketChannel, element: LitebikeListenerElement) {
        try {
            connection.configureBlocking(false)
            val chunk = ByteBuffer(8192)
            // Reassembly buffer: grows by doubling, so a large body costs O(n), not O(n²).
            var pending = ByteArray(65536)
            var size = 0
            var idle = 0
            while (true) {
                chunk.clear()
                val read = connection.read(chunk)
                if (read < 0) return
                if (read == 0) {
                    delay(if (idle++ < 10) 1L else 10L)
                    continue
                }
                idle = 0
                if (size + read > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, size + read))
                chunk.array().copyInto(pending, size, 0, read)
                size += read
                val protocol = ProtocolDetector.detect(pending.copyOf(minOf(size, 8)), size)
                if (protocol == Protocol.Http && !httpFrameComplete(pending, size)) continue
                val answered = CompletableDeferred<Unit>()
                val admitted = element.accept(protocol, pending.copyOf(size)) { answer ->
                    try {
                        write(connection, answer)
                    } finally {
                        answered.complete(Unit)
                    }
                }
                if (admitted) answered.await()
                return
            }
        } catch (gone: IOException) {
            // Reset by the peer: the exchange is over.
        } finally {
            connection.close()
        }
    }

    /** Every byte of [answer] to [connection], waiting while its socket buffer is full. */
    suspend fun write(connection: SocketChannel, answer: ByteArray) {
        val source = ByteBuffer(answer)
        while (source.hasRemaining()) if (connection.write(source) == 0) delay(1)
    }
}
