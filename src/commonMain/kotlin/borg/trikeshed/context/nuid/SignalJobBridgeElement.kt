package borg.trikeshed.context.nuid

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableJob
import borg.trikeshed.context.Drain
import borg.trikeshed.job.JobCommand
import borg.trikeshed.job.JobId
import borg.trikeshed.job.JobSupervisorElement
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.reactor.KanbanEvent
import borg.trikeshed.userspace.reactor.KanbanFSM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SignalJobBridgeElement — joins the two dispatch lanes.
 *
 * The NUID lane answers *who*: a [Workgroup] claims a [Nuid] by capability ×
 * subnet. The JobCommand lane answers *what*: a [JobCommand] enters the
 * durability pipeline (schema → CAS → WAL → barrier → reducer → index) and
 * comes out the other side as a committed `JobEvent`. Until now the two lanes
 * never met — a reduced signal facet landed on the kanban event flow as
 * vocabulary and stopped there.
 *
 * This element is the seam. It collects [KanbanFSM.kanbanEvents], keeps only
 * [KanbanEvent.SignalFacetReduced], and commits each one as a
 * [JobCommand.Submit] through [JobSupervisorElement.submit].
 *
 * ### Idempotency key
 *
 * The natural key is the event's `sourceSignalId` — the identity of the
 * *signal*. It is not, however, the identity of a *reduction occurrence*: the
 * only production emitter ([borg.trikeshed.context.lcnc.LcncFanoutElement])
 * builds `sourceSignalId` from the facet mark plus two hard-coded spine marks,
 * so every reduction of a given facet carries the identical string. Keyed on
 * `sourceSignalId` alone the bridge would commit exactly one job per facet for
 * the lifetime of the process and silently drop the rest.
 *
 * The default key is therefore the occurrence — `sourceSignalId` qualified by
 * the reduction's timestamp and reduced value ([signalOccurrenceKey]). A
 * replayed event (the flow has a 64-deep replay buffer) reproduces the same
 * key and is suppressed, which is what idempotency is for; two genuinely
 * distinct reductions get distinct keys and both commit. Callers that own a
 * unique `sourceSignalId` can restore the strict form by passing
 * `idempotencyKeyOf = { it.sourceSignalId }`.
 *
 * ### Provider neutrality
 *
 * The bridge reads only the facet key, the reduced value, the source signal id
 * and the timestamp. It never inspects, branches on, or records which provider
 * produced the reduction.
 *
 * ### Lifecycle
 *
 * [bridge] accepts until [admission] completes at [drain] or [close]; [close]
 * cancels [supervisor].
 *
 * A collector launched on [supervisor] (as [wireSignalFacetsToJobs] does) is
 * cancelled by [close]. It is *not* stopped by [drain]: `drain` completes the
 * supervisor and joins its children, and a `SharedFlow` collect never
 * completes on its own — shut a wired bridge down with [close].
 */
