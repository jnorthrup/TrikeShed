package borg.trikeshed.daemon

import borg.trikeshed.couch.CouchReportReactorElement
import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.torrent.TorrentElement
import borg.trikeshed.jules.FlywheelDriver
import borg.trikeshed.jules.FlywheelDriver.FlywheelEvent
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.nio.ebpf.bpfProbeAttach
import borg.trikeshed.userspace.nio.ebpf.Tracepoints
import borg.trikeshed.util.io.ForgeCliArgs
import borg.trikeshed.parse.json.JsonSupport
import metrics.FlywheelMetrics
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.util.oroboros.GitCouchGateway
import borg.trikeshed.util.oroboros.JvmFileWatchReactorElement
import borg.trikeshed.util.oroboros.WorktreeCouchGateway
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.userspace.reactor.MuxReactorConfig
import keymux.KeyMux
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.withLock
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
 *   --hermes-root <path>    Hermes Python source checkout
 *   --hermes-sleeve <path>  GraalPy-safe overlay root
 *   --hermes-console        eagerly boot the VT220 Hermes VM panel
 * Positional args (must come last):
 *   forgeHome               default = ~/.local/forge (ForgeHome.defaultHome)
 *   repoDir                 default = cwd
 */
/** The one database this daemon serves; also the first path segment of the Couch surface. */
const val COUCH_DB_NAME: String = "trikeshed"

/** Absorb the live classpath into the store; returns the attachment count. Skips directories that do not exist yet. */
internal fun reconcileBuildPlane(
    classesGateway: WorktreeCouchGateway, classesDir: File,
    libGateway: WorktreeCouchGateway, libDir: File,
    revision: String,
): Int {
    var n = 0
    if (classesDir.isDirectory) n += classesGateway.reconcile(classesDir.absolutePath, "oroboros", revision, System.currentTimeMillis()).paths.size
    if (libDir.isDirectory) n += libGateway.reconcile(libDir.absolutePath, "oroboros", revision, System.currentTimeMillis()).paths.size
    return n
}

object OroborosDaemon {

    const val DEFAULT_INTERVAL_MS = 30_000L
    const val DEFAULT_MAX_SLOTS = 15
    const val DEFAULT_KANBAN_PORT = 8888

    data class DaemonConfig(
        val watch: Boolean,
        val intervalMs: Long,
        val maxSlots: Int,
        val kanbanPort: Int,
        val hermesRoot: String?,
        val hermesSleeve: String?,
        val hermesConsole: Boolean,
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
        var hermesRoot: String? = null
        var hermesSleeve: String? = null
        var hermesConsole = false
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
            ForgeCliArgs.Flag(name = "--hermes-root", withValue = true) { a, i ->
                hermesRoot = a[i]
                i + 1
            },
            ForgeCliArgs.Flag(name = "--hermes-sleeve", withValue = true) { a, i ->
                hermesSleeve = a[i]
                i + 1
            },
            ForgeCliArgs.Flag(name = "--hermes-console") { _, i ->
                hermesConsole = true
                i + 1
            },
        )

        when (val r = ForgeCliArgs.parse(args.toList(), flags)) {
            is ForgeCliArgs.Result.Parsed -> positional.addAll(r.remaining)
            ForgeCliArgs.Result.Help -> { usage(); exitProcess(0) }
            is ForgeCliArgs.Result.Error -> die(r.message)
        }
        return DaemonConfig(watch, intervalMs, maxSlots, kanbanPort, hermesRoot, hermesSleeve, hermesConsole, positional)
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
        val keyMux = KeyMux { env() }
        // Probe early so a missing key aborts before opening the HTX reactor.
        val apiKeyPresent = kotlinx.coroutines.withContext(Dispatchers.IO) { keyMux.get("JULES_API_KEY") }
        if (apiKeyPresent.isNullOrBlank()) {
            System.err.println("[OROBOROS] JULES_API_KEY not set; the conductor cannot poll Jules. Aborting.")
            exitProcess(1)
        }

        // Reset metrics before each daemon start so counters are fresh.
        FlywheelMetrics.reset()

        val config = parseConfig(args)
        // FLYWHEEL_CYCLE_INTERVAL env overrides CLI --interval-ms.
        val envInterval = System.getenv("FLYWHEEL_CYCLE_INTERVAL")
        val intervalMs = if (envInterval != null) {
            val ms = envInterval.toLongOrNull()
            if (ms != null && ms > 0) {
                System.err.println("[OROBOROS] FLYWHEEL_CYCLE_INTERVAL=${ms}ms (env override)")
                ms
            } else {
                config.intervalMs
            }
        } else {
            config.intervalMs
        }
        val watch = config.watch
        val maxSlots = config.maxSlots
        val kanbanPort = config.kanbanPort
        val positional = config.positional

