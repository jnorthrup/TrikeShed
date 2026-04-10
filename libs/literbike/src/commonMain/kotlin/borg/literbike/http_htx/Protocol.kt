package borg.literbike.http_htx

/**
 * HTX block types (matches HAProxy encoding)
 */
enum class HtxBlockType(val value: UByte) {
    ReqSl(0u),   // Request start-line
    ResSl(1u),   // Response start-line
    Hdr(2u),     // Header name/value
    Eoh(3u),     // End-of-headers
    Data(4u),    // Data block
    Tlr(5u),     // Trailer name/value
    Eot(6u),     // End-of-trailers
    Unused(15u); // Unused/removed block

    companion object {
        fun fromByte(b: UByte): HtxBlockType = when (b) {
            0u -> ReqSl
            1u -> ResSl
            2u -> Hdr
            3u -> Eoh
            4u -> Data
            5u -> Tlr
            6u -> Eot
            15u -> Unused
            else -> Unused
        }
    }
}

/**
 * HTX start-line flags (matches HAProxy)
 */
@JvmInline
value class HtxSlFlags(val value: UInt = 0u) {
    companion object {
        const val IS_RESP: UInt = 0x00000001u
        const val XFER_LEN: UInt = 0x00000002u
        const val XFER_ENC: UInt = 0x00000004u
        const val CLEN: UInt = 0x00000008u
        const val CHNK: UInt = 0x00000010u
        const val VER_11: UInt = 0x00000020u
        const val BODYLESS: UInt = 0x00000040u
        const val HAS_SCHM: UInt = 0x00000080u
        const val SCHM_HTTP: UInt = 0x00000100u
        const val SCHM_HTTPS: UInt = 0x00000200u
        const val HAS_AUTHORITY: UInt = 0x00000400u
        const val NORMALIZED_URI: UInt = 0x00000800u
        const val CONN_UPG: UInt = 0x00001000u
        const val BODYLESS_RESP: UInt = 0x00002000u
        const val NOT_HTTP: UInt = 0x00004000u
    }

    fun isResponse(): Boolean = (value and IS_RESP) != 0u
    fun hasTransferLength(): Boolean = (value and XFER_LEN) != 0u
    fun hasContentLength(): Boolean = (value and CLEN) != 0u
    fun isChunked(): Boolean = (value and CHNK) != 0u
    fun isHttp11(): Boolean = (value and VER_11) != 0u
    fun isBodyless(): Boolean = (value and BODYLESS) != 0u
    fun hasScheme(): Boolean = (value and HAS_SCHM) != 0u
    fun isHttpScheme(): Boolean = (value and SCHM_HTTP) != 0u
    fun isHttpsScheme(): Boolean = (value and SCHM_HTTPS) != 0u
    fun hasAuthority(): Boolean = (value and HAS_AUTHORITY) != 0u
    fun hasNormalizedUri(): Boolean = (value and NORMALIZED_URI) != 0u
}

/**
 * HTX message flags
 */
@JvmInline
value class HtxMessageFlags(val value: UInt = 0u) {
    companion object {
        const val NONE: UInt = 0x00000000u
        const val PARSING_ERROR: UInt = 0x00000001u
        const val PROCESSING_ERROR: UInt = 0x00000002u
        const val FRAGMENTED: UInt = 0x00000004u
        const val UNORDERED: UInt = 0x00000008u
        const val EOM: UInt = 0x00000010u // End of message
    }

    fun isEndOfMessage(): Boolean = (value and EOM) != 0u
    fun hasParsingError(): Boolean = (value and PARSING_ERROR) != 0u
    fun isFragmented(): Boolean = (value and FRAGMENTED) != 0u
}

/**
 * HTTP method (for requests)
 */
enum class HttpMethod {
    Get, Post, Put, Delete, Head, Options, Connect, Patch, Trace, Unknown;

