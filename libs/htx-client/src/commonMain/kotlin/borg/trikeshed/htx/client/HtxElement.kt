package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.ipfs.CakManager
import borg.trikeshed.htx.client.ipfs.HtxBitswapTransport
import borg.trikeshed.htx.client.ipfs.HtxCarIntegration
import borg.trikeshed.htx.client.ipfs.HtxDhtTransport
import borg.trikeshed.htx.client.ipfs.IpfsHtxElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * HTTP response from HTX client/server.
 */
@Serializable
data class HtxClientMessage(
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
    val error: String? = null
) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * HTTP request for HTX client/server.
 */
@Serializable
data class HtxClientRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = ""
) {
    companion object {
        fun get(path: String, headers: Map<String, List<String>> = emptyMap()) = 
            HtxClientRequest("GET", path, headers)
        fun post(path: String, body: String = "", headers: Map<String, List<String>> = emptyMap()) = 
            HtxClientRequest("POST", path, headers, body)
    }
}

/**
 * HTX Element for the HTX client — CCEK integrated with IPFS stack.
 * 
 * Extends AsyncContextElement for proper lifecycle integration with TrikeShed context system.
 * 
 * IPFS Integration:
 * - IpfsHtxElement: Content-addressable storage (BlockStore), DHT, Bitswap, CAR
 * - CakManager: Unified content-addressable keys manager
 * - HtxDhtTransport: DHT via io_uring/FunctionalUringFacade
 * - HtxBitswapTransport: Bitswap via HTX Channels
 * - HtxCarIntegration: CAR v1/v2 archives
 * 
 * PRELOAD.md contract:
 * - Gets installed in coroutine context via HtxKey
 * - Lifecycle: CREATED → OPEN → ACTIVE → DRAINING → CLOSED
 * - Zero-copy fanout via shared FanoutDispatcherElement
 * - Cold Series α-projection for HTTP/IPFS message encoding
 */
