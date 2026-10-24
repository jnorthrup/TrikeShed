package borg.trikeshed.agent.modelmux

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil

typealias BreadcrumbReelState = Map<String, Any?>
typealias BreadcrumbReelTransaction<T> = Join<suspend () -> T, CompletableDeferred<T>>

/**
 * Atomic run manifests name complete CAS payloads before any lossy projection.
 * Retention pins and curation are independent: declining a summary never deletes evidence.
 */
class BreadcrumbReelStore(
    val store: borg.trikeshed.agent.modelmux.BreadcrumbStore,
    val cas: CasStore,
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BreadcrumbReelStore> {
        const val HARNESS = "breadcrumb-reel-v3"
        const val TASK = "code-summary"
        const val SCOPE = "Complete reel-captured source bytes, submitted requests, returned responses, summaries, assessments and human corrections only. Excludes mux-internal request rewrites, unreturned HTTP-error bodies, uncaptured conversations/files and live view buffers."
    }

    val mutex = Mutex()
    val states = linkedMapOf<String, borg.trikeshed.agent.modelmux.BreadcrumbReelState>()
    var loaded = false

    suspend fun hydrate() {
        if (loaded) return
        val restored = linkedMapOf<String, borg.trikeshed.agent.modelmux.BreadcrumbReelState>()
        for (entry in store.load().view) {
            val state = objectState(reifyStrict(entry.b))
            require((state["version"] as? Number)?.toInt() == 1 && state["id"] == entry.a) {
                "Invalid reel archive"
            }
            restored[entry.a] = state
        }
        // No model work is replayed after a crash; the last durable observation remains inspectable.
        for ((id, state) in restored) {
            val run = objectState(state["run"])
            if (run["status"] in setOf("queued", "running", "draining")) {
                val interrupted = state + ("run" to (run + mapOf(
                    "status" to "interrupted", "endedAt" to clock(),
                    "interruption" to "Process ended before durable completion; admitted provider work may have completed externally.",
                )))
                store.write(id, jsonOf(interrupted))
                restored[id] = interrupted
            }
        }
        states.putAll(restored)
        loaded = true
    }

    suspend fun <T> transact(operation: suspend () -> T): T = supervisorScope {
        currentCoroutineContext().ensureActive()
        val requests = Channel.buffered<borg.trikeshed.agent.modelmux.BreadcrumbReelTransaction<T>>(1)
        val result = CompletableDeferred<T>()
        val worker = launch(this@BreadcrumbReelStore, start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                for (request in requests.kChannel) {
                    try {
                        request.b.complete(mutex.withLock { hydrate(); request.a() })
                    } catch (failure: Throwable) { request.b.completeExceptionally(failure) }
                }
            }
        }
        try { requests.kChannel.send(operation j result) } finally { requests.close() }
        try { result.await() } finally { withContext(NonCancellable) { worker.join() } }
    }

    suspend fun publish(id: String, state: borg.trikeshed.agent.modelmux.BreadcrumbReelState) {
        store.write(id, jsonOf(state))
        states[id] = state
    }

    suspend fun save(run: borg.trikeshed.agent.modelmux.BreadcrumbReelState) = transact {
        val id = run["id"] as? String ?: error("Run identity required")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val state = states[id] ?: mapOf("version" to 1, "id" to id,
            "captures" to emptyList<Any>(), "reviews" to emptyList<Any>(), "votes" to emptyList<Any>(),
            "retention" to emptyList<Any>())
        publish(id, state + ("run" to run))
    }

    /** Publication is ordered payload → CAS sync → manifest. A failure prevents downstream use. */
    suspend fun capture(id: String, kind: String, name: String, bytes: ByteArray, lane: String? = null): ContentId = transact {
        val state = requireNotNull(states[id]) { "Unknown reel" }
        require(kind in setOf("source", "request", "response", "summary", "assessment"))
        val cid = cas.put(bytes)
        cas.sync()
        val captures = rows(state["captures"])
        val record = mapOf("sequence" to captures.size + 1, "atMs" to clock(), "kind" to kind,
            "name" to name, "lane" to lane, "cid" to cid.value, "bytes" to bytes.size)
        publish(id, state + ("captures" to (captures + record)))
        cid
    }

    suspend fun history(): Series<borg.trikeshed.agent.modelmux.BreadcrumbReelState> = transact {
        states.values.sortedByDescending { (objectState(it["run"])["startedAt"] as? Number)?.toLong() ?: 0L }
            .map { state ->
                val run = objectState(state["run"])
                mapOf("id" to state["id"], "startedAt" to run["startedAt"], "status" to run["status"],
                    "models" to run["models"], "reviews" to rows(state["reviews"]).size,
                    "captures" to rows(state["captures"]).size)
            }.toSeries()
    }

    suspend fun read(id: String): borg.trikeshed.agent.modelmux.BreadcrumbReelState? = transact {
        states[id]?.let { state ->
            val captures = rows(state["captures"])
            val missing = captures.filterNot(::available)
            objectState(state["run"]) + mapOf(
                "votes" to rows(state["votes"]), "reviews" to rows(state["reviews"]),
                "retention" to rows(state["retention"]),
                "capture" to mapOf(
                    "scope" to SCOPE, "records" to captures, "watermark" to captures.size,
                    "capturedBytes" to captures.sumOf { (it["bytes"] as Number).toLong() },
                    "missingPayloads" to missing.size, "complete" to missing.isEmpty(),
                    "retainedFraction" to captures.sumOf { (it["bytes"] as Number).toLong() }.let { total ->
                        if (total == 0L) null else (total - missing.sumOf { (it["bytes"] as Number).toLong() }).toDouble() / total
                    },
                    "fromMs" to captures.firstOrNull()?.get("atMs"),
                    "throughMs" to captures.lastOrNull()?.get("atMs"),
                    "durable" to (cas.durability == borg.trikeshed.userspace.nio.file.spi.StorageDurability.DURABLE),
                ),
            )
        }
    }

    suspend fun payload(id: String, cid: ContentId): String = transact {
        val state = requireNotNull(states[id]) { "Unknown reel" }
        val record = rows(state["captures"]).firstOrNull { it["cid"] == cid.value }
            ?: throw IllegalArgumentException("Payload is not in this reel manifest")
        require(available(record)) { "Captured payload is missing or corrupt" }
        requireNotNull(cas.get(cid)).decodeToString(throwOnInvalidSequence = true)
    }

    suspend fun vote(id: String, winner: String, note: String) = transact {
        val state = requireNotNull(states[id]) { "Unknown reel" }
        val run = objectState(state["run"])
        require(run["status"] in setOf("completed", "partial", "interrupted", "failed")) {
            "Wait for the comparison to finish"
        }
        require(winner in borg.trikeshed.agent.modelmux.BreadcrumbLane.entries.map { it.id } + "tie")
        val completed = borg.trikeshed.agent.modelmux.BreadcrumbLane.entries.filter { lane ->
            rows(run["lanes"]).any { it["id"] == lane.id && it["status"] == "completed" &&
                (it["output"] as? String)?.isNotBlank() == true && (it["resultId"] as? String)?.isNotBlank() == true }
        }
        require(if (winner == "tie") completed.size >= 2 else completed.any { it.id == winner }) {
            "A completed output is required; a tie requires at least two completed outputs"
        }
        require(note.length <= 2000)
        publish(id, state + ("votes" to (rows(state["votes"]) +
            mapOf("winner" to winner, "note" to note, "atMs" to clock(), "actor" to "human"))))
    }

    suspend fun review(id: String, lane: String, correctness: borg.trikeshed.agent.modelmux.ModelCorrectness, disposition: borg.trikeshed.agent.modelmux.ModelDisposition,
                       note: String, concept: String, correction: String = ""): borg.trikeshed.agent.modelmux.BreadcrumbReelState = transact {
        val state = requireNotNull(states[id]) { "Unknown reel" }
        val run = objectState(state["run"])
        val output = rows(run["lanes"]).firstOrNull { it["id"] == lane && it["resultId"] is String }
            ?: throw IllegalArgumentException("A completed summary is required")
        require(note.length <= 2000 && concept.length <= 500 && correction.length <= 4000)
        val models = objectState(run["models"])
        val versions = run["modelVersions"]?.let(::objectState).orEmpty()
        val micro = lane == borg.trikeshed.agent.modelmux.BreadcrumbLane.MICRO.id
        // A heuristic lane has no model: its review is a label on a policy's output, never a model generation grade.
        // Archives written before the heuristic lane still name the summarizer model and keep that attribution.
        val heuristic = micro && models["micro"] is String
        val reviews = rows(state["reviews"])
        val at = clock()
        val correctionCid = correction.takeIf { it.isNotBlank() }?.let {
            cas.put(it.encodeToByteArray()).also { cas.sync() }
        }
        val captures = rows(state["captures"])
        val updatedCaptures = if (correctionCid == null) captures else captures + mapOf(
            "sequence" to captures.size + 1, "atMs" to at, "kind" to "correction", "lane" to lane,
            "name" to "$id-$lane:${reviews.size + 1}", "cid" to correctionCid.value,
            "bytes" to correction.encodeToByteArray().size,
        )
        val review = mapOf(
            "runId" to id, "lane" to lane, "contextId" to "$id-$lane",
            "breadcrumbId" to output["resultId"], "attemptId" to "$id-$lane",
            "model" to if (heuristic) null else models[if (micro) "summarizer" else "main"],
            "modelVersion" to if (heuristic) null else versions[if (micro) "summarizer" else "main"],
            "generator" to if (heuristic) models["micro"] else "model",
            "harness" to (run["harness"] as? String ?: "breadcrumb-reel-v1"), "taskKind" to TASK,
            "basis" to borg.trikeshed.agent.modelmux.ModelGradeBasis.CORRECTNESS, "score" to null,
            "correctness" to correctness.name, "disposition" to disposition.name,
            "actor" to "human", "note" to note, "concept" to concept,
            "sequence" to reviews.size + 1, "atMs" to at, "correctionCid" to correctionCid?.value,
            "sourceCids" to rows(state["captures"]).filter { it["kind"] == "source" }.map { it["cid"] },
        )
        publish(id, state + mapOf("reviews" to (reviews + review), "captures" to updatedCaptures))
        review
    }

    /**
     * Pin a deterministic, byte-counted selection in [fromMs, untilMs). This never unlinks
     * other captured payloads. The watermark fixes the population; future captures need a new pin.
     */
    suspend fun retain(fromMs: Long, untilMs: Long, fraction: Double): Series<borg.trikeshed.agent.modelmux.BreadcrumbReelState> = transact {
        require(fromMs >= 0 && untilMs > fromMs && fraction.isFinite() && fraction in 0.0..1.0)
        val results = mutableListOf<borg.trikeshed.agent.modelmux.BreadcrumbReelState>()
        for ((id, state) in states.toMap()) {
            val captures = rows(state["captures"]).filter {
                (it["atMs"] as Number).toLong() in fromMs until untilMs
            }
            if (captures.isEmpty()) continue
            val total = captures.sumOf { (it["bytes"] as Number).toLong() }
            val required = ceil(total * fraction).toLong()
            var selectedBytes = 0L
            val selected = captures.sortedBy { it["cid"] as String }.takeWhile {
                val include = selectedBytes < required
                if (include) selectedBytes += (it["bytes"] as Number).toLong()
                include
            }
            require(selected.all(::available)) { "Cannot pin missing or corrupt captured payloads" }
            val policy = mapOf("fromMs" to fromMs, "untilMs" to untilMs, "fraction" to fraction,
                "atMs" to clock(), "watermark" to rows(state["captures"]).size,
                "populationBytes" to total, "pinnedBytes" to selectedBytes,
                "cids" to selected.map { it["cid"] }.distinct(), "scope" to SCOPE,
                "semantics" to "Minimum byte fraction of captured payloads in this window at this watermark; whole payloads retained. Other payloads are not deleted.")
            publish(id, state + ("retention" to (rows(state["retention"]) + policy)))
            results += mapOf("runId" to id, "retention" to policy)
        }
        results.toSeries()
    }

    suspend fun grades(): Series<borg.trikeshed.agent.modelmux.ModelGrade> = transact {
        states.values.flatMap { rows(it["reviews"]) }
            .filter { (it["generator"] as? String)?.startsWith("heuristic:") != true }
            .mapNotNull {
                borg.trikeshed.agent.modelmux.modelGrade(
                    it,
                    (it["sequence"] as Number).toLong()
                )
            }.toSeries()
    }

    suspend fun selections(): Series<borg.trikeshed.agent.modelmux.BreadcrumbReelState> = transact {
        states.values.flatMap { rows(it["reviews"]) }.groupBy { it["attemptId"] }.values
            .map { it.last() }.filter {
                it["disposition"] == borg.trikeshed.agent.modelmux.ModelDisposition.SELECTED.name &&
                    (it["correctness"] != borg.trikeshed.agent.modelmux.ModelCorrectness.INACCURATE.name || it["correctionCid"] is String)
            }.map { review ->
                val run = objectState(states.getValue(review["runId"] as String)["run"])
                val output = rows(run["lanes"]).first { it["id"] == review["lane"] }
                val correction = (review["correctionCid"] as? String)?.let {
                    val record = rows(states.getValue(review["runId"] as String)["captures"]).first { row -> row["cid"] == it }
                    require(available(record)) { "Selected correction is missing or corrupt" }
                    requireNotNull(cas.get(ContentId(it))) { "Selected correction is missing" }
                        .decodeToString(throwOnInvalidSequence = true)
                }
                review + ("content" to (correction ?: output["output"]))
            }.toSeries()
    }

    suspend fun restore(board: borg.trikeshed.graal.ConfixBlackboard) {
        for (row in history().view) {
            val id = row["id"] as String
            val run = requireNotNull(read(id))
            board.put("ace/run/$id", run, "ace-archive")
            run["assessment"]?.let { board.put("ace/assessment/$id", it, "ace-archive") }
            val capture = objectState(run["capture"])
            for (record in rows(capture["records"])) {
                if (record["kind"] != "summary") continue
                val lane = record["lane"] as? String ?: continue
                val breadcrumbId = record["name"] as String
                val event = rows(run["events"]).firstOrNull { it["breadcrumbId"] == breadcrumbId && it["lane"] == lane }
                board.put("ace/breadcrumb/$id/$lane/$breadcrumbId", mapOf(
                    "id" to breadcrumbId, "runId" to id, "lane" to lane, "contextId" to "$id-$lane",
                    "content" to payload(id, ContentId(record["cid"] as String)),
                    "children" to event?.get("parentIds"), "model" to event?.get("model"),
                ), "ace-archive")
            }
            for (review in rows(run["reviews"])) board.put("ace/review/${review["attemptId"]}", review, "ace-archive")
        }
    }

    fun objectState(value: Any?): borg.trikeshed.agent.modelmux.BreadcrumbReelState {
        val source = value as? Map<*, *> ?: throw IllegalArgumentException("Reel object required")
        require(source.keys.all { it is String })
        @Suppress("UNCHECKED_CAST")
        return source as borg.trikeshed.agent.modelmux.BreadcrumbReelState
    }

    fun rows(value: Any?): List<borg.trikeshed.agent.modelmux.BreadcrumbReelState> =
        (value as? List<*> ?: throw IllegalArgumentException("Reel array required")).map(::objectState)

    fun available(record: borg.trikeshed.agent.modelmux.BreadcrumbReelState): Boolean {
        val cid = ContentId(record["cid"] as String)
        val bytes = cas.get(cid) ?: return false
        return bytes.size.toLong() == (record["bytes"] as Number).toLong() && ContentId.of(bytes) == cid
    }
}
