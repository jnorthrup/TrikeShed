package borg.trikeshed.mcp

import borg.trikeshed.parse.reifyStrict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One framed read from a line-oriented byte stream. */
sealed interface LineRead {
    /** A line without its terminator (`\n`, or `\r\n`). It may be empty. */
    class Line(val bytes: ByteArray) : LineRead

    /** The line exceeded the bound; everything through its terminator has been discarded without being buffered. */
    object TooLong : LineRead

    object Eof : LineRead
}

/** Where request lines come from: a process's stdin, a socket under tcpd, a test script. */
interface LineSource {
    /** Reads the next line of at most [maxBytes] bytes of content. Never buffers more than that. */
    suspend fun read(maxBytes: Int): LineRead
}

/** One admitted request. [job] is set by the intake loop right after launch, and read only by that same loop, so it needs no lock. */
private class Slot {
    var job: Job? = null
}

/** Where reply lines go. [write] sends exactly one line plus its terminator and flushes; the server serialises calls. */
interface LineSink {
    suspend fun write(line: ByteArray)
}

/**
 * Newline-delimited JSON-RPC over any byte stream: the transport half that the stdio, tcpd and wrapper deployments
 * share. Everything here is bounded and none of it knows what a tool is.
 *
 *  - Line length is bounded by the source; an over-long line gets one error reply and the stream resynchronises at
 *    the next terminator. Input that is not valid UTF-8 gets a parse error. Blank lines are ignored.
 *  - Requests run concurrently, at most [maxInFlight] at a time, so a slow tool never blocks `ping` or a
 *    cancellation. A request over the limit is answered `-32001 server busy` with its own id; it is shed, not queued,
 *    because a queue behind a long mission is exactly what hides a cancellation. A request id that is already in
 *    flight is refused (replying on it would be ambiguous), and replies may come back in any order.
 *  - `notifications/cancelled` cancels the named in-flight request. That request then gets NO reply, as the spec asks.
 *    A cancellation for an unknown or finished id is ignored.
 *  - Notifications and unusable requests are handled inline: they cost microseconds and must not take a slot.
 *  - EOF ends intake only. Requests already accepted run to completion (each is bounded by its own timeout) and their
 *    replies are written before [serve] returns, so `printf '{...}' | server` works as a one-shot wrapper.
 *  - A failing sink (the client went away) fails [serve] and cancels everything in flight: nobody is left to read.
 */
class McpLineServer(
    private val server: McpToolServer,
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
    private val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    /** Transport-level events, by kind only (`line-too-long`, `invalid-utf8`, `shed`, `duplicate-id`, `cancel`). */
    private val onEvent: (String) -> Unit = {},
) {
    init {
        require(maxLineBytes > 0) { "maxLineBytes must be positive" }
        require(maxInFlight > 0) { "maxInFlight must be positive" }
    }

    suspend fun serve(source: LineSource, sink: LineSink) {
        val writeLock = Mutex()
        val registry = Mutex()
        val running = HashMap<String, Slot>()
        var active = 0

        suspend fun send(document: String) {
            if (document.isEmpty()) return
            writeLock.withLock { sink.write(document.encodeToByteArray()) }
        }

        fun event(kind: String) {
            try { onEvent(kind) } catch (_: Throwable) {}
        }

        coroutineScope {
            while (true) {
                val read = source.read(maxLineBytes)
                if (read === LineRead.Eof) break
                if (read === LineRead.TooLong) {
                    event("line-too-long")
                    send(McpToolServer.errorDocument(null, McpToolServer.INVALID_REQUEST, "request line exceeds $maxLineBytes bytes"))
                    continue
                }
                val text = try {
                    (read as LineRead.Line).bytes.decodeToString(throwOnInvalidSequence = true)
                } catch (_: Exception) {
                    event("invalid-utf8")
                    send(McpToolServer.errorDocument(null, McpToolServer.PARSE_ERROR, "request is not valid UTF-8"))
                    continue
                }
                if (text.isBlank()) continue

                val doc = runCatching { reifyStrict(text) }.getOrNull() as? Map<*, *>

                if (doc != null && doc["method"] == CANCELLED && doc["id"] == null) {
                    val target = McpToolServer.idKey((doc["params"] as? Map<*, *>)?.get("requestId"))
                    val job = if (target == null) null else registry.withLock { running[target] }?.job
                    if (job != null) {
                        event("cancel")
                        job.cancel(CancellationException("cancelled by client"))
                    }
                    continue
                }

                val id = doc?.get("id")
                val key = if (doc != null && McpToolServer.idProblem(id) == null) McpToolServer.idKey(id) else null
                if (key == null) {
                    send(server.handle(text))
                    continue
                }

                // The decision and the reservation are one step, so a request is never half-admitted. A shed or duplicate
                // request starts nothing at all.
                val slot = Slot()
                val verdict = registry.withLock {
                    when {
                        active >= maxInFlight -> BUSY
                        key in running -> DUPLICATE
                        else -> {
                            active++
                            running[key] = slot
                            ADMIT
                        }
                    }
                }
                when (verdict) {
                    // ATOMIC: the body always begins, so its cleanup always runs, even when the request is cancelled in the
                    // very next line, before the job was ever dispatched. A cancelled-before-start coroutine would
                    // otherwise skip its `finally` and keep its slot and its id forever.
                    ADMIT -> slot.job = launch(start = CoroutineStart.ATOMIC) {
                        try {
                            ensureActive()
                            val reply = try {
                                server.handle(text)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                event("internal-error")
                                McpToolServer.errorDocument(id, McpToolServer.INTERNAL_ERROR, "internal error")
                            }
                            // A cancellation that arrived while a handler that never suspended was running still means "no reply".
                            ensureActive()
                            send(reply)
                        } finally {
                            // Cancellation must not leak the slot. Identity is the slot itself: inside NonCancellable the
                            // current Job is NonCancellable, so it can never be compared against this request's job.
                            withContext(NonCancellable) {
                                registry.withLock {
                                    active--
                                    if (running[key] === slot) running.remove(key)
                                }
                            }
                        }
                    }
                    BUSY -> {
                        event("shed")
                        send(McpToolServer.errorDocument(id, SERVER_BUSY, "server busy: $maxInFlight requests in flight"))
                    }
                    else -> {
                        event("duplicate-id")
                        send(McpToolServer.errorDocument(null, McpToolServer.INVALID_REQUEST, "request id is already in flight"))
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_LINE_BYTES: Int = 1_048_576
        const val DEFAULT_MAX_IN_FLIGHT: Int = 8

        /** JSON-RPC implementation-defined server error: the request was well-formed but there is no capacity for it. */
        const val SERVER_BUSY: Int = -32001

        private const val CANCELLED = "notifications/cancelled"
        private const val ADMIT = 0
        private const val BUSY = 1
        private const val DUPLICATE = 2
    }
}
