package borg.trikeshed.litebike

import borg.trikeshed.litebike.taxonomy.Protocol
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmLitebikeBindAdapterPermitTest {
    @Test fun connectionOwnsItsPermitUntilClose(): Unit = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val owner = SupervisorJob(coroutineContext[Job])
        val nio = NioSupervisor(maxConcurrentIo = 1)
        val scope = CoroutineScope(owner + Dispatchers.IO + nio)
        val listener = LitebikeListenerElement(parentJob = owner).also { it.open() }
        val slot = listener.register(Protocol.Http)
        val binding = scope.launch { JvmLitebikeBindAdapter.bindAndServe(listener, port, host = "127.0.0.1") }
        var first: Socket? = null
        var second: Socket? = null
        try {
            val firstConnection = withTimeout(10_000) {
                var socket: Socket? = null
                while (socket == null) {
                    socket = runCatching { Socket("127.0.0.1", port) }.getOrNull()
                    if (socket == null) delay(10)
                }
                socket
            }
            first = firstConnection
            firstConnection.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            withTimeout(10_000) { slot.consume() }
            assertFalse(nio.tryAcquireIo(), "the open connection must retain its permit")

            firstConnection.close()
            first = null
            withTimeout(10_000) {
                while (!nio.tryAcquireIo()) delay(10)
            }
            nio.releaseIo()

            val secondConnection = Socket("127.0.0.1", port)
            second = secondConnection
            secondConnection.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            withTimeout(10_000) { slot.consume() }
            assertFalse(nio.tryAcquireIo(), "the next open connection must retain its permit")

            binding.cancelAndJoin()
            assertTrue(nio.tryAcquireIo(), "binding cancellation must release the connection permit")
            nio.releaseIo()
        } finally {
            first?.close()
            second?.close()
            binding.cancelAndJoin()
            listener.close()
            owner.cancelAndJoin()
        }
    }
}