        val home = System.getProperty("user.home")
            ?: die("System property user.home not set")
        val (forgeHome, repoDir) = withContext(Dispatchers.IO) {
            val canonicalForge = File(home, ".local/forge")
            val fHome = File(positional.getOrNull(0) ?: canonicalForge.absolutePath)
            val rDir = File(positional.getOrNull(1) ?: System.getProperty("user.dir"))
            val gitDir = rDir.resolve(".git")
            if (!gitDir.exists()) {
                System.err.println("[OROBOROS] $rDir is not a git work tree. Aborting.")
                exitProcess(1)
            }
            fHome.mkdirs()
            Pair(fHome, rDir)
        }

        if (System.getProperty("os.name").lowercase().contains("linux")) {
            bpfProbeAttach(-1, Tracepoints.SYS_ENTER_SOCKET)
            bpfProbeAttach(-1, Tracepoints.SYS_ENTER_CONNECT)
            bpfProbeAttach(-1, Tracepoints.SYS_ENTER_EXECVE)
            bpfProbeAttach(-1, Tracepoints.SYS_ENTER_OPENAT)
        }

        val driver = FlywheelDriver(
            keyMux = keyMux,
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
        driver.attachHtxElement(htxElement)

        // ── MuxReactorElement: the live key+quota surface ────────────────
        // Owns lease/quota state for both ModelMux.chat/stream/embed and the
        // Brain/Jules dispatch paths. Before this wiring, the KeyMux
        // ReactorSource and ModelMux lease/release calls were unreachable:
        // no MuxReactorElement.Key was ever present in any coroutine context,
        // so every quota edge was dead code in production.
        val muxReactor = MuxReactorElement(
            initialConfig = MuxReactorConfig(),
            parentJob = coroutineContext[kotlinx.coroutines.Job],
        )
        muxReactor.open()
        // Seed from already-resolved KeyMux env keys so the ReactorSource
        // (read path `llm.*.key`) returns real keyIds the very first cycle.
        // Each resolved key becomes one MuxCredentialRecord; later calls to
        // ModelMux.session(...).authKey() reach the pool via the ReactorSource.
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            for (provider in listOf(
                "jules", "brain"
            )) {
                val v = keyMux.get("$provider.default.key") ?: continue
                muxReactor.loadCredentialPool(
                    mapOf(provider to listOf(
                        borg.trikeshed.userspace.reactor.MuxCredentialRecord(
                            id = "$provider-default",
                            label = "$provider-default",
                            baseUrl = "",
                            lastStatus = "active",
                        )
                    ))
                )
                // store the resolved value on the entry for ReactorSource reads
                muxReactor.recordAccess(
                    keyId = "$provider-default",
                    provider = provider,
                    label = "$provider-default",
                )
            }
        }
        System.err.println("[OROBOROS] MuxReactor open: ${muxReactor.state} — KeyMux/ModelMux live")

        // ── TorrentElement: BitTorrent v2 + uTP transport sharing HTX/TLS ──
        // Tracker announces flow through the same HtxElement → HtxReactorElement
        // → JvmTlsCodecBackend path as Jules/ModelMux. Piece data lands in the
        // BlockStore (content-addressed, same CID contract as IPFS blocks).
        // The TorrentElement owns its own CoroutineScope(supervisor) internally;
        // cleanup is via close() in the finally block.
        val torrentElement = TorrentElement(
            parentJob = coroutineContext[kotlinx.coroutines.Job],
            reactorContext = htxElement + muxReactor + nioSupervisor,
        )
        torrentElement.open()
        System.err.println("[OROBOROS] TorrentElement open: ${torrentElement.state} — torrent:// via HTX/TLS + uTP datagrams")

