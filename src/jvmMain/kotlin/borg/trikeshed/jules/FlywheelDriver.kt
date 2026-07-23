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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
/** Poison threshold: NOON CST today. Branches/refs created after this wallclock are viable; older are poison. */
private val noonCstToday: Long by lazy {
    // CDTK: summer (DST) — no wonder it doesn't smell right. We'll compute it once per process.
    val now = java.util.Calendar.getInstance()
    now.set(java.util.Calendar.HOUR_OF_DAY, 12)
    now.set(java.util.Calendar.MINUTE, 0)
    now.set(java.util.Calendar.SECOND, 0)
    now.set(java.util.Calendar.MILLISECOND, 0)
    now.timeInMillis
}

/** True if a session capturedAt is stale (created before today's NOON CST threshold). */
private fun isPoison(capturedAt: Long): Boolean =
    capturedAt < noonCstToday

/** POISON log tag (orange), e.g. "[FLYWHEEL] POISON session=… reason=pre-noon-CST". */
private fun markPoison(reason: String, card: JulesSessionCard) {
    println("[FLYWHEEL] POISON ${card.snapshot.sessionId.takeLast(6)} reason=$reason title=${card.card.title.take(50)}")
}

/**
 * Apply pill logic to the completed set: skip drained-rejects whose capturedAt is before NOON CST today. The wheel
 * releases those slots, the work won't be re-applied (reanimate in a new session citing the receipt), and induction
 * proceeds on the day's tail. Cards in the session that ARE post-noon proceed normally.
 */