class SignalJobBridgeElement(
    private val jobs: JobSupervisorElement,
    parentJob: Job? = null,
    private val events: SharedFlow<KanbanEvent> = KanbanFSM.kanbanEvents,
    private val jobIdOf: (KanbanEvent.SignalFacetReduced) -> JobId = { signalFacetJobId(it) },
    private val idempotencyKeyOf: (KanbanEvent.SignalFacetReduced) -> String = { signalOccurrenceKey(it) },
) : CoroutineContext.Element, Drain {

    companion object Key : CoroutineContext.Key<SignalJobBridgeElement>

    override val key: CoroutineContext.Key<SignalJobBridgeElement> = Key
    val supervisor: CompletableJob = SupervisorJob(parentJob)
    /** Completes at [drain] or [close]; [bridge] refuses after it. */
    val admission: CompletableJob = Job()

    /** The flow [run] attaches to. Defaults to [KanbanFSM.kanbanEvents]. */
    val eventSource: SharedFlow<KanbanEvent> get() = events

    /** Idempotency ledger — the set of occurrence keys already handed to the nexus. */
    private val ledgerMutex: Mutex = Mutex()
    private val handedOffKeys: MutableSet<String> = mutableSetOf()
    private var submitCount: Int = 0

    /**
     * Number of [JobCommand.Submit]s this bridge has handed to the nexus.
     *
     * A handoff is not a commit: the nexus reducer may still reject the command
     * (duplicate idempotency key surviving in a WAL replay, stale revision).
     * Read `JobSupervisorElement.committed` for the authoritative outcome.
     */
    val submitted: Int get() = submitCount

    /**
     * Hand one reduced signal facet to the job nexus as a [JobCommand.Submit].
     *
     * Returns the [JobId] that was submitted, or null when the bridge is not
     * accepting (after drain or close) or the occurrence key was already handed
     * off. A non-null return means the command reached the nexus command
     * channel — the committed/rejected verdict arrives separately on
     * `JobSupervisorElement.committed`.
     *
     * Exposed directly so callers that already hold an event — tests, replay
     * tools, the daemon — can commit without going through the flow.
     */
    suspend fun bridge(event: KanbanEvent.SignalFacetReduced): JobId? {
        if (!admission.isActive) return null
        val idempotencyKey = idempotencyKeyOf(event)
        val fresh = ledgerMutex.withLock { handedOffKeys.add(idempotencyKey) }
        if (!fresh) return null
        val jobId = jobIdOf(event)
        try {
            jobs.submit(JobCommand.Submit(jobId = jobId, idempotencyKey = idempotencyKey))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The ledger claims a key before the handoff so concurrent bridges
            // cannot both submit it; a failed handoff must give the key back.
            ledgerMutex.withLock { handedOffKeys.remove(idempotencyKey) }
            throw e
        }
        ledgerMutex.withLock { submitCount++ }
        return jobId
    }

    /**
     * Attach to the event flow and bridge forever. Suspends until cancelled —
     * launch it, don't await it.
     *
     * A failed handoff (the nexus drained or closed underneath us) must not
     * tear down the collector's scope, so per-event failures are swallowed
     * here; cancellation still propagates.
     */
    suspend fun run() {
        events.collect { event ->
            if (event is KanbanEvent.SignalFacetReduced) {
                try {
                    bridge(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // The nexus stopped accepting. Stay attached — the flow is
                    // process-wide and a later supervisor may take over — but do
                    // not cancel the scope that launched this collector.
                }
            }
        }
    }

    override suspend fun drain() {
        admission.complete()
        supervisor.complete()
        supervisor.join()
        close()
    }

    suspend fun close() {
        admission.complete()
        supervisor.cancelAndJoin()
    }
}

/** Job id prefix for jobs minted from a reduced signal facet. */
const val SIGNAL_FACET_JOB_PREFIX: String = "signal-facet"

/**
 * Identity of one reduction *occurrence*: the source signal, the instant it
 * reduced, and the value it reduced to. Deterministic, so a replayed event
 * reproduces the key of its first pass and dedupes against it.
 */
fun signalOccurrenceKey(event: KanbanEvent.SignalFacetReduced): String =
    "${event.sourceSignalId}@${event.timestampMs}#${event.reducedValue}"

/**
 * Default job identity for a reduced signal facet:
 * `signal-facet:<facetKey>:<occurrenceKey>`.
 */
fun signalFacetJobId(event: KanbanEvent.SignalFacetReduced): JobId =
    JobId.of("$SIGNAL_FACET_JOB_PREFIX:${event.facetKey}:${signalOccurrenceKey(event)}")

/**
 * Wire both lanes together: register the model-facade [Workgroup] on [fanout]
 * (NUID lane — *who*) and launch a [SignalJobBridgeElement] against [jobs]
 * (JobCommand lane — *what*).
 *
 * Returns `workgroup j (bridge j collectorJob)` so the caller can inspect the
 * registration, count handoffs, and cancel the collector on shutdown.
 *
 * The collector is launched on the bridge's own supervisor, so
 * `bridge.close()` stops it; the job is returned as well for callers that want
 * to detach the collector without closing the element.
 */
suspend fun wireSignalFacetsToJobs(
    scope: CoroutineScope,
    jobs: JobSupervisorElement,
    fanout: NuidFanoutElement,
    workgroup: Workgroup = modelWorkgroup(),
    events: SharedFlow<KanbanEvent> = KanbanFSM.kanbanEvents,
): Join<Workgroup, Join<SignalJobBridgeElement, Job>> {
    fanout.open()
    fanout.registerWorkgroup(workgroup)
    val bridge = SignalJobBridgeElement(
        jobs = jobs,
        parentJob = scope.coroutineContext[Job],
        events = events,
    )
    val collector = scope.launch(bridge.supervisor) { bridge.run() }
    return workgroup j (bridge j collector)
}
