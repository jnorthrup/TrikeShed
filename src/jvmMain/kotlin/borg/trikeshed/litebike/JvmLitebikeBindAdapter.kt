@file:Suppress("UNCHECKED_CAST", "FunctionName")

package borg.trikeshed.litebike

import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.nio.charset.StandardCharsets
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
 * lives in protocol-typed workers registered on the listener.
 */
object JvmLitebikeBindAdapter {

    /**
     * Bind + accept-loop on [port] and pipe every accepted byte stream
     * into [element]'s fanout. The bind + accept loop suspends the
     * current coroutine; cancel to stop.
     */
    suspend fun bindAndServe(
        element: LitebikeListenerElement,
        port: Int,
        host: String = "0.0.0.0",
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
        coroutineScope {
            // Single-element-listener loops — one per protocol slot —
            // ensure incoming bytes are routed by protocol.
            element.fanoutChannels { _, _ -> true }

            acceptLoop(server, element)
        }

        // Best-effort cleanup if scope exits unexpectedly.
        runCatching { server.close() }
        runCatching { group.shutdown() }
    }

    private suspend fun acceptLoop(
        server: AsynchronousServerSocketChannel,
        element: LitebikeListenerElement,
    ) = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
        val handler = object : CompletionHandler<AsynchronousSocketChannel, Any?> {
            override fun completed(ch: AsynchronousSocketChannel, attached: Any?) {
                // Continue accepting the next connection immediately.
                server.accept(null, this)
                // Read all bytes from the channel asynchronously, then
                // forward to the listener with the detected protocol.
                drainOne(ch, element)
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
    ) {
        val buf = ByteBuffer.allocate(8 * 1024)
        ch.read(
            buf, null,
            object : CompletionHandler<Int, Any?> {
                override fun completed(read: Int, attached: Any?) {
                    if (read <= 0) {
                        runCatching { ch.close() }
                        return
                    }
                    val bytes = ByteArray(read).also { buf.flip(); buf.get(it) }
                    val head = bytes.copyOf(minOf(bytes.size, 8))
                    val proto: Protocol = ProtocolDetector.detect(head, bytes.size)
                    // runBlocking is OK from a JDK CompletionHandler because
                    // those callbacks are pure Java threads, not coroutines.
                    val ok = runBlocking { element.accept(proto, bytes) }
                    if (!ok) runCatching { ch.close() }
                    // Continue draining while data remains.
                    buf.clear()
                    ch.read(buf, null, this)
                }

                override fun failed(t: Throwable, attached: Any?) {
                    runCatching { ch.close() }
                }
            }
        )
    }
}

/** Companion helper for users who want a fire-and-forget lifecycle. */
suspend fun LitebikeListenerElement.serveOnPort(port: Int) {
    JvmLitebikeBindAdapter.bindAndServe(this, port = port)
}
