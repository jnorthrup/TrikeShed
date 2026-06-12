package borg.trikeshed.htx.client.ipfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.FanoutDispatcherElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Content-Addressable Keys (CAK) Manager — Unified IPFS interface for HTX Client.
 * 
 * Integrates:
 * - BlockStore: Content-addressable storage via CID
 * - DhtService: Kademlia DHT for provider discovery & routing
 * - BitswapEngine: Block exchange protocol
 * - CarIntegration: CAR archive parsing/writing
 * - FanoutDispatcherElement: Zero-copy event fanout
 * 
 * PRELOAD.md contract:
 * - CCEK lifecycle: CREATED → OPEN → ACTIVE → DRAINING → CLOSED
 * - Cold Series α-projection for streaming operations
 * - Fanout via FanoutDispatcherElement for DHT/Bitswap/CAR events
 * - Zero-copy via shared io_uring and ByteRegion/ByteSeries
 */
class CakManager(
    private val blockStore: BlockStore,
    private val dhtService: DhtService,
    private val bitswapEngine: BitswapEngine,
    private val fanoutDispatcher: FanoutDispatcherElement? = null,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    override val key: kotlinx.coroutines.CoroutineContext.Key<*> get() = CakHtxKey

    // ─────────────────────────────────────────────────────────────
    // Content-Addressable Block Operations
    // ─────────────────────────────────────────────────────────────

    /** Store data and return its CID (content-addressable put). */
    suspend fun put(data: ByteArray): CID {
        val cid = CID.sha256(data)
        blockStore.put(cid, data)
        fanoutDispatcher?.dispatch(CakEvent.BlockStored(cid, data.size))
        return cid
    }

    /** Store data with a specific CID. */
    suspend fun putWithCid(cid: CID, data: ByteArray) {
        blockStore.put(cid, data)
        fanoutDispatcher?.dispatch(CakEvent.BlockStored(cid, data.size))
    }

    /** Retrieve data by CID. */
    suspend fun get(cid: CID): ByteArray? {
        val data = blockStore.get(cid)
        fanoutDispatcher?.dispatch(CakEvent.BlockFetched(cid, data?.size ?: 0))
        return data
    }

    /** Check if CID exists locally. */
    suspend fun has(cid: CID): Boolean = blockStore.get(cid) != null

    /** Delete a block by CID. */
    suspend fun delete(cid: CID) {
        blockStore.put(cid, byteArrayOf()) // Tombstone
        fanoutDispatcher?.dispatch(CakEvent.BlockDeleted(cid))
    }

    // ─────────────────────────────────────────────────────────────
    // DHT Operations (Provider Discovery + Routing)
    // ─────────────────────────────────────────────────────────────

    /** Announce this node as a provider for a CID. */
    fun provide(cid: CID, address: String = "local") {
        dhtService.announceProvider(cid, address)
        fanoutDispatcher?.dispatch(CakEvent.ProviderAnnounced(cid, address))
    }

    /** Find providers for a CID (local + remote). */
    suspend fun findProviders(cid: CID): List<String> {
        val providers = dhtService.findProviders(cid)
        fanoutDispatcher?.dispatch(CakEvent.ProvidersFound(cid, providers))
        return providers
    }

    /** Find k closest nodes to target CID. */
    suspend fun findClosestNodes(target: CID, k: Int = 20): List<DhtService.NodeInfo> {
        val targetNodeId = DhtService.NodeId(target.bytes.copyOf(20))
        val nodes = dhtService.findNode(targetNodeId).take(k)
        fanoutDispatcher?.dispatch(CakEvent.ClosestNodesFound(target, nodes))
        return nodes
    }

    // ─────────────────────────────────────────────────────────────
    // Bitswap Operations (Block Exchange)
    // ─────────────────────────────────────────────────────────────

    /** Request a block from the network (Bitswap WANT_BLOCK). */
    suspend fun fetch(cid: CID): ByteArray {
        val data = bitswapEngine.wantBlock(cid)
        fanoutDispatcher?.dispatch(CakEvent.BlockFetched(cid, data.size))
        return data
    }

    /** Cancel a block request. */
    fun cancelFetch(cid: CID) = bitswapEngine.cancelWant(cid)

    /** Handle incoming Bitswap message from transport. */
    fun handleBitswapMessage(message: BitswapEngine.BitswapMessage) {
        bitswapEngine.handleMessage(message)
    }

    // ─────────────────────────────────────────────────────────────
    // CAR Operations (Content-Addressable Archives)
    // ─────────────────────────────────────────────────────────────

    /** Parse CAR and store all blocks. */
    suspend fun importCar(data: ByteArray): CarParseResult {
        val result = CarParser.parse(data)
        result.blocks.forEach { block -> blockStore.put(block.cid, block.data) }
        fanoutDispatcher?.dispatch(CakEvent.CarImported(result.roots, result.blocks.size))
        return result
    }

    /** Export a set of root CIDs as CAR archive. */
    suspend fun exportCar(rootCids: List<CID>, version: Int = 2): ByteArray {
        val blocks = rootCids.mapNotNull { cid -> blockStore.get(cid)?.let { CarBlock(cid, it) } }
        val data = CarWriter.write(blocks, rootCids, version)
        fanoutDispatcher?.dispatch(CakEvent.CarExported(rootCids, data.size))
        return data
    }

    // ─────────────────────────────────────────────────────────────
    // Composite Operations
    // ─────────────────────────────────────────────────────────────

    /** Pin a CID locally and announce to DHT. */
    suspend fun pin(cid: CID, address: String = "local") {
        if (!has(cid)) fetch(cid)
        provide(cid, address)
    }

    /** Sync a set of CIDs with the network. */
    suspend fun sync(cids: List<CID>, address: String = "local") {
        awaitAll(cids.map { cid ->
            async {
                if (!has(cid)) {
                    try { fetch(cid) } catch (e: Exception) {
                        fanoutDispatcher?.dispatch(CakEvent.SyncFailed(cid, e.message ?: "Unknown error"))
                    }
                }
                provide(cid, address)
            }
        })
    }

    /** Verify integrity of stored blocks. */
    suspend fun verify(cids: List<CID>): List<CID> {
        val verified = mutableListOf<CID>()
        cids.forEach { cid ->
            blockStore.get(cid)?.let { data ->
                if (CID.sha256(data).bytes.contentEquals(cid.bytes)) verified.add(cid)
            }
        }
        fanoutDispatcher?.dispatch(CakEvent.VerificationComplete(verified))
        return verified
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    override suspend fun open() {
        super.open()
        fanoutDispatcher?.dispatch(CakEvent.ManagerOpened)
    }

    override suspend fun close() {
        fanoutDispatcher?.dispatch(CakEvent.ManagerClosed)
        super.close()
    }

    /** Access underlying components. */
    val components: Components = Components(blockStore, dhtService, bitswapEngine)

    data class Components(
        val blockStore: BlockStore,
        val dhtService: DhtService,
        val bitswapEngine: BitswapEngine,
    )
}

/** CAK Events for fanout dispatcher. */
sealed class CakEvent : borg.trikeshed.userspace.FanoutEvent {
    data class BlockStored(val cid: CID, val size: Int) : CakEvent() { override val eventType = 200 }
    data class BlockFetched(val cid: CID, val size: Int) : CakEvent() { override val eventType = 201 }
    data class BlockDeleted(val cid: CID) : CakEvent() { override val eventType = 202 }
    data class ProviderAnnounced(val cid: CID, val address: String) : CakEvent() { override val eventType = 203 }
    data class ProvidersFound(val cid: CID, val providers: List<String>) : CakEvent() { override val eventType = 204 }
    data class ClosestNodesFound(val target: CID, val nodes: List<DhtService.NodeInfo>) : CakEvent() { override val eventType = 205 }
    data class CarImported(val roots: List<CID>, val blockCount: Int) : CakEvent() { override val eventType = 206 }
    data class CarExported(val roots: List<CID>, val size: Int) : CakEvent() { override val eventType = 207 }
    data class SyncFailed(val cid: CID, val error: String) : CakEvent() { override val eventType = 208 }
    data class VerificationComplete(val verified: List<CID>) : CakEvent() { override val eventType = 209 }
    object ManagerOpened : CakEvent() { override val eventType = 210 }
    object ManagerClosed : CakEvent() { override val eventType = 211 }
}

/** Context key for CakManager. */
object CakHtxKey : kotlinx.coroutines.CoroutineContext.Key<CakManager>

/** Factory for CakManager with CCEK wiring. */
object CakManagerFactory {

    /** Create CakManager with default implementations. */
    suspend fun create(
        blockStore: BlockStore = MemoryBlockStore(),
        dhtTransport: DhtTransport? = null,
        bitswapSend: (ByteArray) -> Unit = { },
        fanoutDispatcher: FanoutDispatcherElement? = null,
        parentJob: Job? = null,
    ): CakManager {
        val dht = DhtService(transport = dhtTransport)
        val bitswap = BitswapEngine(blockStore, bitswapSend)
        return CakManager(blockStore, dht, bitswap, fanoutDispatcher, parentJob)
    }

    /** Create CakManager fully wired with HTX transports. */
    suspend fun createHtxWired(
        blockStore: BlockStore = MemoryBlockStore(),
        dhtTransport: HtxDhtTransport? = null,
        bitswapTransport: HtxBitswapTransport? = null,
        fanoutDispatcher: FanoutDispatcherElement? = null,
        parentJob: Job? = null,
    ): CakManager {
        val dht = DhtService(transport = dhtTransport)
        val bitswapSend = bitswapTransport?.let { transport ->
            { data: ByteArray -> transport.send(data) }
        } ?: { }
        val bitswap = BitswapEngine(blockStore, bitswapSend)
        return CakManager(blockStore, dht, bitswap, fanoutDispatcher, parentJob)
    }

    /** Install CakManager in coroutine context. */
    suspend fun CoroutineScope.installCakManager(
        blockStore: BlockStore = MemoryBlockStore(),
        dhtTransport: DhtTransport? = null,
        bitswapSend: (ByteArray) -> Unit = { },
        fanoutDispatcher: FanoutDispatcherElement? = null,
    ): CakManager {
        val manager = create(blockStore, dhtTransport, bitswapSend, fanoutDispatcher, coroutineContext[Job])
        manager.open()
        return withContext(manager) { manager }
    }
}

/** CarIntegration wrapper for CakManager. */
class CarIntegration {
    suspend fun parseAndStore(data: ByteArray, blockStore: BlockStore): CarParseResult {
        val result = CarParser.parse(data)
        result.blocks.forEach { blockStore.put(it.cid, it.data) }
        return result
    }
}