        // ── The store: CAS-collapsed Couch (rev hash = body blob CID) over the forge-home CAS ──
        // Built before the HTTP tier so the server can host the PWA and the build out of it.
        val fileOps = JvmFileOperations()
        val casStore = FileCasStore(fileOps, fileOps.resolvePath(forgeHome.absolutePath, "cas"))
        val couchStore = borg.trikeshed.couch.CouchStoreFactory.casBacked(casStore)
        val attachmentGateway = CouchAttachmentGateway(couchStore, casStore)
        val gitCouchGateway = GitCouchGateway(fileOps, attachmentGateway)
        val worktreeCouchGateway = WorktreeCouchGateway(fileOps, attachmentGateway)
        // The running build is an attachment set too (rxf-rsync lineage: classes and jars served
        // from the store). `build/…` is excluded from the worktree plane, so two narrow gateways
        // absorb exactly the live classpath: build/live/classes and build/staging/lib.
        val buildClassesDir = File(repoDir, "build/live/classes")
        val stagingLibDir = File(repoDir, "build/staging/lib")
        val buildClassesGateway = WorktreeCouchGateway(
            fileOps, attachmentGateway,
            prefix = WorktreeCouchGateway.WORKTREE_PREFIX + "build/live/classes/",
            excludedSegments = emptySet(), excludedRelativePrefixes = emptySet(),
        )
        val stagingLibGateway = WorktreeCouchGateway(
            fileOps, attachmentGateway,
            prefix = WorktreeCouchGateway.WORKTREE_PREFIX + "build/staging/lib/",
            excludedSegments = emptySet(), excludedRelativePrefixes = emptySet(),
        )
        // ── Agent home: ~/.hermes is colocated history, not repo state — a fourth narrow gateway,
        // same shape as the build planes. Excludes are the agent's OWN install/cache material
        // (venv, tool binaries, thumbnail/image/audio caches, scratch, per-profile sandboxes) —
        // reinstallable, not "history". Everything else (sessions, skills, state.db, logs, kanban,
        // memories, cron, config) is what "teleport a clone" means: the agent's memory, not its jar.
        // ONE-SHOT, no watcher: a home directory's caches/logs churn constantly and a live watch on
        // 2.7GB of it would dwarf the repo's own file-event volume for no replication benefit.
        val hermesHomeDir = File(System.getProperty("user.home"), ".hermes")
        val hermesHomeGateway = WorktreeCouchGateway(
            fileOps, attachmentGateway,
            prefix = "homes/hermes/",
            excludedSegments = setOf(
                "hermes-agent", "bin", "cache", "image_cache", "audio_cache", "lsp", "plugins",
                "scratch", "sandboxes", "venv", "node_modules", "__pycache__", ".git", ".curator_backups",
            ),
        )
        val couchDb = borg.trikeshed.couch.CouchDatabase(COUCH_DB_NAME, couchStore, casStore)
        couchDb.ensureDesignDoc(vhostRoot = "docs/")
        // Peer exchange for _replicate rides the same HTX reactor as Jules/ModelMux — no JDK client.
        val peerHttp = borg.trikeshed.couch.replicate.HttpExchange { method, url, body, contentType ->
            val req = borg.trikeshed.htx.parseHtxRequest(
                url = url,
                method = borg.trikeshed.htx.HtxMethod.valueOf(method.uppercase()),
                body = body?.let { borg.trikeshed.lib.ByteSeries(it) } ?: borg.trikeshed.htx.emptyHtxBody(),
            ).let { r -> if (contentType != null) r.copy(headers = borg.trikeshed.htx.htxHeaders("Content-Type" j contentType)) else r }
            val resp = htxElement.request(req)
            borg.trikeshed.couch.replicate.HttpReply(resp.status, resp.body.toArray())
        }
        val couchWire = borg.trikeshed.forge.server.CouchWire(
            router = borg.trikeshed.couch.CouchWireRouter(couchDb, WorktreeCouchGateway.WORKTREE_PREFIX),
            replicator = borg.trikeshed.couch.replicate.CouchReplicator(couchDb, peerHttp),
            // NOT the runBlocking scope: async/continuous replication must run on real workers,
            // not queued behind the daemon's single-threaded root event loop.
            scope = CoroutineScope(SupervisorJob(coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default),
        )

        // Kanban HTTP server (CCEK litebike listener, no JDK networking) — starts before
        // the reactive cycle so the port is bound. Driver is available at this point.
        // The Couch wire is mounted on the same listener: `/` is the store-hosted PWA, `/trikeshed/…`
        // the 1.6/1.7 surface, `/api/…` the built-ins — one port, one reactor.
        // ── Graal console: JFR/JMX instrument cluster + sub-VM host + reactor-served RTS view.
        //    All of it rides the SAME litebike listener (extraRoutes/SSE respond seam) — the
        //    userspace uring stack stays the only channelization substrate; JFR/JMX are in-process.
        val daemonBlackboard = borg.trikeshed.graal.ConfixBlackboard.empty()
        val jvmVitals = borg.trikeshed.graal.vitals.JvmVitals().also { it.start() }
        val vmHost = borg.trikeshed.vm.HypervisorVmHost(borg.trikeshed.graal.subvm.Hypervisor(blackboard = daemonBlackboard))
        borg.trikeshed.vm.VmSupervisor.install(vmHost)
        val wireScope = CoroutineScope(SupervisorJob(coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default)
        val vmWire = borg.trikeshed.forge.server.VmWire(vmHost, wireScope)
        val hermesConsole = borg.trikeshed.hermes.HermesVmConsole(
            root = File(
                config.hermesRoot ?: System.getenv("HERMES_SOURCE_ROOT")
                ?: File(home, ".hermes/hermes-agent").absolutePath,
            ).toPath(),
            sleeve = File(
                config.hermesSleeve ?: System.getenv("HERMES_GRAAL_SLEEVE")
                ?: File(repoDir, "graalpy-sleeve/hermes").absolutePath,
            ).toPath(),
        )
        val hermesWire = borg.trikeshed.forge.server.HermesConsoleWire(hermesConsole, wireScope)
        if (config.hermesConsole) wireScope.launch(Dispatchers.IO) { hermesConsole.open() }
        val reportReactorForWires = CouchReportReactorElement(parentJob = coroutineContext[kotlinx.coroutines.Job])
        launch { reportReactorForWires.open() }
        val graalWire = borg.trikeshed.forge.server.GraalWire(
            jvmVitals,
            couchStore,
            reportReactorForWires,
            wireScope,
            couchDb,
            vmHost,
        )

        val kanbanServer = JvmKanbanServer(
            driver,
            extraRoutes = listOf(graalWire::route, vmWire::route, hermesWire::route),
            rawRoutes = listOf(graalWire::ingestRoute, couchWire::route),
            streamingPaths = borg.trikeshed.forge.server.CouchWire.streamingPaths(COUCH_DB_NAME) +
                borg.trikeshed.forge.server.GraalWire.STREAMING +
                borg.trikeshed.forge.server.VmWire.STREAMING +
                borg.trikeshed.forge.server.HermesConsoleWire.STREAMING,
            maxRequestBatch = 4096,
        )
        val kanbanJob = SupervisorJob(coroutineContext[kotlinx.coroutines.Job])
        if (watch) {
            launch(CoroutineScope(kanbanJob).coroutineContext + Dispatchers.Default) {
                // Boot race seen in the wild: bindAndServe dies with "Parent job is Cancelling"
                // moments after the port binds. Until the racing job is named, the server RETRIES —
                // a daemon whose HTTP tier can be killed once at boot must not stay headless.
                var attempt = 0
                while (isRunning) {
                    try {
                        kanbanServer.run(kanbanPort, null)
                        break
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException && !isRunning) break
                        attempt++
                        System.err.println("[OROBOROS] Kanban server failed (attempt $attempt): ${t.message}")
                        t.printStackTrace()
                        if (attempt >= 5) { System.err.println("[OROBOROS] Kanban server giving up after $attempt attempts"); break }
                        kotlinx.coroutines.delay(1500L * attempt)
                    }
                }
            }
            System.err.println("[OROBOROS] Kanban HTTP server launching on :$kanbanPort (CCEK litebike) — Couch 1.6 surface at /$COUCH_DB_NAME, PWA hoisted at /")
        } else {
            System.err.println("[OROBOROS] --once mode: Kanban server skipped")
        }
        
        // ── Pointcut Subsystem ──
        // Connect the pointcut adapter to the actual process-wide ConfixBlackboard instance if it existed globally. 
        // Currently, we'll continue providing an empty blackboard here as there's no pre-existing global ConfixBlackboard exposed to OroborosDaemon.
        // And PointcutCouchProjection ensures it propagates pointcut landings to couch.
        val pointcutAdapter = borg.trikeshed.pointcut.PointcutBlackboardAdapter(daemonBlackboard)
        pointcutAdapter.install()
        val pointcutProjection = borg.trikeshed.pointcut.PointcutCouchProjection(couchStore, pointcutAdapter, CoroutineScope(Dispatchers.Default))

        // ── Memory store + ISAM index layer (fs-memory Prongs 1+2) ──
        // MemoryStore composes the existing CAS+Couch into the paper's
        // memory store M. MemoryIndexLayer subscribes to mutations and
        // maintains taxonomy/temporal/provenance ISAM routes.
        val memoryStore = borg.trikeshed.memory.MemoryStore(casStore, couchStore)
        val memoryIndex = borg.trikeshed.memory.MemoryIndexLayer(memoryStore)
        val couchIndexBridge = borg.trikeshed.memory.CouchIndexBridge(attachmentGateway, memoryIndex)
        driver.attachMemoryIndexLayer(memoryIndex)
        System.err.println("[OROBOROS] MemoryStore + MemoryIndexLayer: ${memoryIndex.route(borg.trikeshed.memory.IndexKind.Taxonomy).entryCount} taxonomy entries")

        // ── Memory bridge: routes memory-eligible reconcile files through
        //    MemoryStore.put() so they get per-line spines + IPFS publication.
        val ipfsBridge = borg.trikeshed.cas.IpfsBridge(casStore)
        val memoryBridge = borg.trikeshed.util.oroboros.MemoryBridge(
            memoryStore,
            attachmentGateway,
            ipfsBridge,
        )

        // ── Reactive git-state hub: JvmFileWatchReactorElement watches .git/**
        //    and feeds the GitCouchGateway + git-state cache. This IS the
        //    choreography — coroutine pipelines that react to filesystem
        //    events, not blocking ProcessBuilder calls. ──
        val gitState = GitStateCache(repoDir)
        val cycleTriggers = Channel<Unit>(Channel.CONFLATED)
        val gitWatcher = JvmFileWatchReactorElement(
            root = repoDir.absolutePath,
            parentJob = coroutineContext[kotlinx.coroutines.Job],
            includeGlobs = listOf(".git/**"),
            excludeGlobs = emptyList(),
        )
        launch(Dispatchers.IO) { gitWatcher.open() }
        System.err.println("[OROBOROS] Git watcher: ${gitWatcher.state} — reactive .git/** events")

        // The Jules causal WAL is an event source, not an out-of-band operator
        // surface. External queue/review appends wake the same serialized cycle
        // that owns API polling, drain, settlement, and dispatch.
        val julesWalWatcher = JvmFileWatchReactorElement(
            root = forgeHome.absolutePath,
            parentJob = coroutineContext[kotlinx.coroutines.Job],
            includeGlobs = listOf("jules-board.wal"),
            excludeGlobs = emptyList(),
        )
        launch(Dispatchers.IO) { julesWalWatcher.open() }
        launch {
            for (event in julesWalWatcher.events) {
                cycleTriggers.trySend(Unit)
                println("[OROBOROS] jules-wal-event: ${event.type}")
            }
        }
        System.err.println("[OROBOROS] Jules WAL watcher: ${julesWalWatcher.state} — serialized cycle trigger")

        // Working-tree plane: source and document files live under Couch/CAS,
        // independently of the `.git/**` identity plane above.
        // ── Composable Reconcile Elements (CCEK) ──
        val worktreeReconcileElement = borg.trikeshed.util.oroboros.element.WorktreeReconcileElement(
            repoRoot = repoDir.absolutePath,
            worktreeCouchGateway = worktreeCouchGateway,
            couchIndexBridge = couchIndexBridge,
            memoryBridge = memoryBridge,
            headShaProvider = { gitState.headSha() },
            parentJob = coroutineContext[kotlinx.coroutines.Job]
        )
        launch(Dispatchers.IO) { worktreeReconcileElement.open() }

        val gitReconcileElement = borg.trikeshed.util.oroboros.element.GitReconcileElement(
            forgeHome = repoDir.absolutePath,
            gitCouchGateway = gitCouchGateway,
            couchIndexBridge = couchIndexBridge,
            headShaProvider = { gitState.headSha() },
            awaitObjectsDirty = { gitState.awaitObjectsDirty() },
            parentJob = coroutineContext[kotlinx.coroutines.Job]
        )
        launch(Dispatchers.IO) { gitReconcileElement.open() }

        val worktreeWatcher = JvmFileWatchReactorElement(
            root = repoDir.absolutePath,
            parentJob = coroutineContext[kotlinx.coroutines.Job],
            includeGlobs = emptyList(),
            excludeGlobs = listOf(
                ".git/**", ".gradle/**", ".idea/**", "build/**", "node_modules/**",
            ),
        )
        launch(Dispatchers.IO) { worktreeWatcher.open() }
        System.err.println("[OROBOROS] Worktree watcher: ${worktreeWatcher.state} — reactive source/document events")

        launch {
            // Seismic damping: a build refeed or git fetch is thousands of events in seconds;
            // one line per 5s window with a magnitude, instead of an earthquake in the log.
            var windowStart = 0L; var windowCount = 0; var lastPath = ""
            for (event in worktreeWatcher.events) {
                worktreeReconcileElement.worktreeDirty.trySend(Unit)
                cycleTriggers.trySend(Unit)
                val now = System.currentTimeMillis()
                if (now - windowStart > 5_000) {
                    if (windowCount > 1) println("[OROBOROS] worktree-quake: $windowCount events in 5s (last: $lastPath)")
                    else if (windowCount == 1) println("[OROBOROS] worktree-event: $lastPath")
                    windowStart = now; windowCount = 0
                }
                windowCount++; lastPath = "${event.type} ${event.path}"
            }
        }

        // Build plane: the hot-swap feed rewrites build/live/classes (and stageDaemonLib the jars);
        // each generation is re-absorbed so the store always serves the classes that are running.
        val buildDirty = Channel<Unit>(Channel.CONFLATED)
        for (dir in listOf(buildClassesDir, stagingLibDir)) {
            if (!dir.isDirectory) continue
            val w = JvmFileWatchReactorElement(
                root = dir.absolutePath,
                parentJob = coroutineContext[kotlinx.coroutines.Job],
                includeGlobs = emptyList(),
                excludeGlobs = emptyList(),
            )
            launch(Dispatchers.IO) { w.open() }
            launch { for (e in w.events) buildDirty.trySend(Unit) }
        }
        launch(Dispatchers.IO) {
            for (unit in buildDirty) {
                kotlinx.coroutines.delay(750) // coalesce a generation's burst of class writes
                runCatching {
                    val n = reconcileBuildPlane(buildClassesGateway, buildClassesDir, stagingLibGateway, stagingLibDir, gitState.headSha())
                    println("[OROBOROS] build-event: classpath re-absorbed ($n attachments)")
                }.onFailure { println("[OROBOROS] build-event: reconcile failed ${it.message}") }
            }
        }

        // Choreography 1: git filesystem events → GitStateCache invalidation.
        // The cache holds headSha, treeClean, and refHead — read from .git
        // files directly (no ProcessBuilder). File events invalidate the
        // relevant cache entry; the next cycle reads fresh values.
        launch {
            var lastRefLogMs = 0L
            for (event in gitWatcher.events) {
                cycleTriggers.trySend(Unit)
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
                        if (System.currentTimeMillis() - lastRefLogMs > 5_000) {
                            lastRefLogMs = System.currentTimeMillis()
                            println("[OROBOROS] git-event: ref changed → ${event.path} (further ref churn sampled 1/5s)")
                        }
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

        // Initial two-plane reconcile. File reads and CAS writes stay off the
        // reactor thread.
        withContext(Dispatchers.IO) {
            runCatching {
                val headSha = gitState.headSha()
                val snap = gitCouchGateway.reconcile(
                    forgeHome = repoDir.absolutePath,
                    agentId = "oroboros",
                    revision = headSha,
                    sequence = System.currentTimeMillis(),
                )
                couchIndexBridge.indexReconciliation(
                    GitCouchGateway.GIT_PREFIX,
                    snap.paths.size j { i: Int -> snap.paths[i] },
                )
                System.err.println("[OROBOROS] Git→Couch initial reconcile: ${snap.paths.size} paths @ ${headSha.take(12)}")

                val worktreeSnap = worktreeCouchGateway.reconcile(
                    repoRoot = repoDir.absolutePath,
                    agentId = "oroboros",
                    revision = headSha,
                    sequence = System.currentTimeMillis(),
                )
                couchIndexBridge.indexReconciliation(
                    WorktreeCouchGateway.WORKTREE_PREFIX,
                    worktreeSnap.paths.size j { i: Int -> worktreeSnap.paths[i] },
                )
                val bridged = memoryBridge.bridge(worktreeSnap, agentId = "oroboros")
                System.err.println(
                    "[OROBOROS] Worktree→Couch initial reconcile: ${worktreeSnap.paths.size} paths, " +
                        "$bridged memory files bridged (spines + IPFS)"
                )
                val buildPaths = reconcileBuildPlane(buildClassesGateway, buildClassesDir, stagingLibGateway, stagingLibDir, headSha)
                System.err.println("[OROBOROS] Build→Couch initial reconcile: $buildPaths classpath attachments (build/live/classes + build/staging/lib)")
                if (hermesHomeDir.isDirectory) {
                    val hermesSnap = hermesHomeGateway.reconcile(hermesHomeDir.absolutePath, "oroboros", headSha, System.currentTimeMillis())
                    System.err.println("[OROBOROS] Hermes home→Couch initial reconcile: ${hermesSnap.paths.size} paths (teleportable clone of ~/.hermes)")
                } else {
                    System.err.println("[OROBOROS] Hermes home skipped: $hermesHomeDir not found")
                }
            }.onFailure {
                System.err.println("[OROBOROS] initial reconcile failed: ${it.message}")
                it.printStackTrace()
            }
        }

        // ── Couch report reactor: CCEK element for map/reduce events ──
        val reportReactor = reportReactorForWires
        System.err.println("[OROBOROS] Couch report reactor: ${reportReactor.state} — feeding the Graal console flourish feed")

        // ── Tendon: _changes → report bus + Rete facts. Every committed revision (local write,
        //    reconcile, or a peer's replication) is a fact in the production system; the git object
        //    plane stays out (opaque blobs carry no fields worth matching).
        val rete = borg.trikeshed.dag.ReteNetwork()
        val changesFacts = borg.trikeshed.couch.CouchChangesFactElement(
            db = couchDb,
            rete = rete,
            report = reportReactor,
            admit = { !it.docId.startsWith("_design/") && !it.docId.startsWith(GitCouchGateway.GIT_PREFIX) },
            parentJob = kanbanJob,
        )
        launch {
            changesFacts.open()
            System.err.println("[OROBOROS] Changes→Rete tendon: ${changesFacts.state} — ${changesFacts.factsApplied} facts from the initial reconcile, commits=${reportReactor.reportState.value.commits}")
        }

        val mainJob = coroutineContext[kotlinx.coroutines.Job]
        val reactiveJob = SupervisorJob(mainJob)
        val reactiveScope = CoroutineScope(coroutineContext + reactiveJob)

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
                consecutivePollErrors.incrementAndGet()
            } else if (ev is FlywheelEvent.Polled) {
                consecutivePollErrors.set(0)
            }
            val now = System.currentTimeMillis()
            when (ev) {
                is borg.trikeshed.jules.FlywheelDriver.FlywheelEvent.Polled ->
                    borg.trikeshed.userspace.reactor.KanbanFSM.kanbanEvents.tryEmit(
                        borg.trikeshed.userspace.reactor.KanbanEvent.CycleObserved(0L, 0, 0, ev.alive, ev.available, now)
                    )
                is borg.trikeshed.jules.FlywheelDriver.FlywheelEvent.Drained ->
                    borg.trikeshed.userspace.reactor.KanbanFSM.kanbanEvents.tryEmit(
                        borg.trikeshed.userspace.reactor.KanbanEvent.PatchDrained(ev.sessionId, ev.sha, ev.tag, now)
                    )
                is borg.trikeshed.jules.FlywheelDriver.FlywheelEvent.Dispatched ->
                    borg.trikeshed.userspace.reactor.KanbanFSM.kanbanEvents.tryEmit(
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
                serverSocket.configureBlocking(false)
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
                    if (client == null) {
                        delay(100)
                        continue
                    }
                    val report = lastCycleReport
                    val uptimeMs = System.currentTimeMillis() - daemonStartTime
                    val (alive, avail, phase) = if (report != null) {
                        Triple(report.alive, report.available, report.phase.name)
                    } else {
                        Triple(-1, -1, "NONE")
                    }

                    // Backward-compatible ALIVE line
                    val aliveLine = "ALIVE $uptimeMs ${report?.cycleMs ?: -1} ${report?.harvested ?: -1} ${report?.dispatched ?: -1} $alive $avail\n"

                    // Full metrics as JSON on subsequent lines
                    val metricsJson = try {
                        val merged = buildMap<String, Any?> {
                            put("uptimeMs", uptimeMs)
                            if (report != null) {
                                put("cycleMs", report.cycleMs)
                                put("answered", report.answered)
                                put("harvested", report.harvested)
                                put("reworked", report.reworked)
                                put("dispatched", report.dispatched)
                                put("alive", report.alive)
                                put("available", report.available)
                                put("inducted", report.inducted)
                                put("settled", report.settled)
                                put("archived", report.archived)
                                put("phase", report.phase.name)
                                put("conflicts", report.conflicts)
                                put("panoramaSize", report.panorama.size)
                                put("http429", report.http429)
                                put("http5xx", report.http5xx)
                            } else {
                                put("cycleMs", -1L)
                                put("answered", -1)
                                put("harvested", -1)
                                put("reworked", -1)
                                put("dispatched", -1)
                                put("alive", -1)
                                put("available", -1)
                                put("inducted", -1)
                                put("settled", false)
                                put("archived", -1)
                                put("phase", "NONE")
                                put("conflicts", emptyList<String>())
                                put("panoramaSize", 0)
                                put("http429", -1)
                                put("http5xx", -1)
                            }
                            // FlywheelMetrics summary
                            put("metrics", FlywheelMetrics.toJsonMap())
                        }
                        "METRICS " + JsonSupport.stringify(merged) + "\n"
                    } catch (e: Exception) {
                        "METRICS {}\n"
                    }

                    val metricsBytes = (aliveLine + metricsJson).toByteArray()
                    val buf = ByteBuffer.allocate(metricsBytes.size)
                    buf.put(metricsBytes)
                    buf.flip()
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

        // Hot-swappable telemetry + quarantine guard. Same instance, JVM
        // retransforms the class in place — next call sees new bytecode. The
        // reactive choreography owns all poll/drain/dispatch; CycleBody only
        // observes and traces.
        // ⚡ Bolt: Offload blocking file operations (file rotation and writes) to a dedicated single-threaded IO dispatcher to guarantee sequential FIFO ordering without blocking the main event loop.
        var currentTw = traceWriter
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                currentTw?.flush()
                currentTw?.close()
            } catch (e: Exception) { /* ignore */ }
        })
        val traceMutex = kotlinx.coroutines.sync.Mutex()
        val cycleBody = CycleBody(
            driver = driver,
            repoDir = repoDir,
            consecutivePollErrors = consecutivePollErrors,
            traceWriter = traceWriter?.let { _ ->
                { json ->
                    launch(TraceIoDispatcher.asCoroutineDispatcher) {
                        traceMutex.withLock {
                            try {
                                if (traceLineCount >= 10000) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        currentTw?.close()
                                        val backup = File(traceFile.parentFile, traceFile.name + ".1")
                                        if (traceFile.exists()) {
                                            try {
                                                java.nio.file.Files.move(traceFile.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                            } catch (e: Exception) {}
                                        }
                                        currentTw = FileOutputStream(traceFile, false).bufferedWriter()
                                    }
                                    traceLineCount = 0
                                }
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    currentTw?.write(json)
                                    currentTw?.write("\n")
                                    currentTw?.flush()
                                }
                                traceLineCount++
                            } catch (e: Exception) {
                                System.err.println("[OROBOROS] warning: failed to write trace file: ${e.message}")
                            }
                        }
                    }
                    Unit
                }
            },
        )
        cycleBodyField = cycleBody

        try {
            if (watch) {
                withContext(htxElement + muxReactor) {
                    // Do not pass this withContext scope: it waits for every
                    // child it owns, including the infinite reactive loops.
                    // Launch them under mainImpl's parent scope while this
                    // context supplies HtxKey for capture.
                    driver.startReactiveCycle(reactiveScope, cycleTriggers)
                }
                while (isRunning) {
                    val errors = consecutivePollErrors.get()
                    val backoffMs = kotlin.math.min(a = intervalMs * (1L shl kotlin.math.min(a = errors, b = 30)), b = intervalMs * 5)
                    if (errors > 0) System.err.println("[OROBOROS] backoff=${backoffMs}ms consecutiveErrors=$errors")
                    delay(backoffMs)

                    // Adaptive throughput backpressure: when the flywheel is sustaining
                    // ≥ 100 cycles/day, throttle the poll cadence by doubling the
                    // backoff interval. This prevents unnecessary Jules API calls
                    // and reduces daemon CPU overhead without starving pending tasks.
                    if (FlywheelMetrics.cyclesAt100PerDay) {
                        System.err.println("[OROBOROS] throughput ≥ 100/day — throttling cadence (double backoff)")
                        delay(intervalMs)
                    }

                    try {
                        (cycleBodyField ?: cycleBody).run()
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
            } else {
                // --once: run one reactive tick synchronously.
                withContext(htxElement + muxReactor) {
                    driver.startReactiveCycle(reactiveScope, cycleTriggers)
                }
                delay(intervalMs * 2)
                try {
                    (cycleBodyField ?: cycleBody).run()
                } catch (t: Throwable) {
                    System.err.println("[OROBOROS] cycleBody.run escaped: ${t.javaClass.simpleName}: ${t.message?.take(200)}")
                }
                isRunning = false
                reactiveJob.cancelAndJoin()
                return
            }
        } finally {
            withContext(NonCancellable) {
                reactiveJob.cancelAndJoin()
                healthJob.cancel()
                try { serverSocket.close() } catch (_: Exception) {}
                healthJob.cancelAndJoin()
                if (healthSock.exists()) healthSock.delete()
                try { traceWriter?.flush(); traceWriter?.close() } catch (_: Exception) {}
                runCatching { kanbanJob.cancel() }
                runCatching { hermesConsole.close() }
                runCatching { reportReactor.close() }
                runCatching { memoryIndex.close() }
                runCatching { worktreeReconcileElement.close() }
                runCatching { gitReconcileElement.close() }
                runCatching { worktreeWatcher.close() }
                runCatching { gitWatcher.close() }
                runCatching { julesWalWatcher.close() }
                try { htxElement.close() } catch (_: Exception) {}
                try { torrentElement.close() } catch (_: Exception) {}
                try { muxReactor.close() } catch (_: Exception) {}
                try { nioSupervisor.close() } catch (_: Exception) {}
                driver.close()
            }
        }
    }

    private suspend fun preflight(repoDir: File, driver: FlywheelDriver): Boolean {
        // git fetch origin master (best-effort, 5s timeout)
        val fetchOk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val fetch = ProcessBuilder("git", "fetch", "origin", "master", "--dry-run")
                .directory(repoDir).start()
            val finished = fetch.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) fetch.destroyForcibly()
            finished && fetch.exitValue() == 0
        }
        if (!fetchOk) return true // offline is OK, we'll poll anyway

        suspend fun command(vararg args: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val p = ProcessBuilder(*args).directory(repoDir).redirectErrorStream(true).start()
            val finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            p.inputStream.bufferedReader().readText().trim()
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
            """usage: OroborosDaemon [--once | --watch] [--interval-ms N] [--max-slots N] [--kanban-port N]
              [--hermes-root PATH] [--hermes-sleeve PATH] [--hermes-console] [forgeHome] [repoDir]
              env: JULES_API_KEY (required)
                   HERMES_SOURCE_ROOT / HERMES_GRAAL_SLEEVE (optional)
              forgeHome default: ~/.local/forge (ForgeHome.defaultHome)
              repoDir  default: cwd
              kanban-port default: 8888"""
        )
    }
}
