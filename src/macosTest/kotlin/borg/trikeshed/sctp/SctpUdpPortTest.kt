package borg.trikeshed.sctp

import borg.trikeshed.htx.PPID_HTX11
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.endpoint
import borg.trikeshed.userspace.nio.channels.parseWhole
import borg.trikeshed.userspace.nio.channels.spi.KqueueReactorOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class SctpUdpPortTest {
    /** Two ports on loopback UDP, each with its own owner loop and kqueue; the server echoes on the same association. */
    @Test
    fun handshakeAndEchoOverLoopbackUdp() = runBlocking {
        val channels = PosixChannelOperations()
        val secret = "sctp over udp".encodeToByteArray()
        val serverAt = parseWhole("127.0.0.1:39899") { endpoint() }!!
        val clientAt = parseWhole("127.0.0.1:39900") { endpoint() }!!
        lateinit var server: SctpUdpPort
        server = SctpUdpPort(channels, KqueueReactorOperations(), serverAt, object : SctpUpper {
            override fun associated(tcb: SctpTcb) {}
            override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
                val echo = ("echo:" + bytes.decodeToString(offset, offset + length)).encodeToByteArray()
                server.table.send(tcb, ppid, echo, 0, echo.size)
            }
            override fun closed(tcb: SctpTcb) {}
        }, secret).open()
        val up = CompletableDeferred<SctpTcb>()
        val got = CompletableDeferred<String>()
        val client = SctpUdpPort(channels, KqueueReactorOperations(), clientAt, object : SctpUpper {
            override fun associated(tcb: SctpTcb) { up.complete(tcb) }
            override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
                got.complete(bytes.decodeToString(offset, offset + length))
            }
            override fun closed(tcb: SctpTcb) {}
        }, secret).open()
        val serving = launch { server.run() }
        val dialing = launch { client.run() }
        val t = client.connect(serverAt)
        assertEquals(t, withTimeout(5_000) { up.await() })
        val request = "GET /feed HTTP/1.1\r\n\r\n".encodeToByteArray()
        client.submit(t, 0x48545831, request, 0, request.size)
        assertEquals("echo:GET /feed HTTP/1.1\r\n\r\n", withTimeout(5_000) { got.await() })
        assertEquals(1, server.table.count)
        client.stop(); server.stop()
        dialing.join(); serving.join()
        client.close(); server.close()
    }

    /**
     * A wheel of 2 × 4 windows under 16 associations whose echoes take 3 packets each: sends run out of windows inside
     * table calls, and the receives reaped there reach the table in the same wake.
     */
    @Test
    fun smallWheelServesMoreAssociationsThanWindows() = runBlocking {
        val channels = PosixChannelOperations()
        val secret = "sctp over udp".encodeToByteArray()
        val serverAt = parseWhole("127.0.0.1:39901") { endpoint() }!!
        val clientAt = parseWhole("127.0.0.1:39902") { endpoint() }!!
        val users = (0 until 16).toSeries()
        val expected = users α { "echo:GET /posts/$it HTTP/1.1\r\n\r\n".padEnd(2_500, '.') }
        lateinit var server: SctpUdpPort
        server = SctpUdpPort(channels, KqueueReactorOperations(), serverAt, object : SctpUpper {
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
        val up = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val client = SctpUdpPort(channels, KqueueReactorOperations(), clientAt, object : SctpUpper {
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
        // Waits under the 1 s initial RTO: a datagram that only a T1/T3 timer would take fails the test.
        for (i in users.view) client.connect(serverAt).upperData = i
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
    }
}
