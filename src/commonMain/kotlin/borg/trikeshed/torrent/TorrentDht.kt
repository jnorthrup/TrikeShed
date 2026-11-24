package borg.trikeshed.torrent

import borg.trikeshed.cursor.monotonicNanoTime
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.UringIOException
import kotlinx.coroutines.*
import kotlin.random.Random

/**
 * BEP 5, the lookup half: get_peers toward an info-hash (its first 20 bytes for v2), from the bootstrap nodes
 * toward the [K] closest nodes known, [ALPHA] queries in flight, each over its own connected UDP descriptor on one
 * ring. It ends when the [K] closest nodes known have all been asked, or at its time limit. It sends no
 * announce_peer and answers no query: nothing here makes this node findable.
 */
object TorrentDht {
    const val K = 8
    const val ALPHA = 8
    const val QUERY_MILLIS = 3_000L
    /** The public routers, as host and port; a caller resolves them. */
    val ROUTERS = s_["router.bittorrent.com" j 6881, "dht.transmissionbt.com" j 6881, "router.utorrent.com" j 6881, "dht.libtorrent.org" j 25401]

    /** What one lookup asked and found. */
    class Lookup(val peers: Series<PeerAddress>, val asked: Int, val answered: Int, val closest: Int)

    suspend fun peers(infoHash: InfoHash, bootstrap: Series<PeerAddress>, limitMillis: Long = 60_000): Lookup = coroutineScope {
        val target = infoHash.wireBytes
        val id = Random.nextBytes(20)
        val ring = TorrentSocketRing(this)
        try {
            val known = LinkedHashMap<PeerAddress, ByteArray?>()
            for (node in bootstrap.view) known[node] = null
            val asked = mutableSetOf<PeerAddress>()
            val peers = LinkedHashSet<PeerAddress>()
            var answered = 0
            val order = Comparator<PeerAddress> { x, y -> distance(known[x], target, known[y]) }
            val deadline = monotonicNanoTime() + limitMillis * 1_000_000
            var transaction = 0
            while (monotonicNanoTime() < deadline) {
                val round = known.keys.sortedWith(order).take(K).filter { it !in asked }.take(ALPHA)
                if (round.isEmpty()) break
                asked += round
                val replies = round.map { node ->
                    val query = TorrentBencode.encode(TorrentBencode.dictionary(
                        "a" to TorrentBencode.dictionary("id" to BencodeValue.Bytes(id), "info_hash" to BencodeValue.Bytes(target)),
                        "q" to BencodeValue.Bytes("get_peers".encodeToByteArray()),
                        "t" to BencodeValue.Bytes(byteArrayOf((transaction shr 8).toByte(), transaction++.toByte())),
                        "y" to BencodeValue.Bytes("q".encodeToByteArray())))
                    async { node j runCatching { ring.query(node, query, QUERY_MILLIS) }.getOrNull() }
                }.awaitAll()
                for (reply in replies) {
                    val answer = reply.b?.let { bytes -> runCatching { (TorrentBencode.decode(bytes) as BencodeValue.Dictionary)["r"] as BencodeValue.Dictionary }.getOrNull() }
                    if (answer == null) { known.remove(reply.a); continue }
                    answered++
                    known[reply.a] = (answer["id"] as? BencodeValue.Bytes)?.bytes?.takeIf { it.size == 20 }
                    (answer["values"] as? BencodeValue.ListValue)?.values?.view?.forEach { value ->
                        val compact = (value as? BencodeValue.Bytes)?.bytes
                        if (compact != null && compact.size == 6) compactAddress(compact, 0)?.let(peers::add)
                    }
                    val nodes = (answer["nodes"] as? BencodeValue.Bytes)?.bytes ?: continue
                    for (at in 0..nodes.size - 26 step 26) {
                        val node = compactAddress(nodes, at + 20) ?: continue
                        if (node !in asked && node !in known) known[node] = nodes.copyOfRange(at, at + 20)
                    }
                }
            }
            val nearest = known.keys.filter { it in asked && known[it] != null }.minWithOrNull(order)
            Lookup(peers.toList().toSeries(), asked.size, answered, nearest?.let { leading(known.getValue(it)!!, target) } ?: 0)
        } finally { withContext(NonCancellable) { ring.close() } }
    }

    /** Compares the XOR distances of two node ids to [target]; a node of unknown id is farthest. */
    fun distance(x: ByteArray?, target: ByteArray, y: ByteArray?): Int {
        if (x == null || y == null) return (if (x == null) 1 else 0) - (if (y == null) 1 else 0)
        for (i in 0 until 20) {
            val a = (x[i].toInt() xor target[i].toInt()) and 255; val b = (y[i].toInt() xor target[i].toInt()) and 255
            if (a != b) return a - b
        }
        return 0
    }

    /** The leading bits [id] shares with [target]. */
    fun leading(id: ByteArray, target: ByteArray): Int {
        for (i in 0 until 20) {
            val x = (id[i].toInt() xor target[i].toInt()) and 255
            if (x != 0) return i * 8 + x.countLeadingZeroBits() - 24
        }
        return 160
    }

    /** A compact IPv4 address and port at [at]; null for port zero. */
    fun compactAddress(bytes: ByteArray, at: Int): PeerAddress? {
        val port = ((bytes[at + 4].toInt() and 255) shl 8) or (bytes[at + 5].toInt() and 255)
        return if (port == 0) null else PeerAddress((0..3).joinToString(".") { (bytes[at + it].toInt() and 255).toString() }, port)
    }

    /** One request and the first datagram back within [millis] over a UDP descriptor connected to [node]; null when none came. */
    internal suspend fun TorrentSocketRing.query(node: PeerAddress, request: ByteArray, millis: Long): ByteArray? {
        val endpoint = ByteBuffer(datagramSockaddr(node))
        val fd = execute(UringOp.SOCKET, 2, length = 17, offset = (2 or 0x800).toLong())
        if (fd < 0) throw UringIOException(UringOp.SOCKET, fd)
        try {
            val expires = monotonicNanoTime() + millis * 1_000_000
            while (true) {
                val result = execute(UringOp.CONNECT, fd, endpoint)
                if (result == 0 || result == -106) break
                if (result != -11 && result != -4 && result != -115 && result != -114) throw UringIOException(UringOp.CONNECT, result)
                if (monotonicNanoTime() >= expires) return null
                delay(5)
            }
            while (true) {
                val sent = execute(UringOp.SEND, fd, ByteBuffer(request))
                if (sent >= 0) break
                if (sent != -11 && sent != -4) throw UringIOException(UringOp.SEND, sent)
                if (monotonicNanoTime() >= expires) return null
                delay(5)
            }
            val input = ByteBuffer(65507)
            while (monotonicNanoTime() < expires) {
                input.clear()
                val count = execute(UringOp.RECV, fd, input)
                if (count >= 0) return input.array().copyOf(count)
                if (count != -11 && count != -4) throw UringIOException(UringOp.RECV, count)
                delay(5)
            }
            return null
        } finally { withContext(NonCancellable) { execute(UringOp.CLOSE, fd) } }
    }
}
