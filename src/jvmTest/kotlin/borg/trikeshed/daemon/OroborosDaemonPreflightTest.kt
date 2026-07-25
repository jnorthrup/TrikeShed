package borg.trikeshed.daemon

import borg.trikeshed.jules.FlywheelDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import kotlin.test.Test
import java.nio.file.Files

class OroborosDaemonPreflightTest {

    @Test
    fun testUpstreamDivergenceEmitsEvent() = runTest {
        val root = Files.createTempDirectory("oroboros_test").toFile()
        try {
            val origin = File(root, "origin")
            origin.mkdirs()
            ProcessBuilder("git", "init").directory(origin).start().waitFor()
            ProcessBuilder("git", "config", "user.name", "Test").directory(origin).start().waitFor()
            ProcessBuilder("git", "config", "user.email", "test@test.com").directory(origin).start().waitFor()

            val originFile = File(origin, "test.txt")
            originFile.writeText("A")
            ProcessBuilder("git", "add", "test.txt").directory(origin).start().waitFor()
            ProcessBuilder("git", "commit", "-m", "A").directory(origin).start().waitFor()

            val clone = File(root, "clone")
            clone.mkdirs()
            ProcessBuilder("git", "clone", origin.absolutePath, clone.absolutePath).directory(root).start().waitFor()

            // Advance origin to commit B directly
            originFile.writeText("B")
            ProcessBuilder("git", "commit", "-am", "B").directory(origin).start().waitFor()

            // Do a fetch in clone to update origin/master ref
            ProcessBuilder("git", "fetch", "origin", "master").directory(clone).start().waitFor()

            // Now clone is diverged from origin/master
            val forgeHome = File(root, "forgeHome")
            forgeHome.mkdirs()

            val driver = FlywheelDriver(
                apiKey = "dummy",
                repoDir = clone,
                forgeDir = forgeHome,
                intervalMs = 1000L,
                maxSlots = 5
            )

            var emittedEvent: FlywheelDriver.FlywheelEvent? = null

            // Using driver.subscribe which is public API
            val job = driver.subscribe { event ->
                if (event is FlywheelDriver.FlywheelEvent.UpstreamDrifted) {
                    emittedEvent = event
                }
            }
            kotlinx.coroutines.yield()

            // Using reflection to call private preflight
            val preflightMethod = OroborosDaemon::class.java.getDeclaredMethod("preflight", File::class.java, FlywheelDriver::class.java)
            preflightMethod.isAccessible = true

            val result = preflightMethod.invoke(OroborosDaemon, clone, driver) as Boolean

            // Wait for event
            kotlinx.coroutines.delay(100)
            driver.close()
            job.cancel()
            kotlinx.coroutines.yield()

            // Assertion
            assertEquals(false, result)
            assertTrue(emittedEvent is FlywheelDriver.FlywheelEvent.UpstreamDrifted, "Expected UpstreamDrifted event but got $emittedEvent")

        } finally {
            root.deleteRecursively()
        }
    }
}
