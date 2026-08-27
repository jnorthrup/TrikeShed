package borg.trikeshed.lcnc

/**
 * The registered route manifest, written once at each wire's definition site
 * and consumed by [RouteParityGate]. A route that starts serving without a
 * manifest line FAILS the gate — the manifest is the single author of "what
 * does this daemon answer", and the gate is what keeps it honest.
 *
 * Manifest shape: `METHOD PATH` where PATH may carry one `…` wildcard
 * segment meaning "any continuation" (mirrors startsWith matching).
 * Streaming/SSE routes are annotated by the wires themselves; the gate only
 * checks existence, not transport.
 */
object RouteManifest {
    /** Every route the daemon serves, grouped by owning wire. */
    val routes: Map<String, List<String>> = mapOf(
        "PatchWire" to listOf(
            "GET /api/mux/models",
            "GET /api/mux/keys",
            "POST /api/mux/chat",
            "GET /api/panels",
            "GET /api/panels/…",
            "POST /api/panels/…",
            "GET /api/panels/presets",
            "POST /api/panels/…/mate",
            "GET /api/lcnc/mating-options",
            "GET /api/projects",
            "POST /api/projects",
            "DELETE /api/projects/…",
            // GraalWire raw ingest rides the rawRoutes chain (documented on GraalWire)
            "POST /api/graal/ingest",
        ),
        "ModuleWire" to listOf(
            "GET /api/modules",
            "POST /api/modules",
            "DELETE /api/modules/…",
        ),
        "KanbanModule" to listOf(
            "GET /api/board",
            "POST /api/invoke",
            "POST /api/board/import",
            "GET /api/lcnc/kanban",
            "POST /api/lcnc/kanban/move",
            "GET /api/lcnc/contracts",
            "POST /api/submit",
        ),
        "GraalWire" to listOf(
            "GET /api/graal/vitals",
            "GET /api/graal/map",
            "GET /api/graal/dag",
            "GET /api/graal/decompile",
            "GET /api/graal/aot",
            "GET /api/graal/aot/blob",
            "POST /api/graal/aot/capture",
            "GET /api/graal/events",
            "POST /api/graal/ingest",
            "GET /api/graal/pointcuts",
            "GET /api/graal/sheet",
            "GET /api/graal/capsule/list",
            "POST /api/graal/capsule/spawn",
            "POST /api/graal/capsule/…",
            "GET /api/graal/occupy",
            "POST /api/graal/occupy",
        ),
        "VmWire" to listOf(
            "GET /api/vm",
            "POST /api/vm/spawn",
            "POST /api/vm/…",
            "GET /api/vm/…",
            "GET /api/vm/terminals",
            "GET /api/vm/terminal/events",
            "GET /api/vm/events",
        ),
        "BeliefWire" to listOf(
            "GET /api/beliefs",
            "GET /api/beliefs/render",
            "POST /api/beliefs/review",
            "POST /api/beliefs/tick",
            "POST /api/beliefs/teach",
            "POST /api/beliefs/query",
            "POST /api/beliefs/resonate",
            "GET /api/beliefs/introspect",
            "POST /api/beliefs/kg",
        ),
        "HermesWire" to listOf(
            "POST /api/hermes/terminal/open",
            "GET /api/hermes/terminal/events",
            "POST /api/hermes/terminal/input",
            "POST /api/hermes/terminal/resize",
            "GET /api/hermes/terminal",
        ),
        "JulesWire" to listOf(
            "GET /api/jules/surface",
            "GET /api/jules/events",
        ),
        "CouchWire" to listOf(
            "POST /api/cap",
            "POST /api/donor",
            "GET /api/metrics",
            "POST /_project/…",
            "GET /_project/…",
        ),
    )

    val all: List<String> = routes.values.flatten()

    private const val WILDCARD = "…"

    /** Longest-prefix manifest match: `GET /api/panels/foo/mate` matches `POST /api/panels/…` only if method agrees. */
    fun covers(method: String, path: String): Boolean {
        val exact = "$method $path"
        if (exact in all) return true
        return all.any { entry ->
            entry.startsWith("$method ") && entry.substringAfter(' ').endsWith(WILDCARD) &&
                path.startsWith(entry.substringAfter(' ').removeSuffix(WILDCARD))
        }
    }
}
