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

/** One duplex protocol endpoint; closing it does not own or terminate a process. */
interface ProcessProtocolStream {
    suspend fun readProtocol(bytes: ByteArray): Int
    suspend fun writeProtocol(bytes: ByteArray)
    suspend fun closeProtocolInput()
    suspend fun closeProtocol()
}

/** A multiplexed worker may keep its protocol separate from physical terminal pipes. */
interface ProcessProtocolPipes : ProcessPipes, ProcessProtocolStream {
    val processId: Long
    val isAlive: Boolean
    val separateProtocol: Boolean
    val terminalOutputs: Set<ProcessOutput>
    /** Explicit revocation, never the normal drain path. Zero grace means immediate revocation. */
    suspend fun revoke(graceMillis: Long)
}

/** Platform bridge for a synchronous caller; all protocol scheduling remains in common userspace. */
interface ProcessProtocolBinding {
    val context: CoroutineContext
    val pipes: ProcessProtocolPipes
    fun <T> await(operation: suspend () -> T): T
    suspend fun <T> invoke(operation: () -> T): T
}

data class ProcessResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
)
