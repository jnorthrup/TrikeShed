package borg.trikeshed.userspace.nio.channels.spi

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible

class JvmProcessOperations : ProcessOperations {

    private fun validateCommand(command: List<String>) {
        require(command.isNotEmpty()) { "Command list must not be empty" }
        command.forEachIndexed { index, arg ->
            require(arg.isNotBlank()) { "Command argument at index $index must not be blank" }
        }
    }

    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): ProcessResult {
        validateCommand(listOf(command) + args)
        val pb = ProcessBuilder(command, *args.toTypedArray())
        pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()); putAll(env) }

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.coroutineScope {
            val proc = pb.start()

            try {
                // All pipes progress together; writing stdin first can block on a full stdout pipe.
                val stdoutDeferred = this.async {
                    val stdoutOut = ByteArrayOutputStream()
                    proc.inputStream.use { it.copyTo(stdoutOut) }
                    stdoutOut.toByteArray()
                }

                val stderrDeferred = this.async {
                    val stderrOut = ByteArrayOutputStream()
                    if (!pb.redirectErrorStream()) {
                        proc.errorStream.use { it.copyTo(stderrOut) }
                    }
                    stderrOut.toByteArray()
                }

                val stdinDeferred = this.async {
                    proc.outputStream.use { stream ->
                        if (stdin != null) stream.write(stdin)
                    }
                }

                val finished = runInterruptible { proc.waitFor(1, java.util.concurrent.TimeUnit.HOURS) }
                if (!finished) throw java.util.concurrent.TimeoutException("Process timed out: $command")
                val exitCode = proc.exitValue()
                stdinDeferred.await()
                ProcessResult(exitCode, stdoutDeferred.await(), stderrDeferred.await())
            } finally {
                if (proc.isAlive) proc.destroyForcibly()
                runCatching { proc.outputStream.close() }
                runCatching { proc.inputStream.close() }
                runCatching { proc.errorStream.close() }
            }
        } }
    }
}
