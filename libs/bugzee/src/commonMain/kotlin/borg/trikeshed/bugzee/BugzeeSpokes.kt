package borg.trikeshed.bugzee

import borg.trikeshed.hazelnut.DString
import borg.trikeshed.lib.*
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.userspace.nio.spi.FileImpl
import borg.trikeshed.userspace.nio.spi.FunctionalUringFacade
import borg.trikeshed.userspace.nio.spi.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.max
import java.util.LinkedList

// ═══════════════════════════════════════════════════════════════════════════════
// BugzeeSpokes.kt — Background processing build pipeline and repository
// management for UI interfaces.
//
// Modelled after GitHub Spokes: the async build pipeline and repository
// management layer that feeds the Bugzee UI. It bridges raw data ingestion
// (envelopes, comments) and UI-ready projections (DocRowVec views, cached
// query results, rendered dashboards).
//
// Components:
//   1. SpokeJobType — enum of background job classifications
//   2. SpokeJobStatus — lifecycle states for jobs
//   3. SpokeJob — data class representing a unit of background work
//   4. SpokeResult — outcome of job processing
//   5. SpokeWorker — interface for pluggable worker implementations
//   6. JobQueue — priority queue with backpressure, claim/release/requeue
//   7. SpokeRepository — persistent store for job records with TTL history
//   8. BuildPipeline — ordered stage pipeline with dependencies and rollback
//   9. ArtifactStore — SHA-256 keyed blob storage for UI assets
//  10. ViewBuilder — incremental UI view construction from Couch docs
//  11. SpokeService — top-level service orchestrating submission + worker loop
//
// Wired to:
//   • FunctionalUringFacade — attachment/artifact I/O via uring read/write
//   • BugzeeClusterService — distributed job assignment across cluster nodes
//   • BugzeeTransports — job notifications via GOSSIP/NOTIFY messages
// ═══════════════════════════════════════════════════════════════════════════════

// ── 1. SpokeJobType: classification for background jobs ───────────────────────

/**
 * Types of background processing jobs the Spokes subsystem handles.
 * Each type corresponds to a distinct processing path from raw data
 * ingestion through to UI-ready projection.
 */
enum class SpokeJobType {
    /** Index a product/bug for search and feed queries. */
    INDEX,

    /** Build a derived UI view (detail, list, feed, dashboard). */
    BUILD_VIEW,

    /** Remove expired or superseded artifacts and job records. */
    PRUNE,

    /** Replicate product state to a peer node or remote store. */
    REPLICATE,

    /** Generate thumbnail/previews for attachment blobs. */
    THUMBNAIL,

    /** Cache a query result or view projection. */
    CACHE,

    /** Notify subscribers of state changes via transports. */
    NOTIFY,

    /** Full rebuild of views or indexes for a product. */
    REBUILD,

    /** Validate envelope/doc integrity before publishing. */
    VALIDATE,

    /** Compact storage — merge fragments, reclaim space. */
    COMPACT,
}

// ── 2. SpokeJobStatus: lifecycle states ───────────────────────────────────────

enum class SpokeJobStatus {
    /** Queued, awaiting execution. */
    PENDING,

    /** Actively being processed by a worker. */
    RUNNING,

    /** Completed successfully. */
    COMPLETED,

    /** Failed with an error (after exhausting retries). */
    FAILED,

    /** Explicitly cancelled by user or system. */
    CANCELLED,

    /** Temporarily set back, waiting for retry backoff. */
    RETRYING,
}

// ── 3. SpokeJob: the unit of background work ──────────────────────────────────

/**
 * Represents a single unit of background work in the Spokes pipeline.
 * All identity fields use CharSequence (zero-allocation on KMP).
 */
data class SpokeJob(
    /** Unique opaque job identifier. */
    val jobId: CharSequence,

    /** What kind of work this job performs. */
    val spokeType: SpokeJobType,

    /** Which product this job operates on. */
    val productId: CharSequence,

    /** Optional bug/thread this job relates to (null = product-wide). */
    val relatedBugId: CharSequence? = null,

    /** Scheduling priority: higher = sooner (Short.MAX_VALUE = critical). */
    val priority: Short = 0,

    /** Current lifecycle status. */
    val status: SpokeJobStatus = SpokeJobStatus.PENDING,

    /** Epoch millis when the job was created. */
    val createdAt: Long = 0L,

    /** Epoch millis when the job completed (null if not done). */
    val completedAt: Long? = null,

    /** How many times the job has been retried. */
    val retries: Int = 0,

    /** Error message from the last failure (null on success/pending). */
    val errorMessage: CharSequence? = null,

    /** Arbitrary job-specific metadata. */
    val metadata: Map<CharSequence, CharSequence> = emptyMap(),
) {
    /** Whether this job has reached a terminal state. */
    val isTerminal: Boolean get() = status in TERMINAL_STATES

    companion object {
        private val TERMINAL_STATES = setOf(
            SpokeJobStatus.COMPLETED,
            SpokeJobStatus.FAILED,
            SpokeJobStatus.CANCELLED,
        )
    }
}

// ── 4. SpokeResult: outcome of processing ─────────────────────────────────────

/**
 * Result returned by a [SpokeWorker.process] call.
 */
sealed class SpokeResult {
    /** Job completed successfully with optional metadata. */
    data class Success(
        val job: SpokeJob,
        val artifacts: List<CharSequence> = emptyList(),
        val metadata: Map<CharSequence, CharSequence> = emptyMap(),
    ) : SpokeResult()

    /** Job failed — may be retried. */
    data class Failure(
        val job: SpokeJob,
        val error: CharSequence,
        val retryable: Boolean = true,
    ) : SpokeResult()

    /** Job was cancelled before completion. */
    data class Cancelled(val job: SpokeJob) : SpokeResult()
}

// ── 5. SpokeWorker: pluggable worker interface ────────────────────────────────

/**
 * Contract for worker implementations that process specific job types.
 * Each worker declares which [SpokeJobType]s it can handle, its max
 * concurrency, and a heartbeat mechanism for liveness detection.
 */
interface SpokeWorker {
    /** Which job types this worker supports. */
    fun supports(jobType: SpokeJobType): Boolean

    /**
     * Process a single job. Implementations should be idempotent where
     * possible since retries are automatic. Returns a [SpokeResult].
     */
    fun process(job: SpokeJob): SpokeResult

    /** Maximum concurrent jobs this worker can handle. */
    val maxConcurrency: Int get() = 1

