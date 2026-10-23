package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

enum class BtrfsProgsOracleSource {
    CONFIGURED,
    PATH,
    ABSENT,
}

enum class BtrfsProgsOracleOutcome {
    PASSED,
    ABSENT,
    INVALID_EXECUTABLE,
    REJECTED,
    TIMED_OUT,
    EXECUTION_FAILED,
}

data class BtrfsProgsOracleReceipt(
    val imagePath: String,
    val source: BtrfsProgsOracleSource,
    val btrfsPath: String?,
    val version: String?,
    val command: List<String>,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val outcome: BtrfsProgsOracleOutcome,
    val failure: String?,
) {
    val checkPassed: Boolean get() = outcome == BtrfsProgsOracleOutcome.PASSED
}

object BtrfsProgsOracle {
    const val DEFAULT_TIMEOUT_MILLIS: Long = 60_000
    private val processOps = JvmProcessOperations()

    fun locateBtrfs(env: Map<String, String> = System.getenv()): Pair<BtrfsProgsOracleSource, String?> {
        configuredCandidates(env).takeIf { it.isNotEmpty() }?.let { candidates ->
            val selected = candidates.firstOrNull { it.isFile && it.canExecute() } ?: candidates.first()
            return BtrfsProgsOracleSource.CONFIGURED to selected.absolutePath
        }
        pathCandidates(env).firstOrNull { it.isFile && it.canExecute() }?.let {
            return BtrfsProgsOracleSource.PATH to it.absolutePath
        }
        return BtrfsProgsOracleSource.ABSENT to null
    }

    suspend fun checkReadOnly(
        imagePath: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        checkDataChecksums: Boolean = true,
    ): BtrfsProgsOracleReceipt {
        val (source, btrfsPath) = locateBtrfs()
        return checkReadOnly(imagePath, btrfsPath, source, timeoutMillis, checkDataChecksums)
    }

    suspend fun checkReadOnly(
        imagePath: String,
        btrfsPath: String?,
        source: BtrfsProgsOracleSource = if (btrfsPath == null) BtrfsProgsOracleSource.ABSENT else BtrfsProgsOracleSource.PATH,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        checkDataChecksums: Boolean = true,
        operations: ProcessOperations = processOps,
    ): BtrfsProgsOracleReceipt {
        require(timeoutMillis > 0) { "Oracle timeout must be positive" }
        currentCoroutineContext().ensureActive()
        val command = listOf(btrfsPath ?: "btrfs", "check", "--readonly") +
            (if (checkDataChecksums) listOf("--check-data-csum") else emptyList()) + imagePath
        var version: String? = null
        fun receipt(outcome: BtrfsProgsOracleOutcome, result: ProcessResult? = null, failure: String? = null) =
            BtrfsProgsOracleReceipt(
                imagePath, if (btrfsPath == null) BtrfsProgsOracleSource.ABSENT else source,
                btrfsPath, version, command, result?.exitCode,
                result?.stdout?.decodeToString().orEmpty(), result?.stderr?.decodeToString().orEmpty(),
                outcome, failure,
            )
        if (btrfsPath == null) {
            return receipt(BtrfsProgsOracleOutcome.ABSENT,
                failure = "btrfs-progs is not configured or discoverable on PATH")
        }
        if (!File(btrfsPath).let { it.isFile && it.canExecute() }) {
            return receipt(BtrfsProgsOracleOutcome.INVALID_EXECUTABLE,
                failure = "btrfs-progs path is not an executable file: $btrfsPath")
        }
        return try {
            withTimeoutOrNull(timeoutMillis) {
                val reported = operations.exec(btrfsPath, listOf("--version"))
                if (reported.exitCode != 0) {
                    return@withTimeoutOrNull receipt(BtrfsProgsOracleOutcome.EXECUTION_FAILED,
                        failure = "btrfs --version exited ${reported.exitCode}: ${reported.stderr.decodeToString().trim()}")
                }
                version = reported.stdout.decodeToString().ifBlank { reported.stderr.decodeToString() }
                    .lineSequence().firstOrNull()?.trim()?.ifBlank { null }
                val result = operations.exec(btrfsPath, command.drop(1))
                if (result.exitCode == 0) receipt(BtrfsProgsOracleOutcome.PASSED, result)
                else receipt(BtrfsProgsOracleOutcome.REJECTED, result,
                    "btrfs check exited ${result.exitCode}")
            } ?: receipt(BtrfsProgsOracleOutcome.TIMED_OUT,
                failure = "btrfs oracle exceeded ${timeoutMillis}ms")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            receipt(BtrfsProgsOracleOutcome.EXECUTION_FAILED, failure = failure.message ?: failure.toString())
        }
    }

    private fun configuredCandidates(env: Map<String, String>): List<File> {
        val root = listOfNotNull(env["TRIKESHED_BTRFS_PROGS"], env["BTRFS_PROGS"]).firstOrNull { it.isNotBlank() }
            ?: return emptyList()
        return root.let { value ->
            val file = File(value)
            if (file.isFile || file.name == "btrfs") listOf(file)
            else listOf(File(file, "btrfs"), File(file, "bin/btrfs"))
        }
    }

    private fun pathCandidates(env: Map<String, String>): List<File> =
        env["PATH"].orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it, "btrfs") }

}
