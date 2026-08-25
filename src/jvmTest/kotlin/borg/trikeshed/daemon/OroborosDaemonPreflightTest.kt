package borg.trikeshed.daemon

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import kotlin.test.Test
import java.nio.file.Files

class OroborosDaemonPreflightTest {

    @Test
    fun testUpstreamDivergenceBlocksPreflight() = runTest {
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
            ProcessBuilder("git", "config", "user.name", "Test").directory(clone).start().waitFor()
            ProcessBuilder("git", "config", "user.email", "test@test.com").directory(clone).start().waitFor()

            // Advance clone to commit C
            val cloneFile = File(clone, "test2.txt")
            cloneFile.writeText("C")
            ProcessBuilder("git", "add", "test2.txt").directory(clone).start().waitFor()
            ProcessBuilder("git", "commit", "-m", "C").directory(clone).start().waitFor()

            // Advance origin to commit B directly
            originFile.writeText("B")
            ProcessBuilder("git", "commit", "-am", "B").directory(origin).start().waitFor()

            // Do a fetch in clone to update origin/master ref
            ProcessBuilder("git", "fetch", "origin", "master").directory(clone).start().waitFor()

            // Now clone is diverged from origin/master: preflight must refuse to proceed.
            assertEquals(false, OroborosDaemon.preflight(clone))
        } finally {
            root.deleteRecursively()
        }
    }
}
