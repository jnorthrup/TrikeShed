@file:Suppress("UNCHECKED_CAST", "FunctionName")

package borg.trikeshed.litebike

import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.concurrent.Executors

/**
 * JvmLitebikeBindAdapter — the only place native bind lives for the
 * LitebikeListenerElement. Reuses the `nio` SPI by binding an
 * `AsynchronousServerSocketChannel` and forwarding accepted bytes
 * into `LitebikeListenerElement.accept(protocol, payload)`.
 *
 * Why this exists:
 *   - `KanbanHttpServerJvm` previously leaned on `com.sun.net.httpserver`,
 *     which violated the "zero NIO in the server core" instruction.
 *     This adapter is the only seam that touches Java NIO; downstream
 *     code is bytes-in / CCEK-out.
 *   - `HtxReactorElement` is exchange-only and does not bind. Right
 *     for client-side dispatch, wrong for listening. Use the listener.
 *
 * Usage:
 *   val element = LitebikeListenerElement().also { it.open() }
 *   val adapter = JvmLitebikeBindAdapter(element, port = 8888)
 *   adapter.start()
 *   // ...
 *   adapter.close()
 *
 * The adapter is deliberately thin: bytes → CCEK accept. The HTTP
 * parsing, the JSON inspection, the wire-protocol fanout — all of it
 * lives in protocol-typed workers registered on the listener. The daemon
 * starts [LitebikeListenerElement.fanoutChannels]; this adapter must not
 * start a competing consumer set.
 *
 * R05 — accepted channels are registered into the [ConnectionRegistry]
 * passed via [bindAndServe]. The bind adapter stamps the resulting
 * sequence id onto the registry so the HTTP worker can write the
 * response back to the originating socket.
 */
object JvmLitebikeBindAdapter {

    /**
     * Reassembly cap, in units of [LitebikeListenerElement.maxBatch]:
     * an incomplete HTTP frame may hold at most
     * `maxBatch * PENDING_FRAMES_PER_BATCH` bytes (64 KiB at the
     * default maxBatch of 64) before the connection is answered with
     * 413 and closed. Bounds the per-connection `pending` buffer.
     */
    const val PENDING_FRAMES_PER_BATCH: Int = 1024

    /** Minimal reply for a frame that outgrew the reassembly cap. */
    internal val PAYLOAD_TOO_LARGE: ByteArray =
        "HTTP/1.1 413 Payload Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray()

    /** Effective reassembly cap for [element]. */
    internal fun pendingCap(element: LitebikeListenerElement): Int = element.maxBatch * PENDING_FRAMES_PER_BATCH

    /**
     * Bind + accept-loop on [port] and pipe every accepted byte stream
     * into [element]'s fanout. The bind + accept loop suspends the
     * current coroutine; cancel to stop.
     *
     * @param connections JVM-side connection registry; every accepted
     * channel is registered and stamped with its sequence id before
     * bytes are offered to [element]. Pass the same instance the HTTP
     * worker reads from so response writes hit the originating socket.
     * The default is suitable only for callers that do not need to share
     * the registry with a separate response worker.
     */
    suspend fun bindAndServe(
        element: LitebikeListenerElement,
        port: Int,
        host: String = "0.0.0.0",
        connections: ConnectionRegistry = ConnectionRegistry(),
    ) {
        // JVM NIO executor — one thread per CPU, daemon.
        val group: AsynchronousChannelGroup =
            AsynchronousChannelGroup.withFixedThreadPool(
                Runtime.getRuntime().availableProcessors(),
                Executors.defaultThreadFactory(),
            )
        val server: AsynchronousServerSocketChannel =
            AsynchronousServerSocketChannel.open(group).apply {
                setOption(StandardSocketOptions.SO_REUSEADDR, true)
                bind(InetSocketAddress(host, port))
            }

        // The accepted-channel reads use the listener as the worker:
        // each accepted connection is its own AsynchronousSocketChannel
        // and each accepted runOnDispatch puts bytes on the listener.
        // No Htx/HTTP framework, no reactor-without-bind.
        // The daemon owns the protocol-slot consumers. This adapter only
        // owns the bind and accepted-channel read loop; starting a second
        // fanout here would race the daemon's consumers and consume messages
        // before the HTTP worker can dispatch them through NUID.
        acceptLoop(server, element, connections)

        // Best-effort cleanup if scope exits unexpectedly.
        runCatching { connections.closeAll() }
        runCatching { server.close() }
        runCatching { group.shutdown() }
    }

