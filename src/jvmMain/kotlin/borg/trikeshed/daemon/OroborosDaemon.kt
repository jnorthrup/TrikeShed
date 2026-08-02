package borg.trikeshed.daemon

import borg.trikeshed.couch.CouchReportReactorElement
import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.jules.FlywheelDriver
import borg.trikeshed.jules.FlywheelDriver.FlywheelEvent
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.util.io.ForgeCliArgs
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.util.oroboros.GitCouchGateway
import borg.trikeshed.util.oroboros.JvmFileWatchReactorElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.system.exitProcess
import sun.misc.Signal
import sun.misc.SignalHandler

/**
 * Oroboros daemon — thin entry-point over [FlywheelDriver].
 *
 * Env: JULES_API_KEY (required)
 * Flags:
 *   --once                  single cycle then exit (default: --watch loops)
 *   --watch                 loop forever
 *   --interval-ms <N>       poll cadence (default = 30000)
 *   --max-slots <N>         live session cap (default = 15)
 * Positional args (must come last):
 *   forgeHome               default = ~/.local/forge (ForgeHome.defaultHome)
 *   repoDir                 default = cwd
 */
object OroborosDaemon {

    const val DEFAULT_INTERVAL_MS = 30_000L
    const val DEFAULT_MAX_SLOTS = 15
    const val DEFAULT_KANBAN_PORT = 8888

    data class DaemonConfig(
        val watch: Boolean,
        val intervalMs: Long,
        val maxSlots: Int,
        val kanbanPort: Int,
        val positional: List<String>
    )

    @Volatile
    var lastCycleReport: FlywheelDriver.CycleReport? = null

    @Volatile
    var daemonStartTime = 0L

    @Volatile
    var isRunning = true

    /** Reference to the live cycle body. Held in a static field so a JVMTI
     *  agent or external observer can locate it after retransform. */
    @Volatile
    var cycleBodyField: CycleBody? = null

