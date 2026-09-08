package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class BtrfsProgsOracleSource {
    CONFIGURED,
    PATH,
    ABSENT,
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
    val checkPassed: Boolean,
    val failure: String?,
)

object BtrfsProgsOracle {
    private val processOps = JvmProcessOperations()

    fun locateBtrfs(env: Map<String, String> = System.getenv()): Pair<BtrfsProgsOracleSource, String?> {
        configuredCandidates(env).firstOrNull { it.canExecute() }?.let {
            return BtrfsProgsOracleSource.CONFIGURED to it.absolutePath
        }
        pathCandidates(env).firstOrNull { it.canExecute() }?.let {
            return BtrfsProgsOracleSource.PATH to it.absolutePath
        }
        return BtrfsProgsOracleSource.ABSENT to null
    }

    suspend fun checkReadOnly(imagePath: String): BtrfsProgsOracleReceipt {
        val (source, btrfsPath) = locateBtrfs()
        return checkReadOnly(imagePath, btrfsPath, source)
    }

    suspend fun checkReadOnly(
        imagePath: String,
        btrfsPath: String?,
        source: BtrfsProgsOracleSource = if (btrfsPath == null) BtrfsProgsOracleSource.ABSENT else BtrfsProgsOracleSource.PATH,
    ): BtrfsProgsOracleReceipt {
        if (btrfsPath == null) {
            return BtrfsProgsOracleReceipt(
                imagePath = imagePath,
                source = BtrfsProgsOracleSource.ABSENT,
                btrfsPath = null,
                version = null,
                command = listOf("btrfs", "check", "--readonly", imagePath),
                exitCode = null,
                stdout = "",
                stderr = "",
                checkPassed = false,
                failure = "btrfs-progs is not configured or discoverable on PATH",
            )
        }

        val command = listOf(btrfsPath, "check", "--readonly", imagePath)
        var version: String? = null
        return try {
            version = runCatching { runCommand(listOf(btrfsPath, "--version")) }.getOrNull()?.let {
                (it.stdout.ifBlank { it.stderr }).lineSequence().firstOrNull()?.trim().orEmpty()
            }?.ifBlank { null }
            val result = runCommand(command)
            BtrfsProgsOracleReceipt(
                imagePath = imagePath,
                source = source,
                btrfsPath = btrfsPath,
                version = version,
                command = command,
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                checkPassed = result.exitCode == 0,
                failure = null,
            )
        } catch (failure: Throwable) {
            BtrfsProgsOracleReceipt(
                imagePath = imagePath,
                source = source,
                btrfsPath = btrfsPath,
                version = version,
                command = command,
                exitCode = null,
                stdout = "",
                stderr = "",
                checkPassed = false,
                failure = failure.message ?: failure.toString(),
            )
        }
    }

    private fun configuredCandidates(env: Map<String, String>): List<File> {
        val roots = listOfNotNull(env["TRIKESHED_BTRFS_PROGS"], env["BTRFS_PROGS"])
        return roots.flatMap { value ->
            val file = File(value)
            if (file.name == "btrfs") listOf(file)
            else listOf(File(file, "btrfs"), File(file, "bin/btrfs"))
        }
    }

    private fun pathCandidates(env: Map<String, String>): List<File> =
        env["PATH"].orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it, "btrfs") }

    private suspend fun runCommand(command: List<String>): CommandResult = withContext(Dispatchers.IO) {
        val result = processOps.exec(command.first(), command.drop(1))
        CommandResult(
            exitCode = result.exitCode,
            stdout = result.stdout.decodeToString(),
            stderr = result.stderr.decodeToString(),
        )
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
