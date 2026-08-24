package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchReportEvent
import borg.trikeshed.couch.CouchReportReactorElement
import borg.trikeshed.couch.CouchStore
import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.graal.subvm.HermesCapsule
import borg.trikeshed.graal.vitals.JvmVitals
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * GraalWire — the Graal console: a web console with APIs, riding the same listener as everything
 * else ("port" of the PWA in the retained sense — console + APIs + CLI, not a copy of the shell).
 *
 *   GET /graal                    the console page (classpath `web/graal.html`)
 *   GET /graal.webmanifest        install manifest (reuses the forge icons)
 *   GET /futon                     the couch-CRUD companion — Futon lineage, plain document editing
 *   GET /api/graal/vitals         [JvmVitals.snapshot] + pointcut route summary
 *   GET /api/graal/pointcuts      every `pointcut/…` document as a route row
 *   GET /api/graal/map            the whole store as compact `[id, bytes]` rows — the RTS terrain
 *   GET /api/graal/dag[?id=…]     the DAG arcs the tree cannot show: shared-blob cross-links and
 *                                 pointcut→class edges for one node, or the high-degree hubs
 *   GET /api/graal/decompile?source=… source + byte-identical classpath mates, parsed by JDK 25
 *   GET /api/graal/aot             process AOT flags and configured HotSpot cache metadata
 *   GET /api/graal/aot/blob        configured opaque HotSpot AOT archive bytes
 *   POST /api/graal/aot/capture    land that archive in the replicated Couch/CAS attachment plane
 *   GET /api/graal/events         SSE: compile / deopt / gc / cpu flourishes, plus store commits
 *   POST /api/graal/ingest?name=… raw bytes → Tika/OCR when binary → store citizen under dropzone/…
 *                                 → plan-shape gate → board cards when it parses as a plan
 *   POST /api/graal/capsule/spawn {id?}              a hermes sleeve: GraalPy + its own btrfs subvolume
 *   POST /api/graal/capsule/{id}/stdin {text}         one line typed at the captured shell
 *   GET  /api/graal/capsule/{id}/output               the VT scrollback so far (poll, not stream)
 *   POST /api/graal/capsule/{id}/kill                 interrupt + close the guest
 *
 * CLI twin: `borg.trikeshed.graal.vitals.GraalConsoleCli` (vitals | watch) reads the same
 * instrument cluster for a JVM you are inside of.
 */
