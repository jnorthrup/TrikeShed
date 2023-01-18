@file:OptIn(ExperimentalUnsignedTypes::class)

package borg.trikeshed.lib

/** this is a set of helper extension functions to produce network-endian versions of getIntAt, setIntAt, getLongAt,
 *  setLongAt, etc. for ByteArray
 *
 * the kotlin native package marshalling is treated as if it was in Least-Significant-Byte first (little-endian) byte
 * order.  we have to conform to what java ByteBuffer reads/writes in network order (big-endian)
 *
 * the functions in this file use the symbol prefix "networkOrder" to indicate that they are in network order
 */
fun ByteArray.networkOrderGetIntAt(i: Int): Int {
    return this[i].toInt() shl 24 or
            (this[i + 1].toInt() and 0xff shl 16) or
            (this[i + 2].toInt() and 0xff shl 8) or
            (this[i + 3].toInt() and 0xff)
}

fun ByteArray.networkOrderSetIntAt(i: Int, value: Int) {
    this[i] = (value shr 24).toByte()
    this[i + 1] = (value shr 16).toByte()
    this[i + 2] = (value shr 8).toByte()
    this[i + 3] = value.toByte()
}

fun ByteArray.networkOrderGetLongAt(i: Int): Long {
    return this[i].toLong() shl 56 or
            (this[i + 1].toLong() and 0xff shl 48) or
            (this[i + 2].toLong() and 0xff shl 40) or
            (this[i + 3].toLong() and 0xff shl 32) or
            (this[i + 4].toLong() and 0xff shl 24) or
            (this[i + 5].toLong() and 0xff shl 16) or
            (this[i + 6].toLong() and 0xff shl 8) or
            (this[i + 7].toLong() and 0xff)
}

fun ByteArray.networkOrderSetLongAt(i: Int, value: Long) {
    this[i] = (value shr 56).toByte()
    this[i + 1] = (value shr 48).toByte()
    this[i + 2] = (value shr 40).toByte()
    this[i + 3] = (value shr 32).toByte()
    this[i + 4] = (value shr 24).toByte()
    this[i + 5] = (value shr 16).toByte()
    this[i + 6] = (value shr 8).toByte()
    this[i + 7] = value.toByte()
}

fun ByteArray.networkOrderGetShortAt(i: Int): Short =
    (this[i].toInt() shl 8 or (this[i + 1].toInt() and 0xff)).toShort()

fun ByteArray.networkOrderSetShortAt(i: Int, value: Short) {
    this[i] = (value.toInt() shr 8).toByte()
    this[i + 1] = value.toByte()
}

fun ByteArray.networkOrderGetFloatAt(i: Int): Float = Float.fromBits(this.networkOrderGetIntAt(i))

fun ByteArray.networkOrderSetFloatAt(i: Int, value: Float) {
    this.networkOrderSetIntAt(i, value.toBits())
}

fun ByteArray.networkOrderGetDoubleAt(i: Int): Double = Double.fromBits(this.networkOrderGetLongAt(i))

fun ByteArray.networkOrderSetDoubleAt(i: Int, value: Double) {
    this.networkOrderSetLongAt(i, value.toBits())
}

fun ByteArray.networkOrderGetCharAt(i: Int): Char = this.networkOrderGetShortAt(i).toInt().toChar()

fun ByteArray.networkOrderSetCharAt(i: Int, value: Char) {
    this.networkOrderSetShortAt(i, value.code.toShort())
}

fun ByteArray.networkOrderGetBooleanAt(i: Int): Boolean = this[i] != 0.toByte()

fun ByteArray.networkOrderSetBooleanAt(i: Int, value: Boolean) {
    this[i] = if (value) 1.toByte() else 0.toByte()
}

fun ByteArray.networkOrderGetByteAt(i: Int): Byte = this[i]

fun ByteArray.networkOrderSetByteAt(i: Int, value: Byte) {
    this[i] = value
}

fun ByteArray.networkOrderGetUByteAt(i: Int): UByte = this[i].toUByte()

fun ByteArray.networkOrderSetUByteAt(i: Int, value: UByte) {
    this[i] = value.toByte()
}

fun ByteArray.networkOrderGetUShortAt(i: Int): UShort = this.networkOrderGetShortAt(i).toUShort()

fun ByteArray.networkOrderSetUShortAt(i: Int, value: UShort) {
    this.networkOrderSetShortAt(i, value.toShort())
}

fun ByteArray.networkOrderGetUIntAt(i: Int): UInt = this.networkOrderGetIntAt(i).toUInt()