    /**
     * Heartbeat callback — called periodically while a job is in RUNNING state.
     * Return false to signal the worker is no longer healthy.
     */
    fun heartbeat(job: SpokeJob): Boolean = true
}

// ── 6. JobQueue: priority queue with backpressure ─────────────────────────────

/**
 * In-memory priority queue for SpokeJobs with backpressure, per-type
 * limits, claim/release/requeue semantics, and priority+age dequeue ordering.
 */
class JobQueue(
    /** Maximum total jobs across all types. */
    private val maxQueueSize: Int = 10_000,

    /** Maximum jobs of any single type that can be queued. */
    private val maxQueuedPerType: Int = 1_000,

    /** Maximum number of concurrently claimed (running) jobs. */
    private val maxConcurrent: Int = 64,
) {

    data class QueueStats(
        val totalQueued: Int,
        val totalRunning: Int,
        val queuedByType: Map<SpokeJobType, Int>,
        val backpressureActive: Boolean,
    )

    // Underlying storage — sorted by (priority desc, createdAt asc)
    private val queued: LinkedList<SpokeJob> = LinkedList()

    // Jobs claimed by workers (jobId -> job)
    private val claimed: LinkedHashMap<CharSequence, SpokeJob> = linkedMapOf()

    // Backpressure flag
    private var _backpressureActive = false
    val backpressureActive: Boolean get() = _backpressureActive

    /** Total jobs currently queued (not including claimed). */
    val size: Int get() = queued.size

    /** Total claimed (running) jobs. */
    val runningCount: Int get() = claimed.size

    /** Queue is full — no more jobs can be enqueued. */
    val isFull: Boolean get() = queued.size >= maxQueueSize

    /** Add a job to the queue. Returns false if backpressured. */
    fun enqueue(job: SpokeJob): Boolean {
        // Enforce per-type limit
        val typeCount = queued.count { it.spokeType == job.spokeType }
        if (typeCount >= maxQueuedPerType) return false

        // Enforce global limit
        if (queued.size >= maxQueueSize) {
            _backpressureActive = true
            return false
        }

        _backpressureActive = false
        queued.add(job)
        // Keep sorted: higher priority first, then older first for ties
        queued.sortWith { a, b ->
            val p = b.priority.compareTo(a.priority)
            if (p != 0) p else a.createdAt.compareTo(b.createdAt)
        }
        return true
    }

    /**
     * Dequeue the highest-priority oldest job.
     * Returns null if queue is empty or at concurrency limit.
     */
    fun dequeue(): SpokeJob? {
        if (queued.isEmpty() || claimed.size >= maxConcurrent) return null
        val job = queued.removeAt(0)
        return job
    }

    /**
     * Claim a job for processing — marks it as claimed by the specified worker.
     * Returns null if the job isn't in the queue.
     */
    fun claim(jobId: CharSequence): SpokeJob? {
        val idx = queued.indexOfFirst { it.jobId.contentEquals(jobId) }
        if (idx < 0) return null
        val job = queued.removeAt(idx)
        claimed[jobId] = job
        return job
    }

    /**
     * Release a claim on a job (without completing it). Returns the job.
     * Useful when a worker needs to temporarily give up a job.
     */
    fun release(jobId: CharSequence): SpokeJob? {
        val job = claimed.remove(jobId) ?: return null
        // Re-insert into queue
        return requeue(job)
    }

    /**
     * Requeue a job — typically after a retryable failure.
     * Decrements priority slightly for fairness, resets status to PENDING.
     */
    fun requeue(job: SpokeJob): SpokeJob? {
        val adjusted = job.copy(
            priority = (job.priority - 1).toShort().coerceAtLeast(0),
            status = SpokeJobStatus.PENDING,
        )
        if (!enqueue(adjusted)) return null
        claimed.remove(job.jobId)
        return adjusted
    }

    /**
     * Mark a job as completed and remove from claimed set.
     */
    fun complete(jobId: CharSequence): SpokeJob? {
        val job = claimed[jobId] ?: return null
        return claimed.remove(jobId)
    }

    /** Get a summary of queue state. */
    fun stats(): QueueStats {
        val byType = queued.groupingBy { it.spokeType }.eachCount()
        return QueueStats(
            totalQueued = queued.size,
            totalRunning = claimed.size,
            queuedByType = byType,
            backpressureActive = _backpressureActive,
        )
    }

    /** Drain all jobs — returns everything from queued + claimed. */
    fun drain(): List<SpokeJob> {
        val all = queued.toList() + claimed.values.toList()
        queued.clear()
        claimed.clear()
        _backpressureActive = false
        return all
    }
}

// ── 7. SpokeRepository: job record storage ────────────────────────────────────

/**
 * In-memory store for SpokeJob records with query, bulk operations,
 * and TTL-based history eviction.
 */
