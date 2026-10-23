package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BtrfsProgsOracleTest {

    @Test
    fun missing_btrfs_progs_is_reported_as_oracle_blocker() = runTest {
        val path = Files.createTempFile("trikeshed-btrfs-oracle-", ".img")
        try {
            val receipt = BtrfsProgsOracle.checkReadOnly(path.toString(), btrfsPath = null)
            assertEquals(BtrfsProgsOracleSource.ABSENT, receipt.source)
            assertNull(receipt.btrfsPath)
            assertNull(receipt.exitCode)
            assertFalse(receipt.checkPassed)
            assertEquals(BtrfsProgsOracleOutcome.ABSENT, receipt.outcome)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun invalid_configuration_does_not_fall_back_to_an_unrelated_path_binary() = runTest {
        val directory = Files.createTempDirectory("trikeshed-btrfs-config-").toFile()
        try {
            val candidate = File(directory, "btrfs").apply { writeBytes(byteArrayOf()); setExecutable(true) }
            val missing = File(directory, "missing/btrfs")
            val (source, path) = BtrfsProgsOracle.locateBtrfs(mapOf(
                "TRIKESHED_BTRFS_PROGS" to missing.path,
                "PATH" to candidate.parent,
            ))
            assertEquals(BtrfsProgsOracleSource.CONFIGURED, source)
            assertEquals(missing.absolutePath, path)
            val receipt = BtrfsProgsOracle.checkReadOnly("image", path, source)
            assertEquals(BtrfsProgsOracleOutcome.INVALID_EXECUTABLE, receipt.outcome)
            assertFalse(receipt.checkPassed)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejected_image_preserves_exit_and_both_output_streams() = runTest {
        val commands = mutableListOf<List<String>>()
        val receipt = BtrfsProgsOracle.checkReadOnly("a volume.img", executable, operations = operations { args ->
            commands += args
            if (args == listOf("--version")) result(0, "btrfs-progs v6.12\n")
            else result(1, "Checking filesystem\n", "checksum verify failed\n")
        })
        assertEquals(listOf(listOf("--version"), listOf("check", "--readonly", "--check-data-csum", "a volume.img")), commands)
        assertEquals(BtrfsProgsOracleOutcome.REJECTED, receipt.outcome)
        assertEquals("btrfs-progs v6.12", receipt.version)
        assertEquals(1, receipt.exitCode)
        assertEquals("Checking filesystem\n", receipt.stdout)
        assertEquals("checksum verify failed\n", receipt.stderr)
        assertEquals("btrfs check exited 1", receipt.failure)
        assertFalse(receipt.checkPassed)
    }

    @Test
    fun deadline_cancels_the_owned_command_and_returns_timeout() = runTest {
        var closed = false
        val receipt = BtrfsProgsOracle.checkReadOnly("image", executable, timeoutMillis = 25,
            operations = operations { args ->
                if (args == listOf("--version")) result(0, "btrfs-progs v6.12")
                else try { awaitCancellation() } finally { closed = true }
            })
        assertTrue(closed)
        assertEquals(BtrfsProgsOracleOutcome.TIMED_OUT, receipt.outcome)
        assertNull(receipt.exitCode)
        assertFalse(receipt.checkPassed)
    }

    @Test
    fun caller_cancellation_propagates_after_command_cleanup() = runTest {
        var closed = false
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(25) {
                BtrfsProgsOracle.checkReadOnly("image", executable, timeoutMillis = 100,
                    operations = operations { args ->
                        if (args == listOf("--version")) result(0, "btrfs-progs v6.12")
                        else try { awaitCancellation() } finally { closed = true }
                    })
            }
        }
        assertTrue(closed)
    }

    @Test
    fun launch_failure_is_distinct_from_a_rejected_image() = runTest {
        val receipt = BtrfsProgsOracle.checkReadOnly("image", executable,
            operations = operations { throw IOException("cannot execute binary") })
        assertEquals(BtrfsProgsOracleOutcome.EXECUTION_FAILED, receipt.outcome)
        assertEquals("cannot execute binary", receipt.failure)
        assertNull(receipt.exitCode)
    }

    @Test
    fun failed_version_probe_does_not_run_the_checker() = runTest {
        var calls = 0
        val receipt = BtrfsProgsOracle.checkReadOnly("image", executable, operations = operations {
            calls++
            result(2, stderr = "cannot load runtime")
        })
        assertEquals(1, calls)
        assertEquals(BtrfsProgsOracleOutcome.EXECUTION_FAILED, receipt.outcome)
        assertTrue(receipt.failure!!.contains("cannot load runtime"))
    }

    private val executable: String get() = File(System.getProperty("java.home"), "bin/java").absolutePath

    private fun result(code: Int, stdout: String = "", stderr: String = "") =
        ProcessResult(code, stdout.encodeToByteArray(), stderr.encodeToByteArray())

    private fun operations(run: suspend (List<String>) -> ProcessResult) = object : ProcessOperations {
        override suspend fun exec(command: String, args: List<String>, stdin: ByteArray?, env: Map<String, String>) = run(args)
    }
}
