package borg.trikeshed.daemon

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OroborosDaemonCycleTraceTest {

    private lateinit var tempDir: File
    private lateinit var forgeHome: File
    private lateinit var repoDir: File

    @Before
    fun setup() {
        tempDir = File.createTempFile("oroboros_test", "").apply {
            delete()
            mkdir()
        }
        forgeHome = File(tempDir, "forge").apply { mkdir() }
        repoDir = File(tempDir, "repo").apply { mkdir() }
        File(repoDir, ".git").apply { mkdir() }
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    // In Java 17+, modifying System.getenv is locked down via reflection unless add-opens is used,
    // which may not be present in Gradle. Instead of reflection hack, let's use a workaround if needed,
    // or assume we can set it in the gradle environment block if JULES_API_KEY is null,
    // but the spec dictates we use:
    // ./gradlew jvmTest --tests 'borg.trikeshed.daemon.OroborosDaemonCycleTraceTest' --no-daemon
    // We can also use a custom wrapper or patch OroborosDaemon to allow passing an API key.
    // Wait, let's just use reflection to inject the ENV for the test.

    @Suppress("UNCHECKED_CAST")
    private fun setEnv(key: String, value: String) {
        try {
            val env = System.getenv()
            val cl = env.javaClass
            val field = cl.getDeclaredField("m")
            field.isAccessible = true
            val map = field.get(env) as MutableMap<String, String>
            map[key] = value
        } catch (e: Exception) {
            // JVM 17+ might block this, but we can try other methods or assume JULES_API_KEY is exported
            // by the environment running this test.
        }

        try {
            val clazz = Class.forName("java.lang.ProcessEnvironment")
            val field = clazz.getDeclaredField("theEnvironment")
            field.isAccessible = true
            val map = field.get(null) as MutableMap<String, String>
            map[key] = value
        } catch (e: Exception) {
        }
    }

    @Test
    fun `daemon cycle trace writes jsonl rings`() {
        val traceFile = File(forgeHome, "oroboros-cycles.jsonl")

        // Mock api key
        System.setProperty("JULES_API_KEY", "test-key-mock")

        for (i in 1..5) {
            try {
                OroborosDaemon.main(arrayOf("--once", forgeHome.absolutePath, repoDir.absolutePath))
            } catch (e: SecurityException) {
                // Ignore if exitProcess was called and trapped
            }
        }

        // The JSONL file is not flushed until JVM exit. In the test, we must call flush manually if possible, or wait, we can't because it's a private variable. But wait, we can close the daemon or use reflection. Actually, FileOutputStream has a FileDescriptor that we can't flush easily. Let's fix OroborosDaemon to close on --once.

        assertTrue(traceFile.exists(), "traceFile should exist")
        val lines = traceFile.readLines()
        assertEquals(5, lines.size, "Should have 5 lines")

        lines.forEach { line ->
            assertTrue(line.startsWith("{"), "Should be JSON")

            // Use simple regex parsing since we can't use complex JSON libraries
            val timeRegex = "\"t\":\\d+".toRegex()
            val cycleRegex = "\"c\":\\d+".toRegex()
            val keys = listOf("\"t\":", "\"c\":", "\"d\":", "\"p\":", "\"a\":", "\"v\":", "\"e\":")

            keys.forEach { key ->
                assertTrue(line.contains(key), "Should contain $key")
            }
        }
    }
}
