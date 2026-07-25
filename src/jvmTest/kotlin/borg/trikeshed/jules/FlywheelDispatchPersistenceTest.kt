package borg.trikeshed.jules

import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlywheelDispatchPersistenceTest {
    @Test
    fun dispatchedSessionIdIsPersistedAndProjectsThroughTheUnifiedQueue() = runBlocking {
        val repo = Files.createTempDirectory("dispatch-persist-repo").toFile()
        repo.mkdirs()
        runGit(repo, "init", "-q", "--initial-branch=master")
        runGit(repo, "config", "user.email", "agent@trikeshed.local")
        runGit(repo, "config", "user.name", "Agent")
        File(repo, "README.md").writeText("hi\n")
        runGit(repo, "add", "README.md")
        runGit(repo, "commit", "-q", "-m", "initial")

        val forge = Files.createTempDirectory("dispatch-persist-forge").toFile()
        val wal = JvmAppendWal(File(forge, "board.wal"))
        val store = JulesBoardStore(wal)

        val driver = FlywheelDriver(
            apiKey = "test-key",
            repoDir = repo,
            forgeDir = forge,
            queueStore = store,
        )
        driver.sessionCreator = { _, _ -> "sess-abc" }
        val createdSessionId = driver.dispatchAndRecord(
            workId = "todo:abc",
            title = "Wire CAS receipt",
            spec = "Test: src/jvmTest/kotlin/CasTest.kt; implement src/jvmMain/kotlin/Cas.kt; run ./gradlew jvmTest --tests CasTest",
        )

        assertTrue(createdSessionId.isNotEmpty(), "dispatch must return a session id")
        val queue = store.loadQueue()
        assertEquals(1, queue.size)
        val entry = queue.single()
        assertEquals("todo:abc", entry.workId)
        assertEquals(createdSessionId, entry.sessionId)
        assertTrue(entry.url == "https://jules.google.com/session/$createdSessionId",
            "unified queue must expose the canonical jules URL")
    }

    @Test
    fun workIdentitySynthesizedRoundTripsThroughWal() = runBlocking {
        val forge = Files.createTempDirectory("identity-forge").toFile()
        val wal = JvmAppendWal(File(forge, "board.wal"))
        val store = JulesBoardStore(wal)

        val repo = Files.createTempDirectory("identity-repo").toFile()
        val driver = FlywheelDriver(apiKey = "k", repoDir = repo, forgeDir = forge, queueStore = store)
        driver.sessionCreator = { _, _ -> "sess-identity-001" }

        val returned = driver.dispatchAndRecord(
            workId = "todo:identity-1",
            title = "Wire identity synonym",
            spec = "noop",
        )

        val causes = store.replayCauses("todo:identity-1")
        val synthesized = causes.filterIsInstance<JulesCause.WorkIdentitySynthesized>()
        assertEquals(1, synthesized.size, "exactly one WorkIdentitySynthesized cause per dispatch")

        val ident = synthesized.single().identity
        assertEquals("todo:identity-1", ident.workId)
        assertEquals(returned, ident.sessionId)
        assertEquals("https://jules.google.com/session/$returned", ident.sessionUrl)
        assertEquals(null, ident.gitBranch, "gitBranch is null at dispatch — Jules may never push")
        assertEquals(null, ident.prUrl, "prUrl is null — Jules may never gh pr create")
        assertEquals(null, ident.gitTag, "gitTag minted at claimPatch, not dispatch")
        assertEquals(null, ident.commitSha)
        assertEquals(false, ident.isLanded)
    }

    private data class CmdResult(val exitCode: Int, val output: String)

    private fun runGit(dir: File, vararg args: String): CmdResult {
        val p = ProcessBuilder(listOf("git") + args.toList())
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        check(p.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return CmdResult(p.exitValue(), out)
    }
}
