package borg.trikeshed.skinnerbox

import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.contains
import borg.trikeshed.dht.embed.SubnetEmbedding
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.platform.spi.CacheTopology

/** Prefix authority and numeric XOR proximity are separate coordinates. */
object MeshRouting {
    const val MAX_PEERS = 256
    const val MAX_CANDIDATES = 20

    fun contains(outer: Subnet, inner: Subnet): Boolean = outer contains inner

    /** Lexicographic unsigned bytes of SHA-256 XOR are the numeric Kademlia distance. */
    fun compareDistance(target: ContentId, left: String, right: String): Int {
        requireId(left)
        requireId(right)
        val key = target.hex
        for (i in 0 until 64) {
            val a = left[i].digitToInt(16) xor key[i].digitToInt(16)
            val b = right[i].digitToInt(16) xor key[i].digitToInt(16)
            if (a != b) return a.compareTo(b)
        }
        return left.compareTo(right)
    }

    fun closest(
        key: ContentId,
        namespace: Subnet,
        authority: Subnet,
        peers: Series<MeshPeer>,
        limit: Int = MAX_CANDIDATES,
        embedding: SubnetEmbedding = SubnetEmbedding.Default,
    ): Series<MeshPeer> {
        require(peers.size in 0..MAX_PEERS) { "Mesh routing peer bound exceeded" }
        require(limit in 1..MAX_CANDIDATES) { "Mesh candidate bound" }
        require(contains(authority, namespace)) { "Namespace is outside routing authority" }
        val budget = minOf(limit, embedding.fanoutOf(namespace))
        if (budget == 0) return emptySeries()
        val seen = mutableSetOf<String>()
        val admitted = mutableListOf<MeshPeer>()
        for (peer in peers.view) {
            require(seen.add(peer.id)) { "Duplicate mesh peer identity" }
            if (contains(authority, peer.subnet) && contains(peer.subnet, namespace)) admitted.add(peer)
        }
        admitted.sortWith { a, b -> compareDistance(key, a.id, b.id) }
        val selected = admitted.take(budget).toTypedArray()
        return selected.size j { selected[it] }
    }

    fun requireId(id: String) {
        require(id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }) { "Mesh peer id must be SHA-256 hex" }
    }
}

/** Strict budget accounting; transport may schedule one separately bounded indivisible frame with surrounding yields. */
data class MeshStroke(val maxRequests: Int, val maxBytes: Long, val observedCacheBytes: Long? = null) {
    init {
        require(maxRequests in 1..1024 && maxBytes in 1..16L * 1024 * 1024) { "Invalid mesh stroke" }
        require(observedCacheBytes == null || observedCacheBytes > 0) { "Invalid observed cache size" }
    }

    fun admits(requests: Int, bytes: Long, nextBytes: Long): Boolean {
        require(requests >= 0 && bytes >= 0 && nextBytes >= 0)
        return requests < maxRequests && bytes <= maxBytes && nextBytes <= maxBytes - bytes
    }

    companion object {
        fun from(topology: CacheTopology, requests: Int = 3, bytes: Long = 256 * 1024): MeshStroke {
            require(bytes in 1..16L * 1024 * 1024)
            val observed = topology.l2Bytes?.takeIf { it > 0 } ?: topology.l1DataBytes?.takeIf { it > 0 }
            // Three lanes plus reactor state share the budget; topology alone cannot establish affinity.
            return MeshStroke(requests, minOf(bytes, observed?.let { maxOf(1L, it / 4) } ?: bytes), observed)
        }
    }
}

/** Full jitter keyed by peer, content and attempt; no shared mutable RNG or global wakeup. */
fun meshRetryDelay(peerId: String, key: ContentId, attempt: Int, baseMillis: Long, capMillis: Long): Long {
    MeshRouting.requireId(peerId)
    require(attempt in 0..63 && baseMillis > 0 && capMillis in baseMillis until Long.MAX_VALUE)
    var ceiling = baseMillis
    repeat(attempt) { ceiling = if (ceiling > capMillis / 2) capMillis else minOf(capMillis, ceiling * 2) }
    val digest = ContentId.of(CanonicalCbor.encodeMap(mapOf(
        "peer" to peerId, "key" to key.value, "attempt" to attempt.toLong(),
    ))).hex
    val draw = (digest.take(16).toULong(16) and Long.MAX_VALUE.toULong()).toLong()
    return draw % (ceiling + 1)
}
