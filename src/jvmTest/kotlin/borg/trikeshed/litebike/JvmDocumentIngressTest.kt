package borg.trikeshed.litebike

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class JvmDocumentIngressTest {
    @TempDir lateinit var directory: Path

    @Test fun documentFramesAreBoundedUntilTheHandlerFinishes(): Unit = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val finish = CompletableDeferred<Unit>()
        val route: RawRoute = { _, path, payload, _ ->
            if (path.substringBefore('?') != "/_project/test/putDocumentBatch") null
            else {
                if (payload.decodeToString().contains("X-Hold: yes")) {
                    entered.send(Unit)
                    finish.await()
                }
                WireHttpResponse(200, "ok", "text/plain")
            }
        }
        val server = JvmKanbanServer(rawRoutes = _l[route], stateDir = directory.toFile())
        val job = launch(Dispatchers.Default) { server.run(port, null) }
        val sockets = mutableListOf<Socket>()
        try {
            withContext(Dispatchers.IO) {
                val deadline = java.lang.System.nanoTime() + 20_000_000_000L
                while (true) {
                    val ready = runCatching { Socket("127.0.0.1", port).use {} }.isSuccess
                    if (ready) break
                    check(java.lang.System.nanoTime() < deadline) { "listener did not bind" }
                    Thread.sleep(20)
                }
                fun socket(): Socket = Socket("127.0.0.1", port).also { it.soTimeout = 5_000; sockets.add(it) }
                fun request(headers: String, body: String = ""): Socket = socket().also {
                    it.getOutputStream().write(("POST /_project/test/putDocumentBatch?intake=manual HTTP/1.1\r\nHost: localhost\r\n" + headers + "\r\n" + body).encodeToByteArray())
                }
                fun status(s: Socket): String = s.getInputStream().bufferedReader().readLine() ?: "closed"
                assertTrue(status(request("Content-Length: 1\r\nContent-Length: 1\r\n", "x")).contains(" 400 "))
                assertTrue(status(request("Transfer-Encoding: chunked\r\nContent-Length: 1\r\n", "x")).contains(" 400 "))
                assertTrue(status(request("Content-Length: 33557517\r\n")).contains(" 413 "))
                val fragment = socket()
                fragment.getOutputStream().write("POST /_project/test/put".encodeToByteArray())
                fragment.getOutputStream().flush()
                Thread.sleep(20)
                fragment.getOutputStream().write("DocumentBatch?intake=manual HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\nx".encodeToByteArray())
                assertTrue(status(fragment).contains(" 200 "))
                val longTarget = socket()
                longTarget.getOutputStream().write(("POST /_project/test/putDocumentBatch?" + "x".repeat(20_000)).encodeToByteArray())
                assertTrue(status(longTarget).contains(" 431 "))

                val accepted = mutableListOf<Socket>()
                repeat(4) { accepted.add(request("Content-Length: 1\r\nX-Hold: yes\r\n", "x")) }
                withTimeout(5_000) { repeat(4) { entered.receive() } }
                assertTrue(status(request("Content-Length: 1\r\n", "x")).contains(" 503 "))
                accepted.first().close()
                assertTrue(status(request("Content-Length: 1\r\n", "x")).contains(" 503 "),
                    "a disconnected peer must not release a handler's retained body")
                finish.complete(Unit)
                for (socket in accepted.drop(1)) assertTrue(status(socket).contains(" 200 "))
                val recoveryDeadline = java.lang.System.nanoTime() + 5_000_000_000L
                var recovered: String
                do {
                    val subsequent = request("Content-Length: 1\r\n", "x")
                    recovered = status(subsequent)
                    subsequent.close()
                    if (recovered.contains(" 503 ")) Thread.sleep(10)
                } while (recovered.contains(" 503 ") && java.lang.System.nanoTime() < recoveryDeadline)
                assertTrue(recovered.contains(" 200 "), "a completed handler must eventually return its permit: $recovered")
            }
        } finally {
            finish.complete(Unit)
            sockets.forEach { runCatching { it.close() } }
            job.cancelAndJoin()
        }
    }

    @Test fun discardedMessagesReleaseOnlyUndeliveredOwnership(): Unit = runBlocking {
        val slot = LitebikeListenerElement.ChannelWorkgroupSlot(borg.trikeshed.litebike.taxonomy.Protocol.Http)
        val taken = CompletableDeferred<Unit>()
        val queued = CompletableDeferred<Unit>()
        slot.offer(LitebikeListenerElement.ChannelMessage(1u, 1, ByteArray(1), completion = taken))
        assertSame(taken, slot.consume().completion)
        slot.offer(LitebikeListenerElement.ChannelMessage(1u, 2, ByteArray(1), completion = queued))
        slot.cancel()
        assertTrue(queued.isCompleted)
        assertFalse(taken.isCompleted, "a consumer retains its message until it finishes")
        taken.complete(Unit)
    }

    @Test fun aPermitWaitsForBothTheSenderAndReceiver(): Unit = runBlocking {
        for (receiverFirst in _l[true, false]) {
            val permits = Semaphore(1)
            assertTrue(permits.tryAcquire())
            val sender = Job()
            val done = CompletableDeferred<Unit>()
            val permit = DocumentPermit(permits, sender, done)
            if (receiverFirst) permit.completion.complete(Unit) else sender.complete()
            assertFalse(permits.tryAcquire(), "either side can still retain the request")
            assertEquals(receiverFirst, done.isCompleted)
            if (receiverFirst) sender.complete() else permit.completion.complete(Unit)
            assertTrue(permits.tryAcquire(), "both owners have finished")
            permits.release()
        }
    }

    @Test fun cancellationAfterDeliveryWaitsForBackpressuredSender(): Unit = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val listener = LitebikeListenerElement(parentJob = owner, maxBatch = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val key = object : CoroutineContext.Key<CoroutineContext.Element> {}
        listener.subscribe(object : AbstractCoroutineContextElement(key), LitebikeFanoutEventSink {
            override fun onLitebikeFanoutEvent(event: LitebikeFanoutEvent) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        })
        withContext(Dispatchers.Default) { listener.open() }
        val slot = listener.register(borg.trikeshed.litebike.taxonomy.Protocol.Http)
        var sender: Job? = null
        try {
            listener.accept(borg.trikeshed.litebike.taxonomy.Protocol.Http, ByteArray(1))
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            listener.accept(borg.trikeshed.litebike.taxonomy.Protocol.Http, ByteArray(1))

            val admission = Semaphore(1)
            assertTrue(admission.tryAcquire())
            val done = CompletableDeferred<Unit>()
            val active = scope.launch {
                val permit = DocumentPermit(admission, coroutineContext[Job]!!, done)
                listener.accept(borg.trikeshed.litebike.taxonomy.Protocol.Http, ByteArray(1), completion = permit.completion)
            }
            sender = active
            slot.consume()
            slot.consume()
            val message = withTimeout(5_000) { slot.consume() }
            val completion = assertNotNull(message.completion)
            active.cancel()
            completion.complete(Unit)
            assertTrue(done.isCompleted)
            assertFalse(admission.tryAcquire(), "the canceled sender is still waiting to publish its event")
            release.countDown()
            withTimeout(5_000) { active.join() }
            // Joining and invoking completion callbacks can resume on different threads.
            withTimeout(5_000) { while (!admission.tryAcquire()) delay(5) }
            admission.release()
        } finally {
            release.countDown()
            sender?.cancelAndJoin()
            listener.close()
            owner.cancelAndJoin()
        }
    }

    @Test fun slowReadsResetTheIdleDeadlineButNotTheAbsoluteLifetime(): Unit = runBlocking {
        suspend fun pair(block: suspend (Socket, AsynchronousSocketChannel) -> Unit) {
            AsynchronousServerSocketChannel.open().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0))
                Socket("127.0.0.1", (server.localAddress as InetSocketAddress).port).use { client ->
                    server.accept().get(5, TimeUnit.SECONDS).use { accepted -> block(client, accepted) }
                }
            }
        }
        fun header(length: Int) = "POST /_project/test/putDocumentBatch HTTP/1.1\r\nContent-Length: $length\r\n\r\n".encodeToByteArray()
        pair { client, accepted ->
            val reading = async { JvmLitebikeBindAdapter.readDocument(accepted, header(3), idleMs = 1_000) }
            client.getOutputStream().write('a'.code)
            delay(600)
            client.getOutputStream().write('b'.code)
            delay(600)
            client.getOutputStream().write('c'.code)
            assertTrue(reading.await().decodeToString().endsWith("abc"))
        }
        pair { _, accepted ->
            val reading = async { JvmLitebikeBindAdapter.readDocument(accepted, header(1), idleMs = 150) }
            assertFailsWith<TimeoutCancellationException> { withTimeout(2_000) { reading.await() } }
        }
        pair { client, accepted ->
            val reading = async {
                withTimeout(500) { JvmLitebikeBindAdapter.readDocument(accepted, header(20), idleMs = 1_000) }
            }
            client.getOutputStream().write('a'.code)
            delay(200)
            client.getOutputStream().write('b'.code)
            delay(200)
            client.getOutputStream().write('c'.code)
            assertFailsWith<TimeoutCancellationException> { reading.await() }
        }
    }
}
