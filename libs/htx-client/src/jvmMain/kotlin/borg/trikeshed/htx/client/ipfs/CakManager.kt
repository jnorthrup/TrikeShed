package borg.trikeshed.htx.client.ipfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

class CakManager(
    private val blockStore: BlockStore,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    override val key: CoroutineContext.Key<*> get() = CakHtxKey

    suspend fun put(data: ByteArray): CID { val cid = CID.sha256(data); blockStore.put(cid, data); return cid }
    suspend fun get(cid: CID): ByteArray? = blockStore.get(cid)
    suspend fun has(cid: CID): Boolean = blockStore.get(cid) != null
    suspend fun fetch(cid: CID): ByteArray = blockStore.get(cid) ?: byteArrayOf()

    suspend fun provide(cid: CID, address: String = "local") { DhtService().announceProvider(cid, address) }
    suspend fun findProviders(cid: CID): List<String> = DhtService().findProviders(cid)
    suspend fun findClosestNodes(target: CID, k: Int = 20): List<NodeInfo> =
        DhtService().findNode(NodeId.fromCID(target)).take(k)

    suspend fun importCar(data: ByteArray): CarParseResult = try {
        CarParser.parse(data)
    } catch (e: Exception) {
        CarParseResult(emptyList(), 0, 2, CID(byteArrayOf()))
    }

    suspend fun exportCar(rootCids: List<CID>, version: Int = 2): ByteArray {
        val blocks = rootCids.mapNotNull { cid -> blockStore.get(cid)?.let { CarBlock(cid, it) } }
        return CarWriter.write(blocks, rootCids, version)
    }

    suspend fun pin(cid: CID, address: String = "local") { if (!has(cid)) fetch(cid); provide(cid, address) }
    suspend fun verify(cids: List<CID>): List<CID> = cids.filter { cid ->
        blockStore.get(cid)?.let { CID.sha256(it).bytes.contentEquals(cid.bytes) } ?: false
    }

    override suspend fun open() { super.open() }
    override suspend fun close() { super.close() }
}

object CakHtxKey : CoroutineContext.Key<CakManager>

object CakManagerFactory {
    fun create(blockStore: BlockStore = MemoryBlockStore(), parentJob: Job? = null): CakManager = CakManager(blockStore, parentJob)
}
