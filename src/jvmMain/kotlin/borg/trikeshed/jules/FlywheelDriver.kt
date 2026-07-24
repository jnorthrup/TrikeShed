package borg.trikeshed.jules

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.util.oroboros.MergeReceipt
import borg.trikeshed.utils.kanban.JulesBoardStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import java.io.File

/**
 * TrikeShed flywheel as a CCEK reactor:
 *   - **C**ontext: a `CoroutineScope` with a `SupervisorJob` + structured fanout via `MutableSharedFlow<FlywheelEvent>`.
 *   - **C**ontext-**E**lement: each subscriber (TUI, history reaper, settlement drain) registers a child job.
 *   - **K**ey: a single routing identity (`FlywheelKey`) selects this element from a `coroutineContext`.
 *
 * Each tick is concurrent structured concurrency — `poll`, `drain`, `dispatch` are all
 * `async`-launched, bounded by an `ioGate` semaphore (`maxSlots` permits). I/O done in
 * `Dispatchers.IO` so the worker doesn't stall on a single git subprocess.
 *
 * State is the WAL (`~/.local/forge/jules-board.wal`) + the git ref (`origin/master`).
 * There are no counters or timelines in memory — anything you want to know is asked.
 *
 * Run: `./gradlew jvmRun -PmainClass=borg.trikeshed.jules.FlywheelDriver --args="--watch"`
 */
