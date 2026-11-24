package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.ipns.IpnsPublisher
import borg.trikeshed.ipns.IpnsDht
import borg.trikeshed.ipns.IpnsName
import borg.trikeshed.ipns.IpnsRecord
import borg.trikeshed.ipns.IpnsPublishReport
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.toSeries
import borg.trikeshed.transfer.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

/**
 * Local CAS block access and instance-local aliases for manifest content IDs.
 *
 * String aliases remain local to this bridge. Typed public IPNS operations compose the persistent
 * publisher and authenticated DHT from the caller's context.
 * Block persistence and I/O are owned by the supplied CasStore.
 */
open class IpfsBridge(private val cas: CasStore) {
    private val aliases = LinearHashMap<String, ContentId>()
    /** The files put here, newest last, at most [ROWS] of them, as transfer rows of the IPFS side. */
    val puts = MutableStateFlow<List<Transfer>>(emptyList())

    fun putBlock(data: ByteArray): ContentId {
        return cas.put(data)
    }

    fun getBlock(cid: ContentId): ByteArray? {
        return cas.get(cid)
    }

    /**
     * [content] as an IPFS file ([UnixFsFile]): every block put in the store under its digest, and the root's CID,
     * the one `ipfs add --cid-version=1` names the same bytes by, returned. The put is a row of [transfers].
     */
    fun putFile(content: ByteArray, name: String, context: TransferContext? = null, contentClass: String? = null): IpnsCid {
        val blocks = UnixFsFile.blocks(content)
        for (block in blocks) check(cas.put(block.bytes) == UnixFsFile.contentId(block.cid)) { "The store named a block by another digest" }
        val root = blocks.last().cid
        val leaves = blocks.count { it.cid.codec == UnixFsFile.RAW }
        val row = Transfer(root.toString(), name, TransferProtocol.IPFS, TransferState.COMPLETE, content.size.toLong(),
            content.size.toLong(), 0, 0, TransferPieces(leaves, leaves, UnixFsFile.CHUNK), emptySeriesOf(), context,
            root.toString(), contentClass, null)
        puts.update { (it + row).takeLast(ROWS) }
        return root
    }

    /** The file under [root], read back block by block by content id; null when a block is absent. */
    fun getFile(root: IpnsCid): ByteArray? = UnixFsFile.read(cas, root)

    /** The current transfer rows of the IPFS side: the files put here. */
    fun transfers(): Series<Transfer> = puts.value.toSeries()

    open fun bindAlias(name: String, manifestCid: ContentId) {
        aliases[name] = manifestCid
    }

    open fun removeAlias(name: String): Boolean = aliases.remove(name) != null

    fun resolveAlias(name: String): ContentId? {
        return aliases[name]
    }

    /** Publish this CAS block digest as a raw CIDv1 using the context's durable signing identity. */
    suspend fun publishIpns(manifestCid: ContentId): IpnsPublishReport {
        require(cas.get(manifestCid) != null) { "Cannot publish an absent CAS block" }
        return requireNotNull(currentCoroutineContext()[IpnsPublisher]) { "IPNS publisher required in context" }
            .publish(manifestCid)
    }

    /** Publish `/ipfs/<root>` for a file put here ([putFile]), its codec kept, using the context's durable signing identity. */
    suspend fun publishIpns(root: IpnsCid): IpnsPublishReport {
        require(cas.get(UnixFsFile.contentId(root)) != null) { "Cannot publish an absent CAS block" }
        return requireNotNull(currentCoroutineContext()[IpnsPublisher]) { "IPNS publisher required in context" }
            .publish("/ipfs/$root")
    }

    suspend fun resolveIpns(name: IpnsName): IpnsRecord? =
        requireNotNull(currentCoroutineContext()[IpnsDht]) { "IPNS DHT required in context" }
            .resolve(name, Clock.System.now())

    companion object {
        /** File rows kept. */
        const val ROWS = 64
    }
}
