package borg.trikeshed.daemon

import borg.trikeshed.couch.CouchReportReactorElement
import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.torrent.TorrentElement
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.lib.j
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.nio.ebpf.bpfProbeAttach
import borg.trikeshed.userspace.nio.ebpf.Tracepoints
import borg.trikeshed.util.io.ForgeCliArgs
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.util.oroboros.GitCouchGateway
import borg.trikeshed.util.oroboros.JvmFileWatchReactorElement
import borg.trikeshed.util.oroboros.WorktreeCouchGateway
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.ccek.CCEK
import borg.trikeshed.userspace.reactor.MuxReactorConfig
import keymux.KeyMux
import keymux.EnvSource
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
 * Oroboros daemon — serves the Couch/kanban/console surfaces.
 *
 * The Jules flywheel (FlywheelDriver + CycleBody) was deleted 2026-08-24 as a
 * failed migration; the daemon no longer polls, drains, or dispatches sessions.
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

/**
 * The absorbed classpath planes plus the durable manifest that makes them bootable.
 *
 * The Couch head projection is in-memory (CouchStoreFactory.casBacked), so blobs in the
 * forge-home CAS are unaddressable across a restart without an index. The manifest is that
 * index: `.oroboros/manifests/classpath.tsv` — one `kind\trelpath\tsha256hex\tlength` line
 * per classpath file — lets bin/oroboros-daemon hydrate a runtime classpath straight out of
 * `cas/sha256/` on a machine with no gradle build at all (a forge home replicated to a
 * separate instance carries its own install).
 */
internal class BuildPlanes(
    val attachments: CouchAttachmentGateway,
    val classesGateway: WorktreeCouchGateway, val classesDir: File,
    val libGateway: WorktreeCouchGateway, val libDir: File,
    val resourcesGateway: WorktreeCouchGateway, val resourcesDir: File,
    /** build/libs/hotswap-agent.jar — the javaagent rides the manifest under kind `agent`. */
    val agentJar: File,
    val manifestFile: File,
) {
    val classesPrefix = WorktreeCouchGateway.WORKTREE_PREFIX + "build/live/classes/"
    val libPrefix = WorktreeCouchGateway.WORKTREE_PREFIX + "build/staging/lib/"
    val resourcesPrefix = WorktreeCouchGateway.WORKTREE_PREFIX + "build/resources/"
    val agentPrefix = WorktreeCouchGateway.WORKTREE_PREFIX + "build/agent/"
}

/** Absorb the live classpath into the store; returns the attachment count. Skips directories that do not exist yet. */
internal fun reconcileBuildPlane(planes: BuildPlanes, revision: String): Int {
    var n = 0
    with(planes) {
        if (classesDir.isDirectory) n += classesGateway.reconcile(classesDir.absolutePath, "oroboros", revision, System.currentTimeMillis()).paths.size
        if (libDir.isDirectory) n += libGateway.reconcile(libDir.absolutePath, "oroboros", revision, System.currentTimeMillis()).paths.size
        if (resourcesDir.isDirectory) n += resourcesGateway.reconcile(resourcesDir.absolutePath, "oroboros", revision, System.currentTimeMillis()).paths.size
        if (agentJar.isFile) {
            val bytes = agentJar.readBytes()
            val cid = borg.trikeshed.job.ContentId.of(bytes)
            val path = agentPrefix + agentJar.name
            if (attachments.listAttachments(path).none { it.path == path && it.contentId == cid }) {
                attachments.putAttachment(
                    borg.trikeshed.util.oroboros.OroborosAttachmentRef(
                        path = path, contentType = "application/java-archive",
                        length = bytes.size.toLong(), contentId = cid,
                        agentId = "oroboros", revision = revision, sequence = System.currentTimeMillis(),
                    ),
                    bytes,
                )
            }
            n += 1
        }
        writeClasspathManifest(planes, revision)
    }
    return n
}