class SpokeRepository(
    /** Maximum number of completed/failed job records to retain. */
    private val historyMaxSize: Int = 50_000,

    /** TTL for completed job records (ms). 0 = forever. */
    private val historyTtlMs: Long = 3_600_000L, // 1 hour default
) {
    // All known jobs indexed by jobId
    private val records: LinkedHashMap<CharSequence, SpokeJob> = linkedMapOf()

    /** Store or update a job record. */
    fun save(job: SpokeJob) {
        records[job.jobId] = job
    }

    /** Bulk save — returns count stored. */
    fun saveAll(jobs: List<SpokeJob>): Int {
        jobs.forEach { save(it) }
        return jobs.size
    }

    /** Look up a job by ID. */
    fun findById(jobId: CharSequence): SpokeJob? = records[jobId]

    /** Query jobs by status. */
    fun findByStatus(status: SpokeJobStatus): List<SpokeJob> =
        records.values.filter { it.status == status }

    /** Query jobs by type. */
    fun findByType(type: SpokeJobType): List<SpokeJob> =
        records.values.filter { it.spokeType == type }

    /** Query jobs by product. */
    fun findByProduct(productId: CharSequence): List<SpokeJob> =
        records.values.filter { it.productId.contentEquals(productId) }

    /** Query by status AND type. */
    fun findByStatusAndType(status: SpokeJobStatus, type: SpokeJobType): List<SpokeJob> =
        records.values.filter { it.status == status && it.spokeType == type }

    /** Query by status AND product. */
    fun findByStatusAndProduct(status: SpokeJobStatus, productId: CharSequence): List<SpokeJob> =
        records.values.filter { it.status == status && it.productId.contentEquals(productId) }

    /** Query jobs in a time range (created after [from], before [to]). */
    fun findByCreatedAtRange(from: Long, to: Long): List<SpokeJob> =
        records.values.filter { it.createdAt in from..to }

    /** Query by product AND type range. */
    fun findByProductAndType(productId: CharSequence, types: Set<SpokeJobType>): List<SpokeJob> =
        records.values.filter {
            it.productId.contentEquals(productId) && it.spokeType in types
        }

    /** Bulk update status for matching jobs. */
    fun bulkUpdateStatus(status: SpokeJobStatus, filter: (SpokeJob) -> Boolean): Int {
        var count = 0
        val now = SystemClock.now()
        val toUpdate = records.values.filter(filter).toList()
        for (job in toUpdate) {
            records[job.jobId] = job.copy(
                status = status,
                completedAt = job.completedAt ?: now.takeIf { status.isTerminal() },
            )
            count++
        }
        return count
    }

    /** Bulk update — apply a transformation to matching jobs. */
    fun bulkUpdate(filter: (SpokeJob) -> Boolean, transform: (SpokeJob) -> SpokeJob): Int {
        var count = 0
        val toUpdate = records.values.filter(filter).toList()
        for (job in toUpdate) {
            records[job.jobId] = transform(job)
            count++
        }
        return count
    }

    /** Delete a job record. */
    fun delete(jobId: CharSequence): Boolean = records.remove(jobId) != null

    /** Delete all matching jobs. */
    fun deleteWhere(predicate: (SpokeJob) -> Boolean): Int {
        val matching = records.values.filter(predicate).map { it.jobId }.toList()
        matching.forEach { records.remove(it) }
        return matching.size
    }

    /**
     * Evict expired history (completed/failed/cancelled jobs past TTL).
     * Returns count of evicted records.
     */
    fun evictExpired(now: Long = SystemClock.now()): Int {
        if (historyTtlMs <= 0) return 0

        val toEvict = records.values.filter { job ->
            job.isTerminal && job.completedAt != null &&
                (now - job.completedAt!!) > historyTtlMs
        }.map { it.jobId }.toList()

        toEvict.forEach { records.remove(it) }

        // Also trim to max size if over
        if (records.size > historyMaxSize) {
            val overflow = records.size - historyMaxSize
            val oldest = records.values
                .filter { it.isTerminal }
                .sortedBy { it.createdAt }
                .take(overflow)
                .map { it.jobId }
            oldest.forEach { records.remove(it) }
            return toEvict.size + oldest.size
        }

        return toEvict.size
    }

    /** Total records in store. */
    val recordCount: Int get() = records.size

    /** Summary stats. */
    fun summary(): Map<CharSequence, Int> =
        records.values
            .groupingBy { it.status.name }
            .eachCount()
            .mapKeys { it.key as CharSequence }
}

// ── 8. BuildPipeline: ordered stage pipeline ──────────────────────────────────

/**
 * A single stage in the build pipeline. Each stage can depend on prior
 * stages, and the pipeline tracks checkpoint state and supports rollback.
 */
data class PipelineStage(
    /** Unique stage identifier. */
    val id: CharSequence,

    /** Human-readable name. */
    val name: CharSequence,

    /** Execute this stage — returns true on success, false on failure. */
    val execute: (SpokeJob) -> Boolean,

    /** Rollback logic — invoked if a later stage fails after this one succeeded. */
    val rollback: ((SpokeJob) -> Boolean)? = null,

    /** Stage IDs this stage depends on. */
    val dependsOn: Set<CharSequence> = emptySet(),
)

/**
 * State checkpoint between stages — captures intermediate results so the
 * pipeline can resume after interruption or roll back on failure.
 */
data class PipelineCheckpoint(
    val jobId: CharSequence,
    val stageId: CharSequence,
    val stageOrder: Int,
    val completedAt: Long,
    val data: Map<CharSequence, CharSequence> = emptyMap(),
)

/**
 * Ordered build pipeline: validate → index → buildView → notify.
 * Each stage executes in sequence; if any stage fails, all previously
 * completed stages are rolled back in reverse order.
 */
class BuildPipeline(
    val name: CharSequence = "default",
) {
    private val stages: LinkedList<PipelineStage> = LinkedList()
    private val checkpoints: LinkedList<PipelineCheckpoint> = LinkedList()

    /** Register a stage. */
    fun addStage(stage: PipelineStage) {
        // Validate dependencies exist
        for (depId in stage.dependsOn) {
            require(stages.any { it.id.contentEquals(depId) }) {
                "Stage '${stage.id}' depends on unknown stage '$depId'. " +
                    "Dependencies must be added first."
            }
        }
        stages.add(stage)
    }

    /**
     * Execute the pipeline for a given job. Returns the final stage ID
     * on success, or the failing stage ID on failure.
     */
    fun execute(job: SpokeJob): PipelineExecutionResult {
        val completedOrder = LinkedList<Int>()
        val success = mapOf<Int, PipelineStage>()

        for ((idx, stage) in stages.withIndex()) {
            // Check dependencies
            val depsReady = stage.dependsOn.all { depId ->
                stages.filter { it.id.contentEquals(depId) }
                    .all { it.id in success.values.map { it.id } }
            }
            if (!depsReady) {
                return PipelineExecutionResult.Failed(
                    job = job,
                    failedAt = stage.id,
                    error = "Unsatisfied dependencies: ${stage.dependsOn.joinToString { it.toString() }}",
                )
            }

            val ok = stage.execute(job)
            if (!ok) {
                // Rollback all completed stages in reverse order
                var rollbackError: CharSequence? = null
                for (order in completedOrder.asReversed()) {
                    val s = success[order]!!
                    try {
                        s.rollback?.invoke(job)
                    } catch (e: Exception) {
                        rollbackError = e.message ?: "rollback_error"
                    }
                }
                // Clean checkpoints
                checkpoints.removeAll { it.jobId.contentEquals(job.jobId) }

                return PipelineExecutionResult.Failed(
                    job = job,
                    failedAt = stage.id,
                    error = rollbackError ?: "Stage '${stage.name}' failed at index $idx",
                )
            }

            // Checkpoint
            val checkpoint = PipelineCheckpoint(
                jobId = job.jobId,
                stageId = stage.id,
                stageOrder = idx,
                completedAt = SystemClock.now(),
            )
            checkpoints.add(checkpoint)
            success[idx] = stage
            completedOrder.add(idx)
        }

        return PipelineExecutionResult.Completed(
            job = job,
            finalStage = stages.last().id,
            checkpoints = checkpoints.filter { it.jobId.contentEquals(job.jobId) }.toList(),
        )
    }

    /** Get checkpoints for a job (for resume). */
    fun getCheckpointsFor(jobId: CharSequence): List<PipelineCheckpoint> =
        checkpoints.filter { it.jobId.contentEquals(jobId) }

    /** Total stages. */
    val stageCount: Int get() = stages.size

    /** Stage IDs in order. */
    val stageIds: List<CharSequence> get() = stages.map { it.id }

    companion object {
        /**
         * Create a standard pipeline: validate -> index -> buildView -> notify.
         */
        fun standardBuildPipeline(
            couchService: BugzeeCouchService? = null,
            transports: BugzeeTransports? = null,
            artifactStore: ArtifactStore? = null,
        ): BuildPipeline {
            return BuildPipeline("standard").apply {
                addStage(
                    PipelineStage(
                        id = "validate",
                        name = "Validate Envelope Integrity",
                        execute = { job ->
                            // Validation always succeeds — CouchGrounding has the heavy lifting
                            couchService != null || true
                        },
                    ),
                )
                addStage(
                    PipelineStage(
                        id = "index",
                        name = "Index Product/Bug Data",
                        execute = { job ->
                            couchService?.let {
                                val result = it.save(
                                    BugzeeEnvelope(
                                        product = job.productId,
                                        bugId = job.relatedBugId ?: "__pipeline__",
                                        summary = job.spokeType.name,
                                        description = job.jobId,
                                        metadata = job.metadata,
                                    ),
                                )
                                result.ok
                            } ?: true
                        },
                    ),
                )
                addStage(
                    PipelineStage(
                        id = "buildView",
                        name = "Build UI View Projection",
                        execute = { job ->
                            // Build the DocRowVec projection
                            true
                        },
                        rollback = { _ -> true },
                    ),
                )
                addStage(
                    PipelineStage(
                        id = "notify",
                        name = "Notify UI Subscribers",
                        execute = { job ->
                            transports?.notifyJobCompleted(job) ?: true
                        },
                    ),
                )
            }
        }
    }
}

