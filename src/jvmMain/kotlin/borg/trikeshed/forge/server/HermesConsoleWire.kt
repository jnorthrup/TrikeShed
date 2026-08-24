package borg.trikeshed.forge.server

import borg.trikeshed.hermes.HermesVmConsole
import borg.trikeshed.lcnc.media.ManualMediaInput
import borg.trikeshed.lcnc.media.toMap
import borg.trikeshed.lib.view
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.terminal.VtKey
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** HTTP/SSE control surface for the real supervised Hermes VM VT220 panel. */
class HermesConsoleWire(
    private val console: HermesVmConsole,
    private val scope: CoroutineScope,
) {
    private val commands = Channel<ManualMediaInput>(capacity = 64)

    init {
        scope.launch(Dispatchers.IO) {
            for (command in commands) console.execute(command)
        }
    }

    companion object {
        const val EVENTS_PATH = "/api/hermes/terminal/events"
        val STREAMING: Set<String> = setOf(EVENTS_PATH)
    }

    suspend fun route(
        method: String,
        path: String,
        text: String,
        respond: (suspend (ByteArray) -> Unit)?,
    ): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        return when {
            method == "GET" && (p == "/hermes" || p == "/hermes/") -> page()
            method == "GET" && p == "/api/hermes/terminal" -> json(200, console.snapshotMap())
            method == "POST" && p == "/api/hermes/terminal/open" -> {
                scope.launch(Dispatchers.IO) { console.open() }
                json(202, mapOf("accepted" to true, "state" to console.state.name.lowercase()))
            }
            method == "POST" && p == "/api/hermes/terminal/input" -> input(parse(text))
            method == "POST" && p == "/api/hermes/terminal/resize" -> {
                val request = parse(text)
                val columns = (request["columns"] as? Number)?.toInt()?.coerceIn(20, 300)
                    ?: return json(400, mapOf("error" to "columns required"))
                val rows = (request["rows"] as? Number)?.toInt()?.coerceIn(4, 120)
                    ?: return json(400, mapOf("error" to "rows required"))
                val emission = console.resize(columns, rows)
                json(200, mapOf("ok" to true, "signal" to emission.signal.toMap(), "patches" to emission.patches.view.map { it.toMap() }))
            }
            method == "GET" && p == EVENTS_PATH -> stream(respond)
            p.startsWith("/api/hermes/terminal") -> json(405, mapOf("error" to "method_not_allowed", "path" to p))
            else -> null
        }
    }

    private fun input(request: Map<String, Any?>): JvmKanbanServer.HttpResponse {
        val command = request["command"] as? String
        if (command != null) {
            val accepted = runCatching { console.prepareCommand(command) }.getOrElse {
                return json(400, mapOf("error" to (it.message ?: "invalid command")))
            }
            if (commands.trySend(accepted).isFailure) {
                console.reject(accepted, "console command queue full or closed")
                return json(503, mapOf("error" to "console command channel closed"))
            }
            return json(202, mapOf("accepted" to true, "signal" to accepted.signal.toMap()))
        }
        val text = request["text"] as? String
        if (text != null) {
            val manual = console.manualText(text, paste = request["paste"] == true)
            return json(200, mapOf("accepted" to true, "input" to manual.input, "signal" to manual.signal.toMap()))
        }
        val keyName = request["key"] as? String
            ?: return json(400, mapOf("error" to "command, text, or key required"))
        val key = runCatching { VtKey.valueOf(keyName.uppercase()) }.getOrNull()
            ?: return json(400, mapOf("error" to "unknown VT220 key $keyName"))
        val manual = console.manualKey(
            key,
            ctrl = request["ctrl"] == true,
            alt = request["alt"] == true,
            shift = request["shift"] == true,
        )
        return json(200, mapOf("accepted" to true, "input" to manual.input, "signal" to manual.signal.toMap()))
    }

    private suspend fun stream(respond: (suspend (ByteArray) -> Unit)?): JvmKanbanServer.HttpResponse? {
        val send = respond ?: return json(200, mapOf("events" to emptyList<Any>()))
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        send(head.toByteArray(StandardCharsets.UTF_8))
        try {
            console.events.collect { event ->
                val payload = when (event) {
                    is HermesVmConsole.Event.Manual -> mapOf("kind" to "manual", "signal" to event.signal.toMap())
                    is HermesVmConsole.Event.Causal -> mapOf(
                        "kind" to "causal",
                        "signal" to event.emission.signal.toMap(),
                        "patches" to event.emission.patches.view.map { it.toMap() },
                        "terminal" to console.terminalMetaMap(),
                    )
                    is HermesVmConsole.Event.StateChanged -> mapOf(
                        "kind" to "state",
                        "state" to event.state.name.lowercase(),
                        "detail" to event.detail,
                    )
                }
                send("data: ${JsonSupport.stringify(payload)}\n\n".toByteArray(StandardCharsets.UTF_8))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // browser disconnected
        }
        return null
    }

    private fun page(): JvmKanbanServer.HttpResponse {
        val bytes = HermesConsoleWire::class.java.classLoader.getResourceAsStream("web/hermes-vt220.html")?.use { it.readBytes() }
            ?: return JvmKanbanServer.HttpResponse(404, """{"error":"asset_missing","resource":"web/hermes-vt220.html"}""")
        return JvmKanbanServer.HttpResponse(200, "", "text/html; charset=utf-8", bytes)
    }

    private fun json(status: Int, value: Map<String, Any?>): JvmKanbanServer.HttpResponse =
        JvmKanbanServer.HttpResponse(status, JsonSupport.stringify(value))

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Map<String, Any?> {
        val body = when {
            "\r\n\r\n" in text -> text.substringAfter("\r\n\r\n")
            "\n\n" in text -> text.substringAfter("\n\n")
            else -> text
        }
        return body.takeIf { it.isNotBlank() }
            ?.let { runCatching { JsonSupport.parse(it) as? Map<String, Any?> }.getOrNull() }
            ?: emptyMap()
    }
}
