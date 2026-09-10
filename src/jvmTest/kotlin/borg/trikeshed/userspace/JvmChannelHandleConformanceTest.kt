package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ChannelResult
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import java.net.InetSocketAddress
import java.nio.channels.FileChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmChannelHandleConformanceTest {
    @Test
    fun registered_file_slices_completion_queue_and_borrowed_descriptor_lifetime() {
        val operations = JvmChannelOperations()
        val path = Files.createTempFile("trikeshed-uring-handle-", ".bin")
        try {
            FileChannel.open(path, READ, WRITE).use { file ->
                val handle = operations.openChannel(2)
                val fd = handle.id
                assertEquals(fd, handle.id, "the handle identity is stable")
                operations.registerFile(fd, file)
                try {
                    val source = ByteBuffer(byteArrayOf(91, 92, 11, 12, 93)).slice(1, 4).position(1)
                    handle.writev(fd, source, 77)
                    assertEquals(1, handle.submit())
                    assertEquals(ChannelResult(fd, 2, 77), handle.wait().single())
                    assertEquals(3, source.position())
                    file.position(0)
                    val bytes = ByteArray(8) { 99 }
                    val target = ByteBuffer(bytes).slice(2, 7).position(1)
                    handle.readv(fd, target, 78)
                    assertEquals(1, handle.submit())
                    assertEquals(ChannelResult(fd, 2, 78), handle.wait().single())
                    assertContentEquals(byteArrayOf(99, 99, 99, 11, 12, 99, 99, 99), bytes)
                    handle.readv(fd, target, 79)
                    handle.submit()
                    assertEquals(ChannelResult(fd, -1, 79), handle.wait().single())
                    assertEquals(3, target.position())
                    handle.prepAccept(fd, 80)
                    handle.submit()
                    assertEquals(ChannelResult(fd, -95, 80), handle.wait().single())
                    handle.writev(fd, ByteBuffer(byteArrayOf(14)), 81)
                    handle.writev(fd, ByteBuffer(byteArrayOf(15)), 82)
                    assertFailsWith<IllegalArgumentException> { handle.writev(fd, ByteBuffer(byteArrayOf(16)), 83) }
                    handle.close()
                    assertTrue(file.isOpen, "closing the queue leaves a borrowed descriptor open")
                    assertContentEquals(byteArrayOf(11, 12, 14, 15), Files.readAllBytes(path))
                } finally {
                    handle.close()
                    operations.close(fd)
                }
            }
        } finally {
            operations.ioWorkers.shutdownNow()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun loopback_nonblocking_read_distinguishes_would_block_data_and_eof() {
        val operations = JvmChannelOperations()
        try {
            ServerSocketChannel.open().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0))
                val fd = operations.socket(2, 1, 0)
                try {
                    val client = operations.getSelectableChannel(fd) as SocketChannel
                    client.connect(server.localAddress)
                    server.accept().use { peer ->
                        val connectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (!client.finishConnect()) {
                            check(System.nanoTime() < connectDeadline) { "loopback connect did not finish" }
                            Thread.yield()
                        }
                        val handle = operations.openChannel(2)
                        try {
                            val buffer = ByteBuffer(4)
                            handle.readv(fd, buffer, 90)
                            handle.submit()
                            assertEquals(ChannelResult(fd, 0, 90), handle.wait().single())
                            assertEquals(0, buffer.position())
                            val sent = java.nio.ByteBuffer.wrap(byteArrayOf(31, 32))
                            while (sent.hasRemaining()) peer.write(sent)
                            assertEquals(ChannelResult(fd, 2, 91), readWhenReady(handle, client, fd, buffer, 91))
                            assertEquals(2, buffer.position())
                            assertEquals(31, buffer.get(0).toInt())
                            assertEquals(32, buffer.get(1).toInt())
                            peer.shutdownOutput()
                            assertEquals(ChannelResult(fd, -1, 92), readWhenReady(handle, client, fd, buffer, 92))
                            assertEquals(2, buffer.position(), "EOF must retain the buffer position")
                            handle.close()
                            assertTrue(client.isOpen, "the socket remains owned by the descriptor provider")
                        } finally { handle.close() }
                    }
                } finally { operations.close(fd) }
            }
        } finally { operations.ioWorkers.shutdownNow() }
    }

    private fun readWhenReady(
        handle: ChannelOperations.ChannelHandle,
        client: SocketChannel,
        fd: Int,
        buffer: ByteBuffer,
        token: Long,
    ): ChannelResult = Selector.open().use { readiness ->
        client.register(readiness, SelectionKey.OP_READ)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            check(System.nanoTime() < deadline) { "loopback read did not settle" }
            readiness.select(100)
            readiness.selectedKeys().clear()
            handle.readv(fd, buffer, token)
            handle.submit()
            val result = handle.wait().single()
            if (result.res != 0) return@use result
        }
        error("unreachable")
    }
}
