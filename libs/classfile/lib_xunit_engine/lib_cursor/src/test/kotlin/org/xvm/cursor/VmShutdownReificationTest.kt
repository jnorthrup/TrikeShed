package org.xvm.cursor

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

private fun findRepoRoot(): File {
    var dir = File(System.getProperty("user.dir")).canonicalFile
    while (!dir.resolve("settings.gradle.kts").isFile) {
        dir = dir.parentFile ?: error("Could not locate repo root from ${System.getProperty("user.dir")}")
    }
    return dir
}

class VmShutdownReificationTest {
    private val repoRoot = findRepoRoot()

    @Test
    fun `shutdown hook automatically dumps csv artifacts`(@TempDir tempDir: Path) {
        val javatoolsDir = File(requireNotNull(System.getProperty("pointcutVm.javatoolsDir")) {
            "Missing pointcutVm.javatoolsDir system property"
        })
        assertTrue(javatoolsDir.isDirectory)

        val childClasspath = buildList {
            add(javatoolsDir.absolutePath)
            addAll(
                System.getProperty("java.class.path")
                    .split(File.pathSeparator)
                    .filterNot {
                        it.contains("/javatools/build/libs/javatools-") ||
                        it.contains("/xdk/build/install/xdk/javatools/javatools.jar")
                    }
            )
        }.joinToString(File.pathSeparator)

        // Launch a child process that registers events and then exits,
        // with the xvm.pointcut.dumpdir property set.
        val process = ProcessBuilder(
            "java",
            "-Dxvm.pointcut.dumpdir=${tempDir.toAbsolutePath()}",
            "-cp",
            childClasspath,
            "org.xvm.cursor.VmShutdownReifierHelper"
        )
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        assertTrue(exit == 0, "Helper failed with exit $exit:\n$output")

        // Assert that the ISAM files were successfully written by the shutdown hook
        val tableDumpBin = tempDir.resolve("table_dump.bin").toFile()
        val cascadeBin = tempDir.resolve("cascade_leafscan.bin").toFile()
        val cascadeMeta = tempDir.resolve("cascade_leafscan.bin.meta").toFile()
        val jointHistBin = tempDir.resolve("joint_histogram.bin").toFile()

        assertTrue(cascadeBin.exists(), "Missing cascade_leafscan.bin")
        assertTrue(cascadeMeta.exists(), "Missing cascade_leafscan.bin.meta")
        assertTrue(jointHistBin.exists(), "Missing joint_histogram.bin")
        assertTrue(tableDumpBin.exists(), "Missing table_dump.bin")

        assertTrue(cascadeBin.length() > 0, "cascade_leafscan.bin must have data")
    }
}
