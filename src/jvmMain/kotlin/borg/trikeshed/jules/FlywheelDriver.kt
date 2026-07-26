/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.jules

import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.util.oroboros.FlywheelGatekeeper
import borg.trikeshed.util.oroboros.FlywheelGateState
import borg.trikeshed.util.oroboros.FlywheelGateVerdict
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.util.oroboros.MergeReceipt
import kotlinx.datetime.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
 * 4. DRAIN every COMPLETED session with a patch: apply → jvmTest → commit
 * 5. SETTLE: push master, require zero open PRs, verify local == origin/master
 * 6. INDUCT unchecked doc/todo.md items into the WAL as WorkQueued causes
 * 7. DISPATCH from the unified queue projection (score desc) until slots fill
 *
 * Steps 6+7 close the loop: induction feeds the queue, the queue feeds
 * dispatch, dispatch feeds Jules, Jules feeds drain. No ad-hoc reads of
 * doc/todo.md at dispatch time — the WAL is the only induction surface,
 * [loadQueue] is the only dispatch surface.
 *
 * Run with:
 *   ./gradlew jvmRun -PmainClass=borg.trikeshed.jules.FlywheelDriver
 */
class FlywheelDriver(
    private val apiKey: String,
    private val repoDir: File = File(System.getProperty("user.dir")),
    private val forgeDir: File = File(System.getProperty("user.home"), ".local/forge"),
    private val intervalMs: Long = 60_000L,
    private val maxSlots: Int = 15,
    private val maxInductPerCycle: Int = 1,
    private val source: String = "sources/github/jnorthrup/TrikeShed",
    /** CAS store backing the patch blobs cited by [MergeReceipt.patchCid]. Default <forgeDir>/cas (same path OroborosMain wires). */
    private val casStore: FileCasStore = FileCasStore(
        JvmFileOperations(),
        JvmFileOperations().resolvePath(forgeDir.absolutePath, "cas"),
    ),
) {
    private val client = JulesRestClient(apiKey)
    private val brain: BrainClient? = System.getenv("NVIDIA_API_KEY")?.let { BrainClient(it) }
    private val store = JulesBoardStore(JvmAppendWal(File(forgeDir, "jules-board.wal")))
    private val conductor = JulesConductor(
        client = client,
        headShaProvider = { headSha() },
        store = store,
        source = source,
    )
    // CCEK context: SupervisorJob + SharedFlow event bus + Semaphore-bounded concurrency.
    // The reactor fanout is structured: tick = poll → DRAIN+DISPATCH under ioGate.
    private val parentJob: Job = SupervisorJob()
    private val _events = MutableSharedFlow<FlywheelEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<FlywheelEvent> get() = _events.asSharedFlow()
    private val ioGate = Semaphore(permits = maxSlots)
    private val drainGate = Mutex()
    private val reactorScope = CoroutineScope(Dispatchers.IO + parentJob)

    /** A reactor lifecycle event. Fanout subscribers (TUI, reaper, drain observers) listen to [events]. */
    sealed interface FlywheelEvent {
        data class Polled(val alive: Int, val available: Int) : FlywheelEvent
        data class Drained(val sessionId: String, val sha: String, val tag: String) : FlywheelEvent
        data class Dispatched(val sessionId: String, val title: String) : FlywheelEvent
        data class DispatchFailed(val title: String, val reason: String) : FlywheelEvent
        data class PollError(val message: String) : FlywheelEvent
        data class UpstreamDrifted(val local: String, val remote: String) : FlywheelEvent
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
        // 1. POLL
        conductor.pollOnce()

        // 2. ANSWER — a waiting conversation is higher-leverage than a new
        //    dispatch: it unblocks a slot the wheel reuses THIS cycle. Draining
        //    blocked work before induction keeps the flow even.
        var answered = 0
        val awaiting = conductor.cards.values.filter {
            it.snapshot.state == "AWAITING_USER_FEEDBACK" &&
                it.causes.lastOrNull() !is JulesCause.HumanAnswered
        }.sortedBy { it.snapshot.capturedAt }
        for (card in awaiting) {
            val answer = buildAnswer(card)
            if (answer.isNotEmpty()) {
                conductor.answer(card.snapshot.sessionId, answer)
                answered++
                println("[FLYWHEEL] ANSWER ${card.snapshot.sessionId.takeLast(6)} ${card.card.title.take(60)}")
            }
        }

        // 3. SYNC — Jules conversations remain responsive even when Git is
        //    blocked, but no patch is applied against a stale master.
        if (!synchronizeMain()) {
            println("[FLYWHEEL] BLOCKED master is not cleanly synchronized with origin/master")
            return CycleReport(
            answered = answered,
            harvested = 0,
            dispatched = 0,
            alive = activeCount(),
            available = (maxSlots - activeCount()).coerceAtLeast(0),
            settled = false,
            phase = FlywheelPhase.SYNC,
        )
        }

        // 4. DRAIN — settle completed patches so slots free for induction.
        //    Phase [FlywheelPhase.DRAIN]: COMPLETED sessions with a patch are
        //    applied + tested + committed + CAS-pinned + tagged before any new
        //    work is inducted. Drains are serial via drainGate (Mutex) inside
        //    drainFanout so git tags are atomic; each commits onto master.
        //    Exclude sessions whose last cause is DrainFailed — a failing patch
        //    (context mismatch against a sibling drain, red tests) would spin
        //    the wheel on the same session forever otherwise. recordDrainFailed
        //    appends the cause without flipping drained (failed ≠ done).
        val completed = conductor.cards.values.filter {
            it.snapshot.state == "COMPLETED" && !it.drained &&
                it.causes.lastOrNull() !is JulesCause.DrainFailed
        }
        val sessions = completed.map {
            JulesRestClient.SessionInfo(
                id = it.snapshot.sessionId,
                state = it.snapshot.state,
                title = it.card.title,
                patchBytes = 0L,
            )
        }
        val harvested = drainFanout(sessions)

        // 5. SETTLE — every valid drain must be pushed, every PR must be
        //    merged or explicitly retired, and local/remote truth must agree.
        //    If another actor interleaves, the parity check fails closed and
        //    this cycle adds no new work. Phase [FlywheelPhase.SETTLE].
        if (!settlementBarrier()) {
            println("[FLYWHEEL] BLOCKED settlement barrier: push/parity/open-PR invariant failed")
            return CycleReport(
            answered = answered,
            harvested = harvested,
            dispatched = 0,
            alive = activeCount(),
            available = (maxSlots - activeCount()).coerceAtLeast(0),
            settled = false,
            phase = FlywheelPhase.SETTLE,
        )
        }

        // 6. INDUCT — read doc/todo.md into the WAL as WorkQueued causes.
        //    Idempotent: appendWork for an already-queued workId is a no-op
        //    at fold time (loadQueue getOrPut). The WAL is the single
        //    induction surface; nothing dispatches straight from the file.
        //    Phase [FlywheelPhase.INDUCT]: the brain curates each candidate
        //    against drained receipts + queued work to avoid circular chases
        //    (re-dispatching work a receipt already closed). See [curateTodo].
        val inducted = inductTodo()

        // 7. DISPATCH — take from the unified queue projection, sorted by
        //    score descending. Waiting work (AWAITING, just answered above)
        //    already holds its slot; we only fill capacity freed by drain.
        //    Guard: never dispatch new work while any session is still
        //    AWAITING — that would pile new conversations onto unresolved ones.
        //
        //    Phase [FlywheelPhase.DISPATCH]: fanout with max pipe = [maxSlots].
        //    Each dispatch is independent (its own Jules session + WAL append),
        //    so we launch them concurrently up to [available] in parallel.
        //    A failure in one session does not cancel siblings (async not
        //    supervisorScope); each appends its own WorkDispatched cause on
        //    success. The WAL append is thread-safe (synchronized in JvmAppendWal).
        var dispatched = 0
        val alive = activeCount()
        val available = (maxSlots - alive).coerceAtLeast(0)
        val stillAwaiting = conductor.cards.values.count {
            it.snapshot.state == "AWAITING_USER_FEEDBACK" }
        if (available > 0 && stillAwaiting == 0) {
            val pending = store.loadQueue()
                .filter { !it.isDispatched && !it.isDrained }
                .sortedByDescending { it.score }
                .take(available)
            dispatched = withContext(Dispatchers.IO) {
                coroutineScope {
                    val jobs = pending.map { entry ->
                        async(Dispatchers.IO) {
                            try {
                                val sessionId = client.createSession(
                                    prompt = entry.spec, title = entry.title, source = source)
                                store.appendWork(entry.workId, JulesCause.WorkDispatched(
                                    workId = entry.workId,
                                    sessionId = sessionId,
                                    attempt = entry.attempt + 1,
                                    at = Clock.System.now().toEpochMilliseconds(),
                                ))
                                println("[FLYWHEEL] DISPATCH ${entry.title.take(60)}")
                                1
                            } catch (t: Throwable) {
                                println("[FLYWHEEL] FAIL ${entry.title}: ${t.message}")
                                0
                            }
                        }
                    }
                    jobs.sumOf { it.await() }
                }
            }
        }

                return CycleReport(
            cycleMs = System.currentTimeMillis() - t0,
            answered = answered,
            harvested = harvested,
            dispatched = dispatched,
            alive = alive,
            available = (maxSlots - alive).coerceAtLeast(0),
            inducted = inducted,
            settled = true,
            phase = FlywheelPhase.DISPATCH,
        )
    }

    /**
     * Serial drain: each [drainOne] runs under [drainGate] (Mutex) then [ioGate]
     * (Semaphore). G14 guarantees git tag creation is atomic — no two drains
     * race the same commit. Returns the count of successfully drained sessions.
     */
    private suspend fun drainFanout(sessions: List<JulesRestClient.SessionInfo>): Int =
        if (sessions.isEmpty()) 0 else coroutineScope {
            sessions.map { s ->
                async(Dispatchers.IO) {
                    drainGate.withLock {
                        ioGate.withPermit {
                            try { drainOne(s) } catch (t: Throwable) { emitPollError("drain ${s.id}: ${t.message}", -1) }
                        }
                    }
                }
            }.awaitAll().sum()
        }

    private fun activeCount(): Int = conductor.cards.values.count {
        it.snapshot.state != "COMPLETED" && it.snapshot.state != "FINISHED" && !it.drained
    }

    /**
     * Fast-forward local master to the latest remote truth. Divergence and a
     * dirty tree both fail closed: the flywheel never guesses interleave order.
     */
    private fun synchronizeMain(): Boolean {
        if (command("git", "status", "--porcelain").output.isNotBlank()) return false
        if (command("git", "fetch", "origin", "master").exitCode != 0) return false
        return command("git", "merge", "--ff-only", "origin/master").exitCode == 0
    }

    /**
     * Push all locally drained commits, require the PR queue to be empty, then
     * fetch once more and prove exact local/remote parity. A PR can be merged
     * or explicitly closed as invalid; an OPEN PR means drain is incomplete.
     */
    private fun settlementBarrier(): Boolean {
        if (command("git", "status", "--porcelain").output.isNotBlank()) return false
        if (command("git", "push", "--follow-tags", "origin", "HEAD:master").exitCode != 0) return false

        val openPrs = command(
            "gh", "pr", "list", "--state", "open", "--limit", "100",
            "--json", "number", "--jq", "length",
        )
        if (openPrs.exitCode != 0) return false

        if (command("git", "fetch", "origin", "master").exitCode != 0) return false
        val local = command("git", "rev-parse", "HEAD")
        val remote = command("git", "rev-parse", "origin/master")
        if (local.exitCode != 0 || remote.exitCode != 0) return false
        val unclaimedDrains = store.loadQueue().count { it.isUnclaimedDrain }
        val state = FlywheelGateState(
            workingTreeClean = command("git", "status", "--porcelain").output.isBlank(),
            openPullRequests = openPrs.output.trim().toIntOrNull() ?: return false,
            localRevision = local.output.trim(),
            remoteRevision = remote.output.trim(),
            unclaimedDrains = unclaimedDrains,
        )
        return FlywheelGatekeeper.evaluate(state) is FlywheelGateVerdict.Admit
    }

    private fun command(vararg args: String): CommandResult {
        return try {
            val process = ProcessBuilder(*args)
                .directory(repoDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            CommandResult(process.waitFor(), output)
        } catch (t: Throwable) {
            CommandResult(1, t.message.orEmpty())
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    /**
     * Induction: parse unchecked items from `doc/todo.md` and append each as a
     * [JulesCause.WorkQueued] under its workId. Higher items get higher score
     * (preserving the file's intent ordering). Returns the count inducted.
     * Already-queued workIds are skipped before append, so this is restart-safe
     * without growing duplicate WAL records on every cycle.
     *
     * Phase [FlywheelPhase.INDUCT]: each candidate is first curated by
     * [curateTodo] against the known workIds + drained receipts to avoid
     * circular chases — re-dispatching work a receipt already closed is the
     * classic wheel-spin the agent guard is meant to stop. When the GUIDE brain
     * is offline the curator falls back to lexical-overlap cycle detection
     * ([FlywheelGatekeeper.closestReceipt] ≥ a meaningful overlap).
     */
    private suspend fun inductTodo(): Int {
        val todo = File(repoDir, "doc/todo.md")
        if (!todo.exists()) return 0
        val items = todo.readLines().filter { it.matches(Regex("^\\s*- \\[ \\].*")) }
        if (items.isEmpty()) return 0
        val queue = store.loadQueue()
        val knownWorkIds = queue.mapTo(mutableSetOf()) { it.workId }
        val drainedReceipts = queue.mapNotNull { it.receipt }
        val drainedTitles = drainedReceipts.map { it.lexicalMemory.title }
        var n = 0
        for ((index, item) in items.withIndex()) {
            val title = item.replace(Regex("^\\s*- \\[ \\]\\s*\\*\\*?|\\*\\*?$"), "").trim()
            if (title.isEmpty()) continue
            val workId = "todo:${title.hashCode().toUInt().toString(16)}"
            if (workId in knownWorkIds) continue
            if (!curateTodo(title, workId, knownWorkIds, drainedReceipts, drainedTitles)) {
                println("[FLYWHEEL] CURATE-SKIP ${title.take(60)} (duplicate/circular)")
                knownWorkIds += workId  // suppress repeat on next poll even when skipped
                if (n >= maxInductPerCycle) break
                continue
            }
            val score = (items.size - index).toDouble() / items.size.toDouble()
            val parentReceipt = FlywheelGatekeeper.closestReceipt(
                LexicalMemory(summary = title, title = title, content = title),
                drainedReceipts,
            )
            store.appendWork(workId, JulesCause.WorkQueued(
                workId = workId,
                tier = "todo",
                title = title,
                spec = buildSpec(title, parentReceipt),
                parent = parentReceipt?.workId,
                score = score,
                at = Clock.System.now().toEpochMilliseconds(),
            ))
            knownWorkIds += workId
            n++
            if (n >= maxInductPerCycle) break
        }
        return n
    }

    /**
     * Curate a todo candidate against the known queue + drained receipts.
     * Returns true to INDUCT (queue the work), false to SKIP (duplicate or
     * circular chase of already-settled work). The GUIDE brain decides with
     * a constrained prompt listing the queued titles + drained receipt titles;
     * its answer MUST be exactly `INDUCT` or `SKIP`. Without a brain, the
     * curator falls back to lexical-overlap detection: a candidate whose
     * [LexicalMemory] shares terms with a drained receipt is assumed to be a
     * circular chase and skipped (the wheel already closed that line).
     */
    private suspend fun curateTodo(
        title: String,
        workId: String,
        knownWorkIds: Set<String>,
        drainedReceipts: List<MergeReceipt>,
        drainedTitles: List<String>,
    ): Boolean {
        val candidate = LexicalMemory(summary = title, title = title, content = title)
        val overlap = drainedReceipts.maxOfOrNull { candidate.overlap(it.lexicalMemory) } ?: 0
        val b = brain
        if (b == null) {
            // No brain: lexical cycle-detector. overlap >= 2 shared terms ⇒ skip.
            if (overlap >= 2) return false
            return true
        }
        val prompt = buildString {
            appendLine("You are the CURATOR for the TrikeShed flywheel induction gate.")
            appendLine("Decide whether to queue a new work item, or skip it as a duplicate/circular chase.")
            appendLine("Already-queued or drained work titles (do NOT re-queue these):")
            drainedTitles.take(20).forEach { appendLine("  - $it") }
            if (drainedTitles.size > 20) appendLine("  ... (${drainedTitles.size} total)")
            appendLine("Candidate item to induct:")
            appendLine("  $title")
            appendLine("Reply with exactly one word: INDUCT or SKIP.")
            appendLine("INDUCT if this is genuinely new work.")
            appendLine("SKIP if it duplicates an existing queued/drained item or re-opens settled work (a circular chase).")
        }
        return try {
            val verdict = b.chat(
                messages = listOf("user" to prompt),
                maxTokens = 5,
                temperature = 0.0,
            ).trim().uppercase()
            verdict.startsWith("INDUCT")
        } catch (t: Throwable) {
            println("[FLYWHEEL] CURATOR-ERROR ${workId.take(12)}: ${t.message}; falling back to lexical")
            overlap < 2
        }
    }

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
            it.snapshot.state != "COMPLETED" && it.snapshot.state != "FINISHED" }
        return renderWheel(unified, aliveCount, maxSlots, intervalMs)
    }

    /** Build a project-conventions answer for an AWAITING session inquiry.
     *  Fires the [brain] (BrainClient → NVIDIA NIM Laguna XS 2.1) with
     *  conventions as the system message and the inquiry as the user message.
     *  Returns "" if no brain is configured (NVIDIA_API_KEY missing) — the
     *  caller skips the answer; never sends a template. */
    private fun buildAnswer(card: JulesSessionCard): String {
        val title = card.card.title
        val lastCause = card.causes.lastOrNull()
        val lastAct = client.activities(card.snapshot.sessionId).lastOrNull()
        val inquiry = lastAct?.excerpt?.take(400) ?: lastCause?.let { when (it) {
            is JulesCause.AgentMessaged -> it.excerpt.take(400)
            else -> null
        } } ?: return ""

        val conventions = buildString {
            appendLine("You are the GUIDE for the TrikeShed project (KMP, AGPLv3 2017).")
            appendLine("Answer coding-agent questions with concrete, decisive guidance (<200 words).")
            appendLine("Project conventions:")
            appendLine("  - domain logic goes in commonMain/kotlin/; platform adapters in jvmMain/jsMain/nativeMain")
            appendLine("  - use Series<T> over List<T> for read-only indexed data")
            appendLine("  - use Confix JSON (borg.trikeshed.parse.json.JsonSupport), not kotlinx-serialization-json")
            appendLine("  - test gate: ./gradlew jvmTest --no-daemon")
            appendLine("  - TDD: write failing test first, then one minimal implementation file")
            appendLine("  - one test file + one implementation file per task")
            appendLine("  - never use the word 'notion' in code, comments, or identifiers (trademark)")
            appendLine("  - never delete a working runner to replace with not-yet-built code")
        }

        val b = brain
        if (b == null) {
            println("[FLYWHEEL] WARN no NVIDIA_API_KEY — GUIDE offline, skipping ${card.snapshot.sessionId.takeLast(6)}")
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

    /** Build a fresh-session spec, optionally reanimating an immutable prior claim. */
    private fun buildSpec(title: String, parent: MergeReceipt? = null): String = buildString {
        appendLine("Write failing tests for $title.")
        appendLine("- TDD: one test file in the correct location")
        appendLine("- one minimal implementation file")
        appendLine("- gate: ./gradlew jvmTest --no-daemon")
        parent?.let {
            appendLine("Prior immutable merge receipt:")
            appendLine("- parentWorkId: ${it.workId}")
            appendLine("- producerRef: ${it.producerRef}")
            appendLine("- revision: ${it.revision}")
            appendLine("- versionTag: ${it.versionTag}")
            appendLine("- patchCid: ${it.patchCid.value}")
            appendLine("Use this as historical evidence in this NEW session; do not mutate the prior session.")
        }
    }.trim()

    /**
     * Apply a patch locally, run jvmTest, commit on green, then claim the exact
     * cumulative patch with a ContentId and annotated version tag.
     *
     * The gate is non-destructive to the surrounding working tree: only the files
     * the patch touches are applied, reverted on red, and committed on green. We
     * never `git add -A` or `git checkout .` — those would sweep uncommitted local
     * work into a flywheel commit or discard it entirely.
     */
    private fun applyAndTest(
        patch: String,
        title: String,
        sessionId: String,
        workId: String,
        content: String,
    ): ClaimedPatch? {
        val touchedFiles = parsePatchFiles(patch)
        if (touchedFiles.isEmpty()) return null
        var committed = false
        try {
            // Write patch file and check if it applies cleanly
            val patchFile = File(repoDir, ".flywheel-patch")
            patchFile.writeText(patch)

            val applyCheck = ProcessBuilder("git", "apply", "--check", ".flywheel-patch")
                .directory(repoDir)
                .redirectErrorStream(true)
                .start()
                .also { it.waitFor() }
            if (applyCheck.exitValue() != 0) {
                patchFile.delete()
                return null
            }

            // Apply — MUST check exit code. A silent apply failure (FS race,
            // permissions) left the tree clean but we proceeded to jvmTest on
            // the OLD code, committed nothing meaningful, and claimPatch minted
            // a receipt over a no-op. Revert via finally on any non-zero.
            val apply = ProcessBuilder("git", "apply", ".flywheel-patch")
                .directory(repoDir).redirectErrorStream(true).start()
            val applyExit = apply.waitFor()
            patchFile.delete()
            if (applyExit != 0) return null

            // Run jvmTest. Red → null; finally reverts. The tree must be clean
            // for the next drain (drainFanout holds the mutex, but a sibling
            // cycle's isWorkingTreeClean() check runs outside drainGate).
            val test = ProcessBuilder("./gradlew", "jvmTest", "--no-daemon")
                .directory(repoDir)
                .redirectErrorStream(true)
                .start()
                .also { it.waitFor() }
            if (test.exitValue() != 0) return null

            // Stage ONLY the touched files, then commit
            val addCmd = mutableListOf("git", "add")
            addCmd.addAll(touchedFiles)
            ProcessBuilder(addCmd)
                .directory(repoDir).start().also { it.waitFor() }

            val commit = ProcessBuilder("git", "commit", "-m", "flywheel: $title")
                .directory(repoDir).start().also { it.waitFor() }
            if (commit.exitValue() != 0) return null
            // The commit landed here. Do NOT set committed=true yet — if
            // headSha() or claimPatch fails below, finally's revertFiles is a
            // no-op (files already match HEAD), so the commit would orphan:
            // no tag, no receipt, no WorkDrained, and the next cycle's
            // `git apply --check` fails on the already-applied patch (wheel
            // spin). git reset --hard HEAD~1 is the only recovery. committed
            // =true only after claimPatch returns non-null.
            val commitSha = headSha()
            val claimed = claimPatch(commitSha, patch, sessionId, workId, title, content)
            if (claimed == null) {
                command("git", "reset", "--hard", "HEAD~1")
                println("[FLYWHEEL] rolled back unclaimed commit for ${sessionId.takeLast(6)} (claimPatch failed)")
                return null
            }
            committed = true
            return claimed
        } catch (e: Exception) {
            // Never swallow silently with a dirty tree. Revert whatever we
            // touched and surface the reason so the wheel logs a real cause
            // rather than a generic "applyAndTest failed" on the next cycle.
            println("[FLYWHEEL] applyAndTest exception for ${sessionId.takeLast(6)}: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            return null
        } finally {
            if (!committed) revertFiles(touchedFiles)
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
    internal fun claimPatch(
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
        if (command("git", "tag", "-a", tag, commitSha, "-m", tagMessage).exitCode != 0) {
            return null
        }

        // Best-effort PR/branch URL fishing. The Jules session id is the ticket;
        // this url is the optional upstream surface that ties the receipt to the
        // human-visible PR or branch. null is a valid result — the receipt stands
        // with or without a PR (Jules pushes branches, not PRs).
        val prUrl = try {
            fishPrUrl(sessionId, tag)
        } catch (t: Throwable) {
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

    private var todoFileLastModified: Long = 0L
    private var cachedTodoItems: List<TodoItem> = emptyList()

    /**
     * Fish an optional PR/branch URL that ties this receipt to the upstream
     * merge surface. Probes:
     *   1. `git ls-remote origin 'refs/heads/jules-<numericSessionId>-*'`
     *      — Jules pushes branches to origin (per jules-cli-branch-delivery-probe),
     *      and a matching ref proves delivery.
     *   2. `gh pr list --json url,headRefName` — if a PR was opened with a
     *      headRef containing the numeric session id, its url is canonical.
     * Both probes swallow errors and return null on no match; the receipt is
     * still provenance-complete via [MergeReceipt.patchCid] + [revision].
     */
    private fun readTodoItems(): List<TodoItem> {
        val todoFile = File(repoDir, "doc/todo.md")
        if (!todoFile.exists()) return emptyList()

        val currentMtime = todoFile.lastModified()
        if (currentMtime != todoFileLastModified || cachedTodoItems.isEmpty()) {
            println("[FLYWHEEL] todo cache miss, re-parsing")
            todoFileLastModified = currentMtime
            cachedTodoItems = parseTheFile(todoFile)
        }
        return cachedTodoItems
    }

    private fun parseTheFile(todoFile: File): List<TodoItem> {
        val titleRe = Regex("^\\s*- \\[ \\]\\s*\\*\\*?(.+?)\\*\\*?\\s*$")
        val items = mutableListOf<TodoItem>()
        val lines = todoFile.readLines()
        var i = 0
        while (i < lines.size) {
            val m = titleRe.find(lines[i])
            if (m == null) { i++; continue }
            val title = m.groupValues[1].trim()
            val body = StringBuilder()
            var j = i + 1
            while (j < lines.size) {
                val l = lines[j]
                // Bullet ends at the next bullet, header, or blank-then-non-indented line.
                if (l.isBlank()) { j++; continue }
                if (!l.startsWith(" ") && !l.startsWith("\t")) break
                if (titleRe.containsMatchIn(l)) break
                body.append(l.trim()).append(' ')
                j++
            }
            val spec = body.toString().trim()
            val workId = "todo:${title.hashCode().toUInt().toString(16)}"
            items.add(TodoItem(workId, title, spec))
            i = j
        }
        return items
    }

    /** One unchecked doc/todo.md item. */
    data class TodoItem(val workId: String, val title: String, val spec: String)

    /**
     * Fish an optional PR/branch URL tying this receipt to the upstream merge
     * surface. Probes: (1) `git ls-remote origin 'refs/heads/jules-<numericId>-*'`,
     * (2) `gh pr list --json url,headRefName`. Both swallow errors and return
     * null on no match; the receipt is provenance-complete via [MergeReceipt.patchCid]
     * + [revision]. Jules pushes branches (not PRs); null is valid for direct merges.
     */
    private fun fishPrUrl(sessionId: String, tag: String): String? {
        val numericId = sessionId.substringAfterLast('/').filter { it.isDigit() }
        if (numericId.isEmpty()) return null
        // Probe 1: branch-on-origin.
        val ls = command("git", "ls-remote", "origin", "refs/heads/jules-$numericId-*")
        if (ls.exitCode == 0) {
            for (line in ls.output.lineSequence()) {
                val parts = line.trim().split("\t")
                if (parts.size == 2) {
                    val sha = parts[0]
                    val ref = parts[1]
                    if (ref.startsWith("refs/heads/jules-$numericId-") && sha.length == 40) {
                        val remote = command("git", "config", "--get", "remote.origin.url")
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
    private fun revertFiles(files: List<String>) {
        val cmd = mutableListOf("git", "checkout", "HEAD", "--")
        cmd.addAll(files)
        command(*cmd.toTypedArray())
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

    private fun headSha(): String {
        val proc = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(repoDir).redirectErrorStream(true).start()
        return proc.inputStream.bufferedReader().readText().trim()
    }

    /** Subscribe a child coroutine to reactor events. Returns the subscriber's job. */
    fun subscribe(block: suspend (FlywheelEvent) -> Unit): Job =
        reactorScope.launch { events.collect { block(it) } }

    /** Cancel the supervisor; children propagate. Idempotent. */
    fun close() { parentJob.cancel() }

    private fun isWorkingTreeClean(): Boolean =
        command("git", "status", "--porcelain").output.isBlank()

    /**
     * Drain a single completed session: apply patch, commit, CAS-pin, tag.
     * Serial inside [drainFanout] via [drainGate]. Returns 1 on success, 0 on
     * soft skip (no patch / dirty tree / apply-check fail), -1 on hard error.
     */
    private suspend fun drainOne(s: JulesRestClient.SessionInfo): Int {
        val patch = client.lastPatch(s.id)
        if (patch == null) { return drainFail(s, "no patch from lastPatch()") }
        if (patch.isBlank()) { return drainFail(s, "blank patch") }
        if (!isWorkingTreeClean()) { return drainFail(s, "trunk dirty, skipping") }
        // workId bond: sessionId→workId projection from loadQueue (set at
        // dispatch by the WorkDispatched append). Sessions with no matching
        // queue entry (tasks created outside the wheel) fall back to a
        // session-derived id so they still get a WorkDrained cause + receipt.
        // The queue projection also carries the ORIGINAL todo bullet title
        // (set at induction as WorkQueued.title), which is what curateTodo's
        // lexical cycle detector compares against new candidates. s.title is
        // the REST-returned session title from Jules, which often diverges
        // from the intent ("Write failing tests for Fix X" vs "Fix X"), so
        // prefer the queue title for the receipt when available.
        val queueEntry = store.loadQueue().firstOrNull { it.sessionId == s.id }
        val workId = queueEntry?.workId ?: "session:${s.id.replace(Regex("[^A-Za-z0-9._-]"), "-")}"
        val intentTitle = queueEntry?.title?.takeIf { it.isNotBlank() } ?: s.title
        // Idempotent recovery. If a prior cycle's WorkDrained cause is already
        // in the WAL with a non-null receipt, this session was settled — the
        // patch landed and the receipt stands. Without this short-circuit, a
        // daemon crash BETWEEN the WorkDrained append and the in-memory
        // conductor.recordDrain() leaves the rehydrated card with drained=false;
        // the next cycle re-enters drainOne, hits `git apply --check` failure
        // (patch already applied), and classifies a settled session as
        // DrainFailed — poisoning the card against any future legitimate
        // retry. Return 1 silently so the wheel moves on.
        if (queueEntry?.isDrained == true && queueEntry.receipt != null) {
            return 1
        }
        // applyAndTest does the full gate: apply-check → apply → jvmTest →
        // revert on red → stage touched files → commit → claimPatch (CAS put +
        // git tag + MergeReceipt). The old drainOne skipped commit + jvmTest,
        // so headSha() was the PRE-patch commit and the tag pointed at the
        // wrong revision — the patch sat uncommitted, the next cycle's
        // dirty-tree check failed, and nothing ever merged.
        val claimed = applyAndTest(patch, intentTitle, s.id, workId, intentTitle)
        if (claimed == null) { return drainFail(s, "applyAndTest failed (apply/test/commit)") }
        val commitSha = claimed.commitSha
        val receipt = claimed.receipt
        // Close the loop: append WorkDrained so loadQueue() marks the entry
        // isDrained with a receipt, and recordDrain so the session card's
        // drained flag flips (laneFor → DONE, no re-drain next cycle).
        store.appendWork(workId, JulesCause.WorkDrained(
            workId = workId,
            sessionId = s.id,
            commitSha = commitSha,
            taskId = receipt.versionTag,
            receipt = receipt,
            at = Clock.System.now().toEpochMilliseconds(),
        ))
        conductor.recordDrain(s.id, commitSha, rejects = 0)
        if (markTodoChecked(workId, intentTitle)) {
            // markTodoChecked wrote doc/todo.md; commit it so the tree stays
            // clean for the next sibling drain and for settlementBarrier's
            // `git status --porcelain` check. Without this, the first
            // successful drain poisons the tree: drainFanout's next drainOne
            // hits isWorkingTreeClean()==false (false-positive DrainFailed),
            // and settlementBarrier never admits (dirty tree → no push →
            // unclaimedDrains never clears → wheel blocks on SYNC next cycle).
            command("git", "add", "doc/todo.md")
            command("git", "commit", "-m", "flywheel: mark todo checked — $workId")
        }
        _events.emit(FlywheelEvent.Drained(s.id, commitSha, receipt.versionTag))
        return 1
    }

    /**
     * Close the loop back to the research surface: flip the matching
     * `doc/todo.md` bullet from `- [ ]` to `- [x]` when a drain lands. The workId
     * bond is `todo:${title.hashCode()...}` (same derivation as `inductTodo`),
     * so we re-derive it per unchecked line and match against the drained
     * workId. Without this, the LAND → research feedback arrow is hollow —
     * inductTodo re-parses the same unchecked item every cycle, the curator's
     * lexical-overlap detector is the only thing stopping re-induction (fragile:
     * two shared terms skips genuinely-new-but-related work), and doc/todo.md
     * accumulates stale unchecked items forever while the wheel has already
     * drained them. Returns true if the file was modified (caller commits it
     * so the tree stays clean); false for a no-op (missing/mismatched). Best
     * -effort and idempotent: an already-checked line is left alone.
     */
    private fun markTodoChecked(workId: String, title: String): Boolean {
        if (!workId.startsWith("todo:")) return false
        val todo = File(repoDir, "doc/todo.md")
        if (!todo.exists()) return false
        val lines = todo.readLines()
        var changed = false
        val out = lines.map { line ->
            val m = Regex("^\\s*- \\[ \\]\\s*\\*\\*?(.+?)\\*\\*?\\s*$").find(line) ?: return@map line
            val lineTitle = m.groupValues[1].trim()
            val lineWorkId = "todo:${lineTitle.hashCode().toUInt().toString(16)}"
            if (lineWorkId != workId) return@map line
            changed = true
            line.replaceFirst("- [ ]", "- [x]")
        }
        if (changed) {
            todo.writeText(out.joinToString("\n") + "\n")
            println("[FLYWHEEL] MARK-CHECKED ${title.take(60)} in doc/todo.md")
        }
        return changed
    }

    /**
     * Record a drain failure on the session card, then emit + return 0. The
     * [JulesCause.DrainFailed] cause is what the DRAIN filter excludes on —
     * without it the wheel re-attempts the same COMPLETED+undrained session
     * every cycle (apply --check fails against the same conflicting patch each
     * time) and spins on DRAIN forever. A failed drain is NOT done: the card's
     * drained flag stays false, so laneFor keeps it in CAUSAL_READY, not DONE.
     */
    private suspend fun drainFail(s: JulesRestClient.SessionInfo, reason: String): Int {
        conductor.recordDrainFailed(s.id, "drain ${s.id}: $reason")
        return emitPollError("drain ${s.id}: $reason", 0)
    }

    data class CycleReport(
        /** Wall-clock duration of the cycle in milliseconds. */
        val cycleMs: Long = 0,
        val answered: Int = 0,
        val harvested: Int = 0,
        val dispatched: Int = 0,
        val alive: Int = 0,
        val available: Int = 0,
        val inducted: Int = 0,
        val settled: Boolean = false,
        /** Which [FlywheelPhase] the cycle last reached before returning (the priority manifest). */
        val phase: FlywheelPhase = FlywheelPhase.POLL,
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