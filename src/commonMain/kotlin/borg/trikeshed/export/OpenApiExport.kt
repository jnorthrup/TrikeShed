package borg.trikeshed.export

import borg.trikeshed.lcnc.RouteManifest
import borg.trikeshed.lib.*
import borg.trikeshed.reactor.openapi.OpenApiRawParser
import narchy.spacegraph.NodeShape
import narchy.spacegraph.RenderModel
import narchy.spacegraph.RenderNode
import narchy.spacegraph.Vec3

/** One operation of a hand-written spec, with the spec it came from and that spec's top-level security. */
data class SpecOperation(
    val source: String,
    val path: String,
    val method: String,
    val operation: Map<String, Any?>,
    val pathItem: Map<String, Any?>,
    val security: Any?,
)

/**
 * One OpenAPI 3.1 document for everything the daemon answers.
 *
 * [RouteManifest] is the single author of what the daemon answers, so every manifest line is an operation here and
 * nothing else is. The hand-written specs under `openapi/` supply schemas: a spec operation that a manifest line
 * covers (same method, same path up to parameter names, or under a `…` line's prefix) is copied whole, with its
 * components. A spec operation no manifest line covers stays out of `paths` and is listed under
 * `x-trikeshed-unlisted`; a components name two specs define differently is listed under `x-trikeshed-conflicts`.
 * Both are gaps to settle in the manifest or the spec, never here.
 */
object OpenApiExport {
    const val OPENAPI: String = "3.1.0"

    /**
     * The hand-written specs of this daemon, as `CommonResources` keys. `openapi/htx-general.openapi.yaml` is not
     * one: it describes the separate htx-general server stub and binds its sides to the HTX/QUIC/SCTP elements
     * through `x-trikeshed-context` for the reactor generators.
     */
    val SPECS: Series<String> = s_["openapi/forge-host.openapi.yaml", "openapi/couch-oroboros.openapi.yaml"]

    val HTTP_METHODS: Set<String> = _s["get", "put", "post", "delete", "options", "head", "patch", "trace"]

    /** A path's segments with every parameter name dropped: `/a/{id}` and `/a/{name}` are one route. */
    fun shape(path: String): Series<String> =
        path.split('/').filter { it.isNotEmpty() }.map { if (it.startsWith('{') && it.endsWith('}')) "{}" else it }.toSeries()

    /** A manifest line serves a spec path when the shapes agree, a line parameter standing for any one segment and a final `…` for any continuation. */
    fun covers(line: Series<String>, spec: Series<String>): Boolean {
        val prefix = line.size > 0 && line[line.size - 1] == "…"
        val fixed = if (prefix) line.size - 1 else line.size
        if (if (prefix) spec.size < fixed else spec.size != fixed) return false
        return (0 until fixed).all { i -> line[i] == "{}" || line[i] == spec[i] }
    }

    fun same(a: Series<String>, b: Series<String>): Boolean = a.size == b.size && (0 until a.size).all { a[it] == b[it] }

    /** A manifest path as an OpenAPI path: the `…` continuation becomes the `{tail}` parameter. */
    fun openApiPath(path: String): String = path.replace("…", "{tail}")

    /** `GET /api/lcnc/run/{jobId}` → `getApiLcncRunJobId`; `…` reads as `Tail`. */
    fun operationId(method: String, path: String): String = buildString {
        append(method.lowercase())
        for (word in path.replace("…", "/tail").split('/', '.', '-', '_', '{', '}', '$')) {
            val clean = word.filter { it.isLetterOrDigit() }
            if (clean.isNotEmpty()) append(clean.replaceFirstChar { it.uppercaseChar() })
        }
        if (length == method.length) append("Root")
    }

    fun pathParameters(path: String): Series<Map<String, Any?>> =
        Regex("""\{([^}/]+)\}""").findAll(path).map { it.groupValues[1] }.toList().toSeries() α { name ->
            _m[
                "name" j name, "in" j "path", "required" j true, "schema" j _m["type" j "string"],
                "description" j if (name == "tail") "any continuation of the path" else null,
            ].filterValues { it != null }
        }

