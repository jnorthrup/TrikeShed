package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.ContentId
import borg.trikeshed.kernel.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.*
import borg.trikeshed.pointcut.PointcutBlackboardAdapter
import borg.trikeshed.userspace.nio.file.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.agent.modelmux.acp.*
import kotlin.coroutines.CoroutineContext

/** One admitted reel per wire scope. The same board serves Graal and faceted Cursor sheets. */
class BreadcrumbReelService(
    val scope: CoroutineScope,
    val context: CoroutineContext,
    val board: ConfixBlackboard,
    val repository: String,
    val catalog: suspend () -> ModelMux,
    val files: FileOperations = JvmFileOperations(),
    val pointcuts: PointcutBlackboardAdapter = PointcutBlackboardAdapter(board),
    val archive: BreadcrumbReelStore? = context[BreadcrumbReelStore],
) {
    val mutex = Mutex()
    var reel: BreadcrumbReel? = null
    var job: Job? = null
    val breadcrumbs = context[BreadcrumbElement] ?: BreadcrumbElement()
    val restoration = scope.async(context.minusKey(Job) + files + fileIoContext) { archive?.restore(board) }

    /**
     * The evidence context for one run's MICRO lane. When the root supplied a [SharedContextElement] that serves this run's
     * scope over the archive's own store, every run reads through that one owner; otherwise the run gets a private context
     * over the archive, exactly as before. The service borrows the element and never drains it.
     */
    internal fun contextFor(runId: String): SharedContext? {
        val store = archive ?: return null
        val scope = "reel/$runId"
        val shared = context[SharedContextElement]?.context
        return if (shared != null && shared.policy.serves(scope) && shared.usesStore(store.cas)) shared
        else SharedContext(store.cas, null, ContextReadPolicy(allowedScopes = setOf(scope)))
    }

    suspend fun route(method: String, path: String, text: String): JvmKanbanServer.HttpResponse? {
        val route = path.substringBefore('?')
        if (route !in setOf("/api/mux/reel", "/api/mux/reel/run", "/api/mux/reel/vote",
                "/api/mux/reel/review", "/api/mux/reel/retain", "/api/mux/reel/payload")) return null
        return try {
            restoration.await()
            val query = path.substringAfter('?', "").split('&').filter { '=' in it }.associate {
                it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            if (method == "GET" && route == "/api/mux/reel/payload") {
                val storage = archive ?: return response(mapOf("error" to "Reel archive unavailable"), 503)
                return response(mapOf("content" to storage.payload(query["runId"] ?: "", ContentId(query["cid"] ?: ""))))
            }
            if (method == "GET" && route == "/api/mux/reel") {
                val mux = withContext(context.minusKey(Job)) { catalog() }
                val history = archive?.history() ?: emptySeriesOf()
                val active = mutex.withLock { reel }
                val id = query["runId"] ?: active?.id ?: history.view.firstOrNull()?.get("id") as? String
                val run = id?.let { archive?.read(it) } ?: active?.takeIf { it.id == id }?.snapshot()
                if (run != null) board.put("ace/run/${run["id"]}", run, "ace-archive")
                return response(mapOf("run" to run, "history" to history.view.toList(),
                    "selections" to archive?.selections()?.view?.toList().orEmpty(),
                    "activeRunId" to active?.takeIf { job?.isCompleted == false }?.id, "defaults" to mapOf(
                    "mainModel" to rankedModel(mux), "selectionBasis" to ModelGradeBasis.CORRECTNESS,
                    "selectionQualification" to "Explicit human accuracy grades for this task and matching known model revision; unknown revisions remain ungraded.",
                    "heuristic" to mapOf("policy" to BreadcrumbProjector.POLICY, "generator" to BreadcrumbReel.HEURISTIC_LABEL,
                        "budgetChars" to BreadcrumbReel.MICRO_PROJECTION_CHARS),
                    "sources" to SOURCES,
                )))
            }
            if (method != "POST" || route == "/api/mux/reel") return response(mapOf("error" to "Method not allowed"), 405)
            val body = if (text.startsWith("$method ")) text.substringAfter("\r\n\r\n", text.substringAfter("\n\n", "")) else text
            val request = reifyStrict(body) as? Map<*, *> ?: throw IllegalArgumentException("JSON object required")
            if (route == "/api/mux/reel/retain") {
                val storage = archive ?: return response(mapOf("error" to "Reel archive unavailable"), 503)
                val from = (request["fromMs"] as? Number)?.toLong() ?: throw IllegalArgumentException("fromMs required")
                val until = (request["untilMs"] as? Number)?.toLong() ?: throw IllegalArgumentException("untilMs required")
                val fraction = (request["fraction"] as? Number)?.toDouble() ?: throw IllegalArgumentException("fraction required")
                return response(mapOf("pins" to storage.retain(from, until, fraction).view.toList()))
            }
            if (route == "/api/mux/reel/review") {
                val storage = archive ?: return response(mapOf("error" to "Reel archive unavailable"), 503)
                val review = storage.review(request["runId"] as? String ?: "", request["lane"] as? String ?: "",
                    ModelCorrectness.valueOf(request["correctness"] as? String ?: "UNKNOWN"),
                    ModelDisposition.valueOf(request["disposition"] as? String ?: "UNSELECTED"),
                    request["note"] as? String ?: "", request["concept"] as? String ?: "",
                    request["correction"] as? String ?: "")
                board.put("ace/review/${review["attemptId"]}", review, "ace-human")
                return response(mapOf("review" to review))
            }
            if (route == "/api/mux/reel/vote") {
                val id = request["runId"] as? String ?: ""
                if (archive != null) archive.vote(id, request["winner"] as? String ?: "", request["note"] as? String ?: "")
                else {
                    val active = mutex.withLock { reel } ?: return response(mapOf("error" to "No comparison"), 404)
                    require(id == active.id) { "Comparison changed; refresh before voting" }
                    active.vote(request["winner"] as? String ?: "", request["note"] as? String ?: "")
                }
                return response(mapOf("ok" to true))
            }
            if (route != "/api/mux/reel/run") return response(mapOf("error" to "Method not allowed"), 405)
            mutex.withLock {
                if (job?.isCompleted == false) return@withLock response(mapOf("error" to "A comparison is already running"), 409)
                val base = withContext(context.minusKey(Job)) { catalog() }
                val main = request["mainModel"] as? String ?: rankedModel(base)
                    ?: throw IllegalArgumentException("Choose a main model from the catalog")
                require(base.listModels("chat").view.any { it.a == main }) { "Main model must be in the catalog" }
                val sources = (request["sources"] as? List<*>)?.map {
                    it as? String ?: throw IllegalArgumentException("Source paths must be strings")
                } ?: SOURCES
                require(sources.size in 1..16)
                require(sources.distinct().size == sources.size) { "Choose each source path once" }
                val evidence = withContext(context.minusKey(Job) + files + fileIoContext) {
                    val sourceRoot = files.canonicalPath(files.resolvePath(repository, "src")).trimEnd('/') + "/"
                    var bytes = 0L
                    sources.map { name ->
                        require(name.startsWith("src/") && name.endsWith(".kt") &&
                            name.split('/').none { it == ".." || it.startsWith('.') } &&
                            name.length < 240) { "Choose Kotlin source paths inside src/" }
                        val source = files.canonicalPath(files.resolvePath(repository, name))
                        require(source.startsWith(sourceRoot) && files.isFile(source)) { "Source must remain inside repository src/" }
                        val fd = files.open(source, readOnly = true)
                        val size = try { files.size(fd) } finally { files.close(fd) }
                        require(size in 1..48_000 && bytes + size <= 48_000) { "Sources exceed 48,000 bytes" }
                        val content = files.readAllBytes(source).decodeToString(throwOnInvalidSequence = true)
                        val actualBytes = content.encodeToByteArray().size
                        require(actualBytes in 1..48_000 && bytes + actualBytes <= 48_000) { "Sources exceed 48,000 bytes" }
                        bytes += actualBytes
                        name j content
                    }.toTypedArray().toSeries()
                }
                val selectedIds = (request["selectionIds"] as? List<*>)?.map {
                    it as? String ?: throw IllegalArgumentException("Selection identities must be strings")
                }.orEmpty()
                require(selectedIds.size <= 1) { "Choose at most one prior summary for this bounded comparison" }
                val selected = (archive?.selections() ?: emptySeriesOf<BreadcrumbReelState>())
                    .filter { it["attemptId"] in selectedIds }
                require(selected.size == selectedIds.size) { "Selected summary is no longer eligible" }
                val runId = defaultSecureIdGenerator.generateHexId("ace", 8)
                // MICRO reads its evidence through shared context over the CAS the archive already captures sources into.
                val sharedContext = contextFor(runId)
                val run = BreadcrumbReel(runId, base, breadcrumbs, board, main, evidence, archive, selected, sharedContext)
                archive?.save(run.snapshot())
                reel = run
                // The wire scope owns the run; the kernel owns and drains every admitted model task.
                job = scope.launch(context.minusKey(Job) + files + breadcrumbs) {
                    val kernel = KernelElement(currentCoroutineContext(), KernelId(run.id))
                    try {
                        // Only the model lanes get a seat: MICRO is heuristic and admits no model work.
                        val seats = BreadcrumbLane.entries.filter { it != BreadcrumbLane.MICRO }.associateWith { lane ->
                            val pointcut = GraalKernelPointcut("${run.id}-${lane.id}", pointcuts)
                            try {
                                kernel.seat(KernelId(lane.id), KernelCapability.MODEL,
                                    parallelism = 2, capacity = 2, pointcut = pointcut)
                            } catch (failure: Throwable) {
                                pointcut.close()
                                throw failure
                            }
                        }
                        run.run(seats)
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) {
                            run.mutex.withLock { run.status = "failed"; run.endedAt = System.currentTimeMillis() }
                            run.event("failure", "Comparison stopped", detail = failure::class.simpleName)
                        }
                        if (failure is CancellationException) throw failure
                    } finally {
                        kernel.drain()
                    }
                }
                response(mapOf("runId" to run.id), 202)
            }
        } catch (invalid: IllegalArgumentException) {
            response(mapOf("error" to (invalid.message ?: "Invalid comparison")), 400)
        }
    }

    suspend fun rankedModel(mux: ModelMux): String? {
        val admitted = mutableListOf<AcpModelCard>()
        for (candidate in mux.listModels("chat").view) {
            if (mux.modelAdmission(candidate.id)?.b == true) admitted += candidate
        }
        val candidates = admitted.sortedBy { if (it.id == mux.defaultModel) 0 else 1 }.toSeries()
        val grades = archive?.grades() ?: return candidates.view.firstOrNull()?.id
        val known = grades.filter { it.model.b != null }
        return rankModelProficiency(candidates, known, BreadcrumbReelStore.HARNESS j BreadcrumbReelStore.TASK,
            ModelGradeBasis.CORRECTNESS) { it.id j it.modelVersion }.view.firstOrNull()?.id
    }

    fun response(value: Any?, status: Int = 200) = JvmKanbanServer.HttpResponse(status, jsonOf(value))

    companion object {
        val SOURCES = listOf(
            "src/commonMain/kotlin/borg/trikeshed/kernel/KernelTaxonomy.kt",
            "src/commonMain/kotlin/borg/trikeshed/agent/keymux/OperatorKeyMux.kt",
            "src/commonMain/kotlin/borg/trikeshed/agent/modelmux/BreadcrumbHorizon.kt",
        )
    }
}
