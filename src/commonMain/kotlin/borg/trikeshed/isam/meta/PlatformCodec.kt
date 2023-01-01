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
                        ((((it[3].toInt() and 0xFF) shl 24) or
                                ((it[2].toShort() and 0xFF).toInt() shl 16) or
                                ((it[1].toShort() and 0xFF).toInt() shl 8) or
                                (it[0].toShort() and 0xFF).toInt()).toULong() and 0xFFFFFFFFUL).toInt()

                    }
                } else {
                    {
                        ((((it[0].toInt() and 0xFF) shl 24) or
                                ((it[1].toShort() and 0xFF).toInt() shl 16) or
                                ((it[2].toShort() and 0xFF).toInt() shl 8) or
                                (it[3].toShort() and 0xFF).toInt()
                                ).toULong() and 0xFFFFFFFFUL).toInt()
                    }
                }

            }

            override val readLong: (ByteArray) -> Long by lazy {

                if (isLittleEndian) {
                    {
                        (((it[7].toInt() and 0xFF).toLong() shl 56) or
                                ((it[6].toInt() and 0xFF).toLong() shl 48) or
                                ((it[5].toInt() and 0xFF).toLong() shl 40) or
                                ((it[4].toInt() and 0xFF).toLong() shl 32) or
                                ((it[3].toInt() and 0xFF).toLong() shl 24) or
                                ((it[2].toInt() and 0xFF).toLong() shl 16) or
                                ((it[1].toInt() and 0xFF).toLong() shl 8) or
                                (it[0].toInt() and 0xFF).toLong())
                    }
                } else {
                    {
                        ((it[0].toInt() and 0xFF).toLong() shl 56) or
                                ((it[1].toInt() and 0xFF).toLong() shl 48) or
                                ((it[2].toInt() and 0xFF).toLong() shl 40) or
                                ((it[3].toInt() and 0xFF).toLong() shl 32) or
                                ((it[4].toInt() and 0xFF).toLong() shl 24) or
                                ((it[5].toInt() and 0xFF).toLong() shl 16) or
                                ((it[6].toInt() and 0xFF).toLong() shl 8) or
                                (it[7].toInt() and 0xFF).toLong()

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
                            (it and 0xFF).toByte(),
                            ((it shr 8) and 0xFF).toByte(),
                            ((it shr 16) and 0xFF).toByte(),
                            ((it shr 24) and 0xFF).toByte(),
                            ((it shr 32) and 0xFF).toByte(),
                            ((it shr 40) and 0xFF).toByte(),
                            ((it shr 48) and 0xFF).toByte(),
                            ((it shr 56) and 0xFF).toByte()
                        )
                    }
                } else {
                    {
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
            }

            override val writeInt: (Int) -> ByteArray by lazy {
                if (isLittleEndian) {
                    {
                        byteArrayOf(
                            (it and 0xFF).toByte(),
                            ((it shr 8) and 0xFF).toByte(),
                            ((it shr 16) and 0xFF).toByte(),
                            ((it shr 24) and 0xFF).toByte()
                        )
                    }
                } else {
                    {
                        byteArrayOf(
                            ((it shr 24) and 0xFF).toByte(),
                            ((it shr 16) and 0xFF).toByte(),
                            ((it shr 8) and 0xFF).toByte(),
                            (it and 0xFF).toByte()
                        )
                    }
                }
            }
            override val writeShort: (Short) -> ByteArray by lazy {

                if (isLittleEndian) {
                    {
                        byteArrayOf(
                            (it and 0xFF).toByte(),
                            ((it.toInt() shr 8) and 0xFF).toByte()
                        )
                    }
                } else {
                    {
                        byteArrayOf(
                            ((it.toInt() shr 8) and 0xFF).toByte(),
                            (it and 0xFF).toByte()
                        )
                    }
                }
            }
        }
    }
}