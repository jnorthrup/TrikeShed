package borg.trikeshed.userspace.nio.process

import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PosixProcessOperationsTest {
    @Test
    fun testSpawnEcho() = runTest {
        val ops = PosixProcessOperations()
        val result = ops.exec("echo", listOf("hello"))
        assertEquals(0, result.exitCode, "Process should exit successfully")
        val stdoutStr = result.stdout.decodeToString()
        assertTrue(stdoutStr.contains("hello"), "Output should contain 'hello'")
    }
}
