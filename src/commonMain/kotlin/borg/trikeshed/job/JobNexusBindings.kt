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
open class JobIndex {
    private val byJob = mutableMapOf<JobId, JobSnapshot>()
    open fun put(snap: JobSnapshot) { byJob[snap.jobId] = snap }
    operator fun get(jobId: JobId): JobSnapshot? = byJob[jobId]
    val size: Int get() = byJob.size
}

open class ReteNetwork {
    var cycleBudget: Int = 1_000
    val rules: MutableList<String> = mutableListOf()
    fun addRule(rule: String) { rules.add(rule) }
}

open class JobProjectionEngine {
    val cards: MutableMap<JobId, JobProjection.KanbanCard> = mutableMapOf()
    open fun project(snapshot: JobSnapshot) {
        cards[snapshot.jobId] = JobProjection.projectToCard(snapshot)
    }
}

open class Checkpoint {
    var checkpointEvery: Int = 0
}

// ── Failing factory stubs for rollback tests ──────────────────────────────────

class FailingCasStoreFactory : () -> CasStore {
    override fun invoke(): CasStore = throw RuntimeException("injected CAS failure")
}

class FailingJobLogFactory(val stage: String = "wal") : () -> JobLog {
    override fun invoke(): JobLog = throw RuntimeException("injected $stage failure")
}

class FailingJobIndexFactory(val stage: String = "index") : () -> JobIndex {
    override fun invoke(): JobIndex = throw RuntimeException("injected $stage failure")
}

class FailingReteNetworkFactory(val stage: String = "rete") : () -> ReteNetwork {
    override fun invoke(): ReteNetwork = throw RuntimeException("injected $stage failure")
}

class FailingProjectionFactory(val stage: String = "projection") : () -> JobProjectionEngine {
    override fun invoke(): JobProjectionEngine = throw RuntimeException("injected $stage failure")
}

class FailingCheckpointFactory(val stage: String = "checkpoint") : () -> Checkpoint {
    override fun invoke(): Checkpoint = throw RuntimeException("injected $stage failure")
}