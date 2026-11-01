package borg.trikeshed.sctp

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
}
