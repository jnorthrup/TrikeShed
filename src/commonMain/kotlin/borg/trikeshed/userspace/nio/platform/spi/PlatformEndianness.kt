package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.ByteOrder

internal expect fun platformNativeByteOrder(): ByteOrder

/** Little-endian load of the 8 bytes from [index], at any alignment. */
expect fun ByteArray.littleEndianGetLongAt(index: Int): Long

/** Little-endian store of [value] into the 8 bytes from [index], at any alignment. */
expect fun ByteArray.littleEndianSetLongAt(index: Int, value: Long)

/** [littleEndianGetLongAt] one byte at a time, composed in 32-bit halves: Kotlin/JS emulates Long arithmetic. */
fun ByteArray.littleEndianGetLongAtCommonImpl(index: Int): Long {
    var low = 0
    var high = 0
    for (k in 3 downTo 0) {
        low = (low shl 8) or (this[index + k].toInt() and 0xFF)
        high = (high shl 8) or (this[index + 4 + k].toInt() and 0xFF)
    }
    return (low.toLong() and 0xFFFFFFFFL) or (high.toLong() shl 32)
}

/** [littleEndianSetLongAt] one byte at a time, from 32-bit halves. */
fun ByteArray.littleEndianSetLongAtCommonImpl(index: Int, value: Long) {
    val low = value.toInt()
    val high = (value ushr 32).toInt()
    for (k in 0..3) {
        this[index + k] = (low ushr 8 * k).toByte()
        this[index + 4 + k] = (high ushr 8 * k).toByte()
    }
}
