package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.commonCreateConnectedSocket
import borg.trikeshed.userspace.nio.commonCreateListeningSocket
import borg.trikeshed.userspace.nio.SocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommonUserspaceIOTest {
    @Test
    fun commonBuffer_readsAndWrites() {
        val buffer = CommonUserspaceBuffer(byteArrayOf(1, 2, 3))

        assertEquals(3, buffer.size)
        assertEquals(2, buffer.get(1))

        buffer.put(1, 7)

        assertEquals(7, buffer.get(1))
    }

    @Test
    fun commonFd_allocatorAndValidity() {
        val allocator = CommonUserspaceFdAllocator()
        val fd = CommonUserspaceFD(allocator.next())

        assertEquals(1, fd.id)
        assertFalse(fd.isInvalid())
    }

    @Test
    fun commonRing_collectsAndClearsResults() {
        val ring = CommonUserspaceRing()
        val fd = userspaceSPI.createSocket(0, 0, 0)
        val buffer = userspaceSPI.wrapBuffer(byteArrayOf(9, 8, 7))

        ring.prepRead(fd, buffer, 0, 11L)
        ring.prepClose(fd, 12L)

        assertEquals(2, ring.submit())
        assertEquals(
            listOf(
                UserspaceIOResult(-1, 11L),
                UserspaceIOResult(0, 12L),
            ),
            ring.peek(),
        )
        assertTrue(ring.peek().isEmpty())
    }

    @Test
    fun commonSocketFactories_returnCommonShapes() = runBlocking {
        val listening = commonCreateListeningSocket("127.0.0.1", 9000)
        val connected = commonCreateConnectedSocket("127.0.0.1", 9000)

        assertEquals(SocketAddress.Inet("127.0.0.1", 9000), listening.bindAddress)
        assertEquals(SocketAddress.Inet("127.0.0.1", 9000), connected.remoteAddress)
        assertEquals(-1, connected.read(ByteArray(4), 0, 4))
        assertEquals(4, connected.write(ByteArray(4), 0, 4))
        assertEquals(null, listening.accept())
        connected.close()
        listening.close()
    }
}
