package borg.trikeshed.forge.server

import borg.trikeshed.forge.ForgeApp
import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.datetime.Clock
import metrics.FlywheelMetrics

/**
 * ForgeRoutes — commonMain-sourced route table for `runKanbanHttpServerJvm`.
 *
 * Every live usecase of KanbanServerMain + JvmKanbanServer is registered here,
 * even the VM-concordant ones. The *table* lives in commonMain; only the
 * execution of tier=VM_DELEGATED / JVM_ONLY delegates to a live host.
 *
 * Tiers:
 *  - PORTABLE — pure Kotlin, no host. Bakes on GH Pages via ForgeApp.renderHtml / generateForgeAssets.
 *               These are the "more powerful long-term" top-level investments.
 *  - VM_DELEGATED — route table + validation in commonMain, eval/spawn needs live VmHost.
 *                   GH Pages bakes the *projection* (hosts.vms sheet, vm-terminal.html shell).
 *  - JVM_ONLY — Tika/ffmpeg extraction, NIO bind; one `expect` actual. Shape gate stays common.
 */
object ForgeRoutes {

    enum class Tier { PORTABLE, VM_DELEGATED, JVM_ONLY }

    data class RouteMeta(
        val method: String,
        val path: String,
        val tier: Tier,
        val description: String,
    )

    /**
     * The canonical registry — every usecase from triage #1-25.
     * Adding a live path without an entry here is a parity failure (ForgeHostSpecParityTest).
     */
    val registry: List<RouteMeta> = listOf(
        // ── Shell + PWA assets (PORTABLE, baked in docs/) ──
        RouteMeta("GET", "/", Tier.PORTABLE, "Forge shell → ForgeApp.renderHtml + {{SEED}}"),
        RouteMeta("GET", "/index.html", Tier.PORTABLE, "alias of /"),
        RouteMeta("GET", "/styles.css", Tier.PORTABLE, "PWA asset web/styles.css"),
        RouteMeta("GET", "/script.js", Tier.PORTABLE, "PWA asset web/script.js"),
        RouteMeta("GET", "/sw.js", Tier.PORTABLE, "PWA asset web/sw.js"),
        RouteMeta("GET", "/manifest.webmanifest", Tier.PORTABLE, "PWA manifest"),
        RouteMeta("GET", "/icons/*", Tier.PORTABLE, "PWA icons"),
        // ── Board / health / caps (PORTABLE) ──
        RouteMeta("GET", "/api/health", Tier.PORTABLE, "liveness probe {ok,server,now}"),
        RouteMeta("GET", "/api/cap", Tier.PORTABLE, "protocols + capabilities"),
        RouteMeta("GET", "/api/board", Tier.PORTABLE, "ForgeKanbanIngest.loadProjection → {title,userId,items}"),
        RouteMeta("GET", "/api/metrics", Tier.PORTABLE, "FlywheelMetrics Prometheus or JSON (?format=json)"),
        // ── Jules (PORTABLE projection, live driver freshens it) ──
        RouteMeta("GET", "/api/jules/surface", Tier.PORTABLE, "JulesBlackboardAdapter.projectFullSurface + lastReactiveReport"),
        RouteMeta("GET", "/api/jules/events", Tier.PORTABLE, "FlywheelDriver.events SSE (live); baking uses empty stream"),
        // ── Ingest (PORTABLE gate, JVM_ONLY extract actual) ──
        RouteMeta("POST", "/api/submit", Tier.JVM_ONLY, "ingest body → ForgeKanbanIngest.persistMarkdown (Tika actual)"),
        RouteMeta("POST", "/api/donor", Tier.JVM_ONLY, "alias of /api/submit"),
        RouteMeta("POST", "/api/invoke", Tier.PORTABLE, "command queue {commands:[…]} → {accepted,sequence}"),
        RouteMeta("POST", "/ingest", Tier.JVM_ONLY, "ForgeIngestServer alias — X-Forge-Name + persist + Tika"),
        // ── Blackboard (PORTABLE) ──
        RouteMeta("GET", "/blackboard/facts", Tier.PORTABLE, "ConfixBlackboard.changes SSE (bounded ring 256)"),
        RouteMeta("POST", "/blackboard/assert", Tier.PORTABLE, "blackboard.put(k,v,ide)"),
        RouteMeta("GET", "/blackboard/sites", Tier.PORTABLE, "keys().filter pointcut/owner/"),
        // ── VM harness (table portable, execution delegated) ──
        RouteMeta("GET", "/api/vm", Tier.VM_DELEGATED, "VM sheet VM_COLUMNS rows"),
        RouteMeta("POST", "/api/vm/spawn", Tier.VM_DELEGATED, "VmHost.spawn(VmSpec) → {id,tier,terminal}"),
        RouteMeta("POST", "/api/vm/{id}/eval", Tier.VM_DELEGATED, "VmHandle.eval → {cid,value:Teleported}"),
        RouteMeta("POST", "/api/vm/{id}/revoke", Tier.VM_DELEGATED, "VmHost.revoke"),
        RouteMeta("GET", "/api/vm/events", Tier.VM_DELEGATED, "VmHost.events SSE"),
        RouteMeta("GET", "/vm-terminal", Tier.VM_DELEGATED, "VT220 page web/vm-terminal.html"),
        RouteMeta("GET", "/api/vm/{id}/terminal", Tier.VM_DELEGATED, "VmTerminalRegistry snapshot"),
        RouteMeta("POST", "/api/vm/{id}/terminal/input", Tier.VM_DELEGATED, "per-vm Channel<TerminalCommand>"),
        RouteMeta("POST", "/api/vm/{id}/terminal/resize", Tier.VM_DELEGATED, "cols×rows → patches"),
        RouteMeta("GET", "/api/vm/terminals", Tier.VM_DELEGATED, "snapshots list"),
        RouteMeta("GET", "/api/vm/terminal/events", Tier.VM_DELEGATED, "VmTerminalRegistry.events SSE"),
    )

