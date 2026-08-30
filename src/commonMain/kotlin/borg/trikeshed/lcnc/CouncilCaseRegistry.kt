package borg.trikeshed.lcnc

import borg.trikeshed.job.ConfixFacetPlan
import borg.trikeshed.job.JobEvent
import borg.trikeshed.job.JobNexusBindings
import borg.trikeshed.job.JobNexusFactory
import borg.trikeshed.job.JobNexusSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CouncilCaseRegistry — the per-case job nexus for council rulings.
 *
 * The legacy tribunal boots ONE [TribunalInstance] singleton whose lanes are
 * the preset's kanban lanes; a second case advanced through it would collide
 * with the first (the singleton regression). The council instead opens a
 * lazy per-case nexus: each case gets its own [borg.trikeshed.job.JobSupervisorElement]
 * (schema → CAS → WAL → reducer) wrapped in a [TribunalInstance] — reusing
 * the schema gate and awaitCommit verbatim, no TribunalInstance edits — under
 * a per-case child scope so eviction can cancel the whole pipeline.
 *
 * Lifecycle per case: seeded on the first record call — `submit`, then the
 * nexus rete's own auto-start (JobDependencyProduction starts dependency-free
 * submitted jobs; an explicit start is issued only when no auto-start lands) —
 * then exactly ONE terminal advance: `complete` (ruling) or `fail`
 * (mistrial). Idempotency is two-layer: the registry caches the snapshot cid
 * per idempotency key (`council-<caseId>-<verdictCid>` /
 * `council-<caseId>-mistrial-<reason.hashCode()>`) and returns it WITHOUT
 * re-advancing, and the reducer's own idempotencyKey dedupe backstops it.
 * A per-case [Mutex] serializes concurrent recorders, so two parallel
 * recordings of the same verdict land exactly one advance.
 *
 * Close-after-commit: a terminal entry is closed for further commits but
 * RETAINED for idempotency reads; when live entries exceed [cap], the oldest
 * terminal entries are evicted by cancelling their child scope (the leak the
 * boot singleton never had to face — the council convenes many cases).
 * An evicted case still answers [recordedCid] from cache.
 *
 * Every advance failure (schema rejection, stale revision, reducer reject)
 * surfaces as a thrown exception naming the case — loud, never swallowed.
 */
