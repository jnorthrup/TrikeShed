package borg.trikeshed.forge.server

import borg.trikeshed.parse.reify
import borg.trikeshed.parse.jsonOf

import borg.trikeshed.hermes.HermesVmConsole
import borg.trikeshed.hermes.HermesConsoleElement
import borg.trikeshed.lcnc.media.ManualMediaInput
import borg.trikeshed.lcnc.media.toMap
import borg.trikeshed.lib.view
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.terminal.VtKey
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** HTTP/SSE control surface for the real supervised Hermes VM xterm-256color panel. */
class HermesConsoleWire(
    console: HermesVmConsole,
    private val scope: CoroutineScope,
    private val consoleFactory: ((String) -> HermesVmConsole)? = null,
) {
    private class Panel(val id: String, val console: HermesVmConsole, val commands: HermesConsoleElement)

    private val panels = linkedMapOf(DEFAULT_PANEL to panel(DEFAULT_PANEL, console))
    private val registryLock = Any()
    private val draining = Mutex()
    @Volatile private var closed = false

    private fun panel(id: String, console: HermesVmConsole): Panel {
        val commands = HermesConsoleElement(
            CoroutineScope(scope.coroutineContext + Dispatchers.IO),
            open = { console.open() },
            execute = { console.execute(it, echo = false) },
            failed = { command, failure ->
                if (command != null) console.reject(command, failure.message ?: "console execution failed")
                else console.fail(failure)
            },
            reset = { console.reset(it) },
        )
        // The server-side line editor submits here, so a keystroke POST returns before the turn runs.
        console.commandSink = { accepted ->
            if (!commands.submit(accepted)) console.reject(accepted, "console command queue full or closed")
        }
        return Panel(id, console, commands)
    }

    fun open(): Boolean = synchronized(registryLock) { !closed && panels.getValue(DEFAULT_PANEL).commands.open() }

    suspend fun drain() = withContext(NonCancellable) {
        draining.withLock {
            val owned = synchronized(registryLock) {
                if (closed) return@withLock
                closed = true
                panels.values.toList()
            }
            var failure: Throwable? = null
            fun record(error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
            for (panel in owned) {
                try { panel.commands.drain() } catch (error: Throwable) { record(error) }
                try { withContext(Dispatchers.IO) { panel.console.close() } } catch (error: Throwable) { record(error) }
            }
            failure?.let { throw it }
        }
    }

    companion object {
        const val DEFAULT_PANEL = "default"
        const val PANEL_LIMIT = 8
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
        if (method == "GET" && (p == "/hermes" || p == "/hermes/")) return page()
        if (!p.startsWith("/api/hermes/terminal")) return null
        if (p == "/api/hermes/terminal/panels") {
            return when (method) {
                "GET" -> json(200, synchronized(registryLock) {
                    mapOf("panels" to panels.values.map { mapOf("id" to it.id, "state" to it.console.state.name.lowercase()) },
                        "limit" to PANEL_LIMIT)
                })
                "POST" -> createPanel()
                else -> json(405, mapOf("error" to "method_not_allowed", "path" to p))
            }
        }
        val id = try { requestedPanel(path) } catch (_: IllegalArgumentException) {
            return json(400, mapOf("error" to "invalid panel id"))
        }
        val panel = synchronized(registryLock) { panels[id] }
            ?: return json(404, mapOf("error" to "unknown panel", "panelId" to id))
        val console = panel.console
        if (method == "POST" && (closed || !panel.commands.accepting)) {
            return json(503, mapOf("error" to "console draining or closed"))
        }
        return when {
            method == "GET" && p == "/api/hermes/terminal" -> json(200, console.snapshotMap() + ("panelId" to id))
            method == "POST" && p == "/api/hermes/terminal/open" -> {
                if (panel.commands.open()) json(202, mapOf("accepted" to true, "panelId" to id, "state" to console.state.name.lowercase()))
                else json(503, mapOf("error" to "console command queue full or closed"))
            }
            method == "POST" && p == "/api/hermes/terminal/reset" -> {
                val request = parse(text)
                val resume = request["resume"] as? Boolean ?: true
                if (panel.commands.reset(resume)) json(202, mapOf("accepted" to true, "panelId" to id, "resume" to resume))
                else json(503, mapOf("error" to "console command queue full or closed"))
            }
            method == "POST" && p == "/api/hermes/terminal/input" -> try {
                input(panel, parse(text))
            } catch (failure: IllegalStateException) {
                json(409, mapOf("error" to (failure.message ?: "console unavailable"), "state" to console.state.name.lowercase()))
            }
            method == "POST" && p == "/api/hermes/terminal/resize" -> {
                val request = parse(text)
                val columns = (request["columns"] as? Number)?.toInt()?.coerceIn(20, 300)
                    ?: return json(400, mapOf("error" to "columns required"))
                val rows = (request["rows"] as? Number)?.toInt()?.coerceIn(4, 120)
                    ?: return json(400, mapOf("error" to "rows required"))
                val emission = withContext(Dispatchers.IO) { console.resize(columns, rows) }
                json(200, mapOf("ok" to true, "signal" to emission.signal.toMap(), "patches" to emission.patches.view.map { it.toMap() }))
            }
            method == "GET" && p == EVENTS_PATH -> stream(panel, respond)
            p.startsWith("/api/hermes/terminal") -> json(405, mapOf("error" to "method_not_allowed", "path" to p))
            else -> null
        }
    }

    private suspend fun createPanel(): JvmKanbanServer.HttpResponse = withContext(Dispatchers.IO) {
        synchronized(registryLock) {
            if (closed) return@withContext json(503, mapOf("error" to "console draining or closed"))
            if (panels.size >= PANEL_LIMIT) return@withContext json(409, mapOf("error" to "panel limit reached", "limit" to PANEL_LIMIT))
            val factory = consoleFactory ?: return@withContext json(501, mapOf("error" to "independent panels unavailable"))
            val id = "panel-" + UUID.randomUUID().toString()
            val console = try { factory(id) } catch (failure: Throwable) {
                return@withContext json(500, mapOf("error" to (failure.message ?: "panel creation failed")))
            }
            val owner = try { panel(id, console) } catch (failure: Throwable) {
                try { console.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
            panels[id] = owner
            if (!owner.commands.open()) return@withContext json(503, mapOf("error" to "console command queue full or closed", "panelId" to id))
            json(202, mapOf("id" to id, "panelId" to id, "url" to "/hermes?panel=$id", "accepted" to true,
                "state" to console.state.name.lowercase()))
        }
    }

    private fun requestedPanel(path: String): String {
        val value = path.substringAfter('?', "").split('&').firstOrNull { it.substringBefore('=') == "panel" }
            ?.substringAfter('=', "") ?: return DEFAULT_PANEL
        val id = URLDecoder.decode(value, StandardCharsets.UTF_8)
        require(id.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        return id
    }

    private fun input(panel: Panel, request: Map<String, Any?>): JvmKanbanServer.HttpResponse {
        val console = panel.console
        val commands = panel.commands
        val command = request["command"] as? String
        if (command != null) {
            val accepted = runCatching { console.prepareCommand(command) }.getOrElse {
                return json(400, mapOf("error" to (it.message ?: "invalid command")))
            }
            if (!commands.submit(accepted)) {
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

    private suspend fun stream(panel: Panel, respond: (suspend (ByteArray) -> Unit)?): JvmKanbanServer.HttpResponse? {
        val console = panel.console
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
                        "terminal" to event.terminal,
                    )
                    is HermesVmConsole.Event.StateChanged -> mapOf(
                        "kind" to "state",
                        "state" to event.state.name.lowercase(),
                        "detail" to event.detail,
                        "boot" to event.boot?.toMap(),
                    )
                }
                send("data: ${jsonOf(payload + ("panelId" to panel.id))}\n\n".toByteArray(StandardCharsets.UTF_8))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // browser disconnected
        }
        return null
    }

    private fun page(): JvmKanbanServer.HttpResponse {
        val bytes = HermesConsoleWire::class.java.classLoader.getResourceAsStream("web/hermes-xterm.html")?.use { it.readBytes() }
            ?: return JvmKanbanServer.HttpResponse(404, """{"error":"asset_missing","resource":"web/hermes-xterm.html"}""")
        return JvmKanbanServer.HttpResponse(200, "", "text/html; charset=utf-8", bytes)
    }

    private fun json(status: Int, value: Map<String, Any?>): JvmKanbanServer.HttpResponse =
        JvmKanbanServer.HttpResponse(status, jsonOf(value))

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Map<String, Any?> {
        val body = when {
            "\r\n\r\n" in text -> text.substringAfter("\r\n\r\n")
            "\n\n" in text -> text.substringAfter("\n\n")
            else -> text
        }
        return body.takeIf { it.isNotBlank() }
            ?.let { runCatching { reify(it) as? Map<String, Any?> }.getOrNull() }
            ?: emptyMap()
    }
}
