package borg.trikeshed.userspace.nio.process

import borg.trikeshed.userspace.nio.channels.spi.*
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessFrameChannelTest {
    class Stream : ProcessProtocolStream {
        val incoming = Channel<ByteArray>(16)
        val writes = mutableListOf<String>()
        var writeGate: Deferred<Unit>? = null
        var outputClosed = false
        var closed = false
        override suspend fun readProtocol(bytes: ByteArray): Int {
            val next = incoming.receiveCatching().getOrNull() ?: return -1
            next.copyInto(bytes)
            return next.size
        }
        override suspend fun writeProtocol(bytes: ByteArray) {
            writeGate?.await()
            writes.add(bytes.decodeToString())
        }
        override suspend fun closeProtocolInput() { outputClosed = true }
        override suspend fun closeProtocol() { closed = true; incoming.cancel() }
    }

    @Test fun childCloseReplyFollowsAdmittedWorkWhileCallbackRepliesRemainReadable() = runTest {
        val parent = SupervisorJob()
        val stream = Stream()
        val callback = CompletableDeferred<String>()
        var retired = false
        lateinit var frames: ProcessFrameChannel
        val operations = ProcessCalls(coroutineContext + parent, concurrency = 1) { response ->
            frames.send((response.b.getOrThrow() as Teleported.Str).v)
        }
        frames = ProcessFrameChannel(coroutineContext + parent, stream, onLine = { line ->
            assertSame(this, currentCoroutineContext()[ProcessFrameChannel])
            when (line) {
                "work" -> assertNotNull(operations.trySubmit(1, "work", null) {
                    send("host")
                    Teleported.Str(callback.await())
                })
                "close" -> assertNotNull(operations.finish(2, "close") {
                    retired = true
                    Teleported.Str("closed")
                })
                else -> callback.complete(line)
            }
        })
        val ended = async { frames.awaitClosed() }
        stream.incoming.send("work\n".encodeToByteArray())
        runCurrent()
        assertEquals(listOf("host\n"), stream.writes)
        stream.incoming.send("close\n".encodeToByteArray())
        runCurrent()
        assertFalse(retired)
        assertFalse(stream.outputClosed)
        stream.incoming.send("42\n".encodeToByteArray())
        runCurrent()
        assertEquals(listOf("host\n", "42\n", "closed\n"), stream.writes)
        assertTrue(retired)
        assertFalse(ended.isCompleted, "Sending the close reply must not invent parent EOF")
        stream.incoming.close()
        ended.await()
        operations.drain()
        assertTrue(stream.outputClosed && stream.closed)
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test fun constructionOutputDoesNotWaitForTheGuestRequestHandler() = runTest {
        val stream = Stream()
        val handler = CompletableDeferred<suspend (String) -> Unit>()
        val received = mutableListOf<String>()
        val frames = ProcessFrameChannel(coroutineContext, stream, onLine = { handler.await()(it) })
        stream.incoming.send("request\n".encodeToByteArray())
        runCurrent()
        frames.send("construction-output")
        assertEquals(listOf("construction-output\n"), stream.writes)
        assertTrue(received.isEmpty())
        handler.complete { received.add(it) }
        runCurrent()
        assertEquals(listOf("request"), received)
        frames.closeInput()
        assertTrue(stream.outputClosed)
        stream.incoming.send("final-reply".encodeToByteArray())
        stream.incoming.close()
        frames.awaitClosed()
        assertEquals(listOf("request", "final-reply"), received)
    }

    @Test fun drainFinishesAdmittedOutputAndWaitsForRealPeerEofEvenWhenWaiterIsCancelled() = runTest {
        val stream = Stream()
        val release = CompletableDeferred<Unit>()
        stream.writeGate = release
        val frames = ProcessFrameChannel(coroutineContext, stream, capacity = 1, onLine = {})
        val first = async { frames.send("first") }
        val second = async { frames.send("second") }
        runCurrent()
        first.cancel()
        val drain = launch { frames.drain() }
        runCurrent()
        drain.cancel()
        assertFalse(drain.isCompleted)
        release.complete(Unit)
        second.await()
        runCurrent()
        assertEquals(listOf("first\n", "second\n"), stream.writes)
        assertTrue(stream.outputClosed)
        assertFalse(drain.isCompleted)
        stream.incoming.close()
        drain.join()
        assertTrue(stream.closed)
    }

    @Test fun cancellationDuringConstructionJoinsTheReaderAndReleasesTheEndpoint() = runTest {
        for (cancelBeforeDispatch in listOf(false, true)) {
            val parent = SupervisorJob()
            val stream = Stream()
            val ready = CompletableDeferred<Unit>()
            if (cancelBeforeDispatch) parent.cancel()
            val frames = ProcessFrameChannel(coroutineContext + parent, stream, onLine = { ready.await() })
            if (!cancelBeforeDispatch) {
                stream.incoming.send("early-request\n".encodeToByteArray())
                runCurrent()
                parent.cancel()
            }
            assertFailsWith<CancellationException> { frames.awaitClosed() }
            parent.join()
            assertTrue(stream.closed)
            assertTrue(parent.children.none())
        }
    }
}
