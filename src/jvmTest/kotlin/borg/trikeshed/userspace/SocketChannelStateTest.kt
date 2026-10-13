package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.channels.*
import java.net.InetSocketAddress
import kotlin.test.*

class SocketChannelStateTest {
    @Test
    fun unconnectedChannelHasNoPeerOrCompletedConnection() {
        val socket = SocketChannel.open()
        try {
            assertFalse(socket.isConnected())
            assertFalse(socket.isConnectionPending())
            assertNull(socket.getRemoteAddress())
            assertFailsWith<NoConnectionPendingException> { socket.finishConnect() }
            assertFailsWith<NotYetConnectedException> { socket.read(ByteBuffer(1)) }
            assertFailsWith<NotYetConnectedException> { socket.write(ByteBuffer(byteArrayOf(1))) }
            assertFailsWith<NotYetConnectedException> { socket.shutdownInput() }
            assertFailsWith<NotYetConnectedException> { socket.shutdownOutput() }
        } finally { socket.close() }
        assertFalse(socket.isConnected())
        assertFalse(socket.isConnectionPending())
        assertFailsWith<ClosedChannelException> { socket.finishConnect() }
        assertFailsWith<ClosedChannelException> { socket.getRemoteAddress() }
        assertFailsWith<ClosedChannelException> { socket.connect("127.0.0.1:1") }
    }

