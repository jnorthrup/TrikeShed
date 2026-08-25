package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStore
import borg.trikeshed.couch.CouchWireRouter
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import java.util.concurrent.ConcurrentHashMap

/**
 * One dropped directory hierarchy = one PROJECT DB: its own [CouchDatabase]
 * (own head projection, own `_changes` order, own doc namespace — the db name
 * IS the project), sharing the daemon's CAS so identical blobs dedupe across
 * every project. This is the couch-shaped multiproject step past increment-1's
 * prefix scopes: each project is independently addressable at `/<name>/…` and
 * (later) independently replicable.
 */
class ProjectDb(
    val name: String,
    val path: String,
    val kind: String,           // "git" | "assets"
    val db: CouchDatabase,
    val store: CouchStore,
    val gateway: CouchAttachmentGateway,
    val router: CouchWireRouter,
) {
    val docCount: Int get() = store.head.size
}

/**
 * Registry of mounted project dbs, consulted per request by [ProjectDbWire].
 * First-segment names must not shadow the daemon's own surfaces — the reserved
 * set + the primary db name are refused at mount.
 */
class ProjectDbRegistry(private val primaryDbName: String) {
    private val dbs = ConcurrentHashMap<String, ProjectDb>()

    /** Invoked on every successful mount (daemon wires the per-db Rete tendon here). */
    @Volatile
    var onMount: ((ProjectDb) -> Unit)? = null

    private val reserved = setOf(
        "api", "graal", "panels", "futon", "blackboard", "icons", "index.html",
        "styles.css", "script.js", "sw.js", "manifest.webmanifest", "_replicate", "_project",
    )

    fun refusalFor(name: String): String? = when {
        name.isBlank() || !name.matches(Regex("^[a-z0-9][a-z0-9._-]*$")) ->
            "project db name must be [a-z0-9._-], got '$name'"
        name == primaryDbName -> "'$name' is the primary database"
        name in reserved -> "'$name' shadows a daemon surface"
        dbs.containsKey(name) -> "project db '$name' already mounted"
        else -> null
    }

    fun register(pdb: ProjectDb) {
        refusalFor(pdb.name)?.let { throw IllegalArgumentException(it) }
        dbs[pdb.name] = pdb
        onMount?.invoke(pdb)
    }

    fun remove(name: String): ProjectDb? = dbs.remove(name)

    fun get(name: String): ProjectDb? = dbs[name]

    fun all(): List<ProjectDb> = dbs.values.sortedBy { it.name }
}

/**
 * ProjectDbWire — ONE static raw route whose CONTENTS are dynamic: the first
 * path segment selects a mounted project db and its [CouchWireRouter] answers
 * the full Couch 1.6 doc surface (`/<name>`, `/<name>/_all_docs`,
 * `/<name>/<docid>[/content]`, `/<name>/_changes` by poll). Unknown first
 * segments decline (null) so the shell/static tiers keep their paths.
 */
class ProjectDbWire(
    private val registry: ProjectDbRegistry,
    /** Browser-drop upload lane (`/_project/…`, raw/binary-safe — `/api/…` paths never reach raw routes). */
    private val uploads: ProjectScopes? = null,
) {
    suspend fun route(
        method: String,
        path: String,
        payload: ByteArray,
        @Suppress("UNUSED_PARAMETER") respond: (suspend (ByteArray) -> Unit)?,
    ): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        val segments = p.trim('/').split('/')
        val first = segments.firstOrNull() ?: return null

        // ── upload lane: POST /_project/<name>/begin · POST /_project/<name>/put?path=<rel> ──
        if (first == "_project") {
            // Every crossing is LOGGED — a drop that dies client-side leaves silence here,
            // and that silence is itself the diagnosis (nothing reached the daemon).
            System.err.println("[OROBOROS] _project: $method $p (${payload.size}b)")
            val scopes = uploads ?: return json(503, """{"error":"uploads not wired"}""")
            if (method != "POST" || segments.size < 3) return json(400, """{"error":"POST /_project/<name>/begin|put"}""")
            // Finder names carry spaces; the client sends them percent-encoded ("My%20PDF%20Stash").
            // Decode BEFORE sanitize, or '%20' mangles into literal '-20' in the db name.
            val name = borg.trikeshed.utils.rfxhttp.CouchHttpSurface.percentDecode(segments[1])
            return when (segments[2]) {
                "begin" -> runCatching { scopes.beginUpload(name) }.fold(
                    onSuccess = {
                        System.err.println("[OROBOROS] project db upload begun: ${it.name}")
                        json(200, """{"verdict":"ok","name":"${it.name}"}""")
                    },
                    onFailure = { json(400, """{"verdict":"refused","detail":"${(it.message ?: "").replace('"', '\'')}"}""") },
                )

                "put" -> {
                    val rel = borg.trikeshed.utils.rfxhttp.CouchHttpSurface
                        .parseQuery(path.substringAfter('?', ""))["path"]
                        ?: return json(400, """{"error":"path query required"}""")
                    val bytes = CouchWire.bodyOf(payload)
                    if (bytes.isEmpty()) return json(400, """{"error":"empty body"}""")
                    runCatching { scopes.uploadPut(name, rel, bytes) }.fold(
                        onSuccess = { json(200, """{"verdict":"ok","cid":"${it.value}","bytes":${bytes.size}}""") },
                        onFailure = { json(400, """{"verdict":"refused","detail":"${(it.message ?: "").replace('"', '\'')}"}""") },
                    )
                }

                else -> json(404, """{"error":"unknown upload verb"}""")
            }
        }

        val pdb = registry.get(first) ?: return null
        val reply = pdb.router.handle(method, path, CouchWire.bodyOf(payload)) ?: return null
        return JvmKanbanServer.HttpResponse(reply.status, "", reply.contentType, reply.bytes)
    }

    private fun json(status: Int, body: String) = JvmKanbanServer.HttpResponse(status, body)
}
