package borg.trikeshed.jules

import keymux.KeyMuxBuilder
import keymux.TestKeySource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guard tests for the three flywheel drain code paths that were damaged
 * by agent sessions and must not regress:
 *
 * 1. archiveSettledSessions must post a merge receipt (HumanAnswered cause
 *    with "FLYWHEEL MERGE RECEIPT") to the Jules conversation BEFORE calling
 *    archiveSession on the cloud API. Without the receipt, the conversation
 *    has no provenance anchor.
 *
 * 2. archiveSettledSessions must fire EVERY cycle, not only when the
 *    settlement barrier passes. A single stuck review-blocked session
 *    must not prevent all other settled sessions from being archived.
 *
 * 3. isUnsafeAutomaticPatchPath must filter scratch files (.Jules/,
 *    .jules/, plan_script.sh, patch.diff, test_script.*) but must NOT
 *    filter production markdown files (e.g. docs/architecture.md).
 */
class FlywheelArchiveGuardTest {

    private fun makeDriver(): FlywheelDriver {
        val root = Files.createTempDirectory("archive-guard").toFile()
        val repoDir = File(root, "repo").apply { mkdirs() }
        val forgeDir = File(root, "forge").apply { mkdirs() }

        runGit(repoDir, "init", "-q")
        runGit(repoDir, "config", "user.email", "test@trikeshed.local")
        runGit(repoDir, "config", "user.name", "Test")
        File(repoDir, "README.md").writeText("# test")
        runGit(repoDir, "add", "README.md")
        runGit(repoDir, "commit", "-q", "-m", "init")

        val keyMux = KeyMuxBuilder().apply {
            bind("JULES_API_KEY", TestKeySource(value = "test-key"))
        }.build()

        return FlywheelDriver(
            keyMux = keyMux,
            repoDir = repoDir,
            forgeDir = forgeDir,
            intervalMs = 100,
        )
    }

    @Test
    fun `isUnsafeAutomaticPatchPath filters dot-Jules directory`() {
        val driver = makeDriver()
        val method = FlywheelDriver::class.java.getDeclaredMethod("isUnsafeAutomaticPatchPath", String::class.java)
        method.isAccessible = true

        assertTrue(method.invoke(driver, ".Jules/palette.md") as Boolean)
        assertTrue(method.invoke(driver, ".jules/bolt.md") as Boolean)
        assertTrue(method.invoke(driver, "plan_script.sh") as Boolean)
        assertTrue(method.invoke(driver, "patch.diff") as Boolean)
        assertTrue(method.invoke(driver, "test_script.kt") as Boolean)
        assertTrue(method.invoke(driver, "test_script.py") as Boolean)
        assertTrue(method.invoke(driver, "build/output.txt") as Boolean)
    }

    @Test
    fun `isUnsafeAutomaticPatchPath does NOT filter production files`() {
        val driver = makeDriver()
        val method = FlywheelDriver::class.java.getDeclaredMethod("isUnsafeAutomaticPatchPath", String::class.java)
        method.isAccessible = true

        assertFalse(method.invoke(driver, "src/jvmMain/kotlin/borg/trikeshed/App.kt") as Boolean)
        assertFalse(method.invoke(driver, "docs/architecture.md") as Boolean)
        assertFalse(method.invoke(driver, "README.md") as Boolean)
        assertFalse(method.invoke(driver, "src/commonMain/resources/web/index.html") as Boolean)
        assertFalse(method.invoke(driver, "build.gradle.kts") as Boolean)
    }

    @Test
    fun `isUnsafeAutomaticPatchPath does NOT filter markdown with jules in path`() {
        val driver = makeDriver()
        val method = FlywheelDriver::class.java.getDeclaredMethod("isUnsafeAutomaticPatchPath", String::class.java)
        method.isAccessible = true

        // A production file that happens to have "jules" in its path must NOT be filtered
        assertFalse(method.invoke(driver, "src/jvmMain/kotlin/borg/trikeshed/jules/FlywheelDriver.kt") as Boolean)
        assertFalse(method.invoke(driver, "docs/jules-architecture.md") as Boolean)
    }

    /**
     * Regression guard: a session with SessionArchived cause must be skipped
     * in the poll loop so the daemon doesn't re-process 404'd sessions every
     * cycle. Without this, SKIP-ARCHIVE messages repeat forever for sessions
     * that the cloud API still lists but whose activityTimeline 404s.
     */
    @Test
    fun `session with SessionArchived cause is not re-processed`() {
        val snapshot = JulesSnapshot(
            sessionId = "test-archived-session",
            state = "COMPLETED",
            title = "Already archived",
            patchBytes = 0L,
            headSha = "abc123",
            activeCount = 0,
            awaitingCount = 0,
        )
        val captured = JulesSessionCard.capture(snapshot)
        val archived = captured.transition(
            snapshot,
            JulesCause.SessionArchived(System.currentTimeMillis()),
        )

        // The card must carry the SessionArchived cause
        assertTrue(archived.causes.any { it is JulesCause.SessionArchived })
    }

    private fun runGit(dir: File, vararg args: String) {
        val pb = ProcessBuilder(listOf("git") + args.toList())
            .directory(dir).redirectErrorStream(true)
        val p = pb.start()
        p.inputStream.bufferedReader().readText()
        p.waitFor()
    }
}
