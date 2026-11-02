@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package borg.trikeshed.sctp

import borg.trikeshed.htx.PPID_HTX11
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ChannelResult
import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.channels.endpoint
import borg.trikeshed.userspace.nio.channels.parseWhole
import borg.trikeshed.userspace.nio.channels.sockaddr
import borg.trikeshed.userspace.nio.channels.unixName
import borg.trikeshed.userspace.nio.channels.unixSlot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SctpUdpPort over AF_UNIX datagram sockets: the same associations as over UDP, for hops on one host. */
class SctpUnixPortTest {
    val linux = Platform.osFamily == OsFamily.LINUX
    /** Linux: abstract names, and a client the kernel names (autobind). macOS has neither: both bind file names. */
    val serverName = unixName(if (linux) "@sctp-unix-server" else "/tmp/sctp-unix-server")
    val clientName = unixName(if (linux) "" else "/tmp/sctp-unix-client")
    val secret = "sctp over unix".encodeToByteArray()

    fun unlinkFiles() {
        if (!linux) for (path in s_["/tmp/sctp-unix-server", "/tmp/sctp-unix-client"].view) platform.posix.unlink(path)
    }

    /** The server side: every message comes back as "echo:" and the message, on the same association. */
    class Echo(val width: Int = 0) : SctpUpper {
        lateinit var port: SctpUdpPort
        override fun associated(tcb: SctpTcb) {}
        override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
            val echo = ("echo:" + bytes.decodeToString(offset, offset + length)).padEnd(width, '.').encodeToByteArray()
            port.table.send(tcb, ppid, echo, 0, echo.size)
        }
        override fun closed(tcb: SctpTcb) {}
    }

    /** Two ports on AF_UNIX datagrams, each with its own owner loop; a 20,000-byte HTX1 request comes back as its echo. */
    @Test
    fun handshakeAndEchoOfTwentyThousandBytesOverUnixDatagrams() = runBlocking {
        unlinkFiles()
        val echo = Echo()
        val server = SctpUdpPort(PosixChannelOperations(), PosixReactorOperations(), serverName, 4001, echo, secret).open()
        echo.port = server
        val up = CompletableDeferred<SctpTcb>()
        val got = CompletableDeferred<String>()
        val client = SctpUdpPort(PosixChannelOperations(), PosixReactorOperations(), clientName, 4002, object : SctpUpper {
            override fun associated(tcb: SctpTcb) { up.complete(tcb) }
            override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
                got.complete(bytes.decodeToString(offset, offset + length))
            }
            override fun closed(tcb: SctpTcb) {}
        }, secret).open()
        val serving = launch { server.run() }
        val dialing = launch { client.run() }
        val t = client.connect(serverName, 4001)
        assertEquals(t, withTimeout(5_000) { up.await() })
        val request = "GET /feed HTTP/1.1\r\n\r\n".padEnd(20_000, '.')
        val bytes = request.encodeToByteArray()
        client.submit(t, PPID_HTX11, bytes, 0, bytes.size)
        assertEquals("echo:$request", withTimeout(5_000) { got.await() })
        assertEquals(1, server.table.count)
        client.stop(); server.stop()
        dialing.join(); serving.join()
        client.close(); server.close()
        unlinkFiles()
    }

    /**
     * A wheel of 2 × 4 windows under 16 associations whose echoes take 3 packets each: sends run out of windows inside
     * table calls, and the receives reaped there reach the table in the same wake. Waits under the 1 s initial RTO.
     */
    @Test
    fun smallWheelServesMoreAssociationsThanWindowsOverUnixDatagrams() = runBlocking {
        unlinkFiles()
        val users = (0 until 16).toSeries()
        val expected = users α { "echo:GET /posts/$it HTTP/1.1\r\n\r\n".padEnd(2_500, '.') }
        val echo = Echo(width = 2_500)
        val server = SctpUdpPort(PosixChannelOperations(), PosixReactorOperations(), serverName, 4001, echo, secret, half = 4).open()
        echo.port = server
        val tcbs = arrayOfNulls<SctpTcb>(users.size)
        val got = arrayOfNulls<String>(users.size)
        var associated = 0
        var answered = 0
        val up = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val client = SctpUdpPort(PosixChannelOperations(), PosixReactorOperations(), clientName, 4002, object : SctpUpper {
            override fun associated(tcb: SctpTcb) {
                tcbs[tcb.upperData as Int] = tcb
                if (++associated == users.size) up.complete(Unit)
            }
            override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
                got[tcb.upperData as Int] = bytes.decodeToString(offset, offset + length)
                if (++answered == users.size) done.complete(Unit)
            }
            override fun closed(tcb: SctpTcb) {}
        }, secret, half = 4).open()
        val serving = launch { server.run() }
        val dialing = launch { client.run() }
        for (i in users.view) client.connect(serverName, 4001).upperData = i
        withTimeout(900) { up.await() }
        for (i in users.view) {
            val request = "GET /posts/$i HTTP/1.1\r\n\r\n".encodeToByteArray()
            client.submit(tcbs[i]!!, PPID_HTX11, request, 0, request.size)
        }
        withTimeout(900) { done.await() }
        for (i in users.view) assertEquals(expected[i], got[i])
        assertEquals(users.size, server.table.count)
        client.stop(); server.stop()
        dialing.join(); serving.join()
        client.close(); server.close()
        unlinkFiles()
    }

    /**
     * A ring that refuses its first [refusals] datagram sends with -EAGAIN, as a unix peer with a full queue does: only
     * sends that ask for it with MSG_DONTWAIT, or every send when [dontWaitOnly] is false.
     */
    class RefusingChannels(val inner: ChannelOperations, var refusals: Int, val dontWaitOnly: Boolean = true) : ChannelOperations {
        override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = Handle(inner.openChannel(entries))

        inner class Handle(val real: ChannelOperations.ChannelHandle) : ChannelOperations.ChannelHandle by real {
            val refused = ArrayDeque<ChannelResult>()

            override fun sendmsg(fd: Int, datagram: ByteBuffer, userData: Long, flags: Int): Int {
                if (dontWaitOnly && flags and UringOp.MSG_DONTWAIT == 0 || refusals == 0) return real.sendmsg(fd, datagram, userData, flags)
                refusals--
                refused.add(ChannelResult(fd, -11, userData))
                return 0
            }

            override fun wait(minComplete: Int): List<ChannelResult> {
                if (refused.isEmpty()) return real.wait(minComplete)
                val all = refused.toList() + real.wait(0)
                refused.clear()
                return all
            }

            /** The port reaps through waitInto; delegation would hand it to [real] and skip the refusals above. */
            override fun waitInto(minComplete: Int, userData: LongArray, res: IntArray): Int =
                super<ChannelOperations.ChannelHandle>.waitInto(minComplete, userData, res)
        }
    }

    /**
     * A peer whose queue is full refuses a send at once. The port keeps the packet and sends it again at a later wake:
     * the association and an echo complete in far less than the 1 s a lost INIT would wait for its timer.
     */
    @Test
    fun refusedSendsGoOutAgainWithoutWaitingForATimer() = runBlocking {
        unlinkFiles()
        val echo = Echo()
        val server = SctpUdpPort(PosixChannelOperations(), PosixReactorOperations(), serverName, 4001, echo, secret).open()
        echo.port = server
        val up = CompletableDeferred<SctpTcb>()
        val got = CompletableDeferred<String>()
        val refusing = RefusingChannels(PosixChannelOperations(), refusals = 6)
        val client = SctpUdpPort(refusing, PosixReactorOperations(), clientName, 4002, object : SctpUpper {
            override fun associated(tcb: SctpTcb) { up.complete(tcb) }
            override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
                got.complete(bytes.decodeToString(offset, offset + length))
            }
            override fun closed(tcb: SctpTcb) {}
        }, secret).open()
        val serving = launch { server.run() }
        val dialing = launch { client.run() }
        val t = client.connect(serverName, 4001)
        assertEquals(t, withTimeout(900) { up.await() })
        val request = "GET /feed HTTP/1.1\r\n\r\n".padEnd(3_000, '.')
        val bytes = request.encodeToByteArray()
        client.submit(t, PPID_HTX11, bytes, 0, bytes.size)
        assertEquals("echo:$request", withTimeout(900) { got.await() })
        assertEquals(6L, client.refused)
        assertTrue(refusing.refusals == 0)
        client.stop(); server.stop()
        dialing.join(); serving.join()
        client.close(); server.close()
        unlinkFiles()
    }

    /**
     * A ring whose waits return the receives' completions first and hold the sends' back to the next wait: the sends
     * complete late, as a socket without send space completes them. [sent] counts the SENDMSGs the ring took.
     */
    class LateSendChannels(val inner: ChannelOperations, val half: Int) : ChannelOperations {
        var sent = 0

        override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = Handle(inner.openChannel(entries))

        inner class Handle(val real: ChannelOperations.ChannelHandle) : ChannelOperations.ChannelHandle by real {
            val held = ArrayDeque<ChannelResult>()

            override fun sendmsg(fd: Int, datagram: ByteBuffer, userData: Long, flags: Int): Int {
                sent++
                return real.sendmsg(fd, datagram, userData, flags)
            }

            override fun wait(minComplete: Int): List<ChannelResult> {
                if (held.isNotEmpty()) {
                    val late = held.toList() + real.wait(0)
                    held.clear()
                    return late
                }
                val all = real.wait(minComplete)
                val receives = all.filter { it.userData.toInt() and half == 0 }
                if (receives.isEmpty()) return all
                held += all.filter { it.userData.toInt() and half != 0 }
                return receives
            }

            /** The port reaps through waitInto; delegation would hand it to [real] and skip the holding above. */
            override fun waitInto(minComplete: Int, userData: LongArray, res: IntArray): Int =
                super<ChannelOperations.ChannelHandle>.waitInto(minComplete, userData, res)
        }
    }

    /**
     * All send windows busy, their completions late: the third packet of a port of two send windows waits for one to
     * complete, as a UDP send does; it is not given up on because the wait returned receives only.
     */
    @Test
    fun aPacketWaitsForASendWindowStillInFlightOverUnixDatagrams() = runBlocking {
        unlinkFiles()
        val late = LateSendChannels(PosixChannelOperations(), half = 2)
        val sink = SctpUdpPort(PosixChannelOperations(), PosixReactorOperations(), serverName, 4001, Echo(), secret).open()
        val port = SctpUdpPort(late, PosixReactorOperations(), clientName, 4002, Echo(), secret, half = 2).open()
        val at = port.pathOf(unixSlot(serverName))
        port.fill()
        val packet = ByteArray(40)
        for (i in (0 until 3).toSeries().view) port.send(at, packet, packet.size)
        assertEquals(3, late.sent)
        assertEquals(0L, port.lost)
        port.close(); sink.close()
        unlinkFiles()
    }

    /** UDP is as it was: a send the kernel refuses is lost, not parked and sent again. */
    @Test
    fun aUdpSendTheKernelRefusesIsLostAsBefore() = runBlocking {
        val refusing = RefusingChannels(PosixChannelOperations(), refusals = 1, dontWaitOnly = false)
        val at = parseWhole("127.0.0.1:34702") { endpoint() }!!
        val port = SctpUdpPort(refusing, PosixReactorOperations(), parseWhole("127.0.0.1:34701") { endpoint() }!!, Echo(), secret).open()
        port.send(port.pathOf(at.sockaddr), ByteArray(40), 40)
        port.submitAndReap()
        assertEquals(0, port.blockedCount)
        assertEquals(0L, port.refused)
        assertEquals(port.half, port.freeCount)
        assertEquals(0, refusing.refusals)
        port.close()
    }
}
