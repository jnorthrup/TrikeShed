package borg.trikeshed.isam.meta

import kotlin.experimental.and


interface PlatformCodec {
    val readLong: (ByteArray) -> Long
    val readInt: (ByteArray) -> Int
    val readDouble: (ByteArray) -> Double
    val readFloat: (ByteArray) -> Float
    val readShort: (ByteArray) -> Short
    val writeLong: (Long) -> ByteArray
    val writeInt: (Int) -> ByteArray
    val writeShort: (Short) -> ByteArray
    val writeDouble: (Double) -> ByteArray
    val writeFloat: (Float) -> ByteArray

    companion object {
        val currentPlatformCodec: PlatformCodec by lazy {
//            val matchThis=_a[0, 0, 0, 0, 0, 0, 0, 1]
            if (LittleEndianCodec.writeLong(1L)[7].toInt() == 1) LittleEndianCodec else BigEndianCodec

        }
    }
}


object LittleEndianCodec : PlatformCodec {
    /* convert bigendian byte[] to Int*/
    override val readInt: (ByteArray) -> Int = {
        it[0].toInt() and 0xFF shl 24 or
                (it[1].toInt() and 0xFF shl 16) or
                (it[2].toInt() and 0xFF shl 8) or
                (it[3].toInt() and 0xFF shl 0)
    }
    override val readFloat: (ByteArray) -> Float = { Float.fromBits(readInt(it)) }
    override val readShort: (ByteArray) -> Short = {
        ((it[0].toShort() and 0xFF).toInt() shl 8 or
                ((it[1].toShort() and 0xFF).toInt() shl 0)).toShort()
    }
    override val readDouble: (ByteArray) -> Double = { Double.fromBits(readLong(it)) }
    override val readLong: (ByteArray) -> Long = {
        it[0].toLong() and 0xFF shl 56 or
                it[1].toLong() and 0xFF shl 48 or
                it[2].toLong() and 0xFF shl 40 or
                it[3].toLong() and 0xFF shl 32 or
                it[4].toLong() and 0xFF shl 24 or
                it[5].toLong() and 0xFF shl 16 or
                it[6].toLong() and 0xFF shl 8 or
                it[7].toLong() and 0xFF shl 0
    }   // convert bigendian byte[] to Long

    override val writeLong: (Long) -> ByteArray = { v ->
        val bytes = ByteArray(8)
        bytes[0] = (v shr 56).toByte()
        bytes[1] = (v shr 48).toByte()
        bytes[2] = (v shr 40).toByte()
        bytes[3] = (v shr 32).toByte()
        bytes[4] = (v shr 24).toByte()
        bytes[5] = (v shr 16).toByte()
        bytes[6] = (v shr 8).toByte()
        bytes[7] = (v shr 0).toByte()
        bytes

    }
    override val writeInt: (Int) -> ByteArray = { v ->
        val bytes = ByteArray(4)
        bytes[0] = (v shr 24).toByte()
        bytes[1] = (v shr 16).toByte()
        bytes[2] = (v shr 8).toByte()
        bytes[3] = (v shr 0).toByte()
        bytes
    }
    override val writeShort: (Short) -> ByteArray = { v ->
        val bytes = ByteArray(2)
        bytes[0] = (v.toInt() shr 8).toByte()
        bytes[1] = (v.toInt() shr 0).toByte()
        bytes
    }
    override val writeFloat: (Float) -> ByteArray = { v -> writeInt(v.toRawBits()) }
    override val writeDouble: (Double) -> ByteArray = { v -> writeLong(v.toRawBits()) }
}

object BigEndianCodec : PlatformCodec {
    override val readInt: (ByteArray) -> Int = {
        it[3].toInt() and 0xFF shl 24 or
                (it[2].toInt() and 0xFF shl 16) or
                (it[1].toInt() and 0xFF shl 8) or
                (it[0].toInt() and 0xFF shl 0)
    }
    override val readFloat: (ByteArray) -> Float = { Float.fromBits(readInt(it)) }
    override val readShort: (ByteArray) -> Short = {
        ((it[1].toShort() and 0xFF).toInt() shl 8 or
                ((it[0].toShort() and 0xFF).toInt() shl 0)).toShort()
    }
    override val readDouble: (ByteArray) -> Double = { Double.fromBits(readLong(it)) }
    override val readLong: (ByteArray) -> Long = {
        it[7].toLong() and 0xFF shl 56 or
                it[6].toLong() and 0xFF shl 48 or
                it[5].toLong() and 0xFF shl 40 or
                it[4].toLong() and 0xFF shl 32 or
                it[3].toLong() and 0xFF shl 24 or
                it[2].toLong() and 0xFF shl 16 or
                it[1].toLong() and 0xFF shl 8 or
                it[0].toLong() and 0xFF shl 0
    }   // convert bigendian byte[] to Long

    override val writeLong: (Long) -> ByteArray = { v ->
        val bytes = ByteArray(8)
        bytes[7] = (v shr 56).toByte()
        bytes[6] = (v shr 48).toByte()
        bytes[5] = (v shr 40).toByte()
        bytes[4] = (v shr 32).toByte()
        bytes[3] = (v shr 24).toByte()
        bytes[2] = (v shr 16).toByte()
        bytes[1] = (v shr 8).toByte()
        bytes[0] = (v shr 0).toByte()
        bytes
    }
    override val writeInt: (Int) -> ByteArray = { v ->
        val bytes = ByteArray(4)
        bytes[3] = (v shr 24).toByte()
        bytes[2] = (v shr 16).toByte()
        bytes[1] = (v shr 8).toByte()
        bytes[0] = (v shr 0).toByte()
        bytes
    }
    override val writeShort: (Short) -> ByteArray = { v ->
        val bytes = ByteArray(2)
        bytes[1] = (v.toInt() shr 8).toByte()
        bytes[0] = (v.toInt() shr 0).toByte()
        bytes
    }
    override val writeFloat: (Float) -> ByteArray = { v -> writeInt(v.toRawBits()) }
    override val writeDouble: (Double) -> ByteArray = { v -> writeLong(v.toRawBits()) }
}

fun main() {
    val _a = PlatformCodec.currentPlatformCodec

}