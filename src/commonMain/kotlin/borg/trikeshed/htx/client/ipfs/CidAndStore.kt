package borg.trikeshed.htx.client.ipfs

import borg.trikeshed.job.sha256
import borg.trikeshed.util.toLowerHex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CID(val bytes: ByteArray) {
    override fun toString(): String = "CID(${bytes.toLowerHex()})"
    fun hex(): String = bytes.toLowerHex()
    override fun equals(other: Any?): Boolean = other is CID && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    companion object {
        fun sha256(data: ByteArray): CID = CID(borg.trikeshed.job.sha256(data))
    }
}

interface BlockStore {
    suspend fun put(cid: CID, data: ByteArray)
    suspend fun get(cid: CID): ByteArray?
    suspend fun has(cid: CID): Boolean = get(cid) != null
}

class MemoryBlockStore : BlockStore {
    private val store = mutableMapOf<String, ByteArray>()
    private val mutex = Mutex()
    override suspend fun put(cid: CID, data: ByteArray) = mutex.withLock { store[cid.hex()] = data }
    override suspend fun get(cid: CID): ByteArray? = mutex.withLock { store[cid.hex()] }
}
