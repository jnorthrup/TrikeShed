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
 * CAR Parse Result
 */
data class CarParseResult(
    val roots: List<CID>,
    val blockCount: Int,
    val version: Int,
    val dataCid: CID,
)