    fun parseConfig(args: Array<String>): DaemonConfig {
        var watch = true
        var intervalMs = DEFAULT_INTERVAL_MS
        var maxSlots = DEFAULT_MAX_SLOTS
        var kanbanPort = DEFAULT_KANBAN_PORT
        val positional = mutableListOf<String>()

        val flags = listOf(
            ForgeCliArgs.Flag(name = "--once") { _, i -> watch = false; i + 1 },
            ForgeCliArgs.Flag(name = "--watch") { _, i -> watch = true; i + 1 },
            ForgeCliArgs.Flag(name = "--interval-ms", withValue = true) { a, i ->
                val v = a[i].toLongOrNull() ?: die("--interval-ms requires a positive long")
                intervalMs = v
                i + 1
            },
            ForgeCliArgs.Flag(name = "--max-slots", withValue = true) { a, i ->
                val v = a[i].toIntOrNull() ?: die("--max-slots requires a positive int")
                maxSlots = v
                i + 1
            },
            ForgeCliArgs.Flag(name = "--kanban-port", withValue = true) { a, i ->
                val v = a[i].toIntOrNull() ?: die("--kanban-port requires a positive int")
                kanbanPort = v
                i + 1
            },
        )

        when (val r = ForgeCliArgs.parse(args.toList(), flags)) {
            is ForgeCliArgs.Result.Parsed -> positional.addAll(r.remaining)
            ForgeCliArgs.Result.Help -> { usage(); exitProcess(0) }
            is ForgeCliArgs.Result.Error -> die(r.message)
        }
        return DaemonConfig(watch, intervalMs, maxSlots, kanbanPort, positional)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            runBlocking {
                mainImpl(args)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // JVM shutdown triggered by signal handler
            exitProcess(0)
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.mainImpl(args: Array<String>) {
        val apiKey = System.getenv("JULES_API_KEY") ?: System.getProperty("JULES_API_KEY")
        if (apiKey.isNullOrBlank()) {
            System.err.println("[OROBOROS] JULES_API_KEY not set; the conductor cannot poll Jules. Aborting.")
            exitProcess(1)
        }

        val config = parseConfig(args)
        val watch = config.watch
        val intervalMs = config.intervalMs
        val maxSlots = config.maxSlots
        val kanbanPort = config.kanbanPort
        val positional = config.positional

        val home = System.getProperty("user.home")
            ?: die("System property user.home not set")
        val canonicalForge = File(home, ".local/forge")
        val forgeHome = File(positional.getOrNull(0) ?: canonicalForge.absolutePath)
        val repoDir = File(positional.getOrNull(1) ?: System.getProperty("user.dir"))
        if (!repoDir.resolve(".git").exists()) {
            System.err.println("[OROBOROS] $repoDir is not a git work tree. Aborting.")
            exitProcess(1)
        }
        forgeHome.mkdirs()

        val driver = FlywheelDriver(
            apiKey = apiKey,
            repoDir = repoDir,
            forgeDir = forgeHome,
            intervalMs = intervalMs,
            maxSlots = maxSlots,
        )

        // HTX + TLS reactor: every Jules API call (and every ModelMux/KeyMux
        // call) flows through HtxKey → HtxElement → HtxReactorElement →
        // JvmTlsCodecBackend. No standalone JDK HTTP client. mTLS
        // (ClientAuth, client cert, trust store) is configured on the
        // TlsConfig carried by the route service, not per-client. The
        // supervisor auto-registers ChannelOperations + JvmTlsCodecBackend +
        // HtxReactorElement via platformNioProviders().
        val nioSupervisor = NioSupervisor()
        nioSupervisor.open()
        val htxElement: HtxElement = openHtxElement(
            nioSupervisor = nioSupervisor,
            parentJob = coroutineContext[kotlinx.coroutines.Job],
        )
        System.err.println("[OROBOROS] HTX reactor open: ${htxElement.state} — Jules/ModelMux via TLS codec")

        // ── Kanban HTTP server (CCEK litebike listener, no JDK networking) ──
        // JvmKanbanServer binds via JvmLitebikeBindAdapter → LitebikeListenerElement
        // (the userspace.nio CCEK path), not via com.sun.net.httpserver or ktor.
        // Only launched in --watch mode; --once is a single-cycle diagnostic.
        val kanbanJob = SupervisorJob(coroutineContext[kotlinx.coroutines.Job])
        if (watch) {
            launch(CoroutineScope(kanbanJob).coroutineContext + Dispatchers.Default) {
                try {
                    JvmKanbanServer.run(kanbanPort, null)
                } catch (t: Throwable) {
                    System.err.println("[OROBOROS] Kanban server failed: ${t.message}")
                }
            }
            System.err.println("[OROBOROS] Kanban HTTP server launching on :$kanbanPort (CCEK litebike)")
        } else {
            System.err.println("[OROBOROS] --once mode: Kanban server skipped")
        }

        // ── GitCouchGateway: mirror .git → Couch/CAS reactively ──
        val fileOps = JvmFileOperations()
        val casStore = FileCasStore(fileOps, fileOps.resolvePath(forgeHome.absolutePath, "cas"))
        val couchStore = borg.trikeshed.couch.CouchStoreFactory.inMemory()
        val attachmentGateway = CouchAttachmentGateway(couchStore, casStore)
        val gitCouchGateway = GitCouchGateway(fileOps, attachmentGateway)

        // ── Reactive git-state hub: JvmFileWatchReactorElement watches .git/**
        //    and feeds the GitCouchGateway + git-state cache. This IS the
        //    choreography — coroutine pipelines that react to filesystem
        //    events, not blocking ProcessBuilder calls. ──
        val gitState = GitStateCache(repoDir)
        val gitWatcher = JvmFileWatchReactorElement(
            root = repoDir.absolutePath,
            parentJob = coroutineContext[kotlinx.coroutines.Job],
            includeGlobs = listOf(".git/**"),
            excludeGlobs = emptyList(),
        )
        launch { gitWatcher.open() }
        System.err.println("[OROBOROS] Git watcher: ${gitWatcher.state} — reactive .git/** events")

        // Choreography 1: git filesystem events → GitStateCache invalidation.
        // The cache holds headSha, treeClean, and refHead — read from .git
        // files directly (no ProcessBuilder). File events invalidate the
        // relevant cache entry; the next cycle reads fresh values.
        launch {
            for (event in gitWatcher.events) {
                when {
                    event.path.startsWith(".git/HEAD") -> {
                        gitState.invalidateHead()
                        println("[OROBOROS] git-event: HEAD changed → headSha cache invalidated")
                    }
                    event.path.startsWith(".git/index") -> {
                        gitState.invalidateTree()
                        println("[OROBOROS] git-event: index changed → treeClean cache invalidated")
                    }
                    event.path.startsWith(".git/refs/") -> {
                        gitState.invalidateHead()
                        println("[OROBOROS] git-event: ref changed → ${event.path}")
                    }
                    event.path.startsWith(".git/objects/") -> {
                        // New git object — trigger Couch reconcile if in --watch mode
                        if (watch) {
                            gitState.markObjectsDirty()
                        }
                    }
                }
            }
        }

        // Choreography 2: git object creation → GitCouchGateway reconcile.
        // Runs on its own coroutine; the gateway reads .git directly via
        // JvmFileOperations, no ProcessBuilder.
        launch {
            var lastReconcledSha = ""
            while (true) {
                // Wait for object-dirty signal, then reconcile
                gitState.awaitObjectsDirty()
                val currentSha = gitState.headSha()
                if (currentSha != lastReconcledSha) {
                    runCatching {
                        val snap = gitCouchGateway.reconcile(
                            forgeHome = repoDir.absolutePath,
                            agentId = "oroboros",
                            revision = currentSha,
                            sequence = System.currentTimeMillis(),
                        )
                        lastReconcledSha = currentSha
                        println("[OROBOROS] Git→Couch reactive reconcile: ${snap.paths.size} paths @ ${currentSha.take(12)}")
                    }.onFailure {
                        System.err.println("[OROBOROS] Git→Couch reconcile failed: ${it.message}")
                    }
                }
            }
        }

        // Initial reconcile — read HEAD directly, no ProcessBuilder
        runCatching {
            val headSha = gitState.headSha()
            val snap = gitCouchGateway.reconcile(
                forgeHome = repoDir.absolutePath,
                agentId = "oroboros",
                revision = headSha,
                sequence = System.currentTimeMillis(),
            )
            System.err.println("[OROBOROS] Git→Couch initial reconcile: ${snap.paths.size} paths @ ${headSha.take(12)}")
        }.onFailure { System.err.println("[OROBOROS] Git→Couch initial reconcile failed: ${it.message}") }

        // ── Couch report reactor: CCEK element for map/reduce events ──
        val reportReactor = CouchReportReactorElement(parentJob = kanbanJob)
        launch { reportReactor.open() }
        System.err.println("[OROBOROS] Couch report reactor: ${reportReactor.state}")

        val mainJob = coroutineContext[kotlinx.coroutines.Job]

        // Shutdown: cancel Jobs only — never nest runBlocking in a signal handler.
        // Structured concurrency unwinds the finally block in mainImpl which
        // closes every CCEK element in scope.
        val sigHandler = SignalHandler {
            isRunning = false
            mainJob?.cancel()
        }
        Signal.handle(Signal("TERM"), sigHandler)
        Signal.handle(Signal("INT"), sigHandler)

        val traceFile = File(forgeHome, "oroboros-cycles.jsonl")
        var traceLineCount = if (traceFile.exists()) traceFile.readLines().size else 0
        var traceWriter: BufferedWriter? = null
        try {
            traceWriter = FileOutputStream(traceFile, true).bufferedWriter()
            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    traceWriter?.flush()
                    traceWriter?.close()
                } catch (e: Exception) { /* ignore */ }
            })
        } catch (e: Exception) {
            System.err.println("[OROBOROS] warning: failed to open trace file: ${e.message}")
        }