fun ByteArray.networkOrderSetUIntAt(i: Int, value: UInt) {
    this.networkOrderSetIntAt(i, value.toInt())
}

fun ByteArray.networkOrderGetULongAt(i: Int): ULong = this.networkOrderGetLongAt(i).toULong()

fun ByteArray.networkOrderSetULongAt(i: Int, value: ULong) {
    this.networkOrderSetLongAt(i, value.toLong())
}

fun ByteArray.networkOrderGetUByteArrayAt(i: Int, length: Int): UByteArray =
    UByteArray(length) { this.networkOrderGetUByteAt(i + it) }

fun ByteArray.networkOrderSetUByteArrayAt(i: Int, value: UByteArray) {
    for (j in value.indices) this.networkOrderSetUByteAt(i + j, value[j])
}

fun ByteArray.networkOrderGetUShortArrayAt(i: Int, length: Int): UShortArray =
    UShortArray(length) { this.networkOrderGetUShortAt(i + it * 2) }

fun ByteArray.networkOrderSetUShortArrayAt(i: Int, value: UShortArray) {
    for (j in value.indices) this.networkOrderSetUShortAt(i + j * 2, value[j])
}

fun ByteArray.networkOrderGetUIntArrayAt(i: Int, length: Int): UIntArray =
    UIntArray(length) { this.networkOrderGetUIntAt(i + it * 4) }

fun ByteArray.networkOrderSetUIntArrayAt(i: Int, value: UIntArray) {
    for (j in value.indices) this.networkOrderSetUIntAt(i + j * 4, value[j])
}

fun ByteArray.networkOrderGetULongArrayAt(i: Int, length: Int): ULongArray =
    ULongArray(length) { this.networkOrderGetULongAt(i + it * 8) }

fun ByteArray.networkOrderSetULongArrayAt(i: Int, value: ULongArray) {
    for (j in value.indices) this.networkOrderSetULongAt(i + j * 8, value[j])
}

fun ByteArray.networkOrderGetByteArrayAt(i: Int, length: Int): ByteArray =
    ByteArray(length) { this.networkOrderGetByteAt(i + it) }

fun ByteArray.networkOrderSetByteArrayAt(i: Int, value: ByteArray) {
    for (j in value.indices) this.networkOrderSetByteAt(i + j, value[j])
}

fun ByteArray.networkOrderGetShortArrayAt(i: Int, length: Int): ShortArray =
    ShortArray(length) { this.networkOrderGetShortAt(i + it * 2) }

fun ByteArray.networkOrderSetShortArrayAt(i: Int, value: ShortArray) {
    for (j in value.indices) this.networkOrderSetShortAt(i + j * 2, value[j])
}

fun ByteArray.networkOrderGetIntArrayAt(i: Int, length: Int): IntArray =
    IntArray(length) { this.networkOrderGetIntAt(i + it * 4) }

fun ByteArray.networkOrderSetIntArrayAt(i: Int, value: IntArray) {
    for (j in value.indices) this.networkOrderSetIntAt(i + j * 4, value[j])
}

fun ByteArray.networkOrderGetLongArrayAt(i: Int, length: Int): LongArray =
    LongArray(length) { this.networkOrderGetLongAt(i + it * 8) }

fun ByteArray.networkOrderSetLongArrayAt(i: Int, value: LongArray) {
    for (j in value.indices) this.networkOrderSetLongAt(i + j * 8, value[j])
}

fun ByteArray.networkOrderGetFloatArrayAt(i: Int, length: Int): FloatArray =
    FloatArray(length) { this.networkOrderGetFloatAt(i + it * 4) }

fun ByteArray.networkOrderSetFloatArrayAt(i: Int, value: FloatArray) {
    for (j in value.indices) this.networkOrderSetFloatAt(i + j * 4, value[j])
}

fun ByteArray.networkOrderGetDoubleArrayAt(i: Int, length: Int): DoubleArray =
    DoubleArray(length) { this.networkOrderGetDoubleAt(i + it * 8) }

fun ByteArray.networkOrderSetDoubleArrayAt(i: Int, value: DoubleArray) {
    for (j in value.indices) this.networkOrderSetDoubleAt(i + j * 8, value[j])
}

fun ByteArray.networkOrderGetBooleanArrayAt(i: Int, length: Int): BooleanArray =
    BooleanArray(length) { this.networkOrderGetBooleanAt(i + it) }

fun ByteArray.networkOrderSetBooleanArrayAt(i: Int, value: BooleanArray) {
    for (j in value.indices) this.networkOrderSetBooleanAt(i + j, value[j])
}