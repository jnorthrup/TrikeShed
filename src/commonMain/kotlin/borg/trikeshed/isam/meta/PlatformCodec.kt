package borg.trikeshed.isam.meta

import kotlin.experimental.or
import kotlin.jvm.JvmStatic


interface PlatformCodec {
    val readLong: (ByteArray) -> Long
    val readInt: (ByteArray) -> Int

    val readShort: (ByteArray) -> Short
    val writeLong: (Long) -> ByteArray
    val writeInt: (Int) -> ByteArray
    val writeShort: (Short) -> ByteArray
    val writeDouble: (Double) -> ByteArray get() = { writeLong(it.toBits()) }
    val writeFloat: (Float) -> ByteArray get() = { writeInt(it.toBits()) }
    val readDouble: (ByteArray) -> Double get() = { Double.fromBits(readLong(it)) }
    val readFloat: (ByteArray) -> Float get() = { Float.fromBits(readInt(it)) }
    val readUShort: (ByteArray) -> UShort
    val readUInt: (ByteArray) -> UInt
    val readULong: (ByteArray) -> ULong
    val writeUShort: (UShort) -> ByteArray
    val writeUInt: (UInt) -> ByteArray
    val writeULong: (ULong) -> ByteArray

    companion object {
        // Use Long constant - works on both JVM and Native
        private const val TEST_INT = 0x01020304
        val isLittleEndian: Boolean = (TEST_INT.toByte().toInt() and 0xFF) == 0x04
        val isNetworkEndian: Boolean = !isLittleEndian

        val currentPlatformCodec: PlatformCodec by lazy {
            CommonPlatformCodec(isLittleEndian)
        }

        // Top-level convenience references
        val readShort: (ByteArray) -> Short get() = currentPlatformCodec.readShort
        val readInt: (ByteArray) -> Int get() = currentPlatformCodec.readInt
        val readLong: (ByteArray) -> Long get() = currentPlatformCodec.readLong
        val readUShort: (ByteArray) -> UShort get() = currentPlatformCodec.readUShort
        val readUInt: (ByteArray) -> UInt get() = currentPlatformCodec.readUInt
        val readULong: (ByteArray) -> ULong get() = currentPlatformCodec.readULong
        val readFloat: (ByteArray) -> Float get() = currentPlatformCodec.readFloat
        val readDouble: (ByteArray) -> Double get() = currentPlatformCodec.readDouble
        val writeShort: (Short) -> ByteArray get() = currentPlatformCodec.writeShort
        val writeInt: (Int) -> ByteArray get() = currentPlatformCodec.writeInt
        val writeLong: (Long) -> ByteArray get() = currentPlatformCodec.writeLong
        val writeUShort: (UShort) -> ByteArray get() = currentPlatformCodec.writeUShort
        val writeUInt: (UInt) -> ByteArray get() = currentPlatformCodec.writeUInt
        val writeULong: (ULong) -> ByteArray get() = currentPlatformCodec.writeULong
        val writeFloat: (Float) -> ByteArray get() = currentPlatformCodec.writeFloat
        val writeDouble: (Double) -> ByteArray get() = currentPlatformCodec.writeDouble
    }
}

