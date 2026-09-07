package borg.trikeshed.daemon

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import java.nio.file.Files

/** A port the OS says is free right now, so this boot never fights the dev
 *  daemon on 8888. Same reasoning as OroborosDaemonHealthTest. */
private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

class OroborosDaemonShutdownTest {

    @Test
    fun testSigtermGracefulShutdown() {
        val forgeHome = Files.createTempDirectory("forge_test").toFile()
        // A throwaway repo, not user.dir. Pointed at the real worktree this child
        // ran the full Git/Worktree/Build reconcile -- thousands of paths -- purely
        // to prove a signal handler answers, and any real state it touched was the
        // developer's. A directory with a .git in it is all the daemon needs here.
        val repoDir = Files.createTempDirectory("repo_test").toFile()
        File(repoDir, ".git").mkdirs()

        // Mock JULES_API_KEY for the child process so it doesn't abort early.
        val pb = ProcessBuilder(
            "java",
            "-cp", System.getProperty("java.class.path"),
            "borg.trikeshed.daemon.OroborosDaemon",
            "--watch", "--interval-ms", "30000",
            // Without an explicit port the child takes DEFAULT_KANBAN_PORT (8888)
            // and burns five bind retries against a running dev daemon before
            // continuing headless.
            "--kanban-port", freePort().toString(),
            forgeHome.absolutePath,
            repoDir.absolutePath
        )
        pb.environment()["JULES_API_KEY"] = "mock_key_for_test"

        // Ensure child process uses a temporary directory for output so it doesn't pollute the test environment
        pb.redirectOutput(File(forgeHome, "stdout.log"))
        pb.redirectError(File(forgeHome, "stderr.log"))

        // Use inheritIO for debugging if needed, but for now just let it run.
        val process = pb.start()

        try {
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
            assertTrue(exited, "Process did not exit gracefully within timeout")
            val exitCode = process.exitValue()
            assertTrue(exitCode == 0 || exitCode == 143, "Daemon should exit with code 0 or 143 (SIGTERM) on SIGTERM")
        } finally {
            // Clean up
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            forgeHome.deleteRecursively()
            repoDir.deleteRecursively()
        }
    }
}
