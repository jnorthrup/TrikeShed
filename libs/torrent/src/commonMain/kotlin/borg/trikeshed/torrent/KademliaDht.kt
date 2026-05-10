package borg.trikeshed.torrent

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.Job
import kotlin.random.Random

/**
 * Kademlia DHT (BEP 5) — distributed hash table for peer discovery.
 *
 * Pattern A CCEK: companion object Key, SupervisorJob choreography.
 *
 * Key concepts:
 *   - 160-bit node IDs (SHA-1 of IP + port)
 *   - XOR distance metric
 *   - k-buckets: up to K=8 nodes per distance bucket
 *   - find_node, get_peers, announce_peer operations
 */
class KademliaDht(
    private val ourNodeId: ByteArray = DEFAULT_NODE_ID,
    private val k: Int = 8,
    parentJob: Job? = null,
) : AsyncContextElement(parentJob = parentJob) {
    companion object Key : AsyncContextKey<KademliaDht>() {
        val DEFAULT_NODE_ID: ByteArray = Random.nextBytes(20)

        val BOOTSTRAP_NODES = listOf(
            Node(hexToBytes("abcdef0123456789abcdef0123456789abcdef01"), "router.bittorrent.com", 6881),
            Node(hexToBytes("abcdef0123456789abcdef0123456789abcdef02"), "dht.transmissionbt.com", 6881),
            Node(hexToBytes("abcdef0123456789abcdef0123456789abcdef03"), "router.utorrent.com", 6881),
        )

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length == 40)
            return ByteArray(20) { i ->
                ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
            }
        }

        fun randomNodeId(): ByteArray = Random.nextBytes(20)
        fun nodeId(ip: String, port: Int, sha1: (ByteArray) -> ByteArray): ByteArray =
            sha1("$ip:$port".encodeToByteArray())
    }
    override val key: AsyncContextKey<KademliaDht> get() = Key

    data class Node(val id: ByteArray, val ip: String, val port: Int)

    private val buckets = Array(160) { mutableListOf<Node>() }

    // ── Lifecycle ─────────────────────────────────────────────────

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
        // Bootstrap the routing table with well-known nodes
        for (node in BOOTSTRAP_NODES) {
            insert(node)
        }
        state = ElementState.ACTIVE
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            supervisor.cancel()
            super.close()
        }
    }

    // ── Routing table ─────────────────────────────────────────────

    fun distance(a: ByteArray, b: ByteArray): ByteArray {
        val d = ByteArray(20)
        for (i in 0 until 20) d[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return d
    }

    fun bucketIndex(nodeId: ByteArray): Int {
        val d = distance(ourNodeId, nodeId)
        for (i in 0 until 20) {
            if (d[i].toInt() and 0xFF == 0) continue
            var bit = 7
            var v = d[i].toInt() and 0xFF
            while (v > 0) { v = v shr 1; bit-- }
            return i * 8 + (7 - bit)
        }
        return 159
    }

    fun insert(node: Node) {
        val idx = bucketIndex(node.id)
        val bucket = buckets[idx]
        if (bucket.none { n -> n.id.contentEquals(node.id) }) {
            if (bucket.size < k) bucket.add(node)
            else {
                bucket.removeFirstOrNull()
                bucket.add(node)
            }
        }
    }

    fun findClosest(target: ByteArray, count: Int = k): List<Node> {
        val all = mutableListOf<Node>()
        for (bucket in buckets) { all.addAll(bucket) }
        all.sortBy { xorDistance(it.id, target) }
        return all.take(count)
    }

    // ── Peer discovery ────────────────────────────────────────────

    /**
     * Discover peers for an info hash via recursive DHT lookup.
     * Runs under this element's SupervisorJob — cancellation propagates.
     */
    suspend fun findPeers(
        infoHash: ByteArray,
        sendQuery: suspend (ByteArray, Node) -> DhtProtocol.DhtMessage?,
    ): List<Node> = kotlinx.coroutines.withContext(supervisor) {
        val result = mutableListOf<Node>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Node>()
        queue.addAll(findClosest(infoHash, k))

        while (isActive && queue.isNotEmpty() && result.size < 50) {
            val node = queue.removeFirst()
            val hexId = node.id.joinToString("") { byteToKmpHex(it) }
            if (hexId in visited) continue
            visited.add(hexId)

            val resp = sendQuery(infoHash, node) ?: continue
            if (resp.type == 'r' && resp.response != null) {
                val peers = DhtProtocol.peers6(resp.response)
                result.addAll(peers)
                if (peers.isEmpty()) {
                    val closerNodes = DhtProtocol.nodes6(resp.response)
                    for (cn in closerNodes) {
                        insert(cn)
                        queue.add(cn)
                    }
                }
            }
        }
        result.distinctBy { "${it.ip}:${it.port}" }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun xorDistance(a: ByteArray, b: ByteArray): Long {
        var result = 0L
        for (i in 0 until minOf(a.size, b.size)) {
            result = (result shl 8) or ((a[i].toInt() xor b[i].toInt()).toLong() and 0xFF)
        }
        return result
    }

    private fun byteToKmpHex(b: Byte): String {
        val i = b.toInt() and 0xFF
        val hexChars = "0123456789abcdef".toCharArray()
        return "${hexChars[i shr 4]}${hexChars[i and 0xF]}"
    }

    private val isActive: Boolean get() = state.isAtLeast(ElementState.ACTIVE) && state.isLessThan(ElementState.DRAINING)

}
