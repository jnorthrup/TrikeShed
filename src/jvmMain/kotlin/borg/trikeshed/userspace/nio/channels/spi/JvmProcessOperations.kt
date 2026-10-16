package borg.trikeshed.userspace.nio.channels.spi

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
                    clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()); putAll(env)
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
<<<<<<< HEAD
<<<<<<< ef9064288c7c987cfc672a3776a17427ea70b918
        // Sentinel Security Guard: Filter caller environment to prevent leaking or injecting secret-shaped keys (Disposition.BLOCKED)
        val safeEnv = env.filterKeys {
            borg.trikeshed.graal.subvm.GuestEnvironment.deliberate(it) != borg.trikeshed.graal.subvm.GuestEnvironment.Disposition.BLOCKED
        }
        pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()); putAll(safeEnv) }
||||||| a39a8f9cb
        pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()); putAll(env) }
=======
        val filteredEnv = env.filter { (k, _) -> borg.trikeshed.graal.subvm.GuestEnvironment.deliberate(k) != borg.trikeshed.graal.subvm.GuestEnvironment.Disposition.BLOCKED }
        pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()); putAll(filteredEnv) }
>>>>>>> fcd790e4bc4f302a77f384b40929a0b214ba6039
||||||| a39a8f9cb
        pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()); putAll(env) }
=======
        pb.environment().apply {
            clear()
            putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated())
            env.forEach { (k, v) -> if (borg.trikeshed.graal.subvm.GuestEnvironment.deliberate(k) != borg.trikeshed.graal.subvm.GuestEnvironment.Disposition.BLOCKED) put(k, v) }
        }
>>>>>>> origin/jules-15618541591352001693-58b4bcf2

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