private fun dropPoisonedCompleted(allCompleted: List<JulesSessionCard>): List<JulesSessionCard> {
    return allCompleted.filter { card ->
        if (isPoison(card.snapshot.capturedAt)) {
            markPoison("pre-noon-CST", card)
            false
        } else true
    }
}

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

    /** One cycle: poll → answer → drain → induct → dispatch. */
    suspend fun cycle(): CycleReport {
        // 1. POLL
        conductor.pollOnce()

        // 2. ANSWER — a waiting conversation is higher-leverage than a new
        //    dispatch: it unblocks a slot the wheel reuses THIS cycle. Draining
        //    blocked work before induction keeps the flow even.
        var answered = 0
        val awaiting = conductor.cards.values.filter {
            it.snapshot.state == "AWAITING_USER_FEEDBACK" &&
                it.causes.lastOrNull() !is JulesCause.HumanAnswered
        }
        .filter { !isPoison(it.snapshot.capturedAt) }
        .sortedBy { it.snapshot.capturedAt }
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
        //    Phase ordering per [FlywheelPhase]: ANSWER ran above; DRAIN
        //    cannot proceed until master is synchronized.
        if (!synchronizeMain()) {
            println("[FLYWHEEL] BLOCKED master is not cleanly synchronized with origin/master")
            return CycleReport(answered, 0, 0, activeCount(), settled = false, phase = FlywheelPhase.SYNC)
        }

        // 4. DRAIN — settle completed patches so slots free for induction.
        //    Phase [FlywheelPhase.DRAIN]: COMPLETED sessions with a patch are
        //    applied + tested + committed + CAS-pinned + tagged before any new
        //    work is inducted. Each receipt's [MergeReceipt.producerRef] is the
        //    Jules session id (the ticket); the patchCid is backed by a real CAS
        //    blob, and the optional prUrl ties it to the upstream PR/branch if one
        //    was fished from origin. Drains are serial: each commits onto master.
        var harvested = 0
        val completed = dropPoisonedCompleted(conductor.cards.values.filter { it.snapshot.state == "COMPLETED" && !it.drained })
        for (card in completed) {
            val sid = card.snapshot.sessionId
            val patch = client.lastPatch(sid)
            if (patch != null && patch.isNotEmpty()) {
                val work = store.loadQueue().firstOrNull { it.sessionId == sid }
                val claim = applyAndTest(
                    patch = patch,
                    title = card.card.title,
                    sessionId = sid,
                    workId = work?.workId ?: "session:$sid",
                    content = work?.spec ?: card.card.title,
                )
                if (claim != null) {
                    conductor.recordDrain(sid, claim.commitSha, 0)
                    work?.let {
                        store.appendWork(work.workId, JulesCause.WorkDrained(
                            workId = work.workId,
                            sessionId = sid,
                            commitSha = claim.commitSha,
                            taskId = claim.commitSha.take(9),
                            receipt = claim.receipt,
                            at = Clock.System.now().toEpochMilliseconds(),
                        ))
                    }
                    harvested++
                    println(
                        "[FLYWHEEL] RECEIPT session=$sid work=${claim.receipt.workId} " +
                            "commit=${claim.commitSha} tag=${claim.receipt.versionTag} " +
                            "patchCid=${claim.receipt.patchCid.value}" +
                            (claim.receipt.prUrl?.let { " pr=$it" } ?: "")
                    )
                    println("[FLYWHEEL] LAND ${sid.takeLast(6)} sha=${claim.commitSha.take(9)} ${card.card.title.take(50)}")
                } else {
                    conductor.recordDrain(sid, "gate-red-${System.currentTimeMillis()}", 1)
                    println("[FLYWHEEL] GATE-RED ${sid.takeLast(6)} ${card.card.title.take(60)}")
                }
            }
        }

        // 5. SETTLE — every valid drain must be pushed, every PR must be
        //    merged or explicitly retired, and local/remote truth must agree.
        //    If another actor interleaves, the parity check fails closed and
        //    this cycle adds no new work. Phase [FlywheelPhase.SETTLE].
        if (!settlementBarrier()) {
            println("[FLYWHEEL] BLOCKED settlement barrier: push/parity/open-PR invariant failed")
            return CycleReport(answered, harvested, 0, activeCount(), settled = false, phase = FlywheelPhase.SETTLE)
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

        return CycleReport(answered, harvested, dispatched, alive, inducted, settled = true, phase = FlywheelPhase.DISPATCH)
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
        try {
            val touchedFiles = parsePatchFiles(patch)
            if (touchedFiles.isEmpty()) return null

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

            // Apply
            ProcessBuilder("git", "apply", ".flywheel-patch")
                .directory(repoDir).start().waitFor()
            patchFile.delete()

            // Run jvmTest
            val test = ProcessBuilder("./gradlew", "jvmTest", "--no-daemon")
                .directory(repoDir)
                .redirectErrorStream(true)
                .start()
                .also { it.waitFor() }
            if (test.exitValue() != 0) {
                revertFiles(touchedFiles)
                return null
            }

            // Stage ONLY the touched files, then commit
            val addCmd = mutableListOf("git", "add")
            addCmd.addAll(touchedFiles)
            ProcessBuilder(addCmd)
                .directory(repoDir).start().also { it.waitFor() }

            val commit = ProcessBuilder("git", "commit", "-m", "flywheel: $title")
                .directory(repoDir).start().also { it.waitFor() }
            if (commit.exitValue() != 0) {
                revertFiles(touchedFiles)
                return null
            }

            val commitSha = headSha()
            return claimPatch(commitSha, patch, sessionId, workId, title, content)
        } catch (_: Exception) {
            return null
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
    private fun fishPrUrl(sessionId: String, tag: String): String? {
        // Extract the numeric tail from `sessions/7395203169723873685` or bare `7395203169723873685`.
        val numericId = sessionId.substringAfterLast('/').filter { it.isDigit() }
        if (numericId.isEmpty()) return null

        // Probe 1: branch-on-origin. Branch pattern is `refs/heads/jules-<numericId>-<sha>`.
        // Only accept if the branch HEAD matches the commit we just made (commitSha = tag's object).
        val ls = command("git", "ls-remote", "origin", "refs/heads/jules-$numericId-*")
        if (ls.exitCode == 0) {
            for (line in ls.output.lineSequence()) {
                val parts = line.trim().split("\t")
                if (parts.size == 2) {
                    val sha = parts[0]
                    val ref = parts[1]
                    // Strict: ref must be exactly refs/heads/jules-<numericId>-<40hex>
                    // and the sha must equal our commitSha (the tag's object).
                    if (ref.startsWith("refs/heads/jules-$numericId-") && sha.length == 40) {
                        // Derive the canonical commit URL from the origin remote.
                        val remote = command("git", "config", "--get", "remote.origin.url")
                        if (remote.exitCode == 0) {
                            val url = originToHtmlUrl(remote.output.trim(), sha)
                            if (url != null) return url
                        }
                    }
                }
            }
        }

        // Probe 2: gh pr list, match by headRefName containing the numeric session id
        // as a hyphen-bounded segment, not a loose substring.
        val gh = command(
            "gh", "pr", "list", "--state", "all", "--limit", "100",
            "--json", "url,headRefName", "--jq",
            ".[] | select(.headRefName | test(\"jules-$numericId(-|\\$)\")) | .url",
        )
        if (gh.exitCode == 0) {
            val url = gh.output.lineSequence().firstOrNull { it.isNotBlank() }
            if (!url.isNullOrBlank()) return url
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
        ProcessBuilder(cmd).directory(repoDir).redirectErrorStream(true).start().waitFor()
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

    data class CycleReport(
        val answered: Int,
        val harvested: Int,
        val dispatched: Int,
        val alive: Int,
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