    @Test
    fun connectedChannelReportsActualPeerAndPreservesDuplexIo() {
        java.nio.channels.ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress("127.0.0.1", 0))
            val remote = "127.0.0.1:${(server.localAddress as InetSocketAddress).port}"
            val socket = SocketChannel.openWithRemote(remote)
            try {
                server.accept().use { peer ->
                    assertTrue(socket.isConnected())
                    assertFalse(socket.isConnectionPending())
                    assertTrue(socket.finishConnect())
                    assertEquals(remote, socket.getRemoteAddress())
                    assertEquals("127.0.0.1:${(peer.remoteAddress as InetSocketAddress).port}", socket.getLocalAddress())
                    assertFailsWith<AlreadyConnectedException> { socket.connect(remote) }
                    assertTrue(socket.isOpen(), "a repeated connect must not discard the established stream")
                    assertEquals(1, socket.write(ByteBuffer(byteArrayOf(42))))
                    val received = java.nio.ByteBuffer.allocate(1)
                    assertEquals(1, peer.read(received))
                    assertEquals(42, received.get(0).toInt())
                    socket.shutdownOutput()
                    assertEquals(-1, peer.read(java.nio.ByteBuffer.allocate(1)))
                    assertTrue(socket.isConnected(), "half-close retains the connection")
                    peer.write(java.nio.ByteBuffer.wrap(byteArrayOf(7)))
                    val reply = ByteBuffer(1)
                    assertEquals(1, socket.read(reply))
                    assertEquals(7, reply.get(0).toInt())
                }
            } finally { socket.close() }
            assertFalse(socket.isConnected())
        }
    }

    @Test
    fun nonblockingReadDoesNotChangeConnectionState() {
        java.nio.channels.ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress("127.0.0.1", 0))
            val remote = "127.0.0.1:${(server.localAddress as InetSocketAddress).port}"
            val socket = SocketChannel.open()
            try {
                socket.configureBlocking(false)
                assertTrue(socket.connect(remote), "the current adapter settles CONNECT in the submitting call")
                server.accept().use {
                    assertEquals(0, socket.read(ByteBuffer(1)))
                    assertTrue(socket.isConnected())
                    assertFalse(socket.isConnectionPending())
                    assertEquals(remote, socket.getRemoteAddress())
                }
            } finally { socket.close() }
        }
    }

    @Test
    fun acceptedChannelReportsActualPeer() {
        val server = ServerSocketChannel.open()
        try {
            server.bind("127.0.0.1:0")
            val address = server.getLocalAddress()
            java.nio.channels.SocketChannel.open(InetSocketAddress("127.0.0.1", address.substringAfterLast(':').toInt())).use { client ->
                val socket = assertNotNull(server.accept())
                try {
                    assertTrue(socket.isConnected())
                    assertFalse(socket.isConnectionPending())
                    assertTrue(socket.finishConnect())
                    assertEquals(address, socket.getLocalAddress())
                    assertEquals("127.0.0.1:${(client.localAddress as InetSocketAddress).port}", socket.getRemoteAddress())
                    assertEquals(1, socket.write(ByteBuffer(byteArrayOf(3))))
                    val received = java.nio.ByteBuffer.allocate(1)
                    assertEquals(1, client.read(received))
                    assertEquals(3, received.get(0).toInt())
                } finally { socket.close() }
                assertFalse(socket.isConnected())
            }
        } finally { server.close() }
    }

    @Test
    fun refusedConnectionClosesChannelButInvalidAddressDoesNot() {
        val socket = SocketChannel.open()
        // A bound, non-listening TCP socket reserves the port and deterministically refuses CONNECT.
        val ring = FunctionalUringFacade(4, JvmUserspaceChannelBackend())
        var fd = -1
        try {
            assertFailsWith<UnresolvedAddressException> { socket.connect("not-an-address") }
            assertTrue(socket.isOpen())
            fd = ring.execute(Submissions.socket(2, 1, 0, 1))
            assertTrue(fd >= 0)
            assertEquals(0, ring.execute(Submissions.bind(fd, sockaddr("127.0.0.1:0"), 2)))
            val address = ByteBuffer(128)
            assertEquals(0, ring.execute(Submissions.getsockname(fd, address, false, 3)))
            assertFailsWith<IOException> { socket.connect(sockaddrText(address.array())) }
            assertFalse(socket.isOpen())
            assertFalse(socket.isConnected())
            assertFalse(socket.isConnectionPending())
        } finally {
            socket.close()
            try { if (fd >= 0) ring.execute(Submissions.close(fd, 5)) } finally { ring.closeNow() }
        }
    }

    @Test
    fun ipv6PeerUsesFacadeAddressFraming() {
        java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.INET6).use { server ->
            server.bind(InetSocketAddress("::1", 0))
            val remote = "[::1]:${(server.localAddress as InetSocketAddress).port}"
            val socket = SocketChannel.openWithRemote(remote)
            try {
                server.accept().use { peer ->
                    assertEquals(remote, socket.getRemoteAddress())
                    assertEquals("[::1]:${(peer.remoteAddress as InetSocketAddress).port}", socket.getLocalAddress())
                }
            } finally { socket.close() }
        }
    }

    @Test
    fun socketNameWritesOnlyBorrowedWindowAndRejectsInvalidBuffers() {
        val ring = FunctionalUringFacade(4, JvmUserspaceChannelBackend())
        var fd = -1
        try {
            fd = ring.execute(Submissions.socket(2, 1, 0, 1))
            assertTrue(fd >= 0)
            assertEquals(0, ring.execute(Submissions.bind(fd, sockaddr("127.0.0.1:0"), 2)))
            val bytes = ByteArray(32) { 99 }
            val buffer = ByteBuffer(bytes).position(4).limit(24).slice().position(2).limit(18)
            val query = Submissions.getsockname(fd, buffer, false, 3)
            assertEquals(0, ring.execute(query))
            assertEquals(2, buffer.position(), "socket command borrows the window without advancing it")
            assertContentEquals(ByteArray(6) { 99 }, bytes.copyOfRange(0, 6))
            assertContentEquals(ByteArray(10) { 99 }, bytes.copyOfRange(22, 32))
            assertEquals(2, sockaddrFamily(bytes.copyOfRange(6, 22)))
            assertTrue(sockaddrPort(bytes.copyOfRange(6, 22)) > 0)
            assertContentEquals(byteArrayOf(127, 0, 0, 1), sockaddrOctets(bytes.copyOfRange(6, 22)))
            assertEquals(-22, ring.execute(query.copy(buffer = buffer.asReadOnlyBuffer())))
            assertEquals(-22, ring.execute(query.copy(len = 17)))
            assertEquals(-22, ring.execute(query.copy(operationFlags = 2)))
            assertEquals(-95, ring.execute(query.copy(offset = 999)))
            assertEquals(-9, ring.execute(query.copy(fd = -1)))
            assertEquals(-107, ring.execute(Submissions.getsockname(fd, buffer, true, 4)))
        } finally {
            try { if (fd >= 0) ring.execute(Submissions.close(fd, 5)) } finally { ring.closeNow() }
        }
    }
}
