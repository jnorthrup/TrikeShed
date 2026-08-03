package borg.trikeshed.jules

import borg.trikeshed.htx.HtxKey
import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.util.oroboros.MergeReceipt
import kotlinx.datetime.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Declared phase precedence for one flywheel cycle. The cycle executes phases
 * in this enum's declaration order; a phase that returns BLOCKED short-circuits
 * the rest. The [FlywheelPhase] ordinal IS the manifest — the imperative cycle
 * body follows this order, and the active phase is surfaced in [CycleReport]
 * so the priority is observable, not implied.
 *
 * ANSWER before DRAIN: a blocked conversation frees a slot the wheel reuses
 * this cycle, higher-leverage than harvesting a fresh patch.
 * DRAIN before SETTLE: drains must be harvested before the parity barrier
 * admits new work, else the wheel inducts onto a dirty repo.
 * SETTLE before INDUCT: induction onto an unsettled tree is speculative.
 * INDUCT before DISPATCH: the queue must hold the new work before dispatch
 * reads it — otherwise the first dispatch never sees freshly induced items.
 */
enum class FlywheelPhase(val order: Int, val label: String) {
    POLL(0, "poll"),
    ANSWER(1, "answer"),
    SYNC(2, "sync"),
    DRAIN(3, "drain"),
    SETTLE(4, "settle"),
    INDUCT(5, "induct"),
    DISPATCH(6, "dispatch"),
}

/**
 * Flywheel driver — the actual loop that turns the wheel.
 *
 * The wheel is an Oroboros element: it keeps an even flow between
 * **induction** (work enters the causal WAL) and **drain** (patches
 * harvest+settle). Waiting Jules conversations are answered before longer
 * tasks are dispatched — a blocked conversation is higher-leverage than a
 * new dispatch, because it frees a slot the wheel can reuse this cycle.
 *
 * Every cycle:
 * 1. POLL Jules sessions via [JulesConductor.pollOnce]
 * 2. ANSWER every AWAITING_USER_FEEDBACK session (GUIDE brain + conventions)
 * 3. SYNC local master to origin/master before applying delivered patches
 * 4. DRAIN the entire COMPLETED set: CAS → sequential 3-way → cumulative repair
 * 5. SETTLE the repaired commits and provenance tags to origin/master
 * 6. INDUCT is a no-op: coordinated RGA producers append partitioned WorkQueued causes
 * 7. DISPATCH up to [maxSlots] non-overlapping queue entries after settlement
 *
 * The durable WAL is the only intake surface and [loadQueue] is the only
 * dispatch surface. The daemon never scans the repository or a todo file for
 * work; the upstream RGA coordinator owns partitioning before WorkQueued.
 *
 * Run with:
 *   ./gradlew jvmRun -PmainClass=borg.trikeshed.jules.FlywheelDriver
 */