    private suspend fun acceptLoop(
        server: AsynchronousServerSocketChannel,
        element: LitebikeListenerElement,
        connections: ConnectionRegistry,
    ) {
        val supervisor = kotlinx.coroutines.currentCoroutineContext()[borg.trikeshed.userspace.nio.spi.NioSupervisor.Key]
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            // NioSupervisor backpressure: acquire permit before accepting
            if (supervisor?.tryAcquireIo() == false) {
                // back-pressure: close the socket immediately
                // Since this is AsynchronousServerSocketChannel, we must accept and then close
                val ch = kotlinx.coroutines.suspendCancellableCoroutine<AsynchronousSocketChannel> { cont ->
                    val handler = object : CompletionHandler<AsynchronousSocketChannel, Any?> {
                        override fun completed(result: AsynchronousSocketChannel, attached: Any?) {
                            cont.resume(result)
                        }
                        override fun failed(t: Throwable, attached: Any?) {
                            if (cont.isActive) cont.resumeWithException(t)
                        }
                    }
                    server.accept(null, handler)
                    cont.invokeOnCancellation {
                        runCatching { server.close() }
                    }
                }
                runCatching { ch.close() }
                continue
            }

            val ch = try {
                kotlinx.coroutines.suspendCancellableCoroutine<AsynchronousSocketChannel> { cont ->
                    val handler = object : CompletionHandler<AsynchronousSocketChannel, Any?> {
                        override fun completed(result: AsynchronousSocketChannel, attached: Any?) {
                            cont.resume(result)
                        }

                        override fun failed(t: Throwable, attached: Any?) {
                            if (cont.isActive) cont.resumeWithException(t)
                        }
                    }
                    server.accept(null, handler)
                    cont.invokeOnCancellation {
                        runCatching { server.close() }
                    }
                }
            } catch (e: Throwable) {
                supervisor?.releaseIo()
                throw e
            }

            // R05 — register the channel up front. The drain loop
            // attaches the sequence id once the listener assigns
            // one, so the HTTP worker can route responses back
            // through the originating socket.
            val connId = connections.register(ch)
            System.err.println("[BIND] accepted conn=$connId")
            // Read all bytes from the channel asynchronously, then
            // forward to the listener with the detected protocol.
            CoroutineScope(element.supervisor).launch {
                drainOne(ch, element, connections, connId, supervisor)
            }
        }
    }

    private suspend fun drainOne(
            ch: AsynchronousSocketChannel,
            element: LitebikeListenerElement,
            connections: ConnectionRegistry,
            connId: Long,
            supervisor: borg.trikeshed.userspace.nio.spi.NioSupervisor? = null,
        ) {
            val buf = ByteBuffer.allocate(8 * 1024)
            var pending = ByteArray(0)
            val cap = pendingCap(element)
            
            // Use a CompletableDeferred to wait for channel closure
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            
            fun readLoop() {
                ch.read(
                    buf, null,
                    object : CompletionHandler<Int, Any?> {
                        override fun completed(read: Int, attached: Any?) {
                            if (read <= 0) {
                                // Peer closed — drop the registry entry.
                                connections.unregister(connId)
                                runCatching { ch.close() }
                                supervisor?.releaseIo()
                                done.complete(Unit)
                                return
                            }
                            val chunk = ByteArray(read).also { buf.flip(); buf.get(it) }
                            // Reassemble: one TCP read is not one request. An HTX
                            // client writes the head and the body as two writes, so
                            // frame HTTP/1.1 on Content-Length before dispatching —
                            // otherwise the body fragment is sniffed as its own
                            // protocol (Json) and the connection is dropped.
                            pending = pending + chunk
                            val head = pending.copyOf(minOf(pending.size, 8))
                            val proto: Protocol = ProtocolDetector.detect(head, pending.size)
                            if (proto == Protocol.Http && !httpFrameComplete(pending)) {
                                if (pending.size > cap) {
                                    // Frame outgrew the reassembly cap: answer 413 on
                                    // this connection only (the registry write closes
                                    // and unregisters it); the listener keeps serving.
                                    pending = ByteArray(0)
                                    runBlocking { connections.write(connId, PAYLOAD_TOO_LARGE) }
                                    runCatching { ch.close() }
                                    supervisor?.releaseIo()
                                    done.complete(Unit)
                                    return
                                }
                                // Incomplete frame: keep the accumulated bytes and read
                                // the next chunk into a cleared buffer; re-check on append.
                                buf.clear()
                                readLoop()
                                return
                            }
                            val bytes = pending
                            pending = ByteArray(0)
                            System.err.println("[BIND] conn=$connId frame complete proto=$proto bytes=${bytes.size} seq-alloc")
                            // R05 — the worker answers through the originating
                            // socket; the registry write closes the connection
                            // (HTTP/1.1 Connection: close semantics).
                            val respond: suspend (ByteArray) -> Unit = { out ->
                                // An SSE response header turns this connection into a stream: it stays open across
                                // writes (VmWire /api/vm/events, BlackboardWire /blackboard/facts, Jules events).
                                if (out.size > 12 && out[0] == 'H'.code.toByte() && String(out, 0, minOf(out.size, 256), Charsets.ISO_8859_1).contains("text/event-stream")) {
                                    connections.markStreaming(connId)
                                }
                                connections.write(connId, out)
                            }
                            // runBlocking is OK from a JDK CompletionHandler because
                            // those callbacks are pure Java threads, not coroutines.
                            val ok = runBlocking { element.accept(proto, bytes, respond) }
                            System.err.println("[BIND] conn=$connId accept ok=$ok")
                            if (!ok) {
                                connections.unregister(connId)
                                runCatching { ch.close() }
                                done.complete(Unit)
                                return
                            }
                            supervisor?.releaseIo()
                            // Continue reading
                            buf.clear()
                            readLoop()
                        }

                        override fun failed(t: Throwable, attached: Any?) {
                            connections.unregister(connId)
                            runCatching { ch.close() }
                            supervisor?.releaseIo()
                            // The registry closes the socket after the worker's
                            // reply (Connection: close); the pending read then
                            // fails with AsynchronousCloseException. That is the
                            // normal end of an exchange, not an error.
                            if (t is java.nio.channels.AsynchronousCloseException ||
                                t is java.nio.channels.ClosedChannelException
                            ) done.complete(Unit) else done.completeExceptionally(t)
                        }
                    }
                )
            }
        
            // Start the read loop
            buf.clear()
            readLoop()
            
            // Wait for completion
            done.await()
        }

    /**
     * True once [bytes] hold a complete HTTP/1.1 request: the header
     * boundary is present and, if a Content-Length is declared, that
     * many body bytes follow it. Chunked request bodies are not framed
     * here (no caller sends them).
     */
    internal fun httpFrameComplete(bytes: ByteArray): Boolean {
        val boundary = indexOfHeaderBoundary(bytes)
        if (boundary < 0) return false
        val headText = bytes.decodeToString(0, boundary)
        val contentLength = headText.split("\r\n")
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        return bytes.size - (boundary + 4) >= contentLength
    }

    private fun indexOfHeaderBoundary(bytes: ByteArray): Int {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
            ) return i
        }
        return -1
    }

/** Companion helper for users who want a fire-and-forget lifecycle. */
suspend fun LitebikeListenerElement.serveOnPort(
    port: Int,
    connections: ConnectionRegistry = ConnectionRegistry(),
) {
    JvmLitebikeBindAdapter.bindAndServe(this, port = port, connections = connections)
}
}