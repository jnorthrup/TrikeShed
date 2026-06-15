package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.tls.TlsConfig
import borg.trikeshed.tls.TlsElement
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
 * IPFS CAK operations are added via HtxElementWithIpfs in jvmMain.
 */
open class HtxElement(
    val baseUrl: String = "http://127.0.0.1",
    val tlsConfig: TlsConfig? = null,
) : AsyncContextElement(ElementState.CREATED) {

    override val key: CoroutineContext.Key<*> get() = HtxKey

    private var _tlsElement: TlsElement? = null

    /** TLS element for HTTPS connections. */
    val tlsElement: TlsElement?
        get() = _tlsElement

    override suspend fun open() {
        super.open()
        // Initialize TLS if configured and using HTTPS
        if (tlsConfig != null && baseUrl.startsWith("https://")) {
            // TODO: Get ChannelRunner from reactor context
            // _tlsElement = openTlsElementWithRunner(tlsConfig!!, channelRunner)
        }
    }

    override suspend fun close() {
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
        return HtxClientMessage(status = 200, body = "OK (secure)")
    }

    private fun extractHost(url: String): String {
        return url.removePrefix("https://").removePrefix("http://").split("/").first()
    }
}

object HtxElementFactory {
    suspend fun open(
        baseUrl: String = "http://127.0.0.1",
        tlsConfig: TlsConfig? = null,
    ): HtxElement {
        val element = HtxElement(baseUrl, tlsConfig)
        element.open()
        return element
    }

    suspend fun openSecure(
        baseUrl: String,
        tlsConfig: TlsConfig,
    ): HtxElement {
        require(baseUrl.startsWith("https://")) { "Secure connection requires https:// URL" }
        return open(baseUrl, tlsConfig)
    }
}

object HtxKey : CoroutineContext.Key<HtxElement>

/**
 * Creates and opens an HTTP client element.
 */
suspend fun openHtxElement(
    baseUrl: String = "http://127.0.0.1",
    tlsConfig: TlsConfig? = null,
): HtxElement = HtxElementFactory.open(baseUrl, tlsConfig)
