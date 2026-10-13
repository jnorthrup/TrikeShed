package borg.trikeshed.daemon

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class OroborosLauncherTest {
    @Test fun buildOverwriteCannotChangeRunningClasspathAndExitCleansSnapshots() = fixture { root, java ->
        val process = launch(root, java, "7")
        try {
            val output = process.inputStream.bufferedReader()
            val snapshot = File(output.readLine())
            assertEquals("application:dependency", output.readLine())
            File(root, "build/libs/TrikeShed-jvm-0.1.0-SNAPSHOT.jar").writeText("rebuilt")
            File(root, "build/staging/lib/dependency.jar").writeText("updated")
            File(root, "release").writeText("")
            assertEquals("application:dependency", output.readLine())
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(7, process.exitValue())
            assertFalse(snapshot.exists(), "Exited daemon left a runtime snapshot")
        } finally { release(root, process) }
    }

    @Test fun launcherSignalWaitsForChildDrainBeforeRemovingClasspath() = fixture { root, java ->
        val process = launch(root, java, "0")
        try {
            val output = process.inputStream.bufferedReader()
            val snapshot = File(output.readLine())
            assertEquals("application:dependency", output.readLine())
            process.destroy() // TERM to the launcher, not directly to its child.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!File(root, "draining").exists() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(File(root, "draining").exists())
            assertTrue(process.isAlive, "Launcher exited before child drain")
            assertTrue(snapshot.exists(), "Classpath removed during child drain")
            File(root, "release").writeText("")
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertFalse(snapshot.exists())
        } finally { release(root, process) }
    }

    private fun fixture(check: (File, File) -> Unit) {
        val root = Files.createTempDirectory("oroboros-launcher-test").toFile()
        try {
            File(root, "bin").mkdirs()
            File("bin/oroboros").copyTo(File(root, "bin/oroboros"))
            File(root, "build/libs").mkdirs(); File(root, "build/staging/lib").mkdirs()
            File(root, "build/libs/TrikeShed-jvm-0.1.0-SNAPSHOT.jar").writeText("application")
            File(root, "build/staging/lib/dependency.jar").writeText("dependency")
            val java = File(root, "java")
            File(java, "bin").mkdirs()
            File(java, "bin/java").apply {
                writeText("""
                    #!/bin/bash
                    set -eu
                    while [[ "${'$'}1" != "-cp" ]]; do shift; done
                    IFS=: read -r -a jars <<< "${'$'}2"
                    snapshot="${'$'}(dirname "${'$'}{jars[0]}")"
                    printf '%s\n' "${'$'}snapshot"
                    read_jars() { printf '%s:%s\n' "${'$'}(cat "${'$'}{jars[0]}")" "${'$'}(cat "${'$'}{jars[1]}")"; }
                    drain() {
                      touch draining
                      while [[ ! -f release ]]; do sleep 0.02; done
                      exit 0
                    }
                    trap drain TERM
                    read_jars
                    while [[ ! -f release ]]; do sleep 0.02; done
                    read_jars
                    exit "${'$'}EXIT_CODE"
                """.trimIndent() + "\n")
                setExecutable(true)
            }
            check(root, java)
        } finally { root.deleteRecursively() }
    }

    private fun launch(root: File, java: File, code: String): Process =
        ProcessBuilder("bash", File(root, "bin/oroboros").absolutePath).directory(root).apply {
            environment()["JAVA_HOME"] = java.absolutePath
            environment()["TMPDIR"] = root.absolutePath
            environment()["EXIT_CODE"] = code
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }.start()

    private fun release(root: File, process: Process) {
        File(root, "release").writeText("")
        if (process.isAlive) { process.destroy(); check(process.waitFor(10, TimeUnit.SECONDS)) }
    }
}