class FlywheelDriver(
    private val apiKey: String,
    private val repoDir: File = File(System.getProperty("user.dir")),
    private val forgeDir: File = File(System.getProperty("user.home"), ".local/forge"),
    private val intervalMs: Long = 30_000L,
    private val maxSlots: Int = 15,
    private val source: String = "sources/github/jnorthrup/TrikeShed",
    private val gateCommand: List<String> = listOf("./gradlew", "jvmTest", "--no-daemon"),
    private val workDrafter: ((String) -> String)? = System.getenv("NVIDIA_API_KEY")
        ?.let { key -> BrainClient(key)::chatWorkDraft },
    private val client: JulesRestClient = JulesRestClient(apiKey),
    private val tendResponder: ((String) -> String)? = System.getenv("NVIDIA_API_KEY")
        ?.let { key -> BrainClient(key)::chatTend },
    private val queueStore: JulesBoardStore? = System.getenv("FLYWHEEL_QUEUE_WAL")
        ?.takeIf { it.isNotBlank() }
        ?.let { path -> JulesBoardStore(JvmAppendWal(java.io.File(path))) },
    internal var sessionCreator: (String, String) -> String = { spec, title ->
        client.createSession(prompt = spec, title = title, source = source)
    },
) {
    private val reportedSpecMissing = mutableSetOf<String>()
    private val tendedActivities = mutableSetOf<String>()

    private val casStore = FileCasStore(
        JvmFileOperations(),
        JvmFileOperations().resolvePath(forgeDir.absolutePath, "cas"),
    )
    // CCEK context: SupervisorJob + SharedFlow event bus + Semaphore-bounded concurrency.
    // The reactor fanout is structured: tick = poll → DRAIN+DISPATCH under ioGate.
    private val parentJob: Job = SupervisorJob()
    private val _events = MutableSharedFlow<FlywheelEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<FlywheelEvent> get() = _events.asSharedFlow()
    private val ioGate = Semaphore(permits = maxSlots)
    private val reactorScope = CoroutineScope(Dispatchers.IO + parentJob)

    /** A reactor lifecycle event. Fanout subscribers (TUI, reaper, drain observers) listen to [events]. */
    sealed interface FlywheelEvent {
        data class Polled(val alive: Int, val available: Int) : FlywheelEvent
        data class Drained(val sessionId: String, val sha: String, val tag: String) : FlywheelEvent
        data class Dispatched(val sessionId: String, val title: String) : FlywheelEvent
        data class DispatchFailed(val title: String, val reason: String) : FlywheelEvent
        data class PollError(val message: String) : FlywheelEvent
        data class SpecMissing(val title: String) : FlywheelEvent
    }

    internal data class RankedWork(
        val workId: String,
        val parent: String?,
        val score: Double,
        val queuedAt: Long,
    )

    internal data class DraftedWork(
        val workId: String,
        val title: String,
        val spec: String,
        val parent: String?,
        val score: Double,
        val queuedAt: Long,
    ) {
        val ranked: RankedWork get() = RankedWork(workId, parent, score, queuedAt)
    }

    enum class SessionAction { HARVEST, TEND, STUCK, FAILED, IGNORE }

    internal data class SessionVerdict(
        val sessionId: String,
        val action: SessionAction,
        val lastActivityAt: Long?,
    )

    /**
     * One-pass classification: every polled session is reduced to the single action
     * the cycle should take against it. Replaces the implicit "if COMPLETED drain,
     * if AWAITING answer, otherwise ignore" gate with a verdict-driven cycle.
     */
    internal suspend fun classifySessions(
        sessions: List<JulesRestClient.SessionInfo>,
        nowMs: Long,
        stuckThresholdMs: Long,
    ): List<SessionVerdict> = sessions.map { s ->
        when (s.state) {
            "COMPLETED" -> SessionVerdict(s.id, SessionAction.HARVEST, null)
            "AWAITING_USER_FEEDBACK", "AWAITING_PLAN_APPROVAL" ->
                SessionVerdict(s.id, SessionAction.TEND, null)
            "FAILED" -> SessionVerdict(s.id, SessionAction.FAILED, null)
            "IN_PROGRESS", "QUEUED", "PLANNING" -> {
                val activities = runCatching { client.activities(s.id) }.getOrNull().orEmpty()
                val lastAt = activities.lastOrNull()?.let { act ->
                    runCatching { java.time.OffsetDateTime.parse(act.createTime).toInstant().toEpochMilli() }
                        .getOrNull()
                }
                val isFresh = lastAt != null && nowMs - lastAt < stuckThresholdMs
                if (isFresh) SessionVerdict(s.id, SessionAction.IGNORE, lastAt)
                else SessionVerdict(s.id, SessionAction.STUCK, lastAt)
            }
            else -> SessionVerdict(s.id, SessionAction.IGNORE, null)
        }
    }

    internal fun draftWorkFromResearch(evidence: String): List<DraftedWork> {
        val draft = workDrafter?.invoke(buildString {
            appendLine("Create a hierarchical queue of TDD-first work from CURRENT repository evidence.")
            appendLine("Each output row must be: WORK<TAB>parent-index-or--<TAB>score<TAB>title<TAB>spec")
            appendLine("The spec must name an exact Test: path, implementation path, assertions, and run ./gradlew verification.")
            appendLine("Evidence:")
            append(evidence)
        }) ?: return emptyList()
        val parsed = mutableListOf<DraftedWork>()
        draft.lineSequence().forEachIndexed { index, line ->
            val fields = line.split('\t', limit = 5)
            if (fields.size != 5 || fields[0] != "WORK") return@forEachIndexed
            val score = fields[2].toDoubleOrNull() ?: return@forEachIndexed
            val title = fields[3].trim()
            val spec = fields[4].trim()
            if (title.isEmpty() || "Test:" !in spec || "run ./gradlew" !in spec) return@forEachIndexed
            val parentIndex = fields[1].toIntOrNull()
            val parent = parentIndex?.let { parsed.getOrNull(it)?.workId }
            val workId = "research:${title.hashCode().toUInt().toString(16)}"
            parsed += DraftedWork(workId, title, spec, parent, score, index.toLong())
        }
        return parsed
    }

    /** One reactor tick. POLL → fanout DRAIN + DISPATCH under ioGate.
     *
     *  Strict invariant: DRAIN must complete and yield a clean trunk before DISPATCH
     *  fires any new task. Trunk-dirty = "a prior drain's patch sits uncommitted", so
     *  we never `client.createSession` while any drain ticket is still in-flight.
     *  That's the canonical `α`-on-launch rule: at launch the task pipe is full and the
     *  trunk has nothing staged but persisted (CAS) state.
     */
    suspend fun cycle(): String = coroutineScope {
        val pollStart = System.currentTimeMillis()
        val pollResult = runCatching {
            withTimeoutOrNull(20_000) {
                withContext(Dispatchers.IO) { client.listSessions(source) }
            }
        }
        val sessions = pollResult.getOrNull() ?: emptyList()
        val pollErr = pollResult.exceptionOrNull()
        val alive = sessions.count { it.state != "COMPLETED" && it.state != "FINISHED" }
        val available = (maxSlots - alive).coerceAtLeast(0)
        _events.emit(FlywheelEvent.Polled(alive, available))
        if (pollErr != null) {
            _events.emit(FlywheelEvent.PollError("listSessions: ${pollErr.javaClass.simpleName}: ${pollErr.message?.take(200)}"))
        } else if (sessions.isEmpty()) {
            _events.emit(FlywheelEvent.PollError("listSessions: returned empty for source=$source (alive=$alive available=$available)"))
        }
        // Tend blocked sessions before harvesting or dispatching new work.
        val tended = tendSessions(sessions)
        val nowMs = System.currentTimeMillis()
        val stuckThresholdMs = (intervalMs * 20).coerceAtLeast(60_000L)
        val verdicts = classifySessions(sessions, nowMs, stuckThresholdMs)
        val stuckCount = verdicts.count { it.action == SessionAction.STUCK }
        val failedCount = verdicts.count { it.action == SessionAction.FAILED }
        if (stuckCount > 0 || failedCount > 0) {
            _events.emit(FlywheelEvent.PollError(
                "stuck=$stuckCount failed=$failedCount (threshold=${stuckThresholdMs}ms)"
            ))
        }
        // DRAIN runs first under ioGate (serial) so trunk lands clean before DISPATCH.
        val completed = sessions.filter { it.state == "COMPLETED" }.take(maxSlots)
        val drained = drainFanout(completed)
        val trunkClean = isWorkingTreeClean()
        // Dispatch from doc/todo.md whenever we have headroom, regardless of whether
        // listSessions came back empty. Alpha-on-launch invariant: top the pipe up
        // to maxSlots from the project todo on every cycle.
        val allItems = if (trunkClean && available > 0) readTodoItems() else emptyList()
        val skipped = allItems.count { it.spec.isBlank() }
        allItems.filter { it.spec.isBlank() }.forEach {
            if (it.title.isNotBlank() && reportedSpecMissing.add(it.title)) {
                _events.tryEmit(FlywheelEvent.SpecMissing(it.title))
            }
        }
        val items = allItems.filter { it.spec.isNotBlank() }.take(available)
        val dispatched = if (trunkClean && available > 0) dispatchFanout(items) else 0
        if (pollErr == null && sessions.isEmpty()) {
            "poll-empty alive=0 available=$available trunk=${if (trunkClean) "clean" else "dirty"} tended=$tended dispatched=$dispatched skipped=$skipped"
        } else {
            "drained=$drained tended=$tended dispatched=$dispatched alive=$alive available=$available trunk=${if (trunkClean) "clean" else "dirty"} skipped=$skipped"
        }
    }

    internal fun tendSessions(sessions: List<JulesRestClient.SessionInfo>): Int {
        val responder = tendResponder ?: return 0
        var tended = 0
        sessions.filter {
            it.state == "AWAITING_USER_FEEDBACK" || it.state == "AWAITING_PLAN_APPROVAL"
        }.forEach { session ->
            val activities = runCatching { client.activities(session.id) }.getOrElse { return@forEach }
            val latest = activities.lastOrNull {
                it.kind == "agentMessaged" || it.kind == "progressUpdated" || it.kind == "planGenerated"
            }
            val activityKey = "${session.id}:${latest?.id ?: session.state}"
            if (activityKey in tendedActivities) return@forEach
            val context = buildString {
                appendLine("Session: ${session.id}")
                appendLine("Title: ${session.title}")
                appendLine("State: ${session.state}")
                if (session.state == "AWAITING_PLAN_APPROVAL") {
                    appendLine("A plan is awaiting approval. Approve it if it is TDD-first and scoped; otherwise give exact corrections.")
                }
                appendLine("Latest Jules activity: ${latest?.excerpt.orEmpty()}")
                appendLine("TrikeShed conventions: commonMain domain logic; focused tests; no unrelated edits or test deletion.")
            }
            val response = runCatching { responder(context).trim() }.getOrNull().orEmpty()
            if (response.isNotEmpty() && runCatching { client.sendMessage(session.id, response) }.isSuccess) {
                tendedActivities += activityKey
                tended++
            }
        }
        return tended
    }

    private suspend fun drainFanout(sessions: List<JulesRestClient.SessionInfo>): Int =
        if (sessions.isEmpty()) 0 else coroutineScope {
            sessions.map { s ->
                async(Dispatchers.IO) {
                    ioGate.withPermit {
                        try { drainOne(s) } catch (t: Throwable) { _events.emit(FlywheelEvent.PollError("drain ${s.id}: ${t.message}")); -1 }
                    }
                }
            }.awaitAll().count { it > 0 }
        }

    private suspend fun dispatchFanout(items: List<TodoItem>): Int =
        if (items.isEmpty()) 0 else coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    ioGate.withPermit {
                        try {
                            val prompt = buildSpecPrompt(item)
                            val sid = dispatchAndRecord(
                                workId = item.workId,
                                title = item.title,
                                spec = prompt,
                            )
                            _events.emit(FlywheelEvent.Dispatched(sid, item.title))
                            true
                        } catch (t: Throwable) {
                            _events.emit(FlywheelEvent.DispatchFailed(item.title, t.message.orEmpty()))
                            false
                        }
                    }
                }
            }.awaitAll().count { it }
        }

    /**
     * Spawn one Jules session and persist the durable workId → sessionId link so the
     * operator can observe what the loop is doing and so the feedback loop has a
     * real artifact to feed back into RESEARCH.
     */
    internal suspend fun dispatchAndRecord(workId: String, title: String, spec: String): String {
        val sessionId = sessionCreator(spec, title)
        queueStore?.appendWork(
            workId,
            JulesCause.WorkDispatched(
                workId = workId,
                sessionId = sessionId,
                attempt = 1,
                at = System.currentTimeMillis(),
            )
        )
        return sessionId
    }

    private val queueStoreRef: JulesBoardStore? get() = queueStore

    /**
     * Build the full prompt from a todo item
     * conventions block. Jules no longer needs a 4000-byte follow-up because the
     * original dispatch carries the spec inline.
     */
    private fun buildSpecPrompt(item: TodoItem): String = buildString {
        append("Task: ").append(item.title).append('\n')
        if (item.spec.isNotBlank()) {
            append('\n')
            append(item.spec.trim()).append('\n')
        }
        append("\nProject conventions (TrikeShed KMP):\n")
        append("- Domain code lives in src/commonMain/kotlin. JVM is a userspace.nio adapter only.\n")
        append("- Canonical collection types: Series<T>, Join<A,B> (see borg.trikeshed.lib). No mutableListOf/HashMap for read-only result builds.\n")
        append("- Forbidden in commonMain: kotlinx.serialization, java.io.File, java.net.http, Random.Default (use explicit Random(0L)).\n")
        append("- TDD: write the failing test first (one test file), then the minimal production code (one impl file). Land in one Jules pass.\n")
        append("- After implementation run `./gradlew jvmTest --rerun-tasks --no-daemon` to prove the gate. Include the test output in your PR description.\n")
        append("- Touch only files named in the spec. Do not reformat or rename unrelated code. Do not add dead helpers, TODOs, or NotImplemented stubs.\n")
        append("- The test file path is fixed; do not invent a different path. If the symbol under test does not exist, create the minimal production type with the exact name the test references.\n")
        append("- Deliver a PR via `gh pr create` against master with a non-empty diff that passes the gate. No 'preparing' / 'draft' / 'will follow up' states — land the work in this session.\n")
    }

    private suspend fun drainOne(s: JulesRestClient.SessionInfo): Int {
        val patch = client.lastPatch(s.id)
        if (patch == null) {
            _events.emit(FlywheelEvent.PollError("drain ${s.id}: no patch from lastPatch()"))
            return 0
        }
        if (patch.isBlank()) {
            _events.emit(FlywheelEvent.PollError("drain ${s.id}: blank patch"))
            return 0
        }
        val claim = settlePatch(
            patch = patch,
            title = s.title,
            sessionId = s.id,
            workId = "session:${s.id}",
            content = s.title,
        )
        if (claim == null) {
            _events.emit(FlywheelEvent.PollError("drain ${s.id}: settlement gate failed"))
            return 0
        }
        _events.emit(FlywheelEvent.Drained(s.id, claim.commitSha, claim.receipt.versionTag))
        return 1
    }

    /**
     * Read unchecked items from doc/todo.md. Each item is `(title, specBody)` where
     * `specBody` is the indented 1+ lines after the title — the TDD spec, test
     * file path, assertions, and implement directive. Without that body, Jules
     * comes back asking the same clarifying questions and the operator has to
     * rubber-stamp a 4000-byte follow-up per task.
     */
    private suspend fun readTodoItems(): List<TodoItem> {
        val todo = File(repoDir, "doc/todo.md")
        if (!todo.exists()) {
            val drafted = draftWorkFromResearch(researchEvidence())
            val ranked = rankWork(drafted.map { it.ranked })
            val byId = drafted.associateBy { it.workId }
            return ranked.map { byId.getValue(it.workId) }.map {
                TodoItem(it.workId, it.title, it.spec, it.parent, it.score, it.queuedAt)
            }
        }
        val titleRe = Regex("^\\s*- \\[ \\]\\s*\\*\\*?(.+?)\\*\\*?\\s*$")
        val items = mutableListOf<TodoItem>()
        val hierarchy = mutableListOf<Pair<Int, String>>()
        val lines = todo.readLines()
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
            val indent = lines[i].indexOf('-').coerceAtLeast(0)
            while (hierarchy.isNotEmpty() && hierarchy.last().first >= indent) hierarchy.removeLast()
            val parent = hierarchy.lastOrNull()?.second
            items.add(
                TodoItem(
                    workId = workId,
                    title = title,
                    spec = spec,
                    parent = parent,
                    score = 1.0 - (items.size.toDouble() / lines.size.coerceAtLeast(1)),
                    queuedAt = i.toLong(),
                )
            )
            hierarchy += indent to workId
            i = j
        }
        val ranked = rankWork(items.map { RankedWork(it.workId, it.parent, it.score, it.queuedAt) })
        val byId = items.associateBy { it.workId }
        return ranked.map { byId.getValue(it.workId) }
    }

    private fun researchEvidence(): String {
        val status = command("git", "status", "--short", "--branch").output.trim()
        val log = command("git", "log", "-20", "--oneline", "--decorate").output.trim()
        val surfaces = listOf("doc/taste.md", "doc/concepts.md", "PRELOAD.md")
            .mapNotNull { path -> File(repoDir, path).takeIf { it.isFile }?.let { path to it.readText().take(12_000) } }
            .joinToString("\n\n") { (path, text) -> "FILE $path\n$text" }
        return "GIT STATUS\n$status\n\nGIT LOG\n$log\n\nPROJECT SURFACE\n$surfaces"
    }

    private data class TodoItem(
        val workId: String,
        val title: String,
        val spec: String,
        val parent: String?,
        val score: Double,
        val queuedAt: Long,
    )

    internal fun settlePatch(
        patch: String,
        title: String,
        sessionId: String,
        workId: String,
        content: String,
    ): ClaimedPatch? {
        fun failed(stage: String, result: CommandResult? = null): ClaimedPatch? {
            val detail = result?.output?.takeLast(300)?.trim().orEmpty()
            System.err.println("[FLYWHEEL] SETTLE-FAIL $stage${if (detail.isEmpty()) "" else ": $detail"}")
            return null
        }
        val touchedFiles = parsePatchFiles(patch)
        if (touchedFiles.isEmpty()) return failed("no touched files")
        if (!isWorkingTreeClean()) return failed("dirty tree")
        val patchFile = File(repoDir, ".flywheel-patch")
        patchFile.writeText(patch)
        try {
            val check = command("git", "apply", "--check", patchFile.name)
            if (check.exitCode != 0) return failed("apply check", check)
            val apply = command("git", "apply", patchFile.name)
            if (apply.exitCode != 0) return failed("apply", apply)
            val gate = command(*gateCommand.toTypedArray())
            if (gate.exitCode != 0) {
                revertFiles(touchedFiles)
                return failed("gate", gate)
            }
            val add = command("git", "add", "--", *touchedFiles.toTypedArray())
            if (add.exitCode != 0) {
                revertFiles(touchedFiles)
                return failed("stage", add)
            }
            val commit = command("git", "commit", "-m", "flywheel: $title")
            if (commit.exitCode != 0) {
                revertFiles(touchedFiles)
                return failed("commit", commit)
            }
            val commitSha = headSha()
            val claim = claimPatch(commitSha, patch, sessionId, workId, title, content)
                ?: return failed("receipt")
            val push = command(
                "git", "push", "--atomic", "origin",
                "HEAD:refs/heads/master",
                "refs/tags/${claim.receipt.versionTag}",
            )
            return if (push.exitCode == 0) claim else failed("push", push)
        } finally {
            patchFile.delete()
        }
    }

    private fun parsePatchFiles(patch: String): List<String> = patch.lineSequence()
        .filter { it.startsWith("+++ b/") }
        .map { it.removePrefix("+++ b/").trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    private fun revertFiles(files: List<String>) {
        command("git", "reset", "HEAD", "--", *files.toTypedArray())
        command("git", "checkout", "HEAD", "--", *files.toTypedArray())
        files.filter { !File(repoDir, it).exists() }.forEach { File(repoDir, it).delete() }
        command("git", "clean", "-f", "--", *files.toTypedArray())
    }

    internal fun claimPatch(
        commitSha: String,
        patch: String,
        sessionId: String,
        workId: String,
        title: String,
        content: String,
    ): ClaimedPatch? {
        val patchCid = try {
            casStore.put(patch.encodeToByteArray())
        } catch (_: Exception) {
            return null
        }
        val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val tag = "flywheel/jules-$safeSession-${commitSha.take(12)}"
        val message = "Jules merge receipt\nsession=$sessionId\nwork=$workId\npatchCid=${patchCid.value}"
        if (command("git", "tag", "-a", tag, commitSha, "-m", message).exitCode != 0) return null
        return ClaimedPatch(
            commitSha,
            MergeReceipt(
                workId = workId,
                producer = "jules",
                producerRef = sessionId,
                patchCid = patchCid,
                revision = commitSha,
                versionTag = tag,
                lexicalMemory = LexicalMemory(summary = title, title = title, content = content),
                claimedAt = System.currentTimeMillis(),
            ),
        )
    }

    internal data class ClaimedPatch(val commitSha: String, val receipt: MergeReceipt)

    private fun isWorkingTreeClean(): Boolean = command("git", "status", "--porcelain").output.isBlank()
    private fun headSha(): String = command("git", "rev-parse", "HEAD").output.trim()
    private fun command(vararg args: String): CommandResult =
        try {
            val p = ProcessBuilder(*args).directory(repoDir).redirectErrorStream(true).start()
            CommandResult(p.waitFor(), p.inputStream.bufferedReader().readText())
        } catch (t: Throwable) { CommandResult(1, t.message.orEmpty()) }

    private data class CommandResult(val exitCode: Int, val output: String)

    /** Subscribe a child coroutine to reactor events. Returns the subscriber's job. */
    fun subscribe(block: suspend (FlywheelEvent) -> Unit): Job =
        reactorScope.launch { events.collect { block(it) } }

    /** Cancel the supervisor; children propagate. Idempotent. */
    fun close() { parentJob.cancel() }

    companion object {
        internal fun rankWork(items: List<RankedWork>): List<RankedWork> {
            val ids = items.mapTo(mutableSetOf()) { it.workId }
            val byId = items.associateBy { it.workId }
            val depthMemo = mutableMapOf<String, Int>()
            fun depth(item: RankedWork, visiting: Set<String> = emptySet()): Int {
                depthMemo[item.workId]?.let { return it }
                if (item.workId in visiting) return 0
                val parent = item.parent?.takeIf { it in ids }
                val value = if (parent == null) 0 else {
                    depth(byId.getValue(parent), visiting + item.workId) + 1
                }
                depthMemo[item.workId] = value
                return value
            }
            return items.sortedWith(
                compareByDescending<RankedWork> { depth(it) }
                    .thenByDescending { it.score }
                    .thenBy { it.queuedAt }
                    .thenBy { it.workId }
            )
        }

        @JvmStatic
        fun main(args: Array<String>) {
            // Single entrypoint is `bin/oroboros-daemon`; this companion is the
            // probe-mode seam for jshell and `gradle jvmRun -PmainClass=...`.
            if (args.isEmpty() || args[0] != "--once" && args[0] != "--watch") {
                error("Use bin/oroboros-daemon. Companion main only accepts --once | --watch.")
            }
            val apiKey = System.getenv("JULES_API_KEY") ?: error("JULES_API_KEY required")
            val watch = args[0] == "--watch"
            val driver = FlywheelDriver(apiKey)
            driver.subscribe { ev -> println("[FLY-EVENT] $ev") }
            println("[FLYWHEEL] reactor started on ${driver.repoDir} mode=${if (watch) "watch" else "once"}")
            runBlocking {
                if (!watch) {
                    println("[FLYWHEEL] " + driver.cycle())
                    driver.close()
                    return@runBlocking
                }
                launch(Dispatchers.Default) {
                    while (true) {
                        val t = System.currentTimeMillis()
                        try { println("[FLYWHEEL] " + driver.cycle()) } catch (e: Throwable) { println("[FLYWHEEL] ERR ${e.message}") }
                        val elapsed = System.currentTimeMillis() - t
                        delay(elapsed.coerceAtMost(driver.intervalMs))
                    }
                }
            }
        }
    }
}
