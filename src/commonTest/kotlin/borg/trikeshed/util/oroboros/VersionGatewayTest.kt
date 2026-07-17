package borg.trikeshed.util.oroboros

import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class DummyProcessOps : ProcessOperations {
    val commands = mutableListOf<List<String>>()
    var mockGitRev = "abc1234"
    var mockPijulRev = "hashxyz"
    var pijulAvailable = true

    override suspend fun exec(command: String, args: List<String>, stdin: ByteArray?, env: Map<String, String>): ProcessResult {
        commands.add(listOf(command) + args)

        if (command == "git" && args.contains("--version")) {
            return ProcessResult(0, "git version 2.30.0".encodeToByteArray(), byteArrayOf())
        }
        if (command == "git" && args.contains("init")) {
            return ProcessResult(0, byteArrayOf(), byteArrayOf())
        }
        if (command == "git" && args.contains("add")) {
            return ProcessResult(0, byteArrayOf(), byteArrayOf())
        }
        if (command == "git" && args.contains("status")) {
            return ProcessResult(0, "M file.txt\n".encodeToByteArray(), byteArrayOf())
        }
        if (command == "git" && args.contains("commit")) {
            return ProcessResult(0, byteArrayOf(), byteArrayOf())
        }
        if (command == "git" && args.contains("rev-parse")) {
            return ProcessResult(0, mockGitRev.encodeToByteArray(), byteArrayOf())
        }

        if (command == "pijul" && args.contains("--version")) {
            return if (pijulAvailable) ProcessResult(0, "pijul 1.0.0".encodeToByteArray(), byteArrayOf()) else ProcessResult(1, byteArrayOf(), byteArrayOf())
        }
        if (command == "pijul" && args.contains("init")) {
            return ProcessResult(0, byteArrayOf(), byteArrayOf())
        }
        if (command == "pijul" && args.contains("add")) {
            return ProcessResult(0, byteArrayOf(), byteArrayOf())
        }
        if (command == "pijul" && args.contains("record")) {
            return ProcessResult(0, byteArrayOf(), byteArrayOf())
        }
        if (command == "pijul" && args.contains("log")) {
            return ProcessResult(0, "$mockPijulRev\n".encodeToByteArray(), byteArrayOf())
        }

        return ProcessResult(1, byteArrayOf(), byteArrayOf())
    }
}

class VersionGatewayTest {
    @Test
    fun testGitGateway() = runTest {
        val ops = DummyProcessOps()
        val git = GitVersionGateway(ops)

        assertTrue(git.isAvailable())
        assertTrue(git.init("/home/agent1"))

        val rev = git.record("/home/agent1", "Agent <a@b.com>", "test commit")
        assertEquals("abc1234", rev)

        val expectedCommands = listOf(
            listOf("git", "--version"),
            listOf("git", "-C", "/home/agent1", "init"),
            listOf("git", "-C", "/home/agent1", "add", "."),
            listOf("git", "-C", "/home/agent1", "status", "--porcelain"),
            listOf("git", "-c", "user.name=Agent", "-c", "user.email=a@b.com", "-C", "/home/agent1", "commit", "--author=Agent <a@b.com>", "-m", "test commit"),
            listOf("git", "-C", "/home/agent1", "rev-parse", "HEAD")
        )

        assertEquals(expectedCommands, ops.commands)
    }

    @Test
    fun testPijulGateway() = runTest {
        val ops = DummyProcessOps()
        val pijul = PijulVersionGateway(ops)

        assertTrue(pijul.isAvailable())
        assertTrue(pijul.init("/home/agent1"))

        val rev = pijul.record("/home/agent1", "Agent", "test patch")
        assertEquals("hashxyz", rev)

        ops.pijulAvailable = false
        assertFalse(pijul.isAvailable())
    }
}
