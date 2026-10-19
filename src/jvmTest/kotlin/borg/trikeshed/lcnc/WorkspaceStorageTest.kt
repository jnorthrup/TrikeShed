package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.couch.isam.*
import borg.trikeshed.couch.persistence.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.isam.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.module.WorkspaceService
import borg.trikeshed.kanban.module.KanbanModule
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.*
import borg.trikeshed.vm.HypervisorVmHost
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceStorageTest {
    class Fixture(val root: Path, val scope: CoroutineScope, val dictionaries: Boolean = false) {
        val files = JvmFileOperations()
        val cas = FileCasStore(files, root.resolve("cas").toString())
        val commits = CouchCommitStore(cas, StorageDurability.DURABLE, files, root.resolve("couch").toString())
        val couch = Couch("workspace", CouchStoreFactory.durableCasBacked(cas, commits), cas,
            localBacking = commits.asLocalBacking())
        val location = root.resolve("dense").toString()
        val resolvedDictionaries = mutableListOf<ContentId>()
        fun codec(): ((ByteArray) -> IsamLeafCodec)? = if (!dictionaries) null else { bytes ->
            resolvedDictionaries.add(ContentId.of(bytes)); ZstdDictionaryCodec(bytes)
        }
        var storage = ConfixIsamElement(scope, couch.store.changes, location, files, cas, dictionaryCodec = codec())
        val board = ConfixBlackboard.empty()
        fun service(moduleScope: CoroutineScope = scope): WorkspaceService = WorkspaceService(ModuleContext(
            couch = couch, rete = ReteNetwork(), productions = ReteProductionRegistry(), beliefBag = null,
            turnReview = null, blackboard = board, casStore = cas, attachments = CouchAttachmentGateway(couch.store, cas),
            routes = ModuleRouteRegistry(), scope = moduleScope, clock = { 123L }, stateDir = root.toFile(), confixIsam = storage,
        ))
        suspend fun reopen() {
            storage.drain()
            storage = ConfixIsamElement(scope, couch.store.changes, location, files, cas, dictionaryCodec = codec())
        }
        suspend fun close() { try { storage.drain() } finally { commits.close() } }
    }

    suspend fun fixture(scope: CoroutineScope, dictionaries: Boolean = false, body: suspend (Fixture) -> Unit) {
        val root = Files.createTempDirectory("workspace-storage-")
        val fixture = Fixture(root, scope, dictionaries)
        try { body(fixture) } finally {
            fixture.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    suspend fun request(service: WorkspaceService, method: String, body: Map<String, Any?> = emptyMap()): Map<*, *> {
        val result = service.route(method, jsonOf(body))
        assertEquals(200, result.status, result.body)
        return assertIs<Map<*, *>>(reify(result.body))
    }

    @Test fun explicitBatchesReopenThroughTheWorkspaceWithoutChangingCouchOrRunningPrograms() = runTest {
        fixture(this) { f ->
            var service = f.service()
            var executions = 0
            service.context.lcncRunners["text.value"] = LcncNodeRunner { _, _ -> executions++; emptyMap() }
            val initial = request(service, "GET")["storage"] as Map<*, *>
            assertEquals(0, (initial["indexedRevisions"] as Number).toInt())
            assertFalse(Files.exists(Path.of(f.location + ".store")))
            val checkpoint = f.commits.localPut("replicator", mapOf("last_seq" to 9L))
            val first = f.couch.put("medical/医", mapOf("患者" to mapOf("résumé" to "Māori 🧬"), "groups" to listOf(1, "a", 2)), null)
            val rev1 = first["rev"] as String
            val second = f.couch.put("medical/医", mapOf("患者" to mapOf("résumé" to "Reviewed")), rev1)
            val rev2 = second["rev"] as String
            val deleted = f.couch.delete("medical/医", rev2)
            val rev3 = deleted["rev"] as String
            val head = Files.readAllBytes(f.root.resolve("couch/HEAD"))
            val boardRevision = f.board.snapshot().revision
            val batch = request(service, "PUT", mapOf("action" to "index-couch", "limit" to 1))
            assertEquals(1, (batch["imported"] as Number).toInt())
            assertEquals(2, (batch["remaining"] as Number).toInt())
            assertEquals("unchanged", batch["checkpoints"])
            val source = mapOf("kind" to "couch", "id" to "medical/医", "rev" to rev1)
            val query = mapOf("source" to source, "path" to listOf("患者", "résumé"), "limit" to 2,
                "cursor" to mapOf("path" to listOf(0), "axes" to listOf(listOf(2), listOf(0))))
            val response = request(service, "POST", query)
            val confix = response["confix"] as Map<*, *>
            assertEquals("CBOR", confix["syntax"])
            assertEquals("Māori 🧬", confix["value"])
            assertEquals(rev1, (response["source"] as Map<*, *>)["rev"])
            assertEquals(2, ((confix["cursor"] as Map<*, *>)["indexes"] as List<*>).size)
            assertEquals(400, service.route("POST", jsonOf(query + ("cursor" to mapOf("sourceCid" to "stale")))).status)
            request(service, "PUT", mapOf("action" to "index-couch", "limit" to 32))
            assertEquals(0, (request(service, "PUT", mapOf("action" to "index-couch"))["imported"] as Number).toInt())
            f.reopen(); service = f.service()
            assertEquals(confix, request(service, "POST", query)["confix"])
            val tombstone = request(service, "POST", mapOf("source" to mapOf("kind" to "couch", "id" to "medical/医")))
            assertEquals(true, (tombstone["source"] as Map<*, *>)["deleted"])
            assertEquals(rev3, (tombstone["source"] as Map<*, *>)["rev"])
            assertNull(tombstone["confix"])
            val page = request(service, "POST", mapOf("source" to mapOf("kind" to "couch"), "offset" to 1, "limit" to 1))["storage"] as Map<*, *>
            assertEquals(rev2, ((page["revisions"] as List<*>).single() as Map<*, *>)["rev"])
            assertContentEquals(head, Files.readAllBytes(f.root.resolve("couch/HEAD")))
            assertEquals(checkpoint, f.commits.localGet("replicator"))
            assertEquals(boardRevision, f.board.snapshot().revision)
            assertEquals(0, executions)
        }
    }

    @Test fun batchReceiptsStopAtTheDurableIndexedSequence() = runTest {
        fixture(this) { f ->
            val empty = request(f.service(), "PUT", mapOf("action" to "index-couch"))
            assertNull(empty["through"])
            assertEquals(0, (empty["imported"] as Number).toInt())
            assertFalse(Files.exists(Path.of(f.location + ".store")))
            repeat(3) { f.couch.put("case-$it", mapOf("review" to it), null) }
            suspend fun batch(limit: Int, imported: Int, remaining: Int, sequence: Long) {
                val receipt = request(f.service(), "PUT", mapOf("action" to "index-couch", "limit" to limit))
                assertEquals(imported, (receipt["imported"] as Number).toInt())
                assertEquals(remaining, (receipt["remaining"] as Number).toInt())
                assertEquals(sequence, (receipt["through"] as Number).toLong(),
                    "The receipt must not acknowledge source revisions absent from the dense projection")
                val revisions = f.storage.catalog()["revisions"] as List<*>
                assertEquals(sequence, ((revisions.first() as Map<*, *>)["sequence"] as Number).toLong())
            }
            batch(1, 1, 2, 0L)
            f.reopen()
            f.couch.put("case-3", mapOf("review" to 3), null)
            batch(1, 1, 2, 1L)
            batch(32, 2, 0, 3L)
            f.reopen()
            batch(32, 0, 0, 3L)
        }
    }

    @Test fun invalidRequestsNeverCreateTheDestination() = runTest {
        fixture(this) { f ->
            val service = f.service()
            for (body in listOf(mapOf("action" to "index-couch", "limit" to 0),
                mapOf("action" to "index-couch", "limit" to 129), mapOf("action" to "replace-couch"))) {
                assertEquals(400, service.route("PUT", jsonOf(body)).status)
            }
            val missing = mapOf("source" to mapOf("kind" to "couch", "id" to "absent"))
            assertEquals(404, service.route("POST", jsonOf(missing)).status)
            assertEquals(400, service.route("POST", jsonOf(missing + ("document" to emptyMap<String, Any>()))).status)
            assertFalse(Files.exists(Path.of(f.location + ".store")))
            val reduced = WorkspaceService(vmRuntimeTestContext(this@runTest))
            assertEquals(503, reduced.route("PUT", jsonOf(mapOf("action" to "index-couch"))).status)
            assertEquals(503, reduced.route("POST", jsonOf(missing)).status)
        }
    }

    @Test fun unavailableDictionaryCannotSilentlyFallBackToPortableWrites() = runTest {
        fixture(this) { f ->
            f.couch.put("legal", mapOf("status" to "review"), null)
            val head = Files.readAllBytes(f.root.resolve("couch/HEAD"))
            val cid = f.cas.put("synthetic compression dictionary".encodeToByteArray())
            val result = f.service().route("PUT", jsonOf(mapOf(
                "action" to "index-couch", "dictionaryCid" to cid.value)))
            assertEquals(400, result.status, "A host without a dictionary codec must refuse the selection")
            assertFalse(Files.exists(Path.of(f.location + ".store")))
            assertContentEquals(head, Files.readAllBytes(f.root.resolve("couch/HEAD")))
        }
    }

    @Test fun drainFinishesAdmittedRequestsReleasesTheLeaseAndRefusesNewOnes() = runTest {
        fixture(this) { f ->
            f.couch.put("legal", mapOf("parties" to listOf("claimant", "respondent")), null)
            val imported = async(start = CoroutineStart.UNDISPATCHED) { f.storage.importCouch() }
            val read = async(start = CoroutineStart.UNDISPATCHED) { f.storage.document("legal") }
            f.storage.drain()
            assertEquals(1, imported.await()["imported"])
            assertNotNull(read.await())
            assertTrue(f.storage.job.isCompleted)
            assertFailsWith<IllegalStateException> { f.storage.catalog() }
            f.reopen()
            assertNotNull(f.storage.document("legal"))
        }
    }

    @Test fun changedSourceHistoryCannotSilentlyExtendAnUnrelatedIndex() = runTest {
        fixture(this) { f ->
            f.couch.put("original", mapOf("v" to 1), null)
            f.storage.importCouch()
            f.storage.drain()
            val head = Files.readAllBytes(Path.of(f.location + ".store/HEAD"))
            val replacement = CouchStoreFactory.casBacked(f.cas)
            replacement.put(Document("different", listOf(Field("v", 2))))
            val other = ConfixIsamElement(this, replacement.changes, f.location, f.files, f.cas)
            try {
                assertFailsWith<IllegalArgumentException> { other.importCouch() }
                assertContentEquals(head, Files.readAllBytes(Path.of(f.location + ".store/HEAD")))
                assertNotNull(other.document("original"))
            } finally { other.drain() }
        }
    }

    @Test fun cancelledWorkspaceRequestRemainsCancelledAndItsOwnerStillDrains() = runTest {
        fixture(this) { f ->
            var returnedResponse = false
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                f.service().route("POST", jsonOf(mapOf("source" to mapOf("kind" to "couch"))))
                returnedResponse = true
            }
            waiting.cancelAndJoin()
            assertFalse(returnedResponse, "Cancellation must not become an HTTP error response")
            f.storage.drain()
            assertTrue(f.storage.job.isCompleted)
            assertFalse(Files.exists(Path.of(f.location + ".store")))
        }
    }

    @Test fun unavailableDenseOwnerDoesNotDisableCanvasInspection() = runTest {
        fixture(this) { f ->
            val service = f.service()
            f.storage.drain()
            val catalog = request(service, "GET")
            assertEquals(false, (catalog["storage"] as Map<*, *>)["available"])
            assertTrue((catalog["facets"] as List<*>).isNotEmpty())
            val canvas = request(service, "POST", mapOf("document" to mapOf(
                "nodes" to emptyList<Any>(), "wires" to emptyList<Any>())))
            assertNotNull(canvas["confix"])
        }
    }

    // The optional browser inspection waits at most ten minutes; ordinary runs never wait.
    @org.junit.jupiter.api.Timeout(value = 11, unit = java.util.concurrent.TimeUnit.MINUTES)
    @Test fun workspaceHttpUsesTheOwnedDenseProjection() = runBlocking {
        fixture(CoroutineScope(coroutineContext + Dispatchers.Default), dictionaries = true) { f ->
            val dictionaryCid = f.couch.blockPut("synthetic clinical legal review parties evidence".encodeToByteArray())
            f.couch.put("clinical-review", mapOf("patients" to listOf(
                mapOf("name" to "Synthetic patient", "findings" to listOf("review", "pending")),
                mapOf("name" to "Synthetic second patient", "findings" to listOf("review")))), null)
            val first = f.couch.put("legal-review", mapOf("parties" to listOf("claimant", "respondent"), "status" to "draft"), null)
            val second = f.couch.put("legal-review", mapOf("parties" to listOf("claimant", "respondent"), "status" to "reviewed"), first["rev"] as String)
            f.couch.delete("legal-review", second["rev"] as String)
            val fileTreeUi = java.lang.System.getenv("LCNC_FILE_TREE_UI") == "1"
            if (fileTreeUi) {
                val world = borg.trikeshed.btrfs.BtrfsWorldStore.ofMemory(f.cas)
                val mount = world.mount("review")
                assertTrue(mount.createSubvolume("review"))
                assertTrue(mount.writeFile("review", "case/医.txt", "synthetic file evidence".encodeToByteArray()))
                assertTrue(borg.trikeshed.btrfs.VmWorldTeleport(f.couch, world).publish("review")["ok"] == true)
            }
            val sourceHead = Files.readAllBytes(f.root.resolve("couch/HEAD"))
            val moduleJob = SupervisorJob(coroutineContext[Job])
            val service = f.service(CoroutineScope(f.scope.coroutineContext + moduleJob))
            val pointcuts = Couch("pointcuts", CouchStoreFactory.inMemory(), f.cas)
            repeat(34) { i ->
                pointcuts.put("pointcut/SyntheticReview/check/$i", mapOf(
                    "facet" to if (i == 0) "python" else "java", "property" to "reviewed",
                    "value" to if (i == 0) "<b>synthetic & unapproved</b>" else "false", "mark" to 1,
                    "coordinate" to mapOf("className" to "SyntheticReview", "methodName" to "check",
                        "bytecodeOffset" to if (i == 0) -42 else i)) +
                    (if (i == 0) mapOf("sourcePath" to "synthetic/curation <review>.py", "line" to 12, "column" to 5)
                     else emptyMap()), null)
            }
            val graal = borg.trikeshed.forge.server.GraalWire(borg.trikeshed.graal.vitals.JvmVitals(),
                pointcuts.store, null, service.context.scope, pointcuts)
            val host = HypervisorVmHost()
            service.context.lcncRunners.putAll(PureNodes.registry { 123L })
            service.context.lcncRunners["display"] = TribunalNodes.registry(
                TribunalDialog { _, _, _ -> error("No model call expected") }).getValue("display")
            service.context.lcncRunners[SubVmLegos.POLYGLOT] = SubVmLegos.polyglot(host)
            service.context.lcncRunners[SubVmLegos.CAMEL] = SubVmLegos.camel(host)
            var module: ModuleHandle? = null
            try {
            module = KanbanModule().open(service.context)
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 8)
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            val finished = java.util.concurrent.CountDownLatch(1)
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    val path = exchange.requestURI.path
                    var status = 200
                    var contentType = "application/json; charset=utf-8"
                    val bytes = when (path) {
                        "/api/graal/pointcuts", "/api/graal/doc" -> {
                            val response = requireNotNull(runBlocking {
                                graal.route(exchange.requestMethod, exchange.requestURI.toString(), "", null)
                            })
                            status = response.status; response.body.encodeToByteArray()
                        }
                        "/api/lcnc/workspace", "/api/lcnc/contracts", "/api/lcnc/run", "/api/board",
                        "/api/lcnc/concentric", "/api/lcnc/runs", "/api/lcnc/content",
                        "/api/lcnc/run/debug", "/api/lcnc/run/cancel" -> {
                            val claim = requireNotNull(service.context.routes.match(path))
                            val response = requireNotNull(runBlocking { claim.route(exchange.requestMethod, exchange.requestURI.toString(),
                                exchange.requestBody.readBytes().decodeToString(), null) })
                            status = response.status; response.body.encodeToByteArray()
                        }
                        "/api/panels/presets" -> jsonOf(mapOf("presets" to LcncPresets.all().map { (name, doc) ->
                            val info = LcncPresets.info(name)
                            mapOf("name" to name, "title" to (info?.title ?: name), "does" to info?.does.orEmpty(),
                                "needs" to info?.needs.orEmpty(), "see" to info?.see.orEmpty(),
                                "tweakFirst" to info?.tweakFirst.orEmpty(), "document" to reify(doc))
                        })).encodeToByteArray()
                        "/api/panels/language-check" -> jsonOf(mapOf("nodes" to emptyList<Any>(),
                            "wires" to emptyList<Any>(), "controls" to mapOf("humanOversight" to true))).encodeToByteArray()
                        "/api/panels/execution-check" -> jsonOf(mapOf("nodes" to listOf(
                            mapOf("id" to "source", "type" to "vm.polyglot", "x" to 30, "y" to 60,
                                "params" to mapOf("language" to "js", "source" to "function reviewFlag(value) {\n  const score = value.score;\n  const reviewed = value.reviewed;\n  return {score: score, reviewed: reviewed};\n}\nreturn reviewFlag(input);",
                                    "input" to "{\"score\":1.25,\"reviewed\":false}")),
                            mapOf("id" to "result", "type" to "display", "x" to 400, "y" to 60,
                                "params" to emptyMap<String, String>())),
                            "wires" to listOf(mapOf("from" to listOf("source", "value"), "to" to listOf("result", "x"))),
                            "controls" to mapOf("humanOversight" to true))).encodeToByteArray()
                        "/_test/finish" -> { finished.countDown(); "{}".encodeToByteArray() }
                        "/panels", "/kotlin/spacegraph/spacegraph.js", "/application-nav.css",
                        "/allocation-inspector.css", "/patch-camera.css", "/spacegraph-shadow.css" -> {
                            val resource = if (path == "/panels") "web/panels.html" else "web$path"
                            contentType = when {
                                path.endsWith(".js") -> "application/javascript"
                                path.endsWith(".css") -> "text/css"
                                else -> "text/html"
                            }
                            requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) { resource }.use { it.readBytes() }
                        }
                        else -> "{}".encodeToByteArray()
                    }
                    exchange.responseHeaders.set("Content-Type", contentType)
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (failure: Exception) {
                    val bytes = jsonOf(mapOf("error" to failure.toString())).encodeToByteArray()
                    exchange.sendResponseHeaders(500, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally { exchange.close() }
            }
            server.start()
            try {
                val base = "http://127.0.0.1:${server.address.port}"
                val client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build()
                // The module's queued-run and observation lanes share this runBlocking event loop.
                // Blocking client I/O must yield it so queued runs and projections can settle.
                suspend fun http(method: String, body: String = "", path: String = "/api/lcnc/workspace") = withContext(Dispatchers.IO) {
                    client.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI("$base$path")).timeout(java.time.Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString())
                }
                val catalogResponse = http("GET")
                assertEquals(200, catalogResponse.statusCode())
                val observations = http("GET", path = "/api/graal/pointcuts")
                assertEquals(200, observations.statusCode())
                val rows = ((reify(observations.body()) as Map<*, *>)["routes"] as List<*>).map { it as Map<*, *> }
                assertEquals(34, rows.size)
                assertEquals("<b>synthetic & unapproved</b>", rows.first { it["facet"] == "python" }["value"])
                assertEquals(-42, (rows.first { it["facet"] == "python" }["bci"] as Number).toInt())
                assertEquals("synthetic/curation <review>.py", rows.first { it["facet"] == "python" }["sourcePath"])
                assertEquals(12, (rows.first { it["facet"] == "python" }["line"] as Number).toInt())
                assertEquals(5, (rows.first { it["facet"] == "python" }["column"] as Number).toInt())
                assertTrue(rows.filter { it["facet"] == "java" }.all { it["sourcePath"] == null && it["line"] == null && it["column"] == null })
                val languages = (reify(catalogResponse.body()) as Map<*, *>)["languages"] as List<*>
                val paletteDefaults = LcncContracts.all().first { it.type == "vm.polyglot" }.params.mapValues { it.value.v }
                assertFalse(Files.exists(Path.of(f.location + ".store")))
                for ((language, source) in listOf(
                    "groovy" to "return [patient: input.patient, score: input.score * 2, reviewed: false]",
                    "java" to "var data = (Map<?, ?>) input; return Map.of(\"patient\", data.get(\"patient\"), \"score\", ((Number) data.get(\"score\")).doubleValue() * 2, \"reviewed\", false);",
                    "clojure" to "{\"patient\" (get input \"patient\") \"score\" (* 2 (get input \"score\")) \"reviewed\" false}",
                )) {
                    val capability = languages.map { it as Map<*, *> }.first { it["id"] == language }
                    val drawerParams = assertNotNull(capability["params"] as? Map<*, *>)
                        .entries.associate { it.key.toString() to it.value.toString() }
                    val params = paletteDefaults + drawerParams + mapOf("outputKind" to "OBJECT", "source" to source)
                    val transformed = http("POST", jsonOf(mapOf("type" to "vm.polyglot",
                        "params" to params,
                        "inputs" to mapOf("input" to mapOf("patient" to "Synthetic patient", "score" to 1.25)))), "/api/lcnc/run")
                    assertEquals(200, transformed.statusCode(), transformed.body())
                    val value = ((reify(transformed.body()) as Map<*, *>)["outputs"] as Map<*, *>)["value"]
                    assertEquals(mapOf("patient" to "Synthetic patient", "score" to 2.5, "reviewed" to false), value)
                    val routed = http("POST", jsonOf(mapOf("type" to "vm.camel",
                        "params" to mapOf("from" to "direct:http-review", "to" to "log:http-review"),
                        "inputs" to mapOf("messages?" to value))), "/api/lcnc/run")
                    assertEquals(200, routed.statusCode(), routed.body())
                    assertEquals(value, reify(((reify(routed.body()) as Map<*, *>)["outputs"] as Map<*, *>)["routed"] as String))
                    val refused = http("POST", jsonOf(mapOf("type" to "vm.polyglot",
                        "params" to (params + ("source" to if (language == "clojure") "(Object.)" else "return new Object();")))), "/api/lcnc/run")
                    assertEquals(400, refused.statusCode(), refused.body())
                }
                assertTrue(host.ids().none { host.get(it)?.isAlive == true })
                // ids() includes revoked guests from the three function checks above.
                // Native routes must neither allocate a guest nor revive one of those entries.
                val nativeVmIds = host.ids()
                for (language in listOf("java", "groovy")) {
                    val capability = languages.map { it as Map<*, *> }.single { it["id"] == language }
                    val native = assertIs<Map<*, *>>(capability["nativeConstruction"])
                    assertEquals(true, native["available"], native["note"].toString())
                    val nativeProgram = http("POST", jsonOf(mapOf("name" to "native-$language-http",
                        "document" to native["document"], "inputs" to emptyMap<String, Any?>())), "/api/lcnc/run")
                    assertEquals(200, nativeProgram.statusCode(), nativeProgram.body())
                    val nativeReceipt = assertIs<Map<*, *>>(reify(nativeProgram.body()))
                    assertNotNull(nativeReceipt["receiptCid"])
                    val outputs = assertIs<Map<*, *>>(nativeReceipt["outputs"])
                    val display = assertIs<Map<*, *>>(assertIs<Map<*, *>>(outputs["receipt"])["x"])
                    assertEquals("human-reviewer: request-evidence", display["reply"])
                    assertEquals("native-dsl", display["authoring"])
                    assertEquals(language, display["language"])
                    assertEquals(nativeVmIds, host.ids(), "Native HTTP execution must not allocate a VM")
                    assertTrue(host.ids().none { host.get(it)?.isAlive == true })
                }
                val nativeUi = System.getenv("LCNC_NATIVE_UI") == "1"
                val pointcutUi = System.getenv("LCNC_POINTCUT_UI") == "1"
                if (System.getenv("LCNC_STORAGE_UI") == "1" || nativeUi || pointcutUi || fileTreeUi) {
                    val priorRuns = LcncRunHead.rows(service.context.blackboard.snapshot().values).map { it.runId }.toSet()
                    println("LCNC_STORAGE_DICTIONARY=${dictionaryCid.value}")
                    println("LCNC_STORAGE_UI=$base/panels?load=language-check")
                    assertTrue(withContext(Dispatchers.IO) { finished.await(10, java.util.concurrent.TimeUnit.MINUTES) },
                        "Browser check did not close")
                    if (fileTreeUi) assertEquals(priorRuns,
                        LcncRunHead.rows(service.context.blackboard.snapshot().values).map { it.runId }.toSet(),
                        "File-tree inspection and indexing must not execute a panel graph")
                    if (pointcutUi) {
                        val runs = LcncRunHead.rows(service.context.blackboard.snapshot().values).filter { it.runId !in priorRuns }
                        assertEquals(1, runs.size, "Receipt inspection must not execute another program")
                        val run = runs.single()
                        assertEquals("completed", run.status)
                        val boardBeforeInspection = service.context.blackboard.snapshot()
                        val frozen = reify(f.cas.get(ContentId(requireNotNull(run.programCid)))!!.decodeToString())
                        val inspected = http("POST", jsonOf(mapOf("name" to "execution-check", "document" to frozen)))
                        assertEquals(200, inspected.statusCode(), inspected.body())
                        val discovered = (assertIs<Map<*, *>>(reify(inspected.body()))["runs"] as List<*>)
                            .map { it as Map<*, *> }.single { it["runId"] == run.runId }
                        assertEquals(run.receiptCid, discovered["receiptCid"])
                        assertNull(discovered["programKey"], "Inline evidence must not acquire named-program membership")
                        assertEquals(boardBeforeInspection, service.context.blackboard.snapshot())
                        val evidence = reify(f.cas.get(ContentId(run.raw["invocationsCid"] as String))!!.decodeToString()) as Map<*, *>
                        val invocation = (evidence["entries"] as List<*>).map { it as Map<*, *> }.single { it["nodeId"] == "source" }
                        val runtime = invocation["runtime"] as Map<*, *>
                        val roots = ((runtime["pointcuts"] as Map<*, *>)["roots"] as List<*>).map { it as Map<*, *> }
                        assertTrue(roots.any { it["root"] == "reviewFlag" })
                        assertEquals(nativeVmIds.size + 1, host.ids().size)
                        assertTrue(host.ids().none { host.get(it)?.isAlive == true })
                    }
                    if (nativeUi) {
                        val runs = LcncRunHead.rows(service.context.blackboard.snapshot().values).filter { it.runId !in priorRuns }
                        assertEquals(1, runs.size, "Loading and editing must not execute; exactly one explicit Run was requested")
                        val run = runs.single()
                        assertEquals("native-groovy-camel", run.program)
                        assertNull(run.programKey, "Running unsaved source must not claim membership in a saved program")
                        assertEquals("completed", run.status)
                        assertNotNull(run.receiptCid)
                        val output = assertIs<Map<*, *>>(assertIs<Map<*, *>>(run.raw["outputs"])["route"])
                        assertEquals("reviewer-医: review-evidence", output["routed"])
                        assertEquals("groovy", assertIs<Map<*, *>>(output["receipt"])["language"])
                        assertEquals(nativeVmIds, host.ids(), "Native UI execution must not allocate a VM")
                        assertTrue(host.ids().none { host.get(it)?.isAlive == true }, "Native UI must not leave a live VM")
                    }
                }
                val indexed = http("PUT", jsonOf(mapOf("action" to "index-couch")))
                assertEquals(200, indexed.statusCode(), indexed.body())
                val result = http("POST", jsonOf(mapOf("source" to mapOf("kind" to "couch", "id" to "clinical-review"))))
                assertEquals(200, result.statusCode(), result.body())
                val confix = (reify(result.body()) as Map<*, *>)["confix"] as Map<*, *>
                assertEquals("CBOR", confix["syntax"])
                assertContentEquals(sourceHead, Files.readAllBytes(f.root.resolve("couch/HEAD")))
            } finally {
                server.stop(0); executor.shutdownNow()
                assertTrue(withContext(NonCancellable + Dispatchers.IO) { executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS) })
            }
            } finally {
                try { module?.drain() } finally {
                    try { module?.close() } finally {
                        service.context.routes.release("kanban")
                        try { host.close() } finally { moduleJob.cancelAndJoin() }
                    }
                }
            }
        }
    }
}
