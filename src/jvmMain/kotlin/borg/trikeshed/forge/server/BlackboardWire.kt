package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

class BlackboardWire(val blackboard: ConfixBlackboard, scope: CoroutineScope) {
    companion object {
        val ROUTES: List<Pair<String, String>> = listOf("GET" to "/blackboard", "GET" to "/blackboard/facts", "POST" to "/blackboard/assert", "GET" to "/blackboard/sites", "GET" to "/blackboard/board")
        /** Paths the HTTP server must hand to [route] raw (SSE lives on them). */
        val STREAMING: Set<String> = setOf("/blackboard/facts")
    }

    private val assertChannel = Channel<String>(Channel.UNLIMITED)
    /** H5: pointcut definition docs posted through the assert funnel apply to the live runtime. */
    internal val pointcutDefinitions = borg.trikeshed.cursor.PointcutDefinitionWriter(blackboard, scope)
    private var sequence = 0L

    // Bounded ring of 256 serialized fact events (seq → SSE-ready JSON line).
    private val ring = Array<Pair<Long, String>?>(256) { null }
    /** Live fan-out to SSE clients: (seq, json). ONE collector below owns sequence + ring. */
    private val factEvents = MutableSharedFlow<Pair<Long, String>>(
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** key → provenance timestamp at last emission; the diff basis for per-key events. */
    private val lastStamps = mutableMapOf<String, Long>()

    /**
     * ConfixBlackboard.changes emits the WHOLE post-mutation doc, not a keyed delta —
     * and its Confix internals defeat JsonSupport (the old wire streamed
     * `JoinKt$j$1@…` toString hashes). Diff by provenance timestamp instead and emit
     * one honest `{seq,key,value,actor,atMs}` event per changed key. Values are the
     * raw objects handed to blackboard.put (maps/strings), which stringify cleanly;
     * anything exotic degrades to its toString, never to a broken stream.
     */
    private fun emitDeltas() = synchronized(this) {
        // The blackboard's maps are single-writer/unguarded-read: a keys() snapshot
        // can throw mid-grow under a concurrent writer. Dropping one diff pass is
        // safe — the next change event (or the next client) re-diffs by stamp.
        val keys = runCatching { blackboard.keys() }.getOrElse { return@synchronized }
        for (k in keys) {
            val prov = blackboard.getProvenance(k) ?: continue
            if (lastStamps[k] == prov.timestamp) continue
            lastStamps[k] = prov.timestamp
            val event = linkedMapOf<String, Any?>(
                "seq" to sequence, "key" to k, "value" to blackboard.get(k),
                "actor" to prov.language, "atMs" to prov.timestamp,
            )
            val json = runCatching { JsonSupport.stringify(event) }.getOrElse {
                JsonSupport.stringify(event + ("value" to blackboard.get(k).toString()))
            }
            val seq = sequence++
            ring[(seq % 256).toInt()] = seq to json
            factEvents.tryEmit(seq to json)
        }
    }

    init {
        scope.launch {
            for (payload in assertChannel) {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val map = JsonSupport.parse(payload) as? Map<String, Any?>
                    map?.forEach { (k, v) ->
                        // H5: definitions are not theater — route the assert-funnel key through
                        // the production writer so enabled=false suppresses the runtime site.
                        if (pointcutDefinitions.applyFunnelDoc(k, v) == null) {
                            blackboard.put(k, v, "ide")
                        }
                    }
                }
            }
        }

        scope.launch {
            // The one owner of sequence/ring. replay=1 on changes seeds the ring with
            // the current board on attach, so a fresh SSE client replays real facts.
            blackboard.changes.collect { runCatching { emitDeltas() } }
        }
    }

    suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)? = null): JvmKanbanServer.HttpResponse? {
        // R7: one consolidated blackboard page. Resource I/O stays off the reactor thread.
        if (method == "GET" && (path == "/blackboard" || path == "/blackboard/")) {
            return withContext(Dispatchers.IO) {
                // One page, one landscape: /blackboard and /graal serve the SAME console
                // document — the blackboard is the console's O panel, not a sibling page.
                val html = BlackboardWire::class.java.classLoader
                    .getResourceAsStream("web/graal.html")
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    ?: return@withContext JvmKanbanServer.HttpResponse(404, "blackboard page not found", "text/plain; charset=utf-8")
                JvmKanbanServer.HttpResponse(200, html, "text/html; charset=utf-8")
            }
        }
        if (method == "GET" && path.startsWith("/blackboard/facts")) {
            // H1 repair: `since` is a QUERY PARAMETER, not a path suffix.
            val query = path.substringAfter("?", "")
            val since = query.split("&").find { it.startsWith("since=") }
                ?.substringAfter("since=")?.toLongOrNull() ?: 0L

            val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Access-Control-Allow-Origin: *\r\n\r\n"
            respond?.invoke(headers.toByteArray(StandardCharsets.UTF_8))

            // Catch-up diff on the client's own thread: the collector may lag the
            // blackboard (bounded changes buffer + real-thread scheduling); a fresh
            // client must replay the CURRENT board, not the collector's progress.
            // emitDeltas is synchronized and idempotent per provenance stamp.
            emitDeltas()

            // Subscribe FIRST, replay SECOND: events the collector emits while we
            // sweep the ring land in the live buffer instead of the gap between
            // ring-read and subscription (the old order silently dropped them).
            kotlinx.coroutines.coroutineScope {
                val live = Channel<Pair<Long, String>>(4096)
                val sub = launch {
                    factEvents.collect { e -> live.trySend(e) }
                }
                // H1 repair: replay in SEQUENCE order, not array-slot order (out of
                // order after the first wrap). The ring holds seq → serialized event;
                // a seq-sorted sweep of the occupied slots is the causal order.
                var replayedTo = -1L
                val occupied = ring.mapIndexed { i, e -> if (e != null) i else -1 }.filter { it >= 0 }
                    .sortedBy { i -> ring[i]!!.first }
                var clientGone = false
                for (i in occupied) {
                    val (seq, json) = ring[i]!!
                    if (seq >= since) {
                        val data = "id: $seq\r\ndata: $json\r\n\r\n"
                        try {
                            respond?.invoke(data.toByteArray(StandardCharsets.UTF_8))
                            replayedTo = seq
                        } catch (_: Throwable) {
                            clientGone = true; break
                        }
                    }
                }
                if (!clientGone) try {
                    // The init collector owns sequence/ring; this lane only forwards.
                    // Skip anything the replay already delivered.
                    for ((seq, json) in live) {
                        if (seq <= replayedTo || seq < since) continue
                        val data = "id: $seq\r\ndata: $json\r\n\r\n"
                        try {
                            respond?.invoke(data.toByteArray(StandardCharsets.UTF_8))
                        } catch (_: Throwable) {
                            break
                        }
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                } catch (t: Throwable) {
                }
                sub.cancel()
            }

            return null
        }

        // H1: the snapshot route beside the delta feed — the ConfixBlackboard delta
        // stream carries one key per event and never reflects deletions; a client
        // that wants the whole board asks for it explicitly.
        if (method == "GET" && path.startsWith("/blackboard/board")) {
            val snapshot = linkedMapOf<String, Any?>()
            for (k in blackboard.keys().sorted()) {
                blackboard.get(k)?.let { snapshot[k] = it }
            }
            return JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(mapOf("keys" to snapshot.size, "board" to snapshot)))
        }

        if (method == "POST" && path == "/blackboard/assert") {
            val payload = text.substringAfter("\r\n\r\n", "").ifEmpty {
                text.substringAfter("\n\n", "")
            }
            if (payload.isBlank()) return JvmKanbanServer.HttpResponse(400, "{\"error\":\"empty_body\"}")
            assertChannel.send(payload)
            return JvmKanbanServer.HttpResponse(200, "{\"ok\":true}")
        }
        
        if (method == "GET" && path.startsWith("/blackboard/sites")) {
            val query = path.substringAfter("?")
            val owner = query.split("&").find { it.startsWith("owner=") }?.substringAfter("owner=") ?: ""
            val prefix = if (owner.isNotEmpty()) "pointcut/${owner}/" else "pointcut//"
            val keys = blackboard.keys().filter { it.startsWith(prefix) }
            return JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(keys))
        }

        return null
    }
}
