package borg.trikeshed.htx.client.ipfs

import kotlinx.coroutines.sync.Mutex
import java.security.MessageDigest

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
    private val store = kotlin.collections.mutableMapOf<String, ByteArray>()
    private val mutex = Mutex()

    override suspend fun put(cid: CID, data: ByteArray) = mutex.withLock { store[cid.hex()] = data }

    override suspend fun get(cid: CID): ByteArray? = mutex.withLock { store[cid.hex()] }
}

/**
 * DHT Transport interface — abstracts network I/O for DHT operations.
 */
interface DhtTransport {
    suspend fun announceProviderRemote(cid: CID, address: String)
    suspend fun findProvidersRemote(cid: CID): List<String>
    suspend fun findNodeRemote(target: DhtService.NodeId): List<DhtService.NodeInfo>
}

/**
 * DHT Service — provider discovery and node routing.
 * Implementation in jvmMain for full Kademlia routing.
 */
class DhtService(
    private val localNodeId: NodeId = NodeId.random(),
    private val transport: DhtTransport? = null,
) {
    data class NodeId(val bytes: ByteArray) {
        companion object {
            fun random(): NodeId = NodeId(ByteArray(20).also { java.util.Random().nextBytes(it) })
        }
        fun xorDistance(other: NodeId): java.math.BigInteger {
            val thisBi = java.math.BigInteger(1, bytes)
            val otherBi = java.math.BigInteger(1, other.bytes)
            return (thisBi xor otherBi).abs()
        }
        fun bucketIndex(other: NodeId): Int = xorDistance(other).bitLength() - 1
        override fun toString(): String = bytes.joinToString("") { "%02x".format(it) }
    }
    
    data class NodeInfo(val id: NodeId, val address: java.net.InetSocketAddress, var lastSeen: Long = System.currentTimeMillis())
    
    fun announceProvider(cid: CID, address: String) {
        transport?.let { t ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()).launch { t.announceProviderRemote(cid, address) }
        }
    }

    suspend fun findProviders(cid: CID): List<String> = transport?.findProvidersRemote(cid) ?: emptyList()

    suspend fun findNode(target: NodeId): List<NodeInfo> = emptyList()

    fun handleFindNode(requester: NodeId, target: NodeId): List<NodeInfo> = emptyList()
    fun handleFindProviders(requester: NodeId, cid: CID): List<String> = emptyList()
    fun handlePing(requester: NodeId): Boolean = true
    fun addContact(node: NodeInfo) {}
}

/**
 * CAR Parse Result
 */
data class CarParseResult(
    val roots: List<CID>,
    val blockCount: Int,
    val version: Int,
    val dataCid: CID,
)