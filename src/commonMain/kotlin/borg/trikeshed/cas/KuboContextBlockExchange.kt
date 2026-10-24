package borg.trikeshed.cas

import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessOutput
import borg.trikeshed.userspace.nio.channels.spi.ProcessPipes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * A Kubo node named explicitly. Nothing is discovered: no default repository, no ambient environment and no
 * running daemon is assumed.
 *
 * [repo] is always handed to the child as `IPFS_PATH`, so a command can never fall back to a default repository such
 * as `~/.ipfs`. Without [api] the command runs standalone against [repo] (`--offline`): it sees only the blocks that
 * repository already holds, and fails if a daemon has it locked. With [api] the command is a client of that daemon,
 * and fetching from the network is the daemon's business. A non-loopback API address is refused unless the operator
 * approves it, because the RPC API is unauthenticated.
 */
class KuboNode(val repo: String, val api: String? = null, val remoteApiApproved: Boolean = false) {
    init {
        require(repo.startsWith("/") && repo.none { it == '\u0000' || it == '\n' || it == '\r' }) { "repo must be an absolute path" }
        if (api != null) {
            require(MULTIADDR.matches(api)) { "api must be a TCP multiaddr such as /ip4/127.0.0.1/tcp/5001" }
            require(remoteApiApproved || isLoopback(api)) { "a non-loopback API needs remoteApiApproved = true" }
        }
    }

    private companion object {
        val MULTIADDR = Regex("^/(ip4|ip6|dns4|dns6|dns)/[A-Za-z0-9._:-]+/tcp/[0-9]{1,5}$")

        fun isLoopback(multiaddr: String): Boolean {
            val parts = multiaddr.split('/')
            val host = parts.getOrNull(2) ?: return false
            return when (parts.getOrNull(1)) {
                "ip4" -> host.startsWith("127.")
                "ip6" -> host == "::1"
                else -> host == "localhost"
            }
        }
    }
}

/** The node ran but did not do what a block exchange needs. [stderr] is the node's own bounded diagnostic. */
class KuboCommandFailed(val command: String, val exitCode: Int, val stderr: String) :
    IllegalStateException("ipfs $command exited $exitCode: ${stderr.take(300)}")

/** The node named a different block than the one we computed, or did not name the block we asked it to retain. */
class KuboIdentityMismatch(val expected: IpnsCid, val reported: String) :
    IllegalStateException("expected $expected but the node reported '${reported.take(120)}'")

/**
 * Block exchange through the installed Kubo CLI, as argument vectors with no shell, through userspace [ProcessOperations].
 *
 * Reads are bounded while streaming: the child is stopped one byte past the caller's ceiling, so a hostile or huge block
 * costs at most the ceiling in memory. Identity is never taken from the node: [publish] compares what the node reports
 * with the raw-codec CID computed locally from the same bytes. Whether [fetch]ed bytes match the CID is for the caller
 * to verify, as for every [ContextBlockExchange].
 */
