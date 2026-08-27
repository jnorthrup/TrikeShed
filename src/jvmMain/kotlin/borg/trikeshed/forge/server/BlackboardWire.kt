package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.parse.confix.value
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

    // Bounded ring of 256
    private val ring = Array<Pair<Long, borg.trikeshed.parse.confix.ConfixDoc>?>(256) { null }

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
            blackboard.changes.collect { doc ->
                val seq = sequence++
                ring[(seq % 256).toInt()] = seq to doc
            }
        }
    }

    suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)? = null): JvmKanbanServer.HttpResponse? {
        // R7: one consolidated blackboard page. Resource I/O stays off the reactor thread.
        if (method == "GET" && (path == "/blackboard" || path == "/blackboard/")) {
            return withContext(Dispatchers.IO) {
                val html = BlackboardWire::class.java.classLoader
                    .getResourceAsStream("web/blackboard.html")
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

            // H1 repair: replay in SEQUENCE order, not array-slot order (out of order
            // after the first wrap). The ring holds seq → doc; a seq-sorted sweep of
            // the occupied slots is the causal order.
            val occupied = ring.mapIndexed { i, e -> if (e != null) i else -1 }.filter { it >= 0 }
                .sortedBy { i -> ring[i]!!.first }
            for (i in occupied) {
                val (seq, doc) = ring[i]!!
                if (seq >= since) {
                    val data = "id: $seq\r\ndata: ${JsonSupport.stringify(doc.value())}\r\n\r\n"
                    try {
                        respond?.invoke(data.toByteArray(StandardCharsets.UTF_8))
                    } catch (_: Throwable) {
                        return null
                    }
                }
            }

            try {
                blackboard.changes.collect { doc ->
                    val seq = sequence++
                    ring[(seq % 256).toInt()] = seq to doc
                    val data = "id: $seq\r\ndata: ${JsonSupport.stringify(doc.value())}\r\n\r\n"
                    try {
                        respond?.invoke(data.toByteArray(StandardCharsets.UTF_8))
                    } catch (_: Throwable) {
                        throw kotlinx.coroutines.CancellationException("SSE client disconnected")
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (t: Throwable) {
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
