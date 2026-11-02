@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package borg.trikeshed.sctp

import borg.trikeshed.htx.PPID_HTX11
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.DatagramWheel
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.endpoint
import borg.trikeshed.userspace.nio.channels.parseWhole
import borg.trikeshed.userspace.nio.channels.spi.LinuxChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.LinuxReactorOperations
import kotlinx.cinterop.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import platform.posix.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** [SctpUdpPort] on the Linux wheel and the parking reactor: the server's owner loop on a thread of its own. */
class SctpUdpPortLinuxTest {
    val secret = "sctp over udp".encodeToByteArray()

    /** The owner loop of [port], run on a thread of its own: it parks in poll(2), so it looks at [SctpUdpPort.stop] every 20 ms. */
    suspend fun serveLoop(port: SctpUdpPort) {
        while (!port.kick.isClosedForReceive) {
            port.waitWake(minOf(port.table.nextDeadline(), port.now() + 20_000_000L))
            port.pump()
        }
    }

    /** The test thread owns [client]: it waits in the port's own parking wait until [done] or [millis] pass. */
    suspend fun drive(client: SctpUdpPort, millis: Long, done: () -> Boolean) {
        val end = client.now() + millis * 1_000_000L
        while (!done() && client.now() < end) {
            client.waitWake(minOf(client.table.nextDeadline(), client.now() + 20_000_000L, end))
            client.pump()
        }
    }

