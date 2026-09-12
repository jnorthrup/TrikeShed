package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.htx.HtxExchangeLifecycle
import borg.trikeshed.htx.HtxExchangeState
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.htx.state
import borg.trikeshed.lib.asString
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
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
        val handle = JvmChannelOperations().openChannel(2)

        try {
            assertEquals(0, handle.prepSocket(2, 1, 0, 1))
            val fd = handle.wait(1).single().res
            assertTrue(fd >= 0)
            val port = (server.localAddress as InetSocketAddress).port
            val address = ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1), port))
            assertEquals(0, handle.prepConnect(fd, address, 2))
            assertEquals(0, handle.writev(fd, ByteBuffer(payload), 3))
            assertEquals(2, handle.submit())
            assertEquals(listOf(ChannelResult(fd, 0, 2), ChannelResult(fd, payload.size, 3)), handle.wait(2))
            assertTrue(serverDone.await(5, TimeUnit.SECONDS))
            assertContentEquals(payload, received)
        } finally {
            handle.close()
            server.close()
            serverThread.join(5_000)
        }
    }

    @Test
    fun burstHandlesRetainEveryCompletion() {
        val ops = JvmChannelOperations()
        val handles = ArrayList<ChannelOperations.ChannelHandle>()

        try {
            repeat(16) { index ->
                val handle = ops.openChannel(1)
                handles.add(handle)
                assertEquals(0, handle.prepSocket(2, 1, 0, index.toLong()))
                assertEquals(1, handle.submit())
            }

            val results = handles.map { it.wait(0).single() }
            assertTrue(results.all { it.res >= 0 })
            assertEquals((0L until 16L).toSet(), results.map { it.userData }.toSet())
            assertEquals(16, results.map { it.res }.toSet().size)
            assertTrue(handles.all { it.wait(0).isEmpty() })
        } finally {
            handles.forEach { it.close() }
        }
    }

    @Test
    fun htxRetriesWouldBlockUntilTheDelayedLoopbackResponseArrives() {
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
        val ops = JvmChannelOperations()
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
        }
    }

}
