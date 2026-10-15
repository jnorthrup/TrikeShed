package borg.trikeshed.userspace.nio.process

import borg.trikeshed.userspace.nio.channels.spi.*
import borg.trikeshed.vm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessVmChannelTest {
    class Pipes : ProcessProtocolPipes {
        override val processId = 41L
        override var isAlive = true
        override val separateProtocol = false
        override val terminalOutputs = emptySet<ProcessOutput>()
        val sent = Channel<Teleported.Obj>(32)
        val incoming = Channel<ByteArray>(32)
        val exit = CompletableDeferred<Int>()
        var revoked = false
        var released = false
        var outputClosed = false
        override suspend fun readProtocol(bytes: ByteArray): Int {
            val next = incoming.receiveCatching().getOrNull() ?: return -1
            next.copyInto(bytes)
            return next.size
        }
        override suspend fun writeProtocol(bytes: ByteArray) { sent.send(SubVmProtocol.decode(bytes.decodeToString().trimEnd())) }
        override suspend fun closeProtocolInput() { outputClosed = true; eof() }
        override suspend fun closeProtocol() { incoming.cancel() }
        override suspend fun write(bytes: ByteArray) { error("No separate terminal input") }
        override suspend fun read(output: ProcessOutput, bytes: ByteArray): Int = error("No separate terminal output")
        override suspend fun closeInput() { error("Terminal EOF must be a protocol frame") }
        override suspend fun awaitExit() = exit.await()
        override suspend fun revoke(graceMillis: Long) { revoked = true; eof() }
        override suspend fun close() { released = true; if (isAlive) revoke(0) }
        fun eof() { isAlive = false; incoming.close(); exit.complete(0) }
        suspend fun emit(message: Teleported.Obj) { incoming.send((SubVmProtocol.encode(message) + "\n").encodeToByteArray()) }
        suspend fun answer(request: Teleported.Obj, value: Teleported = Teleported.Null) =
            emit(Teleported.obj("id" to request.int("id"), "ok" to true, "value" to value))
    }

    @Test fun drainWaitsForAdmittedResultThenCloseAcknowledgementWithoutRevocation() = runTest {
        val parent = SupervisorJob()
        val pipes = Pipes()
        val vm = ProcessVmChannel(coroutineContext + parent, pipes, invokeHost = { it() })
        val evaluation = async { vm.eval("pending") }
        val request = pipes.sent.receive()
        assertEquals("eval", request.str("op"))
        val closing = async { vm.drain() }
        assertEquals("stdin-close", pipes.sent.receive().str("op"))
        runCurrent()
        assertFalse(closing.isCompleted)
        assertTrue(pipes.sent.tryReceive().isFailure)
        assertFailsWith<IllegalStateException> { vm.eval("not admitted") }
        pipes.answer(request, Teleported.Num(42))
        assertEquals(Teleported.Num(42), evaluation.await())
        val close = pipes.sent.receive()
        assertEquals("close", close.str("op"))
        runCurrent()
        assertFalse(closing.isCompleted)
        assertFalse(pipes.outputClosed)
        pipes.answer(close)
        closing.await()
        vm.drain()
        assertTrue(pipes.outputClosed && pipes.released)
        assertFalse(pipes.revoked)
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test fun registeredHostCallbackCanReplyWhileDrainWaitsForItsGuestResult() = runTest {
        val parent = SupervisorJob()
        val pipes = Pipes()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val vm = ProcessVmChannel(coroutineContext + parent, pipes, invokeHost = {
            entered.complete(Unit)
            release.await()
            it()
        })
        val registration = async { vm.delegate("echo") { it.single() } }
        val delegate = pipes.sent.receive()
        assertEquals("delegate", delegate.str("op"), "Registration must reach the child, not only a parent map")
        assertEquals("echo", delegate.str("name"))
        pipes.answer(delegate)
        registration.await()
        val evaluation = async { vm.eval("host.call('echo', 42)") }
        val request = pipes.sent.receive()
        pipes.emit(Teleported.obj("id" to 97, "op" to "host", "name" to "echo", "args" to listOf(Teleported.Num(42))))
        entered.await()
        val closing = async { vm.drain() }
        assertEquals("stdin-close", pipes.sent.receive().str("op"))
        runCurrent()
        assertFalse(closing.isCompleted)
        release.complete(Unit)
        val callback = pipes.sent.receive()
        assertEquals(97, callback.int("id"))
        assertEquals(Teleported.Num(42), callback.field("value"))
        pipes.answer(request, callback.field("value")!!)
        assertEquals(Teleported.Num(42), evaluation.await())
        pipes.answer(pipes.sent.receive())
        closing.await()
        assertEquals(1L, vm.stats().hostCalls)
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test fun cancellationOfRequestAndDrainWaitersDoesNotDiscardAdmittedEffects() = runTest {
        val pipes = Pipes()
        val vm = ProcessVmChannel(coroutineContext, pipes, invokeHost = { it() }, capacity = 1)
        val first = async { vm.eval("first") }
        val request = pipes.sent.receive()
        val second = async { vm.eval("second") }
        runCurrent()
        first.cancel()
        val closing = launch { vm.drain() }
        assertEquals("stdin-close", pipes.sent.receive().str("op"))
        closing.cancel()
        runCurrent()
        assertFalse(closing.isCompleted)
        pipes.answer(request)
        val next = pipes.sent.receive()
        assertEquals("second", next.str("source"))
        pipes.answer(next, Teleported.Num(2))
        assertEquals(Teleported.Num(2), second.await())
        val close = pipes.sent.receive()
        assertEquals("close", close.str("op"))
        pipes.answer(close)
        closing.join()
        assertTrue(pipes.released)
        assertFalse(pipes.revoked)
    }

    @Test fun revocationBypassesBlockedGuestAndSettlesQueuedCallers() = runTest {
        val pipes = Pipes()
        val vm = ProcessVmChannel(coroutineContext, pipes, invokeHost = { it() })
        val first = async { runCatching { vm.eval("blocked") } }
        assertEquals("eval", pipes.sent.receive().str("op"))
        val second = async { runCatching { vm.eval("queued") } }
        runCurrent()
        assertTrue(vm.interrupt())
        assertTrue(first.await().isFailure)
        assertTrue(second.await().isFailure)
        vm.drain()
        assertTrue(pipes.revoked && pipes.released)
        assertFalse(vm.isAlive)
        assertFalse(vm.interrupt())
        assertEquals(1L, vm.stats().interrupted)
    }

    @Test fun unexpectedPeerEofSettlesActiveAndQueuedWorkAndJoinsOwnership() = runTest {
        val parent = SupervisorJob()
        val pipes = Pipes()
        val vm = ProcessVmChannel(coroutineContext + parent, pipes, invokeHost = { it() })
        val first = async { runCatching { vm.eval("lost") } }
        pipes.sent.receive()
        val second = async { runCatching { vm.call("queued", emptyList()) } }
        runCurrent()
        pipes.eof()
        assertIs<VmGuestException>(first.await().exceptionOrNull())
        assertTrue(second.await().isFailure)
        vm.drain()
        assertTrue(pipes.released)
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test fun rejectedCloseIsReportedAndRevokesTheUnclosedChild() = runTest {
        val parent = SupervisorJob()
        val pipes = Pipes()
        val vm = ProcessVmChannel(coroutineContext + parent, pipes, invokeHost = { it() })
        val closing = async { runCatching { vm.drain() } }
        assertEquals("stdin-close", pipes.sent.receive().str("op"))
        val close = pipes.sent.receive()
        assertEquals("close", close.str("op"))
        pipes.emit(Teleported.obj("id" to close.int("id"), "ok" to false, "kind" to "GUEST_ERROR", "error" to "close refused"))
        val failure = assertIs<VmGuestException>(closing.await().exceptionOrNull())
        assertTrue(failure.message.orEmpty().contains("close refused"))
        assertTrue(pipes.revoked && pipes.released)
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test fun parentCancellationSettlesAdmittedRequestsAndReapsUnderTheSameOwner() = runTest {
        val parent = SupervisorJob()
        val pipes = Pipes()
        val vm = ProcessVmChannel(coroutineContext + parent, pipes, invokeHost = { it() })
        val evaluation = async { runCatching { vm.eval("pending") } }
        pipes.sent.receive()
        parent.cancel()
        assertTrue(evaluation.await().isFailure)
        assertFailsWith<CancellationException> { vm.drain() }
        parent.join()
        assertTrue(pipes.revoked && pipes.released)
        assertTrue(parent.children.none())
    }
}
