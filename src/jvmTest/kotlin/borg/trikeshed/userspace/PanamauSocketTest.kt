package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** READ/WRITE retain non-blocking descriptor semantics; RECV/SEND retain their asynchronous wait. */
class PanamauSocketTest {
    @Test fun socketReadWriteReturnWouldBlockWhileRecvWaits() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        val backend = PanamauChannelBackend(openPanamaRing(8))
        val completions = HashMap<Long, Int>()
        var token = 0L
        var fd = -1
        var peer: SocketChannel? = null
        fun take(results: List<SelectionResult>) {
            for (result in results)
                assertNull(completions.put(result.userData, result.res), "duplicate CQE ${result.userData}")
        }
        fun submit(submission: UringSubmission): Long = (++token).also {
            take(backend.submitBatch(listOf(submission.copy(userData = it))))
        }
        fun await(id: Long): Int {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (id !in completions) {
                assertTrue(System.nanoTime() < deadline, "no CQE for $id within 5 s")
                take(backend.reapCompletions(1, 10_000_000L))
            }
            return completions.getValue(id)
        }
        fun transfer(op: UringOp, buffer: ByteBuffer): Long =
            submit(UringSubmission(op, fd, 0, buffer.remaining(), -1, buffer = buffer))
        try {
            ServerSocketChannel.open().use { server ->
                server.setOption(StandardSocketOptions.SO_RCVBUF, 4096)
                server.bind(InetSocketAddress("127.0.0.1", 0))
                server.configureBlocking(false)
                fd = await(submit(UringSubmission(UringOp.SOCKET, 2, 0, 0, 1)))
                assertTrue(fd >= 0, "SOCKET returned $fd")
                val address = ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1),
                    (server.localAddress as InetSocketAddress).port))
                assertEquals(0, await(submit(UringSubmission(UringOp.CONNECT, fd, 0, address.remaining(), 0,
                    buffer = address))))
                val acceptDeadline = System.nanoTime() + 5_000_000_000L
                while (peer == null) {
                    assertTrue(System.nanoTime() < acceptDeadline, "no accepted socket within 5 s")
                    peer = server.accept()
                }
                val remote = checkNotNull(peer)
                remote.configureBlocking(false)

                val empty = ByteBuffer(ByteArray(8) { 42 }).position(2)
                assertEquals(-11, await(transfer(UringOp.READ, empty)), "empty READ returns EAGAIN")
                assertEquals(2, empty.position())
                assertContentEquals(ByteArray(8) { 42 }, empty.array())

                val inbound = ByteBuffer(8)
                val recv = transfer(UringOp.RECV, inbound)
                take(backend.reapCompletions(1, 100_000_000L))
                assertTrue(recv !in completions, "RECV stays pending while the socket is empty")
                assertEquals(0, inbound.position())
                assertEquals(1, remote.write(java.nio.ByteBuffer.wrap(byteArrayOf(31))))
                assertEquals(1, await(recv))
                assertEquals(31, inbound.get(0).toInt())

                assertEquals(1, remote.write(java.nio.ByteBuffer.wrap(byteArrayOf(32))))
                assertTrue(await(submit(UringOp.Companion.Submissions.pollAdd(fd, UringOp.POLLIN, 0))) > 0)
                assertEquals(1, await(transfer(UringOp.READ, inbound)))
                assertEquals(2, inbound.position())
                assertEquals(32, inbound.get(1).toInt())
                remote.shutdownOutput()
                assertTrue(await(submit(UringOp.Companion.Submissions.pollAdd(fd, UringOp.POLLIN, 0))) > 0)
                assertEquals(0, await(transfer(UringOp.READ, inbound)), "EOF is distinct from EAGAIN")
                assertEquals(2, inbound.position())

                // Keep the peer's receive window full. A READ-only fix still hangs on the final WRITE.
                val outbound = ByteBuffer(ByteArray(65536) { 17 })
                var sent = 0L
                val writeDeadline = System.nanoTime() + 5_000_000_000L
                while (true) {
                    assertTrue(System.nanoTime() < writeDeadline && sent < 64L * 1024 * 1024,
                        "socket did not fill within the bounded write budget")
                    outbound.clear()
                    val count = await(transfer(UringOp.WRITE, outbound))
                    if (count == -11) {
                        assertEquals(0, outbound.position(), "EAGAIN does not consume the source")
                        break
                    }
                    assertTrue(count > 0, "WRITE returned $count")
                    assertEquals(count, outbound.position())
                    sent += count
                }
                assertTrue(sent > 0, "WRITE transferred data before the socket filled")
            }
        } finally {
            peer?.close()
            try { backend.close() } finally { if (fd >= 0) JvmFileTable.close(fd) }
        }
    }
}
