package borg.trikeshed.forge.server

import borg.trikeshed.forge.sheet.SheetColumn
import borg.trikeshed.forge.sheet.sheetSeed
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.vm.VM_COLUMNS
import borg.trikeshed.vm.VmBudget
import borg.trikeshed.vm.VmEvent
import borg.trikeshed.vm.VmHost
import borg.trikeshed.vm.VmSpec
import borg.trikeshed.vm.VmTrust
import borg.trikeshed.vm.vmFacetOf
import kotlinx.coroutines.CoroutineScope
import java.nio.charset.StandardCharsets

/**
 * `/api/vm/…` over the common VM API — the seat-of-the-pants routes the OpenAPI sink will later be
 * written from (see [ROUTES]).
 *
 *   GET  /api/vm                 → the VM sheet (`VM_COLUMNS` rows; what the Host view renders)
 *   POST /api/vm/spawn           {id, facet, trust?, statements?, wallMillis?} → {id, tier}
 *   POST /api/vm/{id}/eval       {source, name?} → {cid, value: <canonical Teleported>}
 *   POST /api/vm/{id}/revoke     {reason?} → {ok}
 *   GET  /api/vm/events          SSE of VmEvent (replays the host's recent buffer first)
 */
class VmWire(private val host: VmHost, @Suppress("unused") private val scope: CoroutineScope) {

    companion object {
        const val EVENTS_PATH = "/api/vm/events"
        val ROUTES: List<Pair<String, String>> = listOf(
            "GET" to "/api/vm", "POST" to "/api/vm/spawn", "POST" to "/api/vm/{id}/eval", "POST" to "/api/vm/{id}/revoke", "GET" to EVENTS_PATH,
        )
        private val vmColumns = VM_COLUMNS.map { SheetColumn(it.first, it.second.name) }
    }

    private fun json(status: Int, map: Map<String, Any?>) = JvmKanbanServer.HttpResponse(status, JsonSupport.stringify(map))
    private fun body(text: String): String = text.substringAfter("\r\n\r\n", "").ifEmpty { text.substringAfter("\n\n", "") }

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Map<String, Any?> =
        body(text).takeIf { it.isNotBlank() }?.let { runCatching { JsonSupport.parse(it) as? Map<String, Any?> }.getOrNull() } ?: emptyMap()

    fun sheetJson(): String = JsonSupport.stringify(sheetSeed("vms", "Sub-VMs", host.rows(), columns = vmColumns).toMap())

    suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        if (!p.startsWith("/api/vm")) return null
        return when {
            method == "GET" && p == "/api/vm" -> JvmKanbanServer.HttpResponse(200, sheetJson())

            method == "POST" && p == "/api/vm/spawn" -> {
                val req = parse(text)
                val id = (req["id"] as? String)?.takeIf { it.isNotBlank() } ?: return json(400, mapOf("error" to "id required"))
                val facet = vmFacetOf(req["facet"] as? String ?: "js") ?: return json(400, mapOf("error" to "unknown facet ${req["facet"]}"))
                val trust = if ((req["trust"] as? String)?.uppercase() == "UNTRUSTED") VmTrust.UNTRUSTED else VmTrust.OWN
                val budget = VmBudget(
                    statements = (req["statements"] as? Number)?.toLong() ?: 0,
                    wallMillis = (req["wallMillis"] as? Number)?.toLong() ?: 0,
                )
                runCatching { host.spawn(VmSpec(id, facet, trust, budget)) }
                    .fold({ json(200, mapOf("id" to it.id, "facet" to it.facet.id, "tier" to it.tier)) }, { json(409, mapOf("error" to (it.message ?: it.toString()))) })
            }

            method == "POST" && p.startsWith("/api/vm/") && p.endsWith("/eval") -> {
                val id = p.removePrefix("/api/vm/").removeSuffix("/eval")
                val h = host.get(id) ?: return json(404, mapOf("error" to "no vm '$id'"))
                val req = parse(text)
                val source = req["source"] as? String ?: return json(400, mapOf("error" to "source required"))
                runCatching { h.eval(source, req["name"] as? String ?: "<eval>") }
                    .fold({ JvmKanbanServer.HttpResponse(200, """{"id":"$id","cid":"${it.cid.hex}","value":${it.canonical()}}""") },
                        { json(422, mapOf("error" to (it.message ?: it.toString()))) })
            }

            method == "POST" && p.startsWith("/api/vm/") && p.endsWith("/revoke") -> {
                val id = p.removePrefix("/api/vm/").removeSuffix("/revoke")
                if (host.get(id) == null) return json(404, mapOf("error" to "no vm '$id'"))
                host.revoke(id, parse(text)["reason"] as? String ?: "api")
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
}
