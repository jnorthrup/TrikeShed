package borg.trikeshed.jules

import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class FlywheelEndToEndDrainTest {

    @Test
    fun `settlePatch applies gates commits receipts and atomically pushes master and tag`() {
        val root = Files.createTempDirectory("drain-e2e").toFile()
        val originDir = File(root, "origin").apply { mkdirs() }
        val repoDir = File(root, "work").apply { mkdirs() }
        val forgeDir = File(root, "forge").apply { mkdirs() }
        git(originDir, "init", "-q", "--bare", "--initial-branch=master")
        git(repoDir, "init", "-q", "--initial-branch=master")
        git(repoDir, "config", "user.email", "agent@trikeshed.local")
        git(repoDir, "config", "user.name", "Agent")
        File(repoDir, "README.md").writeText("hello\n")
        File(repoDir, "gate.sh").writeText("test \"$(cat src/Foo.kt)\" = \"val x = 1\"\n")
        git(repoDir, "add", "README.md", "gate.sh")
        git(repoDir, "commit", "-q", "-m", "initial")
        git(repoDir, "remote", "add", "origin", originDir.absolutePath)
        git(repoDir, "push", "-q", "origin", "master")
        val initialSha = git(repoDir, "rev-parse", "HEAD").output.trim()

        val patch = """
            diff --git a/src/Foo.kt b/src/Foo.kt
            new file mode 100644
            index 0000000..e69de29
            --- /dev/null
            +++ b/src/Foo.kt
            @@ -0,0 +1 @@
            +val x = 1
        """.trimIndent() + "\n"
        val driver = FlywheelDriver(
            apiKey = "test-key",
            repoDir = repoDir,
            forgeDir = forgeDir,
            gateCommand = listOf("/bin/sh", "gate.sh"),
        )

        val claim = driver.settlePatch(
            patch = patch,
            title = "E2E drain",
            sessionId = "7395203169723873685",
            workId = "todo:e2e-drain",
            content = "end-to-end drain against file origin",
        )

        assertNotNull(claim)
        assertNotEquals(initialSha, claim.commitSha)
        assertEquals("val x = 1\n", File(repoDir, "src/Foo.kt").readText())
        assertEquals("", git(repoDir, "status", "--porcelain").output.trim())
        assertEquals(claim.commitSha, git(originDir, "rev-parse", "refs/heads/master").output.trim())
        assertEquals(claim.commitSha, git(originDir, "rev-parse", "refs/tags/${claim.receipt.versionTag}^{}").output.trim())

        val casPath = JvmFileOperations().resolvePath(forgeDir.absolutePath, "cas")
        val backed = FileCasStore(JvmFileOperations(), casPath).get(claim.receipt.patchCid)
        assertNotNull(backed)
        assertEquals(patch, backed.decodeToString())
    }

    private data class CmdResult(val exitCode: Int, val output: String)

    private fun git(dir: File, vararg args: String): CmdResult {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        check(process.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return CmdResult(process.exitValue(), output)
    }
}
