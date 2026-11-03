package borg.trikeshed.mux

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import kotlin.test.*

class McpStdioMlxTest {
    @Test fun explicitKotlinReportsMissingNativeProvider() {
        val entries = linkedSetOf<String>()
        System.getProperty("java.class.path").split(File.pathSeparator).forEach { entries.add(it) }
        var loader: ClassLoader? = Thread.currentThread().contextClassLoader
        while (loader != null) {
            if (loader is URLClassLoader) loader.getURLs().filter { it.protocol == "file" }.forEach { entries.add(File(it.toURI()).path) }
            loader = loader.parent
        }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val command = ProcessBuilder(java, "-cp", entries.joinToString(File.pathSeparator),
            "borg.trikeshed.mux.McpStdioMain", "--oversight", "mlx").redirectErrorStream(true)
        command.environment().keys.removeIf { it.startsWith("TRIKESHED_JEVA_") }
        command.environment()["TRIKESHED_JEVA_DEVICE"] = "mlx"
        command.environment()["TRIKESHED_JEVA_RUNTIME"] = "kotlin"
        val process = command.start()
        try {
            process.outputStream.close()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "stdio did not stop")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(1, process.exitValue(), output)
            assertTrue(output.contains("EncoderOperations"), output)
        } finally {
            process.destroyForcibly()
        }
    }
}
