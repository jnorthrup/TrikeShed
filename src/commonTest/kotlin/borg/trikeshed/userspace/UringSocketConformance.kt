package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.channels.ServerSocketChannel
import borg.trikeshed.userspace.nio.channels.SocketChannel
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared assertions over the facade's socket channels on a real adapter: the operating system's loopback answers. */
object UringSocketConformance {
    /** A loopback exchange through a server and a client channel of [protocolFamily], bound to [address] (port 0). */
    fun loopback(protocolFamily: String, address: String) {
        val host = address.substringBeforeLast(':')
        val server = ServerSocketChannel.open(protocolFamily)
        val local = try {
            server.setOption("SO_REUSEADDR", true)
            server.bind(address, 16)
            server.getLocalAddress()
        } catch (failure: Throwable) {
            server.close()
            throw failure
        }
        try {
            assertEquals(host, local.substringBeforeLast(':'), local)
            assertTrue(local.substringAfterLast(':').toInt() > 0, "the kernel chose a port: $local")
            server.configureBlocking(false)
            assertNull(server.accept(), "no connection is pending")
            val client = SocketChannel.openWithProtocolFamily(protocolFamily)
            try {
                assertTrue(client.connect(local))
                assertEquals(host, client.getLocalAddress().substringBeforeLast(':'))
                server.configureBlocking(true)
                val accepted = assertNotNull(server.accept())
                try {
                    val ping = "ping".encodeToByteArray()
                    assertEquals(4, client.write(ByteBuffer(ping)))
                    val inbound = ByteBuffer(16)
                    assertEquals(4, accepted.read(inbound), "a blocking read waits for the bytes")
                    assertContentEquals(ping, inbound.array().copyOf(4))
                    accepted.configureBlocking(false)
                    assertEquals(0, accepted.read(ByteBuffer(16)), "a non-blocking read with nothing ready")

                    // Fill the connection until a non-blocking write takes nothing, then read every byte back.
                    client.configureBlocking(false)
                    fun at(index: Long) = (index * 31 % 251).toByte()
                    var sent = 0L
                    while (true) {
                        val source = ByteBuffer(ByteArray(65536) { at(sent + it) })
                        val count = client.write(source)
                        assertEquals(count, source.position(), "WRITE advances its buffer by its result")
                        sent += count
                        if (count == 0) break
                    }
                    assertTrue(sent > 0, "the socket buffers took bytes before they filled")
                    accepted.configureBlocking(true)
                    val received = ByteBuffer(65536)
                    var total = 0L
                    while (total < sent) {
                        received.clear()
                        val count = accepted.read(received)
                        assertTrue(count > 0, "bytes until everything sent arrived, got $count")
                        for (i in 0 until count) assertEquals(at(total + i), received.array()[i])
                        total += count
                    }
                    assertEquals(sent, total)

                    // Half close: the server reads end of stream and still answers.
                    client.configureBlocking(true)
                    client.shutdownOutput()
                    assertEquals(-1, accepted.read(ByteBuffer(16)))
                    val pong = "pong".encodeToByteArray()
                    assertEquals(4, accepted.write(ByteBuffer(pong)))
                    val answer = ByteBuffer(16)
                    assertEquals(4, client.read(answer))
                    assertContentEquals(pong, answer.array().copyOf(4))
                } finally {
                    accepted.close()
                }
                assertEquals(-1, client.read(ByteBuffer(16)), "the peer's close ends the stream")
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
        // A non-blocking accept leaves its POLL_ADD in flight; close removes it.
        ServerSocketChannel.open(protocolFamily).apply {
            bind(address)
            configureBlocking(false)
            assertNull(accept())
            close()
        }
        val refused = assertFailsWith<IOException> { SocketChannel.openWithRemote(local) }
        assertTrue(refused.message!!.endsWith(": -111"), "a closed port refuses: ${refused.message}")
    }
}
