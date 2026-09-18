package borg.trikeshed.htx.client

enum class HtxScheme {
    HTTP,
    HTTPS,
    IPFS,
}

enum class HtxTransportProtocol {
    HTTP,
    HTTPS,
}

enum class HtxMethod {
    GET,
    HEAD,
}

enum class HtxFetchStyle {
    CURL,
    ARIA2,
    IPFS_GATEWAY,
}

data class HtxRange(
    val startInclusive: Long,
    val endInclusive: Long,
)

data class HtxTarget(
    val scheme: HtxScheme,
    val transportProtocol: HtxTransportProtocol,
    val host: String,
    val port: Int,
    val requestPath: String,
    val resourceId: String? = null,
)

data class HtxRequest(
    val target: HtxTarget,
    val method: HtxMethod = HtxMethod.GET,
    val fetchStyle: HtxFetchStyle = HtxFetchStyle.CURL,
    val range: HtxRange? = null,
)

data class HtxClientConfig(
    val ipfsGatewayProtocol: HtxTransportProtocol = HtxTransportProtocol.HTTP,
    val ipfsGatewayHost: String = "127.0.0.1",
    val ipfsGatewayPort: Int = 8080,
)

// TODO(root-move): delete libs/htx-client once these HTX request/target shapes live
// under the root reactor/commonMain stack beside TLS and the direct protocol elements.
internal fun parseRequest(
    url: String,
    config: HtxClientConfig,
    range: HtxRange? = null,
    method: HtxMethod = HtxMethod.GET,
): HtxRequest {
    val scheme = parseScheme(url)
    val fetchStyle = when {
        scheme == HtxScheme.IPFS -> HtxFetchStyle.IPFS_GATEWAY
        range != null -> HtxFetchStyle.ARIA2
        else -> HtxFetchStyle.CURL
    }

    return HtxRequest(
        target = when (scheme) {
            HtxScheme.HTTP -> parseHttpTarget(url, scheme, HtxTransportProtocol.HTTP, 80)
            HtxScheme.HTTPS -> parseHttpTarget(url, scheme, HtxTransportProtocol.HTTPS, 443)
            HtxScheme.IPFS -> parseIpfsTarget(url, config)
        },
        method = method,
        fetchStyle = fetchStyle,
        range = range,
    )
}

internal fun HtxRequest.renderWireRequest(): String {
    val methodToken = when (method) {
        HtxMethod.GET -> "GET"
        HtxMethod.HEAD -> "HEAD"
    }

    val hostHeader = if (target.port == target.transportProtocol.defaultPort()) {
        target.host
    } else {
        "${target.host}:${target.port}"
    }

    val request = StringBuilder()
    request.append(methodToken)
    request.append(' ')
    request.append(target.requestPath)
    request.append(" HTTP/1.1\r\n")
    request.append("Host: ")
    request.append(hostHeader)
    request.append("\r\n")
    request.append("Connection: close\r\n")
    range?.let {
        request.append("Range: bytes=${it.startInclusive}-${it.endInclusive}\r\n")
    }
    request.append("\r\n")
    return request.toString()
}

private fun parseScheme(url: String): HtxScheme =
    when (url.substringBefore("://").lowercase()) {
        "http" -> HtxScheme.HTTP
        "https" -> HtxScheme.HTTPS
        "ipfs" -> HtxScheme.IPFS
        else -> error("Unsupported HTX scheme in URL: $url")
    }

private fun parseHttpTarget(
    url: String,
    scheme: HtxScheme,
    transportProtocol: HtxTransportProtocol,
    defaultPort: Int,
): HtxTarget {
    val withoutScheme = url.substringAfter("://")
    val authority = withoutScheme.substringBefore("/")
    val host = authority.substringBefore(":")
    val port = authority.substringAfter(":", "").toIntOrNull() ?: defaultPort
    val pathSuffix = withoutScheme.substringAfter("/", "")
    val requestPath = if (pathSuffix.isEmpty()) "/" else "/$pathSuffix"
    return HtxTarget(
        scheme = scheme,
        transportProtocol = transportProtocol,
        host = host,
        port = port,
        requestPath = requestPath,
    )
}

private fun parseIpfsTarget(
    url: String,
    config: HtxClientConfig,
): HtxTarget {
    // TODO(htx-reactor): replace gateway translation with a direct IPFS protocol element
    // once HTX request routing can target reactor-native protocol backends.
    val withoutScheme = url.substringAfter("://")
    val cid = withoutScheme.substringBefore("/")
    check(cid.isNotEmpty()) { "IPFS URL requires a CID: $url" }
    val suffix = withoutScheme.substringAfter("/", "")
    val requestPath = buildString {
        append("/ipfs/")
        append(cid)
        if (suffix.isNotEmpty()) {
            append('/')
            append(suffix)
        }
    }
    return HtxTarget(
        scheme = HtxScheme.IPFS,
        transportProtocol = config.ipfsGatewayProtocol,
        host = config.ipfsGatewayHost,
        port = config.ipfsGatewayPort,
        requestPath = requestPath,
        resourceId = cid,
    )
}

private fun HtxTransportProtocol.defaultPort(): Int =
    when (this) {
        HtxTransportProtocol.HTTP -> 80
        HtxTransportProtocol.HTTPS -> 443
    }
