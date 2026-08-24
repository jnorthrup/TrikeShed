package borg.trikeshed.forge.server

import borg.trikeshed.forge.sheet.SheetColumn
import borg.trikeshed.forge.sheet.sheetSeed
import borg.trikeshed.lcnc.media.ManualMediaInput
import borg.trikeshed.lcnc.media.toMap
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.lib.view
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.vm.VM_COLUMNS
import borg.trikeshed.vm.VmBudget
import borg.trikeshed.vm.VmEvent
import borg.trikeshed.vm.VmHost
import borg.trikeshed.vm.VmSpec
import borg.trikeshed.vm.VmTrust
import borg.trikeshed.vm.HypervisorVmHost
import borg.trikeshed.vm.VmTerminalEvent
import borg.trikeshed.vm.VmTerminalRegistry
import borg.trikeshed.vm.VmTerminalSession
import borg.trikeshed.vm.vmFacetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * `/api/vm/…` over the common VM API — the seat-of-the-pants routes the OpenAPI sink will later be
 * written from (see [ROUTES]).
 *
 *   GET  /api/vm                 → the VM sheet (`VM_COLUMNS` rows; what the Host view renders)
 *   POST /api/vm/spawn           {id, facet, trust?, statements?, wallMillis?} → {id, tier}
 *   POST /api/vm/{id}/eval       {source, name?} → {cid, value: <canonical Teleported>}
 *   POST /api/vm/{id}/revoke     {reason?} → {ok}
 *   GET  /api/vm/events          SSE of VmEvent (replays the host's recent buffer first)
 *   GET  /vm-terminal?id=…       VT220 web terminal with one tab per VM/process
 *   GET  /api/vm/{id}/terminal   one terminal snapshot
 *   POST /api/vm/{id}/terminal/input  {text, mode: eval|stdin}
 *   GET  /api/vm/terminal/events global terminal patch SSE, keyed by vmId
 */
class VmWire(
    private val host: VmHost,
    private val scope: CoroutineScope,
    private val terminals: VmTerminalRegistry? = (host as? HypervisorVmHost)?.terminals,
) {

    private data class TerminalCommand(val id: String, val input: ManualMediaInput, val source: String)
    private val commandChannels = ConcurrentHashMap<String, Channel<TerminalCommand>>()

    companion object {
        const val EVENTS_PATH = "/api/vm/events"
        const val TERMINAL_EVENTS_PATH = "/api/vm/terminal/events"
        val STREAMING: Set<String> = setOf(EVENTS_PATH, TERMINAL_EVENTS_PATH)
        private val VM_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        val ROUTES: List<Pair<String, String>> = listOf(
            "GET" to "/api/vm", "POST" to "/api/vm/spawn", "POST" to "/api/vm/{id}/eval", "POST" to "/api/vm/{id}/revoke", "GET" to EVENTS_PATH,
        )
        private val vmColumns = VM_COLUMNS.map { SheetColumn(it.first, it.second.name) }
    }

    private fun json(status: Int, map: Map<String, Any?>) = JvmKanbanServer.HttpResponse(status, JsonSupport.stringify(map))
    private fun body(text: String): String = when {
        "\r\n\r\n" in text -> text.substringAfter("\r\n\r\n")
        "\n\n" in text -> text.substringAfter("\n\n")
        else -> text
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Map<String, Any?> =
        body(text).takeIf { it.isNotBlank() }?.let { runCatching { JsonSupport.parse(it) as? Map<String, Any?> }.getOrNull() } ?: emptyMap()

    fun sheetJson(): String = JsonSupport.stringify(sheetSeed("vms", "Sub-VMs", host.rows(), columns = vmColumns).toMap())

    suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        if (method == "GET" && (p == "/vm-terminal" || p == "/vm-terminal/")) return terminalPage()
        if (!p.startsWith("/api/vm")) return null
        return when {
            method == "GET" && p == "/api/vm" -> JvmKanbanServer.HttpResponse(200, sheetJson())
            method == "GET" && p == "/api/vm/terminals" -> json(200, mapOf("terminals" to terminals?.snapshots().orEmpty()))
            method == "GET" && p == TERMINAL_EVENTS_PATH -> terminalEvents(respond)

            method == "GET" && p.startsWith("/api/vm/") && p.endsWith("/terminal") -> {
                val id = p.removePrefix("/api/vm/").removeSuffix("/terminal")
                val terminal = terminals?.get(id) ?: return json(404, mapOf("error" to "no terminal for '$id'"))
                json(200, terminal.snapshotMap())
            }

            method == "POST" && p.startsWith("/api/vm/") && p.endsWith("/terminal/input") -> {
                val id = p.removePrefix("/api/vm/").removeSuffix("/terminal/input")
                terminalInput(id, parse(text))
            }

            method == "POST" && p.startsWith("/api/vm/") && p.endsWith("/terminal/resize") -> {
                val id = p.removePrefix("/api/vm/").removeSuffix("/terminal/resize")
                val terminal = terminals?.get(id) ?: return json(404, mapOf("error" to "no terminal for '$id'"))
                val request = parse(text)
                val columns = (request["columns"] as? Number)?.toInt()?.coerceIn(20, 300)
                    ?: return json(400, mapOf("error" to "columns required"))
                val rows = (request["rows"] as? Number)?.toInt()?.coerceIn(4, 120)
                    ?: return json(400, mapOf("error" to "rows required"))
                val emission = terminal.resize(columns, rows)
                json(200, mapOf("ok" to true, "signal" to emission.signal.toMap(), "patches" to emission.patches.view.map { it.toMap() }))
            }

            method == "POST" && p == "/api/vm/spawn" -> {
                val req = parse(text)
                val id = (req["id"] as? String)?.takeIf { it.isNotBlank() } ?: return json(400, mapOf("error" to "id required"))
                if (!VM_ID.matches(id)) return json(400, mapOf("error" to "id must match ${VM_ID.pattern}"))
                val facet = vmFacetOf(req["facet"] as? String ?: "js") ?: return json(400, mapOf("error" to "unknown facet ${req["facet"]}"))
                val trust = if ((req["trust"] as? String)?.uppercase() == "UNTRUSTED") VmTrust.UNTRUSTED else VmTrust.OWN
                val budget = VmBudget(
                    statements = (req["statements"] as? Number)?.toLong() ?: 0,
                    wallMillis = (req["wallMillis"] as? Number)?.toLong() ?: 0,
                )
                runCatching { host.spawn(VmSpec(id, facet, trust, budget)) }
                    .fold({ json(200, mapOf(
                        "id" to it.id,
                        "facet" to it.facet.id,
                        "tier" to it.tier,
                        "terminal" to "/vm-terminal?id=${it.id}",
                    )) }, { json(409, mapOf("error" to (it.message ?: it.toString()))) })
            }

            method == "POST" && p.startsWith("/api/vm/") && p.endsWith("/eval") -> {
                val id = p.removePrefix("/api/vm/").removeSuffix("/eval")
                val h = host.get(id) ?: return json(404, mapOf("error" to "no vm '$id'"))
                val req = parse(text)
                val source = req["source"] as? String ?: return json(400, mapOf("error" to "source required"))
                val terminal = terminals?.get(id)
                val manual = terminal?.prepare(source)
                if (manual != null) terminal.begin(manual)
                runCatching { h.eval(source, req["name"] as? String ?: "<eval>") }
                    .fold({ result ->
                        if (manual != null) terminal.complete(result, manual)
                        JvmKanbanServer.HttpResponse(200, """{"id":"$id","cid":"${result.cid.hex}","value":${result.canonical()}}""")
                    }, { failure ->
                        if (manual != null) terminal.fail(failure, manual)
                        json(422, mapOf("error" to (failure.message ?: failure.toString())))
                    })
            }

            method == "POST" && p.startsWith("/api/vm/") && p.endsWith("/revoke") -> {
                val id = p.removePrefix("/api/vm/").removeSuffix("/revoke")
                if (host.get(id) == null) return json(404, mapOf("error" to "no vm '$id'"))
                host.revoke(id, parse(text)["reason"] as? String ?: "api")
                commandChannels.remove(id)?.close()
                json(200, mapOf("ok" to true, "id" to id))
            }

            method == "GET" && p == EVENTS_PATH -> {
                val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
                respond?.invoke(headers.toByteArray(StandardCharsets.UTF_8)) ?: return JvmKanbanServer.HttpResponse(200, "[]")
                try {
                    host.events.collect { ev: VmEvent ->
                        val data = "data: ${JsonSupport.stringify(ev.toMap())}\r\n\r\n"
                        try { respond.invoke(data.toByteArray(StandardCharsets.UTF_8)) } catch (_: Throwable) { throw kotlinx.coroutines.CancellationException("client disconnected") }
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                }
                null
            }

            p.startsWith("/api/vm") -> json(405, mapOf("error" to "method_not_allowed", "path" to p))
            else -> null
        }
    }

    private fun terminalInput(id: String, request: Map<String, Any?>): JvmKanbanServer.HttpResponse {
        val terminal = terminals?.get(id) ?: return json(404, mapOf("error" to "no terminal for '$id'"))
        val text = request["text"] as? String ?: request["command"] as? String
            ?: return json(400, mapOf("error" to "text required"))
        if ((request["mode"] as? String)?.lowercase() == "stdin") {
            val manual = terminal.pushInput(text)
            return json(200, mapOf("accepted" to true, "mode" to "stdin", "signal" to manual.signal.toMap()))
        }
        val manual = runCatching { terminal.prepare(text) }.getOrElse {
            return json(400, mapOf("error" to (it.message ?: "invalid command")))
        }
        if (commandChannel(id).trySend(TerminalCommand(id, manual, text)).isFailure) {
            terminal.fail(IllegalStateException("terminal command queue full or closed"), manual)
            return json(503, mapOf("error" to "terminal command queue full or closed"))
        }
        return json(202, mapOf("accepted" to true, "mode" to "eval", "signal" to manual.signal.toMap()))
    }

    private fun commandChannel(id: String): Channel<TerminalCommand> = commandChannels.computeIfAbsent(id) {
        Channel<TerminalCommand>(capacity = 64).also { channel ->
            scope.launch(Dispatchers.IO) { for (command in channel) executeTerminal(command) }
        }
    }

    private fun executeTerminal(command: TerminalCommand) {
        val terminal = terminals?.get(command.id) ?: return
        val handle = host.get(command.id)
        if (handle == null) {
            terminal.fail(IllegalStateException("no vm '${command.id}'"), command.input)
            return
        }
        terminal.begin(command.input)
        when (command.source.trim()) {
            ":clear" -> {
                terminal.systemOutput("\u001b[2J\u001b[H", command.input.signal.id)
                terminal.complete(borg.trikeshed.vm.Teleported.Null, command.input)
            }
            ":status" -> {
                terminal.systemOutput("${handle.id} ${handle.facet.id} ${handle.tier} alive=${handle.isAlive} ${handle.stats()}\r\n", command.input.signal.id)
                terminal.complete(borg.trikeshed.vm.Teleported.Null, command.input)
            }
            else -> runCatching { handle.eval(command.source, "terminal") }
                .fold({ terminal.complete(it, command.input) }, { terminal.fail(it, command.input) })
        }
    }

    private suspend fun terminalEvents(respond: (suspend (ByteArray) -> Unit)?): JvmKanbanServer.HttpResponse? {
        val registry = terminals ?: return json(200, mapOf("events" to emptyList<Any>()))
        val send = respond ?: return json(200, mapOf("events" to emptyList<Any>()))
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        send(headers.toByteArray(StandardCharsets.UTF_8))
        try {
            registry.events.collect { event ->
                val payload = when (event) {
                    is VmTerminalEvent.Manual -> mapOf("kind" to "manual", "vmId" to event.vmId, "signal" to event.signal.toMap())
                    is VmTerminalEvent.Causal -> mapOf(
                        "kind" to "causal",
                        "vmId" to event.vmId,
                        "signal" to event.emission.signal.toMap(),
                        "patches" to event.emission.patches.view.map { it.toMap() },
                        "terminal" to registry[event.vmId]?.terminalMetaMap(),
                    )
                    is VmTerminalEvent.Phase -> mapOf("kind" to "phase", "vmId" to event.vmId, "phase" to event.phase, "detail" to event.detail)
                }
                send("data: ${JsonSupport.stringify(payload)}\n\n".toByteArray(StandardCharsets.UTF_8))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // browser disconnected
        }
        return null
    }

    private fun terminalPage(): JvmKanbanServer.HttpResponse {
        val bytes = VmWire::class.java.classLoader.getResourceAsStream("web/vm-terminal.html")?.use { it.readBytes() }
            ?: return JvmKanbanServer.HttpResponse(404, """{"error":"asset_missing","resource":"web/vm-terminal.html"}""")
        return JvmKanbanServer.HttpResponse(200, "", "text/html; charset=utf-8", bytes)
    }
}
