package borg.trikeshed.lcnc

/**
 * The registered route manifest, written once at each wire's definition site
 * and consumed by [RouteParityGate] and RouteManifestParityTest. A route that
 * starts serving without a manifest line FAILS the gate — the manifest is the
 * single author of "what does this daemon answer", and the gate is what keeps
 * it honest.
 *
 * Manifest shape: `METHOD PATH` where PATH may carry:
 * - ellipsis (…) wildcard meaning "any continuation" (prefix match, mirrors startsWith)
 * - `{param}` segments matching any single path segment (parameterized routes)
 *
 * Streaming/SSE routes are annotated by the wires themselves; the gate only
 * checks existence, not transport.
 *
 * The couch surface (/{db}/ pattern) is reconciled with
 * `src/commonMain/resources/openapi/couch-oroboros.openapi.yaml` — that spec
 * stays authoritative for request/response schemas on the couch/_cas surface.
 *
 * Machine-readable: call [toJson] to serialize the manifest to JSON.
 */
object RouteManifest {
    /** A manifest entry with method, path, and a one-line summary. */
    data class RouteEntry(val method: String, val path: String, val summary: String) {
        override fun toString(): String = "$method $path"
    }

    /** Every route the daemon serves, grouped by owning wire. */
    val entries: Map<String, List<RouteEntry>> = mapOf(
        // -- PatchWire: mux/projects/panels/LCNC --
        "PatchWire" to listOf(
            RouteEntry("GET", "/api/mux/models", "discovered provider roster"),
            RouteEntry("GET", "/api/mux/keys", "full roster with key-presence flags"),
            RouteEntry("GET", "/api/mux/standings", "quota-legion standings"),
            RouteEntry("GET", "/api/mux/endpoints", "user-declared provider endpoints"),
            RouteEntry("POST", "/api/mux/endpoints", "register a provider endpoint"),
            RouteEntry("DELETE", "/api/mux/endpoints/{name}", "remove a provider endpoint"),
            RouteEntry("POST", "/api/mux/chat", "provider-neutral failover chat"),
            RouteEntry("GET", "/api/lcnc/mating-options", "kind-compatible mates for a source port"),
            RouteEntry("GET", "/api/lcnc/fills", "param fills for a node type"),
            RouteEntry("GET", "/api/lcnc/autowire", "auto-wire proposal between two types"),
            RouteEntry("GET", "/api/projects", "mounted project scopes"),
            RouteEntry("POST", "/api/projects", "mount a directory as a project scope"),
            RouteEntry("DELETE", "/api/projects/{name}", "unmount a project scope"),
            RouteEntry("GET", "/api/panels", "list stored panel constructions"),
            RouteEntry("GET", "/api/panels/presets", "LCNC preset gallery"),
            RouteEntry("GET", "/api/panels/{name}", "load a panel construction"),
            RouteEntry("POST", "/api/panels/{name}", "save a panel construction"),
            RouteEntry("POST", "/api/projects/{name}/mine", "Tika/OCR mining over a project"),
            RouteEntry("GET", "/api/projects/{name}/mine", "mining progress"),
        ),
        // -- ModuleWire: module lifecycle --
        "ModuleWire" to listOf(
            RouteEntry("GET", "/api/modules", "attached modules and claimed routes"),
            RouteEntry("POST", "/api/modules", "attach a module by FQCN"),
            RouteEntry("DELETE", "/api/modules/{id}", "detach a module"),
        ),
        // -- KanbanModule: board + LCNC runtimes --
        "KanbanModule" to listOf(
            RouteEntry("GET", "/api/board", "WAL-backed live board"),
            RouteEntry("POST", "/api/invoke", "batched browser commands to store intake"),
            RouteEntry("POST", "/api/board/import", "tolerant plan-doc import"),
            RouteEntry("GET", "/api/lcnc/kanban", "active LCNC sheets"),
            RouteEntry("GET", "/api/lcnc/concentric", "modules + rings + wizard roster"),
            RouteEntry("POST", "/api/lcnc/kanban/move", "move a card to a new column"),
            RouteEntry("GET", "/api/lcnc/contracts", "full LCNC contract vocabulary"),
            RouteEntry("POST", "/api/lcnc/run", "generic runner dispatch"),
            RouteEntry("GET", "/api/lcnc/council", "council case read-back: index fact + transcript/verdict from CAS (?caseId=<id>)"),
            RouteEntry("GET", "/api/mcp", "MCP server card: protocol versions, tools, resource URIs"),
            RouteEntry("POST", "/api/mcp", "MCP JSON-RPC: the LCNC Kanban board as tools and resources"),
        ),
        // -- GraalWire: console + terrain + ingest + capsule + occupy --
        "GraalWire" to listOf(
            RouteEntry("GET", "/graal", "console page"),
            RouteEntry("GET", "/futon", "couch-CRUD companion page"),
            RouteEntry("GET", "/graal.webmanifest", "PWA install manifest"),
            RouteEntry("GET", "/api/graal/vitals", "JVM vitals + pointcut summary"),
            RouteEntry("GET", "/api/graal/heap", "heap histogram"),
            RouteEntry("GET", "/api/graal/pointcuts", "pointcut route table"),
            RouteEntry("GET", "/api/graal/map", "full store as compact [id, bytes] rows"),
            RouteEntry("GET", "/api/graal/zoom", "mid-zoom ring representatives per code ring"),
            RouteEntry("GET", "/api/graal/strength", "CAS-granting-strength proof between two cids"),
            RouteEntry("GET", "/api/graal/density", "per-region residual density at a zoom band"),
            RouteEntry("GET", "/api/graal/sheet", "live CursorSheet for a document"),
            RouteEntry("GET", "/api/graal/dag", "DAG arcs and cross-links"),
            RouteEntry("GET", "/api/graal/decompile", "source + classpath mates via JDK 25"),
            RouteEntry("GET", "/api/graal/aot", "AOT flags and HotSpot cache metadata"),
            RouteEntry("GET", "/api/graal/aot/blob", "HotSpot AOT archive bytes"),
            RouteEntry("POST", "/api/graal/aot/capture", "land AOT archive into CAS"),
            RouteEntry("GET", "/api/graal/events", "SSE: compile/deopt/gc/cpu + store commits"),
            RouteEntry("POST", "/api/graal/ingest", "raw bytes through Tika/OCR to store citizen"),
            RouteEntry("GET", "/api/graal/capsule/list", "list hermes capsules"),
            RouteEntry("POST", "/api/graal/capsule/spawn", "spawn a hermes capsule"),
            RouteEntry("POST", "/api/graal/capsule/{id}/stdin", "type at captured shell"),
            RouteEntry("GET", "/api/graal/capsule/{id}/output", "VT scrollback (poll)"),
            RouteEntry("POST", "/api/graal/capsule/{id}/kill", "interrupt + close guest"),
            RouteEntry("GET", "/api/graal/occupy", "list occupied repos"),
            RouteEntry("POST", "/api/graal/occupy", "absorb a git repo"),
            RouteEntry("POST", "/api/graal/occupy/{id}/release", "stop watching"),
        ),
        // -- VmWire: metered sub-VMs + terminals --
        "VmWire" to listOf(
            RouteEntry("GET", "/vm-terminal", "VT220 web terminal page"),
            RouteEntry("GET", "/api/vm", "VM sheet"),
            RouteEntry("POST", "/api/vm/spawn", "spawn a sub-VM"),
            RouteEntry("POST", "/api/vm/{id}/eval", "evaluate source in a VM"),
            RouteEntry("POST", "/api/vm/{id}/revoke", "revoke a VM"),
            RouteEntry("GET", "/api/vm/events", "SSE of VmEvent"),
            RouteEntry("GET", "/api/vm/terminals", "terminal snapshots"),
            RouteEntry("GET", "/api/vm/terminal/events", "global terminal patch SSE"),
            RouteEntry("GET", "/api/vm/{id}/terminal", "one terminal snapshot"),
            RouteEntry("POST", "/api/vm/{id}/terminal/input", "send input to terminal"),
            RouteEntry("POST", "/api/vm/{id}/terminal/resize", "resize terminal"),
        ),
        // -- BeliefWire: NARS curation loop --
        "BeliefWire" to listOf(
            RouteEntry("GET", "/api/beliefs", "top-k beliefs with budgets"),
            RouteEntry("GET", "/api/beliefs/render", "bounded MEMORY render"),
            RouteEntry("POST", "/api/beliefs/review", "facts to induction pass"),
            RouteEntry("POST", "/api/beliefs/tick", "one DecayTick"),
            RouteEntry("POST", "/api/beliefs/teach", "curator hindsight pass (W5.3)"),
            RouteEntry("POST", "/api/beliefs/query", "bank solver query (W5.3)"),
            RouteEntry("POST", "/api/beliefs/resonate", "solver proposal support and refutation"),
            RouteEntry("GET", "/api/beliefs/introspect", "NAL-9 introspection"),
            RouteEntry("POST", "/api/beliefs/kg", "Turtle/RDF/KIF to NAL beliefs"),
        ),
        // -- CouchWire: CouchDB 1.6/1.7 surface + CAS lanes --
        // Pattern families for {db}/* mounts; see couch-oroboros.openapi.yaml
        // for full request/response schemas on the couch/_cas surface.
        "CouchWire" to listOf(
            RouteEntry("GET", "/{db}", "database info"),
            RouteEntry("POST", "/{db}", "bare document put"),
            RouteEntry("GET", "/{db}/_all_docs", "all live document ids"),
            RouteEntry("POST", "/{db}/_all_docs", "restricted to keys"),
            RouteEntry("GET", "/{db}/_changes", "committed-frame log (streaming in CouchWire)"),
            RouteEntry("POST", "/{db}/_revs_diff", "which revisions node lacks"),
            RouteEntry("POST", "/{db}/_bulk_docs", "batch write"),
            RouteEntry("GET", "/{db}/_local/{id}", "read checkpoint"),
            RouteEntry("PUT", "/{db}/_local/{id}", "write checkpoint"),
            RouteEntry("DELETE", "/{db}/_local/{id}", "remove checkpoint"),
            RouteEntry("GET", "/{db}/_cas/{cid}", "get one raw block"),
            RouteEntry("POST", "/{db}/_cas", "put one raw block"),
            RouteEntry("POST", "/{db}/_cas/_bulk", "bulk block exchange"),
            RouteEntry("GET", "/{db}/_design/{ddoc}/_rewrite/{path}", "CouchApp rewrite"),
            RouteEntry("GET", "/{db}/_design/{ddoc}/_view/{view}", "incremental view"),
            RouteEntry("GET", "/{db}/…", "document JSON (id may contain slashes)"),
            RouteEntry("PUT", "/{db}/…", "create or update document"),
            RouteEntry("DELETE", "/{db}/…", "delete document"),
            RouteEntry("GET", "/{db}/…/content", "attachment bytes"),
            RouteEntry("POST", "/{db}/_replicate", "run or start replication"),
            RouteEntry("GET", "/{db}/_replicate", "list live replication jobs"),
            RouteEntry("GET", "/api/v0/block/get", "IPFS alias of _cas"),
            RouteEntry("POST", "/api/v0/block/put", "IPFS alias of _cas"),
        ),
        // -- BlackboardWire: ConfixBlackboard HTTP window --
        "BlackboardWire" to listOf(
            RouteEntry("GET", "/blackboard", "consolidated blackboard page"),
            RouteEntry("GET", "/blackboard/facts", "SSE delta feed"),
            RouteEntry("POST", "/blackboard/assert", "assert key-value pairs"),
            RouteEntry("GET", "/blackboard/sites", "pointcut site listing"),
            RouteEntry("GET", "/blackboard/board", "full board snapshot"),
        ),
        // -- HermesWire: supervised Hermes VM console --
        "HermesWire" to listOf(
            RouteEntry("GET", "/hermes", "Hermes console page"),
            RouteEntry("GET", "/api/hermes/terminal", "terminal snapshot"),
            RouteEntry("POST", "/api/hermes/terminal/open", "open terminal session"),
            RouteEntry("POST", "/api/hermes/terminal/input", "send input"),
            RouteEntry("POST", "/api/hermes/terminal/resize", "resize terminal"),
            RouteEntry("GET", "/api/hermes/terminal/events", "SSE terminal events"),
        ),
        // -- JulesWire: session surface projection --
        "JulesWire" to listOf(
            RouteEntry("GET", "/api/jules/surface", "Jules session surface projection"),
            RouteEntry("GET", "/api/jules/events", "Jules event stream (retired)"),
        ),
        // -- WebhookWire: signed inbound webhooks --
        "WebhookWire" to listOf(
            RouteEntry("POST", "/hook/{program}/{node}/{port}", "signed inbound webhook"),
        ),
        // -- ProjectDbWire: browser-drop upload lane --
        "ProjectDbWire" to listOf(
            RouteEntry("POST", "/_project/{name}/begin", "begin browser-drop upload"),
            RouteEntry("POST", "/_project/{name}/putBatch", "batched file upload"),
            RouteEntry("POST", "/_project/{name}/put", "single file upload"),
        ),
        // -- IngestRoutes: drop-a-corpus entry points --
        "IngestRoutes" to listOf(
            RouteEntry("POST", "/api/submit", "document submit via Tika/OCR"),
            RouteEntry("POST", "/api/donor", "donor document submit"),
        ),
        // -- BuiltIn: daemon infrastructure --
        "BuiltIn" to listOf(
            RouteEntry("GET", "/api/health", "server health check"),
            RouteEntry("GET", "/api/cap", "server capabilities"),
            RouteEntry("GET", "/api/metrics", "flywheel metrics (retired)"),
        ),
    )

