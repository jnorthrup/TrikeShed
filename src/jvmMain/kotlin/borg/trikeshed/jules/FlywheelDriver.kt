package borg.trikeshed.jules

import borg.trikeshed.htx.HtxKey
import borg.trikeshed.lib.j
import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.job.ContentId
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.util.oroboros.MergeReceipt
import keymux.KeyMux
import borg.trikeshed.causal.rankByProximity
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
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
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
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
 * 4. DRAIN COMPLETED artifacts: observed bytes → CAS → isolated build preflight
 * 5. SETTLE validated fast-forward commits and provenance tags to origin/master
 * 6. INDUCT is a no-op: coordinated RGA producers append partitioned WorkQueued causes
 * 7. DISPATCH up to [maxSlots] non-overlapping queue entries after settlement
 *
 * The durable WAL is the only intake surface and [loadQueue] is the only
 * dispatch surface. The daemon never scans the repository or a todo file for
 * work; the upstream RGA coordinator owns partitioning before WorkQueued.
 *
 * Network execution is owned by `bin/oroboros-daemon`; this reducer has no
 * standalone main because Jules HTTP requires the daemon's inherited HtxKey.
 */
class FlywheelDriver(
    private val keyMux: KeyMux,
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
    @Volatile private var htxElement: borg.trikeshed.htx.HtxElement? = null

    /** Wire the HTX element after daemon construction (circular dep). */
    fun attachHtxElement(htx: borg.trikeshed.htx.HtxElement) {
        htxElement = htx
    }

    /** Jules Pro concurrency ceiling; configuration may lower but never raise it. */
    private val maxSlots: Int = maxSlots.coerceIn(0, 15)
    private val client = JulesRestClient(keyMux)
    internal val brain: BrainClient? = BrainClient(errorSink = JvmBrainErrorSink(forgeDir))
    private val store = JulesBoardStore.forForgeDir(forgeDir)
    private val patchContinuity = JulesPatchContinuityStore(casStore, store)
    private val conductor = JulesConductor(
        client = client,
        headShaProvider = { headSha() },
        store = store,
        source = source,
        patchContinuity = patchContinuity,
    )
    @Volatile private var memoryIndexLayer: MemoryIndexLayer? = null

    /** Attach the daemon's live memory index so eviction can flush it first. */
    fun attachMemoryIndexLayer(indexLayer: MemoryIndexLayer) {
        memoryIndexLayer = indexLayer
    }

    // CCEK context: SupervisorJob + SharedFlow event bus. Dispatch concurrency
    // is bounded by the queue slice (`take(available)`) and structured async.
    private val parentJob: Job = SupervisorJob()
    private val _events = MutableSharedFlow<FlywheelEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<FlywheelEvent> get() = _events.asSharedFlow()
    /** Consecutive patch-bearing drain failures per session id; telemetry only. */
    private val drainFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Drain attempts after which a session is parked for explicit review
     * instead of re-preflighting every cycle. The counter is in-memory: a
     * daemon restart re-arms parked sessions, and any successful drain clears
     * it — the cap bounds wasted preflight burn, not the review debt. */
    private val drainAttemptCap = 25
    private val parkedDrainSessions = mutableSetOf<String>()
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
     * Record a drain review gate without closing patch-bearing provenance.
     * Automatic failure never permits a synthetic settlement receipt.
     */
    private suspend fun drainFail(s: JulesRestClient.SessionInfo, reason: String) {
        val attempts = (drainFailures[s.id] ?: 0) + 1
        drainFailures[s.id] = attempts
        if (attempts >= drainAttemptCap) {
            // Park: stop re-preflighting; the durable WAL gate and the log
            // line below name the operator exits (reviewed selection/reject
            // via JulesPatchReviewCli, or settle-report/settle-reject).
            if (parkedDrainSessions.add(s.id)) {
                println(
                    "[FLYWHEEL] PARK ${s.id.takeLast(6)} after $attempts drain attempts: " +
                        "$reason; parked for explicit review (select/reject/settle-report)",
                )
                recordReviewBlockOnce(
                    s,
                    "drain attempts exhausted ($attempts): $reason; parked for explicit review",
                )
            }
            return
        }
        recordReviewBlockOnce(
            s,
            "$reason; exact producer artifact retained for explicit review",
        )
        emitPollError("drain ${s.id}: $reason (attempt $attempts)", 0)
    }

    /** Append one durable review gate per distinct causal reason, not per poll. */
    private suspend fun recordReviewBlockOnce(s: JulesRestClient.SessionInfo, reason: String) {
        val alreadyRecorded = conductor.cards[s.id]?.causes?.any {
            it is JulesCause.DrainFailed && it.reason == reason
        } == true
        if (!alreadyRecorded) {
            conductor.recordDrainFailure(s.id, reason, Clock.System.now().toEpochMilliseconds())
        }
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
     * The dominant shape across drain helpers is "report and exit" —
     * without this, each site expands to two lines (emit + return) and the
     * emit-message-and-return-value pair becomes invisible to the eye.
     */
    private suspend fun emitPollError(message: String, returnValue: Int): Int {
        _events.emit(FlywheelEvent.PollError(message))
        return returnValue
    }

    /**
     * Keep legacy Necromancer requeues out of automatic dispatch without
     * fabricating settlement. Their unfinished WAL entries remain visible for
     * explicit review and a new reducer cut.
     */
    private suspend fun suppressLegacyNecromance(
        entries: List<borg.trikeshed.utils.kanban.QueueEntry>,
    ): List<borg.trikeshed.utils.kanban.QueueEntry> {
        val legacy = entries.filter { it.workId.startsWith("gap:necromance:") && !it.isDrained }
        if (legacy.isNotEmpty()) {
            println("[FLYWHEEL] REVIEW-BLOCK ${legacy.size} legacy Necromancer requeue(s); WAL remains open")
        }
        return entries.filterNot { it in legacy }
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
        val pollComplete = try {
            withTimeoutOrNull(60_000L) { conductor.pollOnce(); true } == true
        } catch (t: Throwable) {
            classifyHttpError(t)
            _events.tryEmit(FlywheelEvent.PollError("poll ${t.javaClass.simpleName}: ${t.message?.take(200)}"))
            false
        }
        if (!pollComplete) {
            // pollOnce rehydrates cards from WAL before any API call, so the
            // cards map is current even when the API hung. Fall through to
            // DRAIN/SETTLE against WAL state — returning here starves drain
            // and the wheel spins at POLL forever (the documented contract at
            // the top of this block says drain proceeds on rehydrated cards).
            _events.tryEmit(FlywheelEvent.PollError("poll incomplete; draining WAL-rehydrated cards"))
        }

        // Refresh optional branch/PR identity metadata. Producer refs never
        // close a card; exact Jules API/CAS bytes remain mutation authority.
        try {
            harvestOrphanBranches()
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
            try {
                val answer = withTimeoutOrNull(45_000L) { buildAnswer(card) } ?: ""
                if (answer.isNotEmpty()) {
                    conductor.answer(card.snapshot.sessionId, answer)
                    answered++
                    println("[FLYWHEEL] ANSWER ${card.snapshot.sessionId.takeLast(6)} ${card.card.title.take(60)}")
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                classifyHttpError(t)
                recordReviewBlockOnce(
                    JulesRestClient.SessionInfo(
                        id = card.snapshot.sessionId,
                        state = card.snapshot.state,
                        title = card.snapshot.title,
                        patchBytes = card.snapshot.patchBytes,
                    ),
                    "response failed: ${t.message?.take(200)}",
                )
                _events.emit(FlywheelEvent.PollError("answer ${card.snapshot.sessionId}: ${t.message?.take(200)}"))
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
                val approved = withTimeoutOrNull(45_000L) {
                    conductor.approvePlan(card.snapshot.sessionId)
                    true
                } == true
                if (!approved) {
                    _events.emit(FlywheelEvent.PollError("approve ${card.snapshot.sessionId}: timed out"))
                    continue
                }
                answered++
                println("[FLYWHEEL] APPROVE ${card.snapshot.sessionId.takeLast(6)} ${card.card.title.take(60)}")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                classifyHttpError(t)
                _events.tryEmit(FlywheelEvent.PollError("approve ${card.snapshot.sessionId}: ${t.message?.take(200)}"))
            }
        }

        // 2c. Terminal API failures require a reviewed disposition.  FAILED or
        //     CANCELLED is producer state, not evidence that no late report or
        //     patch exists, and therefore cannot synthesize a MergeReceipt.
        for (card in conductor.cards.values.filter {
            it.snapshot.state in setOf("FAILED", "CANCELLED") && !it.drained
        }.sortedBy { it.snapshot.capturedAt }) {
            recordReviewBlockOnce(
                JulesRestClient.SessionInfo(
                    id = card.snapshot.sessionId,
                    state = card.snapshot.state,
                    title = card.card.title,
                    patchBytes = card.snapshot.patchBytes,
                ),
                "terminal ${card.snapshot.state} requires explicit reviewed settlement",
            )
            println("[FLYWHEEL] REVIEW-BLOCK ${card.snapshot.sessionId.takeLast(6)} ${card.snapshot.state}")
        }

        // 3. DRAIN — consume the completed set through exact CAS artifacts.
        //    A tag alone is not a completed drain: the durable card/WAL close is
        //    authoritative so an interrupted provenance write remains retryable.
        val completed = conductor.cards.values.filter {
            it.snapshot.state in DRAINABLE_STATES && !it.drained
        }
        val sessions = completed.map {
            JulesRestClient.SessionInfo(
                id = it.snapshot.sessionId,
                state = it.snapshot.state,
                title = it.card.title,
                patchBytes = 0L,
            )
        }
        // With no Jules deltas waiting, origin refresh can proceed directly. A
        // non-empty completion set synchronizes only after every exact API
        // artifact has been written to CAS inside drainExactArtifacts().
        if (sessions.isEmpty() && conflictFiles().isEmpty()) {
            synchronizeMain()
            // Refresh optional origin ref identities on the idle path.
            harvestOrphanBranches()
        } else if (sessions.isEmpty()) {
            // Existing conflict markers are a review gate; never normalize
            // them into history merely to make the tree appear clean.
            harvestOrphanBranches()
        }
        val drain = drainFanout(sessions)
        val harvested = drain.harvested
        val reworked = drain.reworked

        // 5. SETTLE — only a complete, conflict-free drain set advances. The
        // next cycle retries a review-blocked artifact without closing it.
        val remainingTerminal = conductor.cards.values.count {
            it.snapshot.state in TERMINAL_STATES && !it.drained
        }
        val committedConflicts = (drain.conflicts + conflictFiles()).distinct()
        val readyToSettle = remainingTerminal == 0 &&
            committedConflicts.isEmpty() && isWorkingTreeClean()
        val settled = readyToSettle && settlementBarrier()

        // ARCHIVE — fire every cycle, not only when fully settled. The
        // archive path is idempotent (checks SessionArchived cause) and
        // only touches sessions that already have DrainApplied or
        // PatchRejected.  Gating it behind `settled` means a single
        // stuck review-blocked session prevents *every* settled session
        // from ever being archived on the cloud API — the dashboard
        // counter never drops.  Run it unconditionally so the cloud
        // POST /sessions/$sid:archive fires as soon as a session is
        // settled, regardless of other stuck sessions.
        val archived = archiveSettledSessions()

        // 6. INDUCT — the WAL is the only induction surface. External agents
        //    CAS-put a ≤4000-byte spec and appendWork(workId, WorkQueued).
        //    Nothing to do here — DISPATCH reads loadQueue() directly.
        val inducted = 0

        // 7. DISPATCH — take from the unified queue projection, sorted by
        //    score descending. Waiting work (AWAITING, just answered above)
        //    already holds its slot; we only fill capacity freed by drain.
        //
        //    Dispatch fires when the working tree is clean and there are no
        //    active conflicts. Dispatch settlement gating is applied by the
        //    cycle coordinator after this drain-safety pass.
        //
        //    Overlap guard: each task's file scope must not overlap any
        //    in-flight session's touched files.
        //
        //    Spec cap: Jules submissions are capped at [SPEC_BYTE_LIMIT] bytes.
        var dispatched = 0
        val alive = activeCount()
        val available = (maxSlots - alive).coerceAtLeast(0)
        // A new Jules task always starts at upstream master. Dispatch is legal
        // only after every prior completion is causally settled and origin is
        // byte-for-byte current, otherwise the new task forks stale history.
        val canDispatch = available > 0 && settled && remainingTerminal == 0 &&
            committedConflicts.isEmpty() && isWorkingTreeClean()
        if (canDispatch) {
            // Build the in-flight file set from all active sessions' last patches.
            val inflightFiles = mutableSetOf<String>()
            var activeScopeUnknown = false
            for (card in conductor.cards.values) {
                if (card.snapshot.state !in TERMINAL_STATES) {
                    val observed = card.causes.filterIsInstance<JulesCause.PatchSnapshotObserved>()
                        .maxByOrNull { it.causalOrdinal }
                    if (card.snapshot.patchBytes > 0 && observed == null) activeScopeUnknown = true
                    if (observed != null) inflightFiles += observed.touchedFiles
                }
            }
            if (activeScopeUnknown) {
                emitPollError("dispatch blocked: active Jules file scope is not WAL-observed", 0)
            }
            val pendingCandidates = suppressLegacyNecromance(loadQueueIo())
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
            val unified = unifyBoard(getKanbanBoard(), conductor.cards.values)
            val wheel = saturationWheel(unified)
            val bot = bottleneck(wheel)?.paddle?.name

            val validCandidates = pendingCandidates
                .filterNot { it.workId in closedSessionWorkIds }

            validCandidates.filter { it.spec.isBlank() }.forEach {
                if (it.title.isNotBlank() && reportedSpecMissing.add(it.title)) {
                    _events.tryEmit(FlywheelEvent.SpecMissing(it.title))
                }
            }

            val rankedCandidates = validCandidates
                .takeUnless { activeScopeUnknown }
                .orEmpty()
                .filter { it.spec.isNotBlank() }
                .let { candidates ->
                    val graph = withContext(Dispatchers.IO) { store.buildCausalGraph() }
                    val query = LexicalMemory(summary = "bottleneck dispatch", title = bot ?: "idle", content = "")
                    val wids = candidates.size j { i: Int -> candidates[i].workId }
                    val scored = graph.rankByProximity(query, wids)
                    val widToScore = (0 until scored.size).associate { i: Int -> scored[i].a to scored[i].b }
                    candidates.sortedByDescending {
                        it.score +
                        (if (bot != null && it.tier.equals(bot, ignoreCase = true)) 100.0 else 0.0) +
                        (widToScore[it.workId] ?: 0.0)
                    }
                }
            // Greedy deterministic packing prevents two newly admitted tasks
            // from splitting the same file in one wave. Unknown scope is
            // exclusive until WorkQueued carries a typed path Series.
            val selectedScopes = inflightFiles.toMutableSet()
            val pending = mutableListOf<borg.trikeshed.utils.kanban.QueueEntry>()
            for (entry in rankedCandidates) {
                if (pending.size >= available) break
                val taskFiles = extractSpecFiles(entry.spec)
                val unknown = taskFiles.isEmpty()
                if (unknown && (pending.isNotEmpty() || selectedScopes.isNotEmpty())) continue
                if (!unknown && taskFiles.intersect(selectedScopes).isNotEmpty()) continue
                pending += entry
                selectedScopes += taskFiles
                if (unknown) break
            }
            dispatched = withContext(Dispatchers.IO) {
                coroutineScope {
                    val jobs = pending.map { entry ->
                        async(Dispatchers.IO) {
                            try {
                                val cappedSpec = capSpec(entry.spec)
                                // Deterministic work identity is present in the
                                // request, so an ambiguous POST can reconcile on
                                // the next cycle instead of splitting a duplicate.
                                val dispatchTitle = dispatchTitle(entry.workId, entry.title)
                                val existingSession = conductor.visibleSessions.firstOrNull {
                                    it.title == dispatchTitle
                                }
                                val sessionId = existingSession?.id ?: client.createSession(
                                    prompt = cappedSpec, title = dispatchTitle, source = source)
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
            remainingTerminal > 0 || committedConflicts.isNotEmpty() -> FlywheelPhase.DRAIN
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
            archived = archived,
            phase = phase,
            conflicts = committedConflicts,
            panorama = drain.panorama,
            http429 = cycleHttp429,
            http5xx = cycleHttp5xx,
        )
    }

    /**
     * Start one serialized reducer loop. API transport and subprocess/file
     * effects move to their IO contexts inside [cycle], while every card/WAL
     * decision remains ordered on this single CCEK lane. This prevents poll,
     * drain, answer, and dispatch from observing a torn mutable board.
     */
    suspend fun startReactiveCycle(
        scope: kotlinx.coroutines.CoroutineScope,
        triggers: ReceiveChannel<Unit>? = null,
    ) {
        val htxElement = kotlin.coroutines.coroutineContext[HtxKey]
        require(htxElement != null) {
            "startReactiveCycle must be called inside a withContext(htxElement) block"
        }
        scope.launch(htxElement + Dispatchers.Default) {
            while (true) {
                try {
                    lastReactiveReport = cycle()
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    classifyHttpError(t)
                    _events.emit(FlywheelEvent.PollError("serialized cycle: ${t.message?.take(200)}"))
                }
                if (triggers == null) {
                    delay(intervalMs)
                } else {
                    // Timer and file/WAL events feed one serialized reducer;
                    // the conflated channel never launches overlapping cycles.
                    withTimeoutOrNull(intervalMs) { triggers.receive() }
                }
            }
        }
        println("[CHOREOGRAPHY] serialized CCEK cycle started")
    }

    /**
     * Serialize the exact-artifact settlement lane before the next research
     * wave. Every completed session is selected from durable Jules activity /
     * CAS observations and validated in a disposable Git worktree.
     */
    private val drainGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    private suspend fun drainFanout(sessions: List<JulesRestClient.SessionInfo>): DrainBatch {
        if (!drainGuard.compareAndSet(false, true)) return DrainBatch()
        try {
            if (sessions.isEmpty()) return DrainBatch()
            println("[FLYWHEEL] DRAIN-ALL sessions=${sessions.size}")
            return drainExactArtifacts(sessions)
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

    /** Automatic patch probe distinguishes absent output from a regression gate. */
    private sealed interface AutomaticPatch {
        data class Available(val text: String, val patchCid: ContentId? = null) : AutomaticPatch
        data class ReviewBlocked(val reason: String) : AutomaticPatch
    }

    /** Result of validating one exact CAS artifact in an isolated worktree. */
    private sealed interface PatchPreflight {
        data class Ready(val commitSha: String) : PatchPreflight
        data object AlreadyPresent : PatchPreflight
        data class ReviewBlocked(val reason: String) : PatchPreflight
    }

    /**
     * Resolve drain bytes from durable activity history.  Only an unobserved
     * activity stream falls back to the session output resource; a known
     * regression never falls through to `lastPatch()` and therefore cannot be
     * laundered by a Jules branch or PR.
     */
    private suspend fun automaticPatch(s: JulesRestClient.SessionInfo): AutomaticPatch =
        when (val selected = selectJulesPatchForDrain(conductor.cards[s.id]?.causes.orEmpty())) {
            JulesPatchDrainSelection.Unobserved -> AutomaticPatch.ReviewBlocked(
                "completed session has no WAL-bonded API patch artifact; retain the final agent report " +
                    "for explicit reviewed no-op settlement instead of hollow retirement",
            )
            is JulesPatchDrainSelection.Selected -> {
                val bytes = patchContinuity.bytes(selected)
                AutomaticPatch.Available(bytes.decodeToString(), selected.snapshot.patchCid)
            }
            is JulesPatchDrainSelection.Rejected -> AutomaticPatch.ReviewBlocked(
                "chain rejected ${selected.rejectedSnapshot.causalOrdinal}/" +
                    "${selected.rejectedSnapshot.patchCid.value} (${selected.reason}); " +
                    "settle with JulesSettlementCli settle-reject",
            )
            is JulesPatchDrainSelection.ReviewRequired -> AutomaticPatch.ReviewBlocked(
                "latest activity patch ${selected.regressedLatest.causalOrdinal}/" +
                    "${selected.regressedLatest.patchCid.value} dropped " +
                    selected.missingFiles.joinToString(",") +
                    "; retained candidate ${selected.retainedCandidate.causalOrdinal}/" +
                    selected.retainedCandidate.patchCid.value +
                    " requires explicit reviewed selection/receipt",
            )
        }

    /**
     * Apply and build the exact CAS/API patch away from master.  A failed
     * apply, an introduced conflict marker, an unexpected path, or a red build
     * destroys only this temporary worktree and returns a durable review gate.
     * The caller may fast-forward the resulting child commit only while its
     * original [baseSha] is still HEAD.
     */
    private suspend fun preflightExactPatch(
        s: JulesRestClient.SessionInfo,
        patch: String,
        baseSha: String,
    ): PatchPreflight {
        val allTouched = parsePatchFiles(patch)
        val touched = allTouched.filterNot(::isUnsafeAutomaticPatchPath)
        if (touched.isEmpty()) return PatchPreflight.ReviewBlocked("exact patch names no valid repository files")

        val tempRoot = withContext(Dispatchers.IO) {
            java.nio.file.Files.createTempDirectory("oroboros-jules-${s.id.takeLast(6)}-").toFile()
        }
        val worktree = File(tempRoot, "worktree")
        val patchFile = File(tempRoot, "artifact.patch")
        try {
            withContext(Dispatchers.IO) { patchFile.writeBytes(patch.encodeToByteArray()) }
            val added = git("worktree", "add", "--detach", worktree.absolutePath, baseSha)
            if (added.exitCode != 0) {
                return PatchPreflight.ReviewBlocked("temporary worktree failed: ${added.output.take(200)}")
            }

            val apply = gitIn(worktree, "apply", "--3way", patchFile.absolutePath)
            if (apply.exitCode != 0) {
                val reverse = gitIn(worktree, "apply", "--reverse", "--check", patchFile.absolutePath)
                if (reverse.exitCode != 0) {
                    return PatchPreflight.ReviewBlocked("exact patch does not apply cleanly: ${apply.output.take(300)}")
                }
                val build = shellIn(
                    worktree,
                    300_000L,
                    "./gradlew", "jvmMainClasses", "--console=plain",
                )
                return if (build.exitCode == 0 &&
                    gitIn(worktree, "status", "--porcelain", "--untracked-files=no").output.isBlank()
                ) PatchPreflight.AlreadyPresent
                else PatchPreflight.ReviewBlocked(
                    "exact patch appears present but the base tree is not clean/build-green: ${build.output.takeLast(500)}",
                )
            }
            val unmerged = gitIn(worktree, "diff", "--name-only", "--diff-filter=U")
            if (unmerged.output.isNotBlank()) {
                return PatchPreflight.ReviewBlocked(
                    "exact patch produced conflicts in ${unmerged.output.lineSequence().take(4).joinToString(",")}",
                )
            }

            // Clean unstaged scratch files before staging and building
            gitIn(worktree, "add", "-A", "--", *touched.toTypedArray())
            gitIn(worktree, "checkout", "--", ".")
            gitIn(worktree, "clean", "-fd")

            val stagedNames = gitIn(worktree, "diff", "--cached", "--name-only").output
                .lineSequence().filter(String::isNotBlank).toSet()
            if (stagedNames.isEmpty()) return PatchPreflight.ReviewBlocked("exact patch produced no staged delta")
            if (!touched.toSet().containsAll(stagedNames)) {
                return PatchPreflight.ReviewBlocked(
                    "exact patch mutated undeclared paths: ${(stagedNames - touched.toSet()).joinToString(",")}",
                )
            }
            val stagedDiff = gitIn(
                worktree, "diff", "--cached", "--unified=0", "--", *touched.toTypedArray(),
            )
            val introducedMarker = stagedDiff.output.lineSequence().firstOrNull { line ->
                line.startsWith("+<<<<<<< ") || line == "+=======" || line.startsWith("+>>>>>>> ")
            }
            if (introducedMarker != null) {
                return PatchPreflight.ReviewBlocked("exact patch introduces conflict markers")
            }
            val whitespace = gitIn(worktree, "diff", "--cached", "--check", "--", *touched.toTypedArray())
            if (whitespace.exitCode != 0) {
                return PatchPreflight.ReviewBlocked("exact patch fails git diff --check: ${whitespace.output.take(300)}")
            }

            val build = shellIn(
                worktree,
                300_000L,
                "./gradlew", "jvmMainClasses", "--console=plain",
            )
            if (build.exitCode != 0) {
                return PatchPreflight.ReviewBlocked("required build is red: ${build.output.takeLast(500)}")
            }
            if (gitIn(worktree, "diff", "--quiet").exitCode != 0) {
                return PatchPreflight.ReviewBlocked("required build mutated tracked files")
            }

            val subject = "flywheel: patch ${s.title.take(50)} (${s.id.takeLast(6)})"
            val commit = gitIn(worktree, "commit", "--no-verify", "-m", subject)
            if (commit.exitCode != 0) {
                return PatchPreflight.ReviewBlocked("validated patch commit failed: ${commit.output.take(300)}")
            }
            val revision = gitIn(worktree, "rev-parse", "HEAD")
            return if (revision.exitCode == 0 && revision.output.isNotBlank()) {
                PatchPreflight.Ready(revision.output.trim())
            } else PatchPreflight.ReviewBlocked("validated patch commit has no revision")
        } finally {
            if (worktree.exists()) {
                git("worktree", "remove", "--force", worktree.absolutePath)
            }
            git("worktree", "prune")
            withContext(Dispatchers.IO) {
                if (tempRoot.exists()) tempRoot.deleteRecursively()
            }
        }
    }

    private fun isUnsafeAutomaticPatchPath(path: String): Boolean {
        val lower = path.replace('\\', '/').lowercase()
        val parts = lower.split('/')
        val base = parts.lastOrNull().orEmpty()
        return path.isBlank() || path.startsWith('/') || lower.startsWith(".jules/") ||
            parts.any { it == ".." || it == ".git" || it == ".gradle" } ||
            parts.firstOrNull() == "build" ||
            base in setOf("test_script.kt", "patch.diff", "plan_script.sh", "multiindexcontainer-patch.txt") ||
            base.startsWith("test_script.") || (base.endsWith(".md") && lower.contains("jules"))
    }

    /**
     * Settle exact Jules activity bytes without trusting a branch or PR.
     * Each artifact is CAS-pinned, applied and built in a disposable detached
     * worktree, then integrated only by a clean fast-forward. A failed preflight
     * leaves master untouched and records a durable review gate.
     */
    private suspend fun drainExactArtifacts(sessions: List<JulesRestClient.SessionInfo>): DrainBatch {
        val drainBatchStartMs = System.currentTimeMillis()
        DrainPerformanceTracker.initLogFile(repoDir)

        // 1. Fetch all branches so refs are available locally.
        git("fetch", "origin", "--prune")

        val preFetchedRefs = git("for-each-ref", "--format=%(refname:short)", "refs/remotes/origin")
            .takeIf { it.exitCode == 0 }?.output?.lineSequence()?.map { it.trim() }?.toList() ?: emptyList()

        // CAS-FIRST: content-address every exact delta before any preflight.
        data class Arm(val session: JulesRestClient.SessionInfo, val patchCid: ContentId, val patch: String, val branch: String?)

        val arms = kotlinx.coroutines.coroutineScope {
            sessions.map { s ->
                async {
                    // Parked sessions burned their attempt budget; their exact
                    // producer artifact and WAL review gate already exist.
                    // Skip the continuity probe until an operator verb
                    // (select/reject/settle-*) or a restart re-arms them.
                    if (s.id in parkedDrainSessions) {
                        println("[FLYWHEEL] PARKED-SKIP ${s.id.takeLast(6)} continuity probe skipped; awaiting operator review verb")
                        return@async null
                    }
                    val automatic = withTimeoutOrNull(60_000L) { automaticPatch(s) }
                    if (automatic is AutomaticPatch.ReviewBlocked) {
                        recordReviewBlockOnce(s, automatic.reason)
                        emitPollError("drain ${s.id}: ${automatic.reason}", 0)
                        println("[FLYWHEEL] REVIEW-BLOCK ${s.id.takeLast(6)} ${automatic.reason}")
                        null
                    } else if (automatic !is AutomaticPatch.Available) {
                        emitPollError("drain ${s.id}: patch continuity probe timed out", 0)
                        null
                    } else {
                        val patch = automatic.text
                        val patchCid = try {
                            automatic.patchCid ?: withContext(Dispatchers.IO) {
                                casStore.put(patch.encodeToByteArray())
                            }
                        }
                        catch (e: Exception) {
                            drainFail(s, "CAS put failed: ${e.message}")
                            null
                        }
                        if (patchCid != null) {
                            val branch = findSessionBranch(s.id, preFetchedRefs)
                            println("[FLYWHEEL] CAS ${s.id.takeLast(6)} cid=${patchCid.value.take(16)} branch=${branch ?: "none"}")
                            Arm(s, patchCid, patch, branch)
                        } else null
                    }
                }
            }.awaitAll().filterNotNull().toMutableList()
        }
        // PER-ARM DRAIN — do NOT wait for the whole completion set to CAS.
        // No-patch and regressed sessions remain review-blocked; CAS-ready
        // sessions still drain independently. Drain EVERY arm that HAS an
        // explicitly safe patch now. The previous `arms.size != sessions.size`
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

        // Every completed API delta is now immutable in CAS.  Master must be
        // clean and conflict-free before the safe fast-forward lane begins.
        // synchronizeMain() itself is ancestry-only and cannot create a merge.
        if (!isWorkingTreeClean() || conflictFiles().isNotEmpty()) {
            for (arm in arms) drainFail(arm.session, "master is dirty or contains conflict markers")
            return DrainBatch(conflicts = conflictFiles())
        }
        if (!synchronizeMain() || !isWorkingTreeClean() || conflictFiles().isNotEmpty()) {
            for (arm in arms) drainFail(arm.session, "origin/master cannot be synchronized by fast-forward")
            return DrainBatch(conflicts = conflictFiles())
        }

        // 2. Validate exact CAS bytes in a disposable worktree, then move
        // master only by fast-forward. A discovered branch or PR is identity
        // metadata and never supplies mutation bytes.
        val landed = mutableListOf<Arm>()
        for (arm in arms) {
            val s = arm.session
            val baseSha = headSha()
            when (val preflight = preflightExactPatch(s, arm.patch, baseSha)) {
                PatchPreflight.AlreadyPresent -> {
                    println("[FLYWHEEL] ALREADY ${s.id.takeLast(6)} exact API delta is present and build-green")
                    landed += arm
                }
                is PatchPreflight.ReviewBlocked -> drainFail(s, preflight.reason)
                is PatchPreflight.Ready -> {
                    val headUnchanged = headSha() == baseSha && isWorkingTreeClean() && conflictFiles().isEmpty()
                    if (!headUnchanged) {
                        drainFail(s, "master changed during isolated preflight")
                        continue
                    }
                    val integrate = git("merge", "--ff-only", preflight.commitSha)
                    if (integrate.exitCode != 0) {
                        drainFail(s, "validated commit could not fast-forward master: ${integrate.output.take(200)}")
                        continue
                    }
                    println(
                        "[FLYWHEEL] LANDED ${s.id.takeLast(6)} exact CAS artifact " +
                            "branch=${arm.branch ?: "none"} (identity only)",
                    )
                    landed += arm
                }
            }
        }
        // PER-ARM CLOSE — close provenance for every arm that landed.
        // Close only arms that passed isolated preflight and landed. Failed
        // arms keep their exact CAS object and review gate for a later cycle.
        if (landed.isEmpty()) {
            println("[FLYWHEEL] DRAIN no exact artifacts landed out of ${arms.size}; provenance stays open")
            return DrainBatch()
        }
        if (landed.size < arms.size) {
            println("[FLYWHEEL] DRAIN partial validation ${landed.size}/${arms.size} — closing landed arms")
        }

        val panorama = landed.map { arm ->
            QaLaguna.SessionPanorama(
                sessionId = arm.session.id,
                title = arm.session.title,
                touchedFiles = parsePatchFiles(arm.patch),
            )
        }
        val cumulativeConflicts = conflictFiles()
        if (cumulativeConflicts.isNotEmpty() || !isWorkingTreeClean()) {
            for (arm in landed) drainFail(arm.session, "post-preflight master integrity check failed")
            return DrainBatch(conflicts = cumulativeConflicts, panorama = panorama)
        }
        println("[FLYWHEEL] DRAIN ${landed.size}/${sessions.size} exact artifacts landed build-green")

        // PROVENANCE CLOSE — every successful arm retains its exact CAS CID,
        // accepted revision, tag, receipt, and optional branch/PR synonyms.
        val commitSha = headSha()
        val now = Clock.System.now().toEpochMilliseconds()
        data class PreparedClose(
            val arm: Arm,
            val tag: String,
            val prUrl: String?,
            val workId: String,
            val receipt: MergeReceipt,
        )
        val prepared = mutableListOf<PreparedClose>()
        val queueBySession = withContext(Dispatchers.IO) {
            store.loadQueue().mapNotNull { entry -> entry.sessionId?.let { it to entry } }.toMap()
        }
        for (arm in landed) {
            val (s, patchCid, _, branch) = arm
            val safeSession = s.id.replace(Regex("[^A-Za-z0-9._-]"), "-")
            val tag = "flywheel/jules-$safeSession-${commitSha.take(12)}"
            val existingTagCommit = git("rev-parse", "$tag^{commit}")
            val tagReady = existingTagCommit.exitCode == 0 &&
                existingTagCommit.output.trim() == commitSha &&
                git("for-each-ref", "--format=%(contents)", "refs/tags/$tag").let { message ->
                    message.exitCode == 0 &&
                        "session=${s.id}" in message.output &&
                        "patchCid=${patchCid.value}" in message.output
                }
            if (!tagReady) {
                val tagResult = git("tag", "-a", tag, commitSha, "-m",
                    "Jules merge receipt\nsession=${s.id}\npatchCid=${patchCid.value}\nbranch=${branch ?: "none"}\ntaskTitle=${s.title}")
                if (tagResult.exitCode != 0) {
                    drainFail(s, "tag create failed: ${tagResult.output.take(200)}")
                    continue  // per-arm: don't abandon the rest of the batch
                }
            }
            val prUrl = try {
                fishPrUrl(s.id, tag)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                emitPollError("optional branch identity ${s.id}: ${t.message?.take(200)}", 0)
                null
            }
            val workId = queueBySession[s.id]?.workId
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
            prepared += PreparedClose(arm, tag, prUrl, workId, receipt)
        }

        // A direct Jules API session may not have entered through WorkQueued.
        // Materialize that identity before publication so its immutable receipt
        // remains projectable from loadQueue() after a restart.
        for (close in prepared) {
            if (queueBySession[close.arm.session.id] != null) continue
            val s = close.arm.session
            store.appendWork(close.workId, JulesCause.WorkQueued(
                workId = close.workId,
                tier = "jules-api",
                title = s.title,
                spec = "Observed Jules API artifact; mutation bytes are CAS-addressed.",
                score = 0.5,
                at = now,
            ))
            store.appendWork(close.workId, JulesCause.WorkDispatched(
                workId = close.workId,
                sessionId = s.id,
                attempt = 1,
                at = now,
            ))
        }

        // Tags are local preparation. Publish the accepted commit/tag prefix
        // before declaring WorkDrained or telling Jules it merged upstream.
        // Re-poll immediately before publication: a terminal Jules timeline
        // can still acquire a late patch/report while isolated builds run.
        for (close in prepared) {
            val sessionId = close.arm.session.id
            val timeline = client.activityTimeline(sessionId)
            val card = requireNotNull(conductor.cards[sessionId])
            val patchFacts = patchContinuity.observe(sessionId, timeline.patches, card.causes)
            val reportFacts = patchContinuity.observeReports(
                sessionId,
                timeline.reports,
                card.causes + patchFacts,
            )
            if (patchFacts.isNotEmpty() || reportFacts.isNotEmpty()) {
                val advanced = card.copy(causes = card.causes + patchFacts + reportFacts, drained = false)
                conductor.cards[sessionId] = advanced
                drainFail(close.arm.session, "Jules artifact watermark advanced during preflight")
                return DrainBatch(harvested = 0, panorama = panorama)
            }
        }
        if (prepared.isNotEmpty() && !pushOriginParity()) {
            prepared.forEach { drainFail(it.arm.session, "landed locally but origin/master push/parity failed") }
            return DrainBatch(
                harvested = 0,
                conflicts = conflictFiles(),
                panorama = panorama,
            )
        }

        val receipted = mutableListOf<PreparedClose>()
        for (close in prepared) {
            val (arm, tag, _, workId, receipt) = close
            val (s, _, _, branch) = arm
            try {
                // Identity precedes terminal receipt so a crash cannot create a
                // settled queue entry whose session/tag synonyms were lost.
                store.appendWork(workId, JulesCause.WorkIdentitySynthesized(
                    workId = workId,
                    identity = WorkIdentity(
                        workId = workId,
                        sessionId = s.id,
                        gitBranch = branch,
                        prUrl = receipt.prUrl,
                        gitTag = tag,
                        commitSha = commitSha,
                    ),
                    at = now,
                ))
                store.appendWork(workId, JulesCause.WorkDrained(
                    workId = workId,
                    sessionId = s.id,
                    commitSha = commitSha,
                    taskId = tag,
                    receipt = receipt,
                    at = now,
                ))
            } catch (t: Throwable) {
                emitPollError("provenance WAL ${s.id}: ${t.message}", 0)
                drainFail(s, "provenance WAL failed: ${t.message?.take(200)}")
                continue  // per-arm: don't abandon the rest of the batch
            }
            receipted += close
        }

        // All per-arm tags, receipts, and identity records exist before any
        // card leaves the completion set. A failure above closes no card.
        conductor.recordDrains(receipted.map {
            JulesConductor.DrainRecord(
                sessionId = it.arm.session.id,
                commitSha = commitSha,
                rejects = conflictFiles().size,
            )
        })

        for ((arm, tag, prUrl) in receipted) {
            val (s, patchCid, _, branch) = arm
            _events.emit(FlywheelEvent.Drained(s.id, commitSha, tag))
            drainFailures.remove(s.id)
            println("[FLYWHEEL] PROVENANCE ${s.id.takeLast(6)} cid=${patchCid.value.take(16)} branch=${branch ?: "none"} tag=$tag")
            sendMergeReceipt(s.id, commitSha, tag, patchCid, branch, prUrl)
        }

        val drainBatchDurationMs = System.currentTimeMillis() - drainBatchStartMs
        if (receipted.isNotEmpty()) {
            DrainPerformanceTracker.recordDrainBatch(receipted.size, drainBatchDurationMs)
        }

        return DrainBatch(
            harvested = receipted.size,
            conflicts = conflictFiles(),
            panorama = panorama,
        )
    }

    /**
     * Branches without an observed producer artifact are not autonomous
     * mutation authority. Keep origin refs fresh for identity reconciliation;
     * exact Jules API/CAS artifacts enter through [drainExactArtifacts].
     */
    private suspend fun harvestOrphanBranches() {
        val fetch = git("fetch", "origin", "--prune")
        if (fetch.exitCode != 0) emitPollError("origin ref refresh failed: ${fetch.output.take(200)}", 0)
    }

    private fun activeCount(): Int = conductor.cards.values.count {
        it.snapshot.state !in TERMINAL_STATES && !it.drained
    }

    /** Find the GitHub branch or PR head carrying this Jules session id. */
    private suspend fun findSessionBranch(sessionId: String, preFetchedRefs: List<String>? = null): String? {
        val exactId = sessionId.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null

        val refsList = preFetchedRefs ?: run {
            val refs = git("for-each-ref", "--format=%(refname:short)", "refs/remotes/origin")
            if (refs.exitCode == 0) refs.output.lineSequence().map { it.trim() }.toList() else emptyList()
        }

        // Optional identity must carry the complete id as a delimiter-bounded
        // branch token. An ambiguous PR search is worse than null provenance.
        val token = Regex("(^|[^A-Za-z0-9])${Regex.escape(exactId)}([^A-Za-z0-9]|$)")
        return refsList.firstOrNull { ref ->
            ref.startsWith("origin/") && token.containsMatchIn(ref.removePrefix("origin/"))
        }
    }


    /**
     * Refresh origin/master and advance local master only by fast-forward.
     * Divergence or a dirty worktree is a review gate; this helper never creates
     * a merge commit or conflict markers.
     */
    private suspend fun synchronizeMain(): Boolean {
        if (!isWorkingTreeClean()) return false
        if (!isCanonicalMaster()) return false
        if (git("fetch", "origin", "master").exitCode != 0) return false
        // Origin freshness is allowed to move local master only when no local
        // continuity exists to merge. Divergence remains review-blocked.
        return git("merge", "--ff-only", "origin/master").exitCode == 0
    }

    /**
     * Post the settlement receipt back onto the Jules task itself: timestamp,
     * commit, tag, CAS patch id, branch and PR URL. The tag/commit/URL bond is
     * the idempotency anchor — git ancestry and tag existence already veto
     * duplicate settlement upstream, so this message fires once per landed
     * artifact, preventing loops and backwash. Best-effort: a send failure never
     * revokes provenance.
     */
    private suspend fun sendMergeReceipt(
        sessionId: String,
        commitSha: String,
        tag: String,
        patchCid: ContentId,
        branch: String?,
        prUrl: String?,
    ): Boolean {
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
            return true
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            emitPollError("receipt send $sessionId: ${t.message?.take(200)}", 0)
            return false
        }
    }

    /**
     * Immediate-mode publication adapter. Git is not causal authority: this
     * only projects the already validated CAS/WAL prefix to origin and proves
     * byte-for-byte master parity before terminal receipts become visible.
     */
    private suspend fun pushOriginParity(): Boolean {
        if (!isWorkingTreeClean() || !isCanonicalMaster()) return false
        val push = git("push", "origin", "HEAD:master")
        if (push.exitCode != 0) return false
        if (git("fetch", "origin", "master").exitCode != 0) return false
        val local = git("rev-parse", "HEAD")
        val remote = git("rev-parse", "origin/master")
        return local.exitCode == 0 && remote.exitCode == 0 &&
            local.output.trim() == remote.output.trim()
    }

    private suspend fun isCanonicalMaster(): Boolean {
        val branch = git("symbolic-ref", "--short", "HEAD")
        if (branch.exitCode != 0 || branch.output.trim() != "master") return false
        val remote = git("config", "--get", "remote.origin.url")
        if (remote.exitCode != 0) return false
        val remoteText = remote.output.trim()
        val cleaned = remoteText.removeSuffix(".git").removePrefix("git@github.com:")
        val normalized = if ("github.com/" in cleaned) cleaned.substringAfter("github.com/") else cleaned
            .trim('/')
        val expected = source.removePrefix("sources/github/").trim('/')
        return normalized == expected
    }

    /**
     * Push all locally drained commits to origin/master, then surface the
     * current local/remote state. Divergence remains review-blocked. PRs do
     * not block (Jules
     * pushes branches; PRs are operator surface, not gate surface).
     *
     * Returns true iff push succeeded and local HEAD matches origin/master.
     * A false return leaves the local continuity history intact for retry or
     * explicit review.
     */
    private suspend fun settlementBarrier(): Boolean {
        if (!pushOriginParity()) return false

        // Conductor is the authority for drain completeness; queue is intake only.
        // If conductor has no undrained COMPLETED sessions, the drain is closed.
        val undrainedCompleted = conductor.cards.values.count {
            it.snapshot.state in TERMINAL_STATES && !it.drained
        }
        if (undrainedCompleted != 0) {
            println("[FLYWHEEL] SETTLE-BLOCKED $undrainedCompleted COMPLETED session(s) not yet drained in conductor")
            return false
        }

        val unclaimedDrains = loadQueueIo().count { it.isUnclaimedDrain }
        if (unclaimedDrains != 0) {
            println("[FLYWHEEL] SETTLE-BLOCKED $unclaimedDrains queue drain(s) lack immutable receipts")
            return false
        }

        return true
    }

    /**
     * Move durable, upstream-settled sessions out of Jules' review inbox while
     * preserving their complete conversation and output history. This runs only
     * after [settlementBarrier] proves local HEAD == origin/master.
     */
    private suspend fun archiveSettledSessions(): Int {
        var archiveCount = 0
        val candidates = conductor.cards.values.filter { card ->
            (card.drained || card.causes.any { it is JulesCause.DrainApplied || it is JulesCause.PatchRejected }) &&
                card.snapshot.state in TERMINAL_STATES &&
                card.snapshot.sessionId in conductor.visibleSessionIds &&
                card.causes.none { it is JulesCause.SessionArchived }
        }.sortedBy { it.snapshot.capturedAt }.take(16)
        for (card in candidates) {
            val sessionId = card.snapshot.sessionId
            try {
                conductor.archive(sessionId)
                archiveCount++
                println("[FLYWHEEL] ARCHIVE ${sessionId.takeLast(6)} settled session preserved")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                classifyHttpError(t)
                emitPollError("archive session $sessionId: ${t.message?.take(200)}", 0)
            }
        }
        return archiveCount
    }

    private fun isImmutableReceipt(receipt: MergeReceipt): Boolean =
        receipt.producer != "retired" &&
            receipt.revision.isNotBlank() && !receipt.revision.startsWith("outbox-") &&
            receipt.versionTag.isNotBlank() && receipt.versionTag != "retired"

    private suspend fun loadQueueIo() = withContext(Dispatchers.IO) { store.loadQueue() }

    // ─── Dispatch helpers ───────────────────────────────────────────────────

    /** Maximum Jules submission prompt size in UTF-8 bytes. */
    private val SPEC_BYTE_LIMIT = 4000

    private fun dispatchTitle(workId: String, title: String): String =
        "[work:$workId] $title"

    /** Jules session states that no longer occupy a slot. */
    private val DRAINABLE_STATES = setOf("COMPLETED", "FINISHED")
    private val TERMINAL_STATES = DRAINABLE_STATES + setOf("FAILED", "CANCELLED")

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
        return spec.encodeToByteArray().decodeToString(0, cut).trim() +
            "\n\n[spec truncated at $SPEC_BYTE_LIMIT bytes]"
    }

    /**
     * Extract file paths mentioned in a task spec — the task's file scope.
     * Matches `src/...`, `doc/...`, `bin/...`, `build.gradle.kts` etc.
     */
    private val specFilePattern = Regex(
        """(?:src|doc|bin)/[A-Za-z0-9_./-]+|(?:build|settings)\.gradle\.kts""",
    )

    private fun extractSpecFiles(spec: String): Set<String> =
        specFilePattern.findAll(spec).map { it.value.trimEnd('.', ',', ':', ';', ')', ']') }.toSet()

     /**
      * Run a git command in [repoDir]. Unified shell — every git ProcessBuilder
      * site in FlywheelDriver goes through here. Prepends `"git"` so callers
      * write `git("commit", "-m", ...)` not `git("git", "commit", "-m", ...)`.
      * For non-git commands (gh, ./gradlew) use [shell].
     */
     private suspend fun git(vararg args: String): CommandResult = shell("git", *args)

     /** Run Git against an isolated temporary worktree. */
     private suspend fun gitIn(directory: File, vararg args: String): CommandResult =
         shellIn(directory, 30_000L, "git", *args)

     /**
      * Run an arbitrary command in [repoDir]. Use [git] for git subcommands.
      * The default 30-second timeout keeps git/GitHub prompts from parking the
      * wheel; drain-time Gradle gates pass their own bounded build window.
      */
     private suspend fun shell(vararg args: String): CommandResult = shell(30_000L, *args)

     private suspend fun shell(timeoutMs: Long, vararg args: String): CommandResult =
         shellIn(repoDir, timeoutMs, *args)

     private suspend fun shellIn(
         directory: File,
         timeoutMs: Long,
         vararg args: String,
     ): CommandResult = withContext(Dispatchers.IO) {
         try {
             val process = ProcessBuilder(*args)
                 .directory(directory)
                 .redirectErrorStream(true)
                 .start()
             val output = StringBuilder()
             val readerThread = Thread({
                 process.inputStream.bufferedReader().useLines { lines ->
                     lines.forEach { line ->
                         synchronized(output) { output.appendLine(line) }
                     }
                 }
             }, "flywheel-process-output").apply {
                 isDaemon = true
                 start()
             }
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
                } catch (_: java.util.concurrent.TimeoutException) {
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
                 readerThread.join(1_000L)
                 CommandResult(
                     1,
                     synchronized(output) { output.toString() } +
                         "timeout after ${timeoutMs}ms: ${args.joinToString(" ")}; surviving processes=$survivors",
                 )
             } else {
                 readerThread.join(5_000L)
                 CommandResult(process.exitValue(), synchronized(output) { output.toString() })
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


    private fun getKanbanBoard(): borg.trikeshed.kanban.KanbanBoard {
        return try { ForgeKanbanIngest.load("jim").board }
        catch (_: Throwable) { borg.trikeshed.kanban.KanbanBoard(
            id = borg.trikeshed.kanban.KanbanBoardId("flywheel"),
            name = "flywheel",
            columns = JulesLane.values().map { borg.trikeshed.kanban.KanbanColumn(
                borg.trikeshed.kanban.KanbanColumnId(it.columnName), it.columnName, it.order) },
            cards = emptyList(),
        ) }
    }

    /** Project the unified Forge×Jules board and render the saturation wheel. */
    fun renderSaturation(): String {
        val kanban = getKanbanBoard()
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
        val activities = client.activities(card.snapshot.sessionId)
        val inquiry = activities.asReversed()
            .firstOrNull { '?' in it.message }
            ?.message
            ?.split(Regex("\\n\\s*\\n"))
            ?.lastOrNull { '?' in it }
            ?.trim()
            ?: lastCause?.let { when (it) {
                is JulesCause.AgentMessaged -> it.excerpt.take(400)
                else -> null
            } } ?: return ""

        val conventions = buildString {
            appendLine("You are the GUIDE for the TrikeShed KMP project.")
            appendLine("Answer the final question-bearing paragraph only. State the boolean decision explicitly (yes/no or proceed/do-not-proceed), then give the minimal concrete condition or next action. Do not send a generic nudge.")
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
        val htx = htxElement
        if (htx == null) {
            println("[FLYWHEEL] WARN htxElement not attached — GUIDE offline, skipping ${card.snapshot.sessionId.takeLast(6)}")
            return ""
        }
        return try {
            // Call brain.chat() with HtxElement context so HTTP requests work
            withContext(Dispatchers.IO + htx) {
                b.chat(
                    messages = listOf(
                        "system" to conventions,
                        "user" to "Task title: $title\n\nInquiry from the coding agent:\n$inquiry",
                    ),
                    maxTokens = 400,
                    temperature = 0.2,
                )
            }
        } catch (t: Throwable) {
            println("[FLYWHEEL] BRAIN-ERROR ${card.snapshot.sessionId.takeLast(6)}: ${t.message}")
            ""
        }
    }

    data class ClaimedPatch(val commitSha: String, val receipt: MergeReceipt)

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

        val prUrl = try {
            fishPrUrl(sessionId, tag)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
        }

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
        val exactId = sessionId.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        // Probe 1: branch-on-origin.
        val ls = git("ls-remote", "origin", "refs/heads/jules-$exactId-*")
        if (ls.exitCode == 0) {
            for (line in ls.output.lineSequence()) {
                val parts = line.trim().split("\t")
                if (parts.size == 2) {
                    val sha = parts[0]
                    val ref = parts[1]
                    if (ref.startsWith("refs/heads/jules-$exactId-") && sha.length == 40) {
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
    private suspend fun originToHtmlUrl(remote: String, sha: String): String? {
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

    /** Parse unidiff headers (--- a/path, +++ b/path) to extract touched file paths. */
    private suspend fun parsePatchFiles(patch: String): List<String> = julesPatchFiles(patch)

    private suspend fun headSha(): String = git("rev-parse", "HEAD").output.trim()

    /** Subscribe a child coroutine to reactor events. Returns the subscriber's job. */
    fun subscribe(block: suspend (FlywheelEvent) -> Unit): Job =
        reactorScope.launch { events.collect { block(it) } }

    /** Cancel the supervisor; children propagate. Idempotent. */
    fun close() { parentJob.cancel() }

    /** Files still unmerged in Git's index; any result blocks automatic drain. */
    private suspend fun unmergedFiles(): List<String> =
        git("diff", "--name-only", "--diff-filter=U").output.trim().lines()
            .filter { it.isNotBlank() }

    /**
     * Detect unresolved source conflicts without modifying them. Both Git's
     * index and tracked marker content gate CAS preflight and settlement.
     */
    private suspend fun conflictFiles(): List<String> {
        val markerPaths = git("grep", "-l", "^<<<<<<< ", "--")
            .output.trim().lines().filter { it.isNotBlank() }
        val markerFiles = withContext(Dispatchers.IO) {
            markerPaths.filter { path ->
                File(repoDir, path).takeIf { it.isFile }?.useLines { lines ->
                    lines.any { it.startsWith("<<<<<<< ") && it != "<<<<<<< SEARCH" }
                } == true
            }
        }
        return (unmergedFiles() + markerFiles).distinct()
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
        val archived: Int = 0,
        /** Which [FlywheelPhase] the cycle last reached before returning (the priority manifest). */
        val phase: FlywheelPhase = FlywheelPhase.POLL,
        /** Conflict markers that review-block exact-artifact settlement. */
        val conflicts: List<String> = emptyList(),
        /** Panorama for QaLaguna: every arm in the current completion set. */
        val panorama: List<QaLaguna.SessionPanorama> = emptyList(),
        /** Jules 429 (rate-limit) responses seen this cycle. */
        val http429: Int = 0,
        /** Jules 5xx server-error responses seen this cycle. */
        val http5xx: Int = 0,
    )

}
