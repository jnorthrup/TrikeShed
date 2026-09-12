package borg.trikeshed.userspace.nio.channels.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import kotlin.test.assertTrue

class JvmChannelOperationsConnectTest {

    @Test
    fun connectAfterCloseSettlesBadDescriptor() {
        val handle = JvmChannelOperations().openChannel(2)
        try {
            assertEquals(0, handle.prepSocket(2, 1, 0, 1))
            val fd = handle.wait(1).single().res
            assertTrue(fd >= 0)
            val address = ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1), 80))
            assertEquals(0, handle.prepClose(fd, 2))
            assertEquals(0, handle.prepConnect(fd, address, 3))
            assertEquals(2, handle.submit())
            assertEquals(listOf(ChannelResult(fd, 0, 2), ChannelResult(fd, -9, 3)), handle.wait(2))
            assertTrue(handle.wait(0).isEmpty())
        } finally { handle.close() }
    }

    @Test
    fun fullRingRejectsAdmissionWithoutInventingACompletion() {
        val handle = JvmChannelOperations().openChannel(1)
        try {
            assertEquals(0, handle.writev(-1, ByteBuffer(10), 1))
            assertEquals(-11, handle.writev(-1, ByteBuffer(10), 2))
            assertEquals(1, handle.submit())
            assertEquals(listOf(ChannelResult(-1, -9, 1)), handle.wait(1))
            assertTrue(handle.wait(0).isEmpty())
            handle.close()
            assertEquals(-9, handle.writev(-1, ByteBuffer(10), 3))
            assertEquals(-9, handle.submit())
        } finally { handle.close() }
    }
}