    /** Backward-compatible string map derived from [entries]. */
    val routes: Map<String, List<String>> = entries.mapValues { (_, v) -> v.map { it.toString() } }

    val all: List<String> = routes.values.flatten()

    private const val WILDCARD = "…"

    /** Longest-prefix manifest match with {param} segment support. */
    fun covers(method: String, path: String): Boolean {
        val exact = "$method $path"
        if (exact in all) return true
        return entries.values.flatten().any { entry ->
            entry.method == method && matchPath(entry.path, path)
        }
    }

    /** Match a manifest path pattern against an actual request path. */
    private fun matchPath(pattern: String, path: String): Boolean {
        // Wildcard prefix: ends with ellipsis
        if (pattern.endsWith(WILDCARD)) {
            val prefix = pattern.removeSuffix(WILDCARD).trimEnd('/')
            val prefixSegs = prefix.trim('/').split('/')
            val pathSegs = path.trim('/').split('/')
            if (pathSegs.size < prefixSegs.size) return false
            return prefixSegs.zip(pathSegs).all { (p, s) ->
                p == s || (p.startsWith("{") && p.endsWith("}"))
            }
        }
        // Segment-by-segment matching with {param} wildcards
        val patternSegs = pattern.trim('/').split('/')
        val pathSegs = path.trim('/').split('/')
        if (patternSegs.size != pathSegs.size) return false
        return patternSegs.zip(pathSegs).all { (p, s) ->
            p == s || (p.startsWith("{") && p.endsWith("}"))
        }
    }

    /** Serialize the manifest to a machine-readable JSON artifact. */
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\n  \"version\": \"1.0\",\n  \"routes\": {\n")
        var firstWire = true
        for ((wire, routeEntries) in entries) {
            if (!firstWire) sb.append(",\n")
            firstWire = false
            sb.append("    \"").append(wire).append("\": [\n")
            var first = true
            for (e in routeEntries) {
                if (!first) sb.append(",\n")
                first = false
                sb.append("      {\"method\":\"").append(e.method)
                sb.append("\",\"path\":\"").append(e.path)
                sb.append("\",\"summary\":\"").append(e.summary)
                sb.append("\"}")
            }
            sb.append("\n    ]")
        }
        sb.append("\n  }\n}")
        return sb.toString()
    }
}