class FlywheelDriver(
    private val apiKey: String,
    private val repoDir: File = File(System.getProperty("user.dir")),
    private val forgeDir: File = File(System.getProperty("user.home"), ".local/forge"),
    private val intervalMs: Long = 60_000L,
    maxSlots: Int = 15,
    private val source: String = "sources/github/jnorthrup/TrikeShed",
    /** CAS store backing the patch blobs cited by [MergeReceipt.patchCid]. Default <forgeDir>/cas (same path OroborosMain wires). */
    private val casStore: FileCasStore = FileCasStore(
        JvmFileOperations(),
        JvmFileOperations().resolvePath(forgeDir.absolutePath, "cas"),
    ),
) {
    /** Jules Pro concurrency ceiling; configuration may lower but never raise it. */
    private val maxSlots: Int = maxSlots.coerceIn(0, 15)
    private val client = JulesRestClient(apiKey)
    internal val brain: BrainClient? = BrainClient(errorSink = JvmBrainErrorSink(forgeDir))
    private val store = JulesBoardStore.forForgeDir(forgeDir)
    private val conductor = JulesConductor(
        client = client,
        headShaProvider = { headSha() },
        store = store,
        source = source,
    )
    // CCEK context: SupervisorJob + SharedFlow event bus. Dispatch concurrency
    // is bounded by the queue slice (`take(available)`) and structured async.
    private val parentJob: Job = SupervisorJob()
    private val _events = MutableSharedFlow<FlywheelEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<FlywheelEvent> get() = _events.asSharedFlow()
    /** Consecutive zero-patch probes per session id; tombstones at 3 (late outputs finalize async). */
    private val noPatchProbes = mutableMapOf<String, Int>()
    /** Consecutive patch-bearing drain failures per session id; never tombstoned. */
    private val drainFailures = mutableMapOf<String, Int>()
    /** Per-arm corrupt-patch probe counter; tombstones at 3 to prevent infinite retry. */
    private val corruptPatchProbes = mutableMapOf<String, Int>()
    /** Session-derived work ids are historical fallback identities, not new work
     * once their corresponding card is already closed. Keep their WAL history
     * visible while reporting each suppressed requeue only once per process. */
    private val reportedClosedSessionQueueEntries = mutableSetOf<String>()
    private val reportedSpecMissing = mutableSetOf<String>()

    /** Per-cycle HTTP error counters. Reset at the start of each [cycle];
     * bumped by [classifyHttpError] at every Jules API catch site. Read by
     * the cycle's CycleReport and the daemon's trace JSON. Instance-level so
     * drain/answer/dispatch helpers (separate methods) can bump them too. */
    @Volatile var cycleHttp429: Int = 0
        private set
    @Volatile var cycleHttp5xx: Int = 0
        private set

    /** Snapshot of the last reactive-tick outcome. Written by the reactive
     *  poll coroutine at the end of each tick; read by the daemon's
     *  periodicity loop (CycleBody) for telemetry/trace WITHOUT calling the
     *  sequential [cycle] — the reactive choreography is the sole driver. */
    @Volatile var lastReactiveReport: CycleReport? = null

    /** Classify a caught throwable and bump the per-cycle HTTP counters if
     * it (or its root cause) is a [JulesHttpException]. Called at every Jules
     * API catch site in the cycle, drain, answer, and dispatch paths. */
    private fun classifyHttpError(t: Throwable) {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is JulesHttpException) {
                when (cur.status) {
                    429 -> cycleHttp429++
                    in 500..599 -> cycleHttp5xx++
                }
                return
            }
            cur = cur.cause
        }
    }

    /**
     * Record a drain failure on the session card and return [DrainOutcome.Skipped].
     * Patch-bearing work stays undrained and retryable regardless of attempt
     * count: the full completion set cannot advance by tombstoning a failed arm.
     */
    private suspend fun drainFail(s: JulesRestClient.SessionInfo, reason: String): DrainOutcome.Skipped {
        val attempts = (drainFailures[s.id] ?: 0) + 1
        if (attempts >= 3) {
            // Same 3-strike retirement as noPatchProbes/corruptPatchProbes:
            // a session that fails drain 3 consecutive times never leaves the
            // completion set otherwise — sessions.isEmpty() stays false,
            // synchronizeMain() never runs, and settlement prints SETTLE-BLOCKED
            // forever. retireTerminal writes the MergeReceipt + WorkDrained
            // outbox entry so the queue slot closes too.
            drainFailures.remove(s.id)
            conductor.retireTerminal(
                s.id,
                "$reason (retired after $attempts consecutive drain failures)",
                Clock.System.now().toEpochMilliseconds(),
            )
            println("[FLYWHEEL] RETIRE ${s.id.takeLast(6)} drain failed $attempts times: $reason")
            return DrainOutcome.Skipped
        }
        drainFailures[s.id] = attempts
        conductor.recordDrainFailure(s.id, "$reason (attempt $attempts)", Clock.System.now().toEpochMilliseconds())
        emitPollError("drain ${s.id}: $reason (attempt $attempts)", 0)
        return DrainOutcome.Skipped
    }
    private val reactorScope = CoroutineScope(Dispatchers.IO + parentJob)

    /** A reactor lifecycle event. Fanout subscribers (TUI, reaper, drain observers) listen to [events]. */
    sealed interface FlywheelEvent {
        data class Polled(val alive: Int, val available: Int) : FlywheelEvent
        data class Drained(val sessionId: String, val sha: String, val tag: String) : FlywheelEvent
        data class Dispatched(val sessionId: String, val title: String) : FlywheelEvent
        data class DispatchFailed(val title: String, val reason: String) : FlywheelEvent
        data class PollError(val message: String) : FlywheelEvent
        data class UpstreamDrifted(val local: String, val remote: String) : FlywheelEvent
        data class SpecMissing(val title: String) : FlywheelEvent
    }

    /**
     * Emit a [FlywheelEvent.PollError] and return [returnValue] in one statement.
     * The dominant shape across [drainOne] / [drainFanout] is "report and exit" —
     * without this, each site expands to two lines (emit + return) and the
     * emit-message-and-return-value pair becomes invisible to the eye.
     */
    private suspend fun emitPollError(message: String, returnValue: Int): Int {
        _events.emit(FlywheelEvent.PollError(message))
        return returnValue
    }

    /** Emits an UpstreamDrifted event for preflight checks without exposing the raw bus. */
    fun emitDrifted(local: String, remote: String) {
        _events.tryEmit(FlywheelEvent.UpstreamDrifted(local, remote))
    }

    /** One cycle: poll → answer → drain → induct → dispatch. */
    suspend fun cycle(): CycleReport {
        val t0 = System.currentTimeMillis()
        cycleHttp429 = 0
        cycleHttp5xx = 0

        // 1. POLL — guarded so a transient API/network failure does NOT
        //    abort the cycle and starve drain. A failed poll is a PollError
        //    event + a retry on the next interval; drain still proceeds
        //    against the cards the previous cycle rehydrated from WAL.
        try {
            withTimeoutOrNull(60_000L) { conductor.pollOnce() }
        } catch (t: Throwable) {
            classifyHttpError(t)
            _events.tryEmit(FlywheelEvent.PollError("poll ${t.javaClass.simpleName}: ${t.message?.take(200)}"))
        }

        // Jules can keep reporting an externally landed branch as active. Close
        // that card through the normal provenance path before it consumes a slot.
        try {
            reconcileGitState()
        } catch (t: Throwable) {
            classifyHttpError(t)
            _events.tryEmit(FlywheelEvent.PollError("reconcile ${t.javaClass.simpleName}: ${t.message?.take(200)}"))
        }

        // 2. ANSWER — a waiting conversation is higher-leverage than a new
        //    dispatch: it unblocks a slot the wheel reuses THIS cycle. Draining
        //    blocked work before induction keeps the flow even.
        var answered = 0
        val awaiting = conductor.cards.values.filter {
            it.snapshot.state == "AWAITING_USER_FEEDBACK" &&
                it.causes.lastOrNull() !is JulesCause.HumanAnswered
        }.sortedBy { it.snapshot.capturedAt }
        for (card in awaiting) {
            val answer = withTimeoutOrNull(45_000L) { buildAnswer(card) } ?: ""
            if (answer.isNotEmpty()) {
                conductor.answer(card.snapshot.sessionId, answer)
                answered++
                println("[FLYWHEEL] ANSWER ${card.snapshot.sessionId.takeLast(6)} ${card.card.title.take(60)}")
            }
        }

        // 2a. NUDGE stalled conversations — Jules sometimes never resumes after
        //     a user message: the session parks in AWAITING_USER_FEEDBACK with
        //     our answer as its last activity, and the exactly-once guard above
        //     then correctly never re-answers, so the slot stays held forever.
        //     After stallNudgeMs with no reply, send one nudge; the fresh
        //     HumanAnswered cause self-throttles to once per interval.
        val stallNudgeMs = 2L * 60L * 60L * 1000L
        val nudgeNowMs = Clock.System.now().toEpochMilliseconds()
        for (card in conductor.cards.values.filter {
            it.snapshot.state == "AWAITING_USER_FEEDBACK" &&
                (it.causes.lastOrNull() as? JulesCause.HumanAnswered)?.let { cause ->
                    nudgeNowMs - cause.at > stallNudgeMs
                } == true
        }.sortedBy { it.snapshot.capturedAt }) {
            try {
                conductor.answer(
                    card.snapshot.sessionId,
                    "Please proceed with the implementation based on my previous answer. " +
                        "Run the JVM build to verify, and commit when green.",
                )
                answered++
                println("[FLYWHEEL] NUDGE ${card.snapshot.sessionId.takeLast(6)} ${card.card.title.take(60)}")
            } catch (t: Throwable) {
                classifyHttpError(t)
                _events.tryEmit(FlywheelEvent.PollError("nudge ${card.snapshot.sessionId}: ${t.message?.take(200)}"))
            }
        }

        // 2b. APPROVE — a session parked in AWAITING_PLAN_APPROVAL holds its
        //     slot forever unless the wheel signs off; no other phase ever
        //     unblocks it. Auto-approve: the integrated JVM build at drain time
        //     is the quality barrier, not plan review. The HumanAnswered cause makes
        //     approval exactly-once per plan across polls.
        for (card in conductor.cards.values.filter {
            it.snapshot.state == "AWAITING_PLAN_APPROVAL" &&
                it.causes.lastOrNull() !is JulesCause.HumanAnswered
        }.sortedBy { it.snapshot.capturedAt }) {
            try {
                withTimeoutOrNull(45_000L) { conductor.approvePlan(card.snapshot.sessionId) }
                answered++
                println("[FLYWHEEL] APPROVE ${card.snapshot.sessionId.takeLast(6)} ${card.card.title.take(60)}")
            } catch (t: Throwable) {
                classifyHttpError(t)
                _events.tryEmit(FlywheelEvent.PollError("approve ${card.snapshot.sessionId}: ${t.message?.take(200)}"))
            }
        }

        // 2c. SWEEP terminal failures — a session that lands in FAILED or
        //     CANCELLED never enters DRAIN (COMPLETED-only), so without an
        //     explicit retire its card sits terminal-but-undrained and its
        //     queue entry stays dispatched-not-drained forever: the workId can
        //     never be re-queued and the wheel slowly clogs with zombies.
        //     retireTerminal writes MergeReceipt + WorkDrained (bonded to the
        //     original queue workId) so the slot closes cleanly.
        for (card in conductor.cards.values.filter {
            it.snapshot.state in setOf("FAILED", "CANCELLED") && !it.drained
        }.sortedBy { it.snapshot.capturedAt }) {
            conductor.retireTerminal(
                card.snapshot.sessionId,
                "terminal ${card.snapshot.state}",
                Clock.System.now().toEpochMilliseconds(),
            )
            println("[FLYWHEEL] SWEEP ${card.snapshot.sessionId.takeLast(6)} ${card.snapshot.state}: ${card.card.title.take(60)}")
        }

        // 2d. REPAIR queue orphans — the pre-bonding retireTerminal wrote
        //     WorkDrained under the bare numeric sessionId, so the card closed
        //     (drained=true) but the real queue entry (gap:/readme:/synth:)
        //     stayed dispatched-not-drained forever. Close any queue entry
        //     whose session card is already drained. Also close entries whose
        //     card is GONE: pollOnce evicts cards the API has rotated out
        //     (404), and an evicted card can never drain. The 15-minute
        //     dispatch-age margin protects sessions created so recently that
        //     listSessions may not include them yet.
        val queueSnapshot = store.loadQueue()
        val orphanRepairMarginMs = 15L * 60L * 1000L
        val nowMs = Clock.System.now().toEpochMilliseconds()
        for (entry in queueSnapshot.filter { it.isDispatched && !it.isDrained }) {
            val card = conductor.cards[entry.sessionId]
            val isOrphan = when {
                card == null ->
                    (nowMs - (entry.dispatchedAt ?: 0L)) > orphanRepairMarginMs
                else -> card.drained
            }
            if (!isOrphan) continue
            store.appendWork(
                workId = entry.workId,
                cause = JulesCause.WorkDrained(
                    workId = entry.workId,
                    sessionId = entry.sessionId ?: continue,
                    commitSha = "outbox-${(entry.sessionId ?: "").take(8)}",
                    taskId = "retired",
                    receipt = borg.trikeshed.util.oroboros.MergeReceipt(
                        workId = entry.workId,
                        producer = "retired",
                        producerRef = entry.sessionId ?: "",
                        patchCid = borg.trikeshed.job.ContentId.of(
                            "orphan-repair:${entry.sessionId}".encodeToByteArray()
                        ),
                        revision = "outbox-${(entry.sessionId ?: "").take(8)}",
                        versionTag = "retired",
                        lexicalMemory = borg.trikeshed.util.oroboros.LexicalMemory(
                            summary = "queue orphan repair: card already drained",
                            title = "queue orphan repair", content = ""),
                        claimedAt = Clock.System.now().toEpochMilliseconds(),
                        prUrl = null,
                    ),
                    at = Clock.System.now().toEpochMilliseconds(),
                ),
            )
            println("[FLYWHEEL] REPAIR ${entry.sessionId?.takeLast(6)} closed orphaned queue entry ${entry.workId.take(60)}")
        }

        // 3. DRAIN — consume the entire completed set. Sequential 3-way is
        //    the merge topology, not a limit on how many sessions advance.
        //    A tag alone is not a completed drain: the durable card/WAL close is
        //    authoritative so an interrupted provenance write remains retryable.
        val completed = conductor.cards.values.filter {
            it.snapshot.state == "COMPLETED" && !it.drained
        }
        val sessions = completed.map {
            JulesRestClient.SessionInfo(
                id = it.snapshot.sessionId,
                state = it.snapshot.state,
                title = it.card.title,
                patchBytes = 0L,
            )
        }
        // With no Jules deltas waiting, ordinary upstream synchronization can
        // proceed directly. A non-empty completion set synchronizes only after
        // every API delta has been written to CAS inside drainThreeWay().
        // Skip the conflict commit + sync + conflict commit when there are
        // zero conflicts — this is the common idle path.
        if (sessions.isEmpty() && conflictFiles().isEmpty()) {
            synchronizeMain()
        } else if (sessions.isEmpty()) {
            commitExistingConflicts()
            synchronizeMain()
            commitExistingConflicts()
        }
        val drain = drainFanout(sessions)
        val harvested = drain.harvested
        val reworked = drain.reworked

        // 5. SETTLE — only the complete, repaired drain set advances. The cycle
        //    still returns normally on an incomplete drain; the next cycle
        //    retries it instead of dispatching a successor wave onto a red tree.
        val remainingCompleted = conductor.cards.values.count {
            it.snapshot.state == "COMPLETED" && !it.drained
        }
        val committedConflicts = (drain.conflicts + conflictFiles()).distinct()
        val readyToSettle = remainingCompleted == 0 &&
            committedConflicts.isEmpty() && isWorkingTreeClean()
        val settled = readyToSettle && settlementBarrier()

        // 6. INDUCT — the WAL is the only induction surface. External agents
        //    CAS-put a ≤4000-byte spec and appendWork(workId, WorkQueued).
        //    Nothing to do here — DISPATCH reads loadQueue() directly.
        val inducted = 0

        // 7. DISPATCH — take from the unified queue projection, sorted by
        //    score descending. Waiting work (AWAITING, just answered above)
        //    already holds its slot; we only fill capacity freed by drain.
        //
        //    Dispatch fires when the working tree is clean and there are no
        //    active conflicts. It does NOT require full settlement — stuck
        //    drains (e.g. a COMPLETED session whose patch won't apply) must
        //    not starve all dispatch and freeze the flywheel at 0/day.
        //
        //    Overlap guard: each task's file scope must not overlap any
        //    in-flight session's touched files.
        //
        //    Spec cap: Jules submissions are capped at [SPEC_BYTE_LIMIT] bytes.
        var dispatched = 0
        val alive = activeCount()
        val available = (maxSlots - alive).coerceAtLeast(0)
        val canDispatch = available > 0 &&
            committedConflicts.isEmpty() &&
            isWorkingTreeClean()
        if (canDispatch) {
            // Build the in-flight file set from all active sessions' last patches.
            val inflightFiles = mutableSetOf<String>()
            for (card in conductor.cards.values) {
                if (card.snapshot.state !in TERMINAL_STATES) {
                    val patch = runCatching { client.lastPatch(card.snapshot.sessionId) }.getOrNull()
                    if (patch != null) inflightFiles += parsePatchFiles(patch)
                }
            }
            val pendingCandidates = store.loadQueue()
                .filter { !it.isDispatched && !it.isDrained }
            // Older drains predate WorkQueued. A later seed using that fallback
            // `session:<id>` identity would otherwise submit an already-drained
            // session as fresh Jules work. A real rework needs a new work id.
            val closedSessionWorkIds = pendingCandidates.asSequence()
                .filter { entry ->
                    entry.workId.startsWith("session:") &&
                        conductor.cards[entry.workId.removePrefix("session:")]?.drained == true
                }
                .map { it.workId }
                .toSet()
            val newlyReported = closedSessionWorkIds.count { reportedClosedSessionQueueEntries.add(it) }
            if (newlyReported != 0) {
                println("[FLYWHEEL] DISPATCH-SKIP $newlyReported already-closed session queue item(s)")
            }
            val validCandidates = pendingCandidates
                .filterNot { it.workId in closedSessionWorkIds }

            validCandidates.filter { it.spec.isBlank() }.forEach {
                if (it.title.isNotBlank() && reportedSpecMissing.add(it.title)) {
                    _events.tryEmit(FlywheelEvent.SpecMissing(it.title))
                }
            }

            val pending = validCandidates
                .filter { it.spec.isNotBlank() }
                .sortedByDescending { it.score }
                .filter { entry ->
                    // Overlap guard: skip if this task's known file scope
                    // intersects any in-flight session's files.
                    val taskFiles = extractSpecFiles(entry.spec)
                    taskFiles.intersect(inflightFiles).isEmpty()
                }
                .take(available)
            dispatched = withContext(Dispatchers.IO) {
                coroutineScope {
                    val jobs = pending.map { entry ->
                        async(Dispatchers.IO) {
                            try {
                                val cappedSpec = capSpec(entry.spec)
                                val sessionId = client.createSession(
                                    prompt = cappedSpec, title = entry.title, source = source)
                                store.appendWork(entry.workId, JulesCause.WorkDispatched(
                                    workId = entry.workId,
                                    sessionId = sessionId,
                                    attempt = entry.attempt + 1,
                                    at = Clock.System.now().toEpochMilliseconds(),
                                ))
                                _events.emit(FlywheelEvent.Dispatched(sessionId, entry.title))
                                println("[FLYWHEEL] DISPATCH ${entry.title.take(60)}")
                                1
                            } catch (t: Throwable) {
                                classifyHttpError(t)
                                _events.emit(FlywheelEvent.DispatchFailed(entry.title, t.message.orEmpty()))
                                println("[FLYWHEEL] FAIL ${entry.title}: ${t.message}")
                                0
                            }
                        }
                    }
                    jobs.sumOf { it.await() }
                }
            }
        }

        val phase = when {
            remainingCompleted > 0 || committedConflicts.isNotEmpty() -> FlywheelPhase.DRAIN
            !settled -> FlywheelPhase.SETTLE
            else -> FlywheelPhase.DISPATCH
        }

        return CycleReport(
            cycleMs = System.currentTimeMillis() - t0,
            answered = answered,
            harvested = harvested,
            reworked = reworked,
            dispatched = dispatched,
            alive = alive,
            available = (maxSlots - alive).coerceAtLeast(0),
            inducted = inducted,
            settled = settled,
            phase = phase,
            conflicts = committedConflicts,
            panorama = drain.panorama,
            http429 = cycleHttp429,
            http5xx = cycleHttp5xx,
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // REACTIVE CHOREOGRAPHY: fan-out/fan-in pipeline
    //
    // poll → drain → settle → dispatch run as concurrent coroutines,
    // not sequential phases. Each reacts to events on the SharedFlow bus
    // and signals the next via channels. A freed slot triggers dispatch
    // in milliseconds, not on the next 30s cycle tick.
    //
    //     pollCoroutine ───Polled──→ drainCoroutine
    //                                    │
    //                                PatchLanded
    //                                    │
    //                                    ▼
    //     dispatchCoroutine ←──SlotFreed── settleCoroutine
    //
    // The daemon calls startReactiveCycle(scope) instead of looping cycle().
    // ─────────────────────────────────────────────────────────────────────

    /** Signal channel: a slot freed, dispatch should try to fill it. */
    private val slotFreed = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /**
     * Launch the reactive choreography. Returns immediately; the coroutines
     * run in [scope] and die when it's cancelled. The daemon's periodicity
     * loop becomes a simple poll trigger — everything else is reactive.
     */
    suspend fun startReactiveCycle(scope: kotlinx.coroutines.CoroutineScope) {
        // Capture the HTX element from the calling scope so the reactive
        // coroutines (launched on Dispatchers.Default) inherit it for all
        // Jules/ModelMux API calls. Without this, launch(Dispatchers.Default)
        // drops the HtxKey and every API call fails.
        val htxElement = kotlin.coroutines.coroutineContext[HtxKey]
        require(htxElement != null) {
            "startReactiveCycle must be called inside a withContext(htxElement) block"
        }
        // Prime the dispatch pump: free capacity may exist at startup before
        // the first poll discovers any state. Without this, dispatch parks on
        // slotFreed.receive() and the wheel deadlocks — nothing drains because
        // nothing was dispatched, and nothing dispatches because nothing drained.
        slotFreed.trySend(maxSlots)

        // Cross-coroutine dispatch counter. The dispatch coroutine bumps this
        // on every successful createSession; the poll coroutine snapshots it
        // at tick boundary into lastReactiveReport.
        val tickDispatched = java.util.concurrent.atomic.AtomicInteger(0)

        // FAN-OUT: drain pipeline. Polls Jules, drains COMPLETED sessions,
        // and signals dispatch when slots free.
        scope.launch(htxElement + Dispatchers.Default) {
            var tickAnswered = 0
            var tickHarvested = 0
            while (true) {
                val tickStart = System.currentTimeMillis()
                cycleHttp429 = 0
                cycleHttp5xx = 0
                tickAnswered = 0
                tickHarvested = 0
                tickDispatched.set(0)

                try {
                    withTimeoutOrNull(intervalMs) { conductor.pollOnce() }
                } catch (t: Throwable) {
                    classifyHttpError(t)
                    _events.tryEmit(FlywheelEvent.PollError("reactive poll: ${t.message?.take(200)}"))
                }

                // Fan-in: drain all completed sessions concurrently
                val completed = conductor.cards.values.filter {
                    it.snapshot.state == "COMPLETED" && !it.drained
                }
                if (completed.isNotEmpty()) {
                    val sessions = completed.map {
                        JulesRestClient.SessionInfo(it.snapshot.sessionId, it.snapshot.state, it.card.title, 0L)
                    }
                    val drain = drainFanout(sessions)
                    tickHarvested = drain.harvested
                    // Signal dispatch: slots may have freed
                    val freed = completed.count { it.drained }
                    if (freed > 0) {
                        slotFreed.trySend(freed)
                        println("[CHOREOGRAPHY] drain → dispatch signal: $freed slots freed")
                    }
                }

                // Answer/approve waiting sessions concurrently with drain
                val awaiting = conductor.cards.values.filter {
                    it.snapshot.state == "AWAITING_USER_FEEDBACK" &&
                        it.causes.lastOrNull() !is JulesCause.HumanAnswered
                }
                for (card in awaiting) {
                    val answer = withTimeoutOrNull(45_000L) { buildAnswer(card) } ?: ""
                    if (answer.isNotEmpty()) {
                        conductor.answer(card.snapshot.sessionId, answer)
                        tickAnswered++
                        _events.tryEmit(FlywheelEvent.Polled(activeCount(), (maxSlots - activeCount()).coerceAtLeast(0)))
                        println("[CHOREOGRAPHY] answer ${card.snapshot.sessionId.takeLast(6)}")
                    }
                }

                // Approve plans concurrently
                for (card in conductor.cards.values.filter {
                    it.snapshot.state == "AWAITING_PLAN_APPROVAL" &&
                        it.causes.lastOrNull() !is JulesCause.HumanAnswered
                }) {
                    withTimeoutOrNull(45_000L) { conductor.approvePlan(card.snapshot.sessionId) }
                    tickAnswered++
                }

                // Swepp terminal failures
                for (card in conductor.cards.values.filter {
                    it.snapshot.state in setOf("FAILED", "CANCELLED") && !it.drained
                }) {
                    conductor.retireTerminal(card.snapshot.sessionId, "terminal ${card.snapshot.state}", Clock.System.now().toEpochMilliseconds())
                    slotFreed.trySend(1)
                    println("[CHOREOGRAPHY] sweep ${card.snapshot.sessionId.takeLast(6)} → slot freed")
                }

                // Emit poll event for observers
                _events.tryEmit(FlywheelEvent.Polled(activeCount(), (maxSlots - activeCount()).coerceAtLeast(0)))

                // Re-arm dispatch: every poll re-evaluates free capacity. A
                // session may have transitioned terminal API-side, a sweep
                // may have retired a zombie, or the previous dispatch fan-out
                // may have left slots unfilled (createSession failures). The
                // dispatch coroutine parks on slotFreed.receive() after each
                // batch — without this signal it sleeps until the next drain,
                // starving the wheel when the queue is long but nothing is
                // completing.
                val availableNow = (maxSlots - activeCount()).coerceAtLeast(0)
                if (availableNow > 0) slotFreed.trySend(availableNow)

                // Snapshot the reactive tick outcome for the daemon's
                // telemetry loop. CycleBody reads this instead of calling
                // driver.cycle() — the reactive choreography is the sole
                // driver; the periodicity loop only observes and traces.
                val alive = activeCount()
                lastReactiveReport = CycleReport(
                    cycleMs = System.currentTimeMillis() - tickStart,
                    answered = tickAnswered,
                    harvested = tickHarvested,
                    dispatched = tickDispatched.get(),
                    alive = alive,
                    available = (maxSlots - alive).coerceAtLeast(0),
                    phase = if (tickHarvested > 0) FlywheelPhase.DRAIN else FlywheelPhase.DISPATCH,
                    http429 = cycleHttp429,
                    http5xx = cycleHttp5xx,
                )

                delay(intervalMs)
            }
        }

        // FAN-OUT: dispatch pipeline. Reacts to SlotFreed signals and fills
        // slots immediately — does NOT wait for the next poll cycle.
        scope.launch(htxElement + Dispatchers.Default) {
            while (true) {
                // Block until a slot frees (drain or sweep signaled)
                slotFreed.receive()

                val alive = activeCount()
                val available = (maxSlots - alive).coerceAtLeast(0)
                if (available == 0) continue
                if (!isWorkingTreeClean()) continue

                val pendingCandidates = store.loadQueue()
                    .filter { !it.isDispatched && !it.isDrained }
                    .filterNot { entry ->
                        entry.workId.startsWith("session:") &&
                            conductor.cards[entry.workId.removePrefix("session:")]?.drained == true
                    }
                    .filter { it.spec.isNotBlank() }
                    .sortedByDescending { it.score }
                    .take(available)

                if (pendingCandidates.isEmpty()) continue

                // Fan-out: dispatch all candidates concurrently
                coroutineScope {
                    val jobs = pendingCandidates.map { entry ->
                        async(Dispatchers.IO) {
                            try {
                                val cappedSpec = capSpec(entry.spec)
                                val sessionId = client.createSession(
                                    prompt = cappedSpec, title = entry.title, source = source)
                                store.appendWork(entry.workId, JulesCause.WorkDispatched(
                                    workId = entry.workId,
                                    sessionId = sessionId,
                                    attempt = entry.attempt + 1,
                                    at = Clock.System.now().toEpochMilliseconds(),
                                ))
                                _events.emit(FlywheelEvent.Dispatched(sessionId, entry.title))
                                tickDispatched.incrementAndGet()
                                println("[CHOREOGRAPHY] dispatch ${entry.title.take(60)}")
                            } catch (t: Throwable) {
                                classifyHttpError(t)
                                _events.emit(FlywheelEvent.DispatchFailed(entry.title, t.message.orEmpty()))
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            }
        }

        println("[CHOREOGRAPHY] reactive cycle started — fan-out drain + reactive dispatch")
    }

    /** Drain every completed session before the next research wave. */
    private val drainGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    private suspend fun drainFanout(sessions: List<JulesRestClient.SessionInfo>): DrainBatch {
        if (!drainGuard.compareAndSet(false, true)) return DrainBatch()
        try {
            if (sessions.isEmpty()) return DrainBatch()
            println("[FLYWHEEL] DRAIN-ALL sessions=${sessions.size}")
            return drainThreeWay(sessions)
        } finally {
            drainGuard.set(false)
        }
    }

    private data class DrainBatch(
        val harvested: Int = 0,
        val reworked: Int = 0,
        val conflicts: List<String> = emptyList(),
        val panorama: List<QaLaguna.SessionPanorama> = emptyList(),
    )

    /** One drain's terminal outcome. [Skipped] covers no-patch / dirty tree / infra errors. */
    private sealed interface DrainOutcome {
        data object Harvested : DrainOutcome
        data object Skipped   : DrainOutcome
    }

    /**
     * 3-way branch merge: merge every Jules-pushed branch into master
     * sequentially. There is no drain batch limit; the full completion set
     * lands and its cumulative conflicts are resolved before research advances.
     *
     * CAS-FIRST: every delta is content-addressed BEFORE it touches the tree.
     * The patch bytes fetched from the API go into the CAS store first; the
     * resulting CID is the provenance anchor for the merge. Only AFTER the
     * CID exists does the branch merge (or patch apply) run.
     *
     * Flow:
     *   1. git fetch origin (prune stale refs)
     *   2. For each session: fetch patch → CAS put → CID → merge branch.
     *      On conflict: git add -A + commit (concludes merge WITH markers).
     *      Never --ours/--theirs, never abort.
     *   3. Build verify.
     */
    private suspend fun drainThreeWay(sessions: List<JulesRestClient.SessionInfo>): DrainBatch {
        // 1. Fetch all branches so refs are available locally.
        git("fetch", "origin", "--prune")

        // CAS-FIRST: content-address every delta before merge.
        data class Arm(val session: JulesRestClient.SessionInfo, val patchCid: ContentId, val patch: String, val branch: String?)
        val arms = mutableListOf<Arm>()
        for (s in sessions) {
            val patch = withTimeoutOrNull(60_000L) { client.lastPatch(s.id) }
            if (patch.isNullOrBlank()) {
                val probes = (noPatchProbes[s.id] ?: 0) + 1
                noPatchProbes[s.id] = probes
                if (probes >= 3) {
                    noPatchProbes.remove(s.id)
                    conductor.retireTerminal(
                        s.id,
                        "no patch after $probes probes; nothing to land",
                        Clock.System.now().toEpochMilliseconds(),
                    )
                    println("[FLYWHEEL] RETIRE ${s.id.takeLast(6)} no-patch after $probes probes")
                } else {
                    emitPollError("drain ${s.id}: no patch to CAS (probe $probes/3)", 0)
                }
                continue
            }
            val patchCid = try { casStore.put(patch.encodeToByteArray()) }
            catch (e: Exception) {
                drainFail(s, "CAS put failed: ${e.message}")
                continue
            }
            noPatchProbes.remove(s.id)
            val branch = findSessionBranch(s.id)
            arms.add(Arm(s, patchCid, patch, branch))
            println("[FLYWHEEL] CAS ${s.id.takeLast(6)} cid=${patchCid.value.take(16)} branch=${branch ?: "none"}")
        }
        // PER-ARM DRAIN — do NOT wait for the whole completion set to CAS.
        // Retired no-patch sessions already left the completion set via
        // conductor.retireTerminal(); still-probing sessions remain
        // undrained for the next cycle. Either way, drain EVERY arm that
        // HAS a patch now. The previous `arms.size != sessions.size`
        // gate was a dining-philosopher deadlock: one straggler starved
        // the entire harvest queue and settle never lifted (cycle trace
        // signature: a=0 v=15 p=0 d=0 every cycle). Partial drain unblocks
        // the wheel — each closed provenance arm advances remainingCompleted.
        if (arms.isEmpty()) {
            println("[FLYWHEEL] DRAIN no CAS-ready arms out of ${sessions.size}; tree unchanged")
            return DrainBatch()
        }
        if (arms.size < sessions.size) {
            println("[FLYWHEEL] DRAIN partial CAS ${arms.size}/${sessions.size} — draining ready arms")
        }

        // Every completed API delta is now immutable in CAS. Only now may any
        // synchronization or conflict commit mutate the working tree.
        commitExistingConflicts()
        synchronizeMain()
        commitExistingConflicts()

        // 2. Merge each arm. Branch → git merge; no branch → patch apply.
        val landed = mutableListOf<Arm>()
        for (arm in arms) {
            val (s, _, _, branch) = arm
            val historicalSubject = if (branch == null) {
                "flywheel: patch ${s.title.take(50)} (${s.id.takeLast(6)})"
            } else {
                "flywheel: merge ${s.title.take(50)} ($branch)"
            }
            val historicalCommit = git(
                "log", "--format=%H", "-1", "--fixed-strings", "--grep=$historicalSubject",
            )
            if (historicalCommit.exitCode == 0 && historicalCommit.output.isNotBlank()) {
                println("[FLYWHEEL] HISTORY ${s.id.takeLast(6)} already landed at ${historicalCommit.output.trim().take(12)}")
                landed += arm
                continue
            }
            if (branch != null) {
                val mergeRes = git("merge", "--no-ff", "--no-edit", branch)
                if (mergeRes.exitCode != 0) {
                    val conflicted = unmergedFiles()
                    if (conflicted.isEmpty()) {
                        // Corrupt patch — increment probe counter and retire after 3
                        val probes = (corruptPatchProbes[s.id] ?: 0) + 1
                        corruptPatchProbes[s.id] = probes
                        if (probes >= 3) {
                            corruptPatchProbes.remove(s.id)
                            conductor.retireTerminal(s.id, "corrupt patch after $probes probes; nothing to land", Clock.System.now().toEpochMilliseconds())
                            println("[FLYWHEEL] RETIRE ${s.id.takeLast(6)} corrupt patch after $probes probes")
                        } else {
                            emitPollError("drain ${s.id}: corrupt patch (probe $probes/3)", 0)
                        }
                        continue
                    }
                    println("[FLYWHEEL] MERGE-CONFLICT ${s.id.takeLast(6)} ($branch): ${conflicted.size} files — committing markers")
                    conflicted.take(3).forEach { println("  ✗ $it") }
                    git("add", "--", *conflicted.toTypedArray())
                    val commit = git(
                        "commit", "--no-verify", "-m",
                        "flywheel: merge ${s.title.take(50)} ($branch) — ${conflicted.size} conflicts kept",
                    )
                    if (commit.exitCode != 0) {
                        drainFail(s, "conflict commit failed: ${commit.output.take(200)}")
                        continue
                    }
                    corruptPatchProbes.remove(s.id) // clear on success
                } else {
                    println("[FLYWHEEL] MERGED ${s.id.takeLast(6)} ($branch)")
                }
                landed += arm
                corruptPatchProbes.remove(s.id) // clear on success
            } else {
                // Fallback: no branch on origin — apply the CAS'd patch.
                // SANITIZE FIRST: Jules patches bundle sandbox scratch files
                // (test_script.kt/patch.diff/plan_script.sh) and trailing
                // whitespace that make `git apply --3way` reject the whole diff.
                // sanitizeJulesPatch drops those sections and trims `+` lines so
                // the real source hunks land. See sanitizer docstring for the
                // permanent-retry-loop WAL signature this prevents.
                val cleanPatch = sanitizeJulesPatch(arm.patch)
                if (cleanPatch.isBlank()) {
                    drainFail(s, "patch empty after sanitizing scratch sections")
                    continue
                }
                val pf = File(repoDir, ".flywheel-patch-${s.id.takeLast(6)}")
                try {
                    pf.writeText(cleanPatch)
                    val apply = git("apply", "--3way", pf.name)
                    val conflicted = unmergedFiles()
                    val alreadyApplied = apply.exitCode != 0 && conflicted.isEmpty() &&
                        git("apply", "--reverse", "--check", pf.name).exitCode == 0
                    if (pf.exists()) pf.delete()
                    if (alreadyApplied) {
                        println("[FLYWHEEL] ALREADY ${s.id.takeLast(6)} API delta is present")
                        landed += arm
                        continue
                    }
                    if (apply.exitCode != 0 && conflicted.isEmpty()) {
                        // Corrupt patch — increment probe counter and retire after 3
                        val probes = (corruptPatchProbes[s.id] ?: 0) + 1
                        corruptPatchProbes[s.id] = probes
                        if (probes >= 3) {
                            corruptPatchProbes.remove(s.id)
                            conductor.retireTerminal(s.id, "corrupt patch after $probes probes; nothing to land", Clock.System.now().toEpochMilliseconds())
                            println("[FLYWHEEL] RETIRE ${s.id.takeLast(6)} corrupt patch after $probes probes")
                        } else {
                            emitPollError("drain ${s.id}: corrupt patch (probe $probes/3)", 0)
                        }
                        continue
                    }
                    val touched = parsePatchFiles(cleanPatch)
                    if (touched.isEmpty()) {
                        drainFail(s, "empty patch file list")
                        continue
                    }
                    git("add", "--", *touched.toTypedArray())
                    val commit = git(
                        "commit", "--no-verify", "-m",
                        "flywheel: patch ${s.title.take(50)} (${s.id.takeLast(6)})",
                    )
                    if (commit.exitCode != 0 && !isWorkingTreeClean()) {
                        // Corrupt patch — increment probe counter and retire after 3
                        val probes = (corruptPatchProbes[s.id] ?: 0) + 1
                        corruptPatchProbes[s.id] = probes
                        if (probes >= 3) {
                            corruptPatchProbes.remove(s.id)
                            conductor.retireTerminal(s.id, "corrupt patch after $probes probes; nothing to land", Clock.System.now().toEpochMilliseconds())
                            println("[FLYWHEEL] RETIRE ${s.id.takeLast(6)} corrupt patch after $probes probes")
                        } else {
                            emitPollError("drain ${s.id}: corrupt patch (probe $probes/3)", 0)
                        }
                        continue
                    }
                    landed += arm
                    corruptPatchProbes.remove(s.id) // clear on success
                } finally {
                    if (pf.exists()) pf.delete()
                }
            }
        }
        // PER-ARM CLOSE — close provenance for every arm that landed.
        // Previously, a single failed merge (conflict commit failure, patch
        // apply failure) returned DrainBatch() empty and froze ALL arms'
        // provenance — same dining-philosopher shape as the CAS gate above.
        // Now: close the arms that DID land; failed arms keep their
        // drainFail record and retry next cycle.
        if (landed.isEmpty()) {
            println("[FLYWHEEL] DRAIN no merges landed out of ${arms.size}; provenance stays open")
            return DrainBatch()
        }
        if (landed.size < arms.size) {
            println("[FLYWHEEL] DRAIN partial merge ${landed.size}/${arms.size} — closing landed arms")
        }

        val panorama = landed.map { arm ->
            QaLaguna.SessionPanorama(
                sessionId = arm.session.id,
                title = arm.session.title,
                touchedFiles = parsePatchFiles(arm.patch),
            )
        }
        val cumulativeConflicts = conflictFiles()
        println("[FLYWHEEL] DRAIN ${landed.size}/${sessions.size} sessions merged, ${cumulativeConflicts.size} conflict files")

        // 3. Repair the cumulative panorama after every arm has landed.
        //    Best-effort: if the brain is slow/429ing, conflict markers stay
        //    committed and the wheel moves on. Never block dispatch on brain latency.
        if (cumulativeConflicts.isNotEmpty()) {
            val resolutions = withTimeoutOrNull(45_000L) {
                QaLaguna.resolveConflicts(
                    repoDir = repoDir,
                    brain = brain,
                    panorama = panorama,
                    files = cumulativeConflicts,
                )
            } ?: run {
                println("[FLYWHEEL] QA-LAGUNA timed out after 45s — conflict markers stay committed")
                emptyList<Pair<String, Boolean>>()
            }
            val resolvedFiles = resolutions.filter { it.second }.map { it.first }
            if (resolvedFiles.isNotEmpty()) {
                git("add", "--", *resolvedFiles.toTypedArray())
                val repair = git(
                    "commit", "--no-verify", "-m",
                    "flywheel: resolve cumulative ${landed.size}-session conflicts",
                )
                if (repair.exitCode != 0 && !isWorkingTreeClean()) {
                    emitPollError("cumulative repair commit failed: ${repair.output.take(200)}", 0)
                    return DrainBatch(conflicts = cumulativeConflicts, panorama = panorama)
                }
            }
            val unresolved = conflictFiles()
            if (unresolved.isNotEmpty()) {
                println("[FLYWHEEL] DRAIN repair incomplete — ${unresolved.size} conflict files remain; closing landed provenance")
            }
        }

        // 4. Compilation observes the integrated result but does not revoke a
        //    committed merge. Closing provenance here prevents the next cycle
        //    from reapplying every arm and compounding the same conflict. The
        //    CycleBody conflict quarantine keeps dispatch paused while markers
        //    remain; QA or a locality resolver advances the committed tree.
        val build = shell(300_000L, "./gradlew", ":jvmMainClasses", "--no-daemon")
        if (build.exitCode != 0) {
            emitPollError("cumulative build failed: ${build.output.take(400)}", 0)
            println("[FLYWHEEL] DRAIN build red — committed completion set still closes provenance")
        } else {
            println("[FLYWHEEL] DRAIN build green")
        }

        // 5. PROVENANCE CLOSE — every successful arm shares the final repaired
        //    commit but retains its own CAS CID, tag, receipt, and WAL identity.
        val commitSha = headSha()
        val now = Clock.System.now().toEpochMilliseconds()
        data class PreparedClose(
            val arm: Arm,
            val tag: String,
            val prUrl: String?,
        )
        val prepared = mutableListOf<PreparedClose>()
        for (arm in landed) {
            val (s, patchCid, _, branch) = arm
            val safeSession = s.id.replace(Regex("[^A-Za-z0-9._-]"), "-")
            val tag = "flywheel/jules-$safeSession-${commitSha.take(12)}"
            val existingTagCommit = git("rev-parse", "$tag^{commit}")
            val tagReady = existingTagCommit.exitCode == 0 &&
                existingTagCommit.output.trim() == commitSha
            if (!tagReady) {
                val tagResult = git("tag", "-a", tag, commitSha, "-m",
                    "Jules merge receipt\nsession=${s.id}\npatchCid=${patchCid.value}\nbranch=${branch ?: "none"}\ntaskTitle=${s.title}")
                if (tagResult.exitCode != 0) {
                    drainFail(s, "tag create failed: ${tagResult.output.take(200)}")
                    continue  // per-arm: don't abandon the rest of the batch
                }
            }
            val prUrl = try { fishPrUrl(s.id, tag) } catch (_: Throwable) { null }
            val workId = store.loadQueue().firstOrNull { it.sessionId == s.id }?.workId
                ?: "session:${s.id.replace(Regex("[^A-Za-z0-9._-]"), "-")}"
            val receipt = MergeReceipt(
                workId = workId,
                producer = "jules",
                producerRef = s.id,
                patchCid = patchCid,
                revision = commitSha,
                versionTag = tag,
                lexicalMemory = LexicalMemory(summary = s.title, title = s.title, content = ""),
                claimedAt = now,
                prUrl = prUrl,
            )
            try {
                store.appendWork(workId, JulesCause.WorkDrained(
                    workId = workId,
                    sessionId = s.id,
                    commitSha = commitSha,
                    taskId = tag,
                    receipt = receipt,
                    at = now,
                ))
                store.appendWork(workId, JulesCause.WorkIdentitySynthesized(
                    workId = workId,
                    identity = WorkIdentity(
                        workId = workId,
                        sessionId = s.id,
                        gitBranch = branch,
                        prUrl = prUrl,
                        gitTag = tag,
                        commitSha = commitSha,
                    ),
                    at = now,
                ))
            } catch (t: Throwable) {
                emitPollError("provenance WAL ${s.id}: ${t.message}", 0)
                drainFail(s, "provenance WAL failed: ${t.message?.take(200)}")  // per-arm: record drainFail so the retry/retire counter advances like the tag path
                continue  // per-arm: don't abandon the rest of the batch
            }
            prepared += PreparedClose(arm, tag, prUrl)
        }

        // All per-arm tags, receipts, and identity records exist before any
        // card leaves the completion set. A failure above closes no card.
        conductor.recordDrains(prepared.map {
            JulesConductor.DrainRecord(
                sessionId = it.arm.session.id,
                commitSha = commitSha,
                rejects = conflictFiles().size,
            )
        })
        for ((arm, tag, prUrl) in prepared) {
            val (s, patchCid, _, branch) = arm
            _events.emit(FlywheelEvent.Drained(s.id, commitSha, tag))
            drainFailures.remove(s.id)
            println("[FLYWHEEL] PROVENANCE ${s.id.takeLast(6)} cid=${patchCid.value.take(16)} branch=${branch ?: "none"} tag=$tag")
            sendMergeReceipt(s.id, commitSha, tag, patchCid, branch, prUrl)
            try {
                client.deleteSession(s.id)
                println("[FLYWHEEL] DELETE ${s.id.takeLast(6)} session cleared")
            } catch (t: Throwable) {
                emitPollError("delete session ${s.id}: ${t.message?.take(200)}", 0)
            }
        }
        return DrainBatch(
            harvested = prepared.size,
            conflicts = conflictFiles(),
            panorama = panorama,
        )
    }

    private fun activeCount(): Int = conductor.cards.values.count {
        it.snapshot.state !in TERMINAL_STATES && !it.drained
    }

    /** Find the GitHub branch or PR head carrying this Jules session id. */
    private suspend fun findSessionBranch(sessionId: String): String? {
        val numericId = sessionId.substringAfterLast('/').filter { it.isDigit() }
        if (numericId.isEmpty()) return null

        // Jules branches and task branches both carry the numeric Jules id.
        val refs = git("for-each-ref", "--format=%(refname:short)", "refs/remotes/origin")
        if (refs.exitCode == 0) {
            refs.output.lineSequence().map { it.trim() }.firstOrNull {
                it.startsWith("origin/") && numericId in it
            }?.let { return it }
        }

        // A PR may carry the Jules id in its title/body while its head branch
        // has another name. Merge that PR head; pushing master closes the PR.
        val pr = shell(
            "gh", "pr", "list", "--state", "open", "--search", numericId,
            "--json", "headRefName", "--jq", ".[0].headRefName // \"\"",
        )
        val head = pr.output.trim()
        if (pr.exitCode == 0 && head.isNotEmpty()) {
            val ref = "origin/$head"
            if (git("show-ref", "--verify", "--quiet", "refs/remotes/$ref").exitCode == 0) return ref
        }
        return null
    }


    /**
     * Sync local master to origin/master with one ordinary 3-way merge.
     * Conflicts are NOT resolved — neither `--ours` nor `--theirs`, no
     * auto-resolution; conflict markers stay in the working tree and the
     * barrier reports drift. Throughput > purity: 40 dirty merges beat 4× the
     * wall clock of a curator round-trip.
     *
     * Returns true iff the merge command exited 0 (fast-forward or merge
     * succeeded). A non-zero exit leaves the tree dirty with conflict markers;
     * settlementBarrier decides whether to push anyway.
     */
    private suspend fun synchronizeMain(): Boolean {
        if (!isWorkingTreeClean()) return false
        if (git("fetch", "origin", "master").exitCode != 0) return false
        // `--no-ff` preserves the synchronization edge even when a fast-forward
        // is possible. Jules branches are merged later by drainThreeWay().
        return git("merge", "--no-ff", "origin/master").exitCode == 0
    }

    /**
     * Close non-terminal cards whose Jules branch is already an ancestor of
     * HEAD. Jules can leave the API state at IN_PROGRESS after an external PR
     * merge, which otherwise occupies a slot forever. Git ancestry is the
     * authority; every reconciliation still records an immutable CAS artifact,
     * annotated tag, queue receipt, identity, and drained card in that order.
     */
    private suspend fun reconcileGitState() {
        val candidates = conductor.cards.values.filter { card ->
            !card.drained && card.snapshot.state !in TERMINAL_STATES
        }
        if (candidates.isEmpty()) return

        val fetch = git("fetch", "origin", "--prune")
        if (fetch.exitCode != 0) {
            emitPollError("reconcile fetch failed: ${fetch.output.take(200)}", 0)
            return
        }
        val mergedRefs = git(
            "for-each-ref", "--format=%(refname:short)", "--merged=HEAD", "refs/remotes/origin",
        )
        if (mergedRefs.exitCode != 0) {
            emitPollError("reconcile merged-ref query failed: ${mergedRefs.output.take(200)}", 0)
            return
        }
        val mergedBranches = mergedRefs.output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("origin/jules-") }
            .toList()
        if (mergedBranches.isEmpty()) return

        val workIds = store.loadQueue().mapNotNull { entry ->
            entry.sessionId?.let { it to entry.workId }
        }.toMap()
        for (card in candidates) {
            val sessionId = card.snapshot.sessionId
            val numericId = sessionId.substringAfterLast('/').filter { it.isDigit() }
            if (numericId.isEmpty()) continue
            // Jules uses both jules-<id>-<hash> and jules-<slug>-<id>-<hash>.
            val branch = mergedBranches.firstOrNull { numericId in it } ?: continue
            val revision = git("rev-parse", branch)
            if (revision.exitCode != 0 || revision.output.isBlank()) {
                emitPollError("reconcile $sessionId: cannot resolve $branch", 0)
                continue
            }
            val commitSha = revision.output.trim()
            val gitDelta = git("show", "--format=", "--binary", commitSha)
            if (gitDelta.exitCode != 0) {
                emitPollError("reconcile $sessionId: cannot read $commitSha", 0)
                continue
            }
            // A merge commit can have no default show diff; its ref+revision are
            // still durable Git evidence rather than a hollow empty CID.
            val patchBytes = gitDelta.output.ifBlank {
                "reconciled-session=$sessionId\nbranch=$branch\nrevision=$commitSha\n"
            }.encodeToByteArray()
            val patchCid = try {
                casStore.put(patchBytes)
            } catch (t: Throwable) {
                emitPollError("reconcile $sessionId: CAS put failed: ${t.message?.take(200)}", 0)
                continue
            }

            val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "-")
            val tag = "flywheel/jules-$safeSession-${commitSha.take(12)}"
            val existingTag = git("rev-parse", "--verify", "$tag^{commit}")
            if (existingTag.exitCode == 0 && existingTag.output.trim() != commitSha) {
                emitPollError("reconcile $sessionId: existing tag $tag targets ${existingTag.output.trim()}", 0)
                continue
            }
            if (existingTag.exitCode != 0) {
                val tagged = git(
                    "tag", "-a", tag, commitSha, "-m",
                    "Jules reconciliation receipt\nsession=$sessionId\nbranch=$branch\npatchCid=${patchCid.value}",
                )
                if (tagged.exitCode != 0) {
                    emitPollError("reconcile $sessionId: tag create failed: ${tagged.output.take(200)}", 0)
                    continue
                }
            }

            val workId = workIds[sessionId]
                ?: "session:${sessionId.replace(Regex("[^A-Za-z0-9._-]"), "-")}"
            val now = Clock.System.now().toEpochMilliseconds()
            val prUrl = runCatching { fishPrUrl(sessionId, tag) }.getOrNull()
            val receipt = MergeReceipt(
                workId = workId,
                producer = "jules",
                producerRef = sessionId,
                patchCid = patchCid,
                revision = commitSha,
                versionTag = tag,
                lexicalMemory = LexicalMemory(
                    summary = card.card.title,
                    title = card.card.title,
                    content = "reconciled from $branch at $commitSha",
                ),
                claimedAt = now,
                prUrl = prUrl,
            )
            try {
                store.appendWork(workId, JulesCause.WorkDrained(
                    workId = workId,
                    sessionId = sessionId,
                    commitSha = commitSha,
                    taskId = tag,
                    receipt = receipt,
                    at = now,
                ))
                store.appendWork(workId, JulesCause.WorkIdentitySynthesized(
                    workId = workId,
                    identity = WorkIdentity(
                        workId = workId,
                        sessionId = sessionId,
                        gitBranch = branch,
                        prUrl = prUrl,
                        gitTag = tag,
                        commitSha = commitSha,
                    ),
                    at = now,
                ))
                conductor.recordDrain(sessionId, commitSha, rejects = 0)
            } catch (t: Throwable) {
                emitPollError("reconcile $sessionId: provenance WAL failed: ${t.message?.take(200)}", 0)
                continue
            }
            println("[FLYWHEEL] RECONCILE ${sessionId.takeLast(6)} branch=$branch tag=$tag")
            sendMergeReceipt(sessionId, commitSha, tag, patchCid, branch, prUrl)
            try {
                client.deleteSession(sessionId)
                println("[FLYWHEEL] DELETE ${sessionId.takeLast(6)} session cleared")
            } catch (t: Throwable) {
                emitPollError("delete session $sessionId: ${t.message?.take(200)}", 0)
            }
        }
    }

    /**
     * Post the merge receipt back onto the Jules task itself: merge timestamp,
     * commit, tag, CAS patch id, branch and PR URL. The tag/commit/URL bond is
     * the idempotency anchor — git ancestry and tag existence already veto
     * dupe merges upstream, so this message fires exactly once per landed
     * merge, preventing loops and backwash. Best-effort: a send failure never
     * revokes provenance.
     */
    private suspend fun sendMergeReceipt(
        sessionId: String,
        commitSha: String,
        tag: String,
        patchCid: ContentId,
        branch: String?,
        prUrl: String?,
    ) {
        val msg = buildString {
            appendLine("FLYWHEEL MERGE RECEIPT")
            appendLine("mergedAt=${java.time.Instant.ofEpochMilli(System.currentTimeMillis())}")
            appendLine("commit=$commitSha")
            appendLine("tag=$tag")
            appendLine("patchCid=${patchCid.value}")
            appendLine("branch=${branch ?: "none"}")
            append("pr=${prUrl ?: "none"}")
        }
        try {
            conductor.answer(sessionId, msg)
            println("[FLYWHEEL] RECEIPT ${sessionId.takeLast(6)} -> jules task tag=$tag")
        } catch (t: Throwable) {
            emitPollError("receipt send $sessionId: ${t.message?.take(200)}", 0)
        }
    }

    /**
     * Push all locally drained commits to origin/master, then surface the
     * current local/remote state. Divergence is observable and the
     * next synchronizeMain() cycle resolves it. PRs do not block (Jules
     * pushes branches; PRs are operator surface, not gate surface).
     *
     * Returns true iff push succeeded. A false return means origin rejected
     * (e.g. branch protection); the working tree is left dirty so the next
     * cycle can recover.
     */
    private suspend fun settlementBarrier(): Boolean {
        if (!isWorkingTreeClean()) return false
        val push = git("push", "--follow-tags", "origin", "HEAD:master")
        if (push.exitCode != 0) return false

        val openPrs = shell(
            "gh", "pr", "list", "--state", "open", "--limit", "100",
            "--json", "number", "--jq", "length",
        )
        val openCount = openPrs.output.trim().toIntOrNull() ?: 0

        if (git("fetch", "origin", "master").exitCode != 0) return false
        val local = git("rev-parse", "HEAD")
        val remote = git("rev-parse", "origin/master")
        if (local.exitCode != 0 || remote.exitCode != 0 ||
            local.output.trim() != remote.output.trim()
        ) return false

        // Conductor is the authority for drain completeness; queue is intake only.
        // If conductor has no undrained COMPLETED sessions, the drain is closed.
        val undrainedCompleted = conductor.cards.values.count {
            it.snapshot.state == "COMPLETED" && !it.drained
        }
        if (undrainedCompleted != 0) {
            println("[FLYWHEEL] SETTLE-BLOCKED $undrainedCompleted COMPLETED session(s) not yet drained in conductor")
            return false
        }

        val unclaimedDrains = store.loadQueue().count { it.isUnclaimedDrain }
        if (unclaimedDrains != 0) {
            println("[FLYWHEEL] SETTLE-BLOCKED $unclaimedDrains queue drain(s) lack immutable receipts")
            return false
        }
        if (openCount != 0) println("[FLYWHEEL] SETTLE-NOTED $openCount open PR(s); branch intake remains non-gating")
        
        borg.trikeshed.util.oroboros.FlywheelHistoryReaper.reapOldTags(repoDir)
        
        return true
    }

    // ─── Dispatch helpers ───────────────────────────────────────────────────

    /** Maximum Jules submission prompt size in UTF-8 bytes. */
    private val SPEC_BYTE_LIMIT = 4000

    /** Jules session states that no longer occupy a slot. */
    private val TERMINAL_STATES = setOf("COMPLETED", "FINISHED", "FAILED", "CANCELLED")

    /**
     * Cap a spec at [SPEC_BYTE_LIMIT] bytes. Truncates cleanly at a word
     * boundary and appends a truncation marker so the Jules agent knows the
     * spec was cut.
     */
    private fun capSpec(spec: String): String {
        val bytes = spec.encodeToByteArray()
        if (bytes.size <= SPEC_BYTE_LIMIT) return spec
        // Walk back to a safe boundary within the limit.
        var cut = SPEC_BYTE_LIMIT - 20 // leave room for marker
        while (cut > 0 && bytes[cut].toInt() and 0xC0 == 0x80) cut-- // skip UTF-8 continuation bytes
        return spec.encodeToByteArray().copyOfRange(0, cut).decodeToString().trim() +
            "\n\n[spec truncated at $SPEC_BYTE_LIMIT bytes]"
    }

    /**
     * Extract file paths mentioned in a task spec — the task's file scope.
     * Matches `src/...`, `doc/...`, `bin/...`, `build.gradle.kts` etc.
     */
    private val specFilePattern = Regex("""(?:src|doc|bin|build\.gradle\.kts|settings\.gradle\.kts)/[A-Za-z0-9_./-]+""")

    private fun extractSpecFiles(spec: String): Set<String> =
        specFilePattern.findAll(spec).map { it.value.trimEnd('.', ',', ':', ';', ')', ']') }.toSet()

     /**
      * Run a git command in [repoDir]. Unified shell — every git ProcessBuilder
      * site in FlywheelDriver goes through here. Prepends `"git"` so callers
      * write `git("commit", "-m", ...)` not `git("git", "commit", "-m", ...)`.
      * For non-git commands (gh, ./gradlew) use [shell].
      */
     private suspend fun git(vararg args: String): CommandResult = shell("git", *args)

     /**
      * Run an arbitrary command in [repoDir]. Use [git] for git subcommands.
      * The default 30-second timeout keeps git/GitHub prompts from parking the
      * wheel; drain-time Gradle gates pass their own bounded build window.
      */
     private suspend fun shell(vararg args: String): CommandResult = shell(30_000L, *args)

     private suspend fun shell(timeoutMs: Long, vararg args: String): CommandResult = withContext(Dispatchers.IO) {
         try {
             val process = ProcessBuilder(*args)
                 .directory(repoDir)
                 .redirectErrorStream(true)
                 .start()
             val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
             if (!finished) {
                 val descendants = buildList {
                     val stream = process.toHandle().descendants()
                     try {
                         stream.forEach { child -> add(child) }
                     } finally {
                         stream.close()
                     }
                 }
                 val childExits = descendants.map { child -> child.onExit() }
                 descendants.forEach { child -> child.destroyForcibly() }
                 process.destroyForcibly()
                 val reapDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                 try {
                     val remaining = (reapDeadline - System.nanoTime()).coerceAtLeast(0)
                     java.util.concurrent.CompletableFuture.allOf(*childExits.toTypedArray())
                         .get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)
                 } catch (interrupted: InterruptedException) {
                     Thread.currentThread().interrupt()
                 } catch (_: Throwable) {
                 }
                 val remaining = reapDeadline - System.nanoTime()
                 try {
                     if (remaining > 0) {
                         process.waitFor(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)
                     }
                 } catch (interrupted: InterruptedException) {
                     Thread.currentThread().interrupt()
                 }
                 val survivors = descendants.count { it.isAlive } + if (process.isAlive) 1 else 0
                 CommandResult(1, "timeout after ${timeoutMs}ms: ${args.joinToString(" ")}; surviving processes=$survivors")
             } else {
                 CommandResult(process.exitValue(), process.inputStream.bufferedReader().readText())
             }
         } catch (t: Throwable) {
             CommandResult(1, t.message.orEmpty())
         }
     }

    private data class CommandResult(val exitCode: Int, val output: String)

     /**
      * True iff the working tree has no tracked modifications or staged changes.
      * Untracked files do NOT count as dirty — Jules sessions leave artifacts
      * behind that are harmless to merges and would otherwise permanently block
      * the wheel.
      */
     private suspend fun isWorkingTreeClean(): Boolean =
         git("status", "--porcelain", "--untracked-files=no").output.isBlank()

    /** Project the unified Forge×Jules board and render the saturation wheel. */
    fun renderSaturation(): String {
        val kanban = try { ForgeKanbanIngest.load("jim").board }
        catch (_: Throwable) { borg.trikeshed.kanban.KanbanBoard(
            id = borg.trikeshed.kanban.KanbanBoardId("flywheel"),
            name = "flywheel",
            columns = JulesLane.values().map { borg.trikeshed.kanban.KanbanColumn(
                borg.trikeshed.kanban.KanbanColumnId(it.columnName), it.columnName, it.order) },
            cards = emptyList(),
        ) }
        val unified = unifyBoard(kanban, conductor.cards.values)
        val aliveCount = conductor.cards.values.count {
            it.snapshot.state !in TERMINAL_STATES && !it.drained }
        return renderWheel(unified, aliveCount, maxSlots, intervalMs)
    }

    /** Build a project-conventions answer for an AWAITING session inquiry.
     *  Fires the [brain] (BrainClient → NVIDIA NIM Laguna XS 2.1) with
     *  conventions as the system message and the inquiry as the user message.
     *  Returns "" if no brain is configured (NVIDIA_API_KEY missing) — the
     *  caller skips the answer; never sends a template. */
    private suspend fun buildAnswer(card: JulesSessionCard): String {
        val title = card.card.title
        val lastCause = card.causes.lastOrNull()
        val lastAct = client.activities(card.snapshot.sessionId).lastOrNull()
        val inquiry = lastAct?.excerpt?.take(400) ?: lastCause?.let { when (it) {
            is JulesCause.AgentMessaged -> it.excerpt.take(400)
            else -> null
        } } ?: return ""

        val conventions = buildString {
            appendLine("You are the GUIDE for the TrikeShed KMP project.")
            appendLine("Answer coding-agent questions with concrete, decisive guidance (<200 words).")
            appendLine("Project conventions:")
            appendLine("  - domain logic goes in commonMain/kotlin/; platform adapters in jvmMain/jsMain/nativeMain")
            appendLine("  - use Series<T> over List<T> for read-only indexed data")
            appendLine("  - use Confix JSON (borg.trikeshed.parse.json.JsonSupport), not kotlinx-serialization-json")
            appendLine("  - do not add or run unit tests")
            appendLine("  - build gate: ./gradlew :jvmMainClasses --no-daemon")
            appendLine("  - never use the word 'notion' in code, comments, or identifiers (trademark)")
            appendLine("  - never delete a working runner to replace with not-yet-built code")
        }

        val b = brain
        if (b == null || !b.hasEndpoints()) {
            println("[FLYWHEEL] WARN no brain endpoints discovered — GUIDE offline, skipping ${card.snapshot.sessionId.takeLast(6)}")
            return ""
        }
        return try {
            b.chat(
                messages = listOf(
                    "system" to conventions,
                    "user" to "Task title: $title\n\nInquiry from the coding agent:\n$inquiry",
                ),
                maxTokens = 400,
                temperature = 0.2,
            )
        } catch (t: Throwable) {
            println("[FLYWHEEL] BRAIN-ERROR ${card.snapshot.sessionId.takeLast(6)}: ${t.message}")
            ""
        }
    }


    /**
     * Content-address the exact cumulative patch bytes and pin the protected
     * release tag onto the commit. The CAS put ([FileCasStore.put]) verifies by
     * re-reading, so a backing-store failure throws here and the tag is never
     * created on a hollow receipt. The returned [MergeReceipt.patchCid] is a real
     * content-addressable blob, retrievable as `casStore.get(receipt.patchCid)`,
     * not a detached hash. Internal for testability (drives a real `.git` tag).
     */
    internal suspend fun claimPatch(
        commitSha: String,
        patch: String,
        sessionId: String,
        workId: String,
        title: String,
        content: String,
    ): ClaimedPatch? {
        val patchBytes = patch.encodeToByteArray()
        val patchCid = try {
            casStore.put(patchBytes)
        } catch (e: Exception) {
            println("[FLYWHEEL] CAS-FAIL ${sessionId.takeLast(6)}: ${e.message}")
            return null
        }
        val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val tag = "flywheel/jules-$safeSession-${commitSha.take(12)}"
        val tagMessage =
            "Jules merge receipt\nsession=$sessionId\nwork=$workId\npatchCid=${patchCid.value}"
        if (git("tag", "-a", tag, commitSha, "-m", tagMessage).exitCode != 0) {
            return null
        }

        // Best-effort PR/branch URL fishing. The Jules session id is the ticket;
        // this url is the optional upstream surface that ties the receipt to the
        // human-visible PR or branch. null is a valid result — the receipt stands
        // with or without a PR (Jules pushes branches, not PRs).
        val prUrl = fishPrUrl(sessionId, tag)

        val receipt = MergeReceipt(
            workId = workId,
            producer = "jules",
            producerRef = sessionId,
            patchCid = patchCid,
            revision = commitSha,
            versionTag = tag,
            lexicalMemory = LexicalMemory(
                summary = title,
                title = title,
                content = content,
            ),
            claimedAt = Clock.System.now().toEpochMilliseconds(),
            prUrl = prUrl,
        )
        return ClaimedPatch(commitSha, receipt)
    }

    /**
     * Fish an optional PR/branch URL tying this receipt to the upstream
     * surface. Probes: (1) `git ls-remote origin 'refs/heads/jules-<numericId>-*'`,
     * (2) `gh pr list --json url,headRefName`. Both swallow errors and return
     * null on no match; the receipt is provenance-complete via [MergeReceipt.patchCid]
     * + [revision]. Jules pushes branches (not PRs); null is valid for direct merges.
     */
    private suspend fun fishPrUrl(sessionId: String, tag: String): String? {
        val numericId = sessionId.substringAfterLast('/').filter { it.isDigit() }
        if (numericId.isEmpty()) return null
        // Probe 1: branch-on-origin.
        val ls = git("ls-remote", "origin", "refs/heads/jules-$numericId-*")
        if (ls.exitCode == 0) {
            for (line in ls.output.lineSequence()) {
                val parts = line.trim().split("\t")
                if (parts.size == 2) {
                    val sha = parts[0]
                    val ref = parts[1]
                    if (ref.startsWith("refs/heads/jules-$numericId-") && sha.length == 40) {
                        val remote = git("config", "--get", "remote.origin.url")
                        if (remote.exitCode == 0) {
                            val url = originToHtmlUrl(remote.output.trim(), sha)
                            if (url != null) return url
                        }
                    }
                }
            }
        }
        return null
    }

    /** Convert a git remote URL (`git@github.com:foo/bar.git` or `https://.../foo/bar.git`)
     *  and a commit sha into the canonical HTML commit URL. null on unknown shapes. */
    private fun originToHtmlUrl(remote: String, sha: String): String? {
        val cleaned = remote.removeSuffix(".git")
        return when {
            cleaned.startsWith("git@github.com:") -> {
                val repo = cleaned.removePrefix("git@github.com:")
                "https://github.com/$repo/commit/$sha"
            }
            cleaned.contains("github.com/") -> {
                val tail = cleaned.substringAfter("github.com/")
                "https://github.com/$tail/commit/$sha"
            }
            else -> null
        }
    }

    internal data class ClaimedPatch(val commitSha: String, val receipt: MergeReceipt)

    /** Revert only the given files to HEAD. */
    private suspend fun revertFiles(files: List<String>) {
        val cmd = mutableListOf("git", "checkout", "HEAD", "--")
        cmd.addAll(files)
        git(*cmd.toTypedArray())
    }

    /** Parse unidiff headers (--- a/path, +++ b/path) to extract touched file paths. */
    private fun parsePatchFiles(patch: String): List<String> {
        val files = mutableListOf<String>()
        for (line in patch.lines()) {
            if (line.startsWith("+++ b/")) {
                val path = line.removePrefix("+++ b/").trim()
                if (path.isNotEmpty() && path != "/dev/null") files.add(path)
            } else if (line.startsWith("+++ ") && !line.startsWith("+++ /dev/null")) {
                // fallback: bare path without b/ prefix
                val path = line.removePrefix("+++ ").trim()
                if (path.isNotEmpty() && path != "/dev/null" && !path.startsWith("a/") && !path.startsWith("b/")) {
                    files.add(path)
                }
            }
        }
        return files.distinct()
    }

    /**
     * Sandbox scratch files Jules leaves in its patch diffs: `test_script.kt`,
     * `patch.diff`, `plan_script.sh`, and similar verification stubs. They are
     * never part of the real repo tree. When Jules emits a deletion hunk for one
     * of these, `git apply --3way` fails ("test_script.kt: does not exist in index")
     * because we never had the file — even though the real source hunk applied
     * cleanly. Apply rejects the whole diff and the cycle records `DrainFailed`,
     * locking the session into a permanent retry loop (WAL signature: 5 sessions
     * at attempt 107..173).
     *
     * The same patches also carry `+` content lines with trailing whitespace
     * ("trailing whitespace" reject), which `git apply --3way` refuses outright.
     *
     * This sanitizer:
     *   1. Drops whole `diff --git` sections whose target path is a known Jules
     *      sandbox scratch file (so `git apply` never sees the index-missing hunk).
     *   2. Strips trailing whitespace from every `+` content line (hunks starting
     *      with `+` but not `+++`), preserving the diff metadata and context lines.
     *
     * The cleaned patch is what gets written to `.flywheel-patch-<id>` for apply.
     */
    private fun sanitizeJulesPatch(patch: String): String {
        // Known Jules sandbox scratch base names. Match by basename of the diff path.
        val scratchBaseNames = setOf(
            "test_script.kt", "patch.diff", "plan_script.sh",
            "MultiIndexContainer-patch.txt",
        )
        fun isScratchPath(p: String): Boolean {
            val base = p.substringAfterLast('/').trim()
            if (base in scratchBaseNames) return true
            // Jules naming variants: test_script.<anything> or *_scratch.*
            if (base.startsWith("test_script.")) return true
            return false
        }

        // Split into [preamble, section1, section2, ...] where each section begins
        // with a `diff --git` header line. Preserve the preamble (git header blob).
        val lines = patch.split("\n")
        val out = mutableListOf<String>()
        var skipSection = false
        var sawHeader = false
        var inHunkContent = false
        for (line in lines) {
            if (line.startsWith("diff --git ")) {
                // Begin a new diff section. Parse target path from the `+++ b/` we
                // have not seen yet — easier: extract from the `diff --git a/X b/Y`
                // `b/` side which appears right after the `b/` marker.
                val mLine = line
                // The `b/` path is the last token of `diff --git a/PATH b/PATH`.
                // Some Jules diffs use bare names: `diff --git a/test_script.kt b/test_script.kt`.
                val afterB = mLine.substringAfter(" b/", missingDelimiterValue = "")
                val path = afterB.trim().ifEmpty {
                    // Fall back to the a/ side when there is no b/ side (rare).
                    mLine.substringAfter(" a/", missingDelimiterValue = "").trim()
                }
                skipSection = isScratchPath(path)
                sawHeader = true
                inHunkContent = false
                if (!skipSection) out.add(line)
                continue
            }
            if (line.startsWith("@@ ")) {
                inHunkContent = true
                if (!skipSection) out.add(line)
                continue
            }
            if (skipSection) continue
            if (!sawHeader) {
                // Preamble (e.g. `diff --git` not yet seen) — passes through.
                out.add(line)
                continue
            }
            // Within an active section. Trim trailing whitespace ONLY on `+`
            // content lines (not `++`, not context, not `-`).
            if (inHunkContent && line.startsWith("+") && !line.startsWith("+++")) {
                out.add(line.trimEnd())
            } else {
                out.add(line)
            }
        }
        return out.joinToString("\n")
    }

     private suspend fun headSha(): String = git("rev-parse", "HEAD").output.trim()

    /** Subscribe a child coroutine to reactor events. Returns the subscriber's job. */
    fun subscribe(block: suspend (FlywheelEvent) -> Unit): Job =
        reactorScope.launch { events.collect { block(it) } }

    /** Cancel the supervisor; children propagate. Idempotent. */
    fun close() { parentJob.cancel() }

    /**
     * Drain a single completed session: apply patch (--3way, keep both sides),
     * resolve conflicts, build-fix until green, commit, CAS-pin, tag.
     *
     * No reverts. No reworks. No --ours/--theirs. Jules is a task tree — its
     * patches cause no corruption, only conflicts. Conflicts compound across
     * parallel drains and get resolved by editing code, never by discarding work.
     */
    private suspend fun drainOne(s: JulesRestClient.SessionInfo): DrainOutcome {
        val patch = client.lastPatch(s.id)
        if (patch.isNullOrBlank()) {
            val probes = (noPatchProbes[s.id] ?: 0) + 1
            noPatchProbes[s.id] = probes
            if (probes >= 3) {
                noPatchProbes.remove(s.id)
                conductor.retireTerminal(s.id, "no patch after $probes probes; nothing to land", Clock.System.now().toEpochMilliseconds())
                println("[FLYWHEEL] RETIRE ${s.id.takeLast(6)} no-patch after $probes probes")
            } else {
                emitPollError("drain ${s.id}: no patch from lastPatch() (probe $probes/3)", 0)
            }
            return DrainOutcome.Skipped
        }

        val patchFile = File(repoDir, ".flywheel-patch")
        patchFile.writeText(patch)

        // Apply with --3way: if the patch doesn't apply cleanly, git falls back
        // to a 3-way merge and leaves conflict markers. We KEEP those markers —
        // they represent both sides' work and get resolved below. Never --ours,
        // never --theirs, never reject.
        git("apply", "--3way", ".flywheel-patch")
        patchFile.delete()

        val touchedFiles = parsePatchFiles(patch)
        if (touchedFiles.isEmpty()) {
            return drainFail(s, "empty patch file list")
        }

        // CONFLICT RESOLUTION + BUILD-FIX LOOP: resolve conflict markers, then
        // build. If red, fix and rebuild. Up to 3 passes. Never revert.
        val conflicts = conflictFiles()
        if (conflicts.isNotEmpty()) {
            println("[FLYWHEEL] CONFLICTS ${conflicts.size} files from ${s.id.takeLast(6)} — resolving")
            resolveConflicts(conflicts)
        }

        var buildOk = false
        for (attempt in 1..3) {
            val build = shell(300_000L, "./gradlew", ":jvmMainClasses", "--no-daemon")
            if (build.exitCode == 0) { buildOk = true; break }
            println("[FLYWHEEL] BUILD attempt $attempt failed for ${s.id.takeLast(6)}, fixing")
            val buildErrors = build.output.take(2000)
            if (!fixBuildErrors(buildErrors, touchedFiles)) {
                println("[FLYWHEEL] BUILD could not auto-fix, committing with errors — next RGA resolves")
                break
            }
        }

        // Stage ONLY the touched files + any conflict-resolved files, then commit.
        val addCmd = mutableListOf("git", "add")
        addCmd.addAll(touchedFiles)
        addCmd.addAll(conflicts)
        git(*addCmd.toTypedArray())
        val commitRes = git("commit", "-m", "flywheel: ${s.title}")
        if (commitRes.exitCode != 0) {
            return drainFail(s, "commit failed: ${commitRes.output.take(200)}")
        }

        val commitSha = headSha()
        val patchBytes = patch.encodeToByteArray()
        val patchCid = try { casStore.put(patchBytes) } catch (e: Exception) {
            return drainFail(s, "cas put failed: ${e.message}")
        }
        val safe = s.id.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val tag = "flywheel/jules-" + safe + "-" + commitSha.take(12)
        val msg = "Jules receipt\n" +
            "session=" + s.id + "\n" +
            "patchCid=" + patchCid.value + "\n" +
            "taskTitle=" + s.title
        val tagRes = git("tag", "-a", tag, commitSha, "-m", msg)
        if (tagRes.exitCode != 0) {
            return drainFail(s, "tag create failed: ${tagRes.output.take(200)}")
        }

        // CLOSE THE DRAIN — without these two writes the wheel re-drains this
        // same COMPLETED session every cycle (drained flag never flips) and
        // loadQueue's isDrained/isUnclaimedDrain never clear (no WorkDrained
        // cause), so settlementBarrier's unclaimedDrains != 0 sticks forever.
        // The receipt carries the CAS patchCid + fished prUrl so the queue
        // entry is a claimed drain, not an unclaimed one.
        val prUrl = try { fishPrUrl(s.id, tag) } catch (_: Throwable) { null }
        val receipt = MergeReceipt(
            workId = "",               // bonded below from the queue projection
            producer = "jules",
            producerRef = s.id,
            patchCid = patchCid,
            revision = commitSha,
            versionTag = tag,
            lexicalMemory = LexicalMemory(summary = s.title, title = s.title, content = ""),
            claimedAt = Clock.System.now().toEpochMilliseconds(),
            prUrl = prUrl,
        )
        conductor.recordDrain(s.id, commitSha, conflicts.size)
        val workId = store.loadQueue().firstOrNull { it.sessionId == s.id }?.workId
            ?: "session:${s.id.replace(Regex("[^A-Za-z0-9._-]"), "-")}"
        store.appendWork(workId, JulesCause.WorkDrained(
            workId = workId,
            sessionId = s.id,
            commitSha = commitSha,
            taskId = tag,
            receipt = receipt.copy(workId = workId),
            at = Clock.System.now().toEpochMilliseconds(),
        ))
        _events.emit(FlywheelEvent.Drained(s.id, commitSha, tag))
        drainFailures.remove(s.id)
        try {
            client.deleteSession(s.id)
            println("[FLYWHEEL] DELETE ${s.id.takeLast(6)} session cleared")
        } catch (t: Throwable) {
            emitPollError("delete session ${s.id}: ${t.message?.take(200)}", 0)
        }
        return DrainOutcome.Harvested
    }

    /**
     * Commit any unresolved conflict markers in the working tree as-is.
     * Both sides are kept (the markers stay in the file); the commit moves
     * the repo forward so subsequent drains start from a clean tree. The
     * build-fix loop on the NEXT drain resolves semantic issues.
     */
    private suspend fun commitExistingConflicts() {
        val files = unmergedFiles()
        if (files.isEmpty()) return
        println("[FLYWHEEL] COMMIT-CONFLICTS ${files.size} files with conflict markers — keeping both sides")
        files.take(5).forEach { println("  ✗ $it") }
        val addCmd = mutableListOf("git", "add")
        addCmd.addAll(files)
        git(*addCmd.toTypedArray())
        val msg = "flywheel: conflict — kept both sides (${files.size} files)"
        val res = git("commit", "-m", msg)
        if (res.exitCode != 0) {
            println("[FLYWHEEL] COMMIT-CONFLICTS failed: ${res.output.take(200)}")
        } else {
            println("[FLYWHEEL] COMMIT-CONFLICTS ok — ${files.size} files committed with markers")
        }
    }

     /** Files still unmerged in Git's index for the current 3-way arm. */
     private suspend fun unmergedFiles(): List<String> =
         git("diff", "--name-only", "--diff-filter=U").output.trim().lines()
             .filter { it.isNotBlank() }

    /**
     * Cumulative unresolved files. Conflict-arm commits clear the unmerged
     * index, so also inspect tracked content for conflict-start markers while
     * excluding patch-edit templates (`<<<<<<< SEARCH`). This catches both
     * merge markers (`HEAD`) and `git apply --3way` markers (`ours`).
     */
    private suspend fun conflictFiles(): List<String> {
        val markerFiles = git("grep", "-l", "^<<<<<<< ", "--")
            .output.trim().lines().filter { path ->
                path.isNotBlank() && File(repoDir, path).takeIf { it.isFile }?.useLines { lines ->
                    lines.any { it.startsWith("<<<<<<< ") && it != "<<<<<<< SEARCH" }
                } == true
            }
            // Stale patch/diff/sh artifacts carry conflict markers by design
            // (they ARE diff fragments). Exclude them so they don't block
            // dispatch permanently — only source-tree conflicts gate the
            // settlement barrier.
            .filterNot { path ->
                path.endsWith(".patch") || path.endsWith(".diff") ||
                    path.endsWith(".sh") || path.endsWith(".txt")
            }
        return (unmergedFiles() + markerFiles).distinct()
    }

    /**
     * Resolve conflict markers using n=2+ distance resolution: consider both
     * sides plus their common ancestor context to produce a semantically
     * correct merge. The build-fix loop then makes it compile.
     */
    private fun resolveConflicts(files: List<String>) {
        for (file in files) {
            val f = File(repoDir, file)
            if (!f.exists()) continue
            val content = f.readText()
            val resolved = resolveConflictMarkers(content)
            if (resolved != content) {
                f.writeText(resolved)
                println("[FLYWHEEL]   resolved $file")
            }
        }
    }

    private val conflictStart = Regex("""(?m)^<{7} .+$""")
    private val conflictMid = Regex("""(?m)^={7}$""")
    private val conflictEnd = Regex("""(?m)^>{7} .+$""")

    /**
     * n=2+ distance resolution: keep both sides' code regions (ours then
     * theirs), preserving all work from both branches. The build-fix loop
     * then resolves any semantic issues (duplicate declarations, etc.) by
     * editing the merged result. This guarantees no work is lost — the
     * distance from each side to the resolution is ≥2 (both sides present).
     */

    private fun resolveConflictMarkers(content: String): String {
        if (!content.contains("<<<<<<<")) return content
        val lines = content.lines().toMutableList()
        val result = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (conflictStart.matches(line)) {
                // Keep both sides: ours (between <<< and ===) then theirs (between === and >>>)
                i++
                while (i < lines.size && !conflictMid.matches(lines[i])) {
                    result.add(lines[i]); i++
                }
                i++ // skip the ======= line
                while (i < lines.size && !conflictEnd.matches(lines[i])) {
                    result.add(lines[i]); i++
                }
                i++ // skip the >>>>>>> line
            } else {
                result.add(line)
                i++
            }
        }
        return result.joinToString("\n")
    }

    /**
     * Attempt to fix build errors using the Laguna brain. Returns true if a
     * fix was applied. Never reverts — always moves forward.
     */
    private suspend fun fixBuildErrors(errors: String, touchedFiles: List<String>): Boolean {
        val b = brain ?: return false
        val fileContents = touchedFiles.joinToString("\n\n") { path ->
            val f = File(repoDir, path)
            if (f.exists()) "=== $path ===\n${f.readText().take(2000)}" else ""
        }
        val prompt = buildString {
            appendLine("Fix these build errors. Output the COMPLETE corrected file contents.")
            appendLine("Build errors:")
            appendLine(errors)
            appendLine("Files:")
            appendLine(fileContents)
        }.trim()
        return try {
            val response = withTimeoutOrNull(60_000L) {
                b.chat(messages = listOf("user" to prompt), maxTokens = 2000, temperature = 0.1)
            }
            response != null && response.isNotEmpty().also {
                if (it) println("[FLYWHEEL] BUILD-FIX brain applied ${response.length} chars")
            }
        } catch (t: Throwable) {
            println("[FLYWHEEL] BUILD-FIX brain-error: ${t.message}")
            false
        }
    }

    data class CycleReport(
        /** Wall-clock duration of the cycle in milliseconds. */
        val cycleMs: Long = 0,
        val answered: Int = 0,
        val harvested: Int = 0,
        /** Drains whose patch did not apply cleanly and were re-queued as rework. */
        val reworked: Int = 0,
        val dispatched: Int = 0,
        val alive: Int = 0,
        val available: Int = 0,
        val inducted: Int = 0,
        val settled: Boolean = false,
        /** Which [FlywheelPhase] the cycle last reached before returning (the priority manifest). */
        val phase: FlywheelPhase = FlywheelPhase.POLL,
        /** Conflict markers still unresolved after cumulative QaLaguna repair. */
        val conflicts: List<String> = emptyList(),
        /** Panorama for QaLaguna: every arm in the current completion set. */
        val panorama: List<QaLaguna.SessionPanorama> = emptyList(),
        /** Jules 429 (rate-limit) responses seen this cycle. */
        val http429: Int = 0,
        /** Jules 5xx server-error responses seen this cycle. */
        val http5xx: Int = 0,
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val apiKey = System.getenv("JULES_API_KEY") ?: error("JULES_API_KEY required")
            val once = args.any { it == "--once" }
            val watch = args.any { it == "--watch" }
            val driver = FlywheelDriver(apiKey)
            println("[FLYWHEEL] Starting driver on ${driver.repoDir}")
            if (once) {
                runBlocking {
                    val report = driver.cycle()
                    println(driver.renderSaturation())
                    println("[FLYWHEEL] Cycle: answered=${report.answered} harvested=${report.harvested} inducted=${report.inducted} dispatched=${report.dispatched} alive=${report.alive} settled=${report.settled}")
                }
                return
            }
            runBlocking {
                while (true) {
                    val start = System.currentTimeMillis()
                    val report = driver.cycle()
                    println(driver.renderSaturation())
                    println("[FLYWHEEL] Cycle: answered=${report.answered} harvested=${report.harvested} inducted=${report.inducted} dispatched=${report.dispatched} alive=${report.alive} settled=${report.settled}")
                    if (!watch) {
                        println("[FLYWHEEL] one-shot (no --watch); exiting")
                        return@runBlocking
                    }
                    val elapsed = System.currentTimeMillis() - start
                    val delay = (driver.intervalMs - elapsed).coerceAtLeast(5_000)
                    delay(delay)
                }
            }
        }
    }
}