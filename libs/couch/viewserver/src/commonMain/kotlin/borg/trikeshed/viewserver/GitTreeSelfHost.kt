package borg.trikeshed.viewserver

import borg.trikeshed.couch.CouchElement
import borg.trikeshed.htx.client.HtxElement
import borg.trikeshed.htx.client.HtxTransport
import borg.trikeshed.htx.client.createHttpsHandler
import borg.trikeshed.miniduck.tablespace.NioBlockStore
import borg.trikeshed.miniduck.tablespace.NioBlockWal
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.viewserver.ReactorCouchServer
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Self-host: serves this git repository's source tree from CouchDB,
 * watches for file changes, and syncs them to the local CouchDB instance.
 *
 * ## Usage
 *
 * ```
 * val host = GitTreeSelfHost(
 *     fileOps = FileOperations.fromContext(ctx),
 *     repoRoot = "/Users/jim/work/TrikeShed",
 *     couchUrl = "http://localhost:5984",
 * )
 * coroutineScope {
 *     host.boot(this)
 * }
 * ```
 *
 * This starts:
 *  1. ReactorCouchServer on port 5984 (self-hosting)
 *  2. Full git index sync to CouchDB
 *  3. Continuous file watcher + sync loop
 *  4. Registers design document views
 *
 * After boot, visit `http://localhost:5984/trike_git/_design/git_tree/_view/by_path`
 * to browse the source tree via CouchDB's HTTP API.
 */
class GitTreeSelfHost(
    private val fileOps: FileOperations,
    private val repoRoot: String,
    private val couchUrl: String = "http://localhost:5984",
    private val port: Int = 5984,
) {
    private val store = NioBlockStore(fileOps.resolvePath(repoRoot, ".couch"), fileOps)
    private val wal = NioBlockWal(fileOps.resolvePath(repoRoot, ".couch"), fileOps)
    private val couch = CouchElement()
    private val htx = HtxElement()

    /**
     * Boot the self-hosted CouchDB instance and start syncing.
     * Blocking on the caller's coroutine scope.
     */
    suspend fun boot(scope: CoroutineScope): Job {
        val server = ReactorCouchServer(
            channelOps = borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations(),
            reactorOps = borg.trikeshed.userspace.nio.channels.spi.JvmReactorOperations(),
            couch = couch,
            store = store,
            wal = wal,
            compileJs = { source ->
                object : borg.trikeshed.viewserver.CompiledFunction {
                    override fun map(doc: Map<String, Any?>, emit: (key: Any?, value: Any?) -> Unit) {
                        // JVM: use default no-op compiler (view functions are JS, run via CouchDB's own view server)
                    }
                    override fun reduce(sources: List<String>, values: List<Any?>, rereduce: Boolean): Any? = null
                }
            },
            port = port,
        )

        // Register the git_tree views
        server.registerView("trike_git", "git_tree", "by_path",
            "function(doc){if(doc.path) emit(doc.path, null)}")
        server.registerView("trike_git", "git_tree", "by_sha1",
            "function(doc){if(doc.sha1) emit(doc.sha1, null)}")

        // Boot the server
        val serverJob = server.startIn(scope)

        // Create the sync engine
        val syncEngine = CouchSyncEngine(
            fileOps = fileOps,
            blockStore = store,
            wal = wal,
            couchUrl = couchUrl,
            database = "trike_git",
        )

        // Register views on the target
        htx.registerTransport(HtxTransport.HTTPS, createHttpsHandler())
        syncEngine.registerViews(htx)

        // Full sync first
        syncEngine.fullSync(htx)

        // Continuous watch + sync
        val syncJob = syncEngine.startContinuous(scope, htx, repoRoot)

        println("GitTreeSelfHost: booted on port $port")
        println("  Database: trike_git")
        println("  Views: _design/git_tree/_view/by_path")
        println("  Watching: $repoRoot")

        return serverJob
    }
}
