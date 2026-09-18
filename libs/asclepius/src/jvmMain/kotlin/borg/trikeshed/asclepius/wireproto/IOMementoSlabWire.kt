package borg.trikeshed.asclepius.wireproto

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/** Read one value from a slab at [offset]. [size] is the field span, or -1 for length-prefixed variable fields. */
typealias SlabReader = (slab: ByteArray, offset: Int, size: Int) -> Any?

/** Write [value] into a slab at [offset]. Returns bytes written. */
typealias SlabWriter = (value: Any?, slab: ByteArray, offset: Int) -> Int

/**
 * Stable lambda specification for IOMemento slab/ring wire operations.
 *
 * This is intentionally an enum rather than a pile of native adapters: Python,
 * GraalPy, and JVM pointcut delegates can maintain a manual 1:1 mapping from
 * enum name/ordinal to the same lambda contour. Fixed-width cases never allocate
 * per cell; variable-width cases use a 4-byte little-endian length prefix.
 *
 * The byte order is little-endian because this is a local analytical slab shape
 * (Arrow/Feather/SQLite affinity), not network wire order.
 */
enum class IOMementoSlabOp(
    val memento: IOMemento,
    val fixedSize: Int?,
    val reader: SlabReader,
    val writer: SlabWriter,
) {
    IoBoolean(IOMemento.IoBoolean, 1,
        reader = { slab, offset, _ -> slab[offset] != 0.toByte() },
        writer = { value, slab, offset -> slab[offset] = if (value as Boolean) 1 else 0; 1 },
    ),
    IoByte(IOMemento.IoByte, 1,
        reader = { slab, offset, _ -> slab[offset] },
        writer = { value, slab, offset -> slab[offset] = value as Byte; 1 },
    ),
    IoUByte(IOMemento.IoUByte, 1,
        reader = { slab, offset, _ -> slab[offset].toUByte() },
        writer = { value, slab, offset -> slab[offset] = (value as UByte).toByte(); 1 },
    ),
    IoShort(IOMemento.IoShort, 2,
        reader = { slab, offset, _ -> readShortLE(slab, offset) },
        writer = { value, slab, offset -> writeShortLE((value as Short).toInt(), slab, offset); 2 },
    ),
    IoUShort(IOMemento.IoUShort, 2,
        reader = { slab, offset, _ -> readShortLE(slab, offset).toUShort() },
        writer = { value, slab, offset -> writeShortLE((value as UShort).toInt(), slab, offset); 2 },
    ),
    IoInt(IOMemento.IoInt, 4,
        reader = { slab, offset, _ -> readIntLE(slab, offset) },
        writer = { value, slab, offset -> writeIntLE(value as Int, slab, offset); 4 },
    ),
    IoUInt(IOMemento.IoUInt, 4,
        reader = { slab, offset, _ -> readIntLE(slab, offset).toUInt() },
        writer = { value, slab, offset -> writeIntLE((value as UInt).toInt(), slab, offset); 4 },
    ),
    IoLong(IOMemento.IoLong, 8,
        reader = { slab, offset, _ -> readLongLE(slab, offset) },
        writer = { value, slab, offset -> writeLongLE(value as Long, slab, offset); 8 },
    ),
    IoULong(IOMemento.IoULong, 8,
        reader = { slab, offset, _ -> readLongLE(slab, offset).toULong() },
        writer = { value, slab, offset -> writeLongLE((value as ULong).toLong(), slab, offset); 8 },
    ),
    IoFloat(IOMemento.IoFloat, 4,
        reader = { slab, offset, _ -> Float.fromBits(readIntLE(slab, offset)) },
        writer = { value, slab, offset -> writeIntLE((value as Float).toBits(), slab, offset); 4 },
    ),
    IoDouble(IOMemento.IoDouble, 8,
        reader = { slab, offset, _ -> Double.fromBits(readLongLE(slab, offset)) },
        writer = { value, slab, offset -> writeLongLE((value as Double).toBits(), slab, offset); 8 },
    ),
    IoLocalDate(IOMemento.IoLocalDate, 8,
        reader = { slab, offset, _ -> kotlinx.datetime.LocalDate.fromEpochDays(readLongLE(slab, offset).toInt()) },
        writer = { value, slab, offset ->
            val epochDays = (value as kotlinx.datetime.LocalDate).toEpochDays().toLong()
            writeLongLE(epochDays, slab, offset)
            8
        },
    ),
    IoInstant(IOMemento.IoInstant, 12,
        reader = { slab, offset, _ ->
            val seconds = readLongLE(slab, offset)
            val nanos = readIntLE(slab, offset + 8)
            kotlin.time.Instant.fromEpochSeconds(seconds, nanos)
        },
        writer = { value, slab, offset ->
            val instant = value as kotlin.time.Instant
            writeLongLE(instant.epochSeconds, slab, offset)
            writeIntLE(instant.nanosecondsOfSecond, slab, offset + 8)
            12
        },
    ),
    IoString(IOMemento.IoString, null,
        reader = { slab, offset, size -> readLengthPrefixedBytes(slab, offset, size).decodeToString() },
        writer = { value, slab, offset -> writeLengthPrefixedBytes((value as String).encodeToByteArray(), slab, offset) },
    ),
    IoByteArray(IOMemento.IoByteArray, null,
        reader = { slab, offset, size -> readLengthPrefixedBytes(slab, offset, size) },
        writer = { value, slab, offset -> writeLengthPrefixedBytes(value as ByteArray, slab, offset) },
    ),
    IoNothing(IOMemento.IoNothing, 0,
        reader = { _, _, _ -> null },
        writer = { _, _, _ -> 0 },
    );

    fun frameSize(value: Any?): Int = fixedSize ?: when (this) {
        IoString -> 4 + (value as String).encodeToByteArray().size
        IoByteArray -> 4 + (value as ByteArray).size
        else -> 4
    }

    fun write(value: Any?, slab: ByteArray, offset: Int): Int = writer(value, slab, offset)
    fun read(slab: ByteArray, offset: Int, size: Int = fixedSize ?: -1): Any? = reader(slab, offset, size)

    companion object {
        val series: Series<IOMementoSlabOp> = entries.size j { i: Int -> entries[i] }

        fun of(memento: IOMemento): IOMementoSlabOp =
            entries.firstOrNull { it.memento == memento }
                ?: error("No slab op for IOMemento.$memento")
    }
}

