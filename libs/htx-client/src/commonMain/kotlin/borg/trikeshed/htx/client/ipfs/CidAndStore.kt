package borg.trikeshed.htx.client.ipfs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

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

interface BlockStore {
    suspend fun put(cid: CID, data: ByteArray)
    suspend fun get(cid: CID): ByteArray?
    suspend fun has(cid: CID): Boolean = get(cid) != null
}

class MemoryBlockStore : BlockStore {
    private val store = kotlin.collections.mutableMapOf<String, ByteArray>()
    private val mutex = Mutex()

    override suspend fun put(cid: CID, data: ByteArray) = mutex.withLock { store[cid.hex()] = data }

    override suspend fun get(cid: CID): ByteArray? = mutex.withLock { store[cid.hex()] }
}