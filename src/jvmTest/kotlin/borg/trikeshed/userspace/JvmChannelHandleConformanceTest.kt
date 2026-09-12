package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ChannelResult
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import java.net.InetSocketAddress
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmChannelHandleConformanceTest {
    @Test
    fun registered_file_slices_completion_queue_and_borrowed_descriptor_lifetime() {
        val operations = JvmChannelOperations()
        val path = Files.createTempFile("trikeshed-uring-handle-", ".bin")
        try {
            FileChannel.open(path, READ, WRITE).use { file ->
                val handle = operations.openChannel(2)
                val fd = JvmFileTable.register(JvmChannelDescriptor(file))
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
                    assertEquals(ChannelResult(fd, 0, 79), handle.wait().single())
                    assertEquals(3, target.position())
                    handle.prepAccept(fd, 80)
                    handle.submit()
                    assertEquals(ChannelResult(fd, -88, 80), handle.wait().single())
                    handle.writev(fd, ByteBuffer(byteArrayOf(14)), 81)
                    handle.writev(fd, ByteBuffer(byteArrayOf(15)), 82)
                    assertEquals(-11, handle.writev(fd, ByteBuffer(byteArrayOf(16)), 83))
                    handle.close()
                    assertEquals(listOf(ChannelResult(fd, 1, 81), ChannelResult(fd, 1, 82)), handle.wait(0))
                    assertEquals(-9, handle.writev(fd, ByteBuffer(byteArrayOf(16)), 83))
                    assertTrue(file.isOpen, "closing the queue leaves a borrowed descriptor open")
                    assertContentEquals(byteArrayOf(11, 12, 14, 15), Files.readAllBytes(path))
                } finally {
                    handle.close()
                    JvmFileTable.close(fd)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun loopback_nonblocking_read_distinguishes_would_block_data_and_eof() {
        val operations = JvmChannelOperations()
        ServerSocketChannel.open().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0))
                val handle = operations.openChannel(2)
                assertEquals(0, handle.prepSocket(2, 1, 0, 80))
                val fd = handle.wait(1).single().res
                assertTrue(fd >= 0)
                try {
                    val port = (server.localAddress as InetSocketAddress).port
                    val address = ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1), port))
                    assertEquals(0, handle.prepConnect(fd, address, 81))
                    assertEquals(ChannelResult(fd, 0, 81), handle.wait(1).single())
                    server.accept().use { peer ->
                        try {
                            val buffer = ByteBuffer(4)
                            handle.readv(fd, buffer, 90)
                            handle.submit()
                            assertEquals(ChannelResult(fd, -11, 90), handle.wait().single())
                            assertEquals(0, buffer.position())
                            val sent = java.nio.ByteBuffer.wrap(byteArrayOf(31, 32))
                            while (sent.hasRemaining()) peer.write(sent)
                            assertEquals(ChannelResult(fd, 2, 91), readWhenReady(handle, fd, buffer, 91))
                            assertEquals(2, buffer.position())
                            assertEquals(31, buffer.get(0).toInt())
                            assertEquals(32, buffer.get(1).toInt())
                            peer.shutdownOutput()
                            assertEquals(ChannelResult(fd, 0, 92), readWhenReady(handle, fd, buffer, 92))
                            assertEquals(2, buffer.position(), "EOF must retain the buffer position")
                            handle.close()
                            assertFalse(FileImpl(fd).isOpen(), "closing the ring closes its owned socket")
                        } finally { handle.close() }
                    }
                } finally { handle.close() }
        }
    }

    private fun readWhenReady(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        buffer: ByteBuffer,
        token: Long,
    ): ChannelResult {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            check(System.nanoTime() < deadline) { "loopback read did not settle" }
            handle.readv(fd, buffer, token)
            handle.submit()
            val result = handle.wait().single()
            if (result.res != -11) return result
            Thread.yield()
        }
    }

    @Test
    fun repeatedCallerTokensRetainEveryCompletionAndBoundAdmission() {
        val handle = JvmChannelOperations().openChannel(2)
        try {
            assertEquals(0, handle.prepSocket(2, 1, 0))
            assertEquals(0, handle.prepSocket(2, 1, 0))
            assertEquals(-11, handle.prepClose(-1))
            assertEquals(2, handle.submit())
            assertEquals(-11, handle.prepClose(-1), "unreaped CQEs retain admission capacity")
            val results = handle.wait(0)
            assertEquals(2, results.size)
            assertTrue(results.all { it.userData == 0L && it.res >= 0 })
            assertEquals(2, results.map { it.res }.toSet().size)
            assertTrue(handle.wait(0).isEmpty())
            assertEquals(0, handle.prepClose(-1, 7))
            assertEquals(ChannelResult(-1, -9, 7), handle.wait(1).single())
        } finally { handle.close() }
    }
}