val IOMemento.slabOp: IOMementoSlabOp get() = IOMementoSlabOp.of(this)

fun writeSlabValue(memento: IOMemento, value: Any?, slab: ByteArray, offset: Int): Int =
    memento.slabOp.write(value, slab, offset)

fun readSlabValue(memento: IOMemento, slab: ByteArray, offset: Int, size: Int = memento.networkSize ?: -1): Any? =
    memento.slabOp.read(slab, offset, size)

private fun readShortLE(slab: ByteArray, offset: Int): Short =
    (((slab[offset + 1].toInt() and 0xFF) shl 8) or
        (slab[offset].toInt() and 0xFF)).toShort()

private fun writeShortLE(value: Int, slab: ByteArray, offset: Int) {
    slab[offset] = (value and 0xFF).toByte()
    slab[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun readIntLE(slab: ByteArray, offset: Int): Int =
    ((slab[offset + 3].toInt() and 0xFF) shl 24) or
        ((slab[offset + 2].toInt() and 0xFF) shl 16) or
        ((slab[offset + 1].toInt() and 0xFF) shl 8) or
        (slab[offset].toInt() and 0xFF)

private fun writeIntLE(value: Int, slab: ByteArray, offset: Int) {
    slab[offset] = (value and 0xFF).toByte()
    slab[offset + 1] = ((value shr 8) and 0xFF).toByte()
    slab[offset + 2] = ((value shr 16) and 0xFF).toByte()
    slab[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

private fun readLongLE(slab: ByteArray, offset: Int): Long =
    ((slab[offset + 7].toLong() and 0xFFL) shl 56) or
        ((slab[offset + 6].toLong() and 0xFFL) shl 48) or
        ((slab[offset + 5].toLong() and 0xFFL) shl 40) or
        ((slab[offset + 4].toLong() and 0xFFL) shl 32) or
        ((slab[offset + 3].toLong() and 0xFFL) shl 24) or
        ((slab[offset + 2].toLong() and 0xFFL) shl 16) or
        ((slab[offset + 1].toLong() and 0xFFL) shl 8) or
        (slab[offset].toLong() and 0xFFL)

private fun writeLongLE(value: Long, slab: ByteArray, offset: Int) {
    slab[offset] = (value and 0xFFL).toByte()
    slab[offset + 1] = ((value shr 8) and 0xFFL).toByte()
    slab[offset + 2] = ((value shr 16) and 0xFFL).toByte()
    slab[offset + 3] = ((value shr 24) and 0xFFL).toByte()
    slab[offset + 4] = ((value shr 32) and 0xFFL).toByte()
    slab[offset + 5] = ((value shr 40) and 0xFFL).toByte()
    slab[offset + 6] = ((value shr 48) and 0xFFL).toByte()
    slab[offset + 7] = ((value shr 56) and 0xFFL).toByte()
}

private fun writeLengthPrefixedBytes(bytes: ByteArray, slab: ByteArray, offset: Int): Int {
    writeIntLE(bytes.size, slab, offset)
    bytes.copyInto(slab, destinationOffset = offset + 4)
    return 4 + bytes.size
}

private fun readLengthPrefixedBytes(slab: ByteArray, offset: Int, size: Int): ByteArray {
    val length = if (size >= 0) size - 4 else readIntLE(slab, offset)
    return slab.copyOfRange(offset + 4, offset + 4 + length)
}
