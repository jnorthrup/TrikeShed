/* Copyright (c) 2017 TrikeShed Contributors. AGPLv3 — see LICENSE. */
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
        val sessions = withTimeoutOrNull(20_000) {
            withContext(Dispatchers.IO) { client.listSessions(source) }
        } ?: emptyList()
        val alive = sessions.count { it.state != "COMPLETED" && it.state != "FINISHED" }
        val available = (maxSlots - alive).coerceAtLeast(0)
        _events.emit(FlywheelEvent.Polled(alive, available))
        if (sessions.isEmpty()) return@coroutineScope "poll-empty alive=0"
        val completed = sessions.filter { it.state == "COMPLETED" }.take(maxSlots)
        // Alpha on launch: dispatch reads doc/todo.md up to `available` after DRAIN
        // completes. DRAIN goes first under ioGate, drains to a clean trunk, then
        // DISPATCH refills the pipe up to maxSlots.
        val drained = drainFanout(completed)
        // If trunk landed dirty because DRAIN succeeded but local-push didn't (rare),
        // refuse to dispatch. Patches accumulate in CAS regardless.
        val trunkClean = isWorkingTreeClean()
        val titles = if (trunkClean) readTodoTitles().take(available) else emptyList()
        val dispatched = if (trunkClean) dispatchFanout(titles) else 0
        "drained=$drained dispatched=$dispatched alive=$alive available=$available trunk=${if (trunkClean) "clean" else "dirty"}"
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

    private suspend fun dispatchFanout(titles: List<String>): Int =
        if (titles.isEmpty()) 0 else coroutineScope {
            titles.map { title ->
                async(Dispatchers.IO) {
                    ioGate.withPermit {
                        try {
                            val sid = client.createSession(
                                prompt = "TDD: " + title,
                                title = title,
                                source = source)
                            _events.emit(FlywheelEvent.Dispatched(sid, title))
                            true
                        } catch (t: Throwable) {
                            _events.emit(FlywheelEvent.DispatchFailed(title, t.message.orEmpty()))
                            false
                        }
                    }
                }
            }.awaitAll().count { it }
        }

    private suspend fun drainOne(s: JulesRestClient.SessionInfo): Int {
        val patch = client.lastPatch(s.id) ?: return 0
        if (patch.isBlank()) return 0
        if (!isWorkingTreeClean()) return 0
        val patchFile = File(repoDir, ".flywheel-patch")
        patchFile.writeText(patch)
        val applyCheck = ProcessBuilder("git", "apply", "--check", ".flywheel-patch")
            .directory(repoDir).redirectErrorStream(true).start()
        applyCheck.waitFor()
        if (applyCheck.exitValue() != 0) { patchFile.delete(); return 0 }
        ProcessBuilder("git", "apply", ".flywheel-patch").directory(repoDir).start().waitFor()
        patchFile.delete()
        val commitSha = headSha()
        val patchBytes = patch.encodeToByteArray()
        val patchCid = try { casStore.put(patchBytes) } catch (_: Exception) { patchFile.delete(); return -1 }
        val safe = s.id.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val tag = "flywheel/jules-" + safe + "-" + commitSha.take(12)
        val msg = ("Jules receipt" + "\n" +
            "session=" + s.id + "\n" +
            "patchCid=" + patchCid.value + "\n" +
            "taskTitle=" + s.title)
        if (command("git", "tag", "-a", tag, commitSha, "-m", msg).exitCode != 0) return -1
        _events.emit(FlywheelEvent.Drained(s.id, commitSha, tag))
        return 1
    }

    /** Read unchecked titles from doc/todo.md. */
    private fun readTodoTitles(): List<String> {
        val todo = File(repoDir, "doc/todo.md")
        if (!todo.exists()) return emptyList()
        return todo.readLines()
            .mapNotNull { Regex("^\\s*- \\[ \\]\\s*\\*\\*?(.+?)\\*\\*?$").find(it)?.groupValues?.get(1)?.trim() }
    }

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
            val apiKey = System.getenv("JULES_API_KEY") ?: error("JULES_API_KEY required")
            val watch = args.any { it == "--watch" }
            val driver = FlywheelDriver(apiKey)
            // Subscribe a tiny stdout printer so we can see events without spawning a TUI.
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