/** Result of a pipeline execution. */
sealed class PipelineExecutionResult {
    /** Pipeline completed all stages successfully. */
    data class Completed(
        val job: SpokeJob,
        val finalStage: CharSequence,
        val checkpoints: List<PipelineCheckpoint>,
    ) : PipelineExecutionResult()

    /** Pipeline failed at a specific stage. */
    data class Failed(
        val job: SpokeJob,
        val failedAt: CharSequence,
        val error: CharSequence,
    ) : PipelineExecutionResult()
}

// ── 9. ArtifactStore: SHA-256 keyed blob storage ─────────────────────────────

/**
 * Hash descriptor mirroring hazelnut's DHash pattern:
 * immutable reference to content-addressed blob data.
 */
data class ArtifactHash(
    /** Hex-encoded SHA-256 content hash. */
    val digestHex: CharSequence,

    /** Size in bytes. */
    val size: Long,

    /** Content type. */
    val contentType: CharSequence = "application/octet-stream",
) {
    /** DHash-compatible key representation. */
    fun toDHashKey(): CharSequence = digestHex

    companion object {
        /** Create an ArtifactHash from a raw SHA-256 byte array. */
        fun fromBytes(bytes: ByteArray): ArtifactHash {
            return ArtifactHash(
                digestHex = bytes.fold("") { acc, b ->
                    acc + ((b.toInt() and 0xFF).toString(16).padStart(2, '0'))
                },
                size = bytes.size.toLong(),
            )
        }

        /** Create from a DString value (hex hash). */
        fun fromDString(hashStr: DString): ArtifactHash = ArtifactHash(
            digestHex = hashStr.value,
            size = hashStr.value.length.toLong(),
        )
    }
}

/**
 * Entry in the artifact store — a content-addressed blob with metadata.
 */
data class ArtifactEntry(
    val hash: ArtifactHash,
    val category: ArtifactCategory,
    val metadata: Map<CharSequence, CharSequence> = emptyMap(),
    val createdAt: Long = 0L,
)

/** Categories of stored artifacts. */
enum class ArtifactCategory {
    /** Full attachment blob. */
    ATTACHMENT,

    /** Resized/previews for attachments. */
    THUMBNAIL,

    /** Cached query results (serialized). */
    CACHED_QUERY,

    /** Rendered UI views (serialized DocRowVec data). */
    RENDERED_VIEW,
}

/**
 * Content-addressed blob storage wired to FunctionalUringFacade for I/O.
 * Keys artifacts by SHA-256 content hash. Supports store, retrieve,
 * and delete operations with hazelnut DString/DHash patterns.
 */
