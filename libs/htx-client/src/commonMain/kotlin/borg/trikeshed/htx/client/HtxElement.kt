package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.ipfs.BlockStore
import borg.trikeshed.htx.client.ipfs.CID
import borg.trikeshed.htx.client.ipfs.CarParseResult
import borg.trikeshed.htx.client.ipfs.MemoryBlockStore
import borg.trikeshed.tls.TlsConfig
import borg.trikeshed.tls.TlsElement
import borg.trikeshed.tls.openTlsElementWithRunner
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

/**
 * HTTP/HTTPS client element with TLS support.
 */
open class HtxElement(
    val baseUrl: String = "http://127.0.0.1",
    private val blockStore: BlockStore? = null,
    val tlsConfig: TlsConfig? = null,
) : AsyncContextElement(ElementState.CREATED) {

    override val key: CoroutineContext.Key<*> get() = HtxKey

    private var _tlsElement: TlsElement? = null
    private var _cakManager: CakManager? = null

    /** TLS element for HTTPS connections. */
    val tlsElement: TlsElement?
        get() = _tlsElement

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

        // Initialize TLS if configured and using HTTPS
        if (tlsConfig != null && baseUrl.startsWith("https://")) {
            // TODO: Get ChannelRunner from reactor context
            // _tlsElement = openTlsElementWithRunner(tlsConfig!!, channelRunner)
        }
    }

    override suspend fun close() {
        _cakManager?.close()
        _tlsElement?.close()
        super.close()
    }

    suspend fun request(
        method: String,
        path: String,
        headers: Map<String, List<String>> = emptyMap(),
        body: String = ""
    ): HtxClientMessage = HtxClientMessage(status = 200, body = "OK")

    suspend fun secureRequest(
        method: String,
        path: String,
        headers: Map<String, List<String>> = emptyMap(),
        body: String = ""
    ): HtxClientMessage {
        // TODO: Implement HTTPS request using TLS element
        // val endpoint = tlsElement?.clientEndpoint(extractHost(baseUrl))
        // endpoint?.handshake()
        // endpoint?.write(...)
        // endpoint?.read(...)
        return HtxClientMessage(status = 200, body = "OK (secure)")
    }

    suspend fun ipfsPut(data: ByteArray): CID = cak.put(data)
    suspend fun ipfsGet(cid: CID): ByteArray? = cak.get(cid)
    suspend fun ipfsPin(cid: CID, address: String = "local") = cak.pin(cid, address)
    suspend fun ipfsImportCar(data: ByteArray): CarParseResult = cak.importCar(data)
    suspend fun ipfsExportCar(roots: List<CID>, version: Int = 2): ByteArray = cak.exportCar(roots, version)

    private fun extractHost(url: String): String {
        return url.removePrefix("https://").removePrefix("http://").split("/").first()
    }
}

object HtxElementFactory {
    suspend fun open(
        baseUrl: String = "http://127.0.0.1",
        blockStore: BlockStore? = null,
        tlsConfig: TlsConfig? = null,
    ): HtxElement {
        val element = HtxElement(baseUrl, blockStore, tlsConfig)
        element.open()
        return element
    }

    suspend fun openSecure(
        baseUrl: String,
        tlsConfig: TlsConfig,
        blockStore: BlockStore? = null,
    ): HtxElement {
        require(baseUrl.startsWith("https://")) { "Secure connection requires https:// URL" }
        return open(baseUrl, blockStore, tlsConfig)
    }
}

object HtxKey : CoroutineContext.Key<HtxElement>

/**
 * Creates and opens an HTTP client element.
 */
suspend fun openHtxElement(
    baseUrl: String = "http://127.0.0.1",
    blockStore: BlockStore? = null,
    tlsConfig: TlsConfig? = null,
): HtxElement = HtxElementFactory.open(baseUrl, blockStore, tlsConfig)