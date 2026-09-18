package borg.trikeshed.ipfs

import kotlinx.serialization.Serializable

@Serializable
data class CID(val bytes: ByteArray)

interface BlockStore {
    suspend fun put(cid: CID, data: ByteArray)
    suspend fun get(cid: CID): ByteArray?
}

interface NameResolver {
    suspend fun resolve(name: String): CID?
}
