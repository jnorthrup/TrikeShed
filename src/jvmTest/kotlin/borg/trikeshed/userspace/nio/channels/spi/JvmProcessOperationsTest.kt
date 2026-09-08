package borg.trikeshed.userspace.nio.channels.spi

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
    @Test
    fun progressesInputAndOutputBeyondPipeCapacity(): Unit = runBlocking {
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val result = withTimeout(10_000) { JvmProcessOperations().exec("/bin/cat", stdin = bytes) }
        assertEquals(0, result.exitCode)
        assertContentEquals(bytes, result.stdout)
        assertEquals(0, result.stderr.size)
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