        var pollErrors = 0
        val consecutivePollErrors = java.util.concurrent.atomic.AtomicInteger(0)
        var pollErrOccurred = false
        // Stdout observer so cycles are visible without a TUI, and bridge to KanbanFSM.
        driver.subscribe { ev ->
            println("[FLY-EVENT] $ev")
            if (ev is FlywheelEvent.PollError) {
                pollErrors++
                pollErrOccurred = true
            }
            val now = System.currentTimeMillis()
            when (ev) {
                is borg.trikeshed.jules.FlywheelDriver.FlywheelEvent.Polled ->
                    borg.trikeshed.userspace.reactor.KanbanFSM.reduce(
                        borg.trikeshed.userspace.reactor.KanbanEvent.CycleObserved(0L, 0, 0, ev.alive, ev.available, now)
                    )
                is borg.trikeshed.jules.FlywheelDriver.FlywheelEvent.Drained ->
                    borg.trikeshed.userspace.reactor.KanbanFSM.reduce(
                        borg.trikeshed.userspace.reactor.KanbanEvent.PatchDrained(ev.sessionId, ev.sha, ev.tag, now)
                    )
                is borg.trikeshed.jules.FlywheelDriver.FlywheelEvent.Dispatched ->
                    borg.trikeshed.userspace.reactor.KanbanFSM.reduce(
                        borg.trikeshed.userspace.reactor.KanbanEvent.DispatchFired(ev.sessionId, ev.title, now)
                    )
                else -> {}
            }
        }
        System.err.println(
            "[OROBOROS] daemon up. forgeHome=$forgeHome repo=$repoDir " +
                "intervalMs=$intervalMs maxSlots=$maxSlots mode=${if (watch) "watch" else "once"}"
        )

