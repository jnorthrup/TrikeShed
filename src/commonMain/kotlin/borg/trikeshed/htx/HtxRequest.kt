package borg.trikeshed.htx

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toArray
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries

typealias HtxHeader = Join<String, String>
typealias HtxHeaders = Series<HtxHeader>
typealias HtxAuthority = Join<String, Int>
typealias HtxBody = ByteSeries

fun emptyHtxHeaders(): HtxHeaders = emptySeriesOf()
fun emptyHtxBody(): HtxBody = ByteSeries(byteArrayOf())
fun htxHeaders(vararg headers: HtxHeader): HtxHeaders = headers.asList().toSeries()

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
    POST,
    PUT,
    PATCH,
    DELETE,
    OPTIONS,
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
    val authority: HtxAuthority,
    val requestPath: String,
    val resourceId: String? = null,
) {
    val host: String get() = authority.a
    val port: Int get() = authority.b
}

data class HtxRequest(
    val target: HtxTarget,
    val method: HtxMethod = HtxMethod.GET,
    val fetchStyle: HtxFetchStyle = HtxFetchStyle.CURL,
    val headers: HtxHeaders = emptyHtxHeaders(),
    val range: HtxRange? = null,
)

data class HtxResponse(
    val status: Int,
    val body: HtxBody = emptyHtxBody(),
    val headers: HtxHeaders = emptyHtxHeaders(),
)

data class HtxClientConfig(
    val ipfsGatewayProtocol: HtxTransportProtocol = HtxTransportProtocol.HTTP,
    val ipfsGatewayHost: String = "127.0.0.1",
    val ipfsGatewayPort: Int = 8080,
)

fun parseHtxRequest(
    url: String,
    config: HtxClientConfig = HtxClientConfig(),
    range: HtxRange? = null,
    method: HtxMethod = HtxMethod.GET,
): HtxRequest {
    val scheme = when (url.substringBefore("://").lowercase()) {
        "http" -> HtxScheme.HTTP
        "https" -> HtxScheme.HTTPS
        "ipfs" -> HtxScheme.IPFS
        else -> error("Unsupported HTX scheme in URL: $url")
    }

    return HtxRequest(
        target = when (scheme) {
            HtxScheme.HTTP -> parseHttpTarget(url, scheme, HtxTransportProtocol.HTTP, 80)
            HtxScheme.HTTPS -> parseHttpTarget(url, scheme, HtxTransportProtocol.HTTPS, 443)
            HtxScheme.IPFS -> parseIpfsTarget(url, config)
        },
        method = method,
        fetchStyle = when {
            scheme == HtxScheme.IPFS -> HtxFetchStyle.IPFS_GATEWAY
            range != null -> HtxFetchStyle.ARIA2
            else -> HtxFetchStyle.CURL
        },
        range = range,
    )
}

fun HtxRequest.renderWireRequest(): String {
    val methodToken = method.wireToken()
    val hostHeader = if (target.port == target.transportProtocol.defaultPort()) {
        target.host
    } else {
        "${target.host}:${target.port}"
    }

    return buildString {
        append(methodToken)
        append(' ')
        append(target.requestPath)
        append(" HTTP/1.1\r\n")
        append("Host: ")
        append(hostHeader)
        append("\r\n")
        headers.toList().forEach { header ->
            append(header.a)
            append(": ")
            append(header.b)
            append("\r\n")
        }
        append("Connection: close\r\n")
        range?.let {
            append("Range: bytes=${it.startInclusive}-${it.endInclusive}\r\n")
        }
        append("\r\n")
    }
}

fun HtxRequest.headerValue(name: String): String? =
    headers.toList()
        .firstOrNull { it.a.equals(name, ignoreCase = true) }
        ?.b

fun HtxRequest.withHeader(name: String, value: String): HtxRequest {
    val preserved = headers.toList().filterNot { it.a.equals(name, ignoreCase = true) }
    return copy(headers = (preserved + (name j value)).toSeries())
}

fun HtxRequest.withTransportDefaults(): HtxRequest =
    if (headerValue("Accept-Encoding") != null) this else withHeader("Accept-Encoding", "identity")

