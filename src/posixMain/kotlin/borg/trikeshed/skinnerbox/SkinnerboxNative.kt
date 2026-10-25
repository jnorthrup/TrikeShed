@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package borg.trikeshed.skinnerbox

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.*
import platform.posix.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Set by SIGTERM or SIGINT; the serving loop turns it into the drain MeshTool awaits. */
private val stopRequested = AtomicInt(0)

/**
 * Native entry point of the skinnerbox mesh node, the same [MeshTool] the JVM's SkinnerboxCli runs:
 * `entryPoint = "borg.trikeshed.skinnerbox.skinnerboxMain"`. Exit status 0, 1 on a failure, 130 when cancelled.
 */
fun skinnerboxMain(args: Array<String>) {
    // A peer that goes away must be a failed write the node can report, not a signal that ends the process mid-reply.
    signal(SIGPIPE, SIG_IGN)
    if (args.isEmpty() || args[0] in setOf("help", "--help", "-h")) {
        println(MeshTool.HELP)
        return
    }
    val serving = args[0] == "serve"
    if (serving) {
        signal(SIGTERM, staticCFunction { _: Int -> stopRequested.store(1) })
        signal(SIGINT, staticCFunction { _: Int -> stopRequested.store(1) })
    }
    val status = try {
        runBlocking {
            val stop = CompletableDeferred<Unit>()
            val watch = if (serving) launch {
                while (stopRequested.load() == 0) delay(100)
                stop.complete(Unit)
            } else null
            val nio = NioSupervisor()
            nio.open()
            try {
                val files = requireNotNull(nio.service<FileOperations>()) { "No filesystem provider" }
                withContext(nio + files) {
                    println(MeshTool.run(this, files, args.toList(), stop) { diagnose(it) })
                }
            } finally {
                watch?.cancel()
                withContext(NonCancellable) { nio.drain() }
            }
        }
        0
    } catch (cancelled: CancellationException) {
        diagnose("skinnerbox: cancelled")
        130
    } catch (failure: Exception) {
        diagnose("skinnerbox: ${failure.message ?: failure::class.simpleName}")
        1
    }
    fflush(stdout)
    exit(status)
}

private fun diagnose(line: String) {
    fputs("$line\n", stderr)
    fflush(stderr)
}
