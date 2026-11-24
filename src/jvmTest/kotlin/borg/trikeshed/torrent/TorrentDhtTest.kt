package borg.trikeshed.torrent

import borg.trikeshed.htx.client.ipfs.MemoryBlockStore
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

/** BEP 5 lookups against DHT nodes written by hand on loopback UDP. */
class TorrentDhtTest {
    val target = InfoHash(ByteArray(20) { (0x40 + it).toByte() })

    /** The bootstrap node knows no peer but names a node closer to the target; that node holds the peer. */
    @Test fun lookupFollowsNodesToPeers(): Unit = runBlocking {
        val holder = Node(ByteArray(20) { (0x40 + it).toByte() }.also { it[19] = 0 }, _l[PeerAddress("10.1.2.3", 51413)], emptyList())
        val router = Node(ByteArray(20) { 0x7f }, emptyList(), _l[holder.id j holder.address])
        try {
            val found = withTimeout(30_000) { TorrentDht.peers(target, s_[router.address], 20_000) }
            assertEquals(_l[PeerAddress("10.1.2.3", 51413)], found.peers.view.toList())
            assertEquals(2, found.asked); assertEquals(2, found.answered)
            assertEquals(1, router.queries.get()); assertEquals(1, holder.queries.get())
            assertEquals(153, found.closest)
        } finally { holder.socket.close(); router.socket.close() }
    }

    /** A leech with only a DHT node to start from finds the seed through it and downloads from the seed. */
    @Test fun sessionFindsSeedThroughDht(): Unit = runBlocking {
        val swarm = TorrentSwarmTest()
        val seedAddress = PeerAddress("127.0.0.1", ServerSocket(0).use { it.localPort })
        val seed = TorrentEngine(MemoryBlockStore(), this, seedAddress)
        val node = Node(ByteArray(20) { 1 }, _l[seedAddress], emptyList())
        val leech = TorrentEngine(MemoryBlockStore(), this, PeerAddress("127.0.0.1", ServerSocket(0).use { it.localPort }), s_[node.address])
        try {
            seed.seedTorrent(swarm.metainfo, swarm.content)
            val session = leech.addTorrent(swarm.metainfo) as TorrentSession
            assertContentEquals(swarm.content, withTimeout(60_000) { session.downloadAll() })
            assertEquals<List<PeerAddress>?>(_l[seedAddress], session.lookup?.peers?.view?.toList())
            assertTrue(node.queries.get() > 0)
        } finally { leech.close(); seed.close(); node.socket.close() }
    }

    /** Answers every get_peers with [values] (compact peers) and [nodes] (compact node infos), and counts them. */
    class Node(val id: ByteArray, val values: List<PeerAddress>, val nodes: List<Join<ByteArray, PeerAddress>>) {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val queries = AtomicInteger()
        val address = PeerAddress("127.0.0.1", socket.localPort)
        init {
            thread(isDaemon = true) {
                val buffer = ByteArray(2048)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { socket.receive(packet) } catch (closed: Exception) { break }
                    val query = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    if ("9:get_peers" !in query) continue
                    queries.incrementAndGet()
                    val compactNodes = nodes.joinToString("") { text(it.a) + compact(it.b) }
                    val reply = "d1:rd2:id20:" + text(id) + "5:nodes${compactNodes.length}:" + compactNodes + "5:token1:x6:valuesl" +
                        values.joinToString("") { "6:" + compact(it) } + "ee1:t2:" + query.substringAfter("1:t2:").take(2) + "1:y1:re"
                    val bytes = reply.toByteArray(Charsets.ISO_8859_1)
                    socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                }
            }
        }
        fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)
        fun compact(peer: PeerAddress) = text(peer.host.split('.').map { it.toInt().toByte() }.toByteArray() + _a[(peer.port ushr 8).toByte(), peer.port.toByte()])
    }
}
