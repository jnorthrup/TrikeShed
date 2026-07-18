package borg.trikeshed.util.oroboros

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult

class FakeProcessOperations : ProcessOperations {
    val executedCommands = mutableListOf<List<String>>()

    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>
    ): ProcessResult {
        executedCommands.add(listOf(command) + args)
        return ProcessResult(0, ByteArray(0), ByteArray(0))
    }
}

class VersionGatewayTest {
    @Test
    fun testGitInitAndRecord() = runTest {
        val processOps = FakeProcessOperations()
        val gateway = GitVersionGateway(processOps)

        val initSuccess = gateway.init("/test/dir")
        assertTrue(initSuccess)
        assertEquals(listOf("git", "-C", "/test/dir", "init"), processOps.executedCommands[0])

        val recordSuccess = gateway.record("/test/dir", "oroboros", "test commit")
        assertEquals("0", recordSuccess)
        assertEquals(listOf("git", "-C", "/test/dir", "add", "."), processOps.executedCommands[1])
        assertEquals(
            listOf(
                "git", "-c", "user.name=oroboros", "-c", "user.email=agent@trikeshed.local",
                "-C", "/test/dir", "commit", "--author=oroboros", "-m", "test commit"
            ),
            processOps.executedCommands[2]
        )
    }

    @Test
    fun testPijulInitAndRecord() = runTest {
        val processOps = FakeProcessOperations()
        val gateway = PijulVersionGateway(processOps)

        val initSuccess = gateway.init("/test/dir")
        assertTrue(initSuccess)
        assertEquals(listOf("pijul", "init", "--repository", "/test/dir"), processOps.executedCommands[0])

        val recordSuccess = gateway.record("/test/dir", "oroboros", "test record")
        assertEquals("0", recordSuccess)
        assertEquals(listOf("pijul", "add", "--repository", "/test/dir", "."), processOps.executedCommands[1])
        assertEquals(listOf("pijul", "record", "--repository", "/test/dir", "-a", "-m", "test record"), processOps.executedCommands[2])
    }
}
