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
import kotlinx.coroutines.launch
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
    /** Mounted hierarchies become their OWN couch databases (shared CAS). */
    private val projectDbs: ProjectDbRegistry? = null,
    /** Mount ledger (forge home): boot replays it so project dbs survive restarts. */
    private val ledgerFile: File? = null,
    /**
     * Forge-home mirror root (`files/<name>/`). Mounted hierarchies are CLONED
     * here (APFS clonefile: instant, block-shared — the fs dedupes, so clone
     * without fear) and the db reconciles FROM the clone: the original folder
     * can move or vanish and the project survives. Uploads mirror here too, so
     * every project db has a browsable on-disk twin.
     */
    private val filesRoot: File? = null,
) {
    data class Scope(
        val name: String,
        val path: String,
        val kind: String,     // "git" | "assets"
        val prefix: String,
        val paths: Int,
        val minted: Int,
        /** Docs in the project's own database (0 for the pre-db prefix scopes). */
        val docs: Int = 0,
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
        // Sanitize, don't refuse: "My PDFs" mounts as "my-pdfs" — boom, not a regex lecture.
        val name = dir.name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.')
        require(name.isNotBlank()) { "folder name '${dir.name}' sanitizes to nothing" }
        require(scopes.none { it.name == name }) { "scope '$name' already mounted" }
        projectDbs?.refusalFor(name)?.let { throw IllegalArgumentException(it) }
        val kind = if (File(dir, ".git").exists()) "git" else "assets"

        // Clone the hierarchy into the forge home first (reflink/clonefile — the fs
        // dedupes blocks, the CAS dedupes content: both strategies, no fear). The db
        // reconciles FROM the clone, so the original folder owes us nothing after this.
        val sourceDir = cloneIntoFilesRoot(name, dir) ?: dir

        // The dropped hierarchy becomes its OWN project db: the db name is the
        // namespace, doc ids are the relative paths, blobs share the daemon CAS.
        // (Registry absent = legacy prefix-scope absorb into the shared db.)
        val registry = projectDbs
        val (prefix, scopeGateway) = if (registry != null) {
            val store = borg.trikeshed.couch.CouchStoreFactory.casBacked(casStore)
            val db = borg.trikeshed.couch.CouchDatabase(name, store, casStore)
            val gateway = CouchAttachmentGateway(store, casStore)
            val pdb = ProjectDb(name, dir.absolutePath, kind, db, store, gateway,
                borg.trikeshed.couch.CouchWireRouter(db, attachmentPrefix = ""))
            registry.register(pdb)
            "$name/" to gateway
        } else {
            ((if (kind == "git") "projects/" else "assets/") + name + "/") to attachments
        }

        val snap = withContext(Dispatchers.IO) {
            WorktreeCouchGateway(fileOps, scopeGateway, prefix = if (registry != null) "" else prefix)
                .reconcile(sourceDir.absolutePath, agentId = "oroboros", revision = kind, sequence = System.currentTimeMillis())
        }
        couchIndexBridge.indexReconciliation(
            prefix,
            snap.paths.size j { i: Int -> if (registry != null) prefix + snap.paths[i] else snap.paths[i] },
            via = scopeGateway,
            docId = { if (registry != null) it.removePrefix(prefix) else it },
        )
        ledgerAppend(name, kind, sourceDir.absolutePath)

        var minted = 0
        val bag = beliefBag
        if (bag != null) {
            withContext(Dispatchers.IO) {
                val mintCap = 512
                outer@ for (path in snap.paths) {
                    if (minted >= mintCap) break
                    if (!path.endsWith(".md") && !path.endsWith(".markdown") && !path.endsWith(".txt")) continue
                    val att = scopeGateway.getAttachment(path) ?: continue
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
        val scope = Scope(
            name, sourceDir.absolutePath, kind, prefix, snap.paths.size, minted,
            docs = projectDbs?.get(name)?.docCount ?: 0,
        )
        scopes = scopes + scope
        scope
    }

    /**
     * Browser-drop lane: modern Chrome never reveals a dropped folder's host
     * path (no file:// uri-list), so the client WALKS the hierarchy and uploads
     * the bytes. An uploaded project db persists via a per-db manifest
     * (relpath TAB cid TAB length) beside the ledger — blobs are already CAS
     * citizens, so boot rebuilds the db from the manifest alone.
     */
    suspend fun beginUpload(rawName: String, kind: String = "assets"): Scope = mutex.withLock {
        val registry = projectDbs ?: throw IllegalStateException("project dbs not wired")
        val name = rawName.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.')
        require(name.isNotBlank()) { "name '$rawName' sanitizes to nothing" }
        require(scopes.none { it.name == name }) { "scope '$name' already mounted" }
        registry.refusalFor(name)?.let { throw IllegalArgumentException(it) }
        // Chrome hides dot-dirs from the walker, so `.git` can never be the signal here:
        // the CLIENT classifies by build-system markers and tells us. git|project → "git".
        val k = if (kind == "git" || kind == "project") "git" else "assets"
        val store = borg.trikeshed.couch.CouchStoreFactory.casBacked(casStore)
        val db = borg.trikeshed.couch.CouchDatabase(name, store, casStore)
        val gateway = CouchAttachmentGateway(store, casStore)
        registry.register(
            ProjectDb(name, "@upload", k, db, store, gateway,
                borg.trikeshed.couch.CouchWireRouter(db, attachmentPrefix = "")),
        )
        manifestFileFor(name)?.parentFile?.mkdirs()
        ledgerAppend(name, k, "@upload")
        val scope = Scope(name, "@upload", k, "$name/", 0, 0, docs = 0)
        scopes = scopes + scope
        scope
    }

    /** One uploaded file into an upload-kind project db; manifest line makes it boot-durable. */
    fun uploadPut(name: String, relPath: String, bytes: ByteArray): borg.trikeshed.job.ContentId {
        val pdb = projectDbs?.get(name) ?: throw IllegalArgumentException("no project db '$name'")
        val rel = relPath.trim('/')
        require(rel.isNotBlank() && !rel.split('/').any { it == ".." }) { "bad path '$relPath'" }
        val cid = borg.trikeshed.job.ContentId.of(bytes)
        pdb.gateway.putAttachment(
            borg.trikeshed.util.oroboros.OroborosAttachmentRef(
                path = rel,
                contentType = borg.trikeshed.util.io.ContentTypes.forPath(rel),
                length = bytes.size.toLong(),
                contentId = cid,
                agentId = "browser-drop",
                revision = "upload",
                sequence = System.currentTimeMillis(),
            ),
            bytes,
        )
        manifestFileFor(name)?.let { mf ->
            runCatching { mf.appendText("$rel\t${cid.value}\t${bytes.size}\n") }
        }
        // Browsable on-disk twin under files/<name>/ — the fs dedupes it against CAS blocks.
        filesRoot?.let { fr ->
            runCatching {
                val f = File(File(fr, name), rel)
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
            }
        }
        scopes = scopes.map { if (it.name == name) it.copy(paths = it.paths + 1, docs = pdb.docCount) else it }
        return cid
    }

    private fun manifestFileFor(name: String): File? =
        ledgerFile?.let { File(it.parentFile, "project-manifests/$name.tsv") }

    /**
     * Reflink-first clone into `filesRoot/<name>`: APFS clonefile (`cp -c`),
     * btrfs/xfs reflink (`--reflink=auto`), plain copy last. The fs dedupes
     * blocks and the CAS dedupes content — both strategies, cloning is free.
     * Null (no filesRoot / all attempts failed) = mount from the original dir.
     */
    private fun cloneIntoFilesRoot(name: String, src: File): File? {
        val root = filesRoot ?: return null
        val dest = File(root, name)
        if (dest.isDirectory) return dest
        root.mkdirs()
        val attempts = listOf(
            listOf("cp", "-c", "-R", src.absolutePath, dest.absolutePath),
            listOf("cp", "-R", "--reflink=auto", src.absolutePath, dest.absolutePath),
            listOf("cp", "-R", src.absolutePath, dest.absolutePath),
        )
        for (cmd in attempts) {
            val ok = runCatching {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                p.inputStream.readBytes()
                p.waitFor() == 0
            }.getOrDefault(false)
            if (ok && dest.isDirectory) return dest
            dest.deleteRecursively()
        }
        return null
    }

    /**
     * Rebuild an upload-kind db from its manifest: blobs come straight out of CAS.
     * PER-LINE tolerant: a torn tail (kill -9 mid-append) or any malformed line is
     * SKIPPED, never allowed to abort the replay — one bad line once zeroed a
     * whole db's remount (whisper.cpp, 2026-08-25).
     */
    private suspend fun remountUpload(name: String, kind: String = "assets"): Boolean {
        val mf = manifestFileFor(name) ?: return false
        if (!mf.exists()) return false
        val scope = beginUploadInternal(name, kind) ?: return false
        var docs = 0
        var skipped = 0
        for (line in mf.readLines()) {
            runCatching {
                val parts = line.split('\t')
                if (parts.size < 2) return@runCatching
                val bytes = casStore.get(borg.trikeshed.job.ContentId(parts[1])) ?: return@runCatching
                val pdb = projectDbs?.get(name) ?: return@runCatching
                pdb.gateway.putAttachment(
                    borg.trikeshed.util.oroboros.OroborosAttachmentRef(
                        path = parts[0],
                        contentType = borg.trikeshed.util.io.ContentTypes.forPath(parts[0]),
                        length = bytes.size.toLong(),
                        contentId = borg.trikeshed.job.ContentId(parts[1]),
                        agentId = "manifest-replay",
                        revision = "upload",
                        sequence = docs.toLong() + 1,
                    ),
                    bytes,
                )
                docs++
            }.onFailure { skipped++ }
        }
        if (skipped > 0) System.err.println("[OROBOROS] project db $name manifest replay: $docs docs, $skipped malformed/missing lines skipped")
        scopes = scopes.map { if (it.name == name) it.copy(paths = docs, docs = projectDbs?.get(name)?.docCount ?: docs) else it }
        return true
    }

    /** beginUpload minus the ledger write (replay path). Null when already mounted/refused. */
    private fun beginUploadInternal(name: String, kind: String = "assets"): Scope? {
        val registry = projectDbs ?: return null
        if (scopes.any { it.name == name } || registry.refusalFor(name) != null) return null
        val store = borg.trikeshed.couch.CouchStoreFactory.casBacked(casStore)
        val db = borg.trikeshed.couch.CouchDatabase(name, store, casStore)
        val gateway = CouchAttachmentGateway(store, casStore)
        registry.register(
            ProjectDb(name, "@upload", kind, db, store, gateway,
                borg.trikeshed.couch.CouchWireRouter(db, attachmentPrefix = "")),
        )
        val scope = Scope(name, "@upload", kind, "$name/", 0, 0)
        scopes = scopes + scope
        return scope
    }

    /** Unmount: registry + ledger + manifest + files clone all released. Docs' CAS blobs stay (content-addressed). */
    suspend fun unmount(name: String): Boolean = mutex.withLock {
        val had = scopes.any { it.name == name }
        if (!had) return false
        scopes = scopes.filter { it.name != name }
        projectDbs?.remove(name)
        ledgerFile?.takeIf { it.exists() }?.let { f ->
            runCatching { f.writeText(f.readLines().filter { it.substringBefore('\t') != name }.joinToString("\n").let { if (it.isBlank()) "" else it + "\n" }) }
        }
        manifestFileFor(name)?.delete()
        filesRoot?.let { File(it, name).deleteRecursively() }
        System.err.println("[OROBOROS] project db unmounted: $name")
        true
    }

    /** Append to the mount ledger (name TAB kind TAB path), deduped by name. */
    private fun ledgerAppend(name: String, kind: String, path: String) {
        val f = ledgerFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val existing = if (f.exists()) f.readLines() else emptyList()
            if (existing.none { it.substringBefore('\t') == name }) {
                f.appendText("$name\t$kind\t$path\n")
            }
        }
    }

    /** Boot replay: dir-backed dbs re-reconcile their source; upload-kind dbs rebuild from their CAS manifest. */
    suspend fun remountLedger(): Int {
        val f = ledgerFile ?: return 0
        if (!f.exists()) return 0
        var ok = 0
        for (line in f.readLines()) {
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val (name, kind, path) = parts
            if (scopes.any { it.name == name }) continue
            // uploads are path-@upload (older ledgers: kind "upload"): rebuild from manifest+CAS
            if (path == "@upload" || kind == "upload") {
                if (runCatching { remountUpload(name, if (kind == "upload") "assets" else kind) }.getOrDefault(false)) ok++
                continue
            }
            if (!File(path).isDirectory) continue
            runCatching { mount(path) }.onSuccess { ok++ }
                .onFailure { System.err.println("[OROBOROS] project db remount failed for $name: ${it.message}") }
        }
        return ok
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
    /**
     * Mounts run HERE, detached from the request: a multi-GB absorb outlives any
     * HTTP timeout, and a client disconnect can never half-cancel a mount again.
     * Null = legacy inline mount (tests).
     */
    private val mountScope: kotlinx.coroutines.CoroutineScope? = null,
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
                            "paths" to it.paths, "minted" to it.minted, "docs" to it.docs)
                    },
                ),
            )

            method == "DELETE" && p.startsWith("/api/projects/") -> {
                val name = p.removePrefix("/api/projects/")
                if (scopes.unmount(name)) json(mapOf("verdict" to "unmounted", "name" to name))
                else json(mapOf("error" to "no such scope", "name" to name), 404)
            }

            method == "POST" && p == "/api/projects" -> {
                val req = parse(text)
                val mountPath = req["path"]?.toString() ?: return json(mapOf("error" to "path required"), 400)
                val dir = java.io.File(mountPath.removePrefix("file://"))
                if (!dir.isDirectory) return json(mapOf("verdict" to "refused", "detail" to "not a directory: $mountPath"), 400)
                val bg = mountScope
                if (bg == null) {
                    // inline (test) path
                    runCatching { scopes.mount(mountPath) }.fold(
                        onSuccess = {
                            json(mapOf("verdict" to "ok", "name" to it.name, "kind" to it.kind,
                                "prefix" to it.prefix, "paths" to it.paths, "minted" to it.minted, "docs" to it.docs))
                        },
                        onFailure = { json(mapOf("verdict" to "refused", "detail" to (it.message ?: "")), 400) },
                    )
                } else {
                    // detached: 202 now, poll GET /api/projects (or the graal map's dbs) for landing
                    System.err.println("[OROBOROS] project db mount begun (detached): $mountPath")
                    bg.launch {
                        runCatching { scopes.mount(mountPath) }.fold(
                            onSuccess = { System.err.println("[OROBOROS] project db mounted: ${it.kind} ${it.name} (${it.paths} paths, ${it.docs} docs)") },
                            onFailure = { System.err.println("[OROBOROS] project db mount REFUSED for $mountPath: ${it.message}") },
                        )
                    }
                    json(mapOf("verdict" to "mounting", "path" to mountPath), 202)
                }
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
