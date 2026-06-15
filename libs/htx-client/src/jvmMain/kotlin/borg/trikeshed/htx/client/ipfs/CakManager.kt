package borg.trikeshed.htx.client.ipfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * Content-Addressable Keys (CAK) Manager — JVM Implementation.
 */
class CakManager(
    private val blockStore: BlockStore,
    private val dhtTransport: DhtTransport? = null,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    override val key: CoroutineContext.Key<*> get() = CakHtxKey

    suspend fun put(data: ByteArray): CID {
        val cid = CID.sha256(data)
        blockStore.put(cid, data)
        return cid
    }

    suspend fun putWithCid(cid: CID, data: ByteArray) = blockStore.put(cid, data)

    suspend fun get(cid: CID): ByteArray? = blockStore.get(cid)

    suspend fun has(cid: CID): Boolean = blockStore.get(cid) != null

    suspend fun delete(cid: CID) = blockStore.put(cid, byteArrayOf())

    /** Announce this node as a provider for the given CID via local DHT. */
    suspend fun provide(cid: CID, address: String = "local") {
        val dht = DhtService()
        dht.announceProvider(cid, address)
    }

    /** Uses the embedded DhtService for local provider lookups. */
    suspend fun findProviders(cid: CID): List<String> {
        val dht = DhtService()
        return dht.findProviders(cid)
    }

    suspend fun findClosestNodes(target: CID, k: Int = 20): List<NodeInfo> {
        val dhtService = DhtService()
        return dhtService.findNode(NodeId.fromCID(target)).take(k)
    }

    suspend fun fetch(cid: CID): ByteArray = blockStore.get(cid) ?: byteArrayOf()

    fun cancelFetch(cid: CID) {}

    fun handleBitswapMessage(message: Any) {}

    suspend fun importCar(data: ByteArray): CarParseResult {
        return try {
            CarParser.parse(data)
        } catch (e: Exception) {
            CarParseResult(emptyList(), emptyList(), 2, null, CID(byteArrayOf()))
        }
    }

    suspend fun exportCar(rootCids: List<CID>, version: Int = 2): ByteArray {
        val blocks = rootCids.mapNotNull { cid ->
            blockStore.get(cid)?.let { CarBlock(cid, it) }
        }
        return CarWriter.write(blocks, rootCids, version)
    }

    suspend fun pin(cid: CID, address: String = "local") {
        if (!has(cid)) fetch(cid)
        provide(cid, address)
    }

    suspend fun sync(cids: List<CID>, address: String = "local") {
        cids.forEach { cid ->
            if (!has(cid)) {
                try { fetch(cid) } catch (e: Exception) { }
            }
            provide(cid, address)
        }
    }

    suspend fun verify(cids: List<CID>): List<CID> {
        val verified = mutableListOf<CID>()
        cids.forEach { cid ->
            blockStore.get(cid)?.let { data ->
                if (CID.sha256(data).bytes.contentEquals(cid.bytes)) verified.add(cid)
            }
        }
        return verified
    }

    override suspend fun open() { super.open() }
    override suspend fun close() { super.close() }

    data class Components(val blockStore: BlockStore)

    val components: Components = Components(blockStore)
}

object CakHtxKey : CoroutineContext.Key<CakManager>

object CakManagerFactory {
    fun create(
        blockStore: BlockStore = MemoryBlockStore(),
        dhtTransport: DhtTransport? = null,
        parentJob: Job? = null,
    ): CakManager = CakManager(blockStore, dhtTransport, parentJob)

    /** Install a CAK manager as a coroutine-scoped context element. */
    suspend fun CoroutineScope.installCakManager(
        blockStore: BlockStore = MemoryBlockStore(),
        dhtTransport: DhtTransport? = null,
    ): CakManager {
        val manager = create(blockStore, dhtTransport, coroutineContext[Job])
        manager.open()
        return manager
    }
}
