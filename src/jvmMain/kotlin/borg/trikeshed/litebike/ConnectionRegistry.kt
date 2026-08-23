@file:Suppress("UNCHECKED_CAST", "FunctionName")

package borg.trikeshed.litebike

import kotlinx.coroutines.CompletableDeferred
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ConnectionRegistry — JVM-side per-connection state for the litebike
 * bind adapter. R05: the bind adapter hands each accepted
 * [AsynchronousSocketChannel] to [register]; the HTTP worker looks up
 * the originating channel by sequenceId and writes the response back via [write].
 *
 * Why a JVM-only registry? [LitebikeListenerElement.ChannelMessage]
 * lives in commonMain and adding a JVM-specific socket field there
 * would poison the KMP source set. The sequence id already exists in
 * the message; we map it to a socket in JVM space.
 *
 * Concurrency: backed by [ConcurrentHashMap]; safe under arbitrary
 * reader/writer concurrency. Writes are issued on the JVM NIO group
 * via [AsynchronousSocketChannel.write], so workers never block on I/O.
 */
class ConnectionRegistry {

    private val nextId = AtomicLong(0L)

    private data class Entry(
        val channel: AsynchronousSocketChannel,
        /** SequenceId of the in-flight request, if any. */
        @Volatile var pendingSequenceId: Long? = null,
    )

    private val connections: ConcurrentHashMap<Long, Entry> = ConcurrentHashMap()

    /**
     * Register a freshly accepted channel. Returns a stable
     * connectionId that the caller can later use with [write] or
     * [unregister].
     */
    fun register(channel: AsynchronousSocketChannel): Long {
        val id = nextId.incrementAndGet()
        connections[id] = Entry(channel)
        return id
    }

    /**
     * Stamp a [sequenceId] onto an existing connection so the worker
     * can find the originating channel.
     */
    fun attachSequence(connectionId: Long, sequenceId: Long) {
        val entry = connections[connectionId] ?: return
        entry.pendingSequenceId = sequenceId
    }

    /**
     * Write [bytes] back through the channel registered as
     * [connectionId]. Returns true on success, false if the write
     * completes with a negative result (peer closed) or throws.
     *
     * Asynchronous — completes via the supplied [CompletionHandler]
     * or the channel group's default executor. Does not block the
     * calling worker.
     *
     * On completion the channel is closed and unregistered; this is
     * HTTP/1.1 per-connection semantics. If you want keep-alive,
     * replace the `unregister` call with a reset of `pendingSequenceId`.
     */
    /** Connections answering a `text/event-stream` response: writes keep them open until one fails. */
    private val streaming = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Mark [connectionId] as a server-sent-event stream: subsequent [write]s do not close it. */
    fun markStreaming(connectionId: Long) { if (connections.containsKey(connectionId)) streaming += connectionId }

    suspend fun write(connectionId: Long, bytes: ByteArray): Boolean {
        val entry = connections[connectionId] ?: return false
        val channel = entry.channel
        val buf = ByteBuffer.wrap(bytes)
        val done = CompletableDeferred<Boolean>()
        try {
            channel.write(buf, null, object : CompletionHandler<Int, Any?> {
                override fun completed(written: Int, attached: Any?) {
                    done.complete(written >= 0)
                }
                override fun failed(t: Throwable, attached: Any?) {
                    done.complete(false)
                }
            })
        } catch (t: Throwable) {
            // Channel already closed or in invalid state.
            unregister(connectionId)
            return false
        }
        // Block briefly for the write to finish — the worker is on a
        // Default dispatcher so a short park here is fine, and we need
        // the boolean to decide whether to log failure. We don't want
        // to spin: the JDK NIO group completes writes in microseconds
        // for local sockets, and the daemon doesn't have latency SLOs.
        val ok = done.await()
        if (ok && connectionId in streaming) return true   // SSE: the stream ends when the peer goes away (write fails)
        streaming.remove(connectionId)
        unregister(connectionId)
        return ok
    }

    /**
     * Drop [connectionId] from the registry and close the underlying
     * channel. Idempotent.
     */
    fun unregister(connectionId: Long) {
        streaming.remove(connectionId)
        val entry = connections.remove(connectionId) ?: return
        runCatching { entry.channel.close() }
        // NioSupervisor permit is released gracefully during drain or explicitly by adapter.
    }

    /**
     * Close every registered channel. Called from the JVM shutdown
     * hook so the daemon doesn't leak sockets on exit.
     */
    fun closeAll() {
        for (id in connections.keys.toList()) unregister(id)
    }

    /** Live connection count — useful for `/api/cap` and tests. */
    fun activeCount(): Int = connections.size
}
