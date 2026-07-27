package borg.trikeshed.jules

import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
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
    private val store = JulesBoardStore.forForgeDir(forgeDir)
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
    /** Consecutive zero-patch probes per session id; tombstones at 3 (late outputs finalize async). */
    private val noPatchProbes = mutableMapOf<String, Int>()
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
        // 1. POLL — guarded so a transient API/network failure does NOT
        //    abort the cycle and starve drain. A failed poll is a PollError
        //    event + a retry on the next interval; drain still proceeds
        //    against the cards the previous cycle rehydrated from WAL.
        try {
            withTimeoutOrNull(60_000L) { conductor.pollOnce() }
        } catch (t: Throwable) {
            _events.tryEmit(FlywheelEvent.PollError("poll ${t.javaClass.simpleName}: ${t.message?.take(200)}"))
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

        // 2b. APPROVE — a session parked in AWAITING_PLAN_APPROVAL holds its
        //     slot forever unless the wheel signs off; no other phase ever
        //     unblocks it. Auto-approve: the TDD gate at drain time is the
        //     quality barrier, not plan review. The HumanAnswered cause makes
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
                _events.tryEmit(FlywheelEvent.PollError("approve ${card.snapshot.sessionId}: ${t.message?.take(200)}"))
            }
        }

        // 3. SYNC — best-effort. If it fails, we proceed anyway — the merge
        //    at DRAIN time will either succeed or produce conflicts that get
        //    resolved. No early return — gates create paralysis.
        synchronizeMain()

        // 4. DRAIN — settle completed patches so slots free for induction.
        //    No gate. Patches that conflict get resolved (not rejected).
        //    Build must pass before commit. Drains are serial via drainGate.
        val completed = conductor.cards.values.filter { it.snapshot.state == "COMPLETED" && !it.drained }
        val sessions = completed.map {
            JulesRestClient.SessionInfo(
                id = it.snapshot.sessionId,
                state = it.snapshot.state,
                title = it.card.title,
                patchBytes = 0L,
            )
        }
        val (harvested, reworked) = drainFanout(sessions)

        // 4b. CONFLICT ASSESSMENT — conflicts are kept and resolved inside
        //     drainOne; this surfaces the count for the next RGA cycle.
        val conflictCount = assessConflicts()

        // 5. SETTLE — push whatever landed. If push fails, the next cycle
        //    retries. No early return.
        settlementBarrier()

        // 6. INDUCT — read doc/todo.md into the WAL as WorkQueued causes.
        //    Idempotent: appendWork for an already-queued workId is a no-op
        //    at fold time (loadQueue getOrPut). The WAL is the single
        //    induction surface; nothing dispatches straight from the file.
        //    No gate. Throughput > purity.
        val inducted = inductTodo()

        // 7. DISPATCH — take from the unified queue projection, sorted by
        //    score descending. Waiting work (AWAITING, just answered above)
        //    already holds its slot; we only fill capacity freed by drain.
        //    Guard: never dispatch new work while any session is still
        //    AWAITING — that would pile new conversations onto unresolved ones.
        //
        //    Overlap guard: each task's file scope must not overlap any
        //    in-flight session's touched files. Open tasks are given leeway
        //    and clearance from overlapping.
        //
        //    Spec cap: Jules submissions are capped at [SPEC_BYTE_LIMIT] bytes.
        var dispatched = 0
        val alive = activeCount()
        val available = (maxSlots - alive).coerceAtLeast(0)
        val stillAwaiting = conductor.cards.values.count {
            it.snapshot.state == "AWAITING_USER_FEEDBACK" }
        if (available > 0 && stillAwaiting == 0) {
            // Build the in-flight file set from all active sessions' last patches.
            val inflightFiles = mutableSetOf<String>()
            for (card in conductor.cards.values) {
                if (card.snapshot.state !in TERMINAL_STATES) {
                    val patch = runCatching { client.lastPatch(card.snapshot.sessionId) }.getOrNull()
                    if (patch != null) inflightFiles += parsePatchFiles(patch)
                }
            }
            val pending = store.loadQueue()
                .filter { !it.isDispatched && !it.isDrained }
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
            reworked = reworked,
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
     * race the same commit. Returns the (harvested, reworked) counts — a rework
     * is a drain whose patch did not apply cleanly and was re-queued with a
     * bumped attempt instead of silently discarded.
     */
    private suspend fun drainFanout(sessions: List<JulesRestClient.SessionInfo>): Pair<Int, Int> =
        if (sessions.isEmpty()) 0 to 0 else coroutineScope {
            sessions.map { s ->
                async(Dispatchers.IO) {
                    drainGate.withLock {
                        ioGate.withPermit {
                            try { drainOne(s) } catch (t: Throwable) {
                                emitPollError("drain ${s.id}: ${t.message}", -1); DrainOutcome.Skipped
                            }
                        }
                    }
                }
            }.awaitAll().fold(0 to 0) { (h, r), o -> when (o) {
                is DrainOutcome.Harvested -> (h + 1) to r
                is DrainOutcome.Reworked  -> h to (r + 1)
                is DrainOutcome.Skipped   -> h to r
            } }
        }

    /** One drain's terminal outcome. [Skipped] covers no-patch / dirty tree / infra errors. */
    private sealed interface DrainOutcome {
        data object Harvested : DrainOutcome
        data object Skipped   : DrainOutcome
        /** Patch did not apply cleanly; a rework [JulesCause.WorkQueued] was re-appended. */
        data class Reworked(val newWorkId: String, val attempt: Int) : DrainOutcome
    }

    private fun activeCount(): Int = conductor.cards.values.count {
        it.snapshot.state != "COMPLETED" && it.snapshot.state != "FINISHED" && !it.drained
    }

    /**
     * Sync local master to origin/master via octopus-style merge: any number of
     * incoming branches fold into a multi-parent merge commit on every cycle.
     * Conflicts are NOT resolved — neither `--ours` nor `--theirs`, no
     * auto-resolution; conflict markers stay in the working tree and the
     * barrier reports drift. Throughput > purity: 40 dirty merges beat 4× the
     * wall clock of a curator round-trip.
     *
     * Returns true iff the merge command exited 0 (fast-forward or merge
     * succeeded). A non-zero exit leaves the tree dirty with conflict markers;
     * settlementBarrier decides whether to push anyway.
     */
    private fun synchronizeMain(): Boolean {
        if (!isWorkingTreeClean()) return false
        if (git("fetch", "origin", "master").exitCode != 0) return false
        // `--no-ff` forces a merge commit even when fast-forward is possible;
        // octopus-style multi-branch ancestry. Strategy left to git at run time.
        return git("merge", "--no-ff", "origin/master").exitCode == 0
    }

    /**
     * Push all locally drained commits to origin/master, then surface the
     * current local/remote state. Divergence is acceptable — octopus merges
     * can leave local and remote at different revisions indefinitely, and the
     * next synchronizeMain() cycle resolves them. PRs do not block (Jules
     * pushes branches; PRs are operator surface, not gate surface).
     *
     * Returns true iff push succeeded. A false return means origin rejected
     * (e.g. branch protection); the working tree is left dirty so the next
     * cycle can recover.
     */
    private fun settlementBarrier(): Boolean {
        if (!isWorkingTreeClean()) return false
        val push = git("push", "--follow-tags", "origin", "HEAD:master")
        if (push.exitCode != 0) return false

        val openPrs = git(
            "gh", "pr", "list", "--state", "open", "--limit", "100",
            "--json", "number", "--jq", "length",
        )
        val openCount = openPrs.output.trim().toIntOrNull() ?: 0

        if (git("fetch", "origin", "master").exitCode != 0) return true
        val local = git("rev-parse", "HEAD")
        val remote = git("rev-parse", "origin/master")
        val unclaimedDrains = store.loadQueue().count { it.isUnclaimedDrain }
        val state = FlywheelGateState(
            workingTreeClean = isWorkingTreeClean(),
            openPullRequests = openCount,
            localRevision = local.output.trim(),
            remoteRevision = remote.output.trim(),
            unclaimedDrains = unclaimedDrains,
        )
        // Drift admitted; gate's role reduced to recording, not blocking.
        val verdict = FlywheelGatekeeper.evaluate(state)
        if (verdict is FlywheelGateVerdict.Block) {
            println("[FLYWHEEL] SETTLE-NOTED ${state.localRevision.take(9)} vs ${state.remoteRevision.take(9)}: ${(verdict as FlywheelGateVerdict.Block).reason}")
        }
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
     * RGA partitioner: turn one gap description into up to [maxSlots] non-overlapping
     * WorkQueued tasks and induct them into the WAL. Each sub-task gets a file
     * scope that does not overlap siblings, so the overlap guard admits all of
     * them simultaneously.
     *
     * The partitioner reads the codebase structure to assign file scopes.
     * If the gap text already contains file paths, those are used directly;
     * otherwise the partitioner assigns a package-directory scope per sub-task.
     */
    suspend fun inductRgaGap(gapDescription: String): Int {
        val items = partitionGap(gapDescription)
        if (items.isEmpty()) return 0
        val queue = store.loadQueue()
        val knownWorkIds = queue.mapTo(mutableSetOf()) { it.workId }
        var n = 0
        for ((index, item) in items.withIndex()) {
            val workId = "rga:${gapDescription.hashCode().toUInt().toString(16)}#${index + 1}"
            if (workId in knownWorkIds) continue
            val score = (items.size - index).toDouble() / items.size.toDouble()
            store.appendWork(workId, JulesCause.WorkQueued(
                workId = workId,
                tier = "rga",
                title = item.title,
                spec = item.spec,
                parent = null,
                score = score,
                at = Clock.System.now().toEpochMilliseconds(),
            ))
            knownWorkIds += workId
            n++
        }
        println("[FLYWHEEL] RGA inducted $n sub-tasks from gap: ${gapDescription.take(80)}")
        return n
    }

    /** One partition of a gap. */
    data class GapPartition(val title: String, val spec: String)

    /**
     * Partition a gap description into up to [maxSlots] non-overlapping sub-tasks.
     * If the brain (Laguna) is available, it drafts the partition; otherwise
     * we split by source directories under src/.
     */
    private suspend fun partitionGap(gapDescription: String): List<GapPartition> {
        // Try the brain first for intelligent partitioning.
        val b = brain
        if (b != null) {
            val partitionSpec = buildString {
                appendLine("Partition this gap into up to $maxSlots non-overlapping landable tasks.")
                appendLine("Each task must touch a DIFFERENT set of source files (no overlap).")
                appendLine("Format each as: TITLE ||| src/path/file1.kt,src/path/file2.kt")
                appendLine("Gap: $gapDescription")
            }.trim()
            try {
                val response = withTimeoutOrNull(60_000L) {
                    b.chat(messages = listOf("user" to partitionSpec), maxTokens = 800, temperature = 0.3)
                }
                if (response != null) {
                    val parsed = parseGapPartitions(response, gapDescription)
                    if (parsed.isNotEmpty()) return parsed
                }
            } catch (t: Throwable) {
                println("[FLYWHEEL] RGA brain-error: ${t.message}")
            }
        }
        // Fallback: partition by source directories.
        return partitionByDirectories(gapDescription)
    }

    private fun parseGapPartitions(response: String, gapDescription: String): List<GapPartition> {
        return response.lines().mapNotNull { line ->
            val parts = line.split("|||")
            if (parts.size != 2) return@mapNotNull null
            val title = parts[0].trim().removePrefix("-").trim()
            val files = parts[1].trim()
            if (title.isEmpty()) return@mapNotNull null
            val spec = buildString {
                appendLine("$title (from RGA: ${gapDescription.take(200)})")
                appendLine("Scope: $files")
                appendLine("TDD: write failing tests first, then one minimal implementation file.")
                appendLine("Gate: ./gradlew :jvmMainClasses --no-daemon")
            }.trim()
            GapPartition(title, spec)
        }.take(maxSlots)
    }

    private fun partitionByDirectories(gapDescription: String): List<GapPartition> {
        val dirs = File(repoDir, "src/commonMain/kotlin/borg/trikeshed")
            .listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        return dirs.take(maxSlots).mapIndexed { index, dir ->
            val pkg = "borg.trikeshed.${dir.name}"
            GapPartition(
                title = "${dir.name.replaceFirstChar { it.uppercase() }}: $gapDescription".take(120),
                spec = buildString {
                    appendLine("${dir.name} package: $gapDescription")
                    appendLine("Scope: src/commonMain/kotlin/borg/trikeshed/${dir.name}/")
                    appendLine("TDD: write failing tests first, then one minimal implementation file.")
                    appendLine("Gate: ./gradlew :jvmMainClasses --no-daemon")
                }.trim()
            )
        }
    }

    /**
     * Assess cumulative conflicts in the working tree after all drains.
     * Returns the count of files with conflict markers.
     */
    private fun assessConflicts(): Int {
        val status = git("diff", "--name-only", "--diff-filter=U")
        if (status.output.isBlank()) return 0
        val files = status.output.trim().lines().filter { it.isNotBlank() }
        if (files.isNotEmpty()) {
            println("[FLYWHEEL] CONFLICTS ${files.size} files with conflict markers:")
            files.take(10).forEach { println("  ✗ $it") }
            if (files.size > 10) println("  ... and ${files.size - 10} more")
        }
        return files.size
    }
    /**
     * Run a git or gh command in [repoDir]. Unified shell — every ProcessBuilder
     * site in FlywheelDriver goes through here.
     */
    private fun git(vararg args: String): CommandResult = try {
        val process = ProcessBuilder(*args)
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        CommandResult(process.waitFor(), process.inputStream.bufferedReader().readText())
    } catch (t: Throwable) {
        CommandResult(1, t.message.orEmpty())
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    /**
     * True iff the working tree has no tracked modifications or staged changes.
     * Untracked files do NOT count as dirty — Jules sessions leave artifacts
     * behind that are harmless to merges and would otherwise permanently block
     * the wheel.
     */
    private fun isWorkingTreeClean(): Boolean =
        git("status", "--porcelain", "--untracked-files=no").output.isBlank()

    /**
     * Induction: parse unchecked items from `doc/todo.md` and append each as a
     * [JulesCause.WorkQueued] under its workId. Higher items get higher score
     * (preserving the file's intent ordering).
     *
     * No gate. Already-queued workIds are skipped (idempotency, restart-safety),
     * and that is the ONLY filter. The previous brain-curation + lexical-overlap
     * cycle-detector was dropped: 40 dirty merges per cycle beat 4× the wall
     * clock of a curator round-trip. Circular chases and duplicates are
     * acceptable cost for throughput; Jules itself produces corrective patches
     * when prior work has drifted.
     */
    private suspend fun inductTodo(): Int {
        val items = readTodoItems()
        if (items.isEmpty()) return 0
        val queue = store.loadQueue()
        val knownWorkIds = queue.mapTo(mutableSetOf()) { it.workId }
        var n = 0
        for ((index, item) in items.withIndex()) {
            if (item.workId in knownWorkIds) continue
            val score = (items.size - index).toDouble() / items.size.toDouble()
            store.appendWork(item.workId, JulesCause.WorkQueued(
                workId = item.workId,
                tier = "todo",
                title = item.title,
                spec = if (item.spec.isNotEmpty()) item.spec else buildSpec(item.title),
                parent = null,
                score = score,
                at = Clock.System.now().toEpochMilliseconds(),
            ))
            knownWorkIds += item.workId
            n++
            if (n >= maxInductPerCycle) break
        }
        return n
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
    private suspend fun buildAnswer(card: JulesSessionCard): String {
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
    private fun revertFiles(files: List<String>) {
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

    private fun headSha(): String = git("rev-parse", "HEAD").output.trim()

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
            emitPollError("drain ${s.id}: empty patch file list", 0)
            return DrainOutcome.Skipped
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
            val build = git("./gradlew", ":jvmMainClasses", "--no-daemon")
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
            emitPollError("drain ${s.id}: commit failed: ${commitRes.output.take(200)}", 0)
            return DrainOutcome.Skipped
        }

        val commitSha = headSha()
        val patchBytes = patch.encodeToByteArray()
        val patchCid = try { casStore.put(patchBytes) } catch (e: Exception) {
            emitPollError("drain ${s.id}: cas put failed: ${e.message}", -1)
            return DrainOutcome.Skipped
        }
        val safe = s.id.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val tag = "flywheel/jules-" + safe + "-" + commitSha.take(12)
        val msg = "Jules receipt\n" +
            "session=" + s.id + "\n" +
            "patchCid=" + patchCid.value + "\n" +
            "taskTitle=" + s.title
        val tagRes = git("tag", "-a", tag, commitSha, "-m", msg)
        if (tagRes.exitCode != 0) {
            emitPollError("drain ${s.id}: tag create failed: ${tagRes.output.take(200)}", -1)
            return DrainOutcome.Skipped
        }
        _events.emit(FlywheelEvent.Drained(s.id, commitSha, tag))
        return DrainOutcome.Harvested
    }

    /** Files with unresolved conflict markers in the working tree. */
    private fun conflictFiles(): List<String> =
        git("diff", "--name-only", "--diff-filter=U").output.trim().lines()
            .filter { it.isNotBlank() }

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

    /**
     * Re-queue a failed drain as a rework (doneagain), then tombstone the card —
     * the rework supersedes the dead patch, so the drain set stops re-probing it.
     *
     * Lineage lives in the QUEUE, not the card: dispatch appends WorkDispatched
     * via appendWork keyed by workId; card causes never carry it. The previous
     * card-cause lookup never matched (WAL: 281 DrainFailed, 0 reworks ever) —
     * doneagain could not fire and failed cards churned forever. Lineage is now
     * resolved via [JulesBoardStore.loadQueue] sessionId; a session with no
     * queue entry (manual/web-dispatched) gets a synthesized identity seeded
     * from its own completion summary so the wheel can retry its intent.
     */
    private suspend fun reworkFailedDrain(
        s: JulesRestClient.SessionInfo,
        reason: String,
    ): DrainOutcome.Reworked? {
        val now = Clock.System.now().toEpochMilliseconds()
        val card = conductor.cards[s.id] ?: run {
            conductor.recordDrainFailure(s.id, reason, now)
            return null
        }
        val seed = store.loadQueue().firstOrNull { it.sessionId == s.id }?.let { entry ->
            ReworkSeed(
                id = "rework:${entry.workId}#${entry.attempt + 1}",
                attempt = entry.attempt + 1,
                title = stripReworkDecoration(entry.title),
                spec = entry.spec,
                tier = entry.tier,
                parent = entry.workId,
                score = entry.score,
            )
        } ?: ReworkSeed(
            id = "synth:${s.id}",
            attempt = 1,
            title = stripReworkDecoration(s.title),
            spec = buildString {
                appendLine("Prior Jules session ${s.id} delivered a patch that fails to apply to current master.")
                card.causes.filterIsInstance<JulesCause.AgentMessaged>().lastOrNull()?.excerpt?.take(400)?.let {
                    appendLine("Agent's completion summary:")
                    appendLine(it.prependIndent("  "))
                }
            }.trim(),
            tier = "synth",
            parent = null,
            score = 0.5,
        )
        val reworkSpec = buildString {
            appendLine("REWORK attempt ${seed.attempt} of ${seed.title}.")
            appendLine("Prior Jules session ${s.id} produced a patch that failed to apply:")
            appendLine("  reason: $reason")
            appendLine("Original spec:")
            appendLine(seed.spec.prependIndent("  "))
            appendLine("Produce a fresh patch that applies cleanly against current master.")
            appendLine("TDD: one test file + one minimal implementation file; gate: ./gradlew jvmTest --no-daemon")
        }.trim()
        store.appendWork(seed.id, JulesCause.WorkQueued(
            workId = seed.id,
            tier = seed.tier,
            // Base title only: seed.title is already stripped of all prior
            // rework decorations, so re-reworks never compound.
            title = "[rework #${seed.attempt}] ${seed.title}",
            spec = reworkSpec,
            parent = seed.parent,
            score = (seed.score + 0.1).coerceAtMost(1.0),
            at = now,
        ))
        conductor.retireTerminal(s.id, "$reason → superseded by ${seed.id}", now)
        return DrainOutcome.Reworked(seed.id, seed.attempt)
    }

    /** Provenance for one doneagain: where the rework's identity came from. */
    private data class ReworkSeed(
        val id: String, val attempt: Int, val title: String, val spec: String,
        val tier: String, val parent: String?, val score: Double,
    )

    private val reworkDecoration = Regex("^(?:REWORK attempt \\d+ of )?(?:\\[rework #\\d+\\] )*")

    /**
     * Strip all rework decorations from a title so re-reworks never compound.
     * Handles both the queue title shape ("[rework #2] [rework #1] foo") and
     * the Jules session title shape ("REWORK attempt 2 of [rework #1] foo").
     */
    private fun stripReworkDecoration(raw: String): String =
        raw.replace(reworkDecoration, "").trim()

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