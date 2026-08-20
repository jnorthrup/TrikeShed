package borg.trikeshed.userspace.nio.channels.spi

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.async

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
        env.forEach { (k, v) -> pb.environment()[k] = v }

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.coroutineScope {
            val proc = pb.start()

            // Feed stdin if provided
            proc.outputStream.use {
                if (stdin != null) {
                    it.write(stdin)
                    it.flush()
                }
            }

            // Read stdout asynchronously to prevent deadlocks
            val stdoutDeferred = this.async {
                val stdoutOut = ByteArrayOutputStream()
                proc.inputStream.use { it.copyTo(stdoutOut) }
                stdoutOut.toByteArray()
            }

            // Read stderr asynchronously
            val stderrDeferred = this.async {
                val stderrOut = ByteArrayOutputStream()
                if (!pb.redirectErrorStream()) {
                    proc.errorStream.use { it.copyTo(stderrOut) }
                }
                stderrOut.toByteArray()
            }

            val finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                throw RuntimeException("process timed out after 30 seconds")
            }
            val exitCode = proc.exitValue()
            ProcessResult(exitCode, stdoutDeferred.await(), stderrDeferred.await())
        } }
    }
}
