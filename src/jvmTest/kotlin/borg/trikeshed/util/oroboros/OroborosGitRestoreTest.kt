package borg.trikeshed.util.oroboros

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeProcessOpsForRestore : ProcessOperations {
    val commands = mutableListOf<List<String>>()
    var failCheckout = false
    var failFsck = false

    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>
    ): ProcessResult {
        commands.add(listOf(command) + args)
        if (command == "git" && args.contains("fsck") && failFsck) {
            return ProcessResult(1, ByteArray(0), "fsck failed".encodeToByteArray())
        }
        if (command == "git" && args.contains("checkout") && failCheckout) {
            return ProcessResult(1, ByteArray(0), "checkout failed".encodeToByteArray())
        }
        return ProcessResult(0, ByteArray(0), ByteArray(0))
    }
}

class OroborosGitRestoreTest {
    @Test
    fun testSuccessfulRestore() = runTest {
        val fileOps = JvmFileOperations()
        val tempDir = fileOps.createTempDir("oroboros-restore-test")
        val processOps = FakeProcessOpsForRestore()
        val restore = OroborosGitRestore(fileOps, processOps)

        val attachments = listOf(
            Pair(
                OroborosAttachmentRef(
                    path = "src/main.kt",
                    contentType = "text/kotlin",
                    length = 12L,
                    contentId = ContentId("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                    agentId = "agent1",
                    revision = "1234567890abcdef",
                    sequence = 1L
                ),
                "fun main() {}".encodeToByteArray()
            ),
            Pair(
                OroborosAttachmentRef(
                    path = ".git/HEAD",
                    contentType = "application/octet-stream",
                    length = 23L,
                    contentId = ContentId("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                    agentId = "agent1",
                    revision = "1234567890abcdef",
                    sequence = 1L
                ),
                "ref: refs/heads/master\n".encodeToByteArray()
            )
        )

        restore.restore(tempDir, attachments)

        val mainKtPath = fileOps.resolvePath(tempDir, "src/main.kt")
        assertTrue(fileOps.exists(mainKtPath))
        assertEquals("fun main() {}", fileOps.readAllBytes(mainKtPath).decodeToString())

        val headPath = fileOps.resolvePath(tempDir, ".git/HEAD")
        assertTrue(fileOps.exists(headPath))
        assertEquals("ref: refs/heads/master\n", fileOps.readAllBytes(headPath).decodeToString())

        assertEquals(2, processOps.commands.size)
        assertEquals(listOf("git", "-C", tempDir, "fsck"), processOps.commands[0])
        assertEquals(listOf("git", "-C", tempDir, "checkout", "-f", "1234567890abcdef"), processOps.commands[1])
        
        fileOps.deleteRecursively(tempDir)
    }

    @Test
    fun testAbsolutePathsRejected() = runTest {
        val fileOps = JvmFileOperations()
        val tempDir = fileOps.createTempDir("oroboros-restore-test-abs")
        val processOps = FakeProcessOpsForRestore()
        val restore = OroborosGitRestore(fileOps, processOps)

        val attachments = listOf(
            Pair(
                OroborosAttachmentRef(
                    path = "/etc/passwd",
                    contentType = "text/plain",
                    length = 0L,
                    contentId = ContentId("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                    agentId = "agent1",
                    revision = "123",
                    sequence = 1L
                ),
                ByteArray(0)
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            restore.restore(tempDir, attachments)
        }
        assertTrue(exception.message!!.contains("Absolute paths are not allowed"))
        
        fileOps.deleteRecursively(tempDir)
    }

    @Test
    fun testPathTraversalRejected() = runTest {
        val fileOps = JvmFileOperations()
        val tempDir = fileOps.createTempDir("oroboros-restore-test-trav")
        val processOps = FakeProcessOpsForRestore()
        val restore = OroborosGitRestore(fileOps, processOps)

        val attachments = listOf(
            Pair(
                OroborosAttachmentRef(
                    path = "src/../../etc/passwd",
                    contentType = "text/plain",
                    length = 0L,
                    contentId = ContentId("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                    agentId = "agent1",
                    revision = "123",
                    sequence = 1L
                ),
                ByteArray(0)
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            restore.restore(tempDir, attachments)
        }
        assertTrue(exception.message!!.contains("Path traversal (..) is not allowed"))
        
        fileOps.deleteRecursively(tempDir)
    }

    @Test
    fun testGitFsckFailure() = runTest {
        val fileOps = JvmFileOperations()
        val tempDir = fileOps.createTempDir("oroboros-restore-test-fsck")
        val processOps = FakeProcessOpsForRestore()
        processOps.failFsck = true
        val restore = OroborosGitRestore(fileOps, processOps)

        val attachments = listOf(
            Pair(
                OroborosAttachmentRef(
                    path = "src/main.kt",
                    contentType = "text/kotlin",
                    length = 0L,
                    contentId = ContentId("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                    agentId = "agent1",
                    revision = "123",
                    sequence = 1L
                ),
                ByteArray(0)
            )
        )

        val exception = assertFailsWith<IllegalStateException> {
            restore.restore(tempDir, attachments)
        }
        assertTrue(exception.message!!.contains("git fsck failed"))
        
        fileOps.deleteRecursively(tempDir)
    }

    @Test
    fun testGitCheckoutFailure() = runTest {
        val fileOps = JvmFileOperations()
        val tempDir = fileOps.createTempDir("oroboros-restore-test-checkout")
        val processOps = FakeProcessOpsForRestore()
        processOps.failCheckout = true
        val restore = OroborosGitRestore(fileOps, processOps)

        val attachments = listOf(
            Pair(
                OroborosAttachmentRef(
                    path = "src/main.kt",
                    contentType = "text/kotlin",
                    length = 0L,
                    contentId = ContentId("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                    agentId = "agent1",
                    revision = "123",
                    sequence = 1L
                ),
                ByteArray(0)
            )
        )

        val exception = assertFailsWith<IllegalStateException> {
            restore.restore(tempDir, attachments)
        }
        assertTrue(exception.message!!.contains("git checkout failed"))
        
        fileOps.deleteRecursively(tempDir)
    }
}
