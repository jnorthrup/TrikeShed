package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.collections.associative.LinearHashMap

/**
 * Local CAS block access and process-local names for manifest content IDs.
 *
 * The existing IPFS/IPNS-shaped names are local vocabulary only: this class does not
 * contact Kubo, publish signed IPNS records, or participate in libp2p/DHT discovery.
 * Block persistence and I/O are owned by the supplied CasStore; names live only in memory.
 */
open class IpfsBridge(private val cas: CasStore) {
    // Process-local name index; no remote publication is implied.
    private val ipnsRegistry = LinearHashMap<String, ContentId>()

    fun putBlock(data: ByteArray): ContentId {
        return cas.put(data)
    }

    fun getBlock(cid: ContentId): ByteArray? {
        return cas.get(cid)
    }

    open fun publishIpns(name: String, manifestCid: ContentId) {
        ipnsRegistry[name] = manifestCid
    }

    open fun unpublishIpns(name: String): Boolean = ipnsRegistry.remove(name) != null

    fun resolveIpns(name: String): ContentId? {
        return ipnsRegistry[name]
    }
}
