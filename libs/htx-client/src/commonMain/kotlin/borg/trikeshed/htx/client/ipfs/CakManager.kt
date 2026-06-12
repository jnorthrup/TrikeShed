package borg.trikeshed.htx.client.ipfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Content-Addressable Keys (CAK) Manager — Unified IPFS interface for HTX Client.
 */
class CakManager(
    private val blockStore: BlockStore,
    private val dhtService: DhtService,
    private val bitswapEngine: BitswapEngine,
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

    fun provide(cid: CID, address: String = "local") = dhtService.announceProvider(cid, address)

    suspend fun findProviders(cid: CID): List<String> = dhtService.findProviders(cid)

    suspend fun findClosestNodes(target: CID, k: Int = 20): List<DhtService.NodeInfo> {
        val targetNodeId = DhtService.NodeId(target.bytes.copyOf(20))
        return dhtService.findNode(targetNodeId).take(k)
    }

    suspend fun fetch(cid: CID): ByteArray = bitswapEngine.wantBlock(cid)

    fun cancelFetch(cid: CID) = bitswapEngine.cancelWant(cid)

    fun handleBitswapMessage(message: BitswapEngine.BitswapMessage) = bitswapEngine.handleMessage(message)

    suspend fun importCar(data: ByteArray): CarParseResult {
        val result = CarParser.parse(data)
        result.blocks.forEach { blockStore.put(it.cid, it.data) }
        return result
    }

    suspend fun exportCar(rootCids: List<CID>, version: Int = 2): ByteArray {
        val blocks = rootCids.mapNotNull { cid -> blockStore.get(cid)?.let { CarBlock(cid, it) } }
        return CarWriter.write(blocks, rootCids, version)
    }

    suspend fun pin(cid: CID, address: String = "local") {
        if (!has(cid)) fetch(cid)
        provide(cid, address)
    }

    suspend fun sync(cids: List<CID>, address: String = "local") {
        awaitAll(cids.map { cid ->
            async {
                if (!has(cid)) {
                    try { fetch(cid) } catch (e: Exception) { /* ignored */ }
                }
                provide(cid, address)
            }
        })
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

    val components: Components = Components(blockStore, dhtService, bitswapEngine)

    data class Components(
        val blockStore: BlockStore,
        val dhtService: DhtService,
        val bitswapEngine: BitswapEngine,
    )
}

/** Context key for CakManager. */
object CakHtxKey : kotlinx.coroutines.CoroutineContext.Key<CakManager>

/** Factory for CakManager with CCEK wiring. */
object CakManagerFactory {

    suspend fun create(
        blockStore: BlockStore = MemoryBlockStore(),
        dhtTransport: DhtTransport? = null,
        bitswapSend: (ByteArray) -> Unit = { },
        parentJob: Job? = null,
    ): CakManager {
        val dht = DhtService(transport = dhtTransport)
        val bitswap = BitswapEngine(blockStore, bitswapSend)
        return CakManager(blockStore, dht, bitswap, parentJob)
    }

    suspend fun createHtxWired(
        blockStore: BlockStore = MemoryBlockStore(),
        dhtTransport: DhtTransport? = null,
        bitswapTransport: HtxBitswapTransport? = null,
        parentJob: Job? = null,
    ): CakManager {
        val dht = DhtService(transport = dhtTransport)
        val bitswapSend = bitswapTransport?.let { { data: ByteArray -> it.send(data) } } ?: { }
        val bitswap = BitswapEngine(blockStore, bitswapSend)
        return CakManager(blockStore, dht, bitswap, parentJob)
    }

    suspend fun CoroutineScope.installCakManager(
        blockStore: BlockStore = MemoryBlockStore(),
        dhtTransport: DhtTransport? = null,
        bitswapSend: (ByteArray) -> Unit = { },
    ): CakManager {
        val manager = create(blockStore, dhtTransport, bitswapSend, coroutineContext[Job])
        manager.open()
        return withContext(manager) { manager }
    }
}