package borg.trikeshed.lcnc

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

        "board.get" to LcncNodeRunner { _, _ ->
            mapOf("json" to call("GET", "/api/board", null))
        },
        "board.view" to LcncNodeRunner { _, _ ->
            val r = call("GET", "/api/board", null)
            mapOf("board" to r, "alerts" to (field(r, "alerts") ?: emptyList<Any?>()))
        },
        "http.get" to LcncNodeRunner { node, _ ->
            mapOf("json" to call("GET", node.params["path"]?.takeIf { it.isNotBlank() } ?: "/api/health", null))
        },
        "http.post" to LcncNodeRunner { node, inputs ->
            val body = inputs["body"] ?: inputs["body?"]
            mapOf("json" to call("POST", node.params["path"]?.takeIf { it.isNotBlank() } ?: "/api/submit", body))
        },
        "blackboard.facts" to LcncNodeRunner { _, _ ->
            mapOf("facts" to call("GET", "/blackboard/facts", null))
        },
        "blackboard.board" to LcncNodeRunner { _, _ ->
            mapOf("board" to call("GET", "/blackboard/board", null))
        },
        "blackboard.sites" to LcncNodeRunner { node, _ ->
            val owner = node.params["owner"].orEmpty()
            mapOf("sites" to call("GET", "/blackboard/sites" + if (owner.isBlank()) "" else "?owner=$owner", null))
        },
        "graal.vitals" to LcncNodeRunner { _, _ ->
            mapOf("json" to call("GET", "/api/graal/vitals", null))
        },
        "graal.heap" to LcncNodeRunner { _, _ ->
            mapOf("heap" to call("GET", "/api/graal/heap", null))
        },
        "vms.list" to LcncNodeRunner { _, _ ->
            val r = call("GET", "/api/vm", null)
            mapOf("rows" to (field(r, "rows") ?: r))
        },
        "pointcut.routes" to LcncNodeRunner { _, _ ->
            val r = call("GET", "/api/graal/pointcuts", null)
            mapOf("routes" to (field(r, "routes") ?: r))
        },
        "panels.list" to LcncNodeRunner { _, _ ->
            val r = call("GET", "/api/panels", null)
            mapOf("panels" to (field(r, "panels") ?: emptyList<Any?>()))
        },
        "project.list" to LcncNodeRunner { _, _ ->
            val r = call("GET", "/api/projects", null)
            mapOf("scopes" to (field(r, "scopes") ?: r))
        },
        "mux.standings" to LcncNodeRunner { _, _ ->
            val r = call("GET", "/api/mux/standings", null)
            mapOf("standings" to (field(r, "standings") ?: emptyList<Any?>()))
        },
    )

    /** The types this family serves — the gate compares this against the contracts. */
    fun servedTypes(): Set<String> = setOf(
        "board.get", "board.view", "http.get", "http.post",
        "blackboard.facts", "blackboard.board", "blackboard.sites",
        "graal.vitals", "graal.heap", "vms.list", "pointcut.routes",
        "panels.list", "project.list", "mux.standings",
    )
}
