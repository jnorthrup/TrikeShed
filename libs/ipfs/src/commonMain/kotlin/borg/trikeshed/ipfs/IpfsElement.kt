package borg.trikeshed.ipfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey

open class IpfsElement(val blockStore: BlockStore, val dht: DhtService) : AsyncContextElement() {
    companion object Key : AsyncContextKey<IpfsElement>()
    override val key: AsyncContextKey<IpfsElement> get() = Key

    suspend fun putBlock(cid: CID, data: ByteArray) {
        blockStore.put(cid, data)
    }

    suspend fun get(cid: CID): ByteArray? {
        return blockStore.get(cid)
    }

    fun announceProvider(cid: CID, address: String) {
        dht.announceProvider(cid, address)
    }
}