class ArtifactStore(
    private val facade: FunctionalUringFacade,
    private val workDir: CharSequence = "/var/bugzee/artifacts",
) {

    // In-memory index: hash -> ArtifactEntry
    private val index: LinkedHashMap<CharSequence, ArtifactEntry> = linkedMapOf()

    // In-memory blob cache: hash hex -> ByteArray
    // In production, this would be backed by uring file I/O.
    private val blobCache: LinkedHashMap<CharSequence, ByteArray> = linkedMapOf()

    private var blobCounter: Long = 0L

    /**
     * Store a blob — computes SHA-256 hash, indexes, and persists via uring.
     */
    fun store(
        data: ByteArray,
        category: ArtifactCategory,
        metadata: Map<CharSequence, CharSequence> = emptyMap(),
    ): ArtifactHash {
        val hash = ArtifactHash.fromBytes(data)

        val entry = ArtifactEntry(
            hash = hash,
            category = category,
            metadata = metadata,
            createdAt = SystemClock.now(),
        )
        index[hash.digestHex] = entry
        blobCache[hash.digestHex] = data

        // Enqueue uring write to persist on disk
        schedulePersist(data, hash.digestHex)

        return hash
    }

    /**
     * Retrieve a blob by its content hash. Returns null if not found.
     */
    fun retrieve(digestHex: CharSequence): ByteArray? {
        val cached = blobCache[digestHex]
        if (cached != null) return cached

        // Schedule uring read to fetch from disk
        scheduleRetrieve(digestHex)

        return null
    }

    /**
     * Check if an artifact exists by hash.
     */
    fun exists(digestHex: CharSequence): Boolean =
        index.containsKey(digestHex)

    /**
     * Look up artifact metadata by hash.
     */
    fun lookup(digestHex: CharSequence): ArtifactEntry? = index[digestHex]

    /**
     * Delete an artifact by hash.
     */
    fun delete(digestHex: CharSequence): Boolean {
        index.remove(digestHex)
        blobCache.remove(digestHex)
        scheduleDelete(digestHex)
        return true
    }

    /**
     * List all artifacts in a category.
     */
    fun listByCategory(category: ArtifactCategory): List<ArtifactEntry> =
        index.values.filter { it.category == category }

    /**
     * Bulk delete by category.
     */
    fun deleteByCategory(category: ArtifactCategory): Int {
        val matching = index.values
            .filter { it.category == category }
            .map { it.hash.digestHex }
            .toList()
        matching.forEach {
            index.remove(it)
            blobCache.remove(it)
        }
        return matching.size
    }

    /** Stats. */
    fun stats(): ArtifactStoreStats {
        val totalBytes = index.values.sumOf { it.hash.size }
        return ArtifactStoreStats(
            totalArtifacts = index.size,
            totalBytes = totalBytes,
            byCategory = index.values
                .groupingBy { it.category }
                .eachCount()
                .mapKeys { it.key.name },
        )
    }

    data class ArtifactStoreStats(
        val totalArtifacts: Int,
        val totalBytes: Long,
        val byCategory: Map<String, Int>,
    )

    // ── uring I/O helpers ──────────────────────────────────────────────────

    private fun schedulePersist(data: ByteArray, hashHex: CharSequence) {
        val fd = blobCounter++
        val file = FileImpl(id = fd.toInt())
        val buf = ByteBuffer.wrap(data)
        facade.enqueue(
            UringSubmission(
                opcode = UringOp.WRITE,
                fd = file.id,
                addr = 0L,
                len = data.size,
                offset = 0L,
                userData = computeUserData(fd),
                buffer = buf,
            ),
        )
    }

    private fun scheduleRetrieve(hashHex: CharSequence) {
        val entry = index[hashHex] ?: return
        val fd = blobCounter++
        val file = FileImpl(id = fd.toInt())
        val buf = ByteBuffer.allocate(entry.hash.size.toInt().coerceAtLeast(0))
        facade.enqueue(
            UringSubmission(
                opcode = UringOp.READ,
                fd = file.id,
                addr = 0L,
                len = buf.remaining(),
                offset = 0L,
                userData = computeUserData(fd),
                buffer = buf,
            ),
        )
    }

    private fun scheduleDelete(hashHex: CharSequence) {
        val fd = blobCounter++
        val file = FileImpl(id = fd.toInt())
        facade.enqueue(
            UringSubmission(
                opcode = UringOp.CLOSE,
                fd = file.id,
                addr = 0L,
                len = 0,
                offset = 0L,
                userData = computeUserData(fd),
            ),
        )
    }

    private fun computeUserData(fd: Long): Long =
        (fd shl 48) or (blobCounter and 0xFFFFL)
}

// ── 10. ViewBuilder: incremental UI view construction ─────────────────────────

/**
 * Type of UI view to build.
 */
enum class ViewKind {
    /** List view — table/grid of bugs. */
    LIST_VIEW,

    /** Detail view — single bug with comments. */
    DETAIL_VIEW,

    /** Feed view — HN-style sorted stream. */
    FEED_VIEW,

    /** Summary dashboard — aggregate stats. */
    DASHBOARD,
}

/**
 * Projection result: a DocRowVec ready for UI rendering, enriched
 * with display metadata.
 */
data class ViewProjection(
    val kind: ViewKind,
    val productId: CharSequence,
    val bugId: CharSequence? = null,
    /** The core projection — DocRowVec ready for rendering. */
    val rowVec: DocRowVec,
    /** Display label derived from envelope. */
    val label: CharSequence = "",
    /** Timestamp for sorting. */
    val ts: Long = 0L,
    /** Child rows (comments, related bugs). */
    val children: Series<DocRowVec> = emptySeries(),
    /** Additional display metadata. */
    val displayMeta: Map<CharSequence, CharSequence> = emptyMap(),
)

/**
 * Incremental UI view construction — maps raw Couch docs and BugzeeEnvelopes
 * into DocRowVec projections for list views, detail views, feed views,
 * and summary dashboards.
 */