    /** Two ports on loopback UDP: HTX1 request and response, then a 20,000-byte message split over many packets and echoed. */
    @Test
    fun requestResponseAndAMessageOfManyPacketsOverLoopbackUdp() = runBlocking {
        val serverAt = parseWhole("127.0.0.1:39921") { endpoint() }!!
        val clientAt = parseWhole("127.0.0.1:39922") { endpoint() }!!
        lateinit var server: SctpUdpPort
        server = SctpUdpPort(LinuxChannelOperations(), LinuxReactorOperations(), serverAt, object : SctpUpper {
            override fun associated(tcb: SctpTcb) {}
            override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
                val echo = ("echo:" + bytes.decodeToString(offset, offset + length)).encodeToByteArray()
                server.table.send(tcb, ppid, echo, 0, echo.size)
            }
            override fun closed(tcb: SctpTcb) {}
        }, secret).open()
        var up: SctpTcb? = null
        var got: String? = null
        val client = SctpUdpPort(LinuxChannelOperations(), LinuxReactorOperations(), clientAt, object : SctpUpper {
            override fun associated(tcb: SctpTcb) { up = tcb }
            override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
                got = bytes.decodeToString(offset, offset + length)
            }
            override fun closed(tcb: SctpTcb) {}
        }, secret).open()
        val thread = newSingleThreadContext("sctp-server")
        val serving = launch(thread) { serveLoop(server) }
        val t = client.connect(serverAt)
        drive(client, 5_000) { up != null }
        assertEquals(t, up)
        val request = "GET /feed HTTP/1.1\r\n\r\n".encodeToByteArray()
        client.submit(t, PPID_HTX11, request, 0, request.size)
        drive(client, 5_000) { got != null }
        assertEquals("echo:GET /feed HTTP/1.1\r\n\r\n", got)

        got = null
        val big = ((0 until 20_000).toSeries() α { 'a' + it % 26 }).view.joinToString("")
        val bytes = big.encodeToByteArray()
        client.submit(t, PPID_HTX11, bytes, 0, bytes.size)
        drive(client, 5_000) { got != null }
        assertEquals("echo:$big", got)
        assertEquals(1, server.table.count)

        client.stop(); server.stop()
        serving.join()
        thread.close()
        client.close(); server.close()
    }

    /**
     * A wheel of 2 × 4 windows under 16 associations whose echoes take 3 packets each: sends run out of windows inside
     * table calls, and the receives reaped there reach the table in the same wake.
     */
    @Test
    fun smallWheelServesMoreAssociationsThanWindows() = runBlocking {
        val serverAt = parseWhole("127.0.0.1:39923") { endpoint() }!!
        val clientAt = parseWhole("127.0.0.1:39924") { endpoint() }!!
        val users = (0 until 16).toSeries()
        val expected = users α { "echo:GET /posts/$it HTTP/1.1\r\n\r\n".padEnd(2_500, '.') }
        lateinit var server: SctpUdpPort
        server = SctpUdpPort(LinuxChannelOperations(), LinuxReactorOperations(), serverAt, object : SctpUpper {
            override fun associated(tcb: SctpTcb) {}
            override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
                val echo = ("echo:" + bytes.decodeToString(offset, offset + length)).padEnd(2_500, '.').encodeToByteArray()
                server.table.send(tcb, ppid, echo, 0, echo.size)
            }
            override fun closed(tcb: SctpTcb) {}
        }, secret, half = 4).open()
        val tcbs = arrayOfNulls<SctpTcb>(users.size)
        val got = arrayOfNulls<String>(users.size)
        var associated = 0
        var answered = 0
        val client = SctpUdpPort(LinuxChannelOperations(), LinuxReactorOperations(), clientAt, object : SctpUpper {
            override fun associated(tcb: SctpTcb) { tcbs[tcb.upperData as Int] = tcb; associated++ }
            override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
                got[tcb.upperData as Int] = bytes.decodeToString(offset, offset + length)
                answered++
            }
            override fun closed(tcb: SctpTcb) {}
        }, secret, half = 4).open()
        val thread = newSingleThreadContext("sctp-server-small")
        val serving = launch(thread) { serveLoop(server) }
        // Waits under the 1 s initial RTO: a datagram that only a T1/T3 timer would take fails the test.
        for (i in users.view) client.connect(serverAt).upperData = i
        drive(client, 900) { associated == users.size }
        assertEquals(users.size, associated)
        for (i in users.view) {
            val request = "GET /posts/$i HTTP/1.1\r\n\r\n".encodeToByteArray()
            client.submit(tcbs[i]!!, PPID_HTX11, request, 0, request.size)
        }
        drive(client, 900) { answered == users.size }
        for (i in users.view) assertEquals(expected[i], got[i])
        assertEquals(users.size, server.table.count)
        client.stop(); server.stop()
        serving.join()
        thread.close()
        client.close(); server.close()
    }

    /** A UDP socket on 127.0.0.1, nonblocking: its descriptor and its port. */
    fun udp(): Pair<Int, Int> = memScoped {
        val fd = socket(AF_INET, 2 or 0x800, 0)   // SOCK_DGRAM, SOCK_NONBLOCK
        check(fd >= 0) { "socket: $errno" }
        val address = alloc<sockaddr_in>()
        memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
        address.sin_family = AF_INET.convert()
        address.sin_addr.s_addr = 0x0100007fu
        check(bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind: $errno" }
        val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
        check(getsockname(fd, address.ptr.reinterpret(), length.ptr) == 0)
        val network = address.sin_port.toInt() and 0xffff
        fd to (((network and 0xff) shl 8) or (network ushr 8))
    }

    /** A send window: [payload], then the destination 127.0.0.1:[port] as a Linux sockaddr_in in the next 28 bytes. */
    fun sendWindow(payload: ByteArray, port: Int): ByteBuffer {
        val w = ByteArray(1480)
        payload.copyInto(w)
        val at = payload.size
        w[at] = 2; w[at + 1] = 0
        w[at + 2] = (port ushr 8).toByte(); w[at + 3] = port.toByte()
        w[at + 4] = 127; w[at + 7] = 1
        return ByteBuffer(w, 0, 1480).also { it.limit(payload.size + UringOp.DATAGRAM_ADDRESS) }
    }

    /** The wheel alone: a datagram out of one socket and into another by window index, -EAGAIN on an empty socket, -EBUSY and -EINVAL. */
    @Test
    fun theWheelSendsReceivesAndRefusesABusyOrForeignWindow() {
        val wheel = DatagramWheel.open(8)!!
        val (a, portA) = udp()
        val (b, portB) = udp()
        val ud = LongArray(8)
        val res = IntArray(8)
        val hello = "hello wheel".encodeToByteArray()

        // A window outside the wheel is refused; a window already in the ring is busy.
        assertEquals(-22, wheel.prep(true, a, sendWindow(hello, portB), 8, 0))
        assertEquals(0, wheel.prep(true, a, sendWindow(hello, portB), 3, 0))
        assertEquals(-16, wheel.prep(true, a, sendWindow(hello, portB), 3, 0))
        assertEquals(1, wheel.submit())
        assertEquals(1, wheel.reap(1, ud, res))
        assertEquals(3L, ud[0])
        assertEquals(hello.size, res[0])

        // The datagram is on B's socket: a MSG_DONTWAIT receive takes it with the sender's address in the window's tail.
        val inbox = ByteBuffer(ByteArray(1480), 0, 1480)
        assertEquals(0, wheel.prep(false, b, inbox, 1, UringOp.MSG_DONTWAIT))
        wheel.submit()
        assertEquals(1, wheel.reap(1, ud, res))
        assertEquals(1L, ud[0])
        assertEquals(hello.size, res[0])
        assertEquals("hello wheel", inbox.array().decodeToString(0, hello.size))
        val sender = inbox.array()
        val from = 1480 - UringOp.DATAGRAM_ADDRESS
        assertEquals(2, sender[from].toInt())
        assertEquals(portA, ((sender[from + 2].toInt() and 0xff) shl 8) or (sender[from + 3].toInt() and 0xff))

        // The socket is empty now: the same window, armed again, completes at once with -EAGAIN.
        assertEquals(0, wheel.prep(false, b, inbox, 1, UringOp.MSG_DONTWAIT))
        wheel.submit()
        assertEquals(1, wheel.reap(1, ud, res))
        assertEquals(-11, res[0])
        assertEquals(0, wheel.pending)

        wheel.close()
        close(a); close(b)
    }
}