class HtxElement(
    val baseUrl: String = "http://127.0.0.1",
    /** Optional IPFS block store for content-addressable storage. */
    private val ipfsBlockStore: borg.trikeshed.ipfs.BlockStore? = null,
    /** Optional DHT transport for Kademlia routing. */
    private val htxDhtTransport: HtxDhtTransport? = null,
    /** Optional Bitswap transport for block exchange. */
    private val htxBitswapTransport: HtxBitswapTransport? = null,
    /** Optional CCEK fanout dispatcher for zero-copy event dispatch. */
    private val fanoutDispatcher: borg.trikeshed.userspace.FanoutDispatcherElement? = null,
) : AsyncContextElement(ElementState.CREATED) {

    override val key: CoroutineContext.Key<*> get() = HtxKey

    /** IPFS Element (created lazily on first access) */
    private var _ipfsElement: IpfsHtxElement? = null
    private var _cakManager: CakManager? = null
    private var _carIntegration: HtxCarIntegration? = null

    /** Get or create the IPFS element. */
    val ipfs: IpfsHtxElement
        get() {
            if (_ipfsElement == null) {
                val blockStore = ipfsBlockStore ?: IpfsHtxFactory.memoryBlockStore()
                val dht = borg.trikeshed.ipfs.DhtService(transport = htxDhtTransport)
                val bitswapSend = htxBitswapTransport?.let { { data: ByteArray -> it.send(borg.trikeshed.ipfs.bitswap.BitswapEngine.BitswapMessage.decode(data)) } }
                    ?: { }
                val bitswap = borg.trikeshed.ipfs.bitswap.BitswapEngine(blockStore, bitswapSend)
                _ipfsElement = IpfsHtxElement(blockStore, dht, bitswap, coroutineContext[Job])
                _ipfsElement!!.open()
            }
            return _ipfsElement!!
        }

    /** Get or create the CAK Manager (unified IPFS interface). */
    val cak: CakManager
        get() {
            if (_cakManager == null) {
                _cakManager = CakManagerFactory.createHtxWired(
                    blockStore = ipfsBlockStore ?: IpfsHtxFactory.memoryBlockStore(),
                    dhtTransport = htxDhtTransport,
                    bitswapTransport = htxBitswapTransport,
                    fanoutDispatcher = fanoutDispatcher,
                    parentJob = coroutineContext[Job],
                )
            }
            return _cakManager!!
        }

    /** Get or create the CAR integration. */
    val car: HtxCarIntegration
        get() {
            if (_carIntegration == null) {
                _carIntegration = HtxCarIntegrationFactory.create(
                    ipfsBlockStore ?: IpfsHtxFactory.memoryBlockStore(),
                    coroutineContext[CoroutineScope] ?: CoroutineScope(Job())
                )
            }
            return _carIntegration!!
        }

    override suspend fun open() {
        super.open()
        // Initialize IPFS stack if configured
        if (ipfsBlockStore != null || htxDhtTransport != null || htxBitswapTransport != null) {
            _ = ipfs // Force initialization
            _ = cak  // Force initialization
        }
    }

    override suspend fun close() {
        _ipfsElement?.close()
        _cakManager?.close()
        super.close()
    }

    /**
     * Execute an HTTP request.
     * This is the main entry point used by the server adapter.
     */
    suspend fun request(
        method: String,
        path: String,
        headers: Map<String, List<String>> = emptyMap(),
        body: String = ""
    ): HtxClientMessage {
        // Stub implementation - returns mock success
        // Real implementation would use generated OpenAPI client
        return HtxClientMessage(
            status = 200,
            body = "OK"
        )
    }

    /**
     * Convenience: PUT content to IPFS and return CID.
     * Content is stored in BlockStore and announced to DHT.
     */
    suspend fun ipfsPut(data: ByteArray): borg.trikeshed.ipfs.CID = cak.put(data)

    /**
     * Convenience: GET content from IPFS by CID.
     * Checks local store, then fetches via Bitswap/DHT.
     */
    suspend fun ipfsGet(cid: borg.trikeshed.ipfs.CID): ByteArray? = cak.get(cid)

    /**
     * Convenience: Pin a CID (store locally + announce to DHT).
     */
    suspend fun ipfsPin(cid: borg.trikeshed.ipfs.CID, address: String = "local") = cak.pin(cid, address)

    /**
     * Convenience: Import CAR archive.
     */
    suspend fun ipfsImportCar(data: ByteArray): borg.trikeshed.ipfs.car.CarParseResult = cak.importCar(data)

    /**
     * Convenience: Export roots as CAR archive.
     */
    suspend fun ipfsExportCar(roots: List<borg.trikeshed.ipfs.CID>, version: Int = 2): ByteArray = cak.exportCar(roots, version)
}

/** Factory for opening HtxElement with IPFS integration. */
object HtxElementFactory {

    /** Open HtxElement with default configuration. */
    suspend fun open(
        baseUrl: String = "http://127.0.0.1",
        ipfsBlockStore: borg.trikeshed.ipfs.BlockStore? = null,
        htxDhtTransport: HtxDhtTransport? = null,
        htxBitswapTransport: HtxBitswapTransport? = null,
        fanoutDispatcher: borg.trikeshed.userspace.FanoutDispatcherElement? = null,
    ): HtxElement {
        val element = HtxElement(
            baseUrl,
            ipfsBlockStore,
            htxDhtTransport,
            htxBitswapTransport,
            fanoutDispatcher,
        )
        element.open()
        return element
    }

    /** Open HtxElement fully wired with CCEK reactor. */
    suspend fun openCcekWired(
        baseUrl: String = "http://127.0.0.1",
        scope: CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
    ): Triple<HtxElement, borg.trikeshed.userspace.LiburingElement, borg.trikeshed.userspace.FanoutDispatcherElement> {
        
        // Install liburing + fanout
        val (liburing, fanout) = scope.installLiburingWithFanout()
        
        // Install DHT transport
        val (_, _, dhtTransport) = HtxDhtTransportFactory.installHtxDhtTransport(256, fanout)
        
        // IPFS element with memory block store
        val element = HtxElement(
            baseUrl = baseUrl,
            htxDhtTransport = dhtTransport,
            fanoutDispatcher = fanout,
        )
        element.open()
        
        return element to liburing to fanout
    }
}

/** Context key for HtxElement in structured concurrency. */
object HtxKey : CoroutineContext.Key<HtxElement>