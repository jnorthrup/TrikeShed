package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.InvokeLowering
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.torrent.percentEncode

/**
 * The daemon half of the node types the canvas could only reach by `fetch`.
 *
 * These types existed twice: as contracts, and as small JS wrappers in the
 * browser that GET the daemon's own routes. Anything running headless — a
 * webhook delivery, `/api/lcnc/run`, a scheduled program — hit
 * `LcncUnknownNodeType` instead, which is why `preset-hermes`, the flagship
 * example, could not run outside a browser tab at all.
 *
 * The fix is deliberately NOT a second implementation. Each runner calls the
 * daemon's own handler in process through [Call], so the board, the
 * blackboard, vitals and the rest keep exactly one author and the canvas and
 * the daemon cannot drift into answering differently.
 */
object SurfaceNodes {

    /** In-process dispatch to the daemon's own routes: (method, path, body) → parsed JSON. */
    fun interface Call {
        suspend operator fun invoke(method: String, path: String, body: Any?): Any?
    }

    /** `r.field ?: fallback` — the same unwrap the browser wrappers do. */
    private fun field(response: Any?, name: String): Any? =
        (response as? Map<*, *>)?.get(name)

    fun registry(call: Call): Map<String, LcncNodeRunner> = mapOf(

        // board.get / board.view are NOT here: they are units over
        // BoardStoreElement (LcncKanbanExperience.registry). A self-fetch of
        // /api/board was a no-code node playing no-function, and its `alerts`
        // output was a constant [] because the route never carried one.
        "http.get" to boundLcnc(SurfaceCallKey(call)) { service, node, _ ->
            val call = service.value
            mapOf("json" to call("GET", node.params["path"]?.takeIf { it.isNotBlank() } ?: "/api/health", null))
        },
        "http.post" to boundLcnc(SurfaceCallKey(call)) { service, node, inputs ->
            val call = service.value
            val body = inputs["body"] ?: inputs["body?"]
            mapOf("json" to call("POST", node.params["path"]?.takeIf { it.isNotBlank() } ?: "/api/submit", body))
        },
        "blackboard.facts" to boundLcnc(SurfaceCallKey(call)) { service, _, _ ->
            val call = service.value
            mapOf("facts" to call("GET", "/blackboard/facts", null))
        },
        "blackboard.board" to boundLcnc(SurfaceCallKey(call)) { service, _, _ ->
            val call = service.value
            mapOf("board" to call("GET", "/blackboard/board", null))
        },
        "blackboard.sites" to boundLcnc(SurfaceCallKey(call)) { service, node, _ ->
            val call = service.value
            val owner = node.params["owner"].orEmpty()
            mapOf("sites" to call("GET", "/blackboard/sites" + if (owner.isBlank()) "" else "?owner=$owner", null))
        },
        "graal.vitals" to boundLcnc(SurfaceCallKey(call)) { service, _, _ ->
            val call = service.value
            mapOf("json" to call("GET", "/api/graal/vitals", null))
        },
        "graal.heap" to boundLcnc(SurfaceCallKey(call)) { service, _, _ ->
            val call = service.value
            mapOf("heap" to call("GET", "/api/graal/heap", null))
        },
        "vms.list" to boundLcnc(SurfaceCallKey(call)) { service, _, _ ->
            val call = service.value
            val r = call("GET", "/api/vm", null)
            mapOf("rows" to (field(r, "rows") ?: r))
        },
        "pointcut.routes" to boundLcnc(SurfaceCallKey(call)) { service, _, _ ->
            val call = service.value
            val r = call("GET", "/api/graal/pointcuts", null)
            mapOf("routes" to (field(r, "routes") ?: r))
        },
        "panels.list" to boundLcnc(SurfaceCallKey(call)) { service, _, _ ->
            val call = service.value
            val r = call("GET", "/api/panels", null)
            mapOf("panels" to (field(r, "panels") ?: emptyList<Any?>()))
        },
        "mux.standings" to boundLcnc(SurfaceCallKey(call)) { service, _, _ ->
            val call = service.value
            val r = call("GET", "/api/mux/standings", null)
            mapOf("standings" to (field(r, "standings") ?: emptyList<Any?>()))
        },
        "job.command" to boundLcnc(SurfaceCallKey(call)) { service, node, inputs ->
            val command = linkedMapOf<String, Any?>()
            command.putAll(node.params)
            for ((name, value) in inputs) command[name.removeSuffix("?")] = value
            val verb = command.remove("verb")?.toString()?.takeIf { it.isNotBlank() }
                ?: error("job.command: verb required")
            require(!command["jobId"]?.toString().isNullOrBlank()) { "job.command: jobId required" }
            command["type"] = verb
            if (command["idempotencyKey"]?.toString().isNullOrBlank()) {
                command.remove("idempotencyKey")
                val canonical = command.entries.sortedBy { it.key }.associate { it.key to it.value }
                command["idempotencyKey"] = "lcnc:${node.id}:${ContentId.of(JsonSupport.stringify(canonical).encodeToByteArray()).hex}"
            }
            val results = invokeResults(service.value, node.type, listOf(command))
            mapOf("result" to results.single())
        },
        "job.batch" to boundLcnc(SurfaceCallKey(call)) { service, node, inputs ->
            val input = inputs["commands"] ?: inputs["commands?"] ?: node.params["commands"]
                ?: error("job.batch: commands required")
            val parsed = if (input is String) JsonSupport.parse(input) else input
            val commands = InvokeLowering.listishOf(parsed)
                ?: (parsed as? Map<*, *>)?.let(InvokeLowering::commandsOf)
                ?: error("job.batch: commands must be an array or command object")
            mapOf("results" to invokeResults(service.value, node.type, commands))
        },
        "project.mount" to boundLcnc(SurfaceCallKey(call)) { service, node, inputs ->
            val path = (inputs["path"] ?: inputs["path?"] ?: node.params["path"])?.toString()
            require(!path.isNullOrBlank()) { "project.mount: path required" }
            val result = response(service.value("POST", "/api/projects", mapOf("path" to path)), node.type)
            // The production handler acknowledges a detached mount; retain its pending receipt.
            check(result["verdict"] == "ok" || result["verdict"] == "mounting") {
                "project.mount: ${result["detail"] ?: result["verdict"] ?: "mount refused"}"
            }
            mapOf("scope" to result)
        },
        "project.kill" to boundLcnc(SurfaceCallKey(call)) { service, node, _ ->
            val name = node.params["name"]?.takeIf { it.isNotBlank() }
                ?: error("project.kill: name required")
            val result = response(service.value("DELETE", "/api/projects/${percentEncode(name)}", null), node.type)
            check(result["verdict"] == "unmounted") {
                "project.kill: ${result["detail"] ?: result["verdict"] ?: "unmount refused"}"
            }
            mapOf("verdict" to result)
        },
    )

    private fun response(value: Any?, type: String): Map<*, *> {
        val result = value as? Map<*, *> ?: error("$type: dispatcher returned no response object")
        check(result["error"] == null) { "$type: ${result["error"]}" }
        return result
    }

    private suspend fun invokeResults(call: Call, type: String, commands: List<*>): List<*> {
        val result = response(call("POST", "/api/invoke", mapOf("commands" to commands)), type)
        val results = InvokeLowering.listishOf(result["results"])
            ?: error("$type: dispatcher returned no command results")
        check(results.size == commands.size) { "$type: dispatcher returned ${results.size} results for ${commands.size} commands" }
        check(results.all { (it as? Map<*, *>)?.get("verdict") in setOf("committed", "rejected") }) {
            "$type: dispatcher returned an invalid command verdict"
        }
        return results
    }

    /** The types this family serves — the gate compares this against the contracts. */
    fun servedTypes(): Set<String> = setOf(
        "http.get", "http.post",
        "blackboard.facts", "blackboard.board", "blackboard.sites",
        "graal.vitals", "graal.heap", "vms.list", "pointcut.routes",
        "panels.list", "mux.standings",
        "job.command", "job.batch", "project.mount", "project.kill",
    )
}
