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
        fun fromHex(hex: String): CID = CID(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        fun sha256(data: ByteArray): CID {
            val digest = MessageDigest.getInstance("SHA-256")
            return CID(digest.digest(data))
        }
    }
}

/**
 * Block Store — content-addressable storage interface.
 * Uses CID as key, provides put/get operations.
 */
interface BlockStore {
    suspend fun put(cid: CID, data: ByteArray)
    suspend fun get(cid: CID): ByteArray?
    suspend fun has(cid: CID): Boolean = get(cid) != null
}

/** In-memory BlockStore implementation for development/testing. */
class MemoryBlockStore : BlockStore {
    private val store = kotlin.collections.mutableMapOf<String, ByteArray>()
    private val mutex = Mutex()

    override suspend fun put(cid: CID, data: ByteArray) =
        mutex.withLock { store[cid.hex()] = data }

    override suspend fun get(cid: CID): ByteArray? =
        mutex.withLock { store[cid.hex()] }
}