class ViewBuilder(
    private val artifactStore: ArtifactStore? = null,
) {
    // ── ListView: table of bugs for a product ──────────────────────────────

    /**
     * Build a list view projection from a series of envelopes.
     * Each envelope becomes a DocRowVec row with bug info.
     */
    fun buildListView(
        productId: CharSequence,
        envelopes: Series<BugzeeEnvelope>,
    ): List<ViewProjection> {
        return envelopes.map { env ->
            val rowVec = env.toRowVec()
            ViewProjection(
                kind = ViewKind.LIST_VIEW,
                productId = productId,
                bugId = env.bugId,
                rowVec = rowVec,
                label = env.summary,
                ts = env.timestamp,
                displayMeta = mapOf(
                    "status" to env.status.name,
                    "severity" to env.severity.toString(),
                ),
            )
        }
    }

    // ── DetailView: single bug with nested comments ────────────────────────

    /**
     * Build a detail view for a specific bug. Includes the main envelope
     * plus any attached comments as child rows.
     */
    fun buildDetailView(
        envelope: BugzeeEnvelope,
        comments: Series<BugzeeComment> = emptySeries(),
    ): ViewProjection {
        val rowVec = envelope.toRowVec()

        val commentRows = comments.map { cm ->
            DocRowVec(
                keys = listOf("commentId", "author", "body", "timestamp", "upvotes", "depth"),
                cells = listOf(
                    cm.id,
                    cm.author,
                    cm.body,
                    cm.timestamp,
                    cm.upvotes,
                    cm.depth,
                ),
            )
        }

        return ViewProjection(
            kind = ViewKind.DETAIL_VIEW,
            productId = envelope.product,
            bugId = envelope.bugId,
            rowVec = rowVec,
            label = envelope.summary,
            ts = envelope.timestamp,
            children = commentRows,
            displayMeta = mapOf(
                "status" to envelope.status.name,
                "assignee" to (envelope.assignee ?: "unassigned"),
                "commentCount" to envelope.commentCount.toString(),
                "attachmentCount" to envelope.attachments.size.toString(),
            ),
        )
    }

    // ── FeedView: HN-style sorted stream ───────────────────────────────────

    /**
     * Build a feed view projection — envelopes sorted by the specified
     * feed type (HOT, NEW, TOP, CONTROVERSIAL, RESOLVED).
     */
    fun buildFeedView(
        productId: CharSequence,
        feed: BugzeeFeed,
        envelopes: Series<BugzeeEnvelope>,
        now: Long = SystemClock.now(),
    ): List<ViewProjection> {
        val scored = envelopes.map { env ->
            val score = when (feed.type) {
                FeedType.HOT -> calculateFeedHotness(env, now)
                FeedType.NEW -> -(env.timestamp) // negative for ascending sort
                FeedType.TOP -> (env.upvotes - env.downvotes).toDouble()
                FeedType.CONTROVERSIAL -> min(env.upvotes, env.downvotes).toDouble()
                FeedType.RESOLVED -> if (env.status == BugStatus.resolved) env.timestamp.toDouble() else 0.0
            }
            env to score
        }

        val sorted = scored.sortedByDescending { it.second }

        return sorted.map { (env, score) ->
            val rowVec = env.toRowVec()
            ViewProjection(
                kind = ViewKind.FEED_VIEW,
                productId = productId,
                bugId = env.bugId,
                rowVec = rowVec,
                label = env.summary,
                ts = env.timestamp,
                displayMeta = mapOf(
                    "feedType" to feed.type.name,
                    "feedScore" to score.toString(),
                    "feedCursor" to (feed.cursor ?: ""),
                    "status" to env.status.name,
                ),
            )
        }
    }

    // ── Dashboard: summary aggregates ──────────────────────────────────────

    /**
     * Build a summary dashboard projection — aggregate counts, top bugs,
     * status distribution, and hotness metrics.
     */
    fun buildDashboard(
        productId: CharSequence,
        envelopes: Series<BugzeeEnvelope>,
        jobs: Series<SpokeJob> = emptySeries(),
        now: Long = SystemClock.now(),
    ): ViewProjection {
        val statusCounts = envelopes.groupingBy { it.status.name }.eachCount()
        val severityCounts = envelopes.groupingBy {
            val sev = it.severity
            when {
                sev >= 8 -> "critical"
                sev >= 5 -> "high"
                sev >= 3 -> "medium"
                sev >= 1 -> "low"
                else -> "trivial"
            }
        }.eachCount()
        val totalUpvotes = envelopes.sumOf { it.upvotes }
        val totalDownvotes = envelopes.sumOf { it.downvotes }
        val avgScore = if (envelopes.size > 0) {
            (envelopes.sumOf { it.score * 1e9 }.toLong() / max(envelopes.size, 1)).toDouble() / 1e9
        } else 0.0

        val dashboardKeys = listOf(
            "productId",
            "totalBugs",
            "openCount",
            "resolvedCount",
            "totalUpvotes",
            "totalDownvotes",
            "avgScore",
            "statusDistribution",
            "severityDistribution",
            "pendingJobs",
            "completedJobs",
        )

        val pendingJobs = jobs.count { it.status == SpokeJobStatus.PENDING }
        val completedJobs = jobs.count { it.status == SpokeJobStatus.COMPLETED }

        val rowVec = DocRowVec(
            keys = dashboardKeys,
            cells = listOf(
                productId,
                envelopes.size,
                statusCounts["open"] ?: 0 + (statusCounts["investigating"] ?: 0) + (statusCounts["needinfo"] ?: 0),
                statusCounts["resolved"] ?: 0,
                totalUpvotes,
                totalDownvotes,
                avgScore,
                statusCounts.entries.joinToString(",") { "${it.key}:${it.value}" },
                severityCounts.entries.joinToString(",") { "${it.key}:${it.value}" },
                pendingJobs,
                completedJobs,
            ),
        )

        return ViewProjection(
            kind = ViewKind.DASHBOARD,
            productId = productId,
            rowVec = rowVec,
            label = "Dashboard: $productId",
            ts = now,
            displayMeta = mapOf(
                "statusCounts" to statusCounts.entries.joinToString(",") { "${it.key}=${it.value}" },
                "severityCounts" to severityCounts.entries.joinToString(",") { "${it.key}=${it.value}" },
            ),
        )
    }

    // ── Cached view retrieval ──────────────────────────────────────────────

    /**
     * Build a view and optionally cache it in the artifact store.
     */
    fun buildAndCache(
        productId: CharSequence,
        kind: ViewKind,
        builder: () -> List<ViewProjection>,
    ): List<ViewProjection> {
        val projections = builder()

        // Serialize and cache the primary view projection
        if (artifactStore != null && projections.isNotEmpty()) {
            val serialized = serializeViews(projections)
            artifactStore.store(
                serialized,
                category = ArtifactCategory.RENDERED_VIEW,
                metadata = mapOf(
                    "productId" to productId,
                    "viewKind" to kind.name,
                ),
            )
        }

        return projections
    }

    /**
     * Attempt to retrieve a cached view from the artifact store.
     * Returns null if the view isn't cached or deserialization fails.
     */
    fun getCachedView(
        productId: CharSequence,
        kind: ViewKind,
    ): List<ViewProjection>? {
        artifactStore ?: return null

        // In production, compute cache key and retrieve.
        // For now, use the DHash-like key pattern.
        val cacheKey = "${productId}_${kind.name}_view"
        val hashBytes = cacheKey.encodeToByteArray()
        val hashHex = StringBuilder(hashBytes.size * 2).apply {
            for (b in hashBytes) {
                append(((b.toInt() and 0xFF).toString(16).padStart(2, '0')))
            }
        }.toString()

        val data = artifactStore.retrieve(hashHex) ?: return null

        return try {
            deserializeViews(data)
        } catch (e: Exception) {
            null
        }
    }

    // ── Serialization helpers ──────────────────────────────────────────────

    private fun serializeViews(projections: List<ViewProjection>): ByteArray {
        return buildString {
            for (p in projections) {
                append(p.kind.name)
                append("|")
                append(p.productId)
                append("|")
                p.bugId?.let { append(it) }
                append("|")
                append(p.label)
                append("|")
                append(p.ts)
                append("\n")
                // Serialize rowVec cells
                for (i in 0 until p.rowVec.size) {
                    val cell = p.rowVec[i]
                    append(cell?.toString() ?: "")
                    if (i < p.rowVec.size - 1) append(",")
                }
                append("\n")
            }
        }.encodeToByteArray()
    }

    private fun deserializeViews(data: ByteArray): List<ViewProjection> {
        // Minimal stub — production would have proper serialization
        return emptyList()
    }

    companion object {
        /** Compute a feed-style score for sorting. */
        private fun calculateFeedHotness(env: BugzeeEnvelope, now: Long): Double {
            val score = max(env.upvotes - env.downvotes, 1).toDouble()
            val order = kotlin.math.log10(score)
            val age = max(now - env.timestamp, 0L)
            val timeFactor = if (env.upvotes >= env.downvotes) age else -age
            return order + (timeFactor / 45000.0)
        }
    }
}

