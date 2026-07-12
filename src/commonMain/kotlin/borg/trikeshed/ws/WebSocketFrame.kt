package borg.trikeshed.ws

import borg.trikeshed.lib.*

/**
 * RFC 6455 WebSocket frame codec — commonMain.
 *
 * Ported from rxf/core/WebSocketFrame.java (RelaxFactory).
 * Operates on [ByteSeries] for zero-copy buffer manipulation.
 *
 * ## Frame layout (RFC 6455 §5.2)
 *
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 * |I|S|S|S|  (4)  |A|     (7)     |          (16/64)              |
 * |N|V|V|V|       |S|             |  (if len==126 or 127)         |
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * ```
 */
object WebSocketFrame {

    enum class OpCode(val code: Int) {
        CONTINUATION(0x0),
        TEXT(0x1),
        BINARY(0x2),
        CLOSE(0x8),
        PING(0x9),
        PONG(0xA);

        companion object {
            private val byCode = entries.associateBy { it.code }
            fun fromCode(code: Int): OpCode = byCode[code] ?: error("Unknown opcode: $code")
        }
    }

    /**
     * Apply XOR mask to byte buffers in-place.
     * Ported from rxf/core/WebSocketFrame.java:applyMask.
     */
    fun applyMask(mask: ByteArray, vararg buffers: ByteArray) {
        var c = 0
        for (buf in buffers) {
            for (i in buf.indices) {
                buf[i] = (buf[i].toInt() xor (mask[c % 4].toInt() and 0xFF)).toByte()
                c++
            }
        }
    }

    /**
     * Parse a WebSocket frame header from a [ByteSeries].
     *
     * Returns true if a complete header was read, false if more data is needed.
     * After successful parse, [pos] points to the first byte of payload data.
     *
     * Ported from rxf/core/WebSocketFrame.java:apply(ByteBuffer).
     */
    fun parseFrame(
        buf: ByteSeries,
        result: FrameHeader = FrameHeader(),
    ): Boolean {
        if (buf.rem < 2) return false

        val mark = buf.pos
        try {
            val b0 = buf.get.toInt() and 0xFF
            result.fin = (b0 and 0x80) != 0
            result.opcode = OpCode.fromCode(b0 and 0x0F)

            val b1 = buf.get.toInt() and 0xFF
            result.masked = (b1 and 0x80) != 0
            val length7 = b1 and 0x7F

            result.payloadLength = when {
                length7 < 126 -> length7.toLong()
                length7 == 126 -> {
                    if (buf.rem < 2) { buf.pos = mark; return false }
                    ((buf.get.toInt() and 0xFF) shl 8 or (buf.get.toInt() and 0xFF)).toLong()
                }
                else -> { // 127
                    if (buf.rem < 8) { buf.pos = mark; return false }
                    var len = 0L
                    repeat(8) { len = (len shl 8) or (buf.get.toInt() and 0xFF).toLong() }
                    len
                }
            }

            if (result.masked) {
                if (buf.rem < 4) { buf.pos = mark; return false }
                result.maskingKey = ByteArray(4) { buf.get }
            }

            result.headerBytes = buf.pos - mark
            if (buf.rem < result.payloadLength) { buf.pos = mark; return false }
            return true
        } catch (_: IndexOutOfBoundsException) {
            buf.pos = mark
            return false
        }
    }

    /**
     * Read payload data from [buf] and optionally unmask it.
     *
     * @return payload bytes, or null if insufficient data remains.
     */
    fun readPayload(buf: ByteSeries, header: FrameHeader): ByteArray? {
        if (buf.rem < header.payloadLength) return null
        val payload = ByteArray(header.payloadLength.toInt()) { buf.get }
        if (header.masked && header.maskingKey != null) {
            applyMask(header.maskingKey!!, payload)
        }
        return payload
    }

    /**
     * Build a WebSocket frame header + payload into a [ByteArray] ready for writing.
     *
     * Ported from rxf/core/WebSocketFrame.java:as(ByteBuffer...).
     */
    fun buildFrame(
        opcode: OpCode,
        fin: Boolean = true,
        masked: Boolean = false,
        maskingKey: ByteArray? = null,
        payload: ByteArray = byteArrayOf(),
    ): ByteArray {
        val len = payload.size.toLong()
        val headerSize = 2 +
            when { len < 126 -> 0; len <= 0xFFFF -> 2; else -> 8 } +
            if (masked) 4 else 0

        val out = ByteArray(headerSize + payload.size)
        var pos = 0

        // byte 0: FIN + opcode
        out[pos++] = ((if (fin) 0x80 else 0x00) or (opcode.code and 0x0F)).toByte()

        // byte 1: MASK + length
        val maskBit = if (masked) 0x80 else 0x00
        when {
            len < 126 -> out[pos++] = (maskBit or len.toInt()).toByte()
            len <= 0xFFFF -> {
                out[pos++] = (maskBit or 126).toByte()
                out[pos++] = ((len shr 8) and 0xFF).toByte()
                out[pos++] = (len and 0xFF).toByte()
            }
            else -> {
                out[pos++] = (maskBit or 127).toByte()
                for (i in 7 downTo 0) {
                    out[pos++] = ((len shr (i * 8)) and 0xFF).toByte()
                }
            }
        }

        // masking key
        if (masked && maskingKey != null) {
            for (i in 0..3) out[pos++] = maskingKey[i]
        }

        // payload
        payload.copyInto(out, pos)
        if (masked && maskingKey != null) {
            for (i in pos until out.size) {
                out[i] = (payload[i - pos].toInt() xor (maskingKey[(i - pos) % 4].toInt() and 0xFF)).toByte()
            }
        }
        return out
    }
}

/**
 * Parsed frame header state.
 */
class FrameHeader {
    var fin: Boolean = false
    var opcode: WebSocketFrame.OpCode = WebSocketFrame.OpCode.TEXT
    var masked: Boolean = false
    var payloadLength: Long = 0
    var maskingKey: ByteArray? = null
    var headerBytes: Int = 0
}
