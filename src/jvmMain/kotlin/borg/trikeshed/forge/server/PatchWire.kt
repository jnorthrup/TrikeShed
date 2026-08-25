package borg.trikeshed.forge.server

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.narsese.AngularCodec
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.BeliefIntake
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.WorktreeCouchGateway
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * ProjectScopes — oroboros as a MULTIPROJECT daemon: the store hierarchs first
 * by project, then by project subscopes as the blackboard identifies seams in
 * the data (taxonomy prefixes ARE the seams; principal-concept introspection
 * surfaces the unnamed ones).
 *
 * Mounting is drag-a-directory cheap: classify (a `.git` marks a PROJECT →
 * `projects/<name>/`; anything else is a pile of mining ASSETS →
 * `assets/<name>/`), absorb the worktree into the couch/CAS plane, index the
 * taxonomy routes, and — when the belief bag is live — mint capped organic
 * epistemic signals from the text so the scope resonates immediately.
 * Increment 1: extra scopes get the worktree plane only (no git-object plane,
 * no watchers); the primary project keeps its full choreography.
 */
class ProjectScopes(
    private val fileOps: FileOperations,
    private val attachments: CouchAttachmentGateway,
    private val couchIndexBridge: CouchIndexBridge,
    private val casStore: CasStore,
    private val beliefBag: BeliefBagElement?,
) {
    data class Scope(
        val name: String,
        val path: String,
        val kind: String,     // "git" | "assets"
        val prefix: String,
        val paths: Int,
        val minted: Int,
    )

    @Volatile
    private var scopes: List<Scope> = emptyList()
    private val mutex = Mutex()

    fun list(): List<Scope> = scopes

    /** The primary repo registers as scope zero (already absorbed by the daemon's own choreography). */
    fun registerPrimary(repoDir: File, paths: Int) {
        val s = Scope(repoDir.name.lowercase(), repoDir.absolutePath, "git", WorktreeCouchGateway.WORKTREE_PREFIX, paths, 0)
        scopes = listOf(s) + scopes.filter { it.name != s.name }
    }

    suspend fun mount(rawPath: String): Scope = mutex.withLock {
        val dir = File(rawPath.removePrefix("file://")).canonicalFile
        require(dir.isDirectory) { "not a directory: $rawPath" }
        val name = dir.name.lowercase()
        require(scopes.none { it.name == name }) { "scope '$name' already mounted" }
        val kind = if (File(dir, ".git").exists()) "git" else "assets"
        val prefix = (if (kind == "git") "projects/" else "assets/") + name + "/"

        val snap = withContext(Dispatchers.IO) {
            WorktreeCouchGateway(fileOps, attachments, prefix = prefix)
                .reconcile(dir.absolutePath, agentId = "oroboros", revision = kind, sequence = System.currentTimeMillis())
        }
        couchIndexBridge.indexReconciliation(prefix, snap.paths.size j { i: Int -> snap.paths[i] })

        var minted = 0
        val bag = beliefBag
        if (bag != null) {
            withContext(Dispatchers.IO) {
                val mintCap = 512
                outer@ for (path in snap.paths) {
                    if (minted >= mintCap) break
                    if (!path.endsWith(".md") && !path.endsWith(".markdown") && !path.endsWith(".txt")) continue
                    val att = attachments.getAttachment(path) ?: continue
                    val surface = runCatching {
                        borg.trikeshed.cas.ContentEpistemicIngest.ingest(casStore, att.second.decodeToString())
                    }.getOrNull() ?: continue
                    for (si in 0 until surface.signals.size) {
                        if (minted >= mintCap) break@outer
                        val s = surface.signals[si]
                        bag.intake.send(
                            BeliefIntake.Mint(
                                s.copy(
                                    angular = AngularCodec.encode(
                                        relation = s.relation,
                                        taxonomyKey = path,
                                        subjectTerm = path.substringAfterLast('/'),
                                        objectTerm = s.objectCid?.take(12),
                                    ),
                                ),
                                BudgetCoord(0.5f, 0.3f, 0.5f),
                            ),
                        )
                        minted++
                    }
                }
            }
        }
        val scope = Scope(name, dir.absolutePath, kind, prefix, snap.paths.size, minted)
        scopes = scopes + scope
        scope
    }
}

/**
 * PatchWire — the patch-panel backend: full KeyMux/ModelMux access plus project
 * scope mounting, mounted on the kanban listener like the other wires.
 *
 *   GET  /api/mux/models     the discovered provider roster (name/base/model)
 *   GET  /api/mux/keys      FULL roster with key-PRESENCE flags — values never cross
 *   POST /api/mux/chat      {prompt, system?, maxTokens?, temperature?} → provider-neutral
 *                           failover chat THROUGH BrainClient→ModelMux (never around it)
 *   GET  /api/projects      mounted scopes
 *   POST /api/projects      {path} → classify (git|assets) + absorb + index + resonate
 */
