package borg.trikeshed.isam.meta

import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.placeholder.nars.CharBuffer
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import sun.misc.Unsafe

actual object PlatformCodec {
    val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
    actual val readBool = { value: ByteArray -> unsafe.getByte(value, 0) == 1.toByte() }

    actual val readByte = { value: ByteArray -> unsafe.getByte(value, 0) }

    //readShort
    actual val readShort = { value: ByteArray -> unsafe.getShort(value, 0) }

    actual val readInt = { value: ByteArray -> unsafe.getInt(value, 0) }


    actual val readLong = { value: ByteArray -> unsafe.getLong(value, 0) }
    actual val readFloat = { value: ByteArray -> unsafe.getFloat(value, 0) }
    actual val readDouble = { value: ByteArray -> unsafe.getDouble(value, 0) }
    actual val readInstant = { value: ByteArray ->
        Instant.fromEpochSeconds(
            unsafe.getLong(value, 0),
            unsafe.getInt(value, 8)
        )
    }

    val readLocalDate = { value: ByteArray ->
        LocalDate.fromEpochDays(
            unsafe.getLong(value, 0).toLong().toInt()
        )
    }
    actual val readString = { value: ByteArray -> value.decodeToString() }
    actual val readNothing = { _: ByteArray -> null }
    actual val writeBool = { value: Any? ->
        ByteArray(1).apply {
            unsafe.putByte(this, 0, if (value as Boolean) 1 else 0)
        }
    }
    actual val writeByte = { value: Any? -> ByteArray(1).apply { unsafe.putByte(this, 0, value as Byte) } }
    actual val writeShort = { value: Any? -> ByteArray(2).apply { unsafe.putShort(this, 0, value as Short) } }
    actual val writeInt = { value: Any? -> ByteArray(4).apply { unsafe.putInt(this, 0, value as Int) } }
    actual val writeLong = { value: Any? -> ByteArray(8).apply { unsafe.putLong(this, 0, value as Long) } }
    actual val writeFloat = { value: Any? -> ByteArray(4).apply { unsafe.putFloat(this, 0, value as Float) } }
    actual val writeDouble = { value: Any? -> ByteArray(8).apply { unsafe.putDouble(this, 0, value as Double) } }
    actual val writeInstant = { value: Any? ->
        ByteArray(12).apply {
            unsafe.putLong(this, 0, (value as Instant).epochSeconds)
            unsafe.putInt(this, 8, value.nanosecondsOfSecond)
        }
    }

    val writeLocalDate = { value: Any? ->
        ByteArray(8).apply<ByteArray> {
            unsafe.putLong(this, 0, (value as LocalDate).toEpochDays().toLong())
        }
    }

    val writeString = { value: Any? -> (value as String).encodeToByteArray() }
    val writeNothing = { _: Any? -> ByteArray(0) }
    val writeCharBuffer = { value: Any? ->(value as CharBuffer).asString().encodeToByteArray() }
    val readCharBuffer = { value: ByteArray -> CharBuffer(value.decodeToString()) }
    val writeByteArray = { value: Any? -> value as ByteArray }
    val readByteArray = { value: ByteArray -> value }

    actual fun createEncoder(type: IOMemento, size: Int): (Any?) -> ByteArray {
        // must use corresponding  networkOrderSetXXX functions to set the bytes in the ByteArray
        return when (type) {
            IoBoolean -> writeBool
            IoByte -> writeByte
            IoShort -> writeShort
            IoInt -> writeInt
            IoLong -> writeLong
            IoFloat -> writeFloat
            IoDouble -> writeDouble
            IoString -> writeString
            IoInstant -> writeInstant
            IoLocalDate -> writeLocalDate
            IoCharBuffer -> writeCharBuffer
            IoByteArray -> writeByteArray
            IoNothing -> writeNothing
        }
    }



    actual fun createDecoder(
        type: IOMemento,
        size: Int
    ): (ByteArray) -> Any? {
        return when (type) {
            // all values must be read and written in network endian order
            // we must call the marshalling functions inside the NetworkOrder ByteArray extension functions to ensure this

            IoBoolean -> readBool
            IoByte -> readByte
            IoShort -> readShort
            IoInt -> readInt
            IoLong -> readLong
            IoFloat -> readFloat
            IoDouble -> readDouble
            IoString -> readString
            IoLocalDate -> readLocalDate
            IoInstant -> readInstant
            IoCharBuffer -> readCharBuffer
            IoByteArray -> readByteArray
            IoNothing -> readNothing
        }
    }
}