fun parseHtxResponse(payload: HtxBody): HtxResponse {
    val bytes = payload.toArray()
    val boundary = bytes.indexOfHeaderBoundary()
    if (boundary < 0) {
        return HtxResponse(status = 0, body = ByteSeries(bytes))
    }

    val headerText = bytes.copyOfRange(0, boundary).decodeToString()
    val lines = headerText.split("\r\n")
    val status = lines.firstOrNull()
        ?.split(' ')
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: 0
    val headers = lines.drop(1).mapNotNull(::parseHeaderLine).toSeries()
    val encodedBody = bytes.copyOfRange(boundary + 4, bytes.size)
    val decodedBody = when {
        headers.headerValue("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true ->
            decodeChunkedBody(encodedBody)
        headers.headerValue("Content-Length")?.trim()?.toIntOrNull() != null -> {
            val contentLength = headers.headerValue("Content-Length")!!.trim().toInt()
            encodedBody.copyOf(minOf(contentLength, encodedBody.size))
        }
        else -> encodedBody
    }

    return HtxResponse(
        status = status,
        body = ByteSeries(decodedBody),
        headers = headers,
    )
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
        authority = host j port,
        requestPath = requestPath,
    )
}

private fun parseIpfsTarget(
    url: String,
    config: HtxClientConfig,
): HtxTarget {
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
        authority = config.ipfsGatewayHost j config.ipfsGatewayPort,
        requestPath = requestPath,
        resourceId = cid,
    )
}

private fun HtxTransportProtocol.defaultPort(): Int =
    when (this) {
        HtxTransportProtocol.HTTP -> 80
        HtxTransportProtocol.HTTPS -> 443
    }

fun HtxHeaders.headerValue(name: String): String? =
    toList().firstOrNull { it.a.equals(name, ignoreCase = true) }?.b

private fun HtxMethod.wireToken(): String =
    when (this) {
        HtxMethod.GET -> "GET"
        HtxMethod.HEAD -> "HEAD"
        HtxMethod.POST -> "POST"
        HtxMethod.PUT -> "PUT"
        HtxMethod.PATCH -> "PATCH"
        HtxMethod.DELETE -> "DELETE"
        HtxMethod.OPTIONS -> "OPTIONS"
    }

private fun parseHeaderLine(line: String): HtxHeader? {
    val delimiter = line.indexOf(':')
    if (delimiter <= 0) {
        return null
    }
    val name = line.substring(0, delimiter).trim()
    val value = line.substring(delimiter + 1).trim()
    return name.takeIf { it.isNotEmpty() }?.let { it j value }
}

private fun ByteArray.indexOfHeaderBoundary(): Int {
    if (size < 4) {
        return -1
    }
    for (i in 0..size - 4) {
        if (this[i] == '\r'.code.toByte() &&
            this[i + 1] == '\n'.code.toByte() &&
            this[i + 2] == '\r'.code.toByte() &&
            this[i + 3] == '\n'.code.toByte()
        ) {
            return i
        }
    }
    return -1
}

private fun decodeChunkedBody(encodedBody: ByteArray): ByteArray {
    val decoded = ArrayList<Byte>()
    var offset = 0

    while (offset < encodedBody.size) {
        val lineEnd = encodedBody.indexOfCrLf(offset)
        if (lineEnd < 0) {
            return encodedBody
        }
        val chunkLine = encodedBody.copyOfRange(offset, lineEnd).decodeToString()
        val chunkSize = chunkLine.substringBefore(';').trim().toIntOrNull(16) ?: return encodedBody
        offset = lineEnd + 2

        if (chunkSize == 0) {
            return ByteArray(decoded.size) { decoded[it] }
        }

        val chunkEnd = offset + chunkSize
        if (chunkEnd > encodedBody.size) {
            return encodedBody
        }

        for (i in offset until chunkEnd) {
            decoded += encodedBody[i]
        }

        offset = chunkEnd
        if (offset + 1 >= encodedBody.size ||
            encodedBody[offset] != '\r'.code.toByte() ||
            encodedBody[offset + 1] != '\n'.code.toByte()
        ) {
            return encodedBody
        }
        offset += 2
    }

    return encodedBody
}

private fun ByteArray.indexOfCrLf(start: Int): Int {
    for (i in start until size - 1) {
        if (this[i] == '\r'.code.toByte() && this[i + 1] == '\n'.code.toByte()) {
            return i
        }
    }
    return -1
}