class PatchWire(
    private val brain: BrainClient,
    private val scopes: ProjectScopes,
    /** Panel constructions are STORE DOCUMENTS (panels/<name>, CAS-addressed, replicated) — not browser state. */
    private val attachments: CouchAttachmentGateway? = null,
    /** Carries HtxKey (+ mux reactor) so provider calls ride the daemon's reactor. */
    private val muxContext: CoroutineContext = EmptyCoroutineContext,
) {
    suspend fun route(
        method: String,
        path: String,
        text: String,
        respond: (suspend (ByteArray) -> Unit)?,
    ): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        return when {
            method == "GET" && p == "/api/mux/models" -> json(
                mapOf(
                    "models" to brain.endpointSummaries().map {
                        mapOf("name" to it.name, "base" to it.base, "model" to it.model)
                    },
                    "lastAnswered" to brain.lastModel(),
                ),
            )

            method == "GET" && p == "/api/mux/keys" -> json(mapOf("roster" to brain.rosterStatus()))

            method == "POST" && p == "/api/mux/chat" -> {
                if (!brain.hasEndpoints()) return json(mapOf("verdict" to "no-providers", "detail" to "no provider keys in env"), 503)
                val req = parse(text)
                val prompt = req["prompt"]?.toString() ?: return json(mapOf("error" to "prompt required"), 400)
                val system = req["system"]?.toString()
                val maxTokens = (req["maxTokens"] as? Number)?.toInt() ?: 512
                val temperature = (req["temperature"] as? Number)?.toDouble() ?: 0.2
                val messages = buildList {
                    if (system != null) add("system" to system)
                    add("user" to prompt)
                }
                val result = runCatching { withContext(muxContext) { brain.chat(messages, maxTokens, temperature) } }
                result.fold(
                    onSuccess = { json(mapOf("verdict" to "ok", "model" to brain.lastModel(), "content" to it)) },
                    onFailure = { json(mapOf("verdict" to "mux-error", "detail" to (it.message ?: it.toString())), 502) },
                )
            }

            // ── panel constructions: LCNC graphs as replicated store documents ──
            method == "GET" && p == "/api/panels" -> {
                val att = attachments ?: return json(mapOf("error" to "store not wired"), 503)
                json(mapOf("panels" to att.listAttachments("panels/").map {
                    mapOf("name" to it.path.removePrefix("panels/"), "cid" to it.contentId.value, "bytes" to it.length)
                }))
            }

            method == "GET" && p.startsWith("/api/panels/") -> {
                val att = attachments ?: return json(mapOf("error" to "store not wired"), 503)
                val name = p.removePrefix("/api/panels/")
                if (!name.matches(Regex("^[a-z0-9][a-z0-9._-]*$"))) return json(mapOf("error" to "bad name"), 400)
                val doc = att.getAttachment("panels/" + name) ?: return json(mapOf("error" to "no such panel"), 404)
                JvmKanbanServer.HttpResponse(200, doc.second.decodeToString())
            }

            method == "POST" && p.startsWith("/api/panels/") -> {
                val att = attachments ?: return json(mapOf("error" to "store not wired"), 503)
                val name = p.removePrefix("/api/panels/")
                if (!name.matches(Regex("^[a-z0-9][a-z0-9._-]*$"))) return json(mapOf("error" to "bad name"), 400)
                val body = rawBody(text)
                if (body.isBlank()) return json(mapOf("error" to "empty body"), 400)
                val bytes = body.encodeToByteArray()
                val cid = borg.trikeshed.job.ContentId.of(bytes)
                att.putAttachment(
                    borg.trikeshed.util.oroboros.OroborosAttachmentRef(
                        path = "panels/" + name,
                        contentType = "application/json",
                        length = bytes.size.toLong(),
                        contentId = cid,
                        agentId = "panels-ui",
                        revision = cid.hex.take(12),
                        sequence = System.currentTimeMillis(),
                    ),
                    bytes,
                )
                json(mapOf("verdict" to "ok", "name" to name, "cid" to cid.value))
            }

            method == "GET" && p == "/api/projects" -> json(
                mapOf(
                    "scopes" to scopes.list().map {
                        mapOf("name" to it.name, "path" to it.path, "kind" to it.kind, "prefix" to it.prefix,
                            "paths" to it.paths, "minted" to it.minted)
                    },
                ),
            )

            method == "POST" && p == "/api/projects" -> {
                val req = parse(text)
                val mountPath = req["path"]?.toString() ?: return json(mapOf("error" to "path required"), 400)
                runCatching { scopes.mount(mountPath) }.fold(
                    onSuccess = {
                        json(mapOf("verdict" to "ok", "name" to it.name, "kind" to it.kind,
                            "prefix" to it.prefix, "paths" to it.paths, "minted" to it.minted))
                    },
                    onFailure = { json(mapOf("verdict" to "refused", "detail" to (it.message ?: "")), 400) },
                )
            }

            else -> null
        }
    }

    private fun json(value: Any?, status: Int = 200): JvmKanbanServer.HttpResponse =
        JvmKanbanServer.HttpResponse(status, JsonSupport.stringify(value))

    private fun rawBody(text: String): String = when {
        "\r\n\r\n" in text -> text.substringAfter("\r\n\r\n")
        "\n\n" in text -> text.substringAfter("\n\n")
        else -> text
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Map<String, Any?> {
        val body = rawBody(text)
        if (body.isBlank()) return emptyMap()
        return runCatching { JsonSupport.parse(body) as? Map<String, Any?> }.getOrNull() ?: emptyMap()
    }
}
