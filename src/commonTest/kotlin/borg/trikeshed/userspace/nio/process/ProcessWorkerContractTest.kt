package borg.trikeshed.userspace.nio.process

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ProcessWorkerContractTest {

    @Test
    fun spawnEchoReturnsStdout() = runBlocking {
        // verifies: ProcessSpec("/bin/echo", listOf("hello")) → ProcessResult(exitCode=0, stdout="hello\n".encodeToByteArray(), stderr=empty)
        val cap = ProcessCapability("test", setOf("echo"))
        val worker = ProcessWorkerFactory.create(cap)
        val spec = ProcessSpec("/bin/echo", listOf("hello"))
        val result = worker.spawn(spec)
        assertEquals(0, result.exitCode)
        assertEquals("hello\n", result.stdout.decodeToString())
        assertTrue(result.stderr.isEmpty())
    }

    @Test
    fun spawnFailingCommandReturnsNonZeroExit() = runBlocking {
        // verifies: ProcessSpec("/bin/false") → exitCode != 0
        val cap = ProcessCapability("test", setOf("false"))
        val worker = ProcessWorkerFactory.create(cap)
        val spec = ProcessSpec("/bin/false")
        val result = worker.spawn(spec)
        assertNotEquals(0, result.exitCode)
    }

    @Test
    fun spawnTimesOut() = runBlocking {
        // verifies: ProcessSpec("/bin/sleep", listOf("10"), timeoutMs = 100) → throws RuntimeException("process timed out after 100ms")
        val cap = ProcessCapability("test", setOf("sleep"))
        val worker = ProcessWorkerFactory.create(cap)
        val spec = ProcessSpec("/bin/sleep", listOf("10"), timeoutMs = 100)

        val ex = assertFailsWith<RuntimeException> {
            worker.spawn(spec)
        }
        assertEquals("process timed out after 100ms", ex.message)
    }

    @Test
    fun securityRejectsCommandNotInAllowedList() = runBlocking {
        // verifies: capability allows only {"echo"}; spec command /bin/ls → SecurityException("command 'ls' not in allowedCommands")
        val cap = ProcessCapability("test", setOf("echo"))
        val worker = ProcessWorkerFactory.create(cap)
        val spec = ProcessSpec("/bin/ls")

        val ex = assertFailsWith<SecurityException> {
            worker.spawn(spec)
        }
        assertEquals("command 'ls' not in allowedCommands", ex.message)
    }

    @Test
    fun rejectsBlankCommand() {
        // verifies: ProcessSpec("") throws from init
        assertFailsWith<IllegalArgumentException> {
            ProcessSpec("")
        }
    }

    @Test
    fun rejectsNegativeTimeout() {
        // verifies: ProcessSpec("/bin/echo", timeoutMs = -1) throws
        assertFailsWith<IllegalArgumentException> {
            ProcessSpec("/bin/echo", timeoutMs = -1)
        }
    }

    @Test
    fun stdoutRespectsMaxBytes() = runBlocking {
        // verifies: capability with maxStdoutBytes = 5 → result.stdout.size <= 5
        val cap = ProcessCapability("test", setOf("echo"), maxStdoutBytes = 5)
        val worker = ProcessWorkerFactory.create(cap)
        val spec = ProcessSpec("/bin/echo", listOf("hello world"))
        val result = worker.spawn(spec)
        assertTrue(result.stdout.size <= 5)
    }

    @Test
    fun processResultEqualsByContent() {
        // verifies: two ProcessResults with same fields → equal
        val r1 = ProcessResult(0, byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        val r2 = ProcessResult(0, byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        assertEquals(r1, r2)
        assertEquals(r1.hashCode(), r2.hashCode())
    }

    @Test
    fun processResultNotEqualWhenExitDiffers() {
        // verifies: same stdout, different exit → not equal
        val r1 = ProcessResult(0, byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        val r2 = ProcessResult(1, byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        assertNotEquals(r1, r2)
    }

    @Test
    fun envVariablesArePassed() = runBlocking {
        // verifies: ProcessSpec("/bin/sh", listOf("-c", "echo $FOO"), env = mapOf("FOO" to "bar")) → stdout contains "bar"
        val cap = ProcessCapability("test", setOf("sh"))
        val worker = ProcessWorkerFactory.create(cap)
        val spec = ProcessSpec(
            "/bin/sh",
            listOf("-c", "echo \$FOO"),
            env = mapOf("FOO" to "bar")
        )
        val result = worker.spawn(spec)
        assertTrue(result.stdout.decodeToString().contains("bar"))
    }
}
