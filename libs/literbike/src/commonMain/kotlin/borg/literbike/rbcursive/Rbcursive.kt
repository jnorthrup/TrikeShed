package borg.literbike.rbcursive

/**
 * RBCursive - Network parser combinators with SIMD-accelerated parsing.
 * High-performance continuation-based streaming protocol detection.
 * Ported from literbike/src/rbcursive/mod.rs.
 */

/**
 * Network tuple for connection identification.
 */
data class NetTuple(
    val localAddr: ByteArray = ByteArray(4),
    val localPort: Int = 0,
    val remoteAddr: ByteArray,
    val remotePort: Int,
    val protocol: Protocol = Protocol.Unknown
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NetTuple) return false
        return localAddr.contentEquals(other.localAddr) &&
                localPort == other.localPort &&
                remoteAddr.contentEquals(other.remoteAddr) &&
                remotePort == other.remotePort &&
                protocol == other.protocol
    }

    override fun hashCode(): Int {
        var result = localAddr.contentHashCode()
        result = 31 * result + localPort
        result = 31 * result + remoteAddr.contentHashCode()
        result = 31 * result + remotePort
        result = 31 * result + protocol.hashCode()
        return result
    }

    companion object {
        fun fromHostPort(host: String, port: Int, protocol: Protocol = Protocol.Unknown): NetTuple {
            val addr = host.split(".").map { it.toIntOrNull()?.toByte() ?: 0 }.toByteArray()
                .let { if (it.size < 4) it + ByteArray(4 - it.size) else it.copyOf(4) }
            return NetTuple(remoteAddr = addr, remotePort = port, protocol = protocol)
        }
    }
}

/**
 * Protocol enumeration for network classification.
 */
enum class Protocol {
    Unknown,
    CustomQuic,
    Http,
    Socks5,
    Tls,
    WebSocket,
    Dns,
    Quic
}

/**
 * Recognition signal for protocol classification.
 */
sealed class Signal {
    data class Accept(val protocol: Protocol) : Signal()
    object NeedMore : Signal()
    object Reject : Signal()
}

/**
 * Protocol detection result.
 */
sealed class ProtocolDetection {
    data class Http(val method: HttpMethod) : ProtocolDetection()
    object Socks5 : ProtocolDetection()
    object Tls : ProtocolDetection()
    object Dns : ProtocolDetection()
    object WebSocket : ProtocolDetection()
    object Json : ProtocolDetection()
    object Unknown : ProtocolDetection()
}

/**
 * HTTP methods detected by scanning.
 */
enum class HttpMethod {
    Get, Post, Put, Delete, Head, Options, Connect, Patch, Trace;

    companion object {
        fun fromBytes(bytes: ByteArray): HttpMethod? = when {
            bytes.contentEquals("GET".toByteArray()) -> Get
            bytes.contentEquals("POST".toByteArray()) -> Post
            bytes.contentEquals("PUT".toByteArray()) -> Put
            bytes.contentEquals("DELETE".toByteArray()) -> Delete
            bytes.contentEquals("HEAD".toByteArray()) -> Head
            bytes.contentEquals("OPTIONS".toByteArray()) -> Options
            bytes.contentEquals("CONNECT".toByteArray()) -> Connect
            bytes.contentEquals("PATCH".toByteArray()) -> Patch
            bytes.contentEquals("TRACE".toByteArray()) -> Trace
            else -> null
        }
    }

    fun asBytes(): ByteArray = when (this) {
        Get -> "GET".toByteArray()
        Post -> "POST".toByteArray()
        Put -> "PUT".toByteArray()
        Delete -> "DELETE".toByteArray()
        Head -> "HEAD".toByteArray()
        Options -> "OPTIONS".toByteArray()
        Connect -> "CONNECT".toByteArray()
        Patch -> "PATCH".toByteArray()
        Trace -> "TRACE".toByteArray()
    }
}

/**
 * Core RBCursive framework - the main entry point.
 */
