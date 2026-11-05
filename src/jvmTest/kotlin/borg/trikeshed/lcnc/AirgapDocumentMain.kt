package borg.trikeshed.lcnc

import borg.trikeshed.agent.modelmux.Airgap
import borg.trikeshed.collections.associative.*
import borg.trikeshed.couch.*
import borg.trikeshed.couch.persistence.CouchCommitStore
import borg.trikeshed.dag.*
import borg.trikeshed.forge.server.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.kanban.module.KanbanModule
import borg.trikeshed.kernel.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.*
import borg.trikeshed.memory.*
import borg.trikeshed.module.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.io.File
import java.lang.System

/** Packaged demonstrator composition. The miner, store, execution and HTTP owners are production code. */
fun main(args: Array<String>) {
    require(args.size == 4) { "Usage: AirgapDocumentMainKt STATE INSTALL PORT run|inspect" }
    val root = File(args[0]).absoluteFile
    val install = File(args[1]).absoluteFile
    val port = args[2].toInt().also { require(it in 1024..65535) }
    val mode = args[3].also { require(it == "run" || it == "inspect") }
    val runtime = File(root, "runtime")
    require(if (mode == "run") !runtime.exists() else runtime.isDirectory) {
        "run requires fresh state; inspect requires an existing runtime"
    }
    System.setProperty(Airgap.PROPERTY, "true")
    runBlocking(Airgap()) {
        check(runtime.mkdirs() || runtime.isDirectory)
        val files = JvmFileOperations()
        val cas = FileCasStore(files, File(runtime, "cas").absolutePath)
        val commits = CouchCommitStore(cas, StorageDurability.DURABLE, files, File(runtime, "couch").absolutePath)
        val store = CouchStoreFactory.durableCasBacked(cas, commits)
        val routes = ModuleRouteRegistry()
        val owner = SupervisorJob(coroutineContext[Job])
        val gateway = CouchAttachmentGateway(store, cas)
        val ctx = ModuleContext(Couch("airgap-visible", store, cas), ReteNetwork(), ReteProductionRegistry(),
            null, null, ConfixBlackboard.empty(), cas, gateway, routes,
            CoroutineScope(coroutineContext + Dispatchers.IO + owner + files), { System.currentTimeMillis() }, runtime)
        val registry = ProjectDbRegistry("primary")
        val scopes = ProjectScopes(files, gateway, CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, store))),
            cas, null, projectDbs = registry, ledgerFile = File(runtime, "projects.tsv"), filesRoot = File(runtime, "files"))
        val cleanups = mutableListOf<suspend () -> Unit>()
        var handle: ModuleHandle? = null
        var listener: Job? = null
        var kernel: KernelElement? = null
        try {
            // Inspector has no miner: reopening cannot accidentally extract or repair the retained PDF.
            val miner = if (mode == "run") ProjectMiner(registry, scopes, cas, null, filesRoot = File(runtime, "files")) else null
            val corpus = JvmProjectCorpus(registry, scopes, miner)
            val project = "coinbase-suski"
            val id = "coinbase-suski.pdf"
            val admission = File(runtime, "admission.json")
            if (mode == "run") {
                check(scopes.beginUpload(project).name == project)
                val original = File(install, "assets/$id").readBytes()
                check(ContentId.of(original).hex == "591f0fdecf41cb297427288a18fd72230e14d4aefe9af4f2d86d6c64dcc4476f")
                scopes.uploadPut(project, id, original)
                scopes.uploadPut(project, id + ProjectNodes.NOTES_SUFFIX,
                    "url: https://www.supremecourt.gov/opinions/23pdf/23-3_879d.pdf\nretrieved: 2026-11-04T23:32:18Z\n".encodeToByteArray())
                val ingestion = checkNotNull(miner).ingest(project, id)
                check(ingestion["verdict"] == "ingested") { ingestion }
                val doc = checkNotNull(corpus.document(project, id))
                val extracted = checkNotNull(corpus.extract(project, id, 262_144))
                check(ContentId.of(extracted.text.encodeToByteArray()).hex == "593fd4088374764d6a3bd3d12dfe9b54b016d8ded783fa466579f41aeb7bf801")
                val anchor = checkNotNull(extracted.anchors.firstOrNull {
                    it.page == 10 && extracted.text.substring(it.begin, it.end).contains("court")
                })
                val documents = s_[id, id + ProjectNodes.EXTRACT_SUFFIX, id + ProjectNodes.EXTRACTION_SUFFIX,
                    id + ProjectNodes.EXTRACT_ORIGIN_SUFFIX, id + ProjectNodes.NOTES_SUFFIX, id + ProjectNodes.FOOTNOTES_SUFFIX].view
                    .map { checkNotNull(corpus.document(project, it)).toMap() }
                val source = DocumentCurationLegos.source(cas, corpus).execute(
                    LcncNode("admission", "document.source", _m["view" j "reference"]), _m["doc" j doc.toMap()])["source"]
                files.writeAtomically(admission.path, jsonOf(_m["document" j doc.toMap(), "documents" j documents,
                    "sourceReference" j source, "passageOffset" j anchor.begin, "ingestion" j ingestion]).encodeToByteArray())
                ctx.lcncRunners.putAll(PureNodes.registry { System.currentTimeMillis() })
                ctx.lcncRunners[DocumentCurationLegos.SOURCE] = DocumentCurationLegos.source(cas, corpus)
                ctx.lcncRunners[DocumentSpans.TYPE] = DocumentSpans.runner(cas, corpus)
                fun profile(name: String, hash: String, abi: String, more: Map<String, Any?>) {
                    val module = File(install, "modules/$name")
                    check(ContentId.of(module.readBytes()).hex == hash) { "Pinned module differs: $name" }
                    File(runtime, "$name.json").writeText(jsonOf(_m["module" j module.path, "sha256" j hash, "abi" j abi] + more))
                }
                profile("add-mlir.bc", "75c58f04786ec777841e29e185e1c6e59f1efa50afd5f860280b77887674e10b", LlvmScalarProfile.ABI, _m["export" j "add"])
                profile("confix.bc", "25225b0b480635499f091e1c09862aa945720eb204b5b06298a7eb652338d0f3", LlvmConfixProfile.ABI,
                    _m["maxInputBytes" j 65536, "maxRows" j 16384])
                kernel = checkNotNull(OroborosLcncWiring.vmLegos(ctx) { cleanups.add(it) })
                check(LlvmScalarContract.TYPE in ctx.lcncRunners && LlvmConfixContract.TYPE in ctx.lcncRunners)
            } else {
                check(scopes.remountLedger() == 1) { "Expected one durably mounted project" }
            }
            val seed = reifyMap(admission.readText())
            for (entry in seed["documents"] as List<*>) {
                val expected = checkNotNull(ProjectDoc.fromMap(entry as Map<*, *>))
                val actual = checkNotNull(corpus.document(project, expected.id))
                check(actual == expected) { "Project revision changed at startup: ${expected.id}" }
                val bytes = checkNotNull(corpus.bytes(project, expected.id))
                check(ContentId.of(bytes).value == expected.cid) { "Retained source differs: ${expected.id}" }
            }
            handle = KanbanModule().open(ctx)
            val finish = CompletableDeferred<Unit>()
            val boot = java.util.UUID.randomUUID().toString()
            fun report(): Map<String, Any?> {
                for (name in s_["status", "maps", "mountinfo"].view)
                    File(root, "$mode-$name.txt").writeText(File("/proc/self/$name").readText())
                return seed + _m[
                "mode" j mode, "boot" j boot, "pid" j ProcessHandle.current().pid(),
                "reopened" j (mode == "inspect"), "llvmInstalled" j (LlvmConfixContract.TYPE in ctx.lcncRunners),
                "runs" j ctx.blackboard.snapshot().values.filterKeys { it.startsWith("lcnc/run/") },
                "javaHome" j System.getProperty("java.home"), "classpath" j System.getProperty("java.class.path"),
                "workspaceVisible" j File("/workspace").exists(), "gradleCacheVisible" j File("/home/agent/.gradle").exists(),
                "modulesVisible" j File(install, "modules/confix.bc").exists(),
                "assetsVisible" j File(install, "assets/$id").exists(),
                "processors" j Runtime.getRuntime().availableProcessors(), "maxHeap" j Runtime.getRuntime().maxMemory(),
                "processCpuNanos" j ProcessHandle.current().info().totalCpuDuration().orElse(java.time.Duration.ZERO).toNanos(),
                "uptimeMs" j java.lang.management.ManagementFactory.getRuntimeMXBean().uptime]
            }
            val control: ExtraRoute = { method, path, body, _ ->
                when {
                    path == "/__proof/state" && method == "GET" -> JvmKanbanServer.HttpResponse(200, jsonOf(report()))
                    path == "/__proof/finish" && method == "POST" -> {
                        File(root, "state-$mode.json").writeText(jsonOf(report()))
                        finish.complete(Unit)
                        JvmKanbanServer.HttpResponse(200, "{\"finished\":true}")
                    }
                    path.startsWith("/api/v0/block/get?") && method == "GET" -> {
                        val reply = checkNotNull(CouchWireRouter(ctx.couch, "").handle(method, path, body.encodeToByteArray()))
                        JvmKanbanServer.HttpResponse(reply.status, "", reply.contentType, reply.bytes)
                    }
                    else -> null
                }
            }
            val server = JvmKanbanServer(extraRoutes = _l[control], stateDir = File(runtime, "host").apply { mkdirs() }, moduleRoutes = routes)
            listener = launch(Dispatchers.IO) { server.run(port, null) }
            finish.await()
        } finally {
            withContext(NonCancellable) {
                try {
                    listener?.cancelAndJoin()
                    routes.release("kanban")
                    try { handle?.drain(); handle?.close() }
                    finally {
                        while (cleanups.isNotEmpty()) cleanups.removeAt(cleanups.lastIndex).invoke()
                        kernel = null
                        owner.cancelAndJoin()
                    }
                } finally {
                    try { scopes.drain() } finally { commits.close() }
                }
                File(root, "cleanup-$mode.json").writeText(jsonOf(_m["moduleOwnerCompleted" j owner.isCompleted,
                    "nativeRootDrained" j (kernel == null), "listenerJoined" j (listener?.isCompleted != false)]))
            }
        }
    }
}
