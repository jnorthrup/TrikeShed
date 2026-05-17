package borg.trikeshed.ws

import borg.trikeshed.lib.*

/**
 * RFC 6455 WebSocket opening handshake — commonMain.
 *
 * Ported from rxf/core/Rfc6455WsInitiator.java (RelaxFactory).
 *
 * ## Client handshake (upgrade request)
 * ```
 * GET /chat HTTP/1.1
 * Host: server.example.com
 * Upgrade: websocket
 * Connection: Upgrade
 * Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
 * Sec-WebSocket-Version: 13
 * ```
 *
 * ## Server handshake (upgrade response)
 * ```
 * HTTP/1.1 101 Switching Protocols
 * Upgrade: websocket
 * Connection: Upgrade
 * Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
 * ```
 */
object Rfc6455Handshake {

    /** RFC 6455 magic GUID for accept key computation (Section 4.2.2, p. 26). */
    const val MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** Required version (RFC 6455 §4.2.2 item 9). */
    const val VERSION = "13"

    /**
     * Generate a base64-encoded 16-byte nonce for `Sec-WebSocket-Key`.
     *
     * Uses a simple shift-xor PRNG since `kotlin.random.Random` is available
     * in commonMain.  For production, wire in a platform-secure RNG.
     */
    fun generateKey(seed: Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()): String {
        val bytes = ByteArray(16)
        var s = seed
        for (i in bytes.indices) {
            s = s xor (s shl 13)
            s = s xor (s ushr 7)
            s = s xor (s shl 17)
            bytes[i] = (s and 0xFF).toByte()
        }
        return bytes.encodeBase64()
    }

    /**
     * Compute the server's `Sec-WebSocket-Accept` value.
     *
     * `accept = base64(SHA-1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))`
     *
     * The SHA-1 hashing is delegated to the platform via [sha1].
     */
    fun computeAccept(key: String): String {
        val input = (key + MAGIC_GUID).encodeToByteArray()
        val hash = sha1(input)
        return hash.encodeBase64()
    }

    /**
     * SHA-1 hash — expect/actual for platform crypto.
     */
    fun sha1(input: ByteArray): ByteArray {
        // Simple SHA-1 implementation in commonMain (RFC 3174).
        // For production, override with platform-native (JCE, Web Crypto, OpenSSL).
        return Sha1Common.hash(input)
    }

    /**
     * Build a client WebSocket upgrade HTTP request string.
     *
     * @param path   Request path (e.g. "/ws")
     * @param host   Host header value (e.g. "example.com:443")
     * @param origin Origin header (optional)
     * @param protocols Comma-separated protocol list (optional)
     * @return Full HTTP request text ready to send over TCP/TLS.
     */
    fun buildUpgradeRequest(
        path: String,
        host: String,
        origin: String? = null,
        protocols: String? = null,
        key: String = generateKey(),
    ): String {
        val sb = StringBuilder()
        sb.append("GET $path HTTP/1.1\r\n")
        sb.append("Host: $host\r\n")
        sb.append("Upgrade: websocket\r\n")
        sb.append("Connection: Upgrade\r\n")
        sb.append("Sec-WebSocket-Key: $key\r\n")
        sb.append("Sec-WebSocket-Version: $VERSION\r\n")
        if (origin != null) sb.append("Origin: $origin\r\n")
        if (protocols != null) sb.append("Sec-WebSocket-Protocol: $protocols\r\n")
        sb.append("\r\n")
        return sb.toString()
    }

    /**
     * Parse and validate a server upgrade response.
     *
     * @param response   Raw HTTP response text from server.
     * @param expectedKey The `Sec-WebSocket-Key` sent in the client request.
     * @return true if the upgrade was accepted, false otherwise.
     */
    fun validateUpgradeResponse(response: String, expectedKey: String): Boolean {
        val lines = response.split("\r\n")
        if (lines.isEmpty()) return false

        // Status line: "HTTP/1.1 101 Switching Protocols"
        val statusLine = lines[0]
        if (!statusLine.contains("101")) return false

        // Parse headers
        var upgrade = false
        var connection = false
        var acceptValid = false

        val expectedAccept = computeAccept(expectedKey)

        for (line in lines.drop(1)) {
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon < 1) continue

            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()

            when (name) {
                "upgrade" -> upgrade = value.contains("websocket", ignoreCase = true)
                "connection" -> connection = value.contains("Upgrade", ignoreCase = true)
                "sec-websocket-accept" -> acceptValid = value == expectedAccept
            }
        }

        return upgrade && connection && acceptValid
    }

    /**
     * Parse an incoming WebSocket upgrade request and produce a server response.
     *
     * @param request Raw HTTP request text from client.
     * @return A 101 upgrade response string, or null if the request is invalid.
     */
    fun handleUpgradeRequest(request: String): String? {
        val lines = request.split("\r\n")
        if (lines.isEmpty()) return null

        // Must be GET
        val requestLine = lines[0]
        if (!requestLine.startsWith("GET ")) return null
        if (!requestLine.contains("HTTP/1.1")) return null

        // Parse headers
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon < 1) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        // Validate required headers (RFC 6455 §4.2.1)
        if (headers.size < 6) return null
        if (headers["upgrade"]?.contains("websocket", ignoreCase = true) != true) return null
        if (headers["connection"]?.contains("Upgrade", ignoreCase = true) != true) return null
        if (headers["sec-websocket-version"] != VERSION) return null

        val key = headers["sec-websocket-key"] ?: return null
        if (key.decodeBase64()?.size != 16) return null

        val accept = computeAccept(key)
        val protocols = headers["sec-websocket-protocol"]

        return buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $accept\r\n")
            if (protocols != null) append("Sec-WebSocket-Protocol: $protocols\r\n")
            append("\r\n")
        }
    }
}