class RBCursive(
    private val scanner: SimdScanner = ScalarScanner(),
    val patternScanner: PatternScanner = PatternScanner.new()
) {
    companion object {
        fun new(): RBCursive = RBCursive()
    }

    /** Get scanner reference */
    fun getScanner(): SimdScanner = scanner

    /** Create HTTP parser */
    fun httpParser(): HttpParser = HttpParser()

    /** Create SOCKS5 parser */
    fun socks5Parser(): Socks5Parser = Socks5Parser()

    /** Create JSON parser for PAC files */
    fun jsonParser(): JsonParser = JsonParser()

    /** Recognize protocol from data hint */
    fun recognize(tuple: NetTuple, data: ByteArray): Signal {
        if (data.isEmpty()) return Signal.NeedMore

        val detection = detectProtocol(data)
        return when (detection) {
            is ProtocolDetection.Http -> Signal.Accept(Protocol.Http)
            ProtocolDetection.Socks5 -> Signal.Accept(Protocol.Socks5)
            ProtocolDetection.Tls -> Signal.Accept(Protocol.Tls)
            ProtocolDetection.WebSocket -> Signal.Accept(Protocol.WebSocket)
            ProtocolDetection.Dns -> Signal.Accept(Protocol.Dns)
            ProtocolDetection.Json -> Signal.Accept(Protocol.Http)
            ProtocolDetection.Unknown -> {
                // Check for QUIC - QUIC v1 packets carry the fixed bit (0x40)
                if (data[0].toInt() and 0x40 != 0) {
                    Signal.Accept(Protocol.Quic)
                } else {
                    Signal.Reject
                }
            }
        }
    }

    /** Detect protocol from data using SIMD scanning */
    fun detectProtocol(data: ByteArray): ProtocolDetection {
        val structural = scanner.scanStructural(data)

        // Check for SOCKS5
        if (data.size >= 2 && data[0] == 0x05.toByte()) {
            return ProtocolDetection.Socks5
        }

        // Check for HTTP methods
        val httpMethod = detectHttpMethod(data)
        if (httpMethod != null) {
            return ProtocolDetection.Http(httpMethod)
        }

        // Check for JSON (PAC files)
        if (structural.isNotEmpty() && data.firstOrNull() == '{'.code.toByte()) {
            return ProtocolDetection.Json
        }

        return ProtocolDetection.Unknown
    }

    /** Detect HTTP method using scanning */
    private fun detectHttpMethod(data: ByteArray): HttpMethod? {
        val spaces = scanner.scanBytes(data, byteArrayOf(' '.code.toByte()))
        if (spaces.isNotEmpty()) {
            val firstSpace = spaces[0]
            if (firstSpace < data.size) {
                val methodBytes = data.copyOf(firstSpace)
                return HttpMethod.fromBytes(methodBytes)
            }
        }
        return null
    }

    /** Match glob patterns against data */
    fun matchGlob(data: ByteArray, pattern: String): PatternMatchResult {
        return patternScanner.patternMatcher.matchGlob(data, pattern)
    }

    /** Match regex patterns against data */
    fun matchRegex(data: ByteArray, pattern: String): Result<PatternMatchResult> {
        return patternScanner.patternMatcher.matchRegex(data, pattern)
    }

    /** Find all glob pattern matches */
    fun findAllGlob(data: ByteArray, pattern: String): List<PatternMatch> {
        return patternScanner.patternMatcher.findAllGlob(data, pattern)
    }

    /** Find all regex pattern matches */
    fun findAllRegex(data: ByteArray, pattern: String): Result<List<PatternMatch>> {
        return patternScanner.patternMatcher.findAllRegex(data, pattern)
    }

    /** SIMD-accelerated pattern scanning */
    fun scanWithPattern(
        data: ByteArray,
        pattern: String,
        patternType: PatternType
    ): Result<List<PatternMatch>> {
        return patternScanner.scanWithPattern(data, pattern, patternType)
    }

    /** Get pattern matching capabilities */
    fun patternCapabilities(): PatternCapabilities {
        return patternScanner.patternMatcher.patternCapabilities()
    }
}

/**
 * Simple HTTP parser.
 */
class HttpParser {
    fun parseRequest(data: ByteArray): Result<HttpRequest> {
        val str = data.decodeToString()
        val lines = str.lineSequence().toList()
        if (lines.isEmpty()) return Result.failure(Exception("Empty request"))

        val requestLine = lines[0].split(" ")
        if (requestLine.size < 3) return Result.failure(Exception("Invalid request line"))

        val method = HttpMethod.fromBytes(requestLine[0].toByteArray())
            ?: return Result.failure(Exception("Unknown method"))

        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break
            val parts = line.split(": ", limit = 2)
            if (parts.size == 2) headers[parts[0]] = parts[1]
        }

        return Result.success(HttpRequest(method, requestLine[1], requestLine[2], headers))
    }
}

data class HttpRequest(
    val method: HttpMethod,
    val path: String,
    val version: String,
    val headers: Map<String, String>
)

/**
 * Simple SOCKS5 parser.
 */
class Socks5Parser {
    fun parseHandshake(data: ByteArray): Result<Socks5Handshake> {
        if (data.size < 3) return Result.failure(Exception("Incomplete handshake"))
        if (data[0] != 0x05.toByte()) return Result.failure(Exception("Not SOCKS5"))

        val nMethods = data[1].toInt() and 0xFF
        if (data.size < 2 + nMethods) return Result.failure(Exception("Incomplete methods"))

        val methods = data.copyOfRange(2, 2 + nMethods).toList()
        return Result.success(Socks5Handshake(methods))
    }
}

data class Socks5Handshake(
    val methods: List<Byte>
)

/**
 * Simple JSON parser for PAC files.
 */
class JsonParser {
    fun parse(data: ByteArray): Result<Map<String, Any>> {
        return runCatching {
            val str = data.decodeToString().trim()
            if (!str.startsWith("{") || !str.endsWith("}")) {
                throw IllegalArgumentException("Not a JSON object")
            }
            // Simple parsing - in production use a proper JSON library
            emptyMap()
        }
    }
}

/**
 * Indexed trait for position-based access.
 */
interface Indexed<T> {
    fun get(index: Int): T?
    fun len(): Int
    fun isEmpty(): Boolean = len() == 0
}

/**
 * Join trait for combining/concatenating.
 */
fun <T : CharSequence> List<T>.joinItems(separator: String): String = joinToString(separator)
