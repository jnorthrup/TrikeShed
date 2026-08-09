package borg.trikeshed.torrent

/**
 * RFC 3986 percent-encoding utilities — pure Kotlin, no java.net dependency.
 *
 * BitTorrent tracker announces and magnet URIs require percent-encoding
 * of binary data (info_hash, peer_id) and query parameters. This keeps
 * the torrent package free of java.net.URLEncoder / URLDecoder.
 */

// ── Encoding ───────────────────────────────────────────────────────────────

/**
 * Percent-encode every byte of a binary blob.
 *
 * Use for BitTorrent info_hash and peer_id in tracker announce URLs,
 * where every byte (including printable ASCII) must be percent-encoded
 * because the values are raw binary, not text.
 */
fun percentEncodeBinary(data: ByteArray): String =
    buildString(data.size * 3) {
        for (b in data) {
            append('%')
            append(HEX_UPPER[(b.toInt() shr 4) and 0x0F])
            append(HEX_UPPER[b.toInt() and 0x0F])
        }
    }

/**
 * Percent-encode a string for use in a URL query parameter value.
 *
 * Unreserved characters (RFC 3986 §2.3: A-Za-z0-9 -._~) are left as-is;
 * everything else is percent-encoded as UTF-8 bytes.
 */
fun percentEncode(text: String): String =
    buildString(text.length * 2) {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val v = byte.toInt() and 0xFF
            if (v < 128 && UNRESERVED[v]) {
                append(v.toChar())
            } else {
                append('%')
                append(HEX_UPPER[v shr 4])
                append(HEX_UPPER[v and 0x0F])
            }
        }
    }

// ── Decoding ───────────────────────────────────────────────────────────────

/**
 * Percent-decode a string (e.g. a magnet URI parameter value) into a UTF-8 string.
 *
 * Converts %XX sequences to raw bytes, passes through non-% characters as UTF-8,
 * then decodes the full byte array. Invalid %XX sequences are passed through
 * literally.
 */
fun percentDecode(text: String): String {
    val bytes = ByteArray(text.length) // upper bound
    var pos = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 2 < text.length) {
            val hi = hexVal(text[i + 1])
            val lo = hexVal(text[i + 2])
            if (hi >= 0 && lo >= 0) {
                bytes[pos++] = ((hi shl 4) or lo).toByte()
                i += 3
                continue
            }
        }
        // '+' is a space in application/x-www-form-urlencoded (magnet params)
        if (c == '+') {
            bytes[pos++] = ' '.code.toByte()
        } else {
            val src = c.toString().toByteArray(Charsets.UTF_8)
            for (b in src) bytes[pos++] = b
        }
        i++
    }
    return bytes.copyOf(pos).toString(Charsets.UTF_8)
}

/**
 * Percent-decode directly to raw bytes.
 */
fun percentDecodeBytes(text: String): ByteArray {
    val bytes = ByteArray(text.length)
    var pos = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 2 < text.length) {
            val hi = hexVal(text[i + 1])
            val lo = hexVal(text[i + 2])
            if (hi >= 0 && lo >= 0) {
                bytes[pos++] = ((hi shl 4) or lo).toByte()
                i += 3
                continue
            }
        }
        if (c == '+') {
            bytes[pos++] = ' '.code.toByte()
        } else {
            val src = c.toString().toByteArray(Charsets.UTF_8)
            for (b in src) bytes[pos++] = b
        }
        i++
    }
    return bytes.copyOf(pos)
}

// ── Internal ───────────────────────────────────────────────────────────────

private val HEX_UPPER = "0123456789ABCDEF".toCharArray()

private const val UNRESERVED_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
private val UNRESERVED = BooleanArray(128) { false }.also {
    for (c in UNRESERVED_CHARS) it[c.code] = true
}

private fun hexVal(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'A'..'F' -> c - 'A' + 10
    in 'a'..'f' -> c - 'a' + 10
    else -> -1
}
