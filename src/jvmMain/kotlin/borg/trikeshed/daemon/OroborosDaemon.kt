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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
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
                // The daemon gets its OWN supervised scope rather than launching straight into this
                // runBlocking. mainImpl is an extension on its receiver, so everything it launches
                // without an explicit parent becomes a child of that receiver — and runBlocking
                // waits on every child. `--once` therefore did all its work, ran its cleanup,
                // returned, and hung with nothing left to do: a test worker parked on
                // BlockingCoroutine.joinBlocking, 17s of CPU across 32 minutes of wall clock.
                //
                // The fix is structural, not a sweep. Cancelling leftover children by hand works
                // only while the cancel list stays exhaustive, and the next launch that forgets to
                // register reintroduces the hang silently. Scoping to a SupervisorJob makes
                // termination a property of the hierarchy instead: whatever mainImpl launched is a
                // child of daemonScope by construction, and one cancel of the parent ends all of
                // it, in a `finally`, on every path including throw. Supervisor rather than plain
                // Job so a child failing during shutdown cannot cancel its siblings mid-close —
                // the same idiom AsyncContextElement already uses for its own elements.
                val daemonScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
                try {
                    with(daemonScope) { mainImpl(args) }
                } finally {
                    daemonScope.cancel()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // JVM shutdown triggered by signal handler
            exitProcess(0)
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.mainImpl(args: Array<String>) {
        // KeyMux, env-FIRST: harness lane (conventional env names + hermes .env
        // + codex/opencode credential files), then the legacy derived-name env
        // lane (LLM_<X>_KEY), then the hermes CREDENTIAL POOL as the borrowing
        // lane — providers the operator configured in hermes but not in this
        // process env (credential_pool, priority-ordered with cooldowns,
        // following hermes' own env:<VAR> indirection). 5-minute caches.
        // fileOps is explicit: coroutine contexts don't reliably carry FileOperations,
        // and without it the dotenv/auth.json/pool lanes silently degrade to env-only.
        val fileOps = JvmFileOperations()
        // The ACTIVE hermes profile's pool is the operator's live credential
        // store; HERMES_HOME names it (falls back to the default ~/.hermes
        // when the env var is unset). resolvePath expands ~ to $HOME, so the
        // explicit $HERMES_HOME value is what actually gets read.
        val hermesHome = keymux.defaultHermesHome()
        // The recipe itself lives in keymux.operatorKeyMux — ONE lane order,
        // shared with `bin/mux` (borg.trikeshed.mux.MuxCli). When the daemon
        // built this inline, the diagnostic that told the operator whether a key
        // was visible resolved through a different chain than the daemon did,
        // so it could only ever confirm its own wiring.
        val keyMux = keymux.operatorKeyMux(fileOps = fileOps, hermesHome = hermesHome)
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
        // ── health FIRST ───────────────────────────────────────────────────
        // This used to bind 1,600 lines further down, after CAS, wiki, git
        // reconcile and the rest — so during a boot that takes minutes there was
        // no way to ask the daemon anything at all, and OroborosDaemonHealthTest
        // timed out waiting for a socket that only appears once the work is
        // already done. Health is the one surface that must exist BEFORE the slow
        // parts, not after them: it answers 'am I alive' while the answer is still
        // in doubt. It needs only forgeHome and daemonStartTime, both known here.
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
        // Store selection is EXPLICIT (mission-002 decision D6): `TRIKESHED_CAS=btrfs` selects
        // the reflink store, anything else (default `file`) keeps FileCasStore. There is NO
        // autodetect and NO silent fallback — if btrfs is asked for and the CAS root does not
        // resolve onto a btrfs filesystem, the store's own guard throws and the daemon REFUSES
        // TO BOOT here, loudly. A quiet degrade to FileCasStore is exactly the failure that
        // would let "the daemon is on btrfs" pass while nothing had changed.
        val casRootPath = fileOps.resolvePath(forgeHome.absolutePath, "cas")
        val casSelection = (System.getenv("TRIKESHED_CAS") ?: "file").trim().lowercase()
        var btrfsCasStore: borg.trikeshed.btrfs.BtrfsReflinkStore? = null
        val casStore: borg.trikeshed.job.CasStore = when (casSelection) {
            "btrfs" -> {
                val store = try {
                    borg.trikeshed.btrfs.BtrfsReflinkStore(
                        rootDir = casRootPath,
                        fileOps = fileOps,
                        processOps = borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations(),
                        refCounter = borg.trikeshed.reflink.InMemoryReferenceCounter(),
                        fsProbe = borg.trikeshed.btrfs.JvmFilesystemTypeProbe,
                    )
                } catch (t: Throwable) {
                    System.err.println("[OROBOROS] CAS STORE REFUSED: TRIKESHED_CAS=btrfs but $casRootPath is not on btrfs — ${t.message}")
                    System.err.println("[OROBOROS] BOOT ABORTED: no silent fallback to FileCasStore (mission-002 decision D6).")
                    exitProcess(1)
                }
                btrfsCasStore = store
                store
            }
            "file" -> FileCasStore(fileOps, casRootPath)
            else -> {
                System.err.println("[OROBOROS] BOOT ABORTED: TRIKESHED_CAS='$casSelection' is not a known store (expected 'btrfs' or 'file').")
                exitProcess(1)
            }
        }
        System.err.println(
            "[OROBOROS] CAS STORE SELECTED: ${casStore::class.java.name} casRoot=$casRootPath " +
                "TRIKESHED_CAS=$casSelection fstype=${borg.trikeshed.btrfs.JvmFilesystemTypeProbe.typeOf(casRootPath) ?: "<undeterminable>"} " +
                "source=${borg.trikeshed.btrfs.JvmFilesystemTypeProbe.sourceOf(casRootPath) ?: "<undeterminable>"}"
        )
        // The D13 MATERIALIZE surface exists only when the btrfs store is live: reflinkReorganize
        // is a BtrfsReflinkStore member, absent from the CasStore base class.
        val casReflinkWire = btrfsCasStore?.let {
            borg.trikeshed.forge.server.CasReflinkWire(it, casRootPath).also { _ ->
                System.err.println(
                    "[OROBOROS] CAS reflink routes armed: " +
                        borg.trikeshed.forge.server.CasReflinkWire.ROUTES.joinToString(" ") { r -> "${r.first} ${r.second}" }
                )
            }
        }
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
        // Declare the heading the worktree gateway has always been minting documents under. Until
        // now `projects/<repo>/…` was an id prefix nobody had declared, so the store could not say
        // what projects it held; the manifest is an ordinary document, so it replicates with them.
        borg.trikeshed.couch.Projects(couchDb).put(
            repoDir.name.lowercase(),
            mapOf("root" to repoDir.absolutePath, "prefix" to WorktreeCouchGateway.WORKTREE_PREFIX),
        )
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
        // One replicator, held by both mountings: `POST _replicate` drives it as a route, and the
        // RequestFactory's `replicate` operation drives the same object inside a batch.
        val couchReplicator = borg.trikeshed.couch.replicate.CouchReplicator(couchDb, peerHttp)
        val requestFactoryRpcTargets =
            java.util.concurrent.ConcurrentHashMap<String, borg.trikeshed.relaxfactory.RequestFactoryRpcTarget>()
        // The ReportServer, declared here rather than beside the other wires because the router
        // needs it: with it attached, every `_view` and every envelope `query` puts its map and
        // reduce facts on the CCEK report bus. Without it those events had no producer at all.
        val reportReactorForWires = CouchReportReactorElement(parentJob = coroutineContext[kotlinx.coroutines.Job])
        launch { reportReactorForWires.open() }
        val couchWire = borg.trikeshed.forge.server.CouchWire(
            router = borg.trikeshed.couch.CouchWireRouter(
                couchDb,
                WorktreeCouchGateway.WORKTREE_PREFIX,
                replicator = couchReplicator,
                report = reportReactorForWires,
                rpcTargets = requestFactoryRpcTargets,
            ) { ddoc, view ->
                incrementalViews.lookup(ddoc, view)
            },
            replicator = couchReplicator,
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
        // Guest worlds are file-based under forgeHome: a `world = true` guest gets its own btrfs
        // subvolume on disk, so what it wrote and what it snapshotted are still there next boot.
        // They shared one in-memory filesystem before, which meant every world died with the daemon.
        val vmWorldStore = borg.trikeshed.btrfs.BtrfsWorldStore.ofFiles(
            fileOps,
            borg.trikeshed.btrfs.BtrfsWorldStore.homeUnder(forgeHome.absolutePath),
        )
        // ONE pointcut adapter over the daemon blackboard. Built here, before the Hypervisor, so
        // the Hypervisor's landings, the couch projection (installed below, in the pointcut
        // subsystem) and the graal fact tendon all read the same `flow` — the Hypervisor used to
        // build a private adapter of its own, whose landings no projection ever saw.
        val pointcutAdapter = borg.trikeshed.pointcut.PointcutBlackboardAdapter(daemonBlackboard)
        val vmHost = borg.trikeshed.vm.HypervisorVmHost(
            borg.trikeshed.graal.subvm.Hypervisor(blackboard = daemonBlackboard, adapter = pointcutAdapter, worldStore = vmWorldStore),
        )
        borg.trikeshed.vm.VmSupervisor.install(vmHost)
        val wireScope = CoroutineScope(SupervisorJob(coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default)
        // H1: the daemon's own blackboard is finally SERVED. The Hypervisor and the
        // pointcut adapter already write receipts into it; the wire streams them out
        // on the same litebike listener. Repair contract: seq-ordered replay, `id:`
        // on every SSE event, `since` as a query param, snapshot at /blackboard/board.
        val blackboardWire = borg.trikeshed.forge.server.BlackboardWire(daemonBlackboard, wireScope)
        // A guest's file-based world publishes onto the same database `_replicate` already moves,
        // so a VM world teleports on the couch transport rather than needing a lane of its own.
        val vmWire = borg.trikeshed.forge.server.VmWire(
            vmHost, wireScope,
            worlds = borg.trikeshed.btrfs.VmWorldTeleport(couchDb, vmWorldStore),
        )
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
                // The pinned SUMO corpus (Merge + Mid-level) is the ground theory; the
                // 13-category spine is only the offline-cold-cache fallback.
                groundTheory = borg.trikeshed.ontology.SumoCorpus.text().ifBlank { borg.trikeshed.ontology.SumoOntology.emitUpperKif() },
                // Same tee shape as councilKifSink below: taught axioms land in the `kif-ledger/`
                // couch plane the boot thaw re-asserts from. Without this the curator's whole
                // knowledge — everything /api/beliefs/teach banks — died at every restart while
                // council's and legal.ingest's survived, because only they wrote the ledger.
                ledger = { kif ->
                    val cid = borg.trikeshed.job.ContentId.of(kif.encodeToByteArray())
                    runCatching {
                        couchDb.put(
                            "kif-ledger/${cid.hex}",
                            mapOf("kif" to kif, "source" to "curator.teach", "atMs" to System.currentTimeMillis()),
                            null,
                        )
                    }.onFailure { System.err.println("[OROBOROS] curator kif-ledger write failed (non-fatal): ${it.message}") }
                    // The couch write above does not survive a restart — casBacked rebuilds its
                    // head projection empty at every boot, so the bodies persist in CAS but
                    // nothing can find them again. The file is the durable plane.
                    runCatching { borg.trikeshed.narsese.NarsDurableLedger.appendAxiom(forgeHome, kif) }
                        .onFailure { System.err.println("[OROBOROS] curator axiom ledger write failed (non-fatal): ${it.message}") }
                },
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
            System.err.println("[OROBOROS] CausalityRete live: ${causalityRete.rules.size} eternal rules; CuratorImpulse bank: ${curatorImpulse?.knowledgeBank?.size() ?: 0} axioms (ground theory ${curatorImpulse?.theorySize ?: 0} forms, impulses are ${curatorImpulse?.agentClass})")
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
        // The wikiLane holder is the seam between this loop and the WIKI_CONSOLIDATE
        // registration below (which runs after brainClient/moduleContext exist): the
        // registration arms slot 0, the loop polls it — null just means "not wired yet".
        val wikiLane = arrayOf<borg.trikeshed.lcnc.LcncNodeRunner?>(null)
        if (curatorImpulse != null) {
            val profileDir = System.getenv("HERMES_PROFILE")?.let { File(it) }
                ?: File(hermesHomeDir.absolutePath)
            val archiveProfile = System.getenv("HERMES_ARCHIVE_PROFILE")?.let { File(it) }
                ?: File(System.getProperty("user.home"), ".hermes.prev").takeIf { it.isDirectory }
            // Wiki Maintainer lane cadence: one pass per N transcript-cid
            // batches (the arXiv outer loop k); WIKI_CONSOLIDATE_EVERY=0
            // disables the lane entirely.
            val wikiEvery = System.getenv("WIKI_CONSOLIDATE_EVERY")?.toIntOrNull() ?: 1
            var wikiBatchesSeen = 0
            var wikiIterationsDone = 0
            // Transcript cids that landed before the runner was armed; drained
            // into the first Maintainer pass once it is.
            var wikiPending = emptyList<String>()
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
                    var followed: borg.trikeshed.narsese.CuratorImpulseFeeder.FollowResult? = null
                    runCatching {
                        val f = feeder.followOnce(curatorImpulse, memoryStore, checkpoint)
                        checkpoint = f.checkpoint
                        followed = f
                        if (f.landed.isNotEmpty() || f.transcriptCids.size > 0) {
                            System.err.println(
                                "[OROBOROS] Curator followed ${f.transcriptCids.size} changed transcripts: ${f.landed.size} signals; bank ${curatorImpulse.knowledgeBank.asserts().size} axioms",
                            )
                        }
                    }.onFailure {
                        System.err.println("[OROBOROS] CuratorImpulse follow failed (non-fatal): ${it.message}")
                    }
                    // ── the arXiv outer loop (WikiSkill 2608.27454): every time
                    //    the feeder lands NEW transcript cids, the Wiki
                    //    Maintainer runs one consolidation pass over them —
                    //    traces → PATCH edits to wiki/patterns/. Guarded by
                    //    cadence + presence so an idle corpus spends nothing.
                    //    Batches that land BEFORE the runner is armed are
                    //    stashed and processed once it is (a boot's first
                    //    follow races the module registration that wires
                    //    WIKI_CONSOLIDATE); the drain check runs on every
                    //    tick so an armed-then-idle corpus still drains.
                    val freshCids = followed?.transcriptCids
                    if (wikiEvery > 0 && freshCids != null && freshCids.size > 0) {
                        wikiPending = (wikiPending + (0 until freshCids.size).map { i -> freshCids[i].b.value }).take(32)
                    }
                    val wikiRunner = wikiLane[0]
                    if (wikiEvery > 0 && wikiRunner != null && wikiPending.isNotEmpty()) {
                        wikiBatchesSeen++
                        if (wikiBatchesSeen >= wikiEvery) {
                            wikiBatchesSeen = 0
                            val cids = wikiPending
                            wikiPending = emptyList()
                            wikiIterationsDone++
                            runCatching {
                                val wikiNode = borg.trikeshed.lcnc.LcncNode(
                                    id = "wiki-consolidate-lane",
                                    type = borg.trikeshed.lcnc.LcncContracts.WIKI_CONSOLIDATE,
                                    params = mapOf("iteration" to wikiIterationsDone.toString()),
                                )
                                val report = wikiRunner.run(wikiNode, mapOf("cids" to cids))["report"] as? Map<*, *>
                                System.err.println(
                                    "[OROBOROS] Wiki Maintainer iteration $wikiIterationsDone: ok=${report?.get("ok")} " +
                                        "applied=${(report?.get("applied") as? List<*>)?.size ?: "-"} " +
                                        "refused=${(report?.get("refused") as? List<*>)?.size ?: "-"}",
                                )
                            }.onFailure {
                                System.err.println("[OROBOROS] Wiki Maintainer failed (non-fatal): ${it.message}")
                            }
                        }
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
        // ── Brain pin = Hermes' live session. Hermes records, per session, the
        // model and the resolved runtime it actually runs on
        // (sessions.model_config.gateway_runtime: provider, base_url, api_mode)
        // and ranks sessions by real recency (hermes_state.py; mirrored in
        // HermesActiveSession). The daemon runs under the same $HERMES_HOME as
        // the operator's CLI, so the row Hermes would resume IS the model
        // instance to borrow — it follows every `/model` switch with no second
        // config. The key resolves the way Hermes resolves it — the provider's
        // env vars, then its credential pool — which here is the KeyMux
        // `llm.<provider>.key` lane; a runtime that names only a provider gets
        // its base url from `llm.<provider>.base_url` the same way. No session,
        // an api_mode BrainClient cannot speak (it posts /chat/completions), or
        // a key that does not resolve → the roster, with the reason logged.
        // (GLM_MODEL was never a Hermes variable; only GLM_BASE_URL is — the
        // zai base-url override — and the session row already reflects it.)
        val brainErrorSink = borg.trikeshed.jules.JvmBrainErrorSink(forgeHome)
        // LIVE: the ladder below runs at boot AND again on the first call after
        // Hermes' state.db / auth.json / .env change (HermesWatch), so the brain
        // follows a `/model` switch or a key rotation without a restart.
        val hermesWatch = borg.trikeshed.jules.HermesWatch.default()
        // ONE quota legion for every mux in the process, walked back from Hermes' success
        // ledger: a provider's proven limit is the most it ever bore in one day, and what
        // Hermes burned today is already charged — the key is shared, so the pool is.
        // Refreshed (limits + pre-charge, meters kept) whenever the ledger changes.
        val quotaLegion = modelmux.QuotaLegion(windowMs = modelmux.QuotaLegion.DAY_MS)
        fun refreshQuotaLegion() {
            val now = System.currentTimeMillis()
            val db = borg.trikeshed.jules.HermesModelUsage.stateDb()
            quotaLegion.refresh(borg.trikeshed.jules.HermesModelUsage.ledgerRows(db), now)
            System.err.println(
                "[OROBOROS] quota legion from hermes ledger ($db): " +
                    quotaLegion.limitsByProvider.entries.joinToString("; ") { (p, lim) ->
                        "$p proven $lim/day, spent today ${quotaLegion.ledgerSpentFor(p, now)}"
                    }.ifEmpty { "no provider rows — every key unmetered" },
            )
        }
        refreshQuotaLegion()
        suspend fun buildBrain(): Pair<borg.trikeshed.jules.BrainClient, Map<String, Any?>> {
            refreshQuotaLegion()
            val hermesSession = borg.trikeshed.jules.HermesActiveSession.current()
            val hermesProvider = hermesSession?.runtime?.provider
            suspend fun hermesLane(field: String): String? = hermesProvider?.let { provider ->
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching { keyMux.get("llm.$provider.$field") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
            val hermesKey = hermesLane("key")
            val hermesBaseUrl = hermesSession?.runtime?.baseUrl ?: hermesLane("base_url")
            val hermesModel = hermesSession?.model?.takeIf { it.isNotBlank() }
            val hermesPinReason: String = when {
                hermesSession == null -> "no session row in ${borg.trikeshed.jules.HermesActiveSession.stateDb()}"
                hermesModel == null -> "session ${hermesSession.id} carries no model"
                hermesProvider == null -> "session ${hermesSession.id} has no resolved runtime provider"
                !hermesSession.runtime.speaksChatCompletions ->
                    "session ${hermesSession.id} runs api_mode=${hermesSession.runtime.apiMode}, not chat_completions"
                hermesBaseUrl == null -> "no base url for provider $hermesProvider (session row nor llm.$hermesProvider.base_url)"
                hermesKey == null -> "no key resolves for llm.$hermesProvider.key"
                else -> ""
            }
            // Rung 2 (2026-09-04): the launch Hermes makes when no session row
            // pins a runtime — its resume path "falls back to ambient config
            // resolution", i.e. the profile's config.yaml model block
            // (HermesConfigDefault mirrors resolve_runtime_provider). Until this
            // rung existed the daemon fell from a pin-less row straight to the
            // static roster, and the model Hermes runs in this profile was never
            // the model the daemon ran.
            val hermesHome = borg.trikeshed.jules.HermesModelUsage.hermesHome()
            val configOutcome: borg.trikeshed.jules.HermesConfigDefault.Outcome? =
                if (hermesPinReason.isEmpty()) null
                else borg.trikeshed.jules.HermesConfigDefault.resolve(hermesHome, keyLane = { provider, field ->
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        runCatching { keyMux.get("llm.$provider.$field") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    }
                })
            val configLaunch = (configOutcome as? borg.trikeshed.jules.HermesConfigDefault.Outcome.Pinned)?.launch
                ?.takeIf { it.apiMode == "chat_completions" }
            val configReason: String? = when (configOutcome) {
                null -> null
                is borg.trikeshed.jules.HermesConfigDefault.Outcome.Unavailable -> configOutcome.reason
                is borg.trikeshed.jules.HermesConfigDefault.Outcome.Pinned ->
                    if (configLaunch == null) "config default ${configOutcome.launch.model} runs api_mode=${configOutcome.launch.apiMode}, not chat_completions" else null
            }
            val built: borg.trikeshed.jules.BrainClient = if (hermesPinReason.isEmpty() && hermesSession != null && hermesModel != null && hermesBaseUrl != null && hermesKey != null) {
                val seen = java.time.Instant.ofEpochMilli((hermesSession.recencyEpochSeconds * 1000).toLong())
                System.err.println(
                    "[OROBOROS] Brain PINNED to hermes session ${hermesSession.id} (${hermesSession.source}, " +
                        (if (hermesSession.isOpen) "open" else "ended") + ", last activity $seen, ${hermesSession.apiCallCount} api calls): " +
                        "$hermesModel @ $hermesBaseUrl (provider=$hermesProvider, api_mode=${hermesSession.runtime.apiMode ?: "chat_completions"}, ${hermesSession.ledger})",
                )
                // The host Hermes launches against IS an operator-configured provider
                // host: admit it through the substrate's deny-by-default egress gate
                // the same way a configured base_url is (EgressAllowlist.allowUrl).
                // 2026-09-04: the first config-default pin, mimo-v2.5 @ opencode.ai,
                // resolved a key and built a correct request, then died one layer
                // down — "egress denied by substrate: opencode.ai:443" — because
                // that host is Hermes' to know, not the HarnessRegistry's.
                borg.trikeshed.userspace.nio.channels.spi.EgressAllowlist.allowUrl(hermesBaseUrl)
                borg.trikeshed.jules.BrainClient(apiKey = hermesKey, base = hermesBaseUrl.trimEnd('/'), model = hermesModel, errorSink = brainErrorSink, quotaLegion = quotaLegion)
            } else if (configLaunch != null) {
                System.err.println(
                    "[OROBOROS] Brain PINNED to hermes config default (${configLaunch.configFile}; session pin unavailable: $hermesPinReason): " +
                        "${configLaunch.model} @ ${configLaunch.baseUrl} (provider=${configLaunch.provider}, api_mode=${configLaunch.apiMode}, key from ${configLaunch.keySource})",
                )
                borg.trikeshed.userspace.nio.channels.spi.EgressAllowlist.allowUrl(configLaunch.baseUrl)
                borg.trikeshed.jules.BrainClient(apiKey = configLaunch.apiKey, base = configLaunch.baseUrl, model = configLaunch.model, errorSink = brainErrorSink, quotaLegion = quotaLegion)
            } else {
                System.err.println("[OROBOROS] Brain on the provider roster — hermes session pin unavailable: $hermesPinReason; config default unavailable: $configReason")
                borg.trikeshed.jules.BrainClient(errorSink = brainErrorSink, keyMux = keyMux, quotaLegion = quotaLegion)
            }
            val account: Map<String, Any?> = mapOf(
                "pin" to when {
                    hermesPinReason.isEmpty() ->
                        mapOf("source" to "session", "model" to hermesModel, "baseUrl" to hermesBaseUrl, "provider" to hermesProvider, "sessionId" to hermesSession?.id)
                    configLaunch != null ->
                        mapOf("source" to "config", "model" to configLaunch.model, "baseUrl" to configLaunch.baseUrl, "provider" to configLaunch.provider, "configFile" to configLaunch.configFile, "keySource" to configLaunch.keySource)
                    else -> null
                },
                "reason" to listOfNotNull(hermesPinReason.ifEmpty { null }, configReason).joinToString("; ").ifEmpty { null },
                "builtAtMs" to System.currentTimeMillis(),
            )
            return built to account
        }
        val brainClient = borg.trikeshed.jules.LiveBrainClient(hermesWatch, { buildBrain() }, buildBrain())
        // The runner registry is assembled below; the publisher reads it late.
        val lcncRunnersRef = java.util.concurrent.atomic.AtomicReference<Map<String, borg.trikeshed.lcnc.LcncNodeRunner>>(emptyMap())
        // ── The ONE production network and its registry, hoisted above the publisher: the
        //    panels plane (LcncPublisher → PanelFactBridge) writes into it, the couch tendon
        //    and the modules below write into it, and the KIF tee reads every op off it.
        val reteProductions = borg.trikeshed.dag.ReteProductionRegistry()
        val rete = borg.trikeshed.dag.ReteNetwork(reteProductions)
        // KIF half of the join: every fact the network applies is projected into `kifBank`
        // (and un-projected on retract) by one observer — attached before any bridge opens,
        // so nothing needs priming; `prime` is still called once after the bridges open below,
        // idempotently, as the belt to this brace.
        val (kifTee, kifTeeDisposer) = borg.trikeshed.dag.KifTee.attach(rete, kifBank)
        val lcncPublisher = borg.trikeshed.lcnc.LcncPublisher(daemonBlackboard, { lcncRunnersRef.get() }, attachmentGateway, rete, kifBank)
        val patchWire = borg.trikeshed.forge.server.PatchWire(
            brain = brainClient,
            scopes = projectScopes,
            attachments = attachmentGateway,
            publisher = lcncPublisher,
            muxContext = htxElement + muxReactor,
            mountScope = wireScope,
            miner = projectMiner,
        )
        // (boot mounts + ledger remount happen below, once the Rete tendon hook is armed)
        // ── Dynamic modules: Rete (hoisted — the tendon below feeds it) + production
        //    registry + CoW route registry + supervisor (proxy ctors: app CP first,
        //    then a fresh URLClassLoader over build/live/classes — hotswapFeed's tree,
        //    so a class compiled after boot attaches without a bounce).
        // (reteProductions / rete are constructed above the LcncPublisher, which needs them)
        val moduleRoutes = borg.trikeshed.module.ModuleRouteRegistry()
        val moduleScope = CoroutineScope(SupervisorJob(coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default)
        // Spec §3.1 production wiring: ONE stored-program resolver — the offered
        // presets (the panels/ attachment namespace was rooted out 2026-08-27
        // with the browser editor) — shared by module program runs
        // (/api/lcnc/run {program}) and webhook node dispatch.
        // THE LOADER IS THE BLACKBOARD (LcncPublisher.load): the source — a preset,
        // then the user's `panels/<name>` attachment — keeps `lcnc/program/<name>`
        // fresh, and the entry is what the run seam obeys.
        val storedProgramLoader: suspend (String) -> borg.trikeshed.lcnc.LcncProgram? = { name -> lcncPublisher.load(name) }
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
            kifBank = kifBank,
        )
        lcncRunnersRef.set(moduleContext.lcncRunners)
        requestFactoryRpcTargets["session.info"] = borg.trikeshed.relaxfactory.RequestFactoryRpcTarget { args ->
            linkedMapOf<String, Any?>(
                "db" to couchDb.info(),
                "args" to args,
                "lcnc" to linkedMapOf<String, Any?>(
                    "contracts" to borg.trikeshed.lcnc.LcncContracts.all().size,
                    "runners" to moduleContext.lcncRunners.size,
                ),
                "rpcTargets" to requestFactoryRpcTargets.keys.sorted(),
            )
        }
        moduleContext.lcncRunners.putAll(
            borg.trikeshed.lcnc.RequestFactoryNodes.registry(
                borg.trikeshed.relaxfactory.RequestFactoryProxy(
                    borg.trikeshed.relaxfactory.RelaxTransport.local(couchWire.router.requestFactory),
                ),
            ),
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
        // Phase-1 twin removal: `pick` is not a Kotlin lambda. Its existing
        // panels.html RUNNERS method executes in one HostAccess.NONE GraalJS
        // context per invocation; registry() loads that resource on IO.
        moduleContext.lcncRunners.putAll(borg.trikeshed.lcnc.CanvasJsPureNodes.registry())
        // ── hermes.lastUsed: the outcome, next to the intent ──────────
        // mux.meta answers "what is modelmux configured to select" and reports
        // selection:null. Hermes' own state.db answers "what actually replied",
        // and it is not empty. Where those two disagree, the ledger is the one
        // with completions behind it. JVM-side because the ledger is SQLite.
        // Ledger = $HERMES_HOME/state.db; `session` = the row Hermes would resume
        // (HermesActiveSession) and `brainPin` = what the daemon borrowed from it.
        moduleContext.lcncRunners["hermes.lastUsed"] = borg.trikeshed.lcnc.LcncNodeRunner { _, _ ->
            val db = borg.trikeshed.jules.HermesModelUsage.stateDb()
            val recent = borg.trikeshed.jules.HermesModelUsage.recent(db)
            fun row(u: borg.trikeshed.jules.HermesModelUsage.Usage) = mapOf(
                "model" to u.model,
                "provider" to u.provider,
                "baseUrl" to u.baseUrl,
                "task" to u.task,
                "calls" to u.calls,
                "inputTokens" to u.inputTokens,
                "outputTokens" to u.outputTokens,
                "lastSeenMs" to (u.lastSeenEpochSeconds * 1000).toLong(),
            )
            val session = borg.trikeshed.jules.HermesActiveSession.current(db)
            mapOf(
                "ledger" to db.absolutePath,
                "session" to session?.let { s ->
                    mapOf(
                        "id" to s.id,
                        "source" to s.source,
                        "open" to s.isOpen,
                        "model" to s.model,
                        "provider" to s.runtime.provider,
                        "baseUrl" to s.runtime.baseUrl,
                        "apiMode" to s.runtime.apiMode,
                        "lastActivityMs" to (s.recencyEpochSeconds * 1000).toLong(),
                        "messages" to s.messageCount,
                        "apiCalls" to s.apiCallCount,
                        "cwd" to s.cwd,
                    )
                },
                "brainPin" to brainClient.pin["pin"],
                "brainPinReason" to brainClient.pin["reason"],
                "brainPinBuiltAtMs" to brainClient.pin["builtAtMs"],
                "lastUsed" to (recent.firstOrNull()?.let(::row)),
                "recent" to recent.map(::row),
                "provenEndpoints" to borg.trikeshed.jules.HermesModelUsage.provenEndpoints(db)
                    .map { mapOf("provider" to it.first, "baseUrl" to it.second) },
            )
        }
        // ── CCEK itself, programmable ────────────────────────────────
        // Every family above drives a PROJECTION of CCEK (board, council, legal).
        // This one drives the engine: incarnate/signal/agent/projection/recording/
        // status/drain plus context lineage, choreographed by the SAME
        // reactor-bound binding the rest of the process rides (ccekBinding above),
        // so a program addresses the live CCEK rather than a private instance.
        moduleContext.lcncRunners.putAll(
            borg.trikeshed.lcnc.CcekNodes.registry(borg.trikeshed.lcnc.CcekSeams.live(ccekBinding)),
        )
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
                // The RDF projection of the whole fact plane at freeze time (was an empty graph).
                graph = { borg.trikeshed.rdf.RdfGraph(kotlinx.coroutines.runBlocking { rete.snapshot() }.flatMap(borg.trikeshed.dag.PlaneFacts::toTriples)) },
                cas = casStore,
            )
            moduleContext.lcncRunners["state.thaw"] = borg.trikeshed.narsese.StateNodes.thawRunner(
                bag = bag,
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
            // …and law that is only in memory is not law after a restart. Each admitted rule is
            // filed under its own ruleCid in the `rete-rule/` couch plane, which the boot thaw
            // below re-admits — the rules counterpart of the `kif-ledger/` axiom plane.
            moduleContext.lcncRunners["nal.rule.admit"] = borg.trikeshed.narsese.RuleNodes.ruleAdmitRunner(
                liveRete,
                ledger = { r ->
                    runCatching {
                        couchDb.put(
                            "rete-rule/${r.ruleCid.hex}",
                            mapOf(
                                "antecedent" to r.antecedent,
                                "consequent" to r.consequent,
                                "copula" to r.copula.name,
                                "evidence" to r.evidence.packed.toString(),
                                "provenanceCid" to (r.provenanceCid ?: ""),
                                "atMs" to System.currentTimeMillis(),
                            ),
                            null,
                        )
                    }.onFailure { System.err.println("[OROBOROS] rete-rule ledger write failed (non-fatal): ${it.message}") }
                    // Same reason as the axiom ledger: couch cannot find its own documents after
                    // a restart, so the durable record of admitted law is the file.
                    runCatching { borg.trikeshed.narsese.NarsDurableLedger.appendRule(forgeHome, r) }
                        .onFailure { System.err.println("[OROBOROS] rete-rule file ledger write failed (non-fatal): ${it.message}") }
                },
            )
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
                "state.freeze", "state.thaw", "kanban.attention", "kanban.drift", "kanban.review",
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
                // Durable tee: the in-memory bank dies with the process; the
                // couch ledger is what the council boot thaw re-asserts from.
                runCatching { couchDb.put("kif-ledger/${cid.hex}", mapOf("kif" to kif, "source" to "legal.ingest", "atMs" to System.currentTimeMillis()), null) }
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
                            // 600 starved every seat on a REASONING model: GLM 4.x flash
                            // spends this same budget thinking, so mux.chat returned
                            // content:"" on every non-trivial prompt while succeeding on
                            // "say OK" (mission-002 M2 measured it four times). The floor
                            // is the seat's authored budget; TRIKESHED_SEAT_MAXTOKENS
                            // overrides for a model that needs more headroom.
                            val seatBudget = (System.getenv("TRIKESHED_SEAT_MAXTOKENS")?.toIntOrNull() ?: 3000)
                            val content = brainClient.chat(
                                listOf("system" to system, "user" to prompt),
                                maxTokens = seatBudget,
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
        // ModelMux for the mux.* LCNC nodes: built from the brain's full
        // provider ROSTER (the static table, un-gated) over the daemon's
        // shared KeyMux — so the panel reflects every provider this machine
        // could talk to, not the brain's runtime pin (GLM single-endpoint).
        // One key pool: every card's provider tag resolves llm.<provider>.key
        // through the same env → dotenv → harness chain keys.status reports.
        // chatContext rides HtxKey + the MuxReactor: ModelMux.chat resolves
        // the HTX client and reactor metering from the caller's context, and
        // the CCEK assembly scope carries the reactor but NOT the HTX element.
        // Card ids must be UNIQUE. ModelMux.session takes the first entry whose id
        // matches, so a model id served by two providers is not ambiguous — the
        // second one is silently unreachable while still being listed in the panel.
        // The roster collides exactly so (z-ai/glm-5.2 under both nvidia and
        // openrouter; one hermes model under three endpoints), which is why the
        // panel offered a menu whose entries mostly failed. disambiguateModelIds
        // keeps the bare id for the first claimant and qualifies the rest.
        // LIVE: cards are rebuilt on the first mux call after Hermes' files
        // change, so the picklist shows a `/model` switch on its next open.
        suspend fun buildLcncMux(): modelmux.ModelMux {
            // Cards come from Hermes FIRST — the model instances it has run on and
            // been answered by ($HERMES_HOME/state.db: sessions + session_model_usage,
            // HermesInstances) — then the static roster for whatever Hermes has not
            // touched. The live picklist prompt.chat declares (mux.models#models[].id)
            // therefore offers what actually runs here, not a hand-typed table.
            // …and only the rows THIS daemon can authenticate: a provider whose
            // `llm.<provider>.key` does not resolve here would be a dead entry in the
            // listbox, so it is reported, not offered.
            val hermesKnown = borg.trikeshed.jules.HermesInstances.known()
            val hermesKeyed = kotlinx.coroutines.withContext(Dispatchers.IO) {
                hermesKnown.map { it.provider }.distinct().associateWith { provider ->
                    runCatching { keyMux.get("llm.$provider.key") }.getOrNull()?.isNotBlank() == true
                }
            }
            val hermesInstances = hermesKnown.filter { hermesKeyed[it.provider] == true }
            val hermesSpecs = borg.trikeshed.jules.HermesInstances.specs(hermesInstances)
            val hermesClaimed = hermesSpecs.mapTo(HashSet()) { it.provider to it.model }
            val lcncRoster = hermesSpecs + brainClient.providerRoster().filterNot { (it.provider ?: it.name) to it.model in hermesClaimed }
            System.err.println(
                "[OROBOROS] mux cards: ${hermesSpecs.size} from hermes (${borg.trikeshed.jules.HermesModelUsage.stateDb()}), " +
                    "${lcncRoster.size - hermesSpecs.size} from the static roster" +
                    hermesInstances.take(3).joinToString(prefix = " — newest: ", separator = ", ") { "${it.model}@${it.provider}" } +
                    hermesKeyed.filterValues { !it }.keys.takeIf { it.isNotEmpty() }
                        ?.joinToString(prefix = "; hermes providers with no key here (not offered): ", separator = ", ").orEmpty(),
            )
            val lcncRosterEntries = lcncRoster.map {
                modelmux.RosterEntry(provider = it.provider ?: it.name, model = it.model)
            }
            val lcncCardIds = modelmux.disambiguateModelIds(lcncRosterEntries)
            modelmux.shadowedEntries(lcncRosterEntries).forEach { (entry, id) ->
                System.err.println(
                    "[OROBOROS] roster collision: ${entry.provider} serves '${entry.model}', " +
                        "already claimed — routable as '$id' (previously unreachable)",
                )
            }
            // Quota walked back from Hermes' success ledger: a provider's proven limit is the
            // most it ever bore in one day, and what Hermes burned today is already charged —
            // the key is shared, so the pool is. Rebuilt with the mux on every ledger change.
            refreshQuotaLegion()
            return modelmux.ModelMux(keyMux) {
                quota(quotaLegion)
                lcncRoster.forEachIndexed { i, ep ->
                    model(
                        id = lcncCardIds[i],
                        caps = setOf("chat", "conflict-resolve"),
                        baseUrl = ep.base,
                        provider = ep.provider ?: ep.name,
                        // The qualified id is a LOCAL routing key; the provider still
                        // only answers to its own name for the model.
                        wireModel = ep.model,
                    )
                }
            }
        }
        val lcncMux = borg.trikeshed.jules.LiveHolder(hermesWatch, buildLcncMux()) { buildLcncMux() }
        moduleContext.lcncRunners.putAll(
            borg.trikeshed.lcnc.BrainMuxNodes.registry(
                keyMux = keyMux,
                modelMuxProvider = { lcncMux.current() },
                credStore = couchKeyStore,
                chatContext = htxElement + muxReactor,
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
        // ── Legal council (design/legal-council-3x5.md): the 3x5 preset's node
        //    family. Its OWN seam — CouncilDialog → BrainClient.chatSeat — so
        //    every seat carries its authored maxTokens/temperature/preferred
        //    model and a council/<caseId>/<panel>/<seat> spend contextId (the
        //    legacy tribunal closure above, with its hardcoded maxTokens=600 /
        //    contextId="tribunal-seat", is intentionally untouched: the council
        //    supersedes it; preset-tribunal keeps byte-identical semantics).
        val councilDialog = borg.trikeshed.lcnc.CouncilDialog { call ->
            runCatching {
                kotlinx.coroutines.withContext(htxElement + muxReactor) {
                    brainClient.chatSeat(
                        messages = listOf("system" to call.system, "user" to call.prompt),
                        maxTokens = call.maxTokens,
                        temperature = call.temperature,
                        contextId = call.contextId.takeIf { it.isNotBlank() },
                        preferredModel = call.preferredModel,
                    )
                }
            }.fold(
                onSuccess = { (content, answeredBy) -> borg.trikeshed.lcnc.SeatOutcome.Ok(content, answeredBy) },
                onFailure = { t ->
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    borg.trikeshed.lcnc.SeatOutcome.Refused(
                        t.message ?: t.toString(),
                        (t as? borg.trikeshed.jules.BrainNoRoute)?.attempts ?: emptyList(),
                    )
                },
            )
        }
        // Per-case job nexus: every convened case gets its own lifecycle (the
        // boot TribunalInstance singleton above stays legacy-preset-only).
        val councilCases = borg.trikeshed.lcnc.CouncilCaseRegistry(
            moduleScope, borg.trikeshed.lcnc.TribunalInstance.schemaPlan(),
        )
        // The council's KIF sink: blackboard provenance + live bank + durable
        // couch ledger (same tee shape as legal.ingest's sink above).
        val councilKifSink: (String) -> Unit = { kif ->
            val cid = borg.trikeshed.job.ContentId.of(kif.encodeToByteArray())
            daemonBlackboard.put("legal-kif/${cid.hex}", kif, "council.record")
            runCatching { kifBank.assertKif(kif) }
            runCatching { couchDb.put("kif-ledger/${cid.hex}", mapOf("kif" to kif, "source" to "council", "atMs" to System.currentTimeMillis()), null) }
        }
        moduleContext.lcncRunners.putAll(
            borg.trikeshed.lcnc.CouncilNodes.registry(
                dialog = councilDialog,
                seams = borg.trikeshed.lcnc.RecordSeams(
                    casPut = { bytes -> casStore.put(bytes).value },
                    blackboardPut = { key, fact, source -> daemonBlackboard.put(key, fact, source) },
                    kifSink = councilKifSink,
                    couchPut = { docId, body ->
                        runCatching {
                            val rev = couchDb.docJson(docId)?.get("_rev") as? String
                            couchDb.put(docId, body, rev)
                        }
                    },
                    recordRuling = councilCases::recordRuling,
                    recordMistrial = councilCases::recordMistrial,
                    blackboardGet = { key ->
                        (daemonBlackboard.get(key) as? Map<*, *>)
                            ?.entries?.associate { (k, v) -> k.toString() to (v?.toString() ?: "") }
                    },
                    couchGet = { docId -> couchDb.docJson(docId) },
                    casGet = { cid -> runCatching { casStore.get(borg.trikeshed.job.ContentId(cid)) }.getOrNull() },
                ),
            ),
        )
        // ── WikiSkill (arXiv 2608.27454) as two legos: wiki.consolidate (the
        //    Wiki Maintainer, one iteration per invocation) and wiki.propose
        //    (the Skill Proposer, one atomic proposal per pass). Their ONE
        //    spend seam is BrainClient.chatSeat under the same htx+mux context
        //    the council uses, so every Maintainer/Proposer token lands on the
        //    daemon's quota/lease receipts. Wiki state lives under the FORGE
        //    home (<forgeHome>/wiki) — never the repo worktree. The raw/ layer
        //    is the CAS, with a cid-VERIFIED rebuild from the hermes profile
        //    for snapshots this process never wrote (WikiTraceSources).
        //    The same consolidate runner instance is armed into the curation
        //    loop's lane seam — manual /api/lcnc/run invocations and the
        //    automatic feeder-driven lane hit ONE wiki, ONE spend seam.
        val wikiDialog = borg.trikeshed.wiki.WikiNodes.WikiDialog { call ->
            val (content, answeredBy) = kotlinx.coroutines.withContext(htxElement + muxReactor) {
                brainClient.chatSeat(
                    messages = listOf("system" to call.system, "user" to call.prompt),
                    maxTokens = call.maxTokens,
                    temperature = call.temperature,
                    contextId = call.contextId.takeIf { it.isNotBlank() },
                    preferredModel = call.preferredModel,
                )
            }
            borg.trikeshed.wiki.WikiNodes.WikiReply(content, answeredBy)
        }
        val wikiRoot = { File(forgeHome, "wiki") }
        val wikiTraces = borg.trikeshed.wiki.WikiTraceSources.loader(
            cas = casStore,
            profileDir = System.getenv("HERMES_PROFILE")?.let { File(it) } ?: File(hermesHomeDir.absolutePath),
        )
        moduleContext.lcncRunners[borg.trikeshed.lcnc.LcncContracts.WIKI_CONSOLIDATE] =
            borg.trikeshed.wiki.WikiNodes.consolidateRunner(
                dialog = wikiDialog, wikiRoot = wikiRoot, traces = wikiTraces,
                casPut = { bytes -> casStore.put(bytes).value },
            ).also { wikiLane[0] = it }
        moduleContext.lcncRunners[borg.trikeshed.lcnc.LcncContracts.WIKI_PROPOSE] =
            borg.trikeshed.wiki.WikiNodes.proposeRunner(
                dialog = wikiDialog, wikiRoot = wikiRoot, traces = wikiTraces,
                casPut = { bytes -> casStore.put(bytes).value },
            )
        // Both wiki legos above call a model to do their work, so until now READING a curated
        // artifact needed an API key. VAL-CROSS-002 found the curation plane hosted, durable and
        // unreachable as itself. These two routes are the read side, and they touch nothing.
        val wikiReadWire = borg.trikeshed.forge.server.WikiReadWire(wikiRoot)
        System.err.println(
            "[OROBOROS] Wiki read routes armed: " +
                borg.trikeshed.forge.server.WikiReadWire.ROUTES.joinToString(" ") { r -> "${r.first} ${r.second}" } +
                " root=${wikiRoot().absolutePath}"
        )
        // Boot thaw (non-fatal, like the tribunal open): the kifBank and the
        // blackboard are in-memory — couch + FileCasStore are the durable
        // planes. Re-assert the kif-ledger/ facts and re-land the
        // council-case/ index facts so the bank and the GET read-back
        // survive a restart.
        launch(Dispatchers.Default) {
            runCatching {
                var kifCount = 0
                var caseCount = 0
                val kifRows = couchDb.allDocs(startkey = "kif-ledger/", endkey = "kif-ledger/\uFFF0", includeDocs = true)["rows"] as? List<*> ?: emptyList<Any?>()
                for (row in kifRows) {
                    val rowMap = row as? Map<*, *> ?: continue
                    if (rowMap["id"]?.toString()?.startsWith("kif-ledger/") != true) continue
                    val kif = (rowMap["doc"] as? Map<*, *>)?.get("kif") as? String ?: continue
                    runCatching { kifBank.assertKif(kif) }.onSuccess { kifCount++ }
                }
                // The couch loop above can only see documents THIS boot wrote — casBacked's head
                // projection starts empty every time. The file ledger is what actually carries
                // taught axioms across a restart.
                var kifFileCount = 0
                for (kif in borg.trikeshed.narsese.NarsDurableLedger.readAxioms(forgeHome)) {
                    runCatching { kifBank.assertKif(kif) }.onSuccess { kifFileCount++ }
                }
                val caseRows = couchDb.allDocs(startkey = "council-case/", endkey = "council-case/\uFFF0", includeDocs = true)["rows"] as? List<*> ?: emptyList<Any?>()
                for (row in caseRows) {
                    val rowMap = row as? Map<*, *> ?: continue
                    val docId = rowMap["id"]?.toString() ?: continue
                    if (!docId.startsWith("council-case/")) continue
                    val doc = rowMap["doc"] as? Map<*, *> ?: continue
                    // The couch case doc carries no caseCid (a doc cannot embed
                    // its own hash); the read-back resolves transcript/verdict
                    // from these cids directly.
                    daemonBlackboard.put(docId, mapOf(
                        "verdictCid" to (doc["verdictCid"]?.toString() ?: ""),
                        "transcriptCid" to (doc["transcriptCid"]?.toString() ?: ""),
                        "documentCid" to (doc["documentCid"]?.toString() ?: ""),
                        "status" to (doc["status"]?.toString() ?: ""),
                    ), "council-thaw")
                    caseCount++
                }
                // Rules half of the thaw: the rete boots over zero rules, so every rule ever
                // admitted is re-offered from the `rete-rule/` plane. admit() is a set union on
                // ruleCid, so replaying the whole plane is idempotent.
                var ruleCount = 0
                causalityRete?.let { liveRete ->
                    val ruleRows = couchDb.allDocs(startkey = "rete-rule/", endkey = "rete-rule/￰", includeDocs = true)["rows"] as? List<*> ?: emptyList<Any?>()
                    val restored = ArrayList<borg.trikeshed.narsese.EternalRule>()
                    for (row in ruleRows) {
                        val rowMap = row as? Map<*, *> ?: continue
                        if (rowMap["id"]?.toString()?.startsWith("rete-rule/") != true) continue
                        val doc = rowMap["doc"] as? Map<*, *> ?: continue
                        val antecedent = doc["antecedent"]?.toString() ?: continue
                        val consequent = doc["consequent"]?.toString() ?: continue
                        val packed = doc["evidence"]?.toString()?.toLongOrNull() ?: continue
                        val copula = runCatching {
                            borg.trikeshed.narsese.NalCopula.valueOf(doc["copula"]?.toString() ?: "")
                        }.getOrNull() ?: continue
                        restored.add(
                            borg.trikeshed.narsese.EternalRule(
                                antecedent = antecedent,
                                consequent = consequent,
                                copula = copula,
                                evidence = borg.trikeshed.narsese.EvidenceCoord(packed),
                                provenanceCid = doc["provenanceCid"]?.toString()?.takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                    restored.addAll(borg.trikeshed.narsese.NarsDurableLedger.readRules(forgeHome))
                    if (restored.isNotEmpty()) ruleCount = liveRete.admit(borg.trikeshed.lib.seriesOf(restored))
                }
                System.err.println(
                    "[OROBOROS] council thaw: $kifCount kif facts (couch) + $kifFileCount (durable ledger), " +
                        "$caseCount cases re-indexed, $ruleCount eternal rules re-admitted"
                )
            }.onFailure {
                System.err.println("[OROBOROS] council thaw failed (non-fatal): ${it.message}")
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
        // The canvas as RDF, joined with what watches it: productions (Rete),
        // causal rules (CausalityRete), and KIF facts (curator bank).
        val rdfWire = borg.trikeshed.forge.server.LcncRdfWire(
            productions = { reteProductions.all() },
            causalRules = { causalityRete?.rules?.let { r -> (0 until r.size).map { r[it] } }.orEmpty() },
            facts = { pattern -> curatorImpulse?.let { c -> runCatching { c.queryBank(pattern) }.getOrDefault(emptyList()) }.orEmpty() },
        )
        // The fact plane itself, read-only: /api/rete/facts, /api/facts/rdf, /api/rete/productions.
        val reteWire = borg.trikeshed.forge.server.ReteWire(rete)
        // The hover blip: one LCNC node read across panels + KIF + productions + graal (/api/lcnc/blip).
        val blipWire = borg.trikeshed.forge.server.LcncBlipWire(
            network = rete,
            kif = { pattern -> curatorImpulse?.let { c -> runCatching { c.queryBank(pattern) }.getOrDefault(emptyList()) }.orEmpty() },
            productions = { reteProductions.all() },
        )
        val extraRouteList: List<borg.trikeshed.litebike.ExtraRoute> = listOfNotNull(
            graalWire::route, vmWire::route, hermesWire::route, beliefWire?.let { it::route },
            patchWire::route, moduleWire::route, webhookWire::route, blackboardWire::route, rdfWire::route,
            reteWire::route, blipWire::route,
        )
        // ── the surface family: node types the canvas could only reach by fetch ──
        // blackboard.*, graal.vitals/heap, vms.list, panels.list … existed (board.get /
        // board.view moved to KanbanModule: units over BoardStoreElement, not a self-fetch)
        // as contracts plus small JS wrappers, so a headless run (webhook delivery,
        // /api/lcnc/run, a scheduled program) threw LcncUnknownNodeType — preset-hermes
        // could not run outside a browser tab. These dispatch to the daemon's OWN
        // handlers in process: one author, no second implementation to drift.
        moduleContext.lcncRunners.putAll(
            borg.trikeshed.lcnc.SurfaceNodes.registry { method, path, body ->
                val text = buildString {
                    append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    append("Content-Type: application/json\r\n\r\n")
                    if (body != null) append(JsonSupport.stringify(body))
                }
                val claimed = moduleRoutes.match(path)?.route
                val response = claimed?.invoke(method, path, text, null)
                    ?: extraRouteList.firstNotNullOfOrNull { it(method, path, text, null) }
                response?.body?.let { runCatching { JsonSupport.parse(it) }.getOrDefault(it) }
            },
        )
        val kanbanServer = JvmKanbanServer(
            extraRoutes = extraRouteList,
            rawRoutes = listOfNotNull(graalWire::ingestRoute, couchWire::route, projectDbWire::route, casReflinkWire?.let { it::route }, wikiReadWire::route),
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
        // (the adapter itself is constructed next to the Hypervisor, which shares it)
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
            // This watcher wants the git DB and nothing else, but the walk is rooted at the repo:
            // 15,232 of this checkout's 16,537 directories live under .claude/worktrees, so
            // without pruning it registered an OS watch on 92% of the tree to observe none of it.
            // `.git` stays walkable — everything this watcher exists for is under it.
            walkerBlockedSegments = WorktreeCouchGateway.EXCLUDED_SEGMENTS - ".git",
            walkerBlockedRelativePrefixes = WorktreeCouchGateway.EXCLUDED_RELATIVE_PREFIXES,
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
            // One file at the forge root, but the walk is rooted at the forge home — which is
            // where the CAS store lives. Pruning the store's shard tree keeps this watcher the
            // size of the thing it actually watches.
            walkerBlockedSegments = WorktreeCouchGateway.EXCLUDED_SEGMENTS,
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
            // Derived from the gateway's own ignore sets, never hand-listed here again: the
            // hand-listed version omitted `logs/`, so the daemon's log writes woke the watcher
            // whose reconcile wrote that log — a permanent self-feeding quake at 95% CPU.
            excludeGlobs = WorktreeCouchGateway.watcherExcludeGlobs(),
            // …and prune the same paths from the WALK, not just from event delivery. Registering
            // an OS watch on every directory of .git and of every .claude/worktrees checkout cost
            // minutes of boot and a permanent watch set, for events this daemon then discards.
            walkerBlockedSegments = WorktreeCouchGateway.EXCLUDED_SEGMENTS,
            walkerBlockedRelativePrefixes = WorktreeCouchGateway.EXCLUDED_RELATIVE_PREFIXES,
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
                    "[OROBOROS] Worktree→Couch initial reconcile: ${worktreeSnap.paths.size} paths" +
                        (if (worktreeSnap.skippedDirs.isEmpty()) ""
                        else " — INCOMPLETE: ${worktreeSnap.skippedDirs.size} unreadable dir(s), " +
                            "nothing under them tombstoned: ${worktreeSnap.skippedDirs.take(5)}") +
                        (if (worktreeSnap.skippedFiles.isEmpty()) ""
                        else " — ${worktreeSnap.skippedFiles.size} file(s) over " +
                            "${borg.trikeshed.util.oroboros.WorktreeCouchGateway.MAX_FILE_BYTES shr 20} MiB skipped: " +
                            "${worktreeSnap.skippedFiles.take(3)}")
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
            // pointcut/ docs stay out: the graal tendon below asserts each landing once, on the graal partition.
            admit = { !it.docId.startsWith("_design/") && !it.docId.startsWith(GitCouchGateway.GIT_PREFIX) && !it.docId.startsWith("pointcut/") },
            parentJob = kanbanJob,
        )
        launch {
            changesFacts.open()
            System.err.println("[OROBOROS] Changes→Rete tendon: ${changesFacts.state} — ${changesFacts.factsApplied} facts from the initial reconcile, commits=${reportReactor.reportState.value.commits}")
        }

        // ── Tendon: daemonBlackboard keys → Rete facts (partition "blackboard"). Admit table is
        //    BlackboardNamespaces: rule-firing outputs (kanban/rule/, narsese/curation/,
        //    narsese/rete/firing/) stay receipt-only so the sink cannot re-enter the network.
        val blackboardFacts = borg.trikeshed.graal.BlackboardChangesFactElement(
            blackboard = daemonBlackboard,
            rete = rete,
            parentJob = kanbanJob,
        )
        // ── Tendon: the runtime → Rete facts (partition "graal"): bounded STATE facts per tick
        //    (memory, gc/<collector>, jit, deopt/<method> capped, alloc/<class> top-N) plus one
        //    fact per pointcut landing key off the one shared adapter (daemon + Hypervisor lanes).
        val graalFacts = borg.trikeshed.graal.vitals.GraalFactElement(
            vitals = jvmVitals,
            rete = rete,
            pointcutFlows = listOf(pointcutAdapter.flow),
            tickMs = borg.trikeshed.graal.vitals.GraalFactElement.DEFAULT_TICK_MS,
            parentJob = kanbanJob,
        )
        launch {
            blackboardFacts.open()
            System.err.println("[OROBOROS] Blackboard→Rete tendon: ${blackboardFacts.state} — ${blackboardFacts.factsApplied} facts from the initial drain of ${daemonBlackboard.keys().size} keys")
            graalFacts.open()
            System.err.println("[OROBOROS] Graal→Rete tendon: ${graalFacts.state}")
            // Belt to the tee's brace: anything the network held before the observer attached is projected once.
            kifTee.prime(rete)
            System.err.println("[OROBOROS] Rete→KIF tee: ${kifTee.trackedCount()} facts projected into the one bank")
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
                // Plane tendons close before their supervisor is cancelled, so close() joins the drains cleanly.
                runCatching { blackboardFacts.close() }
                runCatching { graalFacts.close() }
                runCatching { kifTeeDisposer.close() }
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
            val outAsync = java.util.concurrent.CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readText().trim() }
            val finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                return@withContext ""
            }
            outAsync.get()
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
