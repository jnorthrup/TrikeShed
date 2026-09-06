/*
 * Ported from Tessera — https://github.com/3x3xX3N0N/tessera (via jnorthrup/tessera @78bc042).
 * Copyright 2026 3x3xX3N0N. Licensed under the Apache License, Version 2.0; see tessera/NOTICE.md.
 *
 * Changes in TrikeShed: `java.nio.ByteBuffer` is a [ByteCursor] over a `ByteArray`; a short buffer
 * throws [ShortBufferException] where upstream threw `BufferUnderflowException`.
 */
package tessera.core

/** A read/write position over a `ByteArray`, the commonMain stand-in for the parts of `ByteBuffer` the codec uses. */
class ByteCursor(val array: ByteArray, var position: Int = 0, val limit: Int = array.size) {
    val remaining: Int get() = limit - position
    fun hasRemaining(): Boolean = position < limit
    private fun need(n: Int) { if (remaining < n) throw ShortBufferException("need $n byte(s), $remaining left") }
    fun get(): Int { need(1); return array[position++].toInt() and 0xFF }
    fun peek(): Int { need(1); return array[position].toInt() and 0xFF }
    fun getShort(): Int { need(2); val v = ((array[position].toInt() and 0xFF) shl 8) or (array[position + 1].toInt() and 0xFF); position += 2; return v }
    fun getInt(): Int { need(4); var v = 0; for (i in 0 until 4) v = (v shl 8) or (array[position + i].toInt() and 0xFF); position += 4; return v }
    fun getLong(): Long { need(8); var v = 0L; for (i in 0 until 8) v = (v shl 8) or (array[position + i].toLong() and 0xFF); position += 8; return v }
    fun put(b: Int): ByteCursor { need(1); array[position++] = b.toByte(); return this }
    fun putShort(v: Int): ByteCursor { need(2); array[position] = (v ushr 8).toByte(); array[position + 1] = v.toByte(); position += 2; return this }
    fun putInt(v: Int): ByteCursor { need(4); for (i in 0 until 4) array[position + i] = (v ushr (24 - 8 * i)).toByte(); position += 4; return this }
    fun putLong(v: Long): ByteCursor { need(8); for (i in 0 until 8) array[position + i] = (v ushr (56 - 8 * i)).toByte(); position += 8; return this }
}

class ShortBufferException(message: String) : RuntimeException(message)

/** QUIC-style variable-length integers (1/2/4/8 bytes, 2 prefix bits) — RFC 9000 §16, implemented from the document. */
object VarInt {
    fun write(buf: ByteCursor, v: Long) {
        require(v >= 0)
        when {
            v < 0x40 -> buf.put(v.toInt())
            v < 0x4000 -> buf.putShort((v or 0x4000).toInt())
            v < 0x4000_0000 -> buf.putInt((v or 0x8000_0000L).toInt())
            else -> buf.putLong(v or (0xC0L shl 56))
        }
    }
    fun read(buf: ByteCursor): Long {
        // Peeking the prefix byte must signal a short buffer the same way every other reader does. (upstream fuzz finding)
        val first = buf.peek()
        return when (first shr 6) {
            0 -> (buf.get().toLong() and 0x3F)
            1 -> (buf.getShort().toLong() and 0x3FFF)
            2 -> (buf.getInt().toLong() and 0x3FFF_FFFF)
            else -> buf.getLong() and 0x3FFF_FFFF_FFFF_FFFFL
        }
    }
    fun size(v: Long): Int = when { v < 0x40 -> 1; v < 0x4000 -> 2; v < 0x4000_0000 -> 4; else -> 8 }
}