    companion object {
        fun fromBytes(b: ByteArray): HttpMethod? = when {
            b.contentEquals("GET".toByteArray()) -> Get
            b.contentEquals("POST".toByteArray()) -> Post
            b.contentEquals("PUT".toByteArray()) -> Put
            b.contentEquals("DELETE".toByteArray()) -> Delete
            b.contentEquals("HEAD".toByteArray()) -> Head
            b.contentEquals("OPTIONS".toByteArray()) -> Options
            b.contentEquals("CONNECT".toByteArray()) -> Connect
            b.contentEquals("PATCH".toByteArray()) -> Patch
            b.contentEquals("TRACE".toByteArray()) -> Trace
            else -> null
        }

        fun fromString(s: String): HttpMethod = when (s.uppercase()) {
            "GET" -> Get
            "POST" -> Post
            "PUT" -> Put
            "DELETE" -> Delete
            "HEAD" -> Head
            "OPTIONS" -> Options
            "CONNECT" -> Connect
            "PATCH" -> Patch
            "TRACE" -> Trace
            else -> Unknown
        }
    }

    fun toBytes(): ByteArray = when (this) {
        Get -> "GET".toByteArray()
        Post -> "POST".toByteArray()
        Put -> "PUT".toByteArray()
        Delete -> "DELETE".toByteArray()
        Head -> "HEAD".toByteArray()
        Options -> "OPTIONS".toByteArray()
        Connect -> "CONNECT".toByteArray()
        Patch -> "PATCH".toByteArray()
        Trace -> "TRACE".toByteArray()
        Unknown -> ByteArray(0)
    }
}

/**
 * HTX start-line (matches HAProxy struct htx_sl)
 * For requests: method, uri, version
 * For responses: version, status, reason
 */
data class HtxStartLine(
    val flags: HtxSlFlags = HtxSlFlags(),
    val meth: HttpMethod? = null,
    val status: UShort? = null,
    val uri: ByteArray = ByteArray(0),
    val version: Pair<UByte, UByte> = 1u to 1u,
    val reason: ByteArray = ByteArray(0)
) {
    companion object {
        fun newRequest(method: HttpMethod, uri: ByteArray, major: UByte = 1u, minor: UByte = 1u): HtxStartLine =
            HtxStartLine(meth = method, uri = uri, version = major to minor)

        fun newResponse(status: UShort, reason: ByteArray, major: UByte = 1u, minor: UByte = 1u): HtxStartLine =
            HtxStartLine(
                flags = HtxSlFlags(HtxSlFlags.IS_RESP or HtxSlFlags.VER_11),
                status = status,
                uri = ByteArray(0),
                version = major to minor,
                reason = reason
            )
    }

    fun isRequest(): Boolean = meth != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HtxStartLine) return false
        return flags == other.flags && meth == other.meth && status == other.status &&
                uri.contentEquals(other.uri) && version == other.version && reason.contentEquals(other.reason)
    }

    override fun hashCode(): Int {
        var result = flags.hashCode()
        result = 31 * result + (meth?.hashCode() ?: 0)
        result = 31 * result + (status?.hashCode() ?: 0)
        result = 31 * result + uri.contentHashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + reason.contentHashCode()
        return result
    }
}

/**
 * HTX block - a single element of an HTX message
 */
sealed class HtxBlockData {
    data class StartLine(val line: HtxStartLine) : HtxBlockData()
    data class Header(val name: ByteArray, val value: ByteArray) : HtxBlockData() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Header) return false
            return name.contentEquals(other.name) && value.contentEquals(other.value)
        }
        override fun hashCode(): Int = 31 * name.contentHashCode() + value.contentHashCode()
    }
    data class Data(val payload: ByteArray) : HtxBlockData() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Data) return false
            return payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = payload.contentHashCode()
    }
    data class Trailer(val name: ByteArray, val value: ByteArray) : HtxBlockData() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Trailer) return false
            return name.contentEquals(other.name) && value.contentEquals(other.value)
        }
        override fun hashCode(): Int = 31 * name.contentHashCode() + value.contentHashCode()
    }
    object EndHeaders : HtxBlockData()
    object EndTrailers : HtxBlockData()

    fun blockType(): HtxBlockType = when (this) {
        is StartLine -> if (line.isRequest()) HtxBlockType.ReqSl else HtxBlockType.ResSl
        is Header -> HtxBlockType.Hdr
        is Data -> HtxBlockType.Data
        is Trailer -> HtxBlockType.Tlr
        EndHeaders -> HtxBlockType.Eoh
        EndTrailers -> HtxBlockType.Eot
    }
}

