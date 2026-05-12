package borg.trikeshed.ipfs

data class CID(val bytes: ByteArray)

interface BlockStore {
    suspend fun put(cid: CID, data: ByteArray)
    suspend fun get(cid: CID): ByteArray?
}

interface NameResolver {
    suspend fun resolve(name: CharSequence): CID?
}
