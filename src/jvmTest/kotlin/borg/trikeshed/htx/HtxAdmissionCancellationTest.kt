package borg.trikeshed.htx

import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HtxAdmissionCancellationTest {
    @Test fun canceledSuspendedSendSettlesWithoutDispatch(): Unit = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val owner = SupervisorJob(coroutineContext[Job])
        val network = HtxReactorElement(JvmChannelOperations(), parentJob = owner)
        val server = ServerSocket(0, 32, InetAddress.getLoopbackAddress())
        val blocked = CompletableDeferred<Unit>()
        val unblock = CountDownLatch(1)
        try {
            withContext(dispatcher + owner) { network.open() }
            val blocker = CoroutineScope(dispatcher + owner).launch {
                blocked.complete(Unit)
                unblock.await()
            }
            withTimeout(5_000) { blocked.await() }
            val served = CoroutineScope(Dispatchers.IO + owner).launch {
                repeat(17) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
                    }
                }
            }
            val observations = AtomicInteger()
            val observer = HtxResponseObserver { observations.incrementAndGet() }
            val request = parseHtxRequest("http://127.0.0.1:${server.localPort}/").copy(timeoutMs = 5_000)
            val accepted = (0 until 17).map {
                async(start = CoroutineStart.UNDISPATCHED) {
                    withContext(observer) { network.exchange(HtxExchangeState(), request) }
                }
            }
            val canceled = async(start = CoroutineStart.UNDISPATCHED) {
                withContext(observer) { network.exchange(HtxExchangeState(), request) }
            }
            canceled.cancel()
            withTimeout(5_000) { canceled.join() }
            assertEquals(0, observations.get())
            unblock.countDown()
            withTimeout(20_000) { accepted.awaitAll(); served.join(); blocker.join() }
            assertEquals(17, observations.get())
        } finally {
            unblock.countDown()
            server.close()
            network.drain()
            owner.complete()
            owner.join()
            dispatcher.close()
        }
    }
}