class GraalWire(
    private val vitals: JvmVitals,
    private val couchStore: CouchStore?,
    private val report: CouchReportReactorElement?,
    private val scope: CoroutineScope,
    private val couchDatabase: CouchDatabase? = null,
    /** Sub-VM host, when mounted: its Spawned/Evaluated/Revoked/Landed events join the flourish feed. */
    private val vmHost: borg.trikeshed.vm.VmHost? = null,
) {
    companion object {
        const val EVENTS_PATH = "/api/graal/events"
        val STREAMING: Set<String> = setOf(EVENTS_PATH)
    }

    suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        return when {
            method == "GET" && (p == "/graal" || p == "/graal/") -> page()
            method == "GET" && (p == "/futon" || p == "/futon/") -> asset("web/futon.html", "text/html; charset=utf-8")
            method == "GET" && p == "/graal.webmanifest" -> JvmKanbanServer.HttpResponse(200, MANIFEST, "application/manifest+json; charset=utf-8")
            method == "GET" && p == "/api/graal/vitals" -> JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(vitals.snapshot() + ("pointcuts" to pointcutSummary())))
            method == "GET" && p == "/api/graal/pointcuts" -> JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(mapOf("routes" to pointcutRoutes())))
            method == "GET" && p == "/api/graal/map" -> JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(mapMap()))
            method == "GET" && p == "/api/graal/dag" -> {
                val q = borg.trikeshed.utils.rfxhttp.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
                JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(q["id"]?.let { dagFor(it) } ?: dagHubs()))
            }
            method == "GET" && p == "/api/graal/decompile" -> withContext(Dispatchers.IO) {
                val source = borg.trikeshed.utils.rfxhttp.CouchHttpSurface
                    .parseQuery(path.substringAfter('?', ""))["source"]
                    ?: return@withContext JvmKanbanServer.HttpResponse(400, """{"error":"source_required"}""")
                val db = couchDatabase
                    ?: return@withContext JvmKanbanServer.HttpResponse(503, """{"error":"cas_database_unavailable"}""")
                val projection = ClasspathSourceProjection(db).project(source)
                JvmKanbanServer.HttpResponse(
                    if (projection["error"] == null) 200 else 404,
                    JsonSupport.stringify(projection),
                )
            }
            method == "GET" && p == "/api/graal/aot" -> withContext(Dispatchers.IO) {
                JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(HotSpotAotBlobAccess.snapshot()))
            }
            method == "GET" && p == "/api/graal/aot/blob" -> withContext(Dispatchers.IO) {
                val blob = HotSpotAotBlobAccess.blob()
                    ?: return@withContext JvmKanbanServer.HttpResponse(404, """{"error":"aot_blob_unavailable"}""")
                JvmKanbanServer.HttpResponse(200, "", "application/x-java-aot-cache", blob.second)
            }
            method == "POST" && p == "/api/graal/aot/capture" -> withContext(Dispatchers.IO) {
                val db = couchDatabase
                    ?: return@withContext JvmKanbanServer.HttpResponse(503, """{"error":"cas_database_unavailable"}""")
                val captured = HotSpotAotBlobAccess.capture(db)
                JvmKanbanServer.HttpResponse(
                    if (captured["ok"] == true) 201 else 409,
                    JsonSupport.stringify(captured),
                )
            }
            method == "GET" && p == EVENTS_PATH && respond != null -> { stream(respond); JvmKanbanServer.HttpResponse(200, "") }
            else -> null
        }
    }

    private fun page(): JvmKanbanServer.HttpResponse = asset("web/graal.html", "text/html; charset=utf-8")

    private fun asset(resource: String, contentType: String): JvmKanbanServer.HttpResponse {
        val bytes = GraalWire::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: return JvmKanbanServer.HttpResponse(404, """{"error":"asset_missing","resource":"$resource"}""")
        return JvmKanbanServer.HttpResponse(200, "", contentType, bytes)
    }

    /**
     * The 30k-foot terrain: every live document as `[id, bytes]`. The console builds the
     * prefix-tree territories client-side and lays them out as a zoomable treemap; bytes come
     * from the attachment `length` field where present, else the field count as a stand-in mass.
     */
    private fun mapMap(): Map<String, Any?> {
        val store = couchStore
        // last commit sequence per doc: the store's own causal order
        val lastSeq = HashMap<String, Long>()
        if (store != null) {
            val frames = store.changes.series()
            for (i in 0 until frames.size) {
                val f = frames[i]
                lastSeq[f.docId] = f.sequence + 1
            }
        }
        val rows = store?.all().orEmpty()
            .filter { d -> d.fields.none { it.name == "_deleted" && it.value == true } }
            .map { d ->
                val len = (d.fields.firstOrNull { it.name == "length" }?.value as? String)?.toLongOrNull()
                val gen = store?.head?.getRev(d.id)?.substringBefore('-')?.toIntOrNull() ?: 1
                listOf(d.id, len ?: (d.fields.size.toLong() * 64), lastSeq[d.id] ?: 0L, gen)
            }
        return mapOf("rows" to rows, "at" to System.currentTimeMillis())
    }

    /**
     * The ingest lane — the product loop, not a graphics demo. Every drop becomes a store citizen:
     * raw bytes as a CAS blob + attachment doc under `dropzone/<name>`; binary goes through
     * Tika/Tesseract to markdown; the extraction lands beside it as `dropzone/<name>.extract.md`;
     * and if the text passes [ForgeKanbanIngest.isPlan]'s shape grammar it is persisted into the
     * board — the terrain, the shape strip and the kanban all grow from one drop.
     * RawRoute (binary-safe): register beside CouchWire in `rawRoutes`.
     */
    suspend fun ingestRoute(method: String, path: String, payload: ByteArray, respond: (suspend (ByteArray) -> Unit)?): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        if (p == "/api/graal/capsule/list" || p == "/api/graal/capsule/spawn" || p.startsWith("/api/graal/capsule/")) return capsuleRoute(method, p, payload)
        if (p != "/api/graal/ingest") return null
        if (method != "POST") return JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
        val database = couchDatabase ?: return JvmKanbanServer.HttpResponse(501, """{"error":"no store mounted"}""")
        val q = borg.trikeshed.utils.rfxhttp.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
        val rawName = (q["name"] ?: "drop-${System.currentTimeMillis()}").substringAfterLast('/').substringAfterLast('\\')
        val name = rawName.replace(Regex("[^\\w .()-]+"), "_").ifBlank { "drop" }
        val bytes = CouchWire.bodyOf(payload)
        if (bytes.isEmpty()) return JvmKanbanServer.HttpResponse(400, """{"error":"empty body"}""")
        return try {
            val texty = name.lowercase().let { n -> listOf(".md", ".txt", ".markdown", ".json", ".yaml", ".yml", ".csv").any { n.endsWith(it) } }
            // 1) the original bytes become a store citizen
            val cid = database.blockPut(bytes)
            val docId = "dropzone/$name"
            putAttachmentDoc(database, docId, borg.trikeshed.util.io.ContentTypes.forPath(name), cid, bytes.size.toLong())
            // 2) binary → markdown through Tika/Tesseract; text is its own markdown
            val markdown = if (texty) bytes.decodeToString() else {
                val tmp = java.nio.file.Files.createTempFile("graal-ingest-", "-$name")
                try {
                    java.nio.file.Files.write(tmp, bytes)
                    borg.trikeshed.kanban.JvmTikaIngestAdapter.extractToMarkdown(tmp)
                } finally { runCatching { java.nio.file.Files.deleteIfExists(tmp) } }
            }
            var extractId: String? = null
            if (!texty && markdown.isNotBlank()) {
                val mdBytes = markdown.encodeToByteArray()
                val mdCid = database.blockPut(mdBytes)
                extractId = "$docId.extract.md"
                putAttachmentDoc(database, extractId, "text/markdown", mdCid, mdBytes.size.toLong())
            }
            // 3) the plan gate: shape grammar decides whether this drop is board material
            val plan = runCatching { borg.trikeshed.kanban.ForgeKanbanIngest.isPlan(markdown) }.getOrDefault(false)
            var persisted = false
            if (plan && q["persist"] != null) {
                val tmpMd = java.nio.file.Files.createTempFile("graal-plan-", ".md")
                try {
                    java.nio.file.Files.write(tmpMd, markdown.encodeToByteArray())
                    borg.trikeshed.kanban.ForgeKanbanIngest.persistMarkdown(q["persist"]!!, tmpMd.toString())
                    persisted = true
                } finally { runCatching { java.nio.file.Files.deleteIfExists(tmpMd) } }
            }
            val shapeKey = runCatching { borg.trikeshed.kanban.ForgeKanbanIngest.planShape(markdown) }.getOrDefault("")
            JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(mapOf(
                "ok" to true, "id" to docId, "cid" to cid.value, "bytes" to bytes.size,
                "extracted" to extractId, "chars" to markdown.length, "shape" to shapeKey.take(80),
                "plan" to plan, "persisted" to persisted,
            )))
        } catch (t: Throwable) {
            JvmKanbanServer.HttpResponse(500, JsonSupport.stringify(mapOf("error" to (t.message ?: t.toString()))))
        }
    }

    // ── capsule: the hermes sleeve's captured VT shell ────────────

    private fun json(status: Int, v: Any?) = JvmKanbanServer.HttpResponse(status, JsonSupport.stringify(v))

    private fun capsuleRoute(method: String, p: String, payload: ByteArray): JvmKanbanServer.HttpResponse {
        val tail = p.removePrefix("/api/graal/capsule/")
        if (tail == "list") {
            return json(200, mapOf("capsules" to HermesCapsule.registry.map { (id, c) -> mapOf("id" to id, "alive" to c.alive) }))
        }
        if (tail == "spawn") {
            if (method != "POST") return json(405, mapOf("error" to "method_not_allowed"))
            @Suppress("UNCHECKED_CAST")
            val body = runCatching { JsonSupport.parse(CouchWire.bodyOf(payload).decodeToString()) as? Map<String, Any?> }.getOrNull().orEmpty()
            val id = (body["id"] as? String)?.takeIf { it.isNotBlank() } ?: "hermes-${System.currentTimeMillis() % 100000}"
            if (!id.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) return json(400, mapOf("error" to "invalid capsule id"))
            if (HermesCapsule.registry[id]?.alive == true) return json(409, mapOf("error" to "already running", "id" to id))
            val terminal = (vmHost as? borg.trikeshed.vm.HypervisorVmHost)?.terminals?.open(
                id,
                borg.trikeshed.pointcut.VmFacet.GRAAL_PYTHON,
                "capsule",
            )
            val capsule = HermesCapsule(id, terminal)
            HermesCapsule.registry[id] = capsule
            capsule.start(scope)
            return json(202, mapOf("ok" to true, "id" to id, "terminal" to "/vm-terminal?id=$id"))
        }
        val parts = tail.split('/')
        val id = parts.getOrNull(0) ?: return json(404, mapOf("error" to "missing capsule id"))
        val capsule = HermesCapsule.registry[id] ?: return json(404, mapOf("error" to "no such capsule", "id" to id))
        return when (parts.getOrNull(1)) {
            "stdin" -> {
                if (method != "POST") return json(405, mapOf("error" to "method_not_allowed"))
                @Suppress("UNCHECKED_CAST")
                val body = runCatching { JsonSupport.parse(CouchWire.bodyOf(payload).decodeToString()) as? Map<String, Any?> }.getOrNull().orEmpty()
                capsule.type((body["text"] as? String).orEmpty())
                json(200, mapOf("ok" to true))
            }
            "output" -> json(200, mapOf("id" to id, "alive" to capsule.alive, "text" to capsule.output()))
            "kill" -> { capsule.kill(); json(200, mapOf("ok" to true)) }
            else -> json(404, mapOf("error" to "unknown capsule route"))
        }
    }

    private fun putAttachmentDoc(database: borg.trikeshed.couch.CouchDatabase, id: String, contentType: String, cid: borg.trikeshed.job.ContentId, length: Long) {
        val doc = borg.trikeshed.couch.Document(id, listOf(
            borg.trikeshed.couch.Field("contentType", contentType),
            borg.trikeshed.couch.Field("length", length.toString()),
            borg.trikeshed.couch.Field("contentId", cid.value),
            borg.trikeshed.couch.Field("agentId", "dropzone"),
            borg.trikeshed.couch.Field("revision", "drop"),
            borg.trikeshed.couch.Field("sequence", System.currentTimeMillis().toString()),
        ))
        database.store.put(doc, database.store.head.getRev(id))
    }

    // ── DAG arcs: what the prefix tree cannot show ───────────────
    // The terrain is a tree of ids, but the blackboard is a DAG: many documents name the same CAS
    // blob (dedup), and pointcut routes point INTO class attachments. These are the cross-links.

    private fun cidOf(d: borg.trikeshed.couch.Document): String? =
        d.fields.firstOrNull { it.name == "contentId" }?.value as? String

    /** Edges for one node: every other doc sharing its blob, plus pointcut→class targets. */
    private fun dagFor(id: String): Map<String, Any?> {
        val all = couchStore?.all().orEmpty()
        val me = all.firstOrNull { it.id == id } ?: return mapOf("node" to id, "edges" to emptyList<Any?>())
        val edges = mutableListOf<Map<String, Any?>>()
        val cid = cidOf(me)
        if (cid != null) for (d in all) {
            if (d.id != id && cidOf(d) == cid) edges += mapOf("to" to d.id, "kind" to "shared-blob", "cid" to cid)
        }
        if (id.startsWith("pointcut/")) {
            @Suppress("UNCHECKED_CAST")
            val cls = (me.fields.firstOrNull { it.name == "coordinate" }?.value as? Map<String, Any?>)?.get("className")?.toString()
            if (cls != null) {
                val rel = cls.replace('.', '/') + ".class"
                for (d in all) if (d.id.endsWith(rel)) edges += mapOf("to" to d.id, "kind" to "pointcut-target")
            }
        } else {
            // reverse: pointcut routes aimed at this class
            if (id.endsWith(".class")) {
                val stem = id.substringAfterLast('/').removeSuffix(".class")
                for (d in all) if (d.id.startsWith("pointcut/")) {
                    @Suppress("UNCHECKED_CAST")
                    val cls = (d.fields.firstOrNull { it.name == "coordinate" }?.value as? Map<String, Any?>)?.get("className")?.toString()
                    if (cls != null && cls.substringAfterLast('.') == stem) edges += mapOf("to" to d.id, "kind" to "pointcut-source")
                }
            }
        }
        return mapOf("node" to id, "cid" to cid, "edges" to edges.take(200))
    }

    /** The DAG's high-degree vertices: blobs named by more than one document (dedup hubs). */
    private fun dagHubs(): Map<String, Any?> {
        val byCid = HashMap<String, MutableList<String>>()
        for (d in couchStore?.all().orEmpty()) {
            val cid = cidOf(d) ?: continue
            byCid.getOrPut(cid) { mutableListOf() } += d.id
        }
        val hubs = byCid.entries.filter { it.value.size > 1 }.sortedByDescending { it.value.size }.take(60)
            .map { (cid, ids) -> mapOf("cid" to cid, "degree" to ids.size, "ids" to ids.take(24)) }
        return mapOf("hubs" to hubs)
    }

    // ── pointcut routes: the `pointcut/…` plane of the store ─────

    private fun pointcutDocs() = couchStore?.all().orEmpty().filter { it.id.startsWith("pointcut/") }

    private fun pointcutSummary(): Map<String, Any?> {
        val docs = pointcutDocs()
        return mapOf(
            "routes" to docs.size,
            "byFacet" to docs.groupingBy { d -> d.fields.firstOrNull { it.name == "facet" }?.value?.toString() ?: "?" }.eachCount(),
        )
    }

    private fun pointcutRoutes(): List<Map<String, Any?>> = pointcutDocs().map { d ->
        fun f(n: String): Any? = d.fields.firstOrNull { it.name == n }?.value
        @Suppress("UNCHECKED_CAST")
        val coord = f("coordinate") as? Map<String, Any?> ?: emptyMap()
        mapOf(
            "route" to d.id,
            "facet" to f("facet"),
            "property" to f("property"),
            "value" to f("value"),
            "mark" to f("mark"),
            "className" to coord["className"],
            "methodName" to coord["methodName"],
            "bci" to coord["bytecodeOffset"],
        )
    }.sortedBy { it["route"].toString() }

    // ── the flourish feed ────────────────────────────────────────

    private suspend fun stream(respond: suspend (ByteArray) -> Unit) {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        respond(head.toByteArray(StandardCharsets.UTF_8))
        val out = Channel<String>(capacity = 256)
        val jobs = mutableListOf(
            vitals.events.onEach { e ->
                out.trySend(JsonSupport.stringify(mapOf("kind" to e.kind, "at" to e.atMs) + e.detail.mapKeys { (k, _) -> k }))
            }.launchIn(scope),
        )
        report?.let { r ->
            jobs += r.events.onEach { e ->
                if (e is CouchReportEvent.Committed) {
                    out.trySend(JsonSupport.stringify(mapOf("kind" to "commit", "id" to e.docId, "seq" to e.seq, "deleted" to e.deleted, "at" to e.timestampMs)))
                }
            }.launchIn(scope)
        }
        vmHost?.let { h ->
            // Sub-VM runs were previously silent: a spawn/eval/revoke on /api/vm had no signal on
            // the console until the next 5s poll. These land on the same feed the terrain uses.
            jobs += h.events.onEach { e ->
                val m = e.toMap()
                out.trySend(JsonSupport.stringify(mapOf("kind" to "vm", "vmKind" to m["kind"]) + m.filterKeys { it != "kind" }))
            }.launchIn(scope)
        }
        try {
            for (line in out) {
                respond("data: $line\n\n".toByteArray(StandardCharsets.UTF_8))
            }
        } catch (_: Throwable) {
            // client went away — the normal end of a feed
        } finally {
            jobs.forEach { it.cancel() }
            out.close()
        }
    }
}

private val MANIFEST = """
{
  "name": "Graal Console",
  "short_name": "Graal",
  "start_url": "/graal",
  "display": "standalone",
  "background_color": "#0b0e14",
  "theme_color": "#0b0e14",
  "icons": [
    { "src": "/icons/forge-icon.svg", "sizes": "any", "type": "image/svg+xml" },
    { "src": "/icons/forge-icon-maskable.svg", "sizes": "any", "type": "image/svg+xml", "purpose": "maskable" }
  ]
}
""".trimIndent()
