@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.posix.trikeshed_spawn
import borg.trikeshed.userspace.nio.posix.trikeshed_process_open
import borg.trikeshed.userspace.nio.posix.trikeshed_process_send
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.*

class PosixProcessOperations : ProcessOperations {

    override suspend fun open(command: String, args: List<String>, env: Map<String, String>): ProcessPipes {
        require(command.isNotEmpty() && '\u0000' !in command)
        require(args.all { '\u0000' !in it })
        require(env.all { (key, value) -> key.isNotEmpty() && '=' !in key && '\u0000' !in key && '\u0000' !in value })
        var process: PosixProcessPipes? = null
        try {
            return withContext(Dispatchers.IO) {
                memScoped {
                    val pid = alloc<pid_tVar>()
                    val input = alloc<IntVar>()
                    val output = alloc<IntVar>()
                    val error = alloc<IntVar>()
                    val argv = allocArray<CPointerVar<ByteVar>>(args.size + 2)
                    var index = 0
                    argv[index++] = command.cstr.ptr
                    args.forEach { argv[index++] = it.cstr.ptr }
                    argv[index] = null
                    val environment = ProcessEnvironment.curated() + env.filterKeys { !ProcessEnvironment.blocked(it) }
                    val envp = allocArray<CPointerVar<ByteVar>>(environment.size + 1)
                    index = 0
                    environment.forEach { (key, value) -> envp[index++] = "$key=$value".cstr.ptr }
                    envp[index] = null
                    val result = trikeshed_process_open(pid.ptr, input.ptr, output.ptr, error.ptr, argv, envp)
                    check(result == 0) { "posix_spawnp failed: $result" }
                    PosixProcessPipes(pid.value, input.value, output.value, error.value).also { process = it }
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { process?.close() }
            throw failure
        }
    }

    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val temporary = mutableListOf<String>()
        fun capture(bytes: ByteArray): String =
            checkNotNull(createTempFile(bytes)) { "mkstemp or stdin write failed" }.also(temporary::add)
        try {
            val stdinPath = stdin?.let(::capture)
            val stdoutPath = capture(byteArrayOf())
            val stderrPath = capture(byteArrayOf())
            val exitCode = memScoped {
                val pid = alloc<pid_tVar>()
                // env applies overrides to the inherited environment without shell parsing.
                val argv = allocArray<CPointerVar<ByteVar>>(env.size + args.size + 3)
                var index = 0
                argv[index++] = "env".cstr.ptr
                env.forEach { (key, value) -> argv[index++] = "$key=$value".cstr.ptr }
                argv[index++] = command.cstr.ptr
                args.forEach { argv[index++] = it.cstr.ptr }
                argv[index] = null
                val spawned = trikeshed_spawn(pid.ptr, stdinPath, stdoutPath, stderrPath, argv)
                check(spawned == 0) { "posix_spawnp failed: $spawned" }
                val status = alloc<IntVar>()
                var waited: pid_t
                do { waited = waitpid(pid.value, status.ptr, 0) }
                while (waited == -1 && errno == EINTR)
                check(waited == pid.value) { "waitpid failed: $errno" }
                if ((status.value and 0x7F) == 0) (status.value shr 8) and 0xFF else -1
            }
            ProcessResult(exitCode, borg.trikeshed.common.Files.readAllBytes(stdoutPath),
                borg.trikeshed.common.Files.readAllBytes(stderrPath))
        } finally {
            temporary.forEach { unlink(it) }
        }
    }

    private fun createTempFile(bytes: ByteArray): String? = memScoped {
            val template = "/tmp/trikeshed-process-XXXXXX".cstr.placeTo(this)
            val fd = mkstemp(template)
            if (fd < 0) {
                return@memScoped null
            }
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val written = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
                        if (written < 0 && errno == EINTR) continue
                        if (written <= 0) {
                            close(fd)
                            unlink(template.toKString())
                            return@memScoped null
                        }
                        offset += written.toInt()
                    }
                }
            }
            close(fd)
            template.toKString()
        }
}

/** Parent endpoints and child are one owned resource; all OS operations are nonblocking. */
class PosixProcessPipes(private val pid: pid_t, input: Int, output: Int, error: Int) : ProcessPipes {
    private val mutex = Mutex()
    private var inputFd = input
    private var outputFd = output
    private var errorFd = error
    private var exitCode: Int? = null

    private suspend fun <T> owned(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        close()
        throw cancelled
    }

    override suspend fun write(bytes: ByteArray): Unit = owned {
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val result = mutex.withLock {
                check(inputFd >= 0) { "Process stdin is closed" }
                val count = bytes.usePinned { trikeshed_process_send(inputFd, it.addressOf(offset), (bytes.size - offset).convert()) }
                count.toInt() j if (count < 0) errno else 0
            }
            when {
                result.a > 0 -> offset += result.a
                result.a < 0 && result.b == EINTR -> Unit
                result.a < 0 && (result.b == EAGAIN || result.b == EWOULDBLOCK) -> delay(5)
                else -> error("Process stdin write failed: ${result.b}")
            }
        }
    }

    override suspend fun read(output: ProcessOutput, bytes: ByteArray): Int = owned {
        require(bytes.isNotEmpty())
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = mutex.withLock {
                val fd = when (output) { ProcessOutput.Stdout -> outputFd; ProcessOutput.Stderr -> errorFd }
                if (fd < 0) return@withLock 0 j 0
                val count = bytes.usePinned { platform.posix.read(fd, it.addressOf(0), bytes.size.convert()) }
                count.toInt() j if (count < 0) errno else 0
            }
            when {
                result.a > 0 -> return@owned result.a
                result.a == 0 -> return@owned -1
                result.b == EINTR -> Unit
                result.b == EAGAIN || result.b == EWOULDBLOCK -> delay(5)
                else -> error("Process output read failed: ${result.b}")
            }
        }
        @Suppress("UNREACHABLE_CODE") -1
    }

    override suspend fun closeInput() = mutex.withLock {
        if (inputFd >= 0) { close(inputFd); inputFd = -1 }
    }

    // Called under mutex: WNOHANG never waits while another endpoint needs the lock.
    private fun collect(): Int? = exitCode ?: memScoped {
        val status = alloc<IntVar>()
        val result = waitpid(pid, status.ptr, WNOHANG)
        when {
            result == 0 -> null
            result == pid -> (if ((status.value and 0x7f) == 0) (status.value shr 8) and 0xff else -1)
                .also { exitCode = it }
            result == -1 && errno == EINTR -> null
            else -> error("waitpid failed: $errno")
        }
    }

    override suspend fun awaitExit(): Int = owned {
        while (true) {
            currentCoroutineContext().ensureActive()
            mutex.withLock { collect() }?.let { return@owned it }
            delay(5)
        }
        @Suppress("UNREACHABLE_CODE") -1
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        mutex.withLock {
            if (collect() == null) kill(pid, SIGKILL)
            if (inputFd >= 0) { close(inputFd); inputFd = -1 }
            if (outputFd >= 0) { close(outputFd); outputFd = -1 }
            if (errorFd >= 0) { close(errorFd); errorFd = -1 }
        }
        while (mutex.withLock { collect() } == null) delay(5)
    }
}