    /** Exact/prefix match helper (query stripped). */
    fun match(method: String, path: String): RouteMeta? {
        val p = path.substringBefore('?')
        return registry.firstOrNull { r ->
            r.method == method && when {
                r.path.endsWith("/*") -> p.startsWith(r.path.removeSuffix("/*"))
                r.path.contains("{id}") -> {
                    val prefix = r.path.substringBefore("{id}")
                    val suffix = r.path.substringAfter("{id}")
                    p.startsWith(prefix) && p.endsWith(suffix) && p.length > prefix.length + suffix.length - 1
                }
                else -> p == r.path
            }
        }
    }

    // ── Portable handlers (no host) ──

    fun healthJson(nowMs: Long = Clock.System.now().toEpochMilliseconds()): HttpForwarderResponse =
        HttpForwarderResponse(200, body = """{"ok":true,"server":"kanban","now":$nowMs}""".encodeToByteArray())

    fun capJson(): HttpForwarderResponse =
        HttpForwarderResponse(200, body = """{"protocols":["Http","Json","Socks5","Tls","Bonjour","Upnp"],"capabilities":["Process@local","Cas@local","Wireproto@lan.localhost"]}""".encodeToByteArray())

    fun boardJson(): HttpForwarderResponse {
        val json = runCatching {
            val reduction = ForgeKanbanIngest.loadProjection("jim")
            JsonSupport.stringify(linkedMapOf(
                "title" to reduction.source.title,
                "userId" to reduction.source.userId,
                "items" to reduction.board.cards.sortedBy { it.order }.map { card ->
                    linkedMapOf("id" to card.id.value, "title" to card.title, "status" to card.columnId.value)
                },
                "correlations" to reduction.correlations.size,
            ))
        }.getOrElse { """{"error":"load_failed","reason":"${it.message}"}""" }
        val status = if ("\"error\"" in json) 500 else 200
        return HttpForwarderResponse(status, body = json.encodeToByteArray())
    }

    fun metricsResponse(acceptJson: Boolean): HttpForwarderResponse =
        if (acceptJson) {
            val json = runCatching { JsonSupport.stringify(FlywheelMetrics.toJsonMap()) }
                .getOrElse { """{"error":"metrics_unavailable"}""" }
            HttpForwarderResponse(200, body = json.encodeToByteArray())
        } else {
            val prom = runCatching { FlywheelMetrics.toPrometheusFormat() }
                .getOrElse { "# ERROR: metrics unavailable\n" }
            HttpForwarderResponse(200, headers = mapOf("Content-Type" to "text/plain; version=0.0.4; charset=utf-8"), body = prom.encodeToByteArray())
        }

    fun invokeJson(payload: String): HttpForwarderResponse {
        val raw = payload.substringAfter("\r\n\r\n", "").ifEmpty { payload.substringAfter("\n\n", "") }
        if (raw.isBlank()) return HttpForwarderResponse(400, body = """{"error":"empty_body"}""".encodeToByteArray())
        val parsed = runCatching { JsonSupport.parse(raw) }.getOrNull()
            ?: return HttpForwarderResponse(400, body = """{"error":"bad_json"}""".encodeToByteArray())
        val commands: List<*> = when (parsed) {
            is Map<*, *> -> (parsed["commands"] as? List<*>) ?: listOf(parsed)
            is List<*> -> parsed
            else -> emptyList<Any?>()
        }
        // sequence is ephemeral in commonMain; jvm side increments atomically. Here we just echo size.
        val keys = commands.mapNotNull { (it as? Map<*, *>)?.get("idempotencyKey") as? String }
        val json = JsonSupport.stringify(linkedMapOf("ok" to true, "accepted" to commands.size, "idempotencyKeys" to keys))
        return HttpForwarderResponse(202, body = json.encodeToByteArray())
    }

    fun shellHtml(userId: String = "jim"): HttpForwarderResponse {
        val html = runCatching { ForgeApp.renderHtml(userId = userId) }
            .getOrElse { ex -> "<html><body><h1>Forge shell failed</h1><pre>${ex.message}</pre></body></html>" }
        return HttpForwarderResponse(200, headers = mapOf("Content-Type" to "text/html; charset=utf-8"), body = html.encodeToByteArray())
    }
}
