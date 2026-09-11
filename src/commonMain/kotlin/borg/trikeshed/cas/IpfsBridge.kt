package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.ipns.IpnsPublisher
import borg.trikeshed.ipns.IpnsDht
import borg.trikeshed.ipns.IpnsName
import borg.trikeshed.ipns.IpnsRecord
import borg.trikeshed.ipns.IpnsPublishReport
import kotlinx.coroutines.currentCoroutineContext
import kotlin.time.Clock

/**
 * Local CAS block access and process-local names for manifest content IDs.
 *
 * String aliases remain process-local. Typed public IPNS operations compose the persistent
 * publisher and authenticated DHT from the caller's context. No daemon or HTTP routing service.
 * Block persistence and I/O are owned by the supplied CasStore.
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

    /** Publish this CAS block digest as a raw CIDv1 using the context's durable signing identity. */
    suspend fun publishIpns(manifestCid: ContentId): IpnsPublishReport {
        require(cas.get(manifestCid) != null) { "Cannot publish an absent CAS block" }
        return requireNotNull(currentCoroutineContext()[IpnsPublisher]) { "IPNS publisher required in context" }
            .publish(manifestCid)
    }

    suspend fun resolveIpns(name: IpnsName): IpnsRecord? =
        requireNotNull(currentCoroutineContext()[IpnsDht]) { "IPNS DHT required in context" }
            .resolve(name, Clock.System.now())
}
