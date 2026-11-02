package borg.trikeshed.userspace.nio.process

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessProtocolPipes
import kotlinx.coroutines.*
import java.lang.System
import java.nio.file.Path
import kotlin.test.*

/** Actual finite JVM children; no shell, fake pipe or timeout-only cancellation assertion. */
class ProcessCaptureBoundedTest {
    private suspend fun capture(mode: String, strict: Boolean = true, wall: Long = 5000): ProcessCaptureChannel {
        val pipes = JvmProcessProtocolPipes.open({ _, _ -> _l[
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
            System.getProperty("java.class.path"), CaptureFixture::class.java.name, mode] }, emptyMap(), mergeError = true)
        return ProcessCaptureChannel(currentCoroutineContext(), pipes, maxBytes = 1024,
            timeoutMillis = wall, failOnOverflow = strict)
    }
    @Test fun overflowRevokesAndJoins(): Unit = runBlocking {
        for (mode in s_["stdout", "stderr"].view) {
            val process = capture(mode)
            assertFailsWith<ProcessOutputLimitExceeded> { process.await() }
            assertFalse(ProcessHandle.of(process.processId).map { it.isAlive }.orElse(false))
        }
    }
    @Test fun oldCaptureRetainsHeadAndDrains(): Unit = runBlocking {
        val process = capture("finite", strict = false)
        val result = process.await()
        assertEquals(0, result.exit)
        assertEquals(1024, result.bytes.size)
        assertEquals(3072L, result.dropped)
        assertFalse(ProcessHandle.of(process.processId).map { it.isAlive }.orElse(false))
    }
    @Test fun wallTimeoutKillsAndJoins(): Unit = runBlocking {
        val process = capture("block", wall = 1000)
        assertTrue(process.await().timedOut)
        assertFalse(ProcessHandle.of(process.processId).map { it.isAlive }.orElse(false))
    }
    @Test fun callerCancellationKillsAndJoins(): Unit = runBlocking {
        val process = capture("block")
        val waiter = launch { process.await() }
        yield()
        waiter.cancelAndJoin()
        assertFalse(ProcessHandle.of(process.processId).map { it.isAlive }.orElse(false))
    }
}

object CaptureFixture {
    @JvmStatic fun main(args: Array<String>) {
        when (args.single()) {
            "finite" -> { System.out.write(ByteArray(4096) { 120 }); System.out.flush() }
            "stdout", "stderr" -> {
                val output = if (args.single() == "stderr") System.err else System.out
                output.write(ByteArray(4096) { 120 }); output.flush()
                Thread.sleep(20_000)
            }
            "block" -> Thread.sleep(20_000)
        }
    }
}