    fun specOperations(source: String, root: Map<String, Any?>): Series<SpecOperation> {
        val paths = root["paths"] as? Map<*, *> ?: return emptySeriesOf()
        val found = mutableListOf<SpecOperation>()
        for ((path, item) in paths) {
            val pathItem = (item as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: continue
            for ((method, op) in pathItem) {
                val operation = (op as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: continue
                if (method in HTTP_METHODS) found += SpecOperation(source, path.toString(), method, operation, pathItem, root["security"])
            }
        }
        return found.toSeries()
    }

    /**
     * The document. [specs] pairs each spec's key with its text (YAML or JSON); [servers] are absolute base URLs and
     * may be empty, which OpenAPI reads as the document's own origin.
     */
    fun document(
        specs: Series<Join<String, String>>,
        version: String,
        servers: Series<String> = emptySeriesOf(),
        manifest: Map<String, List<RouteManifest.RouteEntry>> = RouteManifest.entries,
        title: String = "TrikeShed oroboros daemon",
    ): Map<String, Any?> {
        val roots = specs α { (source, text) -> source j OpenApiRawParser.parse(text).root }
        val specOps = roots.view.flatMap { (source, root) -> specOperations(source, root).view }
        val covered = HashSet<SpecOperation>()
        val paths = LinkedHashMap<String, MutableMap<String, Any?>>()
        val ids = HashSet<String>()
        fun unique(id: String): String {
            var candidate = id
            var n = 2
            while (!ids.add(candidate)) candidate = id + "_" + n++
            return candidate
        }

        // Each spec operation belongs to the one line that covers it most specifically: most equal literal
        // segments, then a fixed line over a `…` line, then manifest order. `/{db}` covers `/api/health` too,
        // and must not take it from the line that names it.
        val lines = manifest.entries.flatMap { (wire, routes) -> routes.map { wire j it } }
        fun score(line: Series<String>, spec: Series<String>): Int =
            2 * (0 until minOf(line.size, spec.size)).count { line[it] != "{}" && line[it] != "…" && line[it] == spec[it] } +
                if (line.size > 0 && line[line.size - 1] == "…") 0 else 1
        val owner: Map<SpecOperation, RouteManifest.RouteEntry> = specOps.mapNotNull { op ->
            lines.filter { (_, r) -> r.method.lowercase() == op.method && covers(shape(r.path), shape(op.path)) }
                .maxByOrNull { (_, r) -> score(shape(r.path), shape(op.path)) }?.let { op to it.b }
        }.toMap()

        for ((wire, routes) in manifest) for (route in routes) {
            val method = route.method.lowercase()
            val line = shape(route.path)
            val matches = specOps.filter { owner[it] == route }
            covered += matches
            val exact = matches.firstOrNull { same(shape(it.path), line) }
            if (exact == null) {
                val path = openApiPath(route.path)
                paths.getOrPut(path) { LinkedHashMap() }[method] = _m[
                    "operationId" j unique(operationId(route.method, route.path)),
                    "summary" j route.summary,
                    "tags" j _l[wire],
                    "parameters" j pathParameters(path).view.toList(),
                    "responses" j _m["default" j _m["description" j route.summary]],
                    "x-trikeshed-wire" j wire,
                    "x-trikeshed-match" j if (route.path.endsWith("…")) "prefix" else null,
                ].filterValues { it != null }
            }
            for (m in matches) {
                val item = paths.getOrPut(m.path) { LinkedHashMap() }
                for ((k, v) in m.pathItem) if (k !in HTTP_METHODS && k !in item) item[k] = v
                if (method in item) continue
                val op = LinkedHashMap(m.operation)
                op["operationId"] = unique(m.operation["operationId"] as? String ?: operationId(route.method, m.path))
                op["tags"] = ((m.operation["tags"] as? List<*>).orEmpty().map { it.toString() } + wire).distinct()
                if (op["summary"] == null) op["summary"] = route.summary
                if (op["security"] == null && m.security != null) op["security"] = m.security
                op["x-trikeshed-wire"] = wire
                op["x-trikeshed-source"] = m.source
                item[method] = op
            }
        }

        val components = LinkedHashMap<String, MutableMap<String, Any?>>()
        val conflicts = mutableListOf<String>()
        for ((source, root) in roots.view) {
            val sections = root["components"] as? Map<*, *> ?: continue
            for ((section, named) in sections) for ((name, definition) in (named as? Map<*, *>).orEmpty()) {
                val into = components.getOrPut(section.toString()) { LinkedHashMap() }
                val key = name.toString()
                when {
                    key !in into -> into[key] = definition
                    into[key] != definition -> conflicts += "$section/$key ($source)"
                }
            }
        }
        val unlisted = specOps.filter { it !in covered }.map { "${it.method.uppercase()} ${it.path} (${it.source})" }

        return _m[
            "openapi" j OPENAPI,
            "info" j _m[
                "title" j title,
                "version" j version,
                "description" j "Every route RouteManifest lists, with the hand-written specs' schemas where they cover a route.",
            ],
            "servers" j if (servers.size == 0) null else (servers α { _m["url" j it] }).view.toList(),
            "tags" j manifest.keys.map { _m["name" j it] },
            "paths" j paths,
            "components" j components.ifEmpty { null },
            "x-trikeshed-unlisted" j unlisted.ifEmpty { null },
            "x-trikeshed-conflicts" j conflicts.ifEmpty { null },
        ].filterValues { it != null }
    }

    /**
     * The routes as a SpaceGraph host model: each wire a group, each route a card inside it. Positions are laid
     * here, in world units, because a contract renderer never lays out.
     */
    fun surface(manifest: Map<String, List<RouteManifest.RouteEntry>> = RouteManifest.entries): RenderModel {
        val nodes = mutableListOf<RenderNode>()
        manifest.entries.forEachIndexed { column, (wire, routes) ->
            val x = column * 480.0
            nodes += RenderNode(
                id = "wire:$wire", kind = "wire", label = wire, position = Vec3(x, 0.0, 0.0),
                size = Vec3(440.0, (routes.size + 1) * 56.0, 1.0), shape = NodeShape.GROUP,
            )
            routes.forEachIndexed { row, route ->
                nodes += RenderNode(
                    id = "route:$route", kind = "route", label = route.toString(), summary = route.summary,
                    position = Vec3(x, (row + 1) * 56.0, 0.0), size = Vec3(420.0, 48.0, 1.0),
                    shape = NodeShape.CARD, parent = "wire:$wire",
                    data = _m["method" j route.method, "path" j route.path, "wire" j wire],
                )
            }
        }
        return RenderModel(nodes.toSeries())
    }
}

/**
 * A dependency-free TypeScript client for [OpenApiExport.document]: one function per operation, path parameters
 * typed by name, and an `events` helper for the routes that stream server-sent events. Bodies and replies are
 * `unknown` until the specs carry their schemas.
 */
object TypeScriptExport {
    fun client(document: Map<String, Any?>): String = buildString {
        appendLine("/* Generated by borg.trikeshed.export.TypeScriptExport from the daemon's GET /openapi.json. Do not edit. */")
        appendLine("export type Fetch = (input: string, init?: RequestInit) => Promise<Response>;")
        appendLine("export type Client = { readonly base: string; readonly fetch?: Fetch; readonly headers?: Record<string, string> };")
        appendLine("export type Query = Record<string, string | number | boolean | undefined>;")
        appendLine("export type Call = { readonly query?: Query; readonly body?: unknown; readonly headers?: Record<string, string> };")
        appendLine()
        val operations = mutableListOf<Join<String, Join<String, String>>>()
        appendLine("export const operations = {")
        for ((path, item) in (document["paths"] as? Map<*, *>).orEmpty()) {
            for ((method, op) in (item as? Map<*, *>).orEmpty()) {
                if (method.toString() !in OpenApiExport.HTTP_METHODS) continue
                val operation = op as? Map<*, *> ?: continue
                val id = operation["operationId"].toString()
                val summary = operation["summary"]?.toString().orEmpty()
                appendLine("    ${quote(id)}: { method: '${method.toString().uppercase()}', path: ${quote(path.toString())}, summary: ${quote(summary)} },")
                operations += id j (path.toString() j summary)
            }
        }
        appendLine("} as const;")
        appendLine("export type OperationId = keyof typeof operations;")
        appendLine()
        appendLine("export function url(client: Client, id: OperationId, params: Record<string, string> = {}, query: Query = {}): string {")
        appendLine("    const path = operations[id].path.replace(/\\{([^}]+)\\}/g, (_, name: string) => {")
        appendLine("        const value = params[name];")
        appendLine("        if (value === undefined) throw new Error(`\${id}: missing path parameter \${name}`);")
        appendLine("        return name === 'tail' ? value.split('/').map(encodeURIComponent).join('/') : encodeURIComponent(value);")
        appendLine("    });")
        appendLine("    const search = Object.entries(query).filter(([, v]) => v !== undefined).map(([k, v]) => `\${encodeURIComponent(k)}=\${encodeURIComponent(String(v))}`).join('&');")
        appendLine("    return client.base.replace(/\\/$/, '') + path + (search ? `?\${search}` : '');")
        appendLine("}")
        appendLine()
        appendLine("export function call(client: Client, id: OperationId, params: Record<string, string> = {}, init: Call = {}): Promise<Response> {")
        appendLine("    const body = init.body === undefined ? undefined : typeof init.body === 'string' ? init.body : JSON.stringify(init.body);")
        appendLine("    const headers = { ...(body !== undefined && typeof init.body !== 'string' ? { 'Content-Type': 'application/json' } : {}), ...client.headers, ...init.headers };")
        appendLine("    return (client.fetch ?? fetch)(url(client, id, params, init.query), { method: operations[id].method, headers, body });")
        appendLine("}")
        appendLine()
        appendLine("/** Server-sent events from a streaming route; returns the function that closes the stream. */")
        appendLine("export function events(client: Client, path: string, onEvent: (data: string, event: MessageEvent) => void): () => void {")
        appendLine("    const source = new EventSource(client.base.replace(/\\/$/, '') + path);")
        appendLine("    source.onmessage = (event) => onEvent(String(event.data), event);")
        appendLine("    return () => source.close();")
        appendLine("}")
        val names = HashSet<String>()
        for ((id, rest) in operations) {
            val (path, summary) = rest
            val base = id.filter { it.isLetterOrDigit() || it == '_' || it == '$' }.let { if (it.isEmpty() || it[0].isDigit()) "_$it" else it }
            var name = base
            var n = 2
            while (!names.add(name)) name = base + "_" + n++
            val parameters = Regex("""\{([^}/]+)\}""").findAll(path).map { it.groupValues[1] }.toList()
            appendLine()
            if (summary.isNotEmpty()) appendLine("/** ${summary.replace("*/", "*\\/")} */")
            if (parameters.isEmpty()) appendLine("export const $name = (client: Client, init: Call = {}) => call(client, ${quote(id)}, {}, init);")
            else appendLine("export const $name = (client: Client, params: { ${parameters.joinToString("; ") { "${quote(it)}: string" }} }, init: Call = {}) => call(client, ${quote(id)}, params, init);")
        }
    }

    fun quote(text: String): String = buildString {
        append('\'')
        for (c in text) when (c) {
            '\'' -> append("\\'")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('\'')
    }
}
