@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.SocketDomain
import borg.trikeshed.userspace.nio.channels.SocketType
import borg.trikeshed.userspace.nio.channels.UnixName
import borg.trikeshed.userspace.nio.channels.sockaddrUnixName
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.unixName
import borg.trikeshed.userspace.nio.channels.unixSlot
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SENDMSG and RECVMSG over AF_UNIX datagram sockets, through the ring of this host (the kernel's on Linux, the POSIX
 * emulation on macOS). The window's last UringOp.DATAGRAM_ADDRESS bytes are the address slot: a send names its address
 * by the slot's content, a receive writes the sender's name and zeros the rest of the slot.
 */
class UnixDatagramRingTest {
    val channels = PosixChannelOperations()
    val linux = Platform.osFamily == OsFamily.LINUX
    val slotSize = UringOp.DATAGRAM_ADDRESS
    val room = 16

    /** One prepared SQE, submitted and settled. */
    fun ChannelOperations.ChannelHandle.settle(prepared: Int): Int {
        check(prepared >= 0) { "SQE refused: $prepared" }
        check(submit() == 1) { "SQE was not submitted" }
        return wait(1).single().res
    }

    /** A nonblocking datagram socket; bound to [name] unless it is null. An empty name is Linux's autobind. */
    fun ChannelOperations.ChannelHandle.unixSocket(name: UnixName?): Int {
        val fd = settle(prepSocket(SocketDomain.AF_UNIX.posix, SocketType.SOCK_DGRAM.mask or SocketType.SOCK_NONBLOCK.mask, 0))
        check(fd >= 0) { "socket: $fd" }
        if (name != null) check(settle(prepBind(fd, ByteBuffer(sockaddrUnixName(name)))) == 0) { "bind" }
        return fd
    }

    /** A datagram window: [payload], then the slot [slot], or [fill] bytes (what a previous datagram left) for a receive. */
    fun window(payload: ByteArray, slot: ByteArray?, fill: Byte = 0): ByteArray =
        payload + (slot ?: ByteArray(slotSize) { fill })

    /**
     * The client sends "ping" to [serverName]; the server's receive window starts with 0x7f bytes in its slot; the
     * server answers "pong" to the slot it received. Returns the slot of the server's receive, and the slot of the
     * client's receive of the answer.
     */
    fun pingPong(serverName: UnixName, clientName: UnixName?, unlink: Series<String>): Twin<ByteArray> {
        for (path in unlink.view) platform.posix.unlink(path)
        val ring = channels.openChannel(8)
        val server = ring.unixSocket(serverName)
        val client = ring.unixSocket(clientName)
        try {
            val ping = "ping".encodeToByteArray()
            assertEquals(ping.size, ring.settle(ring.sendmsg(client, ByteBuffer(window(ping, unixSlot(serverName))), 1, UringOp.MSG_DONTWAIT)))
            val into = window(ByteArray(room), null, 0x7f)
            assertEquals(ping.size, ring.settle(ring.recvmsg(server, ByteBuffer(into), 2, UringOp.MSG_DONTWAIT)))
            assertContentEquals(ping, into.copyOfRange(0, ping.size))
            val from = into.copyOfRange(room, into.size)
            val pong = "pong".encodeToByteArray()
            assertEquals(pong.size, ring.settle(ring.sendmsg(server, ByteBuffer(window(pong, from)), 3, UringOp.MSG_DONTWAIT)))
            val answer = window(ByteArray(room), null, 0x7f)
            assertEquals(pong.size, ring.settle(ring.recvmsg(client, ByteBuffer(answer), 4, UringOp.MSG_DONTWAIT)))
            assertContentEquals(pong, answer.copyOfRange(0, pong.size))
            return from j answer.copyOfRange(room, answer.size)
        } finally {
            ring.settle(ring.prepClose(server))
            ring.settle(ring.prepClose(client))
            ring.close()
            for (path in unlink.view) platform.posix.unlink(path)
        }
    }

    /** Linux only: abstract names, and a client the kernel names itself (autobind: a 0 byte and 5 hex digits). */
    @Test
    fun aKernelNamedClientIsAnsweredAtTheNameItsDatagramCarried() {
        if (!linux) return
        val server = unixName("@e-unix-ring-server")
        val (from, answered) = pingPong(server, unixName(""), emptySeriesOf())
        assertEquals(SocketDomain.AF_UNIX.posix, from[0].toInt())
        assertEquals(0, from[1].toInt())
        assertEquals(0, from[2].toInt(), "an abstract name leads with 0")
        for (digit in from.copyOfRange(3, 8)) assertTrue(digit.toInt().toChar() in "0123456789abcdef", "autobind digit $digit")
        assertContentEquals(ByteArray(slotSize - 8), from.copyOfRange(8, slotSize), "the slot past the name is zeros, not the 0x7f of the last datagram")
        assertContentEquals(unixSlot(server), answered)
    }

    /** File names, as macOS has them and Linux allows them: the name, then zeros to the slot's end. */
    @Test
    fun fileNamesTravelInTheSlotAndTheRestOfItIsZeros() {
        val server = unixName("/tmp/e-unix-ring-s")
        val client = unixName("/tmp/e-unix-ring-c")
        val (from, answered) = pingPong(server, client, s_["/tmp/e-unix-ring-s", "/tmp/e-unix-ring-c"])
        assertContentEquals(unixSlot(client), from)
        assertContentEquals(unixSlot(server), answered)
    }

    /** A sender that never bound has no name to answer: its datagram arrives, and the slot reads as no family at all. */
    @Test
    fun aSenderWithoutANameLeavesTheWholeSlotZero() {
        val serverName = unixName(if (linux) "@e-unix-ring-unnamed" else "/tmp/e-unix-ring-u")
        if (!linux) platform.posix.unlink("/tmp/e-unix-ring-u")
        val ring = channels.openChannel(8)
        val server = ring.unixSocket(serverName)
        val client = ring.unixSocket(null)
        try {
            val ping = "ping".encodeToByteArray()
            assertEquals(ping.size, ring.settle(ring.sendmsg(client, ByteBuffer(window(ping, unixSlot(serverName))), 1, UringOp.MSG_DONTWAIT)))
            val into = window(ByteArray(room), null, 0x7f)
            assertEquals(ping.size, ring.settle(ring.recvmsg(server, ByteBuffer(into), 2, UringOp.MSG_DONTWAIT)))
            assertContentEquals(ByteArray(slotSize), into.copyOfRange(room, into.size))
        } finally {
            ring.settle(ring.prepClose(server))
            ring.settle(ring.prepClose(client))
            ring.close()
            if (!linux) platform.posix.unlink("/tmp/e-unix-ring-u")
        }
    }
}
