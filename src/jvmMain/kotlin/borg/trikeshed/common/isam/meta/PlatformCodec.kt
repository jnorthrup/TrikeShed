package borg.trikeshed.common.isam.meta

import borg.trikeshed.isam.meta.IOMemento
import jdk.internal.misc.Unsafe
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

object PlatformCodec{
    val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe

    val readBool = { value: ByteArray -> unsafe.getByte(value, 0) == 1.toByte() }
    val readByte = { value: ByteArray -> unsafe.getByte(value, 0) }
    val readInt = { value: ByteArray -> unsafe.getInt(value, 0) }
    val readLong = { value: ByteArray -> unsafe.getLong(value, 0) }
    val readFloat = { value: ByteArray -> unsafe.getFloat(value, 0) }
    val readDouble = { value: ByteArray -> unsafe.getDouble(value, 0) }

    val readInstant = { value: ByteArray ->
        Instant.fromEpochSeconds(
            unsafe.getLong(value, 0),
            unsafe.getInt(value, 8)
        )
    }

    private val readLocalDate = { value: ByteArray ->
        LocalDate.fromEpochDays(
            unsafe.getLong(value, 0).toLong().toInt()
        )
    }

    val readString = { value: ByteArray -> value.decodeToString() }
    val readNothing = { _: ByteArray -> null }

    //rewrite those methods with unsafe pointers

    val writeBool = { value: Any? -> ByteArray(1).apply { unsafe.putByte(this, 0, if (value as Boolean) 1 else 0) } }
    val writeByte = { value: Any? -> ByteArray(1).apply { unsafe.putByte(this, 0, value as Byte) } }
    val writeInt = { value: Any? -> ByteArray(4).apply { unsafe.putInt(this, 0, value as Int) } }
    val writeLong = { value: Any? -> ByteArray(8).apply { unsafe.putLong(this, 0, value as Long) } }
    val writeFloat = { value: Any? -> ByteArray(4).apply { unsafe.putFloat(this, 0, value as Float) } }
    val writeDouble = { value: Any? -> ByteArray(8).apply { unsafe.putDouble(this, 0, value as Double) } }
    val writeInstant = { value: Any? ->
        ByteArray(12).apply {
            unsafe.putLong(this, 0, (value as Instant).epochSeconds)
            unsafe.putInt(this, 8, value.nanosecondsOfSecond)
        }
    }

    private val writeLocalDate = { value: Any? ->
        ByteArray(8).apply<ByteArray> {
            ->
            unsafe.putLong(this, 0, (value as LocalDate).toEpochDays().toLong())
        }
    }
    private val writeString = { value: Any? -> (value as String).encodeToByteArray() }
    private val writeNothing = { _: Any? -> ByteArray(0) }





    fun createEncoder(type: IOMemento, size: Int): (Any?) -> ByteArray {
        // must use corresponding  networkOrderSetXXX functions to set the bytes in the ByteArray
        return when (type) {
            IOMemento.IoBoolean -> writeBool
            IOMemento.IoByte -> writeByte

            IOMemento.IoInt -> writeInt
            IOMemento.IoLong -> writeLong
            IOMemento.IoFloat -> writeFloat
            IOMemento.IoDouble -> writeDouble
            IOMemento.IoString -> writeString
            IOMemento.IoInstant -> writeInstant
            IOMemento.IoLocalDate -> writeLocalDate
            IOMemento.IoNothing -> writeNothing
        }
    }

    fun createDecoder(
        type: IOMemento,
        size: Int
    ): (ByteArray) -> Any? {
        return when (type) {
            // all values must be read and written in network endian order
            // we must call the marshalling functions inside the NetworkOrder ByteArray extension functions to ensure this

            IOMemento.IoBoolean -> readBool
            IOMemento.IoByte -> readByte
            IOMemento.IoInt -> readInt
            IOMemento.IoLong -> readLong
            IOMemento.IoFloat -> readFloat
            IOMemento.IoDouble -> readDouble
            IOMemento.IoInstant -> readInstant
            IOMemento.IoLocalDate -> readLocalDate
            IOMemento.IoString -> readString
            IOMemento.IoNothing -> readNothing
        }
    }
}