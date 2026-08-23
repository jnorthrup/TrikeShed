package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.parse.confix.value
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

class BlackboardWire(val blackboard: ConfixBlackboard, scope: CoroutineScope) {
    companion object {
        val ROUTES: List<Pair<String, String>> = listOf("GET" to "/blackboard/facts", "POST" to "/blackboard/assert", "GET" to "/blackboard/sites")
    }

    private val assertChannel = Channel<String>(Channel.UNLIMITED)
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
                        blackboard.put(k, v, "ide")
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
        if (method == "GET" && path.startsWith("/blackboard/facts")) {
            val since = path.substringAfter("since=").substringBefore("&", missingDelimiterValue = "").toLongOrNull() ?: 0L
            
            val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Access-Control-Allow-Origin: *\r\n\r\n"
            respond?.invoke(headers.toByteArray(StandardCharsets.UTF_8))
            
            // Replay from bounded ring
            for (i in 0 until 256) {
                val entry = ring[i]
                if (entry != null && entry.first >= since) {
                    val data = "data: ${JsonSupport.stringify(entry.second.value())}\r\n\r\n"
                    try {
                        respond?.invoke(data.toByteArray(StandardCharsets.UTF_8))
                    } catch (_: Throwable) {
                        return null
                    }
                }
            }
            
            try {
                blackboard.changes.collect { doc ->
                    val data = "data: ${JsonSupport.stringify(doc.value())}\r\n\r\n"
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
