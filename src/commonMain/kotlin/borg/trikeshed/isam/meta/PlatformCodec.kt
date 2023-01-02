package borg.trikeshed.isam.meta

import kotlin.experimental.and
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

    companion object {
        @JvmStatic
        val isNetworkEndian by lazy {
            val i = 0x01020304
            val b = i.toByte()
            b == 0x01.toByte()

        }

        @JvmStatic
        val isLittleEndian get() = !isNetworkEndian

        object currentPlatformCodec : PlatformCodec {
            override val readInt: (ByteArray) -> Int by lazy {
                if (isLittleEndian) {
                    {
                        (((it[3].toUByte()).toUInt() shl 24) or
                                ((it[2].toUByte()).toUInt() shl 16) or
                                ((it[1].toUByte()).toUInt() shl 8) or
                                (it[0].toUByte()).toUInt()).toInt()

                    }
                } else {
                    {
                        (((it[0].toUByte()).toUInt() shl 24) or
                                ((it[1].toUByte()).toUInt() shl 16) or
                                ((it[2].toUByte()).toUInt() shl 8) or
                                (it[3].toUByte()).toUInt()).toInt()
                    }
                }

            }

            override val readLong: (ByteArray) -> Long by lazy {

                if (isLittleEndian) {
                    {
                        (((it[7].toUByte()).toULong() shl 56) or
                                ((it[6].toUByte()).toULong() shl 48) or
                                ((it[5].toUByte()).toULong() shl 40) or
                                ((it[4].toUByte()).toULong() shl 32) or
                                ((((it[3].toUByte()).toUInt() shl 24) or
                                        ((it[2].toUByte()).toUInt() shl 16) or
                                        ((it[1].toUByte()).toUInt() shl 8) or
                                        (it[0].toUByte()).toUInt()).toULong())).toLong()
                    }
                } else {
                    {
                        (((it[0].toUByte()).toULong() shl 56) or
                                ((it[1].toUByte()).toULong() shl 48) or
                                ((it[2].toUByte()).toULong() shl 40) or
                                ((it[3].toUByte()).toULong() shl 32) or
                                ((((it[4].toUByte()).toUInt() shl 24) or
                                        ((it[5].toUByte()).toUInt() shl 16) or
                                        ((it[6].toUByte()).toUInt() shl 8) or
                                        (it[7].toUByte()).toUInt()).toULong())).toLong()

                    }
                }
            }

            override val readShort: (ByteArray) -> Short by lazy {
                if (isLittleEndian) {
                    { ((it[1].toInt() and 0xFF) shl 8).toShort() or (it[0].toInt() and 0xFF).toShort() }
                } else {
                    { (it[0].toInt() and 0xFF shl 8 or (it[1].toInt() and 0xFF)).toShort() }
                }
            }

            override val writeLong: (Long) -> ByteArray by lazy {
                if (isLittleEndian) {
                    {
                        byteArrayOf(
                            (it.toUByte()).toByte(),
                            ((it shr 8).toUByte()).toByte(),
                            ((it shr 16).toUByte()).toByte(),
                            ((it shr 24).toUByte()).toByte(),
                            ((it shr 32).toUByte()).toByte(),
                            ((it shr 40).toUByte()).toByte(),
                            ((it shr 48).toUByte()).toByte(),
                            ((it shr 56).toUByte()).toByte()
                        )
                    }
                } else {
                    {
                        byteArrayOf(
                            ((it shr 56).toUByte()).toByte(),
                            ((it shr 48).toUByte()).toByte(),
                            ((it shr 40).toUByte()).toByte(),
                            ((it shr 32).toUByte()).toByte(),
                            ((it shr 24).toUByte()).toByte(),
                            ((it shr 16).toUByte()).toByte(),
                            ((it shr 8).toUByte()).toByte(),
                            (it.toUByte()).toByte()
                        )
                    }
                }
            }

            override val writeInt: (Int) -> ByteArray by lazy {
                if (isLittleEndian) {
                    {
                        byteArrayOf(
                            (it.toUByte()).toByte(),
                            ((it shr 8).toUByte()).toByte(),
                            ((it shr 16).toUByte()).toByte(),
                            ((it shr 24).toUByte()).toByte()
                        )
                    }
                } else {
                    {
                        byteArrayOf(
                            ((it shr 24).toUByte()).toByte(),
                            ((it shr 16).toUByte()).toByte(),
                            ((it shr 8).toUByte()).toByte(),
                            (it.toUByte()).toByte()
                        )
                    }
                }
            }
            override val writeShort: (Short) -> ByteArray by lazy {

                if (isLittleEndian) {
                    {
                        byteArrayOf(
                            (it.toUByte()).toByte(),
                            ((it.toUInt() shr 8).toUByte()).toByte()
                        )
                    }
                } else {
                    {
                        byteArrayOf(
                            ((it.toUInt() shr 8).toUByte()).toByte(),
                            (it.toUByte()).toByte()
                        )
                    }
                }
            }
        }
    }
}