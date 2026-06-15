package borg.trikeshed.htx.client.ipfs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Random

/**
 * Content Identifier (CID) — self-describing content-addressable identifier.
 * Based on IPFS CIDv1: multibase + multicodec + multihash.
 */
data class CID(
    val bytes: ByteArray,
) {
    override fun toString(): String = "CID(${bytes.joinToString("") { "%02x".format(it) }})"
    fun hex(): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        fun sha256(data: ByteArray): CID {
            val digest = MessageDigest.getInstance("SHA-256")
            return CID(digest.digest(data))
        }
    }
}

/**
 * Block Store — content-addressable storage interface.
 */
interface BlockStore {
    suspend fun put(cid: CID, data: ByteArray)
    suspend fun get(cid: CID): ByteArray?
    suspend fun has(cid: CID): Boolean = get(cid) != null
}

/** In-memory BlockStore implementation. */
class MemoryBlockStore : BlockStore {
    private val store = mutableMapOf<String, ByteArray>()
    private val mutex = Mutex()

    override suspend fun put(cid: CID, data: ByteArray) = mutex.withLock { store[cid.hex()] = data }
    override suspend fun get(cid: CID): ByteArray? = mutex.withLock { store[cid.hex()] }
}

/**
 * 160-bit Kademlia NodeId — for both DHT routing and node identity.
 */
data class NodeId(val bytes: ByteArray) {
    init { require(bytes.size == 20) { "NodeId must be exactly 20 bytes" } }

    companion object {
        fun random(): NodeId {
            val bytes = ByteArray(20)
            Random().nextBytes(bytes)
            return NodeId(bytes)
        }

        /** Derive a 160-bit NodeId from a 32-byte CID by XOR-spreading its bytes */
        fun fromCID(cid: CID): NodeId {
            val nodeIdBytes = ByteArray(20)
            for (i in 0 until 20) {
                nodeIdBytes[i] = (cid.bytes[i % cid.bytes.size].toInt() xor (i * 31 + 1)).toByte()
            }
            return NodeId(nodeIdBytes)
        }
    }

    fun xorDistance(other: NodeId): BigInteger {
        val thisBi = BigInteger(1, bytes)
        val otherBi = BigInteger(1, other.bytes)
        return (thisBi xor otherBi).abs()
    }

    fun bucketIndex(other: NodeId): Int = xorDistance(other).bitLength() - 1

    override fun toString(): String = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Node info for DHT contact management.
 */
data class NodeInfo(
    val id: NodeId,
    val address: InetSocketAddress,
    var lastSeen: Long = System.currentTimeMillis(),
)

/**
 * DHT Transport interface — abstracts network I/O for DHT operations.
 */
interface DhtTransport {
    suspend fun announceProviderRemote(cid: CID, address: String)
    suspend fun findProvidersRemote(cid: CID): List<String>
    suspend fun findNodeRemote(target: NodeId): List<NodeInfo>
}

// CarParseResult is defined in jvmMain/CarIntegration.kt (richer: blocks, index)
