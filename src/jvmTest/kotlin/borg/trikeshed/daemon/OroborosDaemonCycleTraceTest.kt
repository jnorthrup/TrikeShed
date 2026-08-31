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
        tempDir = File.createTempFile("odt", "").apply {
            delete()
            mkdir()
        }
        forgeHome = File(tempDir, "forge").apply { mkdir() }
        repoDir = File(tempDir, "repo").apply { mkdir() }
        File(repoDir, ".git").apply { mkdir() }
    }

    @After
    fun teardown() {
        borg.trikeshed.userspace.nio.platform.spi.SystemOperations.register(borg.trikeshed.userspace.nio.platform.spi.loadDefaultSystemOperations())
        tempDir.deleteRecursively()
    }

    // In Java 17+, modifying System.getenv is locked down via reflection unless add-opens is used,
    // which may not be present in Gradle. Instead of reflection hack, let's use a workaround if needed,
    // or assume we can set it in the gradle environment block if JULES_API_KEY is null,
    // but the spec dictates we use:
    // ./gradlew jvmTest --tests 'borg.trikeshed.daemon.OroborosDaemonCycleTraceTest' --no-daemon
    // We can also use a custom wrapper or patch OroborosDaemon to allow passing an API key.
    // Wait, let's just use reflection to inject the ENV for the test.

    private fun setEnv(envKey: String, value: String) {
        borg.trikeshed.userspace.nio.platform.spi.SystemOperations.register(
            object : borg.trikeshed.userspace.nio.platform.spi.SystemOperations {
                override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = borg.trikeshed.userspace.nio.platform.spi.SystemOperations.Key
                override fun getenv(name: String, defaultVal: String?): String? = if (name == envKey) value else java.lang.System.getenv(name) ?: defaultVal
                override fun getProperty(name: String, defaultVal: String?): String? = java.lang.System.getProperty(name) ?: defaultVal
                override val homedir: String get() = java.lang.System.getProperty("user.home") ?: "/"
            }
        )
    }

    /**
     * Cycle tracing was RETIRED with the flywheel; this test outlived it.
     *
     * It booted the daemon five times and expected five JSONL records carrying
     * `t/c/d/p/a/v/e` — flywheel cycle fields. Nothing writes them any more:
     * `traceWriter` in OroborosDaemon is opened, flushed and closed, and never
     * written to (the daemon's own ALIVE line says "cycle fields retired with the
     * flywheel" and reports -1 for each). So it could not pass, and it paid five
     * full daemon boots — about five minutes — to fail.
     *
     * Rewritten to the invariant that survived: a `--once` boot completes and
     * shuts down cleanly. That is a real smoke test and costs ONE boot. The
     * emptiness of the trace file is asserted deliberately, so that whoever
     * reinstates cycle tracing is told by a failure here to update this test
     * rather than discovering an always-empty file in every forge home.
     */
    @Test
    fun `a --once daemon boots and shuts down cleanly`() {
        val traceFile = File(forgeHome, "oroboros-cycles.jsonl")
        setEnv("JULES_API_KEY", "test-key-mock")

        try {
            OroborosDaemon.main(
                arrayOf(
                    "--once",
                    "--kanban-port", java.net.ServerSocket(0).use { it.localPort }.toString(),
                    forgeHome.absolutePath,
                    repoDir.absolutePath,
                ),
            )
        } catch (e: SecurityException) {
            // exitProcess trapped by the test security manager — a clean exit.
        }

        assertTrue(traceFile.exists(), "the daemon should still open its trace file")
        assertEquals(
            0,
            traceFile.readLines().size,
            "cycle tracing is retired: nothing writes traceWriter. If you have reinstated it, " +
                "assert the real record shape here instead of this emptiness check.",
        )
    }
}
