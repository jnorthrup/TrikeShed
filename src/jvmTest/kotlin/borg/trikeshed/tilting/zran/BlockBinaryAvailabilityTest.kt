package borg.trikeshed.tilting.zran

import kotlin.test.*

/**
 * JVM-only platform binary availability tests.
 *
 * These require ProcessBuilder (JVM-only) to detect whether zstd/lz4
 * CLI tools are installed on the host system.
 *
 * Run with: ./gradlew jvmTest --tests "borg.trikeshed.tilting.zran.BlockBinaryAvailabilityTest"
 */
class BlockBinaryAvailabilityTest {

    // ========================================================================
    // Platform binary availability checks
    // ========================================================================

    @Test
    fun `zstd CLI is available on this system`() {
        val version = runCmd("zstd", "--version")
        assertNotNull(version, "zstd CLI must be available. Install: brew install zstd")
        assertTrue(
            version!!.contains("Zstandard", ignoreCase = true),
            "zstd version output should contain 'Zstandard': $version"
        )
    }

    @Test
    fun `lz4 CLI is available on this system`() {
        val version = runCmd("lz4", "--version")
        assertNotNull(version, "lz4 CLI must be available. Install: brew install lz4")
        assertTrue(
            version!!.contains("lz4", ignoreCase = true),
            "lz4 version output should contain 'lz4': $version"
        )
    }

    // ========================================================================
    // Helper
    // ========================================================================

    /** Run a command and return its stdout, or null if the command fails. */
    private fun runCmd(vararg args: String): String? {
        return try {
            val pb = java.lang.ProcessBuilder(args.toList())
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val exitCode = proc.waitFor()
            if (exitCode == 0) output else null
        } catch (e: Exception) {
            null
        }
    }
}
