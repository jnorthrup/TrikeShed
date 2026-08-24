package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchReportEvent
import borg.trikeshed.couch.CouchReportReactorElement
import borg.trikeshed.couch.CouchStore
import borg.trikeshed.graal.vitals.JvmVitals
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

/**
 * GraalWire — the Graal console: a web console with APIs, riding the same listener as everything
 * else ("port" of the PWA in the retained sense — console + APIs + CLI, not a copy of the shell).
 *
 *   GET /graal                    the console page (classpath `web/graal.html`)
 *   GET /graal.webmanifest        install manifest (reuses the forge icons)
 *   GET /api/graal/vitals         [JvmVitals.snapshot] + pointcut route summary
 *   GET /api/graal/pointcuts      every `pointcut/…` document as a route row
 *   GET /api/graal/map            the whole store as compact `[id, bytes]` rows — the RTS terrain
 *   GET /api/graal/events         SSE: compile / deopt / gc / cpu flourishes, plus store commits
 *
 * CLI twin: `borg.trikeshed.graal.vitals.GraalConsoleCli` (vitals | watch) reads the same
 * instrument cluster for a JVM you are inside of.
 */
class GraalWire(
    private val vitals: JvmVitals,
    private val couchStore: CouchStore?,
    private val report: CouchReportReactorElement?,
    private val scope: CoroutineScope,
) {
    companion object {
        const val EVENTS_PATH = "/api/graal/events"
        val STREAMING: Set<String> = setOf(EVENTS_PATH)
    }

    suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        return when {
            method == "GET" && (p == "/graal" || p == "/graal/") -> page()
            method == "GET" && p == "/graal.webmanifest" -> JvmKanbanServer.HttpResponse(200, MANIFEST, "application/manifest+json; charset=utf-8")
            method == "GET" && p == "/api/graal/vitals" -> JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(vitals.snapshot() + ("pointcuts" to pointcutSummary())))
            method == "GET" && p == "/api/graal/pointcuts" -> JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(mapOf("routes" to pointcutRoutes())))
            method == "GET" && p == "/api/graal/map" -> JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(mapMap()))
            method == "GET" && p == EVENTS_PATH && respond != null -> { stream(respond); JvmKanbanServer.HttpResponse(200, "") }
            else -> null
        }
    }

    private fun page(): JvmKanbanServer.HttpResponse {
        val bytes = GraalWire::class.java.classLoader.getResourceAsStream("web/graal.html")?.use { it.readBytes() }
            ?: return JvmKanbanServer.HttpResponse(404, """{"error":"asset_missing","resource":"web/graal.html"}""")
        return JvmKanbanServer.HttpResponse(200, "", "text/html; charset=utf-8", bytes)
    }

    /**
     * The 30k-foot terrain: every live document as `[id, bytes]`. The console builds the
     * prefix-tree territories client-side and lays them out as a zoomable treemap; bytes come
     * from the attachment `length` field where present, else the field count as a stand-in mass.
     */
    private fun mapMap(): Map<String, Any?> {
        val rows = couchStore?.all().orEmpty()
            .filter { d -> d.fields.none { it.name == "_deleted" && it.value == true } }
            .map { d ->
                val len = (d.fields.firstOrNull { it.name == "length" }?.value as? String)?.toLongOrNull()
                listOf(d.id, len ?: (d.fields.size.toLong() * 64))
            }
        return mapOf("rows" to rows, "at" to System.currentTimeMillis())
    }

    // ── pointcut routes: the `pointcut/…` plane of the store ─────

    private fun pointcutDocs() = couchStore?.all().orEmpty().filter { it.id.startsWith("pointcut/") }

    private fun pointcutSummary(): Map<String, Any?> {
        val docs = pointcutDocs()
        return mapOf(
            "routes" to docs.size,
            "byFacet" to docs.groupingBy { d -> d.fields.firstOrNull { it.name == "facet" }?.value?.toString() ?: "?" }.eachCount(),
        )
    }

    private fun pointcutRoutes(): List<Map<String, Any?>> = pointcutDocs().map { d ->
        fun f(n: String): Any? = d.fields.firstOrNull { it.name == n }?.value
        @Suppress("UNCHECKED_CAST")
        val coord = f("coordinate") as? Map<String, Any?> ?: emptyMap()
        mapOf(
            "route" to d.id,
            "facet" to f("facet"),
            "property" to f("property"),
            "value" to f("value"),
            "mark" to f("mark"),
            "className" to coord["className"],
            "methodName" to coord["methodName"],
            "bci" to coord["bytecodeOffset"],
        )
    }.sortedBy { it["route"].toString() }

    // ── the flourish feed ────────────────────────────────────────

    private suspend fun stream(respond: suspend (ByteArray) -> Unit) {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        respond(head.toByteArray(StandardCharsets.UTF_8))
        val out = Channel<String>(capacity = 256)
        val jobs = mutableListOf(
            vitals.events.onEach { e ->
                out.trySend(JsonSupport.stringify(mapOf("kind" to e.kind, "at" to e.atMs) + e.detail.mapKeys { (k, _) -> k }))
            }.launchIn(scope),
        )
        report?.let { r ->
            jobs += r.events.onEach { e ->
                if (e is CouchReportEvent.Committed) {
                    out.trySend(JsonSupport.stringify(mapOf("kind" to "commit", "id" to e.docId, "seq" to e.seq, "deleted" to e.deleted, "at" to e.timestampMs)))
                }
            }.launchIn(scope)
        }
        try {
            for (line in out) {
                respond("data: $line\n\n".toByteArray(StandardCharsets.UTF_8))
            }
        } catch (_: Throwable) {
            // client went away — the normal end of a feed
        } finally {
            jobs.forEach { it.cancel() }
            out.close()
        }
    }
}

private val MANIFEST = """
{
  "name": "Graal Console",
  "short_name": "Graal",
  "start_url": "/graal",
  "display": "standalone",
  "background_color": "#0b0e14",
  "theme_color": "#0b0e14",
  "icons": [
    { "src": "/icons/forge-icon.svg", "sizes": "any", "type": "image/svg+xml" },
    { "src": "/icons/forge-icon-maskable.svg", "sizes": "any", "type": "image/svg+xml", "purpose": "maskable" }
  ]
}
""".trimIndent()
