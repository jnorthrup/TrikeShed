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
    val casStoreFactory: CasStoreFactory = CasStoreFactory { CasStore.inMemory() },
    val walFactory: JobLogFactory = JobLogFactory { JobLog.inMemory() },
    val indexFactory: JobIndexFactory = JobIndexFactory { JobIndex() },
    val reteFactory: ReteNetworkFactory = ReteNetworkFactory { borg.trikeshed.dag.ReteNetwork() },
    val projectionFactory: ProjectionFactory = ProjectionFactory { JobProjectionEngine() },
    val checkpointFactory: CheckpointFactory = CheckpointFactory { Checkpoint() },
)

/** Minimal component types assembled by the factory. */
open class JobIndex {
    private val byJob = mutableMapOf<JobId, JobSnapshot>()
    open fun put(snap: JobSnapshot) { byJob[snap.jobId] = snap }
    operator fun get(jobId: JobId): JobSnapshot? = byJob[jobId]
    val size: Int get() = byJob.size
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
// Use fun interface with SAM named invoke() so the classes remain callable
// as factory() (compatible with the () -> X field types), while avoiding the
// JS prohibition on classes implementing kotlin.FunctionN directly.

fun interface CasStoreFactory { operator fun invoke(): CasStore }
fun interface JobLogFactory { operator fun invoke(): JobLog }
fun interface JobIndexFactory { operator fun invoke(): JobIndex }
fun interface ReteNetworkFactory { operator fun invoke(): borg.trikeshed.dag.ReteNetwork }
fun interface ProjectionFactory { operator fun invoke(): JobProjectionEngine }
fun interface CheckpointFactory { operator fun invoke(): Checkpoint }

class FailingCasStoreFactory : CasStoreFactory {
    override fun invoke(): CasStore = throw RuntimeException("injected CAS failure")
}

class FailingJobLogFactory(val stage: String = "wal") : JobLogFactory {
    override fun invoke(): JobLog = throw RuntimeException("injected $stage failure")
}

class FailingJobIndexFactory(val stage: String = "index") : JobIndexFactory {
    override fun invoke(): JobIndex = throw RuntimeException("injected $stage failure")
}

class FailingReteNetworkFactory(val stage: String = "rete") : ReteNetworkFactory {
    override fun invoke(): borg.trikeshed.dag.ReteNetwork = throw RuntimeException("injected $stage failure")
}

class FailingProjectionFactory(val stage: String = "projection") : ProjectionFactory {
    override fun invoke(): JobProjectionEngine = throw RuntimeException("injected $stage failure")
}

class FailingCheckpointFactory(val stage: String = "checkpoint") : CheckpointFactory {
    override fun invoke(): Checkpoint = throw RuntimeException("injected $stage failure")
}