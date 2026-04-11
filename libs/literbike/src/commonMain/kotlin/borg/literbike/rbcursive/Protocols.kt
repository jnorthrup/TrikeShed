package borg.literbike.rbcursive

/**
 * Network protocol parsers using RBCursive combinators.
 * High-performance, zero-allocation protocol parsing.
 * Ported from literbike/src/rbcursive/protocols/mod.rs.
 */

// Protocol module re-exports are handled via individual files in this package.

/** Well-known constants to avoid magic numbers */
const val DEFAULT_PROXY_PORT: Int = 8888
const val UPNP_SSDP_PORT: Int = 1900

/**
 * Protocol detection result used by protocols module.
 */
enum class ProtocolType {
    Http,
    Http2,
    Socks5,
    Tls,
    Json,
    Dns,
    Unknown
}

/**
 * Declarative table of protocol anchors used by shared listeners.
 */
enum class Anchor {
    /** Fixed literal at start, e.g., b"GET ", b"POST" */
    Literal,
    /** Byte range with min length */
    Range,
    /** Starts with byte, then any until closing (non-nested) */
    Confix
}

/**
 * Lightweight hint produced by anchor evaluation prior to full parsing.
 */
enum class ProtocolHint {
    Http,
    Socks5,
    Json,
    Unknown
}

/**
 * HTTP version.
 */
enum class HttpVersion {
    Http10,
    Http11,
    Http2
}

/**
 * HTTP header.
 */
data class HttpHeader(
    val name: ByteArray,
    val value: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpHeader) return false
        return name.contentEquals(other.name) && value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        var result = name.contentHashCode()
        result = 31 * result + value.contentHashCode()
        return result
    }
}

/**
 * HTTP request.
 */
data class HttpRequestFull(
    val method: HttpMethod,
    val path: ByteArray,
    val version: HttpVersion,
    val headers: MutableList<HttpHeader>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequestFull) return false
        return method == other.method &&
                path.contentEquals(other.path) &&
                version == other.version &&
                headers == other.headers
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + path.contentHashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + headers.hashCode()
        return result
    }
}

/**
 * SOCKS5 auth method.
 */
enum class Socks5AuthMethod(val value: Int) {
    NoAuth(0x00),
    GssApi(0x01),
    UserPass(0x02),
    NoAcceptable(0xFF)
}

/**
 * SOCKS5 handshake.
 */
data class Socks5HandshakeFull(
    val version: Int,
    val methods: MutableList<Socks5AuthMethod>
)

/**
 * SOCKS5 connect request.
 */
data class Socks5Connect(
    val version: Int,
    val command: Int,
    val addressType: Int,
    val address: ByteArray,
    val port: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Socks5Connect) return false
        return version == other.version &&
                command == other.command &&
                addressType == other.addressType &&
                address.contentEquals(other.address) &&
                port == other.port
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + command
        result = 31 * result + addressType
        result = 31 * result + address.contentHashCode()
        result = 31 * result + port
        return result
    }
}

/**
 * Evaluate an anchor against data, returning a Signal.
 */
fun evalAnchor(anchor: AnchorDescriptor, data: ByteArray): Signal {
    return when (anchor) {
        is AnchorDescriptor.Literal -> {
            if (data.size < anchor.lit.size) Signal.NeedMore
            else if (data.startsWith(anchor.lit)) Signal.Accept
            else Signal.Reject
        }
        is AnchorDescriptor.Range -> {
            var n = 0
            for (b in data) {
                if (b.toInt() in anchor.start.toInt()..anchor.end.toInt()) {
                    n++
                    if (n >= anchor.min) return Signal.Accept
                } else {
                    break
                }
            }
            if (data.size < anchor.min) Signal.NeedMore else Signal.Reject
        }
        is AnchorDescriptor.Confix -> {
            if (data.firstOrNull() != anchor.open) return Signal.Reject
            val limit = minOf(data.size, 256)
            for (i in 1 until limit) {
                if (data[i] == anchor.close) return Signal.Accept
            }
            Signal.NeedMore
        }
    }
}

/**
 * Anchor descriptor for runtime evaluation.
 */
sealed class AnchorDescriptor {
    data class Literal(val lit: ByteArray) : AnchorDescriptor() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Literal) return false
            return lit.contentEquals(other.lit)
        }
        override fun hashCode() = lit.contentHashCode()
    }
    data class Range(val start: Byte, val end: Byte, val min: Int) : AnchorDescriptor()
    data class Confix(val open: Byte, val close: Byte) : AnchorDescriptor()
}

/**
 * Evaluate all anchors and return a protocol hint on first acceptance.
 */
fun fastAnchorHint(data: ByteArray): ProtocolHint {
    for ((name, anchors) in PROTOCOL_ANCHORS) {
        for (a in anchors) {
            when (evalAnchor(a, data)) {
                is Signal.Accept -> {
                    return when (name) {
                        "http" -> ProtocolHint.Http
                        "socks5" -> ProtocolHint.Socks5
                        "json" -> ProtocolHint.Json
                        else -> ProtocolHint.Unknown
                    }
                }
                Signal.NeedMore -> { /* keep checking others */ }
                Signal.Reject -> { /* try next */ }
            }
        }
    }
    return ProtocolHint.Unknown
}

/**
 * Protocol spec for classification.
 */
data class ProtocolSpec(
    val name: String,
    val anchors: List<AnchorDescriptor>,
    val classify: (ByteArray) -> Classify
)