class KuboContextBlockExchange(
    private val process: ProcessOperations,
    private val node: KuboNode,
    private val binary: String = "ipfs",
    private val timeoutSeconds: Int = 30,
) : ContextBlockExchange {
    companion object {
        /** Blocks above this cannot be transferred over the standard bitswap, so none is ever read or written here. */
        const val MAX_BLOCK_BYTES = 2_097_152
        private const val DIAGNOSTIC_BYTES = 2_048
        private const val CHUNK = 8_192
    }

    init {
        require(binary.isNotBlank()) { "binary must name the ipfs executable" }
        require(timeoutSeconds in 1..3_600) { "timeoutSeconds must be 1..3600" }
    }

    private val env: Map<String, String> = mapOf("IPFS_PATH" to node.repo)
    private val global: List<String> = listOf(if (node.api != null) "--api=${node.api}" else "--offline", "--timeout=${timeoutSeconds}s")

    internal fun getArgs(cid: IpnsCid): List<String> = global + listOf("block", "get", "--", cid.toString())
    internal fun putArgs(): List<String> = global + listOf("block", "put", "--cid-codec=raw", "--mhtype=sha2-256")
    internal fun pinArgs(cid: IpnsCid): List<String> = global + listOf("pin", "add", "--recursive=false", "--", cid.toString())

    override suspend fun fetch(cid: IpnsCid, maxBytes: Int): ByteArray {
        require(maxBytes in 1..MAX_BLOCK_BYTES) { "maxBytes must be 1..$MAX_BLOCK_BYTES" }
        val pipes = process.open(binary, getArgs(cid), env)
        try {
            return coroutineScope {
                val diagnostic = async { diagnostic(pipes) }
                val block = readUpTo(pipes, ProcessOutput.Stdout, maxBytes + 1)
                if (block.size > maxBytes) throw BlockTooLarge(maxBytes)
                val exit = pipes.awaitExit()
                if (exit != 0) throw KuboCommandFailed("block get", exit, diagnostic.await())
                block
            }
        } finally {
            // Terminates the child if it is still running (an oversize or cancelled read) and always reaps it.
            withContext(NonCancellable) { pipes.close() }
        }
    }

    override suspend fun publish(bytes: ByteArray): IpnsCid {
        require(bytes.size <= MAX_BLOCK_BYTES) { "a block is at most $MAX_BLOCK_BYTES bytes" }
        val expected = IpnsCid.raw(bytes)
        val result = process.exec(binary, putArgs(), bytes, env)
        if (result.exitCode != 0) throw KuboCommandFailed("block put", result.exitCode, boundedText(result.stderr))
        val reported = boundedText(result.stdout).trim()
        val named = try { IpnsCid.parse(reported) } catch (bad: RuntimeException) { throw KuboIdentityMismatch(expected, reported) }
        if (named != expected) throw KuboIdentityMismatch(expected, reported)
        return expected
    }

    override suspend fun pin(cid: IpnsCid) {
        val result = process.exec(binary, pinArgs(cid), null, env)
        if (result.exitCode != 0) throw KuboCommandFailed("pin add", result.exitCode, boundedText(result.stderr))
        val said = boundedText(result.stdout)
        if (cid.toString() !in said) throw KuboIdentityMismatch(cid, said.trim())
    }

    private fun boundedText(bytes: ByteArray): String = bytes.decodeToString(0, minOf(bytes.size, DIAGNOSTIC_BYTES))

    /** Reads until EOF or [limit] bytes, whichever comes first. The retained bytes never exceed [limit]. */
    private suspend fun readUpTo(pipes: ProcessPipes, output: ProcessOutput, limit: Int): ByteArray {
        var buffer = ByteArray(minOf(limit, CHUNK))
        var size = 0
        val chunk = ByteArray(CHUNK)
        while (size < limit) {
            val count = pipes.read(output, chunk)
            if (count < 0) break
            if (count == 0) { yield(); continue }
            val take = minOf(count, limit - size)
            if (size + take > buffer.size) buffer = buffer.copyOf(minOf(limit.toLong(), maxOf((size + take).toLong(), buffer.size * 2L)).toInt())
            chunk.copyInto(buffer, size, 0, take)
            size += take
        }
        return buffer.copyOf(size)
    }

    /** Keeps the head of stderr and drains the rest, so a chatty child can never block on a full pipe. */
    private suspend fun diagnostic(pipes: ProcessPipes): String {
        val kept = ByteArray(DIAGNOSTIC_BYTES)
        var size = 0
        val chunk = ByteArray(1_024)
        try {
            while (true) {
                val count = pipes.read(ProcessOutput.Stderr, chunk)
                if (count < 0) break
                if (count == 0) { yield(); continue }
                val take = minOf(count, kept.size - size)
                if (take > 0) { chunk.copyInto(kept, size, 0, take); size += take }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (closed: Throwable) {
            // The child was stopped while this was reading; what was gathered so far is the diagnostic.
        }
        return kept.decodeToString(0, size)
    }
}