/**
 * HTX message - complete HTTP message in internal format
 */
class HtxMessage {
    val blocks = mutableListOf<HtxBlockData>()
    var flags: HtxMessageFlags = HtxMessageFlags(HtxMessageFlags.NONE)
        private set

    companion object {
        fun new(): HtxMessage = HtxMessage()
    }

    fun isEmpty(): Boolean = blocks.isEmpty()

    fun len(): Int = blocks.size

    fun addStartLine(sl: HtxStartLine) {
        blocks.add(HtxBlockData.StartLine(sl))
    }

    fun addHeader(name: ByteArray, value: ByteArray) {
        blocks.add(HtxBlockData.Header(name, value))
    }

    fun addData(data: ByteArray) {
        blocks.add(HtxBlockData.Data(data))
    }

    fun addTrailer(name: ByteArray, value: ByteArray) {
        blocks.add(HtxBlockData.Trailer(name, value))
    }

    fun addEndHeaders() {
        blocks.add(HtxBlockData.EndHeaders)
    }

    fun addEndTrailers() {
        blocks.add(HtxBlockData.EndTrailers)
    }

    fun setEom() {
        flags = HtxMessageFlags(HtxMessageFlags.EOM)
    }

    fun startLine(): HtxStartLine? = blocks.firstNotNullOfOrNull { b ->
        if (b is HtxBlockData.StartLine) b.line else null
    }

    fun headers(): List<Pair<ByteArray, ByteArray>> = blocks.mapNotNull { b ->
        if (b is HtxBlockData.Header) b.name to b.value else null
    }
}

/**
 * HtxBlock metadata (matches HAProxy struct htx_blk)
 */
data class HtxBlock(
    val addr: UInt,
    val info: UInt
) {
    companion object {
        fun new(blockType: HtxBlockType, nameLen: UInt, valueLen: UInt, addr: UInt): HtxBlock {
            val info = (blockType.value.toUInt() shl 28) or (valueLen shl 8) or nameLen
            return HtxBlock(addr, info)
        }
    }

    fun blockType(): HtxBlockType = HtxBlockType.fromByte((info shr 28).toUByte())
    fun valueLen(): UInt = info and 0x0FFFFFFFu
    fun nameLen(): UInt = (info shr 8) and 0xFFu
}

/**
 * HtxKey - Root of HTTP-HTX hierarchy
 */
object HtxKey {
    val FACTORY: () -> HtxElement = { HtxElement() }
}

/**
 * HtxElement - HTTP-HTX operational state
 *
 * Tracks HTTP parsing metrics across all versions (HTTP/1, HTTP/2, HTTP/3)
 */
class HtxElement {
    val version: UInt = 1u
    private val http1Count = kotlin.native.concurrent.AtomicLong(0)
    private val http2Count = kotlin.native.concurrent.AtomicLong(0)
    private val http3Count = kotlin.native.concurrent.AtomicLong(0)
    private val bytesParsed = kotlin.native.concurrent.AtomicLong(0)
    private val errorsCount = kotlin.native.concurrent.AtomicLong(0)
    private val activeRequests = kotlin.native.concurrent.AtomicInt(0)

    fun recordHttp1(bytes: ULong) {
        http1Count.incrementAndGet()
        bytesParsed.addAndGet(bytes.toLong())
    }

    fun recordHttp2(bytes: ULong) {
        http2Count.incrementAndGet()
        bytesParsed.addAndGet(bytes.toLong())
    }

    fun recordHttp3(bytes: ULong) {
        http3Count.incrementAndGet()
        bytesParsed.addAndGet(bytes.toLong())
    }

    fun recordError() {
        errorsCount.incrementAndGet()
    }

