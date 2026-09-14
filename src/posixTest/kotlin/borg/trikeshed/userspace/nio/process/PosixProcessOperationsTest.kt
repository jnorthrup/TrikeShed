package borg.trikeshed.userspace.nio.process

import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PosixProcessOperationsTest {
    @Test
    fun capturePreservesInputEnvironmentAndExitStatus() = runTest {
        val input = byteArrayOf(0, 1, 127, -1, 10)
        val override = " value = retained "
        val inheritedPath = SystemOperations.default.getenv("PATH")
        assertTrue(!inheritedPath.isNullOrEmpty())
        val result = PosixProcessOperations().exec(
            "sh",
            listOf("-c", "cat; printf '%s' \"\$TRIKESHED_PROCESS_TEST\"; printf '%s' \"\$PATH\" >&2; exit 7"),
            stdin = input,
            env = mapOf("TRIKESHED_PROCESS_TEST" to override),
        )
        assertEquals(7, result.exitCode)
        assertContentEquals(input + override.encodeToByteArray(), result.stdout)
        assertEquals(inheritedPath, result.stderr.decodeToString())
    }

    @Test
    fun testSpawnEcho() = runTest {
        val ops = PosixProcessOperations()
        val result = ops.exec("echo", listOf("hello"))
        assertEquals(0, result.exitCode, "Process should exit successfully")
        val stdoutStr = result.stdout.decodeToString()
        assertTrue(stdoutStr.contains("hello"), "Output should contain 'hello'")
    }
}
