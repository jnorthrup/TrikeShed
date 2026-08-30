package borg.trikeshed.forge.server

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.job.ContentId
import borg.trikeshed.modelmux.Frame
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
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.toSeries
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
 * PatchWire — the mux/projects wire: full KeyMux/ModelMux access plus project
 * scope mounting, mounted on the kanban listener like the other wires.
 *
 *   GET  /api/mux/models     the discovered provider roster (name/base/model)
 *   GET  /api/mux/keys      FULL roster with key-PRESENCE flags — values never cross
 *   GET  /api/mux/standings  quota-legion standings (reactor roster × ledger, usable-first)
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
    /** Tika/OCR mining over project dbs; extracts land as `.extract.md` citizens + belief mints. */
    private val miner: ProjectMiner? = null,
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

            // The legion made visible: reactor roster × quota ledger, usable-first.
            // Rides muxContext because the roster is the REACTOR's — no context, no keys.
            method == "GET" && p == "/api/mux/standings" -> {
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                val standings = runCatching { withContext(muxContext) { brain.quotaStandings(now) } }
                    .getOrDefault(emptyList())
                json(mapOf(
                    "atMs" to now,
                    "standings" to standings.map { s ->
                        mapOf(
                            "keyId" to s.keyId, "provider" to s.provider,
                            "limit" to s.limit, "spent" to s.spent, "remaining" to s.remaining,
                            "exhausted" to s.exhausted, "usable" to s.isUsable,
                            "utilization" to s.utilization, "accessCount" to s.accessCount,
                            "windowStartMs" to s.windowStartMs, "windowMs" to s.windowMs,
                        )
                    },
                ))
            }

            // ── keymux endpoint registry: user-declared provider endpoints
            // beside the built-in roster. NAMES AND ADDRESSES ONLY — key
            // values never cross the wire (env var NAMES point at them).
            // Stored in the forge home (keymux/endpoints attachment);
            // consumption by routing stays the model code's own decision.
            method == "GET" && p == "/api/mux/endpoints" -> {
                val att = attachments ?: return json(mapOf("error" to "store not wired"), 503)
                json(mapOf("builtin" to brain.rosterStatus(), "user" to loadEndpointRegistry(att)))
            }
            method == "POST" && p == "/api/mux/endpoints" -> {
                val att = attachments ?: return json(mapOf("error" to "store not wired"), 503)
                val req = parse(text)
                val name = req["name"]?.toString()?.lowercase()?.trim().orEmpty()
                if (!name.matches(Regex("^[a-z0-9][a-z0-9._-]*$"))) return json(mapOf("error" to "bad name"), 400)
                val entry = mapOf(
                    "name" to name,
                    "base" to (req["base"]?.toString() ?: ""),
                    "model" to (req["model"]?.toString() ?: ""),
                    "envVar" to (req["envVar"]?.toString() ?: ""),
                    "flags" to ((req["flags"] as? Map<*, *>)?.entries?.associate { it.key.toString() to (it.value == true) } ?: emptyMap()),
                )
                val next = loadEndpointRegistry(att).filter { it["name"] != name } + listOf(entry)
                saveEndpointRegistry(att, next)
                json(mapOf("verdict" to "ok", "count" to next.size))
            }
            method == "DELETE" && p.startsWith("/api/mux/endpoints/") -> {
                val att = attachments ?: return json(mapOf("error" to "store not wired"), 503)
                val name = p.removePrefix("/api/mux/endpoints/").trimEnd('/')
                val cur = loadEndpointRegistry(att)
                val next = cur.filter { it["name"] != name }
                if (next.size == cur.size) return json(mapOf("error" to "no such endpoint", "name" to name), 404)
                saveEndpointRegistry(att, next)
                json(mapOf("verdict" to "removed", "name" to name))
            }

            method == "POST" && p == "/api/mux/chat" -> {
                if (!brain.hasEndpoints()) return json(mapOf("verdict" to "no-providers", "detail" to "no provider keys in env"), 503)
                val req = parse(text)
                val prompt = req["prompt"]?.toString() ?: return json(mapOf("error" to "prompt required"), 400)
                val system = req["system"]?.toString()
                val maxTokens = (req["maxTokens"] as? Number)?.toInt() ?: 512
                val temperature = (req["temperature"] as? Number)?.toDouble() ?: 0.2
                // Plan step 5: the caller may carry a conversation identity.
                // With one, the wire sends cid + delta — the prompt rides the
                // rolling frame chain instead of a stateless rebuilt
                // [system, user] pair; receipts stamp the cid so affinity and
                // the commander view can reconcile them.
                val contextId = req["contextId"]?.toString()?.takeIf { it.isNotBlank() }
                val messages = if (contextId != null) {
                    val parent = runCatching { ContentId(contextId) }.getOrNull()
                        ?: return json(mapOf("error" to "bad_contextId", "detail" to "contextId must be a sha256:<hex> ContentId"), 400)
                    val delta = system?.let { "$it\n\n" }.orEmpty() + prompt
                    listOf("system" to parent.value, "user" to delta)
                } else {
                    buildList {
                        if (system != null) add("system" to system)
                        add("user" to prompt)
                    }
                }
                val result = runCatching {
                    withContext(muxContext) { brain.chat(messages, maxTokens, temperature, contextId) }
                }
                result.fold(
                    onSuccess = {
                        val resp = mutableMapOf<String, Any?>(
                            "verdict" to "ok",
                            "model" to brain.lastModel(),
                            "content" to it,
                        )
                        if (contextId != null) {
                            // the child cid the NEXT call appends to:
                            // cid_child = H(cid_parent ++ answer)
                            val child = Frame.append(
                                Frame(cid = ContentId(contextId), parent = null, turn = ByteArray(0)),
                                it.encodeToByteArray(),
                            )
                            resp["contextId"] = child.cid.value
                        }
                        json(resp)
                    },
                    onFailure = { json(mapOf("verdict" to "mux-error", "detail" to (it.message ?: it.toString())), 502) },
                )
            }

            // (The /api/panels family — save/load/list/presets for the
            // revived concentric editor at /panels — is defined below. Stored
            // programs are also offered via presets + ModuleContext.
            // programLoader; execution is /api/lcnc/run either way. Mating
            // stays as pure vocabulary logic below — it never needed the
            // page, revived or not.)
            method == "GET" && p == "/api/lcnc/mating-options" -> {
                val query = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
                val sourceType = query["sourceType"]
                    ?: return json(mapOf("error" to "sourceType_required"), 400)
                val sourcePort = query["sourcePort"]
                    ?: return json(mapOf("error" to "sourcePort_required"), 400)
                val source = borg.trikeshed.lcnc.LcncNode("source", sourceType)
                val program = borg.trikeshed.lcnc.LcncProgram("options", listOf(source).toSeries(), emptySeriesOf())
                // The mate-menu list: kind-compatible, EVIDENCE-ordered (wire
                // bigrams counted over the offered corpus), q = the popup's
                // text-entry filter lane.
                val corpus = borg.trikeshed.lcnc.LcncPresets.all().map { (n, doc) ->
                    borg.trikeshed.lcnc.LcncProgramConfix.fromJson(n, doc)
                }
                // The surface resolves a RING port's kind (it lives in the node's
                // scope.out child, not in the `scope` contract) and passes it here as
                // `kind`; "*" means the ring declared none and accepts everything.
                // Without it this route was node-blind and answered "no compatible
                // mate" for cables the canvas would happily let you drop.
                val kindParam = query["kind"]?.takeIf { it.isNotBlank() }
                val override = when (kindParam) {
                    null -> null
                    "*" -> borg.trikeshed.lcnc.LcncTypeCheck.PortKind(null, generic = true)
                    else -> borg.trikeshed.lcnc.LcncTypeCheck.PortKind(kindParam, generic = false)
                }
                json(mapOf(
                    "generic" to (override?.generic ?: false),
                    "options" to borg.trikeshed.lcnc.LcncMating
                        .rankedCandidates(program, "source", sourcePort, corpus, query["q"] ?: "", override).map {
                            mapOf("type" to it.type, "inputPort" to it.inputPort, "title" to it.title)
                        },
                ))
            }
            method == "GET" && p == "/api/lcnc/fills" -> {
                val query = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
                val type = query["type"] ?: return json(mapOf("error" to "type_required"), 400)
                val corpus = borg.trikeshed.lcnc.LcncPresets.all().map { (n, doc) ->
                    borg.trikeshed.lcnc.LcncProgramConfix.fromJson(n, doc)
                }
                val fills = borg.trikeshed.lcnc.LcncMating.paramFills(type, corpus)
                json(mapOf("type" to type, "fills" to fills.map {
                    mapOf("param" to it.param, "value" to it.value, "count" to it.count)
                }))
            }
            method == "GET" && p == "/api/lcnc/autowire" -> {
                val query = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
                val fromType = query["from"] ?: return json(mapOf("error" to "from_required"), 400)
                val toType = query["to"] ?: return json(mapOf("error" to "to_required"), 400)
                val nodes = listOf(
                    borg.trikeshed.lcnc.LcncNode("from", fromType),
                    borg.trikeshed.lcnc.LcncNode("to", toType),
                ).toSeries()
                val program = borg.trikeshed.lcnc.LcncProgram("autowire", nodes, emptySeriesOf())
                val result = borg.trikeshed.lcnc.LcncMating.autoWire(program, "from", "to")
                json(mapOf(
                    "from" to fromType, "to" to toType,
                    "proposed" to result.wire?.let { mapOf(
                        "fromNode" to it.fromNode, "fromPort" to it.fromPort,
                        "toNode" to it.toNode, "toPort" to it.toPort,
                    ) },
                    "candidates" to result.candidates.map { mapOf(
                        "fromPort" to it.fromPort, "toPort" to it.toPort, "kind" to it.kind,
                    ) },
                    "ambiguous" to (result.candidates.size > 1),
                ))
            }
            method == "GET" && p == "/api/projects" -> json(
                mapOf(
                    "scopes" to scopes.list().map {
                        mapOf("name" to it.name, "path" to it.path, "kind" to it.kind, "prefix" to it.prefix,
                            "paths" to it.paths, "minted" to it.minted, "docs" to it.docs)
                    },
                ),
            )

            // ── panel constructions: LCNC graphs as replicated store documents ──
            // (revived: the concentric editor at /panels saves/loads HERE, not
            // localStorage — panels/<name> attachments, CAS-addressed.)
            method == "GET" && p == "/api/panels" -> {
                val att = attachments ?: return json(mapOf("error" to "store not wired"), 503)
                json(mapOf("panels" to att.listAttachments("panels/").map {
                    mapOf("name" to it.path.removePrefix("panels/"), "cid" to it.contentId.value, "bytes" to it.length)
                }))
            }
            // Offered, never installed: the gallery's preset lane. Before the
            // generic name branch — "presets" is a reserved word, not a panel.
            // The document is what runs; the description is what makes it adoptable.
            // Both are server-authored, so the gallery renders plain language rather
            // than inventing its own (or showing a file name and a node count).
            method == "GET" && p == "/api/panels/presets" -> json(mapOf(
                "presets" to borg.trikeshed.lcnc.LcncPresets.all().map { (name, doc) ->
                    val info = borg.trikeshed.lcnc.LcncPresets.info(name)
                    linkedMapOf(
                        "name" to name,
                        "title" to (info?.title ?: name),
                        "does" to info?.does.orEmpty(),
                        "needs" to info?.needs.orEmpty(),
                        "see" to info?.see.orEmpty(),
                        "tweakFirst" to info?.tweakFirst.orEmpty(),
                        "document" to JsonSupport.parse(doc),
                    )
                },
            ))
            method == "GET" && p.startsWith("/api/panels/") -> {
                val att = attachments ?: return json(mapOf("error" to "store not wired"), 503)
                val name = p.removePrefix("/api/panels/").trimEnd('/')
                if (!name.matches(Regex("^[a-z0-9][a-z0-9._-]*$"))) return json(mapOf("error" to "bad name"), 400)
                val doc = att.getAttachment("panels/" + name) ?: return json(mapOf("error" to "no such panel"), 404)
                JvmKanbanServer.HttpResponse(200, doc.second.decodeToString())
            }
            method == "POST" && p.startsWith("/api/panels/") -> {
                val att = attachments ?: return json(mapOf("error" to "store not wired"), 503)
                val name = p.removePrefix("/api/panels/").trimEnd('/')
                if (!name.matches(Regex("^[a-z0-9][a-z0-9._-]*$"))) return json(mapOf("error" to "bad name"), 400)
                val programJson = rawBody(text)
                val program = runCatching { borg.trikeshed.lcnc.LcncProgramConfix.fromJson(name, programJson) }
                    .getOrElse { return json(mapOf("error" to (it.message ?: "bad program")), 400) }
                val bytes = borg.trikeshed.lcnc.LcncProgramConfix.toJson(program).encodeToByteArray()
                val cid = borg.trikeshed.job.ContentId.of(bytes)
                att.putAttachment(
                    borg.trikeshed.util.oroboros.OroborosAttachmentRef(
                        path = "panels/$name", contentType = "application/json", length = bytes.size.toLong(),
                        contentId = cid, agentId = "panels-editor", revision = cid.hex.take(12),
                        sequence = System.currentTimeMillis(),
                    ), bytes,
                )
                json(mapOf("verdict" to "ok", "cid" to cid.value))
            }

            method == "POST" && p.startsWith("/api/projects/") && p.endsWith("/mine") -> {
                val name = p.removePrefix("/api/projects/").removeSuffix("/mine")
                val m = miner ?: return json(mapOf("error" to "miner not wired"), 503)
                val bg = mountScope
                if (bg == null) {
                    runCatching { m.mine(name) }.fold(
                        onSuccess = { json(mapOf("verdict" to "mined", "extracted" to it.extracted, "minted" to it.minted, "skipped" to it.skipped, "failed" to it.failed)) },
                        onFailure = { json(mapOf("verdict" to "refused", "detail" to (it.message ?: "")), 400) },
                    )
                } else {
                    System.err.println("[OROBOROS] mining begun (detached): $name")
                    bg.launch { runCatching { m.mine(name) }.onFailure { System.err.println("[OROBOROS] mine FAILED for $name: ${it.message}") } }
                    json(mapOf("verdict" to "mining", "name" to name), 202)
                }
            }

            method == "GET" && p.startsWith("/api/projects/") && p.endsWith("/mine") -> {
                val name = p.removePrefix("/api/projects/").removeSuffix("/mine")
                val prog = miner?.progress(name) ?: return json(mapOf("error" to "no mining run for '$name'"), 404)
                json(mapOf("total" to prog.total, "extracted" to prog.extracted, "minted" to prog.minted,
                    "skipped" to prog.skipped, "failed" to prog.failed, "done" to prog.done, "note" to prog.note))
            }

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

    @Suppress("UNCHECKED_CAST")
    private fun loadEndpointRegistry(att: CouchAttachmentGateway): List<Map<String, Any?>> =
        att.getAttachment("keymux/endpoints")?.let { (_, bytes) ->
            runCatching {
                (JsonSupport.parse(bytes.decodeToString()) as? List<*>)?.filterIsInstance<Map<String, Any?>>()
            }.getOrNull()
        } ?: emptyList()

    private fun saveEndpointRegistry(att: CouchAttachmentGateway, entries: List<Map<String, Any?>>) {
        val bytes = JsonSupport.stringify(entries).encodeToByteArray()
        val cid = ContentId.of(bytes)
        att.putAttachment(
            borg.trikeshed.util.oroboros.OroborosAttachmentRef(
                path = "keymux/endpoints", contentType = "application/json", length = bytes.size.toLong(),
                contentId = cid, agentId = "keymux-dlg", revision = cid.hex.take(12),
                sequence = System.currentTimeMillis(),
            ),
            bytes,
        )
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