    fun requestStart() {
        activeRequests.incrementAndGet()
    }

    fun requestEnd() {
        activeRequests.decrementAndGet()
    }

    fun http1Count(): ULong = http1Count.get().toULong()
    fun http2Count(): ULong = http2Count.get().toULong()
    fun http3Count(): ULong = http3Count.get().toULong()
    fun bytesParsed(): ULong = bytesParsed.get().toULong()
    fun errors(): ULong = errorsCount.get().toULong()
    fun activeRequests(): UInt = activeRequests.get().toUInt()
    fun totalMessages(): ULong = http1Count() + http2Count() + http3Count()
}

// ============================================================================
// HTTP/1 Parser
// ============================================================================

private enum class ParseState {
    RequestLine, Headers, Body
}

/**
 * Parse HTTP/1.x text format into HTX blocks
 */
fun parseHttp1(input: ByteArray): HtxMessage? {
    val inputStr = input.decodeToString()
    val msg = HtxMessage()
    var state = ParseState.RequestLine

    val lines = inputStr.split("\r\n", "\n")
    for (line in lines) {
        val trimmed = line.trimEnd('\r')

        when (state) {
            ParseState.RequestLine -> {
                parseRequestLine(trimmed)?.let { (method, uri, version) ->
                    msg.addStartLine(HtxStartLine.newRequest(method, uri.toByteArray(), version.first, version.second))
                    state = ParseState.Headers
                } ?: parseStatusLine(trimmed)?.let { (status, reason, version) ->
                    msg.addStartLine(HtxStartLine.newResponse(status, reason.toByteArray(), version.first, version.second))
                    state = ParseState.Headers
                } ?: return null
            }
            ParseState.Headers -> {
                if (trimmed.isEmpty()) {
                    msg.addEndHeaders()
                    state = ParseState.Body
                    continue
                }
                parseHeaderLine(trimmed)?.let { (name, value) ->
                    msg.addHeader(name.toByteArray(), value.toByteArray())
                }
            }
            ParseState.Body -> {
                if (trimmed.isNotEmpty()) {
                    msg.addData(trimmed.toByteArray())
                }
            }
        }
    }

    msg.setEom()
    return msg
}

private fun parseRequestLine(line: String): Triple<HttpMethod, String, Pair<UByte, UByte>>? {
    val parts = line.split(' ', limit = 3)
    if (parts.size < 3) return null
    val method = HttpMethod.fromString(parts[0])
    val version = parseVersion(parts[2]) ?: return null
    return Triple(method, parts[1], version)
}

private fun parseStatusLine(line: String): Triple<UShort, String, Pair<UByte, UByte>>? {
    val parts = line.split(' ', limit = 3)
    if (parts.size < 2) return null
    if (!parts[0].startsWith("HTTP/")) return null
    val status = parts[1].toUShortOrNull() ?: return null
    val version = parseVersion(parts[0]) ?: return null
    val reason = parts.getOrNull(2) ?: ""
    return Triple(status, reason, version)
}

private fun parseVersion(s: String): Pair<UByte, UByte>? {
    if (!s.startsWith("HTTP/")) return null
    val rest = s.drop(5)
    val parts = rest.split('.', limit = 2)
    if (parts.size < 2) return null
    val major = parts[0].toUByteOrNull() ?: return null
    val minor = parts[1].toUByteOrNull() ?: return null
    return major to minor
}

private fun parseHeaderLine(line: String): Pair<String, String>? {
    val colonPos = line.indexOf(':') ?: return null
    val name = line.substring(0, colonPos).trim()
    val value = line.substring(colonPos + 1).trim()
    return name to value
}

/**
 * Normalize input bytes to HTX representation
 */
fun normalizeToHtx(input: ByteArray, protocolHint: ByteArray = ByteArray(0)): HtxMessage {
    parseHttp1(input)?.let { return it }
    return HtxMessage()
}

/**
 * Parse bytes into HTX message (convenience wrapper)
 */
fun parseHtx(input: ByteArray): HtxMessage? = parseHttp1(input)
