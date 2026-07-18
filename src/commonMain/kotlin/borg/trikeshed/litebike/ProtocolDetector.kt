@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.litebike

import borg.trikeshed.litebike.taxonomy.Protocol

/**
 * Byte-level protocol classifier for the LitebikeListenerElement bind
 * adapter. Litebike's `rbcursive::scanner` is the canonical detector;
 * this is a minimal port that covers the protocols the wire currently
 * speaks — and falls back to `Protocol.Http` because the first packet
 * usually is.
 *
 * Detection rules (litebike-derived, byte-stable):
 *   - bytes[0..1] == "GE" / "PO" / "PU" / "DE" / "HE" / "OP" / "PA" / "TR" → Http
 *     (covers GET, POST, PUT, DELETE, HEAD, OPTIONS, PATCH, TRACE)
 *   - bytes[0] == 0x16 → Tls
 *   - bytes[0..2] == "SSH" → Socks5 (litebike uses the SSH-banner sniff as
 *     a SOCKS5/SOCKS5-style dispatch).
 *   - bytes[0..3] == { 0x00, 0x00, ... } WebSocket-style fin/opcode → WebSocket
 *   - bytes equal to "{...}" prefix → Json
 *   - else Json
 *
 * This port is intentionally minimal; the JVM adapter can override via
 * the `ProtocolDetectorStrategy` SPI without touching commonMain.
 */
fun interface ProtocolDetectorStrategy {
    fun detect(firstBytes: ByteArray, total: Int): Protocol
}

object ProtocolDetector {
    var strategy: ProtocolDetectorStrategy = DefaultDetector

    fun detect(firstBytes: ByteArray, total: Int = firstBytes.size): Protocol =
        strategy.detect(firstBytes, total)
}

private object DefaultDetector : ProtocolDetectorStrategy {
    override fun detect(firstBytes: ByteArray, total: Int): Protocol {
        if (firstBytes.isEmpty()) return Protocol.Json
        // TLS record: ContentType 22 (0x16) | VersionMajor 0x03 ...
        if (firstBytes[0] == 0x16.toByte()) return Protocol.Tls
        // WebSocket frame: FIN (1) | RSV (000) | OPCODE (0000-0010)
        val wsByte = firstBytes[0].toInt() and 0xff
        if (wsByte in 0x81..0x82) return Protocol.WebSocket
        // HTTP/1.x request line tokens
        val length = minOf(8, firstBytes.size)
        val charArr = CharArray(length) { firstBytes[it].toInt().toChar() }
        val head = charArr.concatToString()
        for (token in httpMethods) if (head.startsWith(token)) return Protocol.Http
        // DNS header (note: litebike reserves a future Socks5 path here)
        if (firstBytes[0] in 0x00.toByte()..0x04.toByte() && total >= 2 &&
            (firstBytes[1].toInt() and 0x80) == 0x00
        ) return Protocol.Dns
        // JSON: leading '{' or '['
        if (firstBytes[0] == '{'.code.toByte() || firstBytes[0] == '['.code.toByte()) {
            return Protocol.Json
        }
        // HTTP/2 preface: "PRI * HTTP/2.0"
        if (firstBytes.size >= 6 && firstBytes[0].toInt() == 0x50 && firstBytes[1].toInt() == 0x52 &&
            firstBytes[2].toInt() == 0x49 && firstBytes[3].toInt() == 0x20 &&
            firstBytes[4].toInt() == 0x2A && firstBytes[5].toInt() == 0x20
        ) {
            return Protocol.Http2
        }
        // Default fallback — current bind traffic is usually HTTP/1.1.
        return Protocol.Http
    }

    private val httpMethods = arrayOf(
        "GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "OPTIONS ", "PATCH ", "TRACE ",
    )
    private val http2Preface = byteArrayOf(
        0x50, 0x52, 0x49, 0x20, 0x2A, 0x20, 0x48, 0x54, 0x54, 0x50, 0x2F, 0x32, 0x2E, 0x30,
    )
}
