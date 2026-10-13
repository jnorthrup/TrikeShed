package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.graal.subvm.GuestEnvironment
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class JvmProcessOperations : ProcessOperations {

    override suspend fun open(command: String, args: List<String>, env: Map<String, String>): ProcessPipes {
        validateCommand(listOf(command) + args)
        var process: Process? = null
        try {
            return withContext(Dispatchers.IO) {
                val builder = ProcessBuilder(command, *args.toTypedArray())
                builder.environment().apply {
                    clear()
                    putAll(GuestEnvironment.curated())
                    putAll(env.filterKeys { GuestEnvironment.deliberate(it) != GuestEnvironment.Disposition.BLOCKED })
                }
                JvmProcessPipes(builder.start().also { process = it })
            }
        } catch (failure: Throwable) {
            // withContext can reject delivery after start() succeeds. It still owns that child.
            process?.let { JvmProcessPipes(it).close() }
            throw failure
        }
    }

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
        pb.environment().apply {
            clear()
            putAll(GuestEnvironment.curated())
            putAll(env.filterKeys { GuestEnvironment.deliberate(it) != GuestEnvironment.Disposition.BLOCKED })
        }

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

class JvmProcessPipes(val process: Process) : ProcessPipes {
    /** A cancelled native pipe operation must unblock before its coroutine can join. */
    private suspend fun <T> io(block: () -> T): T = coroutineScope {
        val operation = async(Dispatchers.IO) { block() }
        try { operation.await() } catch (cancelled: CancellationException) {
            process.destroyForcibly()
            throw cancelled
        }
    }

    override suspend fun write(bytes: ByteArray) = io {
        process.outputStream.write(bytes)
        process.outputStream.flush()
    }

    override suspend fun read(output: ProcessOutput, bytes: ByteArray): Int {
        require(bytes.isNotEmpty())
        return io { when (output) {
            ProcessOutput.Stdout -> process.inputStream.read(bytes)
            ProcessOutput.Stderr -> process.errorStream.read(bytes)
        } }
    }

    override suspend fun closeInput() = io { process.outputStream.close() }
    override suspend fun awaitExit(): Int = io { process.waitFor() }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        if (process.isAlive) process.destroyForcibly()
        process.waitFor()
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        Unit
    }
}
