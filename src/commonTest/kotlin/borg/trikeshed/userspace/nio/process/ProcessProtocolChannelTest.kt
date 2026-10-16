package borg.trikeshed.userspace.nio.process

import borg.trikeshed.userspace.nio.channels.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessProtocolChannelTest {
    class Pipes(override val separateProtocol: Boolean = true) : ProcessProtocolPipes {
        override val processId = 17L
        override var isAlive = true
        override val terminalOutputs = setOf(ProcessOutput.Stdout, ProcessOutput.Stderr)
        val protocol = Channel<ByteArray>(16)
        val stdout = Channel<ByteArray>(16)
        val stderr = Channel<ByteArray>(16)
        val writes = mutableListOf<String>()
        val input = mutableListOf<String>()
        val exit = CompletableDeferred<Int>()
        var writeGate: Deferred<Unit>? = null
        var inputGate: Deferred<Unit>? = null
        var closed = false
        var inputClosed = false
        var revoked = false
        override suspend fun readProtocol(bytes: ByteArray) = read(protocol, bytes)
        override suspend fun read(output: ProcessOutput, bytes: ByteArray) =
            read(if (output == ProcessOutput.Stdout) stdout else stderr, bytes)
        private suspend fun read(source: Channel<ByteArray>, bytes: ByteArray): Int {
            val next = source.receiveCatching().getOrNull() ?: return -1
            next.copyInto(bytes)
            return next.size
        }
        override suspend fun writeProtocol(bytes: ByteArray) { writeGate?.await(); writes.add(bytes.decodeToString()) }
        override suspend fun write(bytes: ByteArray) { inputGate?.await(); input.add(bytes.decodeToString()) }
        override suspend fun closeInput() { inputClosed = true }
        override suspend fun closeProtocolInput() {
            protocol.close(); stdout.close(); stderr.close(); isAlive = false; exit.complete(0)
        }
        override suspend fun awaitExit() = exit.await()
        override suspend fun closeProtocol() { protocol.cancel() }
        override suspend fun revoke(graceMillis: Long) { revoked = true; closeProtocolInput() }
        override suspend fun close() { closed = true; if (isAlive) revoke(0) }
    }

    @Test fun fragmentedFramesMultiplexCallbacksAndTerminalBytesBeforeAnyReply() = runTest {
        val parent = SupervisorJob()
        val pipes = Pipes()
        val frames = mutableListOf<String>()
        val terminal = mutableListOf<Pair<ProcessOutput, String>>()
        var ended = false
        val channel = ProcessProtocolChannel(coroutineContext + parent, pipes,
            onLine = {
                assertSame(this, currentCoroutineContext()[ProcessProtocolChannel])
                frames.add(it)
                if (it == "host λ🙂") send("host-result")
            },
            onTerminal = { output, bytes -> terminal.add(output to bytes.decodeToString()) },
            onEnd = { ended = true })
        for (byte in "host λ🙂\r\nreply\n".encodeToByteArray()) pipes.protocol.send(byteArrayOf(byte))
        pipes.stdout.send("native-out".encodeToByteArray())
        pipes.stderr.send("native-err".encodeToByteArray())
        runCurrent()
        assertEquals(listOf("host λ🙂", "reply"), frames)
        assertEquals(listOf("host-result\n"), pipes.writes)
        assertEquals(setOf(ProcessOutput.Stdout to "native-out", ProcessOutput.Stderr to "native-err"), terminal.toSet())
        channel.drain()
        assertTrue(ended && pipes.closed)
        assertFalse(pipes.revoked, "Normal drain awaits exit; it must not kill the child")
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test fun blockedTerminalInputCannotBlockProtocolRepliesAndClosesAfterAdmittedInput() = runTest {
        val pipes = Pipes()
        val release = CompletableDeferred<Unit>()
        pipes.inputGate = release
        val channel = ProcessProtocolChannel(coroutineContext, pipes, onLine = {})
        val terminal = async { channel.writeInput("input".encodeToByteArray()) }
        runCurrent()
        channel.send("callback")
        assertFalse(terminal.isCompleted)
        assertEquals(listOf("callback\n"), pipes.writes)
        val eof = async { channel.closeInput() }
        runCurrent()
        assertFalse(eof.isCompleted)
        release.complete(Unit)
        terminal.await(); eof.await()
        assertEquals(listOf("input"), pipes.input)
        assertTrue(pipes.inputClosed)
        channel.send("close") // Terminal EOF is not protocol EOF.
        channel.drain()
    }

    @Test fun stdioDrainDoesNotCloseTheProtocolThroughTheTerminalWriter() = runTest {
        val pipes = Pipes(separateProtocol = false)
        val release = CompletableDeferred<Unit>()
        pipes.writeGate = release
        val channel = ProcessProtocolChannel(coroutineContext, pipes, onLine = {})
        val sender = async { channel.send("admitted") }
        runCurrent()
        val drain = async { channel.drain() }
        runCurrent()
        assertFalse(pipes.inputClosed, "Physical stdin is also the protocol: only its writer may close it")
        assertFalse(drain.isCompleted)
        release.complete(Unit)
        sender.await(); drain.await()
        assertEquals(listOf("admitted\n"), pipes.writes)
        assertFalse(pipes.inputClosed)
    }

    @Test fun existingLargeAndUnterminatedFramesRetainTheirContents() = runTest {
        val pipes = Pipes()
        val frames = mutableListOf<String>()
        val channel = ProcessProtocolChannel(coroutineContext, pipes, onLine = { frames.add(it) })
        val source = "λ".repeat(600_000) // Existing VM frames have no one-MiB response restriction.
        val bytes = (source + "\r\nlast").encodeToByteArray()
        for (offset in bytes.indices step 4_093) {
            pipes.protocol.send(bytes.copyOfRange(offset, minOf(bytes.size, offset + 4_093)))
        }
        channel.drain()
        assertEquals(listOf(source, "last"), frames)
        assertFalse(pipes.revoked)
    }

    @Test fun cancelledSendAndDrainWaitersStillJoinAdmittedWritesAndTerminalDelivery() = runTest {
        val parent = SupervisorJob()
        val pipes = Pipes()
        val write = CompletableDeferred<Unit>()
        val sink = CompletableDeferred<Unit>()
        pipes.writeGate = write
        var delivered = false
        val channel = ProcessProtocolChannel(coroutineContext + parent, pipes, onLine = {},
            onTerminal = { _, _ -> sink.await(); delivered = true })
        pipes.stdout.send(byteArrayOf(1))
        val sender = launch { channel.send("admitted") }
        runCurrent()
        sender.cancelAndJoin()
        val drain = launch(start = CoroutineStart.UNDISPATCHED) { channel.drain() }
        drain.cancel()
        runCurrent()
        assertFalse(drain.isCompleted)
        write.complete(Unit)
        runCurrent()
        assertEquals(listOf("admitted\n"), pipes.writes)
        assertFalse(drain.isCompleted)
        sink.complete(Unit)
        drain.join()
        assertTrue(delivered && pipes.closed)
        channel.drain()
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test fun transportFailureSettlesQueuedWritesAndReapsUnderTheOwner() = runTest {
        val parent = SupervisorJob()
        val pipes = Pipes()
        val gate = CompletableDeferred<Unit>()
        pipes.writeGate = gate
        val channel = ProcessProtocolChannel(coroutineContext + parent, pipes, capacity = 1,
            onLine = { error("decode failed") })
        val senders = (1..4).map { async { runCatching { channel.send("request-$it") } } }
        runCurrent()
        pipes.protocol.send("bad\n".encodeToByteArray())
        runCurrent()
        assertTrue(senders.awaitAll().all { it.isFailure })
        assertEquals("decode failed", assertFailsWith<IllegalStateException> { channel.drain() }.message)
        assertTrue(pipes.closed && pipes.revoked)
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test fun parentCancellationBeforeOrAfterDispatchReleasesThePipes() = runTest {
        for (dispatch in listOf(false, true)) {
            val parent = SupervisorJob()
            val pipes = Pipes()
            val channel = ProcessProtocolChannel(coroutineContext + parent, pipes, onLine = {})
            if (dispatch) runCurrent()
            parent.cancel()
            assertFailsWith<CancellationException> { channel.drain() }
            parent.join()
            assertTrue(pipes.closed && pipes.revoked)
            assertTrue(parent.children.none())
        }
    }
}