class CouncilCaseRegistry(
    private val scope: CoroutineScope,
    private val plan: ConfixFacetPlan,
    private val cap: Int = 64,
) {

    private inner class CaseEntry(val caseId: String) {
        val jobId = "case/$caseId"
        val childJob: Job = SupervisorJob(scope.coroutineContext[Job])
        val childScope = CoroutineScope(scope.coroutineContext + childJob)
        val instance = TribunalInstance(
            JobNexusFactory.open(
                spec = JobNexusSpec(),
                bindings = JobNexusBindings(parentScope = childScope),
            ),
            plan,
            listOf(jobId),
        )
        val mutex = Mutex()

        /** idempotency key → the snapshot cid that record returned. */
        val recordedByKey = mutableMapOf<String, String>()
        var lastRecordedCid: String? = null
        var seeded = false
        var terminal = false
        var terminalAt = 0L
        var evicted = false
    }

    private val entriesLock = Mutex()
    private val entries = LinkedHashMap<String, CaseEntry>()
    private var terminalClock = 0L

    private companion object {
        /** Bound on waiting for the nexus rete's auto-start commit (ms). */
        const val AUTO_START_WAIT_MS = 2_000
    }

    /**
     * Record the case's ruling: advance its job to `closed` and return the
     * committed snapshot cid. Idempotent per (caseId, verdictCid) — a repeat
     * returns the cached cid without advancing the job again.
     */
    suspend fun recordRuling(caseId: String, verdictCid: String, transcriptCid: String): String =
        record(
            caseId = caseId,
            idempotencyKey = "council-$caseId-$verdictCid",
            operation = "complete",
            reason = "",
            fallbackCid = verdictCid,
        )

    /**
     * Record a mistrial: advance the case's job through the `fail` operation
     * (lifecycle `failed`) with [reason] on the record. Idempotent per
     * (caseId, reason).
     */
    suspend fun recordMistrial(caseId: String, reason: String): String =
        record(
            caseId = caseId,
            idempotencyKey = "council-$caseId-mistrial-${reason.hashCode()}",
            operation = "fail",
            reason = reason,
            fallbackCid = null,
        )

    /** Entries whose nexus is still alive (not evicted). */
    fun liveCases(): Int = entries.values.count { !it.evicted }

    /** The snapshot cid a terminal record returned — answers from cache even after eviction. */
    fun recordedCid(caseId: String): String? = entries[caseId]?.lastRecordedCid

    /** Tracked lifecycle of the case's job, when the case is known. */
    fun lifecycle(caseId: String): String? = entries[caseId]?.let { it.instance.lifecycle(it.jobId) }

    /** Committed revision of the case's job, when the case is known. */
    fun revision(caseId: String): Long? = entries[caseId]?.let { it.instance.revision(it.jobId) }

    /** Cancel every live case's nexus and child scope (shutdown / test teardown). */
    fun close() {
        for (entry in entries.values) evict(entry)
    }

    private suspend fun record(
        caseId: String,
        idempotencyKey: String,
        operation: String,
        reason: String,
        fallbackCid: String?,
    ): String {
        val entry = entriesLock.withLock { entries.getOrPut(caseId) { CaseEntry(caseId) } }
        val cid = entry.mutex.withLock {
            // Idempotency first: a repeat of a recorded key answers from cache
            // WITHOUT re-advancing — even on an evicted (cancelled) entry.
            entry.recordedByKey[idempotencyKey]?.let { return@withLock it }
            if (entry.terminal) throw IllegalStateException(
                "council case '$caseId' is closed for commits " +
                    "(already terminal: ${entry.instance.lifecycle(entry.jobId)}); " +
                    "new record '$idempotencyKey' refused",
            )

            // Lazy seed on the first record: submit → start (root at revision 1,
            // active at revision 2) through the schema-gated advance.
            if (!entry.seeded) {
                seed(entry)
                entry.seeded = true
            }

            drainCommitted(entry)
            advanceLoud(entry, operation, idempotencyKey, entry.instance.revision(entry.jobId) ?: 1L, reason)
            val snapshotCid = entry.instance.snapshotCid(entry.jobId) ?: fallbackCid
                ?: throw IllegalStateException("council case '$caseId': no snapshot cid after '$operation' commit")
            entry.recordedByKey[idempotencyKey] = snapshotCid
            entry.lastRecordedCid = snapshotCid
            entry.terminal = true
            entry.terminalAt = ++terminalClock
            snapshotCid
        }
        evictOverCap()
        return cid
    }

    /**
     * Seed the case's job to `active`. The nexus's rete registers
     * [borg.trikeshed.dag.JobDependencyProduction] by default, which
     * AUTO-STARTS a dependency-free submitted job by injecting its own
     * `Start` command — racing it with an explicit start poisons the
     * committed-event stream with the loser's rejection. So: submit, then
     * WAIT for the auto-start commit (bounded, loud on timeout), falling
     * back to an explicit start only if no auto-start ever lands.
     */
    private suspend fun seed(entry: CaseEntry) {
        advanceLoud(entry, "submit", "council-case-${entry.caseId}", 0L)
        var waitedMs = 0
        while (entry.instance.lifecycle(entry.jobId) != "active" && waitedMs < AUTO_START_WAIT_MS) {
            delay(1)
            waitedMs++
        }
        if (entry.instance.lifecycle(entry.jobId) != "active") {
            // No auto-starter in this nexus build — start explicitly.
            drainCommitted(entry)
            advanceLoud(entry, "start", "council-case-${entry.caseId}-start", entry.instance.revision(entry.jobId) ?: 1L)
        }
    }

    /**
     * Discard committed events nobody awaited (the rete auto-start's accept,
     * a raced command's rejection) so the NEXT advance's awaited commit is
     * attributed to its own command — the nexus assumes one command in
     * flight, and the auto-starter breaks that assumption.
     */
    private fun drainCommitted(entry: CaseEntry) {
        while (entry.instance.nexus.committed.tryReceive().isSuccess) {
            // drained
        }
    }

    /** Advance and THROW (with the case named) on any rejection or failure. */
    private suspend fun advanceLoud(
        entry: CaseEntry,
        operation: String,
        idempotencyKey: String,
        expectedRevision: Long,
        reason: String = "",
    ) {
        val event = try {
            entry.instance.advance(entry.jobId, operation, idempotencyKey, expectedRevision, reason)
        } catch (t: Throwable) {
            throw IllegalStateException(
                "council case '${entry.caseId}': '$operation' advance failed: ${t.message}", t,
            )
        }
        if (event is JobEvent.Rejected) throw IllegalStateException(
            "council case '${entry.caseId}': '$operation' advance rejected: ${event.reason}",
        )
    }

    /** Close-after-commit: evict oldest TERMINAL entries while live count exceeds the cap. */
    private suspend fun evictOverCap() {
        entriesLock.withLock {
            while (entries.values.count { !it.evicted } > cap) {
                val victim = entries.values
                    .filter { it.terminal && !it.evicted }
                    .minByOrNull { it.terminalAt }
                    ?: break // nothing terminal to evict — live non-terminal cases stay
                evict(victim)
            }
        }
    }

    private fun evict(entry: CaseEntry) {
        if (entry.evicted) return
        entry.instance.nexus.cancel()
        entry.childJob.cancel()
        entry.evicted = true
    }
}
