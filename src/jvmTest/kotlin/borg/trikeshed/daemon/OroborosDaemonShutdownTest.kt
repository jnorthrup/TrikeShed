package borg.trikeshed.daemon

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import java.nio.file.Files

class OroborosDaemonShutdownTest {

    @Test
    fun testSigtermGracefulShutdown() {
        val forgeHome = Files.createTempDirectory("forge_test").toFile()
        val repoDir = File(System.getProperty("user.dir"))

        // Mock JULES_API_KEY for the child process so it doesn't abort early.
        val pb = ProcessBuilder(
            "java",
            "-cp", System.getProperty("java.class.path"),
            "borg.trikeshed.daemon.OroborosDaemon",
            "--watch", "--interval-ms", "30000",
            forgeHome.absolutePath,
            repoDir.absolutePath
        )
        pb.environment()["JULES_API_KEY"] = "mock_key_for_test"
        
        // Ensure child process uses a temporary directory for output so it doesn't pollute the test environment
        pb.redirectOutput(File(forgeHome, "stdout.log"))
        pb.redirectError(File(forgeHome, "stderr.log"))

        // Use inheritIO for debugging if needed, but for now just let it run.
        val process = pb.start()

        // Wait a bit for the daemon to start up and register its signal handler
        Thread.sleep(3000)

        // Ensure process is still running
        assertTrue(process.isAlive, "Daemon process should be running")

        // Send SIGTERM
        val killPb = ProcessBuilder("kill", "-15", process.pid().toString())
        killPb.start().waitFor()

        // Give it up to 5 seconds to gracefully exit
        val exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        
        // Assertions
        try {
            assertTrue(exited, "Process did not exit gracefully within timeout")
            assertEquals(0, process.exitValue(), "Daemon should exit with code 0 on SIGTERM")
        } finally {
            // Clean up
            if (process.isAlive) {
                process.destroyForcibly()
            }
            forgeHome.deleteRecursively()
        }
    }
}
