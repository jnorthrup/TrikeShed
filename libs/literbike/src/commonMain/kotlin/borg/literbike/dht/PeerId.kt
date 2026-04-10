package borg.literbike.dht

/**
 * PeerId - SHA256-based node identity for Kademlia DHT.
 * Ported from literbike/src/dht/kademlia.rs (PeerId struct).
 */
data class PeerId(val id: ByteArray) {

    init {
        require(id.isNotEmpty()) { "PeerId cannot be empty" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerId) return false
        return id.contentEquals(other.id)
    }

    override fun hashCode(): Int = id.contentHashCode()

    override fun toString(): String = "PeerId(${toBase58()})"

    companion object {
        /** Create PeerId from public key (SHA256 hash) */
        fun fromPublicKey(pubkey: ByteArray): PeerId {
            val hash = sha256(pubkey)
            return PeerId(hash)
        }

        /** Generate random PeerId */
        fun random(): PeerId {
            val bytes = ByteArray(32)
            kotlin.random.Random.nextBytes(bytes)
            return PeerId(bytes)
        }

        /** Decode from Base58 */
        fun fromBase58(s: String): PeerId? {
            val bytes = base58Decode(s) ?: return null
            return PeerId(bytes)
        }

        private fun sha256(data: ByteArray): ByteArray {
            // Simple SHA-256 implementation using Kotlin crypto
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(data)
        }
    }

    /** XOR distance to another PeerId */
    fun xorDistance(other: PeerId): ByteArray {
        val maxLen = maxOf(id.size, other.id.size)
        val distance = ByteArray(maxLen)
        for (i in 0 until maxLen) {
            val a = if (i < id.size) id[i].toInt() and 0xFF else 0
            val b = if (i < other.id.size) other.id[i].toInt() and 0xFF else 0
            distance[i] = (a xor b).toByte()
        }
        return distance
    }

    /** Get bucket index for routing table (leading zeros in XOR distance) */
    fun bucketIndex(other: PeerId): Int {
        val distance = xorDistance(other)
        return leadingZeros(distance).coerceAtMost(255)
    }

    /** Base58 encoding for PeerId string representation */
    fun toBase58(): String = base58Encode(id)
}

/**
 * PeerInfo - Peer information with multiaddrs.
 */
data class PeerInfo(
    val id: PeerId,
    val addresses: MutableList<String> = mutableListOf(),
    val protocols: MutableList<String> = mutableListOf(),
    var publicKey: ByteArray = byteArrayOf(),
    var lastSeen: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerInfo) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * KBucket - Kademlia routing bucket (configurable max size, default 20).
 *
 * P0 policy:
 * - Duplicate peer IDs refresh the entry (LRU-like "most recently seen").
 * - No eviction when full; new peers are rejected.
 */
class KBucket(private val maxSize: Int = 20) {
    private val peers = mutableListOf<PeerInfo>()

    /** Add peer to bucket, returns true if successful */
    fun add(peer: PeerInfo): Boolean {
        // Remove existing if present (update/refresh)
        val existingIndex = peers.indexOfFirst { it.id == peer.id }
        if (existingIndex >= 0) {
            peers.removeAt(existingIndex)
        }

        // Add if space available
        if (peers.size < maxSize) {
            peers.add(peer)
            return true
        }
        return false
    }

    /** Remove peer by ID */
    fun remove(peerId: PeerId) {
        peers.removeAll { it.id == peerId }
    }

    /** Check if bucket contains peer */
    fun contains(peerId: PeerId): Boolean = peers.any { it.id == peerId }

    /** Get all peers */
    fun toIndexed(): List<PeerInfo> = peers.toList()

    /** Get peer count */
    fun size(): Int = peers.size

    /** Check if bucket is empty */
    fun isEmpty(): Boolean = peers.isEmpty()
}

/**
 * RoutingTable - Kademlia routing table (256 k-buckets).
 */
class RoutingTable(
    val localId: PeerId,
    private val bucketSize: Int = 20
) {
    private val buckets: List<KBucket> = List(256) { KBucket(bucketSize) }

    /** Add peer to appropriate bucket based on XOR distance.
     * Local peer is never inserted.
     */
    fun addPeer(peer: PeerInfo) {
        if (peer.id == localId) return // Don't add self
        val bucketIndex = localId.bucketIndex(peer.id)
        buckets[bucketIndex].add(peer)
    }

    /** Find closest peers to target node ID */
    fun findClosestPeers(target: PeerId, count: Int): List<PeerInfo> {
        val allPeers = mutableListOf<Pair<PeerInfo, Long>>()

        for (bucket in buckets) {
            for (peer in bucket.toIndexed()) {
                val distance = peer.id.xorDistance(target)
                val distScore = distance.sumOf { (it.toInt() and 0xFF).toLong() }
                allPeers.add(peer to distScore)
            }
        }

        return allPeers
            .sortedBy { it.second }
            .take(count)
            .map { it.first }
    }

    /** Get peer by ID */
    fun getPeer(peerId: PeerId): PeerInfo? {
        val bucketIndex = localId.bucketIndex(peerId)
        return buckets[bucketIndex].toIndexed().find { it.id == peerId }
    }

    /** Get all peers */
    fun allPeers(): List<PeerInfo> = buckets.flatMap { it.toIndexed() }

    /** Get total peer count */
    fun peerCount(): Int = buckets.sumOf { it.size() }

    /** Get bucket count (always 256) */
    fun bucketCount(): Int = buckets.size
}

// ============================================================================
// Helper functions
// ============================================================================

private fun leadingZeros(bytes: ByteArray): Int {
    for ((i, byte) in bytes.withIndex()) {
        if (byte.toInt() != 0) {
            return i * 8 + byte.toInt().countLeadingZeroBits()
        }
    }
    return bytes.size * 8
}

// ============================================================================
// Base58 encoding/decoding
// ============================================================================

private val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

private fun base58Encode(data: ByteArray): String {
    if (data.isEmpty()) return ""

    // Count leading zeros
    var leadingZeros = 0
    while (leadingZeros < data.size && data[leadingZeros] == 0.toByte()) {
        leadingZeros++
    }

    // Convert to base58
    val encoded = java.math.BigInteger(1, data).toByteArray()
    val base58 = StringBuilder()
    var num = java.math.BigInteger(1, encoded)
    val base = java.math.BigInteger.valueOf(58)

    while (num > java.math.BigInteger.ZERO) {
        val (quotient, remainder) = num.divideAndRemainder(base)
        base58.append(BASE58_ALPHABET[remainder.toInt()])
        num = quotient
    }

    // Add leading '1's for each leading zero byte
    repeat(leadingZeros) { base58.append('1') }

    return base58.reverse().toString()
}

private fun base58Decode(s: String): ByteArray? {
    if (s.isEmpty()) return null

    // Check for invalid characters
    for (c in s) {
        if (c !in BASE58_ALPHABET) return null
    }

    // Count leading '1's (encoded zero bytes)
    var leadingOnes = 0
    while (leadingOnes < s.length && s[leadingOnes] == '1') {
        leadingOnes++
    }

    // Decode base58 to number
    var num = java.math.BigInteger.ZERO
    val base = java.math.BigInteger.valueOf(58)
    for (c in s) {
        val value = BASE58_ALPHABET.indexOf(c)
        if (value < 0) return null
        num = num.multiply(base).add(java.math.BigInteger.valueOf(value.toLong()))
    }

    val bytes = num.toByteArray()

    // Prepend leading zero bytes
    val result = ByteArray(leadingOnes + bytes.size)
    bytes.copyInto(result, leadingOnes)
    return result
}
