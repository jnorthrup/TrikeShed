@file:Suppress("UNCHECKED_CAST", "FunctionName")

package borg.trikeshed.litebike

import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
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
import java.util.concurrent.TimeUnit

/** An acquired document slot is retained by both its sending job and its receiver. */
internal class DocumentPermit(admission: Semaphore, sender: Job, done: CompletableDeferred<Unit>) {
    private val owner = Job()
    private val sent = CompletableDeferred<Unit>(owner)
    val completion = CompletableDeferred<Unit>(owner)

    init {
        owner.invokeOnCompletion { admission.release() }
        completion.invokeOnCompletion { done.complete(Unit) }
        sender.invokeOnCompletion { sent.complete(Unit) }
        owner.complete()
    }
}

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
        // JVM NIO executor — one thread per CPU, owned by this bind scope.
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
        try {
            acceptLoop(server, element, connections, Semaphore(DocumentBatch.IN_FLIGHT))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { connections.closeAll() }
                runCatching { server.close() }
                group.shutdown()
                val terminated = runCatching { group.awaitTermination(2, TimeUnit.SECONDS) }
                    .getOrDefault(false)
                if (!terminated) {
                    runCatching { group.shutdownNow() }
                    runCatching { group.awaitTermination(2, TimeUnit.SECONDS) }
                }
            }
        }
    }

    private suspend fun acceptLoop(
        server: AsynchronousServerSocketChannel,
        element: LitebikeListenerElement,
        connections: ConnectionRegistry,
        documents: Semaphore,
    ) {
        val supervisor = kotlinx.coroutines.currentCoroutineContext()[borg.trikeshed.userspace.nio.spi.NioSupervisor.Key]
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val ch = kotlinx.coroutines.suspendCancellableCoroutine<AsynchronousSocketChannel> { cont ->
                val handler = object : CompletionHandler<AsynchronousSocketChannel, Any?> {
                    override fun completed(result: AsynchronousSocketChannel, attached: Any?) {
                        cont.resume(result) { _, socket, _ -> runCatching { socket.close() } }
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
            // The permit belongs to an accepted socket, not to an idle accept.
            if (supervisor?.tryAcquireIo() == false) {
                runCatching { ch.close() }
                continue
            }

            // R05 — register the channel up front. The drain loop
            // attaches the sequence id once the listener assigns
            // one, so the HTTP worker can route responses back
            // through the originating socket.
            val connId = connections.register(ch) { supervisor?.releaseIo() }
            System.err.println("[BIND] accepted conn=$connId")
            // Read all bytes from the channel asynchronously, then
            // forward to the listener with the detected protocol.
            CoroutineScope(element.supervisor).launch(start = CoroutineStart.UNDISPATCHED) {
                drainOne(ch, element, connections, connId, documents)
            }
        }
    }

    private suspend fun drainOne(
            ch: AsynchronousSocketChannel,
            element: LitebikeListenerElement,
            connections: ConnectionRegistry,
            connId: Long,
            documents: Semaphore,
        ) {
            val buf = ByteBuffer.allocate(8 * 1024)
            // Reassembly buffer: grows by doubling, so a large body costs O(n), not O(n²).
            var pending = ByteArray(64 * 1024)
            var pendingSize = 0
            
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
                                done.complete(Unit)
                                return
                            }
                            buf.flip()
                            if (pendingSize + read > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + read))
                            buf.get(pending, pendingSize, read)
                            pendingSize += read
                            // Reassemble: one TCP read is not one request. An HTX
                            // client writes the head and the body as two writes, so
                            // frame HTTP/1.1 on Content-Length before dispatching —
                            // otherwise the body fragment is sniffed as its own
                            // protocol (Json) and the connection is dropped.
                            val head = pending.copyOf(minOf(pendingSize, 8))
                            val proto: Protocol = ProtocolDetector.detect(head, pendingSize)
                            if (proto == Protocol.Http && isDocumentRequest(pending, pendingSize)) {
                                val initial = pending.copyOf(pendingSize)
                                CoroutineScope(element.supervisor).launch(start = CoroutineStart.UNDISPATCHED) {
                                    try { documentOne(ch, element, connections, connId, documents, initial, done) }
                                    catch (failure: Throwable) { done.complete(Unit); throw failure }
                                }
                                return
                            }
                            if (proto == Protocol.Http && !httpFrameComplete(pending, pendingSize)) {
                                // Incomplete frame: keep the accumulated bytes and read
                                // the next chunk into a cleared buffer; re-check on append.
                                buf.clear()
                                readLoop()
                                return
                            }
                            val bytes = pending.copyOf(pendingSize)
                            pending = ByteArray(64 * 1024)
                            pendingSize = 0
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
                            // Continue reading
                            buf.clear()
                            readLoop()
                        }

                        override fun failed(t: Throwable, attached: Any?) {
                            connections.unregister(connId)
                            runCatching { ch.close() }
                            // The registry closes the socket after the worker's
                            // reply (Connection: close); the pending read then
                            // fails with AsynchronousCloseException. A peer reset
                            // (a browser dropping an SSE stream or aborting a fetch)
                            // ends the exchange the same way. Neither is an error.
                            if (t is java.io.IOException) {
                                if (t !is java.nio.channels.ClosedChannelException) System.err.println("[BIND] conn=$connId peer reset: ${t.message}")
                                done.complete(Unit)
                            } else done.completeExceptionally(t)
                        }
                    }
                )
            }
        
            // Start the read loop
            try {
                buf.clear()
                readLoop()
                done.await()
            } finally {
                connections.unregister(connId)
            }
        }

    /** Only the dedicated Documents route uses the bounded body contract. */
    private fun isDocumentRequest(bytes: ByteArray, size: Int): Boolean {
        var start = 0
        while (start < size && bytes[start] != ' '.code.toByte()) start++
        if (start == size) return false
        start++
        var end = start
        while (end < size && bytes[end] != ' '.code.toByte() && bytes[end] != '?'.code.toByte() &&
            bytes[end] != '\r'.code.toByte() && bytes[end] != '\n'.code.toByte()) end++
        if (end == size) return false
        val path = String(bytes, start, end - start, Charsets.ISO_8859_1).trim('/')
        val segments = path.split('/')
        return segments.size >= 3 && segments[0] == "_project" && segments[2] == "putDocumentBatch"
    }

    private class FrameFailure(val status: Int) : Exception()

    internal suspend fun readDocument(ch: AsynchronousSocketChannel, initial: ByteArray,
                                      idleMs: Long = DocumentBatch.READ_IDLE_MS): ByteArray {
        if (initial.size > DocumentBatch.HEAD_BYTES) throw FrameFailure(431)
        val head = ByteArray(DocumentBatch.HEAD_BYTES)
        initial.copyInto(head)
        var size = initial.size
        var boundary = indexOfHeaderBoundary(head, size)
        while (boundary < 0) {
            if (size == head.size) throw FrameFailure(431)
            val read = ch.readAwait(ByteBuffer.wrap(head, size, minOf(8192, head.size - size)), idleMs)
            if (read <= 0) throw java.io.EOFException()
            size += read
            boundary = indexOfHeaderBoundary(head, size)
        }
        val lines = String(head, 0, boundary, Charsets.ISO_8859_1).split("\r\n")
        var length: Long? = null
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0 || line.substring(0, colon).any { it <= ' ' || it >= '\u007f' }) throw FrameFailure(400)
            val name = line.substring(0, colon)
            val value = line.substring(colon + 1).trim()
            if (name.equals("Transfer-Encoding", ignoreCase = true)) throw FrameFailure(400)
            if (name.equals("Content-Length", ignoreCase = true)) {
                if (length != null || value.isEmpty() || value.any { it !in '0'..'9' }) throw FrameFailure(400)
                length = value.toLongOrNull() ?: throw FrameFailure(400)
            }
        }
        val contentLength = length ?: throw FrameFailure(400)
        if (contentLength > DocumentBatch.BODY_BYTES) throw FrameFailure(413)
        val headSize = boundary + 4
        val frameSize = headSize + contentLength.toInt()
        if (size > frameSize) throw FrameFailure(400)
        val frame = ByteArray(frameSize)
        head.copyInto(frame, endIndex = size)
        while (size < frame.size) {
            val read = ch.readAwait(ByteBuffer.wrap(frame, size, frame.size - size), idleMs)
            if (read <= 0) throw java.io.EOFException()
            size += read
        }
        return frame
    }

    private suspend fun AsynchronousSocketChannel.readAwait(buffer: ByteBuffer, idleMs: Long): Int =
        withTimeout(idleMs) { suspendCancellableCoroutine { continuation ->
            read(buffer, null, object : CompletionHandler<Int, Any?> {
                override fun completed(result: Int, attachment: Any?) {
                    if (continuation.isActive) continuation.resume(result)
                }
                override fun failed(failure: Throwable, attachment: Any?) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            })
            continuation.invokeOnCancellation { runCatching { close() } }
        } }

    private suspend fun documentOne(
        ch: AsynchronousSocketChannel,
        element: LitebikeListenerElement,
        connections: ConnectionRegistry,
        connId: Long,
        documents: Semaphore,
        initial: ByteArray,
        done: CompletableDeferred<Unit>,
    ) {
        suspend fun reject(status: Int) {
            val reason = when (status) { 400 -> "Bad Request"; 413 -> "Payload Too Large"; 431 -> "Request Header Fields Too Large"; else -> "Service Unavailable" }
            val body = "{\"error\":\"$reason\"}"
            connections.write(connId, "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".encodeToByteArray())
        }
        if (!documents.tryAcquire()) { try { reject(503) } finally { done.complete(Unit) }; return }
        // Both the sending coroutine and the receiver may retain the same frame.
        // The permit outlives each independently and returns only after both end.
        val completion = DocumentPermit(documents, kotlinx.coroutines.currentCoroutineContext()[Job]!!, done).completion
        val payload = try { withTimeout(DocumentBatch.READ_LIFETIME_MS) { readDocument(ch, initial) } }
        catch (failure: FrameFailure) { try { reject(failure.status) } finally { completion.complete(Unit) }; return }
        catch (failure: Throwable) { completion.complete(Unit); throw failure }
        val respond: suspend (ByteArray) -> Unit = { bytes -> connections.write(connId, bytes) }
        // Admission transfers completion to the channel; it may outlive this socket.
        withContext(NonCancellable) {
            try { element.accept(Protocol.Http, payload, respond, completion) } catch (_: Throwable) { }
        }
    }

/** Companion helper for users who want a fire-and-forget lifecycle. */
suspend fun LitebikeListenerElement.serveOnPort(
    port: Int,
    connections: ConnectionRegistry = ConnectionRegistry(),
) {
    JvmLitebikeBindAdapter.bindAndServe(this, port = port, connections = connections)
}
}