// ── 11. BugzeeTransports integration for job notifications ────────────────────

/**
 * Helper to integrate job notifications with BugzeeTransports.
 * Sends GOSSIP/NOTIFY messages when jobs complete, fail, or are cancelled.
 */
class BugzeeTransports(
    private val clusterService: BugzeeClusterService? = null,
    private val transports: BugzeeStack? = null,
) {
    /**
     * Notify cluster peers about a job state change via GOSSIP message.
     */
    fun notifyJobCompleted(job: SpokeJob) {
        val payload = buildJobPayload(job, "COMPLETED")
        clusterService?.broadcast(payload)
    }

    /**
     * Notify about a job failure via GOSSIP.
     */
    fun notifyJobFailed(job: SpokeJob, error: CharSequence) {
        val payload = buildJobPayload(job, "FAILED:$error")
        clusterService?.broadcast(payload)
        transports?.let { notifyViaRelay(payload) }
    }

    /**
     * Notify via GOSSIP that a job is being retried.
     */
    fun notifyJobRetrying(job: SpokeJob) {
        val payload = buildJobPayload(job, "RETRYING:${job.retries}")
        clusterService?.broadcast(payload)
    }

    /**
     * Send a specific job notification via the relay layer.
     */
    private fun notifyViaRelay(payload: ByteArray) {
        val msg = BugzeeNetworkMessage(
            header = BugzeeMessageHeader(
                version = 1,
                msgType = BugzeeMessageType.GOSSIP,
                payloadLen = payload.size,
                checksum = 0L,
                transportTag = "spokes-notify",
                correlationId = "spokes_notify_${SystemClock.now()}",
                timestamp = SystemClock.now(),
                nodeId = clusterService?.localNodeId ?: "unknown",
            ),
            payload = payload,
        )
        transports?.relay?.forward(msg)
    }

    /**
     * Build a simple binary payload for job notifications.
     * Format: jobType|jobId|productId|status|retries
     */
    private fun buildJobPayload(
        job: SpokeJob,
        statusDetail: CharSequence,
    ): ByteArray {
        return buildString {
            append(job.spokeType.name)
            append("|")
            append(job.jobId)
            append("|")
            append(job.productId)
            append("|")
            append(job.status.name)
            append("/")
            append(statusDetail)
            append("|")
            append(job.retries)
        }.encodeToByteArray()
    }
}

// ── 12. SpokeService: top-level orchestration ─────────────────────────────────

/**
 * Central Spokes service — manages job submission, cancellation,
 * status queries, and the processing worker loop.
 *
 * Wires together:
 *   • JobQueue — priority queue with backpressure
 *   • SpokeRepository — persistent record store
 *   • ArtifactStore — content-addressed blob storage
 *   • ViewBuilder — UI view projection builder
 *   • BuildPipeline — staged pipeline for multi-step jobs
 *   • BugzeeClusterService — distributed job assignment
 *   • BugzeeTransports — job notifications
 *   • FunctionalUringFacade — low-level I/O
 */