        daemonStartTime = System.currentTimeMillis()
        lastCycleReport = null

        val oroborosDir = File(forgeHome, ".oroboros")
        oroborosDir.mkdirs()
        val healthSock = File(oroborosDir, "health.sock")
        if (healthSock.exists()) healthSock.delete()

        // Bind with retry: a prior daemon may have left a stale socket file
        // even after the JVM exited; the bind() then creates a regular file
        // instead of a UNIX socket. Retry up to 3× with the file removed
        // between attempts so we always end up with a real socket.
        var serverSocket: ServerSocketChannel? = null
        var bindAttempt = 0
        while (serverSocket == null && bindAttempt < 3) {
            try {
                serverSocket = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                serverSocket.bind(UnixDomainSocketAddress.of(healthSock.toPath()))
            } catch (e: Throwable) {
                System.err.println("[OROBOROS] health.sock bind attempt ${bindAttempt + 1} failed: ${e.message}")
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
                if (healthSock.exists()) healthSock.delete()
                bindAttempt++
            }
        }
        if (serverSocket == null) {
            System.err.println("[OROBOROS] health.sock bind FAILED after 3 attempts; aborting")
            return
        }

        val healthJob = launch(Dispatchers.IO) {
            while (isActive) {
                var client: SocketChannel? = null
                try {
                    client = serverSocket!!.accept()
                    val report = lastCycleReport
                    val uptimeMs = System.currentTimeMillis() - daemonStartTime
                    val msg = if (report != null) {
                        "ALIVE $uptimeMs ${report.cycleMs} ${report.harvested} ${report.dispatched} ${report.alive} ${report.available}\n"
                    } else {
                        "ALIVE $uptimeMs -1 -1 -1 -1 -1\n"
                    }
                    val buf = ByteBuffer.wrap(msg.toByteArray())
                    while (buf.hasRemaining()) {
                        client.write(buf)
                    }
                } catch (e: Exception) { /* ignore */ }
                finally {
                    try { client?.close() } catch (_: Exception) {}
                }
            }
        }
        if (!preflight(repoDir, driver)) {
            System.err.println("[OROBOROS] preflight failed; aborting before first cycle")
            driver.close()
            return
        }

        suspend fun runCycle() = withContext(htxElement) {
            val t0 = System.currentTimeMillis()
            val startPollErrors = pollErrors
            val summary: FlywheelDriver.CycleReport = driver.cycle()
            val cyclePollErrors = pollErrors - startPollErrors
            println("[FLYWHEEL] phase=" + summary.phase + " cycleMs=" + summary.cycleMs + " harvested=" + summary.harvested + " dispatched=" + summary.dispatched + " alive=" + summary.alive + "/" + summary.available + " inducted=" + summary.inducted + " settled=" + summary.settled)

            lastCycleReport = summary
            val json = "{\"t\":" + t0 + ",\"c\":" + summary.cycleMs + ",\"d\":" + summary.harvested + ",\"p\":" + summary.dispatched + ",\"a\":" + summary.alive + ",\"v\":" + summary.available + ",\"e\":" + cyclePollErrors + ",\"h429\":" + summary.http429 + ",\"h5x\":" + summary.http5xx + "}"
            try {
                if (traceLineCount >= 10000) {
                    traceWriter?.close()
                    val backup = File(traceFile.parentFile, traceFile.name + ".1")
                    traceFile.renameTo(backup)
                    traceWriter = FileOutputStream(traceFile, false).bufferedWriter()
                    traceLineCount = 0
                }
                traceWriter?.let {
                    it.write(json)
                    it.write("\n")
                    it.flush() // observable progress — the trace is the operator's live signal
                    traceLineCount++
                }
            } catch (e: Exception) {
                System.err.println("[OROBOROS] warning: failed to write trace file: ${e.message}")
            }
        }

