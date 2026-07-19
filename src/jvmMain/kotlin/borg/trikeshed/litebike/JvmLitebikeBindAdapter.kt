@file:Suppress("UNCHECKED_CAST", "FunctionName")

package borg.trikeshed.litebike

import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    ) = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
        val handler = object : CompletionHandler<AsynchronousSocketChannel, Any?> {
            override fun completed(ch: AsynchronousSocketChannel, attached: Any?) {
                // Continue accepting the next connection immediately.
                server.accept(null, this)
                // R05 — register the channel up front. The drain loop
                // attaches the sequence id once the listener assigns
                // one, so the HTTP worker can route responses back
                // through the originating socket.
                val connId = connections.register(ch)
                // Read all bytes from the channel asynchronously, then
                // forward to the listener with the detected protocol.
                drainOne(ch, element, connections, connId)
            }

            override fun failed(t: Throwable, attached: Any?) {
                if (cont.isActive) cont.cancel(t)
            }
        }
        server.accept(null, handler)
        cont.invokeOnCancellation {
            runCatching { server.close() }
        }
    }

    private fun drainOne(
        ch: AsynchronousSocketChannel,
        element: LitebikeListenerElement,
        connections: ConnectionRegistry,
        connId: Long,
    ) {
        val buf = ByteBuffer.allocate(8 * 1024)
        val respondCallback: suspend (ByteArray) -> Unit = { responseBytes ->
            withContext(Dispatchers.IO) {
                val writeBuf = ByteBuffer.wrap(responseBytes)
                while (writeBuf.hasRemaining()) {
                    val written = kotlin.coroutines.suspendCoroutine { cont ->
                        ch.write(writeBuf, null, object : CompletionHandler<Int, Any?> {
                            override fun completed(result: Int, attachment: Any?) {
                                cont.resumeWith(Result.success(result))
                            }
                            override fun failed(exc: Throwable, attachment: Any?) {
                                cont.resumeWith(Result.failure(exc))
                            }
                        })
                    }
                    if (written < 0) break
                }
            }
        }
        ch.read(
            buf, null,
            object : CompletionHandler<Int, Any?> {
                override fun completed(read: Int, attached: Any?) {
                    if (read <= 0) {
                        // Peer closed — drop the registry entry.
                        connections.unregister(connId)
                        return
                    }
                    val bytes = ByteArray(read).also { buf.flip(); buf.get(it) }
                    val head = bytes.copyOf(minOf(bytes.size, 8))
                    val proto: Protocol = ProtocolDetector.detect(head, bytes.size)
                    // runBlocking is OK from a JDK CompletionHandler because
                    // those callbacks are pure Java threads, not coroutines.
                    val ok = runBlocking { element.accept(proto, bytes, respondCallback) }
                    if (!ok) runCatching { ch.close() }
                    // Continue draining while data remains.
                    buf.clear()
                    ch.read(buf, null, this)
                }

                override fun failed(t: Throwable, attached: Any?) {
                    connections.unregister(connId)
                }
            }
        )
    }
}

/** Companion helper for users who want a fire-and-forget lifecycle. */
suspend fun LitebikeListenerElement.serveOnPort(
    port: Int,
    connections: ConnectionRegistry = ConnectionRegistry(),
) {
    JvmLitebikeBindAdapter.bindAndServe(this, port = port, connections = connections)
}
