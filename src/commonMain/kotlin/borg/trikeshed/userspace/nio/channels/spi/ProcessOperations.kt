package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer
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
        command: CharSequence,
        args: List<CharSequence> = emptyList(),
        stdin: ByteArray? = null,
        env: Map<CharSequence, CharSequence> = emptyMap(),
    ): ProcessResult
}

data class ProcessResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
)
