package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

/** JVM/macOS launcher; storage and command execution remain in commonMain. */
object BtrfsCli {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val result = try {
            runBlocking {
                val nio = NioSupervisor()
                nio.open()
                try {
                    val files = requireNotNull(nio.service<FileOperations>()) { "No filesystem provider" }
                    withContext(nio + files) {
                        if (arguments.firstOrNull() == "control") {
                            require(arguments.size >= 4) { "control 127.0.0.1:PORT TOKEN info|add|balance|compression [OPERANDS]" }
                            println(BtrfsMountControl.request(arguments[1], arguments[2], arguments.drop(3)))
                        } else {
                            println(BtrfsTool.run(this, files, arguments.toList()) { fs, path, library ->
                                BtrfsMacFuseMount.requireHost()
                                val root = withContext(fileIoContext) { files.canonicalPath(arguments[1]) }
                                val random = ByteArray(32).also(SecureRandom()::nextBytes)
                                val token = random.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                                val control = BtrfsMountControl.open(this, token) { command ->
                                    BtrfsTool.runMounted(this, files, root, fs, command)
                                }
                                try {
                                    System.err.println("Live volume control: control ${control.address} $token info")
                                    BtrfsMacFuseMount.serve(fs, path, library) {
                                        control.drain()
                                        fs.drain()
                                    }
                                } finally { withContext(NonCancellable) { control.drain() } }
                            })
                        }
                    }
                } finally { withContext(NonCancellable) { nio.drain() } }
            }
            0
        } catch (cancelled: CancellationException) {
            System.err.println("btrfs: cancelled")
            130
        } catch (failure: Exception) {
            System.err.println("btrfs: ${failure.message ?: failure::class.simpleName}")
            1
        }
        if (result != 0) exitProcess(result)
    }
}
