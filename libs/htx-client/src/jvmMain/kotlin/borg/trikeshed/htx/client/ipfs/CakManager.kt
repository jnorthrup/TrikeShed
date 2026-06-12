package borg.trikeshed.htx.client.ipfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.Job

/**
 * Content-Addressable Keys (CAK) Manager — JVM Implementation.
 */
class CakManager(
    private val blockStore: BlockStore,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    override val key: kotlinx.coroutines.CoroutineContext.Key<*> get() = CakHtxKey

    suspend fun put(data: ByteArray): CID {
        val cid = CID.sha256(data)
        blockStore.put(cid, data)
        return cid
    }

    suspend fun putWithCid(cid: CID, data: ByteArray) = blockStore.put(cid, data)

    suspend fun get(cid: CID): ByteArray? = blockStore.get(cid)

    suspend fun has(cid: CID): Boolean = blockStore.get(cid) != null

    suspend fun delete(cid: CID) = blockStore.put(cid, byteArrayOf())

    fun provide(cid: CID, address: String = "local") { /* DHT integration */ }

    suspend fun findProviders(cid: CID): List<String> = emptyList()

    suspend fun findClosestNodes(target: CID, k: Int = 20): List<Any> = emptyList()

    suspend fun fetch(cid: CID): ByteArray = blockStore.get(cid) ?: byteArrayOf()

    fun cancelFetch(cid: CID) {}

    fun handleBitswapMessage(message: Any) {}

    suspend fun importCar(data: ByteArray): CarParseResult = CarParseResult(emptyList(), 0, 2, CID(byteArrayOf()))

    suspend fun exportCar(rootCids: List<CID>, version: Int = 2): ByteArray = byteArrayOf()

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

object CakHtxKey : kotlinx.coroutines.CoroutineContext.Key<CakManager>

object CakManagerFactory {
    suspend fun create(
        blockStore: BlockStore = MemoryBlockStore(),
        parentJob: Job? = null,
    ): CakManager = CakManager(blockStore, parentJob)

    suspend fun CoroutineScope.installCakManager(
        blockStore: BlockStore = MemoryBlockStore(),
    ): CakManager {
        val manager = create(blockStore, coroutineContext[Job])
        manager.open()
        return withContext(manager) { manager }
    }
}