        // Hot-swappable cycle body. Same instance, JVM retransforms the class
        // in place — next call sees new bytecode. Edit CycleBody.kt, rebuild,
        // agent reloads; loop continues uninterrupted.
        val cycleBody = CycleBody(
            driver = driver,
            repoDir = repoDir,
            consecutivePollErrors = consecutivePollErrors,
            pollErrRef = { pollErrOccurred },
            setPollErr = { pollErrOccurred = it },
            runCycle = { runCycle() },
            preflight = { preflight(repoDir, driver) },
        )
        cycleBodyField = cycleBody

        try {
            val startErrs = pollErrors
            runCycle()
            if (pollErrors > startErrs) {
                consecutivePollErrors.set(1)
            }
            if (watch) {
                while (isRunning) {
                    val errors = consecutivePollErrors.get()
                    val backoffMs = kotlin.math.min(intervalMs * (1L shl kotlin.math.min(errors, 30)), intervalMs * 5)
                    if (errors > 0) System.err.println("[OROBOROS] backoff=${backoffMs}ms consecutiveErrors=$errors")
                    delay(backoffMs)
                    try {
                        cycleBody.run()
                    } catch (t: LinkageError) {
                        // Botched hot-swap: the retransform produced bytecode
                        // the JVM can't link against the loaded class
                        // graph. The Runnable is unrunnable — bail out so the
                        // supervisor (cron / launchctl) restarts us from a
                        // known-clean compiled state.
                        System.err.println("[OROBOROS] CycleBody unlinkable: ${t.javaClass.simpleName}: ${t.message?.take(200)} — bouncing")
                        isRunning = false
                        break
                    } catch (t: Throwable) {
                        // Defense in depth: CycleBody.run is itself wrapped in
                        // a hard guard, but if anything escapes (NoClassDef,
                        // AbstractMethodError from a future shape change)
                        // the daemon loop must survive. Log and continue.
                        System.err.println("[OROBOROS] cycleBody.run escaped: ${t.javaClass.simpleName}: ${t.message?.take(200)}")
                    }
                }
            }
        } finally {
            healthJob.cancel()
            try { serverSocket.close() } catch (_: Exception) {}
            if (healthSock.exists()) healthSock.delete()
            try { traceWriter?.flush(); traceWriter?.close() } catch (_: Exception) {}
            runCatching { kanbanJob.cancel() }
            runCatching { reportReactor.close() }
            runCatching { gitWatcher.close() }
            try { htxElement.close() } catch (_: Exception) {}
            try { nioSupervisor.close() } catch (_: Exception) {}
            driver.close()
        }
    }

    private fun preflight(repoDir: File, driver: FlywheelDriver): Boolean {
        // git fetch origin master (best-effort, 5s timeout)
        val fetch = ProcessBuilder("git", "fetch", "origin", "master", "--dry-run")
            .directory(repoDir).start()
        val fetchOk = fetch.waitFor() == 0
        if (!fetchOk) return true // offline is OK, we'll poll anyway

        fun command(vararg args: String): String {
            val p = ProcessBuilder(*args).directory(repoDir).redirectErrorStream(true).start()
            p.waitFor()
            return p.inputStream.bufferedReader().readText().trim()
        }

        val local = command("git", "rev-parse", "HEAD")
        val remote = command("git", "rev-parse", "origin/master")
        if (local == remote) return true
        // Local AHEAD of remote is the normal flywheel state between pushes
        // (drained patches land locally, then get pushed by settlementBarrier).
        // Only block when truly diverged: local has commits origin doesn't
        // AND origin has commits local doesn't (true divergence).
        val mergeBase = command("git", "merge-base", local, remote)
        if (mergeBase == local || mergeBase == remote) {
            // linear: local ahead OR local behind, but not both
            return true
        }
        // local is neither reachable from remote nor vice versa → diverged
        println("[OROBOROS] UPSTREAM-DIVERGED local=$local remote=$remote mergeBase=$mergeBase")
        driver.emitDrifted(local, remote)
        return false
    }

    private fun die(msg: String): Nothing {
        System.err.println("[OROBOROS] $msg")
        usage()
        exitProcess(2)
    }

    private fun usage() {
        System.err.println(
            """usage: OroborosDaemon [--once | --watch] [--interval-ms N] [--max-slots N] [--kanban-port N] [forgeHome] [repoDir]
              env: JULES_API_KEY (required)
              forgeHome default: ~/.local/forge (ForgeHome.defaultHome)
              repoDir  default: cwd
              kanban-port default: 8888"""
        )
    }
}
