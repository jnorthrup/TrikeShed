package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.graal.subvm.GuestEnvironment
import borg.trikeshed.userspace.nio.process.ProcessCapability
import borg.trikeshed.userspace.nio.process.ProcessSpec
import borg.trikeshed.userspace.nio.process.ProcessWorkerJvm
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class JvmProcessOperationsTest {
    val declared = mapOf("SAFE_VAR" to "declared-value", "MY_API_KEY" to "fixture-key", "mIxEd_Secret" to "fixture-secret")

    fun environment(bytes: ByteArray) {
        assertEquals(GuestEnvironment.Disposition.DEFERRED, GuestEnvironment.deliberate("SAFE_VAR"))
        val actual = bytes.decodeToString().lineSequence().filter { it.isNotEmpty() }.associate { line ->
            line.substringBefore('=') to line.substringAfter('=')
        }
        assertEquals<Map<String, String>>(GuestEnvironment.curated() + ("SAFE_VAR" to "declared-value"), actual)
    }

    @Test
    fun progressesInputAndOutputBeyondPipeCapacity(): Unit = runBlocking {
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val result = withTimeout(10_000) { JvmProcessOperations().exec("/bin/cat", stdin = bytes) }
        assertEquals(0, result.exitCode)
        assertContentEquals(bytes, result.stdout)
        assertEquals(0, result.stderr.size)
    }

    @Test
    fun deliberateBlocksToxicDynamicLoaderAndInterpreterVars() {
        listOf("LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "PYTHONPATH", "NODE_OPTIONS").forEach { key ->
            assertEquals(GuestEnvironment.Disposition.BLOCKED, GuestEnvironment.deliberate(key))
        }
    }

    @Test
    fun execFiltersBlockedKeysAndRetainsDeclaredDeferredValues(): Unit = runBlocking {
        val result = withTimeout(5_000) { JvmProcessOperations().exec("/usr/bin/env", env = declared) }
        assertEquals(0, result.exitCode)
        environment(result.stdout)
    }

    @Test
    fun openFiltersBlockedKeysAndRetainsDeclaredDeferredValues(): Unit = runBlocking {
        val pipes = JvmProcessOperations().open("/usr/bin/env", env = declared)
        try {
            withTimeout(5_000) {
                pipes.closeInput()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = pipes.read(ProcessOutput.Stdout, buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                assertEquals(0, pipes.awaitExit())
                environment(output.toByteArray())
            }
        } finally { pipes.close() }
    }

    @Test
    fun workerFiltersBlockedKeysAndRetainsDeclaredDeferredValues(): Unit = runBlocking {
        val worker = ProcessWorkerJvm(ProcessCapability("environment-fixture", setOf("env")))
        val result = withTimeout(5_000) { worker.spawn(ProcessSpec("/usr/bin/env", env = declared, timeoutMs = 5_000)) }
        assertEquals(0, result.exitCode)
        environment(result.stdout)
    }

    @Test
    fun cancellationReapsTheStartedProcess(): Unit = runBlocking {
        val pidFile = File.createTempFile("trikeshed-process-", ".pid")
        val processes = JvmProcessOperations()
        val call = async {
            processes.exec("/bin/sh", listOf("-c",
                "printf '%s\\n' \"\$\$\" > \"\$1\"; exec /bin/sleep 60", "process-fixture", pidFile.absolutePath))
        }
        try {
            val pid = withTimeout(5_000) {
                var text = pidFile.readText()
                while (!text.endsWith('\n')) { delay(10); text = pidFile.readText() }
                text.trim().toLong()
            }
            withTimeout(5_000) { call.cancelAndJoin() }
            withTimeout(5_000) {
                while (processes.exec("/bin/kill", listOf("-0", pid.toString())).exitCode == 0) delay(10)
            }
        } finally {
            call.cancelAndJoin()
            pidFile.delete()
        }
    }

}
