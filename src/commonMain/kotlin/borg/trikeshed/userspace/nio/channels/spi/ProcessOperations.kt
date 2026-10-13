package borg.trikeshed.userspace.nio.channels.spi

import kotlin.coroutines.CoroutineContext

/**
 * Exec suspend for process launching + completion polling.
 *
 * This is the sole OS-variant surface left after converting the rest of
 * TrikeShed's userspace IO to the io_uring-kernel-compatible SPI model.
 *
 * Each platform maps this to the appropriate child-process mechanism:
 *   - JVM: java.lang.ProcessBuilder
 *   - JS/Node.js: child_process.execSync / spawn
 *   - POSIX/Linux: posix_spawnp + waitpid
 *   - WASM: exit 1 (unsupported)
 */
interface ProcessOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ProcessOperations>
    override val key: CoroutineContext.Key<*> get() = Key

    /**
     * Execute a command and capture its output.
     *
     * @param command The executable name.
     * @param args    Command-line arguments (optional).
     * @param stdin   Bytes to feed to stdin (optional).
     * @param env     Additional environment variables (optional).
     * @return [ProcessResult] with exit code, stdout, stderr.
     */
    suspend fun exec(
        command: String,
        args: List<String> = emptyList(),
        stdin: ByteArray? = null,
        env: Map<String, String> = emptyMap(),
    ): ProcessResult

    /** Open owned pipes for a persistent worker. The caller must drain, await and close them. */
    suspend fun open(
        command: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ): ProcessPipes = TODO("Persistent process pipes are not bound on this target")
}

enum class ProcessOutput { Stdout, Stderr }

/** OS effects only; admission, framing and queue ownership belong to commonMain. */
interface ProcessPipes {
    suspend fun write(bytes: ByteArray)
    /** Number of bytes read, or -1 at EOF. Exactly one reader per output. */
    suspend fun read(output: ProcessOutput, bytes: ByteArray): Int
    suspend fun closeInput()
    suspend fun awaitExit(): Int
    /** Release every pipe and reap the child; terminate only if it has not exited. */
    suspend fun close()
}

data class ProcessResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
)
