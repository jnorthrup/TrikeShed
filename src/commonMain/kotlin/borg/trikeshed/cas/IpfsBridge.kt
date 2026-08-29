package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.collections.associative.LinearHashMap

/**
 * IpfsBridge maps IPFS concepts to TrikeShed's CAS and Manifests.
 * - CAS blocks as IPFS blocks.
 * - IPNS names resolve to CasManifest CIDs.
 */
open class IpfsBridge(private val cas: CasStore) {
    // Map of IPNS name to Manifest CID
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