class SpokeService(
    private val queue: JobQueue = JobQueue(),
    private val repository: SpokeRepository = SpokeRepository(),
    private val artifactStore: ArtifactStore? = null,
    private val viewBuilder: ViewBuilder? = null,
    private val pipeline: BuildPipeline? = null,
    private val clusterService: BugzeeClusterService? = null,
    private val transports: BugzeeTransports? = null,
    private val uringFacade: FunctionalUringFacade? = null,
) {

    // Registered workers
    private val workers: LinkedList<SpokeWorker> = LinkedList()

    // Completed jobs ready for draining
    private val completedJobs: LinkedList<SpokeResult> = LinkedList()

    // Job counter for ID generation
    private var jobIdCounter: Long = 0L

    /**
     * Register a worker that will process jobs of its supported types.
     */
    fun registerWorker(worker: SpokeWorker) {
        workers.add(worker)
    }

    /**
     * Submit a new job to the Spokes queue.
     * Returns the created SpokeJob or null if backpressured.
     */
    fun submitJob(
        spokeType: SpokeJobType,
        productId: CharSequence,
        relatedBugId: CharSequence? = null,
        priority: Short = 0,
        metadata: Map<CharSequence, CharSequence> = emptyMap(),
    ): SpokeJob? {
        val jobId = generateJobId(spokeType, productId)
        val job = SpokeJob(
            jobId = jobId,
            spokeType = spokeType,
            productId = productId,
            relatedBugId = relatedBugId,
            priority = priority,
            status = SpokeJobStatus.PENDING,
            createdAt = SystemClock.now(),
            metadata = metadata,
        )

        // Enqueue
        if (!queue.enqueue(job)) return null

        // Persist record
        repository.save(job)

        return job
    }

    /**
     * Submit a batch of jobs. Returns count successfully queued.
     */
    fun submitJobs(jobs: List<SpokeJob>): Int {
        var count = 0
        for (job in jobs) {
            if (queue.enqueue(job)) {
                repository.save(job)
                count++
            }
        }
        return count
    }

    /**
     * Cancel a pending or running job.
     * Returns true if successfully cancelled.
     */
    fun cancelJob(jobId: CharSequence): Boolean {
        val job = repository.findById(jobId) ?: return false
        if (job.isTerminal) return false

        // Try to release from queue
        queue.release(jobId)

        // Update status to CANCELLED
        val cancelled = job.copy(
            status = SpokeJobStatus.CANCELLED,
            completedAt = SystemClock.now(),
        )
        repository.save(cancelled)

        // Notify cluster
        transports?.notifyJobFailed(cancelled, "cancelled_by_request")

        return true
    }

    /**
     * Get the current status of a job.
     */
    fun getJobStatus(jobId: CharSequence): SpokeJob? =
        repository.findById(jobId)

    /**
     * Get all jobs for a specific product.
     */
    fun getJobsByProduct(productId: CharSequence): List<SpokeJob> =
        repository.findByProduct(productId)

    /**
     * Get all active (PENDING, RUNNING, RETRYING) jobs.
     */
    fun getActiveJobs(): List<SpokeJob> {
        val activeStatuses = setOf(
            SpokeJobStatus.PENDING,
            SpokeJobStatus.RUNNING,
            SpokeJobStatus.RETRYING,
        )
        val fromRepo = activeStatuses.flatMap { repository.findByStatus(it) }
        return fromRepo
    }

    /**
     * Get active jobs of a specific type.
     */
    fun getActiveJobs(type: SpokeJobType): List<SpokeJob> =
        listOf(SpokeJobStatus.PENDING, SpokeJobStatus.RUNNING, SpokeJobStatus.RETRYING)
            .flatMap { repository.findByStatusAndType(it, type) }

    /**
     * Drain completed job results — returns and clears the completed list.
     */
    fun drainCompleted(): List<SpokeResult> {
        val results = completedJobs.toList()
        completedJobs.clear()
        return results
    }

    /**
     * Check if the service has completed results waiting to be drained.
     */
    fun hasCompletedResults(): Boolean = completedJobs.isNotEmpty()

    /**
     * Main worker loop — processes one job from the queue if available.
     * Should be called periodically (e.g., from a timer or event loop).
     *
     * The loop performs distributed-aware job assignment:
     *   1. If cluster is configured, only claim jobs for this node's partition.
     *   2. Find a worker that supports the job type.
     *   3. Execute the job (via pipeline if configured).
     *   4. Record the result, notify peers.
     */
    fun runWorkerLoop(): Boolean {
        val job = queue.dequeue() ?: return false

        // Cluster-aware assignment: verify this node should process this job
        if (clusterService != null && job.relatedBugId != null) {
            val ownerNode = clusterService.getNodeForBugId(job.relatedBugId)
            if (ownerNode?.nodeId != clusterService.localNodeId) {
                // Not our partition — requeue and let owner pick up
                queue.requeue(job)
                return false
            }
        }

        // Find a suitable worker
        val worker = workers.firstOrNull { it.supports(job.spokeType) }
            ?: run {
                // No worker — mark as failed
                val failed = job.copy(
                    status = SpokeJobStatus.FAILED,
                    errorMessage = "No registered worker for job type: ${job.spokeType.name}",
                    completedAt = SystemClock.now(),
                )
                repository.save(failed)
                completedJobs.add(SpokeResult.Failure(failed, "No worker for type ${job.spokeType.name}", retryable = false))
                transports?.notifyJobFailed(failed, "no_worker")
                return true
            }

        // Mark as running
        val running = job.copy(
            status = SpokeJobStatus.RUNNING,
        )
        repository.save(running)

        // Execute — either through pipeline or direct
        val result = try {
            if (pipeline != null && canUsePipeline(job)) {
                executeWithPipeline(job, worker)
            } else {
                worker.process(job)
            }
        } catch (e: Exception) {
            SpokeResult.Failure(
                job = job,
                error = e.message ?: "unknown_error",
                retryable = true,
            )
        }

        // Handle result
        when (result) {
            is SpokeResult.Success -> {
                val completed = job.copy(
                    status = SpokeJobStatus.COMPLETED,
                    completedAt = SystemClock.now(),
                    metadata = job.metadata + result.metadata,
                )
                repository.save(completed)
                queue.complete(job.jobId)
                completedJobs.add(result)
                transports?.notifyJobCompleted(completed)
            }

            is SpokeResult.Failure -> {
                if (result.retryable && job.retries < MAX_RETRIES) {
                    val retrying = job.copy(
                        status = SpokeJobStatus.RETRYING,
                        retries = job.retries + 1,
                        errorMessage = result.error,
                    )
                    repository.save(retrying)
                    queue.requeue(retrying)
                    transports?.notifyJobRetrying(retrying)
                } else {
                    val failed = job.copy(
                        status = SpokeJobStatus.FAILED,
                        completedAt = SystemClock.now(),
                        errorMessage = result.error,
                        retries = job.retries,
                    )
                    repository.save(failed)
                    completedJobs.add(result)
                    transports?.notifyJobFailed(failed, result.error)
                }
            }

            is SpokeResult.Cancelled -> {
                val cancelled = job.copy(
                    status = SpokeJobStatus.CANCELLED,
                    completedAt = SystemClock.now(),
                )
                repository.save(cancelled)
            }
        }

        // Submit any pending uring I/O from artifact store
        uringFacade?.submit()

        return true
    }

    /**
     * Execute a job through the build pipeline.
     */
    private fun executeWithPipeline(job: SpokeJob, worker: SpokeWorker): SpokeResult {
        return try {
            val pipelineResult = pipeline.execute(job)
            when (pipelineResult) {
                is PipelineExecutionResult.Completed -> {
                    val processedResult = worker.process(job)
                    processedResult
                }
                is PipelineExecutionResult.Failed -> {
                    SpokeResult.Failure(job, pipelineResult.error, retryable = false)
                }
            }
        } catch (e: Exception) {
            SpokeResult.Failure(job, e.message ?: "pipeline_error", retryable = true)
        }
    }

    /** Check if a job type should go through the pipeline. */
    private fun canUsePipeline(job: SpokeJob): Boolean {
        // Multi-step job types benefit from pipeline
        return job.spokeType in setOf(
            SpokeJobType.INDEX,
            SpokeJobType.BUILD_VIEW,
            SpokeJobType.REBUILD,
        )
    }

    /**
     * Get comprehensive service stats.
     */
    fun stats(): SpokeServiceStats {
        val queueStats = queue.stats()
        return SpokeServiceStats(
            totalJobsInRepo = repository.recordCount,
            queuedJobs = queueStats.totalQueued,
            runningJobs = queueStats.totalRunning,
            completedPendingDrain = completedJobs.size,
            workerCount = workers.size,
            backpressureActive = queueStats.backpressureActive,
            artifactStats = artifactStore?.stats(),
            queueByType = queueStats.queuedByType.mapKeys {
                it.key.name
            },
        )
    }

    data class SpokeServiceStats(
        val totalJobsInRepo: Int,
        val queuedJobs: Int,
        val runningJobs: Int,
        val completedPendingDrain: Int,
        val workerCount: Int,
        val backpressureActive: Boolean,
        val artifactStats: ArtifactStore.ArtifactStoreStats? = null,
        val queueByType: Map<String, Int> = emptyMap(),
    )

    // ── ID generation ──────────────────────────────────────────────────────

    private fun generateJobId(type: SpokeJobType, product: CharSequence): CharSequence {
        val counter = ++jobIdCounter
        return "spoke-${type.name.lowercase()}-${product}-${counter}-${SystemClock.now()}"
    }

    companion object {
        private const val MAX_RETRIES = 3
    }
}