/** Atomic (tmp+rename) manifest write; an empty classpath never clobbers a previously good manifest. */
internal fun writeClasspathManifest(planes: BuildPlanes, revision: String) {
    val kinds = listOf(
        "classes" to planes.classesPrefix,
        "lib" to planes.libPrefix,
        "resources" to planes.resourcesPrefix,
        "agent" to planes.agentPrefix,
    )
    val lines = StringBuilder("# oroboros classpath manifest\n# revision $revision\n")
    var entries = 0
    for ((kind, prefix) in kinds) {
        for (ref in planes.attachments.listAttachments(prefix).sortedBy { it.path }) {
            lines.append(kind).append('\t').append(ref.path.removePrefix(prefix))
                .append('\t').append(ref.contentId.hex).append('\t').append(ref.length).append('\n')
            entries++
        }
    }
    if (entries == 0) return
    val file = planes.manifestFile
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(lines.toString())
    if (!tmp.renameTo(file)) {
        file.writeText(lines.toString())
        tmp.delete()
    }
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
        val positional: List<String>,
        /** Extra scopes mounted at boot: --project <path> (repeatable). Git repo → projects/<name>/, else assets/<name>/. */
        val projects: List<String> = emptyList(),
        /** Dynamic modules attached at boot: --module <fqcn> (repeatable). Proxy-ctor loaded (app CP, then build/live). */
        val modules: List<String> = emptyList(),
    )

    @Volatile
    var daemonStartTime = 0L

    @Volatile
    var isRunning = true

    fun parseConfig(args: Array<String>): DaemonConfig {
        var watch = true
        var intervalMs = DEFAULT_INTERVAL_MS
        var maxSlots = DEFAULT_MAX_SLOTS
        var kanbanPort = DEFAULT_KANBAN_PORT
        var hermesRoot: String? = null
        var hermesSleeve: String? = null
        var hermesConsole = false
        val positional = mutableListOf<String>()
        val projects = mutableListOf<String>()
        val modules = mutableListOf<String>()

        val flags = listOf(
            ForgeCliArgs.Flag(name = "--once") { _, i -> watch = false; i + 1 },
            ForgeCliArgs.Flag(name = "--watch") { _, i -> watch = true; i + 1 },
            // Consumed by mainImpl via the raw args (P2 belief-bag wiring); registered so the parser accepts it.
            ForgeCliArgs.Flag(name = "--belief-bag") { _, i -> i + 1 },
            ForgeCliArgs.Flag(name = "--project", withValue = true) { a, i -> projects.add(a[i]); i + 1 },
            ForgeCliArgs.Flag(name = "--module", withValue = true) { a, i -> modules.add(a[i]); i + 1 },
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
        return DaemonConfig(watch, intervalMs, maxSlots, kanbanPort, hermesRoot, hermesSleeve, hermesConsole, positional, projects, modules)
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
        // KeyMux: harness lane first (conventional env names + hermes .env +
        // codex/opencode credential files, 5-minute discovery cache), then the
        // legacy derived-name env lane (LLM_<X>_KEY, 24-hour cache) as fallback.
        // fileOps is explicit: coroutine contexts don't reliably carry FileOperations,
        // and without it the dotenv/auth.json lanes silently degrade to env-only.
        val fileOps = JvmFileOperations()
        val keyMux = KeyMux {
            cached("*", keymux.HarnessSource(explicitFileOps = fileOps), ttlMs = 5 * 60_000L)
            cached("*", EnvSource())
        }
        // Probe early so a missing key aborts before opening the HTX reactor.
        val apiKeyPresent = kotlinx.coroutines.withContext(Dispatchers.IO) { keyMux.get("JULES_API_KEY") }
        if (apiKeyPresent.isNullOrBlank()) {
            System.err.println("[OROBOROS] JULES_API_KEY not set; Jules credential pool will be empty.")
        }

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
            // The bug class that dumps a gigabyte CAS into a source tree: forgeHome
            // resolving to (or inside) the repo worktree. Refuse outright — state
            // belongs in ~/.local forge homes, never in source.
            val fCanon = fHome.canonicalFile
            val rCanon = rDir.canonicalFile
            if (fCanon.path == rCanon.path || fCanon.path.startsWith(rCanon.path + File.separator)) {
                System.err.println("[OROBOROS] REFUSED: forgeHome $fCanon is inside the repo worktree $rCanon — daemon state never lands in source trees. Use ~/.local/forge*.")
                exitProcess(1)
            }
            fHome.mkdirs()
            Pair(fHome, rDir)
        }
        // A daemon process rooted at a repo other than TrikeShed itself (its own port +
        // forgeHome, run standalone) must not file its worktree under "projects/trikeshed/" —
        // that mislabels every absorbed path with this project's name instead of its own.
        // lowercase() keeps TrikeShed's own default byte-identical to the prior hardcoded
        // literal ("TrikeShed".lowercase() == "trikeshed"), so existing stored content stays
        // addressable under the same prefix it was written with.
        WorktreeCouchGateway.WORKTREE_PREFIX = "projects/${repoDir.name.lowercase()}/"

        if (System.getProperty("os.name").lowercase().contains("linux")) {
            bpfProbeAttach(-1, Tracepoints.SYS_ENTER_SOCKET)
            bpfProbeAttach(-1, Tracepoints.SYS_ENTER_CONNECT)
            bpfProbeAttach(-1, Tracepoints.SYS_ENTER_EXECVE)
            bpfProbeAttach(-1, Tracepoints.SYS_ENTER_OPENAT)
        }

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
        // ── CCEK binding: the single control plane for assemblies ────
        // Provides choreograph(), createUserContext(), and the bounded
        // agent fan-out that LCNC nodes, model panels, and the curator
        // all run through. Modules access it via the binding's
        // reactorScope for coroutine dispatch.
        val ccekBinding = CCEK.initialize(muxReactor)
        System.err.println("[OROBOROS] CCEK binding open: reactor=${ccekBinding.reactorScope}")
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
        // fileOps was created above (the KeyMux harness lane shares it).
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
        // Third narrow plane: jvm resources (web/*, META-INF/services) — without them a
        // forge-booted instance serves no console page. Plus the hotswap agent jar, so the
        // hydrated runtime is launchable end-to-end from the manifest alone.
        val processedResourcesDir = File(repoDir, "build/processedResources/jvm/main")
        val resourcesGateway = WorktreeCouchGateway(
            fileOps, attachmentGateway,
            prefix = WorktreeCouchGateway.WORKTREE_PREFIX + "build/resources/",
            excludedSegments = emptySet(), excludedRelativePrefixes = emptySet(),
        )
        val buildPlanes = BuildPlanes(
            attachments = attachmentGateway,
            classesGateway = buildClassesGateway, classesDir = buildClassesDir,
            libGateway = stagingLibGateway, libDir = stagingLibDir,
            resourcesGateway = resourcesGateway, resourcesDir = processedResourcesDir,
            agentJar = File(repoDir, "build/libs/hotswap-agent.jar"),
            manifestFile = File(forgeHome, ".oroboros/manifests/classpath.tsv"),
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
        // ── Incremental view registry (P2): every design-doc view marked
        //    `"incremental": true` gets one _changes-driven tendon (mapped-once,
        //    Trie-cached, `_local` checkpointed); `_view` reads marked views off the
        //    router's registry hook and never rescan the corpus. Unmarked views keep
        //    today's eager route, untouched.
        val incrementalViews = borg.trikeshed.couch.IncrementalViewRegistry(
            db = couchDb,
            parentJob = coroutineContext[kotlinx.coroutines.Job],
            log = { msg -> System.err.println("[OROBOROS] $msg") },
        )
        launch {
            incrementalViews.open()
            System.err.println("[OROBOROS] incremental-view registry: ${incrementalViews.state}")
        }
        val couchWire = borg.trikeshed.forge.server.CouchWire(
            router = borg.trikeshed.couch.CouchWireRouter(couchDb, WorktreeCouchGateway.WORKTREE_PREFIX) { ddoc, view ->
                incrementalViews.lookup(ddoc, view)
            },
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
        // H1: the daemon's own blackboard is finally SERVED. The Hypervisor and the
        // pointcut adapter already write receipts into it; the wire streams them out
        // on the same litebike listener. Repair contract: seq-ordered replay, `id:`
        // on every SSE event, `since` as a query param, snapshot at /blackboard/board.
        val blackboardWire = borg.trikeshed.forge.server.BlackboardWire(daemonBlackboard, wireScope)
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
        // ── Project DBs: dropped hierarchies as their own couch databases (shared CAS) ──
        val projectDbRegistry = borg.trikeshed.forge.server.ProjectDbRegistry(COUCH_DB_NAME)
        // ── Memory store + ISAM index layer (fs-memory Prongs 1+2) ──
        // MemoryStore composes the existing CAS+Couch into the paper's
        // memory store M. MemoryIndexLayer subscribes to mutations and
        // maintains taxonomy/temporal/provenance ISAM routes.
        // Declared BEFORE graalWire: the density route reads its live lineIndex.
        val memoryStore = borg.trikeshed.memory.MemoryStore(casStore, couchStore)
        val graalWire = borg.trikeshed.forge.server.GraalWire(
            jvmVitals,
            couchStore,
            reportReactorForWires,
            wireScope,
            couchDb,
            vmHost,
            attachmentGateway,
            projectDbs = projectDbRegistry,
            memoryStore = memoryStore,
        )

        // R2: persist the inverted index. At boot, restore the continent from the store
        // (no cold re-derive); after every ingest the live index snapshots to CAS+Couch,
        // trailing the writer by at most one put.
        borg.trikeshed.cas.LineCasIndexPersistence.restore(couchStore, casStore)?.let { restored ->
            memoryStore.restoreIndex(restored)
            System.err.println("[OROBOROS] LineCasIndex restored from store: ${restored.documentCount} docs, ${restored.contentKeyCount} content keys")
            // Durable fact, not a log line: the board carries what the boot actually did.
            daemonBlackboard.put(
                "daemon/linecas-index",
                mapOf("docs" to restored.documentCount.toString(), "contentKeys" to restored.contentKeyCount.toString()),
                "oroboros",
            )
        }
        memoryStore.onIndexIngest = { idx ->
            runCatching { borg.trikeshed.cas.LineCasIndexPersistence.write(couchStore, casStore, idx) }
                .onFailure { t -> System.err.println("[OROBOROS] LineCasIndex persist failed: ${t.message}") }
        }
        val memoryIndex = borg.trikeshed.memory.MemoryIndexLayer(memoryStore)
        val couchIndexBridge = borg.trikeshed.memory.CouchIndexBridge(attachmentGateway, memoryIndex)
        System.err.println("[OROBOROS] MemoryStore + MemoryIndexLayer: ${memoryIndex.route(borg.trikeshed.memory.IndexKind.Taxonomy).entryCount} taxonomy entries")

        // ── BeliefBagElement: the daemon-owned NarseseBag (AIKR belief bound) ──
        // Flagged (--belief-bag / TRIKESHED_BELIEF_BAG=1) while phases P3..P8 land.
        // Evidence spills to the SAME forge-home CAS the store uses; the WAL rides
        // .oroboros (a ForgeHome-reserved prefix). DecayTick = daily curation pulse.
        val beliefBagEnabled = "--belief-bag" in args || System.getenv("TRIKESHED_BELIEF_BAG") == "1"
        val beliefBag: borg.trikeshed.narsese.BeliefBagElement? = if (beliefBagEnabled) {
            val walDir = File(forgeHome, ".oroboros").apply { mkdirs() }
            val bag = borg.trikeshed.narsese.BeliefBagElement(
                capacity = 4096,
                cas = casStore,
                wal = borg.trikeshed.couch.isam.JvmDurableAppendLog(File(walDir, "belief.wal")),
                decayFn = { b -> borg.trikeshed.narsese.AttentionEconomy.decay(b) },
                priorityFloor = borg.trikeshed.narsese.CurationState.STALE.floor,
                parentJob = coroutineContext[kotlinx.coroutines.Job],
            )
            bag.open()
            launch(Dispatchers.Default) {
                while (isActive) {
                    delay(24 * 3600 * 1000L)
                    bag.intake.send(borg.trikeshed.narsese.BeliefIntake.DecayTick)
                }
            }
            System.err.println("[OROBOROS] BeliefBag open: ${bag.state} — ${bag.size} beliefs (capacity 4096, WAL ${walDir}/belief.wal)")
            bag
        } else null

        // The NARS curation loop, closed: review (induction) + render (bounded MEMORY
        // file, forge-homed — the ~/.hermes swap stays an explicit later step) + the
        // curation ledger (every belief event lands as a Rete-visible blackboard fact).
        val turnReview: borg.trikeshed.narsese.TurnReviewElement? = beliefBag?.let { bag ->
            val r = borg.trikeshed.narsese.TurnReviewElement(bag, parentJob = coroutineContext[kotlinx.coroutines.Job])
            r.open()
            r
        }
        val hermesMemoryFiles: borg.trikeshed.memory.HermesMemoryFiles? = beliefBag?.let { bag ->
            val files = borg.trikeshed.memory.HermesMemoryFiles(
                bag = bag,
                memoriesDir = File(forgeHome, "memories"),
                evaluatorCid = borg.trikeshed.job.ContentId.of("oroboros-session".encodeToByteArray()),
            )
            // session start: user edits are authoritative evidence, then freeze the render
            launch(Dispatchers.IO) {
                val deltas = files.ingestUserEdits()
                if (deltas > 0) System.err.println("[OROBOROS] MEMORY.md user edits re-ingested: $deltas deltas")
            }
            files
        }
        if (beliefBag != null) {
            launch {
                beliefBag.beliefEvents.collect { ev ->
                    val (kind, angular) = when (ev) {
                        is borg.trikeshed.narsese.BeliefEvent.Minted -> "minted" to ev.angular
                        is borg.trikeshed.narsese.BeliefEvent.Revised -> "revised" to ev.angular
                        is borg.trikeshed.narsese.BeliefEvent.Attended -> "attended" to ev.angular
                        is borg.trikeshed.narsese.BeliefEvent.Evicted -> "evicted" to ev.angular
                        is borg.trikeshed.narsese.BeliefEvent.Contradicted -> "contradicted" to ev.angular
                    }
                    daemonBlackboard.put(
                        "narsese/curation/$kind/${angular.toString(16)}",
                        mapOf("event" to kind, "angular" to angular.toString(), "actor" to "curator-pure"),
                        "oroboros",
                    )
                }
            }
        }

        // Shared, live, queryable KIF bank — nal-kif/construction-kif/legal-kif
        // all assert here (in addition to their write-only, provenance-only
        // blackboard landing), so SparqlKifMcpServer-style pattern queries
        // (kif.query semantics) over `sparql.query`/`kif.sparqlSelect` can
        // actually find them. Also backs state.freeze/thaw's persistence.
        // One bank rather than three: the predicate vocabularies (causal
        // relation/subject/object for NAL, cites/holding/party for legal)
        // don't collide, and a single bank is what lets the legal tribunal's
        // evidence-bank query (below) see everything ingested so far. The
        // curator banks its SUMO spine + assessed impulses HERE too — one
        // bank, not a wire twin and an impulse twin each hoarding their own.
        val kifBank = borg.trikeshed.kif.KifKnowledgeBase()
        // ── CausalityReteElement + CuratorImpulseElement: the LIVE rete over the
        // bag (eternal truths at discounted support, minimum-understanding floor)
        // and the curator-impulse teaching recipient (hindsight replay → SUMO/KIF
        // banked knowledge → bag signals). Both ride the belief-bag flag; rules
        // and impulses are fed by callers (forge routes / curation pulses).
        val causalityRete: borg.trikeshed.narsese.CausalityReteElement? = beliefBag?.let { bag ->
            val r = borg.trikeshed.narsese.CausalityReteElement(
                bag,
                rules = borg.trikeshed.lib.emptySeriesOf(),
                parentJob = coroutineContext[kotlinx.coroutines.Job],
            )
            r.open()
            r
        }
        // ONE curator: the same element serves the BeliefWire teach/query routes
        // AND the hermes feeder below — rete-registered, kifBank-banked.
        val curatorImpulse: borg.trikeshed.narsese.CuratorImpulseElement? = beliefBag?.let { bag ->
            val c = borg.trikeshed.narsese.CuratorImpulseElement(
                bag,
                rete = causalityRete,
                knowledgeBank = kifBank,
                parentJob = coroutineContext[kotlinx.coroutines.Job],
            )
            c.open()
            c
        }
        val beliefWire = if (beliefBag != null && turnReview != null) {
            // W5.3: wire the live curator element into the beliefs HTTP surface
            // (teach/query routes) — the SAME element the feeder teaches.
            borg.trikeshed.forge.server.BeliefWire(beliefBag, turnReview, hermesMemoryFiles, curatorImpulse)
        } else null
        if (causalityRete != null) {
            System.err.println("[OROBOROS] CausalityRete live: ${causalityRete.rules.size} eternal rules; CuratorImpulse bank: ${curatorImpulse?.knowledgeBank?.asserts()?.size ?: 0} axioms")
            launch {
                causalityRete.firings.collect { firing ->
                    daemonBlackboard.put(
                        "narsese/rete/firing/${firing.firingCid.hex}",
                        mapOf(
                            "event" to "dependent-rete-firing",
                            "firingCid" to firing.firingCid.value,
                            "ruleCid" to firing.rule.ruleCid.value,
                            "antecedent" to firing.rule.antecedent,
                            "consequent" to firing.rule.consequent,
                            "dependence" to firing.dependence.name,
                        ),
                        "oroboros",
                    )
                }
            }
            launch {
                while (isActive) {
                    causalityRete.fireLive()
                    delay(250L)
                }
            }
        }

        // ── Hermes design distillation + incremental curator feeding (I1/I2) ──
        // The trusted ledger/state.db reader remains CuratorImpulseFeeder. At boot its snapshot
        // is distilled into CAS/Line-CAS design docs; then one structured coroutine follows
        // MAX(messages.id) checkpoints. Checkpoint state is local/frozen Series data — no daemon
        // registry. All file/sqlite/store blocking work is dispatched to IO inside the helpers.
        if (curatorImpulse != null) {
            val profileDir = System.getenv("HERMES_PROFILE")?.let { File(it) }
                ?: File(hermesHomeDir.absolutePath)
            val archiveProfile = System.getenv("HERMES_ARCHIVE_PROFILE")?.let { File(it) }
                ?: File(System.getProperty("user.home"), ".hermes.prev").takeIf { it.isDirectory }
            launch(Dispatchers.Default) {
                runCatching {
                    val distilled = borg.trikeshed.narsese.HermesDesignDistiller.distillTo(profileDir, archiveProfile, memoryStore)
                    System.err.println("[OROBOROS] Hermes design distilled: ${distilled.size} CAS documents")
                }.onFailure {
                    System.err.println("[OROBOROS] Hermes design distillation failed (non-fatal): ${it.message}")
                }

                val feeder = borg.trikeshed.narsese.CuratorImpulseFeeder(profileDir)
                var checkpoint = borg.trikeshed.narsese.CuratorImpulseFeeder.FollowCheckpoint.empty()
                // I5: baselines are computed once per daemon run, after the first follow has
                // had a chance to land transcripts — session cids to the blackboard.
                var baselinesLanded = false
                while (isActive) {
                    runCatching {
                        val followed = feeder.followOnce(curatorImpulse, memoryStore, checkpoint)
                        checkpoint = followed.checkpoint
                        if (followed.landed.isNotEmpty() || followed.transcriptCids.size > 0) {
                            System.err.println(
                                "[OROBOROS] Curator followed ${followed.transcriptCids.size} changed transcripts: ${followed.landed.size} signals; bank ${curatorImpulse.knowledgeBank.asserts().size} axioms",
                            )
                        }
                    }.onFailure {
                        System.err.println("[OROBOROS] CuratorImpulse follow failed (non-fatal): ${it.message}")
                    }
                    if (!baselinesLanded) {
                        runCatching {
                            val impulses = feeder.loadImpulses()
                            if (impulses.size > 0) {
                                val scenarios = feeder.loadScenarios(impulses)
                                val baselines = borg.trikeshed.narsese.HermesBaselines.computeAndLand(
                                    couchStore, casStore, daemonBlackboard, impulses, scenarios,
                                )
                                if (baselines != null) {
                                    baselinesLanded = true
                                    System.err.println(
                                        "[OROBOROS] Hermes baselines landed: watermark=${baselines.watermark.size} training=${baselines.training?.sessionCid?.value?.take(18) ?: "-"} corpus=${baselines.corpus.cid.value.take(18)}@${baselines.corpus.seq}",
                                    )
                                }
                            }
                        }.onFailure {
                            System.err.println("[OROBOROS] Hermes baseline computation failed (non-fatal): ${it.message}")
                        }
                    }
                    delay(5_000L)
                }
            }
        }

        // ── PatchWire: the ComfyUI patch-panel backend — full KeyMux/ModelMux access
        // (provider-neutral, key-leased, values never cross the wire) + multiproject
        // scope mounting (drag a directory: git repo → projects/<name>/, else
        // assets/<name>/ — the blackboard's taxonomy prefixes are the subscope seams).
        val projectScopes = borg.trikeshed.forge.server.ProjectScopes(
            fileOps, attachmentGateway, couchIndexBridge, casStore, beliefBag,
            projectDbs = projectDbRegistry,
            ledgerFile = File(forgeHome, ".oroboros/projects.tsv"),
            filesRoot = File(forgeHome, "files"),
        )
        val projectDbWire = borg.trikeshed.forge.server.ProjectDbWire(projectDbRegistry, uploads = projectScopes)
        val projectMiner = borg.trikeshed.forge.server.ProjectMiner(
            projectDbRegistry, projectScopes, casStore, beliefBag, File(forgeHome, "files"),
        )
        val brainClient = borg.trikeshed.jules.BrainClient(
            errorSink = borg.trikeshed.jules.JvmBrainErrorSink(forgeHome),
            keyMux = keyMux,
        )
        val patchWire = borg.trikeshed.forge.server.PatchWire(
            brain = brainClient,
            scopes = projectScopes,
            attachments = attachmentGateway,
            muxContext = htxElement + muxReactor,
            mountScope = wireScope,
            miner = projectMiner,
        )
        // (boot mounts + ledger remount happen below, once the Rete tendon hook is armed)
        // ── Dynamic modules: Rete (hoisted — the tendon below feeds it) + production
        //    registry + CoW route registry + supervisor (proxy ctors: app CP first,
        //    then a fresh URLClassLoader over build/live/classes — hotswapFeed's tree,
        //    so a class compiled after boot attaches without a bounce).
        val reteProductions = borg.trikeshed.dag.ReteProductionRegistry()
        val rete = borg.trikeshed.dag.ReteNetwork(reteProductions)
        val moduleRoutes = borg.trikeshed.module.ModuleRouteRegistry()
        val moduleScope = CoroutineScope(SupervisorJob(coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default)
        // Spec §3.1 production wiring: ONE stored-program resolver — the offered
        // presets (the panels/ attachment namespace was rooted out 2026-08-27
        // with the browser editor) — shared by module program runs
        // (/api/lcnc/run {program}) and webhook node dispatch.
        val storedProgramLoader: suspend (String) -> borg.trikeshed.lcnc.LcncProgram? = { name ->
            borg.trikeshed.lcnc.LcncPresets.all()[name]
                ?.let { borg.trikeshed.lcnc.LcncProgramConfix.fromJson(name, it) }
        }
        val moduleContext = borg.trikeshed.module.ModuleContext(
            couchDb = couchDb,
            rete = rete,
            productions = reteProductions,
            beliefBag = beliefBag,
            turnReview = turnReview,
            blackboard = daemonBlackboard,
            casStore = casStore,
            attachments = attachmentGateway,
            routes = moduleRoutes,
            scope = moduleScope,
            clock = { System.currentTimeMillis() },
            stateDir = forgeHome,
            muxContext = htxElement + muxReactor,
            ccekBinding = ccekBinding,
            programLoader = storedProgramLoader,
        )
        // Step K: the context-assembly node family is host-composed like any module's
        // runners — webhook dispatch and program runs can mint real context receipts.
        moduleContext.lcncRunners.putAll(borg.trikeshed.memory.ace.AceContextNodes.registry(daemonBlackboard))
        // P4: lawyer-bot reading seat. ONLY this runner calls BrainClient/ModelMux;
        // ConstructionReadingLoop owns deterministic CAS claim-check/fold/bag/Rete landing.
        beliefBag?.let { readingBag ->
            moduleContext.lcncRunners["read.construct"] = borg.trikeshed.narsese.ConstructionBotNode.runner(
                brain = brainClient,
                muxContext = htxElement + muxReactor,
                cas = casStore,
                bag = readingBag,
                rete = rete,
                kifSink = { kif ->
                    val cid = borg.trikeshed.job.ContentId.of(kif.encodeToByteArray())
                    daemonBlackboard.put("construction-kif/${cid.hex}", kif, "reader")
                    runCatching { kifBank.assertKif(kif) }
                },
            )
        }
        // Sub-VM module legos: tika/corenlp/camel/graalce as supervised guest evals
        // over the daemon's own hypervisor (VmSupervisor.current — VmWire's same host).
        borg.trikeshed.lcnc.SubVmLegos.register(moduleContext)
        // Pure/presentation node runners: canvas-authored programs (preset-kanban)
        // complete HEADLESS via /api/lcnc/run — the curl-able smoke-test lane.
        moduleContext.lcncRunners.putAll(borg.trikeshed.lcnc.PureNodes.registry { System.currentTimeMillis() })
        // ── NAL belief-bag nodes: nal.mint, nal.decay, nal.recall ────
        // nal.mint wraps ConstructionBotNode (the only model-spend seam).
        // nal.decay is a thin timer trigger over AttentionEconomy.decay.
        // nal.recall exposes BeliefBagElement's read methods as LCNC nodes.
        beliefBag?.let { bag ->
            moduleContext.lcncRunners["nal.mint"] = borg.trikeshed.narsese.NalNodes.mintRunner(
                brain = brainClient,
                muxContext = htxElement + muxReactor,
                cas = casStore,
                bag = bag,
                rete = rete,
                kifSink = { kif ->
                    val cid = borg.trikeshed.job.ContentId.of(kif.encodeToByteArray())
                    daemonBlackboard.put("nal-kif/${cid.hex}", kif, "nal.mint")
                    runCatching { kifBank.assertKif(kif) }
                },
            )
            moduleContext.lcncRunners["nal.decay"] = borg.trikeshed.narsese.NalNodes.decayRunner(bag)
            moduleContext.lcncRunners["nal.recall"] = borg.trikeshed.narsese.NalNodes.recallRunner(bag)
            moduleContext.lcncRunners["skill.decay"] = borg.trikeshed.narsese.NalNodes.skillDecayRunner(bag)
        }
        // ── State freeze / thaw: persist bag+KB to CAS ─────────────
        beliefBag?.let { bag ->
            moduleContext.lcncRunners["state.freeze"] = borg.trikeshed.narsese.StateNodes.freezeRunner(
                bag = bag,
                kif = kifBank,
                graph = { borg.trikeshed.rdf.RdfGraph(emptyList()) },
                cas = casStore,
            )
            moduleContext.lcncRunners["state.thaw"] = borg.trikeshed.narsese.StateNodes.thawRunner(
                cas = casStore,
                kif = kifBank,
            )
        }
        // ── Belief-field nodes: the BeliefWire behaviors as runners plus the
        // attend/reinforce/encode legos. Gloss identity rides HermesMemoryFiles.
        beliefBag?.let { bag ->
            moduleContext.lcncRunners["beliefs.introspect"] = borg.trikeshed.narsese.BeliefsNodes.introspectRunner(bag)
            moduleContext.lcncRunners["beliefs.resonate"] = borg.trikeshed.narsese.BeliefsNodes.resonateRunner(bag) { angular ->
                hermesMemoryFiles?.glossOf(angular)
            }
            moduleContext.lcncRunners["nal.attend"] = borg.trikeshed.narsese.BeliefsNodes.attendRunner(bag)
            moduleContext.lcncRunners["nal.reinforce"] = borg.trikeshed.narsese.BeliefsNodes.reinforceRunner(bag)
        }
        turnReview?.let { review ->
            moduleContext.lcncRunners["beliefs.review"] = borg.trikeshed.narsese.BeliefsNodes.reviewRunner(review) { angular, gloss ->
                hermesMemoryFiles?.gloss(angular, gloss)
            }
        }
        moduleContext.lcncRunners["nal.encode"] = borg.trikeshed.narsese.BeliefsNodes.encodeRunner()
        // ── Rule admission: the seam that ends the live rete's dead spin —
        // the daemon boots causalityRete over ZERO rules; these admit law.
        causalityRete?.let { liveRete ->
            moduleContext.lcncRunners["nal.rule.admit"] = borg.trikeshed.narsese.RuleNodes.ruleAdmitRunner(liveRete)
            moduleContext.lcncRunners["nal.rules.fromKg"] = borg.trikeshed.narsese.RuleNodes.rulesFromKgRunner(liveRete)
        }
        // Bag-off honesty: these contracts ship unconditionally, so with the
        // bag flagged off the canvas still gets a value naming the flag — not
        // a 404. KanbanModule's own bag-gated pair is stubbed here too (its
        // attach only registers them when ctx.beliefBag is live).
        if (beliefBag == null) {
            val bagGated = listOf(
                "read.construct", "nal.mint", "nal.decay", "nal.recall", "skill.decay",
                "nal.attend", "nal.reinforce", "nal.rule.admit", "nal.rules.fromKg",
                "beliefs.introspect", "beliefs.resonate", "beliefs.review",
                "state.freeze", "state.thaw", "kanban.attention", "kanban.drift",
            )
            val bagOff = borg.trikeshed.lcnc.LcncNodeRunner { _, _ ->
                mapOf("error" to "belief bag disabled (--belief-bag)")
            }
            for (type in bagGated) moduleContext.lcncRunners[type] = bagOff
        }
        // ── Legal domain nodes: legal.ingest, legal.evidence ────────────
        moduleContext.lcncRunners["legal.ingest"] = borg.trikeshed.narsese.LegalNodes.ingestRunner(
            brain = brainClient,
            muxContext = htxElement + muxReactor,
            cas = casStore,
            kifSink = { kif ->
                val cid = borg.trikeshed.job.ContentId.of(kif.encodeToByteArray())
                daemonBlackboard.put("legal-kif/${cid.hex}", kif, "legal.ingest")
                runCatching { kifBank.assertKif(kif) }
            },
        )
        // Evidence-bank injection (§3/§5 gap): queries the shared kifBank for
        // every fact legal.ingest asserted against this document's atom and
        // folds it into the brief argue actually reads — closing the loop
        // the report flagged as "nothing reads these entries back out".
        moduleContext.lcncRunners["legal.evidence"] = borg.trikeshed.narsese.LegalNodes.evidenceRunner(kifBank)
        // ── Tribunal: the LCNC preset that argues, rebuts, and rules through
        //    REAL hermes-env model dialogs. The node family (mux.chat /
        //    kg.ingest / display) is registered in-process, and each seat's
        //    model call routes through BrainClient — KeyMux harness lane
        //    ($HERMES_HOME/.env, auth.json credential pool) → ModelMux →
        //    HtxReactor, so the tribunal's token spend lands on the daemon's
        //    quota/lease receipts exactly like every other model traffic.
        //    The instance (schema → mutable, versionable state) opens from
        //    the preset's pre-canned lanes; kg.ingest advances the judge's
        //    job (active → closed) when the verdict lands on the record.
        val tribunalHolder = borg.trikeshed.lcnc.TribunalInstanceHolder()
        moduleContext.lcncRunners.putAll(
            borg.trikeshed.lcnc.TribunalNodes.registry(
                dialog = borg.trikeshed.lcnc.hermesEnvDialog(
                    chat = { system, prompt ->
                        {
                            val content = brainClient.chat(
                                listOf("system" to system, "user" to prompt),
                                maxTokens = 600,
                                contextId = "tribunal-seat",
                            )
                            content to (brainClient.lastModel() ?: "")
                        }
                    },
                    muxContext = htxElement + muxReactor,
                ),
                ingest = { verdict ->
                    val t = tribunalHolder.instance
                    val life = t?.lifecycle("deliberate")
                    if (t == null || (life != "active" && life != "submitted")) verdict
                    else {
                        t.advance("deliberate", "complete", "tribunal-verdict-${verdict.hashCode()}", t.revision("deliberate") ?: 1L)
                        t.snapshotCid("deliberate") ?: verdict
                    }
                },
            ),
        )
        // ── BrainClient decomposition as LCNC: credential.enter → prompt.chat →
        //    result.confirm. CouchKeyStore backs the credential persistence;
        //    the chatFn uses the daemon's HTX client (200/non-200 patchpoints).
        val couchKeyStore = keymux.CouchKeyStore(couchDb)
        // Seed CouchKeyStore from the daemon's KeyMux so the prefill
        // dropdown resolves real keys from env/hermes/harness stores.
        runCatching {
            for (i in 0 until keymux.HarnessRegistry.providers.size) {
                val p = keymux.HarnessRegistry.providers[i]
                val k = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    keyMux.get("llm.${p.id}.key")
                }
                val u = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    keyMux.get("llm.${p.id}.base_url")
                }
                if (!k.isNullOrBlank()) {
                    couchKeyStore.storeCredential(
                        p.id, k,
                        u ?: p.defaultBaseUrl ?: "",
                        "openai",
                    )
                }
            }
        }
        // ModelMux for the mux.models LCNC node — enumerates routable models.
        val lcncModelMux = modelmux.ModelMux(keyMux) {
            val nvidia = "https://integrate.api.nvidia.com/v1"
            model(id = "deepseek-ai/deepseek-v4-pro", caps = setOf("chat"), baseUrl = nvidia)
            model(id = "nvidia/nemotron-3-super-120b-a12b", caps = setOf("chat"), baseUrl = nvidia)
            model(id = "mistralai/mistral-large-2-instruct", caps = setOf("chat"), baseUrl = nvidia)
            model(id = "z-ai/glm-5.2", caps = setOf("chat"), baseUrl = nvidia)
            model(id = "moonshotai/kimi-k2.6", caps = setOf("chat"), baseUrl = nvidia)
            model(id = "openai/gpt-oss-120b", caps = setOf("chat"), baseUrl = nvidia)
            model(id = "minimaxai/minimax-m3", caps = setOf("chat"), baseUrl = nvidia)
            model(id = "poolside/laguna-xs-2.1", caps = setOf("chat"), baseUrl = nvidia)
            model(id = "gpt-4o-mini", caps = setOf("chat"), baseUrl = "https://api.openai.com/v1")
            model(id = "llama-3.3-70b-versatile", caps = setOf("chat"), baseUrl = "https://api.groq.com/openai/v1")
            model(id = "deepseek-chat", caps = setOf("chat"), baseUrl = "https://api.deepseek.com/v1")
        }
        moduleContext.lcncRunners.putAll(
            borg.trikeshed.lcnc.BrainMuxNodes.registry(
                keyMux = keyMux,
                modelMux = lcncModelMux,
                credStore = couchKeyStore,
                chatFn = { url, apiKey, model, messages, maxTokens, temperature, headers ->
                    runCatching {
                        val bodyMap = linkedMapOf<String, Any?>(
                            "model" to model,
                            "max_tokens" to maxTokens,
                            "temperature" to temperature,
                            "messages" to (0 until messages.size).map { i ->
                                linkedMapOf("role" to messages[i].first, "content" to messages[i].second)
                            },
                        )
                        val json = JsonSupport.stringify(bodyMap)
                        fun hdr(k: String, v: String): borg.trikeshed.htx.HtxHeader =
                            object : borg.trikeshed.htx.HtxHeader {
                                override val a = k; override val b = v
                            }
                        val allHdrs = arrayOf(
                            hdr("Authorization", "Bearer $apiKey"),
                            hdr("Content-Type", "application/json"),
                        ) + headers.filter { it.first.isNotBlank() }.map { (k, v) -> hdr(k, v) }.toTypedArray()
                        val htxReq = borg.trikeshed.htx.parseHtxRequest(
                            url = "$url/chat/completions",
                            method = borg.trikeshed.htx.HtxMethod.POST,
                            body = borg.trikeshed.lib.ByteSeries(json.encodeToByteArray()),
                        ).copy(headers = borg.trikeshed.htx.htxHeaders(*allHdrs))
                        val resp = htxElement.requestResult(htxReq).getOrThrow()
                        val respBody = resp.body.toArray().decodeToString()
                        val parsed = JsonSupport.parse(respBody) as? Map<*, *>
                            ?: error("non-JSON response: ${respBody.take(200)}")
                        val choices = parsed["choices"] as? List<*>
                            ?: error("no choices in response")
                        val first = choices.firstOrNull() as? Map<*, *>
                            ?: error("empty choices")
                        val message = first["message"] as? Map<*, *>
                            ?: error("no message in choice")
                        message["content"] as? String ?: error("no content in message")
                    }
                },
            ),
        )
        launch(Dispatchers.Default) {
            runCatching {
                val tribunal = borg.trikeshed.lcnc.TribunalInstance.open(
                    scope = moduleScope,
                    plan = borg.trikeshed.lcnc.TribunalInstance.schemaPlan(),
                    presetDocument = borg.trikeshed.lcnc.LcncPresets.all().getValue("preset-tribunal"),
                )
                tribunalHolder.instance = tribunal
                tribunal.awaitRootSeeds()
                System.err.println("[OROBOROS] tribunal instance live: ${tribunal.laneIds.size} lanes seeded at root (schema job-nexus)")
            }.onFailure {
                System.err.println("[OROBOROS] tribunal instance failed to open (non-fatal): ${it.message}")
            }
        }
        val moduleSupervisor = borg.trikeshed.module.ModuleSupervisor(
            ctx = moduleContext,
            liveClassesDir = File(repoDir, "build/live/classes"),
            receipt = { event, id, detail ->
                daemonBlackboard.put("$event/$id", detail.mapValues { it.value?.toString() ?: "" }, "oroboros")
            },
        )
        val moduleWire = borg.trikeshed.forge.server.ModuleWire(moduleSupervisor, moduleRoutes)
        val webhookRuntime = borg.trikeshed.forge.server.couchWebhookRuntime(
            couchStore, daemonBlackboard, forgeHome,
            runners = moduleContext.lcncRunners,
            loadProgram = { name -> borg.trikeshed.lcnc.LcncPresets.all()[name]?.encodeToByteArray() },
        )
        val webhookWire = webhookRuntime.wire
        val webhookScope = CoroutineScope(SupervisorJob(coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default + htxElement)
        // Outbound deliveries own their lane: separate WAL + prefix, so inbound and
        // outbound NUID acceptance spaces never collide.
        val outboundHookLedger = withContext(Dispatchers.IO) {
            borg.trikeshed.hook.CausalHookDeliveryLedger.open(
                File(forgeHome, ".hook-deliveries-out.wal"), "hook-delivery-out/",
            )
        }
        borg.trikeshed.hook.installOutboundWebhookBridge(couchStore, daemonBlackboard, webhookScope, outboundHookLedger)

        // Boot facts land on the board — the durable answer to "what is this daemon".
        daemonBlackboard.put(
            "daemon/boot/kanban",
            mapOf("port" to "8888", "atMs" to System.currentTimeMillis().toString()),
            "oroboros",
        )
        val kanbanServer = JvmKanbanServer(
            extraRoutes = listOfNotNull(graalWire::route, vmWire::route, hermesWire::route, beliefWire?.let { it::route }, patchWire::route, moduleWire::route, webhookWire::route, blackboardWire::route),
            rawRoutes = listOf(graalWire::ingestRoute, couchWire::route, projectDbWire::route),
            streamingPaths = borg.trikeshed.forge.server.CouchWire.streamingPaths(COUCH_DB_NAME) +
                borg.trikeshed.forge.server.GraalWire.STREAMING +
                borg.trikeshed.forge.server.VmWire.STREAMING +
                borg.trikeshed.forge.server.HermesConsoleWire.STREAMING +
                borg.trikeshed.forge.server.BlackboardWire.STREAMING,
            maxRequestBatch = 4096,
            stateDir = forgeHome,
            moduleRoutes = moduleRoutes,
        )
        launch {
            // KanbanModule is the point of the module system: attached by DEFAULT (the WAL-backed
            // board replaces the fossil plan-parser). TRIKESHED_NO_KANBAN_MODULE=1 opts out.
            if (System.getenv("TRIKESHED_NO_KANBAN_MODULE") != "1") {
                runCatching { moduleSupervisor.attach(borg.trikeshed.kanban.module.KanbanModule()) }
                    .onSuccess { System.err.println("[OROBOROS] module attached: ${it.id} (default) — ${it.describe()}") }
                    .onFailure { System.err.println("[OROBOROS] KanbanModule attach FAILED: ${it.message}") }
            }
            for (fqcn in config.modules) {
                runCatching { moduleSupervisor.attach(fqcn) }
                    .onSuccess { System.err.println("[OROBOROS] module attached: ${it.id} ($fqcn)") }
                    .onFailure { System.err.println("[OROBOROS] module attach FAILED for $fqcn: ${it.message}") }
            }
        }

        // ── Project dbs: every mounted db grows its own Changes→Rete tendon
        //    (partition = db name — blackboard subscope seams by project), then
        //    boot mounts: the --project flags and the forge-home mount ledger
        //    (project dbs SURVIVE restarts — the ledger replays them).
        projectDbRegistry.onMount = { pdb ->
            val tendon = borg.trikeshed.couch.CouchChangesFactElement(
                db = pdb.db,
                rete = rete,
                report = reportReactorForWires,
                admit = { true },
                parentJob = moduleScope.coroutineContext[kotlinx.coroutines.Job],
            )
            moduleScope.launch {
                tendon.open()
                System.err.println("[OROBOROS] project-db tendon: ${pdb.name} — ${tendon.factsApplied} facts")
            }
        }
        launch(Dispatchers.IO) {
            for (extra in config.projects) {
                runCatching { projectScopes.mount(extra) }
                    .onSuccess { System.err.println("[OROBOROS] project db mounted: ${it.kind} ${it.name} (${it.paths} paths, ${it.docs} docs, ${it.minted} minted)") }
                    .onFailure { System.err.println("[OROBOROS] scope mount FAILED for $extra: ${it.message}") }
            }
            val remounted = runCatching { projectScopes.remountLedger() }.getOrElse { 0 }
            if (remounted > 0) System.err.println("[OROBOROS] project dbs remounted from ledger: $remounted")
        }
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
                    val n = reconcileBuildPlane(buildPlanes, gitState.headSha())
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
        // reactor thread. Three independent runCatching blocks ensure a failure
        // in one plane (e.g. memory bridge) does not prevent another (build
        // plane) from completing — specifically, classpath.tsv must always be
        // written so --boot-forge hydration works.
        withContext(Dispatchers.IO) {
            // ── Foundation: git + worktree reconcile ──
            val reconcileResult = runCatching {
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
                projectScopes.registerPrimary(repoDir, worktreeSnap.paths.size)
                System.err.println(
                    "[OROBOROS] Worktree→Couch initial reconcile: ${worktreeSnap.paths.size} paths"
                )
                headSha to worktreeSnap
            }.onFailure {
                System.err.println("[OROBOROS] git/worktree reconcile failed: ${it.message}")
                it.printStackTrace()
            }.getOrNull() ?: return@withContext

            val (headSha, worktreeSnap) = reconcileResult

            // ── Memory bridge + belief seeding (degrades independently) ──
            runCatching {
                val bridged = memoryBridge.bridge(worktreeSnap, agentId = "oroboros")
                System.err.println(
                    "[OROBOROS] Memory bridge: $bridged memory files bridged (spines + IPFS)"
                )
                // ── Belief minting feed: epistemic signals from the memory plane land in the bag.
                // FNV identity stays on the signal's CIDs; the BAG key gets the feature-coded
                // AngularCodec coordinate so recallNear is real locality, not hash noise.
                // Boot seed is AIKR-capped; the bag's own capacity bounds the rest.
                if (beliefBag != null) {
                    var mintedSignals = 0
                    val mintCap = 2048
                    for (i in 0 until worktreeSnap.paths.size) {
                        if (mintedSignals >= mintCap) break
                        val path = worktreeSnap.paths[i]
                        if (!path.endsWith(".md") && !path.endsWith(".markdown")) continue
                        val att = attachmentGateway.getAttachment(path) ?: continue
                        val surface = runCatching {
                            borg.trikeshed.cas.ContentEpistemicIngest.ingest(casStore, att.second.decodeToString())
                        }.getOrNull() ?: continue
                        for (si in 0 until surface.signals.size) {
                            if (mintedSignals >= mintCap) break
                            val s = surface.signals[si]
                            val coord = borg.trikeshed.narsese.AngularCodec.encode(
                                relation = s.relation,
                                taxonomyKey = path,
                                subjectTerm = path.substringAfterLast('/'),
                                objectTerm = s.objectCid?.take(12),
                            )
                            beliefBag.intake.send(
                                borg.trikeshed.narsese.BeliefIntake.Mint(
                                    s.copy(angular = coord),
                                    borg.trikeshed.cursor.BudgetCoord(0.5f, 0.3f, 0.5f),
                                ),
                            )
                            mintedSignals++
                        }
                    }
                    System.err.println("[OROBOROS] BeliefBag seeded: $mintedSignals epistemic signals → ${beliefBag.size} beliefs")
                }
            }.onFailure {
                System.err.println("[OROBOROS] memory bridge/belief failed: ${it.message}")
                it.printStackTrace()
            }

            // ── Build plane reconcile + hermes home (must always run — writes classpath.tsv) ──
            runCatching {
                val buildPaths = reconcileBuildPlane(buildPlanes, headSha)
                System.err.println("[OROBOROS] Build→Couch initial reconcile: $buildPaths classpath attachments → manifest ${buildPlanes.manifestFile}")
                if (hermesHomeDir.isDirectory) {
                    val hermesSnap = hermesHomeGateway.reconcile(hermesHomeDir.absolutePath, "oroboros", headSha, System.currentTimeMillis())
                    System.err.println("[OROBOROS] Hermes home→Couch initial reconcile: ${hermesSnap.paths.size} paths (teleportable clone of ~/.hermes)")
                } else {
                    System.err.println("[OROBOROS] Hermes home skipped: $hermesHomeDir not found")
                }
            }.onFailure {
                System.err.println("[OROBOROS] build plane reconcile failed: ${it.message}")
                it.printStackTrace()
            }
        }

        // ── Couch report reactor: CCEK element for map/reduce events ──
        val reportReactor = reportReactorForWires
        System.err.println("[OROBOROS] Couch report reactor: ${reportReactor.state} — feeding the Graal console flourish feed")

        // ── Tendon: _changes → report bus + Rete facts. Every committed revision (local write,
        //    reconcile, or a peer's replication) is a fact in the production system; the git object
        //    plane stays out (opaque blobs carry no fields worth matching).
        // (rete hoisted above the kanban server so ModuleContext can carry it)
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

        System.err.println(
            "[OROBOROS] daemon up. forgeHome=$forgeHome repo=$repoDir " +
                "intervalMs=$intervalMs maxSlots=$maxSlots mode=${if (watch) "watch" else "once"}"
        )

        daemonStartTime = System.currentTimeMillis()

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
                    val uptimeMs = System.currentTimeMillis() - daemonStartTime

                    // Backward-compatible ALIVE line (cycle fields retired with the flywheel)
                    val aliveLine = "ALIVE $uptimeMs -1 -1 -1 -1 -1\n"

                    val metricsJson = try {
                        "METRICS " + JsonSupport.stringify(mapOf("uptimeMs" to uptimeMs)) + "\n"
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
        if (!preflight(repoDir)) {
            System.err.println("[OROBOROS] preflight failed; aborting")
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
        try {
            if (watch) {
                while (isRunning) {
                    delay(intervalMs)
                }
            } else {
                // --once: settle the reactive elements, then exit.
                delay(intervalMs)
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
                runCatching { incrementalViews.closeAll() }
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
            }
        }
    }

    internal suspend fun preflight(repoDir: File): Boolean {
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
        // Local ahead of remote is normal between pushes. Only block when truly
        // diverged: local has commits origin doesn't AND vice versa.
        val mergeBase = command("git", "merge-base", local, remote)
        if (mergeBase == local || mergeBase == remote) {
            // linear: local ahead OR local behind, but not both
            return true
        }
        // local is neither reachable from remote nor vice versa → diverged
        println("[OROBOROS] UPSTREAM-DIVERGED local=$local remote=$remote mergeBase=$mergeBase")
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