/** Common implementation that works on both JVM and Native */
class CommonPlatformCodec(private val littleEndian: Boolean) : PlatformCodec {
    override val readShort: (ByteArray) -> Short by lazy {
        val le = littleEndian
        { bytes: ByteArray ->
            if (le) {
                ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)
            } else {
                ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            }
        }.let { fn -> { fn(it).toShort() } }
    }

    override val readInt: (ByteArray) -> Int by lazy {
        val le = littleEndian
        { bytes: ByteArray ->
            if (le) {
                ((bytes[3].toInt() and 0xFF) shl 24) or
                        ((bytes[2].toInt() and 0xFF) shl 16) or
                        ((bytes[1].toInt() and 0xFF) shl 8) or
                        (bytes[0].toInt() and 0xFF)
            } else {
                ((bytes[0].toInt() and 0xFF) shl 24) or
                        ((bytes[1].toInt() and 0xFF) shl 16) or
                        ((bytes[2].toInt() and 0xFF) shl 8) or
                        (bytes[3].toInt() and 0xFF)
            }
        }
    }

    override val readLong: (ByteArray) -> Long by lazy {
        val le = littleEndian
        { bytes: ByteArray ->
            if (le) {
                ((bytes[7].toLong() and 0xFF) shl 56) or
                        ((bytes[6].toLong() and 0xFF) shl 48) or
                        ((bytes[5].toLong() and 0xFF) shl 40) or
                        ((bytes[4].toLong() and 0xFF) shl 32) or
                        ((bytes[3].toLong() and 0xFF) shl 24) or
                        ((bytes[2].toLong() and 0xFF) shl 16) or
                        ((bytes[1].toLong() and 0xFF) shl 8) or
                        (bytes[0].toLong() and 0xFF)
            } else {
                ((bytes[0].toLong() and 0xFF) shl 56) or
                        ((bytes[1].toLong() and 0xFF) shl 48) or
                        ((bytes[2].toLong() and 0xFF) shl 40) or
                        ((bytes[3].toLong() and 0xFF) shl 32) or
                        ((bytes[4].toLong() and 0xFF) shl 24) or
                        ((bytes[5].toLong() and 0xFF) shl 16) or
                        ((bytes[6].toLong() and 0xFF) shl 8) or
                        (bytes[7].toLong() and 0xFF)
            }
        }
    }

    override val writeShort: (Short) -> ByteArray by lazy {
        val le = littleEndian
        { value: Short ->
            val bytes = ByteArray(2)
            if (le) {
                bytes[0] = (value.toInt() and 0xFF).toByte()
                bytes[1] = ((value.toInt() shr 8) and 0xFF).toByte()
            } else {
                bytes[0] = ((value.toInt() shr 8) and 0xFF).toByte()
                bytes[1] = (value.toInt() and 0xFF).toByte()
            }
            bytes
        }
    }

    override val writeInt: (Int) -> ByteArray by lazy {
        val le = littleEndian
        { value: Int ->
            val bytes = ByteArray(4)
            if (le) {
                bytes[0] = (value and 0xFF).toByte()
                bytes[1] = ((value shr 8) and 0xFF).toByte()
                bytes[2] = ((value shr 16) and 0xFF).toByte()
                bytes[3] = ((value shr 24) and 0xFF).toByte()
            } else {
                bytes[0] = ((value shr 24) and 0xFF).toByte()
                bytes[1] = ((value shr 16) and 0xFF).toByte()
                bytes[2] = ((value shr 8) and 0xFF).toByte()
                bytes[3] = (value and 0xFF).toByte()
            }
            bytes
        }
    }

    override val writeLong: (Long) -> ByteArray by lazy {
        val le = littleEndian
        { value: Long ->
            val bytes = ByteArray(8)
            if (le) {
                bytes[0] = (value and 0xFF).toByte()
                bytes[1] = ((value shr 8) and 0xFF).toByte()
                bytes[2] = ((value shr 16) and 0xFF).toByte()
                bytes[3] = ((value shr 24) and 0xFF).toByte()
                bytes[4] = ((value shr 32) and 0xFF).toByte()
                bytes[5] = ((value shr 40) and 0xFF).toByte()
                bytes[6] = ((value shr 48) and 0xFF).toByte()
                bytes[7] = ((value shr 56) and 0xFF).toByte()
            } else {
                bytes[0] = ((value shr 56) and 0xFF).toByte()
                bytes[1] = ((value shr 48) and 0xFF).toByte()
                bytes[2] = ((value shr 40) and 0xFF).toByte()
                bytes[3] = ((value shr 32) and 0xFF).toByte()
                bytes[4] = ((value shr 24) and 0xFF).toByte()
                bytes[5] = ((value shr 16) and 0xFF).toByte()
                bytes[6] = ((value shr 8) and 0xFF).toByte()
                bytes[7] = (value and 0xFF).toByte()
            }
            bytes
        }
    }

    override val readUShort: (ByteArray) -> UShort get() = { readShort(it).toUShort() }
    override val readUInt: (ByteArray) -> UInt get() = { readInt(it).toUInt() }
    override val readULong: (ByteArray) -> ULong get() = { readLong(it).toULong() }
    override val writeUShort: (UShort) -> ByteArray get() = { writeShort(it.toShort()) }
    override val writeUInt: (UInt) -> ByteArray get() = { writeInt(it.toInt()) }
    override val writeULong: (ULong) -> ByteArray get() = { writeLong(it.toLong()) }
}
