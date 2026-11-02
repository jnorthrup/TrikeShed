package borg.trikeshed.sctp

import borg.trikeshed.lib.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SctpTcbTableTest {
    /**
     * Two tables over an in-memory link; [lose] decides, by packet count, which packets the link drops. The clock
     * stands still while packets flow and moves [idle] when both sides idle with a timer armed.
     */
    class Link(val lose: (Int) -> Boolean = { false }, val idle: Long = 1_000_000_000L) {
        var now = 0L
        var packets = 0
        val toServer = ArrayDeque<ByteArray>()
        val toClient = ArrayDeque<ByteArray>()
        val clientGot = mutableListOf<String>()
        val clientUp = mutableListOf<SctpTcb>()
        val closedClient = mutableListOf<SctpTcb>()
        val closedServer = mutableListOf<SctpTcb>()
        val serverUp = mutableListOf<SctpTcb>()
        val secret = "sctp cookie secret".encodeToByteArray()

        val client: SctpTcbTable = SctpTcbTable(5000, object : SctpUpper {
            override fun associated(tcb: SctpTcb) { clientUp += tcb }
            override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
                clientGot += bytes.decodeToString(offset, offset + length)
            }
            override fun closed(tcb: SctpTcb) { closedClient += tcb }
        }, object : SctpSink {
            override fun send(path: SctpPath, packet: ByteArray, length: Int) {
                if (!lose(packets++)) toServer.addLast(packet.copyOf(length))
            }
        }, secret, random = Random(1))

        /** The server echoes each message with a prefix, on the same association. */
        val server: SctpTcbTable = SctpTcbTable(9899, object : SctpUpper {
            override fun associated(tcb: SctpTcb) { serverUp += tcb }
            override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
                val echo = ("echo:" + bytes.decodeToString(offset, offset + length)).encodeToByteArray()
                server.send(tcb, ppid, echo, 0, echo.size)
            }
            override fun closed(tcb: SctpTcb) { closedServer += tcb }
        }, object : SctpSink {
            override fun send(path: SctpPath, packet: ByteArray, length: Int) {
                if (!lose(packets++)) toClient.addLast(packet.copyOf(length))
            }
        }, secret, random = Random(2))

        /** Wakes until both sides are idle: each wake receives everything queued, then flushes once. */
        fun pump() {
            var rounds = 0
            while (rounds++ < 100_000) {
                if (toServer.isEmpty() && toClient.isEmpty()) {
                    if (client.timedCount == 0 && server.timedCount == 0) return
                    now += idle
                }
                while (toServer.isNotEmpty()) { val p = toServer.removeFirst(); server.receive(p, p.size, 0, now) }
                server.flush(now)
                while (toClient.isNotEmpty()) { val p = toClient.removeFirst(); client.receive(p, p.size, 0, now) }
                client.flush(now)
            }
            error("link did not settle")
        }

        fun connect(): SctpTcb = client.connect(path = 0, peerPort = 9899, now = now)

        fun send(t: SctpTcb, text: String) {
            val b = text.encodeToByteArray()
            client.send(t, 0x48545831, b, 0, b.size)
            client.flush(now)
        }
    }

    @Test
    fun handshakeThenEchoOnOneAssociation() {
        val link = Link()
        val t = link.connect()
        link.pump()
        assertEquals(SctpState.ESTABLISHED, t.state)
        assertEquals(1, link.serverUp.size)
        val s = link.serverUp.single()
        assertEquals(t.localTag, s.peerTag)
        assertEquals(t.peerTag, s.localTag)
        assertEquals(ConnectionNumber(s.peerTag, s.localTag), t.number)
        link.send(t, "GET /posts/1 HTTP/1.1\r\n\r\n")
        link.pump()
        assertEquals(_l["echo:GET /posts/1 HTTP/1.1\r\n\r\n"], link.clientGot)
    }

    @Test
    fun aThousandAssociationsShareOnePath() {
        val link = Link()
        val opened = mutableListOf<SctpTcb>()
        for (i in (1..1000).toSeries().view) opened += link.connect()
        link.pump()
        assertEquals(1000, link.server.count)
        assertTrue(opened.all { it.state == SctpState.ESTABLISHED })
        for ((i, t) in opened.withIndex()) link.send(t, "user $i")
        link.pump()
        assertEquals(((0 until 1000).toSeries() α { "echo:user $it" }).view.toSet(), link.clientGot.toSet())
        assertEquals(1000, link.clientGot.size)
    }

    @Test
    fun aMessageLongerThanThePathFragmentsAndReassembles() {
        val link = Link()
        val t = link.connect()
        link.pump()
        val body = ((0 until 10_000).toSeries() α { 'a' + it % 26 }).view.joinToString("")
        link.send(t, body)
        link.pump()
        assertEquals(_l["echo:$body"], link.clientGot)
    }

    @Test
    fun everyThirdPacketLostStillDeliversInOrder() {
        val link = Link(lose = { it % 3 == 2 })
        val t = link.connect()
        link.pump()
        assertEquals(SctpState.ESTABLISHED, t.state)
        for (i in (1..20).toSeries().view) link.send(t, "m$i")
        link.pump()
        assertEquals(((1..20).toSeries() α { "echo:m$it" }).view.map { it }, link.clientGot)
    }

    @Test
    fun shutdownReleasesBothEnds() {
        val link = Link()
        val t = link.connect()
        link.pump()
        link.send(t, "last")
        link.client.close(t)
        link.client.flush(link.now)
        link.pump()
        assertEquals(_l["echo:last"], link.clientGot)
        assertEquals(SctpState.CLOSED, t.state)
        assertEquals(_l[t], link.closedClient)
        assertEquals(1, link.closedServer.size)
        assertEquals(0, link.client.count)
        assertEquals(0, link.server.count)
    }

    /** The array a message was copied into goes back to the pool when its last chunk is acknowledged, not before. */
    @Test
    fun anAcknowledgedMessageGoesBackToThePoolAndIsLeasedAgain() {
        val link = Link()
        val t = link.connect()
        link.pump()
        val pooled = link.client.pool.counts.sum()
        val body = ((0 until 5_000).toSeries() α { 'a' + it % 26 }).view.joinToString("")
        val bytes = body.encodeToByteArray()
        link.client.send(t, 0x48545831, bytes, 0, bytes.size)
        // Queued, not yet acknowledged: the 8 KB array is in use, none of that class is waiting.
        assertEquals(pooled, link.client.pool.counts.sum())
        link.client.flush(link.now)
        link.pump()
        assertEquals(_l["echo:$body"], link.clientGot)
        // The 5,000 bytes took an 8,192-byte array, which came back with the SACK of the fourth chunk.
        assertEquals(pooled + 1, link.client.pool.counts.sum())
        val again = link.client.pool.lease(5_000)
        assertEquals(8_192, again.size)
        assertEquals(pooled, link.client.pool.counts.sum())
    }

    /** A message that is lost and sent again reaches the peer whole: the pool never takes an array a chunk still reads. */
    @Test
    fun aLostMessageSentAgainIsNotOverwrittenByTheNextOnes() {
        val link = Link(lose = { it % 4 == 3 })
        val t = link.connect()
        link.pump()
        for (i in (1..40).toSeries().view) {
            link.send(t, "m$i".padEnd(900, '.'))
            if (i % 5 == 0) link.pump()
        }
        link.pump()
        assertEquals(((1..40).toSeries() α { "echo:m$it".padEnd(905, '.') }).view.map { it }, link.clientGot)
    }

    @Test
    fun aCorruptPacketIsDropped() {
        val link = Link()
        link.connect()
        val p = link.toServer.removeFirst()
        p[p.size - 1] = (p[p.size - 1].toInt() xor 0x40).toByte()
        link.server.receive(p, p.size, 0, link.now)
        assertEquals(1L, link.server.dropped)
        assertEquals(0, link.server.count)
    }

    @Test
    fun aLostChunkGoesAgainOnThreeMissIndicationsBeforeT3() {
        var drop = -1
        val link = Link(lose = { it == drop })
        val t = link.connect()
        link.pump()
        val started = link.now
        // The second DATA packet of a 15-chunk message: the SACKs for the chunks after it report it missing.
        drop = link.packets + 1
        val body = ((0 until 20_000).toSeries() α { 'a' + it % 26 }).view.joinToString("")
        link.send(t, body)
        link.pump()
        assertEquals(_l["echo:$body"], link.clientGot)
        // The link's clock moves only when both sides idle with a timer armed: T3 never had to fire.
        assertEquals(started, link.now)
    }

    @Test
    fun aLostChunkWithTooFewLaterChunksGoesAgainOnTheTimeThreshold() {
        var drop = -1
        val link = Link(lose = { it == drop }, idle = 10_000_000L)
        val t = link.connect()
        link.pump()
        val started = link.now
        // The second of four DATA packets: only two later chunks are acknowledged, short of three miss indications.
        drop = link.packets + 1
        val body = ((0 until 5_000).toSeries() α { 'a' + it % 26 }).view.joinToString("")
        link.send(t, body)
        link.pump()
        assertEquals(_l["echo:$body"], link.clientGot)
        // One idle step: the loss timer (9/8 RTT, at least 1 ms) went off; a PTO (over 25 ms) would take three.
        assertEquals(started + 10_000_000L, link.now)
    }
}
