package borg.trikeshed.skinnerbox

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.*
import kotlin.system.exitProcess

/** JVM process boundary; storage, networking, configuration and application ownership are common Kotlin. */
object SkinnerboxCli {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.isEmpty() || arguments[0] in setOf("help", "--help", "-h")) {
            println(MeshTool.HELP)
            return
        }
        val stop = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val shutdown = Thread({ runBlocking { stop.complete(Unit); finished.await() } }, "skinnerbox-drain")
        val serving = arguments.firstOrNull() == "serve"
        if (serving) Runtime.getRuntime().addShutdownHook(shutdown)
        val status = try {
            runBlocking {
                val nio = NioSupervisor()
                nio.open()
                try {
                    val files = requireNotNull(nio.service<FileOperations>()) { "No filesystem provider" }
                    withContext(nio + files) {
                        println(MeshTool.run(this, files, arguments.toList(), stop) { System.err.println(it) })
                    }
                } finally { withContext(NonCancellable) { nio.drain() } }
            }
            0
        } catch (cancelled: CancellationException) {
            System.err.println("skinnerbox: cancelled")
            130
        } catch (failure: Exception) {
            System.err.println("skinnerbox: ${failure.message ?: failure::class.simpleName}")
            1
        } finally {
            finished.complete(Unit)
            if (serving) try { Runtime.getRuntime().removeShutdownHook(shutdown) } catch (_: IllegalStateException) { /* shutdown is running */ }
        }
        if (status != 0) exitProcess(status)
    }
}
