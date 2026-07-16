package borg.trikeshed.job

import kotlinx.coroutines.CoroutineScope

/**
 * JobNexusBindings — effectful dependencies for factory composition.
 *
 * The bindings carry the parent scope and injectable component factories.
 * The factory is the single effectful composition boundary.
 */
class JobNexusBindings(
    val parentScope: CoroutineScope?,
    val fileOps: Any? = Any(), // non-null default = file capability available
    val linuxStorageAvailable: Boolean = true,
    val componentFactories: JobNexusComponentFactories = JobNexusComponentFactories(),
) {
    /** Trace of component open/close events for rollback verification. */
    val closeTrace: MutableList<CloseTraceEntry> = mutableListOf()
}

data class CloseTraceEntry(
    val component: String,
    val order: Int,
    val closed: Boolean,
)

/**
 * Injectable component factories — each can be replaced with a failing stub
 * to test rollback at every assembly stage.
 */
class JobNexusComponentFactories(
    val scopeFactory: () -> CoroutineScope = { error("default scopeFactory not set") },
    val casStoreFactory: () -> CasStore = { CasStore.inMemory() },
    val walFactory: () -> JobLog = { JobLog.inMemory() },
    val indexFactory: () -> JobIndex = { JobIndex() },
    val reteFactory: () -> ReteNetwork = { ReteNetwork() },
    val projectionFactory: () -> JobProjectionEngine = { JobProjectionEngine() },
    val checkpointFactory: () -> Checkpoint = { Checkpoint() },
)

/** Minimal component types assembled by the factory. */
open class JobIndex : AutoCloseable {
    private val byJob = mutableMapOf<JobId, JobSnapshot>()
    open fun put(snap: JobSnapshot) { byJob[snap.jobId] = snap }
    operator fun get(jobId: JobId): JobSnapshot? = byJob[jobId]
    val size: Int get() = byJob.size
    override fun close() {}
}

open class ReteNetwork : AutoCloseable {
    var cycleBudget: Int = 1_000
    val rules: MutableList<String> = mutableListOf()
    fun addRule(rule: String) { rules.add(rule) }
    override fun close() {}
}

open class JobProjectionEngine : AutoCloseable {
    val cards: MutableMap<JobId, JobProjection.KanbanCard> = mutableMapOf()
    open fun project(snapshot: JobSnapshot) {
        cards[snapshot.jobId] = JobProjection.projectToCard(snapshot)
    }
    override fun close() {}
}

open class Checkpoint : AutoCloseable {
    var checkpointEvery: Int = 0
    override fun close() {}
}

// ── Failing factory stubs for rollback tests ──────────────────────────────────

fun FailingCasStoreFactory(): () -> CasStore =
    { throw RuntimeException("injected CAS failure") }

fun FailingJobLogFactory(stage: String = "wal"): () -> JobLog =
    { throw RuntimeException("injected $stage failure") }

fun FailingJobIndexFactory(stage: String = "index"): () -> JobIndex =
    { throw RuntimeException("injected $stage failure") }

fun FailingReteNetworkFactory(stage: String = "rete"): () -> ReteNetwork =
    { throw RuntimeException("injected $stage failure") }

fun FailingProjectionFactory(stage: String = "projection"): () -> JobProjectionEngine =
    { throw RuntimeException("injected $stage failure") }

fun FailingCheckpointFactory(stage: String = "checkpoint"): () -> Checkpoint =
    { throw RuntimeException("injected $stage failure") }