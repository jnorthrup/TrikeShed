package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.ipfs.CakManager
import borg.trikeshed.htx.client.ipfs.CarParseResult
import borg.trikeshed.htx.client.ipfs.HtxBitswapTransport
import borg.trikeshed.htx.client.ipfs.HtxDhtTransport
import borg.trikeshed.htx.client.ipfs.MemoryBlockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext

@Serializable
data class HtxClientMessage(
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
    val error: String? = null
) {
    val isSuccess: Boolean get() = status in 200..299
}

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

class HtxElement(
    val baseUrl: String = "http://127.0.0.1",
    private val blockStore: borg.trikeshed.htx.client.ipfs.BlockStore? = null,
    private val htxDhtTransport: HtxDhtTransport? = null,
    private val htxBitswapTransport: HtxBitswapTransport? = null,
) : AsyncContextElement(ElementState.CREATED) {

    override val key: CoroutineContext.Key<*> get() = HtxKey

    private var _cakManager: CakManager? = null

    val cak: CakManager
        get() {
            if (_cakManager == null) {
                _cakManager = CakManagerFactory.createHtxWired(
                    blockStore = blockStore ?: MemoryBlockStore(),
                    dhtTransport = htxDhtTransport,
                    bitswapTransport = htxBitswapTransport,
                    parentJob = coroutineContext[Job],
                )
            }
            return _cakManager!!
        }

    override suspend fun open() {
        super.open()
        if (blockStore != null || htxDhtTransport != null || htxBitswapTransport != null) {
            _ = cak
        }
    }

    override suspend fun close() {
        _cakManager?.close()
        super.close()
    }

    suspend fun request(
        method: String,
        path: String,
        headers: Map<String, List<String>> = emptyMap(),
        body: String = ""
    ): HtxClientMessage = HtxClientMessage(status = 200, body = "OK")

    suspend fun ipfsPut(data: ByteArray): borg.trikeshed.htx.client.ipfs.CID = cak.put(data)
    suspend fun ipfsGet(cid: borg.trikeshed.htx.client.ipfs.CID): ByteArray? = cak.get(cid)
    suspend fun ipfsPin(cid: borg.trikeshed.htx.client.ipfs.CID, address: String = "local") = cak.pin(cid, address)
    suspend fun ipfsImportCar(data: ByteArray): CarParseResult = cak.importCar(data)
    suspend fun ipfsExportCar(roots: List<borg.trikeshed.htx.client.ipfs.CID>, version: Int = 2): ByteArray = cak.exportCar(roots, version)
}

object HtxElementFactory {

    suspend fun open(
        baseUrl: String = "http://127.0.0.1",
        blockStore: borg.trikeshed.htx.client.ipfs.BlockStore? = null,
        htxDhtTransport: HtxDhtTransport? = null,
        htxBitswapTransport: HtxBitswapTransport? = null,
    ): HtxElement {
        val element = HtxElement(baseUrl, blockStore, htxDhtTransport, htxBitswapTransport)
        element.open()
        return element
    }

    suspend fun openCcekWired(
        baseUrl: String = "http://127.0.0.1",
        scope: CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
    ): Triple<HtxElement, borg.trikeshed.userspace.LiburingElement, borg.trikeshed.userspace.FanoutDispatcherElement> {
        
        val (liburing, fanout) = scope.installLiburingWithFanout()
        val (_, _, dhtTransport) = HtxDhtTransportFactory.installHtxDhtTransport(256)
        
        val element = HtxElement(
            baseUrl = baseUrl,
            htxDhtTransport = dhtTransport,
        )
        element.open()
        
        return element to liburing to fanout
    }
}

object HtxKey : CoroutineContext.Key<HtxElement>