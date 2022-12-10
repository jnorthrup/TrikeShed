package borg.trikeshed.isam.meta

import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.jvm.JvmStatic


interface PlatformCodec {
    val readLong: (ByteArray) -> Long
    val readInt: (ByteArray) -> Int
    val readDouble: (ByteArray) -> Double
        get() = {
            Double.fromBits(readLong(it))
        }
    val readFloat: (ByteArray) -> Float
        get() = {
            Float.fromBits(readInt(it))
        }
    val readShort: (ByteArray) -> Short
    val writeLong: (Long) -> ByteArray
    val writeInt: (Int) -> ByteArray
    val writeShort: (Short) -> ByteArray
    val writeDouble: (Double) -> ByteArray get() = { writeLong(it.toRawBits()) }
    val writeFloat: (Float) -> ByteArray get() = { writeInt(it.toRawBits()) }

    companion object {

        @JvmStatic
        val isLittleEndian by lazy {
            val i = 0x01020304
            val b = i.toByte()
            b == 0x04.toByte()
        }

        object currentPlatformCodec : PlatformCodec {
            override val readInt: (ByteArray) -> Int = {
                (( if (isLittleEndian) {
                    ((it[3].toInt() and 0xFF) shl 24) or
                            ((it[2].toShort() and 0xFF).toInt() shl 16) or
                            ((it[1].toShort() and 0xFF).toInt() shl 8) or
                            (it[0].toShort() and 0xFF).toInt()
                } else {
                    ((it[0].toInt() and 0xFF) shl 24) or
                            ((it[1].toShort() and 0xFF).toInt() shl 16) or
                            ((it[2].toShort() and 0xFF).toInt() shl 8) or
                            (it[3].toShort() and 0xFF).toInt()
                }).toLong() and 0xFFFFFFFFL).toInt()
            }

            override val readLong: (ByteArray) -> Long = {
                if (isLittleEndian) (                ((it[7].toUByte()).toLong() shl 56) or
                        ((it[6].toUByte()).toLong() shl 48) or
                        ((it[5].toUByte()).toLong() shl 40) or
                        ((it[4].toUByte()).toLong() shl 32) or
                        ((it[3].toUByte()).toLong() shl 24) or
                        ((it[2].toUByte()).toLong() shl 16) or
                        ((it[1].toUByte()).toLong() shl 8) or
                        ( it[0].toUByte()).toLong())
                else ((it[0].toUByte()).toLong() shl 56) or
                        ((it[1].toUByte()).toLong() shl 48) or
                        ((it[2].toUByte()).toLong() shl 40) or
                        ((it[3].toUByte()).toLong() shl 32) or
                        ((it[4].toUByte()).toLong() shl 24) or
                        ((it[5].toUByte()).toLong() shl 16) or
                        ((it[6].toUByte()).toLong() shl 8) or
                        ( it[7].toUByte()).toLong()
            }

            override val readShort: (ByteArray) -> Short = {
                if (isLittleEndian) ((it[1].toInt() and 0xFF) shl 8).toShort() or (it[0].toInt() and 0xFF).toShort()
                else (it[0].toInt() and 0xFF shl 8 or (it[1].toInt() and 0xFF)).toShort()
            }

            override val writeLong: (Long) -> ByteArray = {
                if (isLittleEndian) {
                    byteArrayOf(
                        (it and 0xFF).toByte(),
                        ((it shr 8) and 0xFF).toByte(),
                        ((it shr 16) and 0xFF).toByte(),
                        ((it shr 24) and 0xFF).toByte(),
                        ((it shr 32) and 0xFF).toByte(),
                        ((it shr 40) and 0xFF).toByte(),
                        ((it shr 48) and 0xFF).toByte(),
                        ((it shr 56) and 0xFF).toByte()
                    )
                } else {
                    byteArrayOf(
                        ((it shr 56) and 0xFF).toByte(),
                        ((it shr 48) and 0xFF).toByte(),
                        ((it shr 40) and 0xFF).toByte(),
                        ((it shr 32) and 0xFF).toByte(),
                        ((it shr 24) and 0xFF).toByte(),
                        ((it shr 16) and 0xFF).toByte(),
                        ((it shr 8) and 0xFF).toByte(),
                         (it and 0xFF).toByte()
                    )
                }
            }

            override val writeInt: (Int) -> ByteArray = {
                if (isLittleEndian) {
                    byteArrayOf(
                        (it and 0xFF).toByte(),
                        ((it shr 8) and 0xFF).toByte(),
                        ((it shr 16) and 0xFF).toByte(),
                        ((it shr 24) and 0xFF).toByte()
                    )
                } else {
                    byteArrayOf(
                        ((it shr 24) and 0xFF).toByte(),
                        ((it shr 16) and 0xFF).toByte(),
                        ((it shr 8) and 0xFF).toByte(),
                        (it and 0xFF).toByte()
                    )
                }
            }

            override val writeShort: (Short) -> ByteArray = {
                if (isLittleEndian) {
                    byteArrayOf(
                        (it and 0xFF).toByte(),
                        ((it.toInt() shr 8) and 0xFF).toByte()
                    )
                } else {
                    byteArrayOf(
                        ((it.toInt() shr 8) and 0xFF).toByte(),
                        (it and 0xFF).toByte()
                    )
                }
            }
        }
    }
}