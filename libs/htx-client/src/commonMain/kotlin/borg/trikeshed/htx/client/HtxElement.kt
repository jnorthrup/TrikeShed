package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.ipfs.BlockStore
import borg.trikeshed.htx.client.ipfs.CID
import borg.trikeshed.htx.client.ipfs.CarParseResult
import borg.trikeshed.htx.client.ipfs.MemoryBlockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val blockStore: BlockStore? = null,
) : AsyncContextElement(ElementState.CREATED) {

    override val key: CoroutineContext.Key<*> get() = HtxKey

    private var _cakManager: CakManager? = null

    /** IPFS CAK Manager - lazy init. */
    val cak: CakManager
        get() {
            if (_cakManager == null) {
                _cakManager = CakManagerFactory.create(
                    blockStore = blockStore ?: MemoryBlockStore(),
                    parentJob = coroutineContext[Job],
                )
            }
            return _cakManager!!
        }

    override suspend fun open() {
        super.open()
        if (blockStore != null) _ = cak
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

    suspend fun ipfsPut(data: ByteArray): CID = cak.put(data)
    suspend fun ipfsGet(cid: CID): ByteArray? = cak.get(cid)
    suspend fun ipfsPin(cid: CID, address: String = "local") = cak.pin(cid, address)
    suspend fun ipfsImportCar(data: ByteArray): CarParseResult = cak.importCar(data)
    suspend fun ipfsExportCar(roots: List<CID>, version: Int = 2): ByteArray = cak.exportCar(roots, version)
}

object HtxElementFactory {
    suspend fun open(
        baseUrl: String = "http://127.0.0.1",
        blockStore: BlockStore? = null,
    ): HtxElement {
        val element = HtxElement(baseUrl, blockStore)
        element.open()
        return element
    }
}

object HtxKey : CoroutineContext.Key<HtxElement>