// ── Base64 codec (commonMain, no external deps) ───────────────────

private val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private fun ByteArray.encodeBase64(): String {
    val sb = StringBuilder((size + 2) / 3 * 4)
    var i = 0
    while (i < size) {
        val a = this[i].toInt() and 0xFF
        val b = if (i + 1 < size) this[i + 1].toInt() and 0xFF else 0
        val c = if (i + 2 < size) this[i + 2].toInt() and 0xFF else 0
        val triple = (a shl 16) or (b shl 8) or c
        sb.append(B64_ALPHABET[(triple shr 18) and 0x3F])
        sb.append(B64_ALPHABET[(triple shr 12) and 0x3F])
        sb.append(if (i + 1 < size) B64_ALPHABET[(triple shr 6) and 0x3F] else '=')
        sb.append(if (i + 2 < size) B64_ALPHABET[triple and 0x3F] else '=')
        i += 3
    }
    return sb.toString()
}

private fun String.decodeBase64(): ByteArray? {
    val s = this.trimEnd('=')
    val result = mutableListOf<Byte>()
    var i = 0
    while (i < s.length) {
        val a = B64_ALPHABET.indexOf(s[i])
        if (a < 0) { i++; continue }
        val b = if (i + 1 < s.length) B64_ALPHABET.indexOf(s[i + 1]) else -1
        val c = if (i + 2 < s.length) B64_ALPHABET.indexOf(s[i + 2]) else -1
        val d = if (i + 3 < s.length) B64_ALPHABET.indexOf(s[i + 3]) else -1
        if (b < 0) return null

        result.add(((a shl 2) or (b shr 4)).toByte())
        if (c >= 0) result.add(((b shl 4) or (c shr 2)).toByte())
        if (d >= 0) result.add(((c shl 6) or d).toByte())
        i += 4
    }
    return result.toByteArray()
}

// ── SHA-1 (RFC 3174) commonMain reference implementation ─────────

/**
 * Pure-Kotlin SHA-1 hash — commonMain compatible, no platform dependencies.
 *
 * For production use on JVM, swap with `java.security.MessageDigest`.
 * For JS/WASM, swap with `crypto.subtle.digest("SHA-1", ...)`.
 * This reference implementation guarantees the TDD contract is testable
 * on all platforms without require-external-crypto flags.
 */
internal object Sha1Common {
    fun hash(input: ByteArray): ByteArray {
        // Padding
        val msgLen = input.size.toLong() * 8
        val remainder = (input.size + 1) % 64
        val padLen = if (remainder <= 56) 56 - remainder else 120 - remainder
        val padded = ByteArray(input.size + 1 + padLen + 8)
        input.copyInto(padded, 0, 0, input.size)
        padded[input.size] = 0x80.toByte()

        // Append message length in bits (big-endian)
        for (i in 0..7) {
            padded[padded.size - 1 - i] = ((msgLen ushr (i * 8)) and 0xFF).toByte()
        }

        // Initialize hash values
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        // Process each 512-bit chunk
        val w = IntArray(80)
        var chunk = 0
        while (chunk < padded.size) {
            // Fill w[0..15] from chunk
            for (j in 0..15) {
                val offset = chunk + (j * 4)
                w[j] = ((padded[offset].toInt() and 0xFF) shl 24) or
                    ((padded[offset + 1].toInt() and 0xFF) shl 16) or
                    ((padded[offset + 2].toInt() and 0xFF) shl 8) or
                    (padded[offset + 3].toInt() and 0xFF)
            }
            // Extend w[16..79]
            for (j in 16..79) {
                val t = w[j - 3] xor w[j - 8] xor w[j - 14] xor w[j - 16]
                w[j] = (t shl 1) or (t ushr 31)
            }

            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4

            for (j in 0..79) {
                val f: Int; val k: Int
                when (j) {
                    in 0..19 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                    in 20..39 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                    in 40..59 -> { f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDC.toInt() }
                    else -> { f = b xor c xor d; k = 0xCA62C1D6.toInt() }
                }
                val temp = ((a shl 5) or (a ushr 27)) + f + e + k + w[j]
                e = d; d = c; c = (b shl 30) or (b ushr 2); b = a; a = temp
            }

            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
            chunk += 64
        }

        // Output as big-endian bytes
        return byteArrayOf(
            (h0 ushr 24).toByte(), (h0 ushr 16).toByte(), (h0 ushr 8).toByte(), h0.toByte(),
            (h1 ushr 24).toByte(), (h1 ushr 16).toByte(), (h1 ushr 8).toByte(), h1.toByte(),
            (h2 ushr 24).toByte(), (h2 ushr 16).toByte(), (h2 ushr 8).toByte(), h2.toByte(),
            (h3 ushr 24).toByte(), (h3 ushr 16).toByte(), (h3 ushr 8).toByte(), h3.toByte(),
            (h4 ushr 24).toByte(), (h4 ushr 16).toByte(), (h4 ushr 8).toByte(), h4.toByte(),
        )
    }
}