/**
 * Classification result.
 */
sealed class Classify {
    data class Protocol(val type: ProtocolType) : Classify()
    object NeedMore : Classify()
    object Unknown : Classify()
}

/**
 * Protocol specs table.
 */
val PROTOCOL_SPECS: List<ProtocolSpec> = listOf(
    ProtocolSpec(
        name = "http",
        anchors = listOf(
            AnchorDescriptor.Literal("GET ".toByteArray()),
            AnchorDescriptor.Literal("POST ".toByteArray()),
            AnchorDescriptor.Literal("HEAD ".toByteArray()),
            AnchorDescriptor.Literal("PUT ".toByteArray()),
            AnchorDescriptor.Literal("DELETE ".toByteArray()),
            AnchorDescriptor.Literal("CONNECT ".toByteArray()),
            AnchorDescriptor.Literal("OPTIONS ".toByteArray()),
            AnchorDescriptor.Literal("TRACE ".toByteArray()),
            AnchorDescriptor.Literal("PATCH ".toByteArray()),
        ),
        classify = { data ->
            val parser = HttpParser()
            when (parser.parseRequest(data).getOrNull()?.method?.signal()) {
                Signal.Accept -> Classify.Protocol(ProtocolType.Http)
                Signal.NeedMore -> Classify.NeedMore
                Signal.Reject -> Classify.Unknown
                null -> Classify.Unknown
            }
        }
    ),
    ProtocolSpec(
        name = "socks5",
        anchors = listOf(AnchorDescriptor.Literal(byteArrayOf(0x05))),
        classify = { data ->
            if (data.isEmpty()) Classify.NeedMore
            else if (data[0] == 0x05.toByte()) Classify.Protocol(ProtocolType.Socks5)
            else Classify.Unknown
        }
    ),
    ProtocolSpec(
        name = "json",
        anchors = listOf(AnchorDescriptor.Confix('{'.code.toByte(), '}'.code.toByte())),
        classify = { data ->
            if (data.firstOrNull() != '{'.code.toByte()) return@ProtocolSpec Classify.Unknown
            val limit = minOf(data.size, 256)
            for (i in 1 until limit) {
                if (data[i] == '}'.code.toByte()) return@ProtocolSpec Classify.Protocol(ProtocolType.Json)
            }
            Classify.NeedMore
        }
    ),
    ProtocolSpec(
        name = "tls",
        anchors = listOf(AnchorDescriptor.Literal(byteArrayOf(0x16, 0x03))),
        classify = { data ->
            if (data.size < 3) Classify.NeedMore
            else if (data[0] == 0x16.toByte() && data[1] == 0x03.toByte()) Classify.Protocol(ProtocolType.Tls)
            else Classify.Unknown
        }
    ),
    ProtocolSpec(
        name = "dns",
        anchors = listOf(AnchorDescriptor.Range(0x00.toByte(), 0xFF.toByte(), 2)),
        classify = { data ->
            if (data.size < 4) Classify.NeedMore
            else Classify.Protocol(ProtocolType.Dns)
        }
    )
)

/**
 * Protocol anchor table.
 */
val PROTOCOL_ANCHORS: List<Pair<String, List<AnchorDescriptor>>> = listOf(
    "http" to listOf(
        AnchorDescriptor.Literal("GET ".toByteArray()),
        AnchorDescriptor.Literal("POST ".toByteArray()),
        AnchorDescriptor.Literal("HEAD ".toByteArray()),
        AnchorDescriptor.Literal("PUT ".toByteArray()),
        AnchorDescriptor.Literal("DELETE ".toByteArray()),
        AnchorDescriptor.Literal("CONNECT ".toByteArray()),
        AnchorDescriptor.Literal("OPTIONS ".toByteArray()),
        AnchorDescriptor.Literal("TRACE ".toByteArray()),
        AnchorDescriptor.Literal("PATCH ".toByteArray()),
    ),
    "socks5" to listOf(AnchorDescriptor.Literal(byteArrayOf(0x05))),
    "json" to listOf(AnchorDescriptor.Confix('{'.code.toByte(), '}'.code.toByte())),
    "tls" to listOf(AnchorDescriptor.Literal(byteArrayOf(0x16, 0x03))),
    "dns" to listOf(AnchorDescriptor.Range(0x00.toByte(), 0xFF.toByte(), 2))
)

/**
 * An inlinable listener over a protocol spec table.
 */
class Listener(private val specs: List<ProtocolSpec>) {
    companion object {
        fun fromSpecs(specs: List<ProtocolSpec>): Listener = Listener(specs)
        fun default(): Listener = Listener(PROTOCOL_SPECS)
    }

    /** Classify using anchors first; on Accept, run the fast classify fn */
    fun classify(data: ByteArray): Classify {
        var needMore = false
        for (spec in specs) {
            for (a in spec.anchors) {
                when (evalAnchor(a, data)) {
                    is Signal.Accept -> return spec.classify(data)
                    Signal.NeedMore -> needMore = true
                    Signal.Reject -> {}
                }
            }
        }
        return if (needMore) Classify.NeedMore else Classify.Unknown
    }
}

/**
 * Optional per-port listener table selection.
 */
fun listenerTableFor(port: Int): List<Pair<String, List<AnchorDescriptor>>> {
    // For now, use the global table for all listeners
    return PROTOCOL_ANCHORS
}
