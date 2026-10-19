package borg.trikeshed.userspace.nio.process

import borg.trikeshed.userspace.nio.channels.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessCaptureChannelTest {
    private class Pipes : ProcessProtocolPipes {
        val output = Channel<ByteArray>(1)
        val reading = CompletableDeferred<Unit>()
        val inputClosed = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Int>()
        var input: ByteArray? = null
        var revocations = 0
        var closeFailure: Throwable? = null
        override val processId: Long get() = 1L
        override var isAlive: Boolean = true
        override val separateProtocol: Boolean get() = false
        override val terminalOutputs: Set<ProcessOutput> get() = emptySet()

        override suspend fun write(bytes: ByteArray) {
            reading.await() // Writing before servicing output would deadlock this duplex process.
            input = bytes.copyOf()
        }
        override suspend fun read(output: ProcessOutput, bytes: ByteArray): Int {
            check(output == ProcessOutput.Stdout)
            reading.complete(Unit)
            val received = this.output.receiveCatching()
            received.exceptionOrNull()?.let { throw it }
            val next = received.getOrNull() ?: return -1
            check(next.size <= bytes.size)
            next.copyInto(bytes)
            return next.size
        }
        override suspend fun closeInput() { inputClosed.complete(Unit) }
        override suspend fun awaitExit(): Int = exit.await()
        fun finish(code: Int) { isAlive = false; output.close(); exit.complete(code) }
        override suspend fun revoke(graceMillis: Long) { revocations++; finish(-1) }
        override suspend fun close() {
            if (isAlive) revoke(0)
            closed.complete(Unit)
            closeFailure?.let { throw it }
        }
        override suspend fun readProtocol(bytes: ByteArray): Int = error("Finite capture has no guest protocol")
        override suspend fun writeProtocol(bytes: ByteArray): Unit = error("Finite capture has no guest protocol")
        override suspend fun closeProtocolInput(): Unit = error("Finite capture has no guest protocol")
        override suspend fun closeProtocol(): Unit = error("Finite capture has no guest protocol")
    }

    @Test fun drainJoinsInputOutputAndExitWithoutRevokingAdmittedWork() = runTest {
        val parent = SupervisorJob(coroutineContext[Job])
        val pipes = Pipes()
        val input = ByteArray(262_144) { (it % 251).toByte() }
        val expectedInput = input.copyOf()
        val channel = ProcessCaptureChannel(coroutineContext + parent, pipes, input, maxBytes = 13, timeoutMillis = 10_000, capacity = 1)
        input.fill(0)
        val producer = launch {
            repeat(40) { pipes.output.send(ByteArray(4096) { (it % 127).toByte() }) }
        }
        val drained = async { channel.drain() }
        runCurrent()
        producer.join()
        pipes.inputClosed.await()
        assertFalse(drained.isCompleted)
        assertFalse(pipes.closed.isCompleted)
        assertContentEquals(expectedInput, pipes.input)
        pipes.finish(23)
        drained.await()
        val result = channel.await()
        assertEquals(23, result.exit)
        assertFalse(result.timedOut)
        assertEquals(40L * 4096L, result.total)
        assertContentEquals(ByteArray(13) { it.toByte() }, result.bytes)
        assertEquals(result.total - 13, result.dropped)
        assertEquals(0, pipes.revocations)
        assertTrue(pipes.closed.isCompleted)
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test fun deadlineRevokesThenDrainsAllDeliveredOutputBeforeReturning() = runTest {
        val pipes = Pipes()
        val channel = ProcessCaptureChannel(coroutineContext, pipes, maxBytes = 5, timeoutMillis = 100, capacity = 1)
        pipes.output.send("first".encodeToByteArray())
        pipes.output.send("second".encodeToByteArray())
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        val result = channel.await()
        assertEquals(-1, result.exit)
        assertTrue(result.timedOut)
        assertEquals(11L, result.total)
        assertEquals("first", result.text())
        assertEquals(6L, result.dropped)
        assertEquals(1, pipes.revocations)
        assertTrue(pipes.closed.isCompleted)
    }

    @Test fun cancellingTheWaiterRevokesAndJoinsItsProcessStages() = runTest {
        val parent = SupervisorJob(coroutineContext[Job])
        val pipes = Pipes()
        val channel = ProcessCaptureChannel(coroutineContext + parent, pipes, maxBytes = 10, timeoutMillis = 10_000)
        val waiting = async { channel.await() }
        pipes.reading.await()
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
        assertFalse(pipes.isAlive)
        assertTrue(pipes.closed.isCompleted)
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test fun failedOutputCannotBecomeACompletedCaptureAndStillReapsTheOwner() = runTest {
        val parent = SupervisorJob(coroutineContext[Job])
        val pipes = Pipes()
        val channel = ProcessCaptureChannel(coroutineContext + parent, pipes, maxBytes = 10, timeoutMillis = 10_000)
        pipes.output.close(IllegalStateException("broken output"))
        val failure = assertFailsWith<IllegalStateException> { channel.await() }
        assertEquals("broken output", failure.message)
        assertFalse(pipes.isAlive)
        assertTrue(pipes.closed.isCompleted)
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test fun aPipeCancellationCannotProduceASuccessfulCapture() = runTest {
        val parent = SupervisorJob(coroutineContext[Job])
        val pipes = Pipes()
        val channel = ProcessCaptureChannel(coroutineContext + parent, pipes, timeoutMillis = 10_000)
        pipes.output.close(CancellationException("output operation cancelled"))
        pipes.finish(0)
        val failure = assertFailsWith<CancellationException> { channel.await() }
        assertEquals("output operation cancelled", failure.message)
        assertTrue(pipes.closed.isCompleted)
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test fun cleanupFailureSettlesBothAwaitAndDrainAfterEveryStageHasJoined() = runTest {
        val parent = SupervisorJob(coroutineContext[Job])
        val pipes = Pipes().also { it.closeFailure = IllegalStateException("close failed") }
        val channel = ProcessCaptureChannel(coroutineContext + parent, pipes, timeoutMillis = 10_000)
        pipes.finish(0)
        assertEquals("close failed", assertFailsWith<IllegalStateException> { channel.await() }.message)
        assertEquals("close failed", assertFailsWith<IllegalStateException> { channel.drain() }.message)
        assertTrue(pipes.closed.isCompleted)
        assertTrue(parent.children.none())
        parent.complete()
    }

}
