package borg.trikeshed.jules

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.util.oroboros.MergeReceipt
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
) {
    private val client = JulesRestClient(apiKey)
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
        // DRAIN runs first under ioGate (serial) so trunk lands clean before DISPATCH.
        val completed = sessions.filter { it.state == "COMPLETED" }.take(maxSlots)
        val drained = drainFanout(completed)
        val trunkClean = isWorkingTreeClean()
        // Dispatch from doc/todo.md whenever we have headroom, regardless of whether
        // listSessions came back empty. Alpha-on-launch invariant: top the pipe up
        // to maxSlots from the project todo on every cycle.
        val items = if (trunkClean && available > 0) readTodoItems().take(available) else emptyList()
        val dispatched = if (trunkClean && available > 0) dispatchFanout(items) else 0
        if (pollErr == null && sessions.isEmpty()) {
            "poll-empty alive=0 available=$available trunk=${if (trunkClean) "clean" else "dirty"} dispatched=$dispatched"
        } else {
            "drained=$drained dispatched=$dispatched alive=$alive available=$available trunk=${if (trunkClean) "clean" else "dirty"}"
        }
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
                            val sid = client.createSession(
                                prompt = prompt,
                                title = item.title,
                                source = source)
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
     * Build the full prompt from a todo item: title + the body lines (TDD spec, file
     * path, assertions, implement directive) plus the canonical TrikeShed project
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
        if (patch == null) { _events.emit(FlywheelEvent.PollError("drain ${s.id}: no patch from lastPatch()")); return 0 }
        if (patch.isBlank()) { _events.emit(FlywheelEvent.PollError("drain ${s.id}: blank patch")); return 0 }
        if (!isWorkingTreeClean()) { _events.emit(FlywheelEvent.PollError("drain ${s.id}: trunk dirty, skipping")); return 0 }
        val patchFile = File(repoDir, ".flywheel-patch")
        patchFile.writeText(patch)
        val applyCheck = ProcessBuilder("git", "apply", "--check", ".flywheel-patch")
            .directory(repoDir).redirectErrorStream(true).start()
        applyCheck.waitFor()
        if (applyCheck.exitValue() != 0) {
            val stderr = applyCheck.inputStream.bufferedReader().readText().take(200)
            patchFile.delete()
            _events.emit(FlywheelEvent.PollError("drain ${s.id}: apply --check failed: $stderr"))
            return 0
        }
        ProcessBuilder("git", "apply", ".flywheel-patch").directory(repoDir).start().waitFor()
        patchFile.delete()
        val commitSha = headSha()
        val patchBytes = patch.encodeToByteArray()
        val patchCid = try { casStore.put(patchBytes) } catch (e: Exception) {
            _events.emit(FlywheelEvent.PollError("drain ${s.id}: cas put failed: ${e.message}"))
            return -1
        }
        val safe = s.id.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val tag = "flywheel/jules-" + safe + "-" + commitSha.take(12)
        val msg = ("Jules receipt" + "\n" +
            "session=" + s.id + "\n" +
            "patchCid=" + patchCid.value + "\n" +
            "taskTitle=" + s.title)
        val tagRes = command("git", "tag", "-a", tag, commitSha, "-m", msg)
        if (tagRes.exitCode != 0) {
            _events.emit(FlywheelEvent.PollError("drain ${s.id}: tag create failed: ${tagRes.output.take(200)}"))
            return -1
        }
        _events.emit(FlywheelEvent.Drained(s.id, commitSha, tag))
        return 1
    }

    /**
     * Read unchecked items from doc/todo.md. Each item is `(title, specBody)` where
     * `specBody` is the indented 1+ lines after the title — the TDD spec, test
     * file path, assertions, and implement directive. Without that body, Jules
     * comes back asking the same clarifying questions and the operator has to
     * rubber-stamp a 4000-byte follow-up per task.
     */
    private fun readTodoItems(): List<TodoItem> {
        val todo = File(repoDir, "doc/todo.md")
        if (!todo.exists()) return emptyList()
        val titleRe = Regex("^\\s*- \\[ \\]\\s*\\*\\*?(.+?)\\*\\*?\\s*$")
        val items = mutableListOf<TodoItem>()
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
            items.add(TodoItem(title, body.toString().trim()))
            i = j
        }
        return items
    }

    private data class TodoItem(val title: String, val spec: String)

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
