package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.channels.spi.*
import borg.trikeshed.userspace.nio.process.ProcessLineChannel
import kotlinx.coroutines.*
import kotlin.test.*

class ProcessLineChannelTest {
    open class Operations : ProcessOperations {
        lateinit var child: JvmProcessPipes
        override suspend fun exec(command: String, args: List<String>, stdin: ByteArray?, env: Map<String, String>) =
            JvmProcessOperations().exec(command, args, stdin, env)
        override suspend fun open(command: String, args: List<String>, env: Map<String, String>): ProcessPipes =
            (JvmProcessOperations().open(command, args, env) as JvmProcessPipes).also { child = it }
    }

    @Test fun concurrentRequestsReuseOneChildAndDrainAllPipes() = runBlocking {
        val ops = Operations()
        val channel = ProcessLineChannel.open(CoroutineScope(coroutineContext + ops), "/bin/cat", capacity = 2)
        try {
            val input = List(24) { "row-$it:λ🙂" }
            val output = input.map { async { channel.exchange(it) } }.awaitAll()
            assertEquals(input, output)
            assertTrue(ops.child.process.isAlive)
        } finally { channel.drain() }
        assertFalse(ops.child.process.isAlive)
        assertEquals(0, ops.child.process.exitValue())
        assertFailsWith<IllegalStateException> { channel.exchange("after-drain") }
        channel.drain()
    }

    @Test fun stderrCannotBlockTheResponseAndItsTailIsBounded() = runBlocking {
        val ops = Operations()
        val program = "i=0; while [ \"${'$'}i\" -lt 20000 ]; do printf xxxxxxxxxx >&2; i=${'$'}((i+1)); done; /bin/cat"
        val channel = ProcessLineChannel.open(CoroutineScope(coroutineContext + ops), "/bin/sh",
            listOf("-c", program), timeoutMs = 10_000)
        try { assertEquals("answer", channel.exchange("answer")) } finally { channel.drain() }
        assertFalse(ops.child.process.isAlive)
    }

    @Test fun oversizedReplyFailsThePendingRequestAndReapsTheChild() = runBlocking {
        val ops = Operations()
        val channel = ProcessLineChannel.open(CoroutineScope(coroutineContext + ops), "/bin/sh",
            listOf("-c", "read line; printf '123456789\\n'; /bin/cat"), frameBytes = 8)
        assertFailsWith<IllegalStateException> { channel.exchange("x") }
        assertFailsWith<IllegalStateException> { channel.drain() }
        assertFalse(ops.child.process.isAlive)
    }

    @Test fun nonzeroExitIsReportedEvenAfterAValidResponse() = runBlocking {
        val ops = Operations()
        val channel = ProcessLineChannel.open(CoroutineScope(coroutineContext + ops), "/bin/sh",
            listOf("-c", "read line; printf 'ok\\n'; printf 'failure-detail' >&2; exit 7"))
        assertEquals("ok", channel.exchange("x"))
        val failure = assertFailsWith<IllegalStateException> { channel.drain() }
        assertContains(failure.message.orEmpty(), "exited 7")
        assertContains(failure.message.orEmpty(), "failure-detail")
        assertFalse(ops.child.process.isAlive)
    }

    @Test fun cancelledOwnerJoinsBlockedNativeReadsAndReapsTheChild() = runBlocking {
        val ops = Operations()
        val owner = SupervisorJob(coroutineContext[Job])
        val channel = ProcessLineChannel.open(CoroutineScope(coroutineContext + owner + ops), "/bin/cat")
        owner.cancel()
        assertFailsWith<CancellationException> { channel.drain() }
        owner.join()
        assertTrue(owner.isCompleted)
        assertFalse(ops.child.process.isAlive)
    }

    @Test fun aCancelledCallerDoesNotDiscardAnAdmittedRequest() = runBlocking {
        val requestWritten = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val pipes = object : ProcessPipes {
            var sent = false
            var eof = false
            override suspend fun write(bytes: ByteArray) { requestWritten.complete(Unit) }
            override suspend fun read(output: ProcessOutput, bytes: ByteArray): Int {
                if (output == ProcessOutput.Stderr) { closed.await(); return -1 }
                if (eof) return -1
                release.await()
                check(!sent); sent = true
                "accepted\n".encodeToByteArray().copyInto(bytes)
                return 9
            }
            override suspend fun closeInput() { eof = true; closed.complete(Unit) }
            override suspend fun awaitExit() = 0
            override suspend fun close() { closed.complete(Unit) }
        }
        val ops = object : Operations() {
            override suspend fun open(command: String, args: List<String>, env: Map<String, String>) = pipes
        }
        val channel = ProcessLineChannel.open(CoroutineScope(coroutineContext + ops), "owned-test")
        val caller = async { channel.exchange("work") }
        requestWritten.await()
        caller.cancelAndJoin()
        val drain = async { channel.drain() }
        yield()
        assertFalse(drain.isCompleted)
        release.complete(Unit)
        drain.await()
        assertTrue(closed.isCompleted)
    }
}
