package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.htx.HtxExchangeLifecycle
import borg.trikeshed.htx.HtxExchangeState
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.htx.state
import borg.trikeshed.lib.asString
import borg.trikeshed.userspace.nio.ByteBuffer
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class JvmChannelOperationsContinuityTest {

    @Test
    fun queuedConnectPreservesTheImmediateWrite() {
        val payload = "continuity".encodeToByteArray()
        val server = ServerSocketChannel.open().apply {
            bind(InetSocketAddress("127.0.0.1", 0))
        }
        val received = ByteArray(payload.size)
        val serverDone = CountDownLatch(1)
        val serverThread = thread(name = "htx-loopback-writer", isDaemon = true) {
            server.accept().use { client ->
                val buffer = java.nio.ByteBuffer.wrap(received)
                while (buffer.hasRemaining()) {
                    client.read(buffer)
                }
            }
            serverDone.countDown()
        }
        val ops = JvmChannelOperations(entries = 1)
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)

        try {
            assertTrue(ops.schedule {
                workerEntered.countDown()
                releaseWorker.await(5, TimeUnit.SECONDS)
            })
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))

            val fd = ops.socket(0, 0, 0)
            val port = (server.localAddress as InetSocketAddress).port
            assertEquals(0, ops.connect(fd, "127.0.0.1", port))

            val handle = ops.openChannel(1)
            handle.writev(fd, ByteBuffer(payload))
            assertEquals(1, handle.submit())

            releaseWorker.countDown()
            val completion = awaitCompletion(handle)
            assertEquals(payload.size, completion.res)
            assertTrue(serverDone.await(5, TimeUnit.SECONDS))
            assertContentEquals(payload, received)
            ops.close(fd)
        } finally {
            releaseWorker.countDown()
            server.close()
            serverThread.join(5_000)
            ops.ioWorkers.shutdownNow()
        }
    }

    @Test
    fun burstHandlesRemainQueuedInsteadOfManufacturingFailures() {
        val ops = JvmChannelOperations(entries = 1)
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val handles = ArrayList<ChannelOperations.ChannelHandle>()
        val fds = ArrayList<Int>()

        try {
            assertTrue(ops.schedule {
                workerEntered.countDown()
                releaseWorker.await(5, TimeUnit.SECONDS)
            })
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))

            repeat(16) {
                val fd = ops.socket(0, 0, 0)
                val handle = ops.openChannel(1)
                fds.add(fd)
                handles.add(handle)
                handle.writev(fd, ByteBuffer(byteArrayOf(1)))
                assertEquals(1, handle.submit())
            }

            assertTrue(
                handles.all { it.wait(minComplete = 0).isEmpty() },
                "worker saturation must preserve pending operations; it must not synthesize ChannelResult(-1)",
            )
        } finally {
            fds.forEach(ops::close)
            releaseWorker.countDown()
            ops.ioWorkers.shutdown()
            ops.ioWorkers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun htxRetriesZeroReadsUntilTheDelayedLoopbackResponseArrives() {
        val responseBody = "still-contiguous"
        val server = ServerSocketChannel.open().apply {
            bind(InetSocketAddress("127.0.0.1", 0))
        }
        val serverThread = thread(name = "htx-delayed-loopback", isDaemon = true) {
            server.accept().use { client ->
                val request = java.nio.ByteBuffer.allocate(4 * 1024)
                while (true) {
                    client.read(request)
                    val text = request.array().copyOf(request.position()).decodeToString()
                    if ("\r\n\r\n" in text) break
                }
                Thread.sleep(100)
                val wire = (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: ${responseBody.encodeToByteArray().size}\r\n" +
                        "Connection: close\r\n\r\n" +
                        responseBody
                    ).encodeToByteArray()
                val output = java.nio.ByteBuffer.wrap(wire)
                while (output.hasRemaining()) {
                    client.write(output)
                }
            }
        }
        val ops = JvmChannelOperations(entries = 2)
        val reactor = HtxReactorElement(channelOperations = ops)
        val port = (server.localAddress as InetSocketAddress).port

        try {
            val result = runBlocking {
                reactor.open()
                try {
                    reactor.exchange(
                        HtxExchangeState(exchangeOrdinal = 1),
                        parseHtxRequest("http://127.0.0.1:$port/delayed"),
                    )
                } finally {
                    reactor.close()
                }
            }

            assertEquals(HtxExchangeLifecycle.RESPONDED, result.state.lifecycle, result.state.failure)
            assertEquals(200, result.state.response?.status)
            assertEquals(responseBody, result.state.response?.body?.asString())
        } finally {
            server.close()
            serverThread.join(5_000)
            ops.ioWorkers.shutdownNow()
        }
    }

    private fun awaitCompletion(
        handle: ChannelOperations.ChannelHandle,
        timeoutMillis: Long = 5_000,
    ): ChannelResult {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            handle.wait(minComplete = 0).firstOrNull()?.let { return it }
            Thread.sleep(5)
        }
        error("timed out waiting for channel completion")
    }
}
