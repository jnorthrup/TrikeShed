package borg.trikeshed.viewserver

import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ReactorOperations
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.spi.platformNioProviders
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * JVM entry point for git self-host CLI.
 *
 * Usage:
 *   java ... borg.trikeshed.viewserver.GitSelfHostMain [options]
 *
 * Options:
 *   --repo-root PATH    Git repository root (default: current dir)
 *   --couch-url URL     CouchDB URL (default: http://localhost:5984)
 *   --port PORT         Server port (default: 5984)
 *   --sync-only         One-shot sync, then exit
 */
fun main(args: Array<CharSequence>) {
    val options = parseArgs(args)

    // Build CCEK context from JVM platform providers
    val providers = platformNioProviders()
    var ctx: CoroutineContext = EmptyCoroutineContext
    for (p in providers) {
        ctx = ctx + p
    }

    val fileOps = ctx[FileOperations.Key]
        ?: error("FileOperations not found in platform providers")
    val channelOps = ctx[ChannelOperations.Key]
        ?: error("ChannelOperations not found in platform providers")
    val reactorOps = ctx[ReactorOperations.Key]
        ?: error("ReactorOperations not found in platform providers")

    runBlocking(ctx) {
        val host = GitTreeSelfHost(
            fileOps = fileOps,
            repoRoot = options["repo-root"] ?: System.getProperty("user.dir"),
            couchUrl = options["couch-url"] ?: "http://localhost:5984",
            port = (options["port"]?.toIntOrNull() ?: 5984),
        )

        if (options.containsKey("sync-only")) {
            // One-shot sync
            println("GitTreeSelfHost: running one-shot sync...")
            val syncEngine = CouchSyncEngine(
                fileOps = fileOps,
                blockStore = borg.trikeshed.miniduck.tablespace.NioBlockStore(
                    fileOps.resolvePath(options["repo-root"] ?: System.getProperty("user.dir"), ".couch"),
                    fileOps
                ),
                wal = borg.trikeshed.miniduck.tablespace.NioBlockWal(
                    fileOps.resolvePath(options["repo-root"] ?: System.getProperty("user.dir"), ".couch"),
                    fileOps
                ),
                couchUrl = options["couch-url"] ?: "http://localhost:5984",
                database = "trike_git",
            )
            val htx = borg.trikeshed.htx.client.HtxElement()
            htx.registerTransport(
                borg.trikeshed.htx.client.HtxTransport.HTTPS,
                borg.trikeshed.htx.client.createHttpsHandler()
            )
            syncEngine.fullSync(htx)
            println("Sync complete.")
        } else {
            // Boot server
            val port = options["port"]?.toIntOrNull() ?: 5984
            println("GitTreeSelfHost: booting on port $port...")
            println("  Repo root: ${options["repo-root"] ?: System.getProperty("user.dir")}")
            println("  CouchDB:   ${options["couch-url"] ?: "http://localhost:5984"}")

            coroutineScope {
                host.boot(this)
                // Keep alive until SIGINT
                println("Press Ctrl-C to stop.")
                while (isActive) {
                    delay(1000)
                }
            }
        }
    }
}

private fun parseArgs(args: Array<CharSequence>): Map<CharSequence, CharSequence> {
    val options = mutableMapOf<CharSequence, CharSequence>()
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--repo-root" -> { i++; options["repo-root"] = args.getOrNull(i) ?: "" }
            "--couch-url" -> { i++; options["couch-url"] = args.getOrNull(i) ?: "" }
            "--port" -> { i++; options["port"] = args.getOrNull(i) ?: "" }
            "--sync-only" -> options["sync-only"] = "true"
        }
        i++
    }
    return options
}
