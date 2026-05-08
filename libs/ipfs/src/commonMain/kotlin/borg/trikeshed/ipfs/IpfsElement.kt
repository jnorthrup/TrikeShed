package borg.trikeshed.ipfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import kotlin.coroutines.coroutineContext

/**
 * IPFS protocol element with cross-context access to CouchElement.
 *
 * Once a CouchElement is in scope (e.g., via CombinedClientElement), IpfsElement can
 * introspect couch collections for peer discovery and content routing metadata.
 *
 * Usage:
 *   val couch = coroutineContext[couchKey]  // resolve CouchElement by its AsyncContextKey
 *   couch.collections["peers"]?.snapshot()?.view?.forEach { ... }
 *
 * The couchKey can be resolved at class-load time via:
 *   val couchKey = Class.forName("borg.trikeshed.couch.CouchElement").getDeclaredField("Key").get(null)
 *     as kotlin.coroutines.CoroutineContext.Key<*>
 */
open class IpfsElement(val blockStore: BlockStore, val dht: DhtService) : AsyncContextElement() {
    companion object Key : AsyncContextKey<IpfsElement>()
    override val key: AsyncContextKey<IpfsElement> get() = Key

    var couchKey: kotlin.coroutines.CoroutineContext.Key<*>? = null

    suspend fun putBlock(cid: CID, data: ByteArray) {
        blockStore.put(cid, data)
    }

    suspend fun get(cid: CID): ByteArray? {
        return blockStore.get(cid)
    }

    fun announceProvider(cid: CID, address: String) {
        dht.announceProvider(cid, address)
    }

    suspend fun activePeers(): Int {
        val key = couchKey ?: return 0
        val couch = coroutineContext[key] ?: return 0
        return try {
            val collections = couch::class.members.firstOrNull { it.name == "collections" }
            val map = collections?.call(couch) as? Map<*, *>
            val peers = map?.get("peers")
            peers?.let { p ->
                p::class.members.firstOrNull { it.name == "rowCount" }?.call(p) as? Int
            } ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
