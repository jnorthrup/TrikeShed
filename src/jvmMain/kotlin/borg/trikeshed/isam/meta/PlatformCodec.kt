package borg.trikeshed.isam.meta

import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.isam.meta.IOMemento.Companion.readByteArray
import borg.trikeshed.isam.meta.IOMemento.Companion.readCharSeries
import borg.trikeshed.isam.meta.IOMemento.Companion.writeByteArray
import borg.trikeshed.isam.meta.IOMemento.Companion.writeCharSeries
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

actual object PlatformCodec {

    actual val readBool = { value: ByteArray -> value[0] == 1.toByte() }
    actual val readByte = { value: ByteArray -> value[0] }
    actual val readShort = { value: ByteArray -> ((value[0].toInt() shl 8) or (value[1].toInt() and 0xff)).toShort() }
    actual val readInt =
        { value: ByteArray -> (value[0].toInt() shl 24) or (value[1].toInt() and 0xff shl 16) or (value[2].toInt() and 0xff shl 8) or (value[3].toInt() and 0xff) }
    actual val readLong =
        { value: ByteArray -> (value[0].toLong() shl 56) or (value[1].toLong() and 0xff shl 48) or (value[2].toLong() and 0xff shl 40) or (value[3].toLong() and 0xff shl 32) or (value[4].toLong() and 0xff shl 24) or (value[5].toLong() and 0xff shl 16) or (value[6].toLong() and 0xff shl 8) or (value[7].toLong() and 0xff) }
    actual val readFloat = { value: ByteArray -> java.lang.Float.intBitsToFloat(readInt(value)) }
    actual val readDouble = { value: ByteArray -> java.lang.Double.longBitsToDouble(readLong(value)) }
    actual val readInstant = { value: ByteArray -> Instant.fromEpochSeconds(readLong(value), 0) }
    actual val readLocalDate = { value: ByteArray -> LocalDate.fromEpochDays(readLong(value).toInt()) }
    actual val readString = { value: ByteArray -> value.decodeToString() }
    actual val readNothing = { _: ByteArray -> null }
    actual val writeBool = { value: Any? -> ByteArray(1).apply { this[0] = if (value as Boolean) 1 else 0 } }
    actual val writeByte = { value: Any? -> ByteArray(1).apply { this[0] = value as Byte } }
    actual val writeShort = { value: Any? ->
        ByteArray(2).apply {
            this[0] = (value as Short).toInt().ushr(8).toByte()
            this[1] = (value.toInt() and 0xff).toByte()
        }
    }
    actual val writeInt = { value: Any? ->
        ByteArray(4).apply {
            this[0] = (value as Int).ushr(24).toByte()
            this[1] = (value.ushr(16) and 0xff).toByte()
            this[2] = (value.ushr(8) and 0xff).toByte()
            this[3] = (value and 0xff).toByte()
        }
    }
    actual val writeLong = { value: Any? ->
        ByteArray(8).apply {
            this[0] = (value as Long).ushr(56).toByte()
            this[1] = (value.ushr(48) and 0xff).toByte()
            this[2] = (value.ushr(40) and 0xff).toByte()
            this[3] = (value.ushr(32) and 0xff).toByte()
            this[4] = (value.ushr(24) and 0xff).toByte()
            this[5] = (value.ushr(16) and 0xff).toByte()
            this[6] = (value.ushr(8) and 0xff).toByte()
            this[7] = (value and 0xff).toByte()
        }
    }
    actual val writeFloat = { value: Any? -> writeInt(java.lang.Float.floatToIntBits(value as Float)) }
    actual val writeDouble = { value: Any? -> writeLong(java.lang.Double.doubleToLongBits(value as Double)) }
    actual val writeInstant = { value: Any? ->
        ByteArray(12).apply {
            writeLong((value as Instant).epochSeconds)
            writeInt(value.nanosecondsOfSecond)
        }
    }
    actual val writeLocalDate = { value: Any? ->
        ByteArray(8).apply<ByteArray> {
            writeLong((value as LocalDate).toEpochDays().toLong())
        }
    }
    actual val writeString = { value: Any? -> (value as String).encodeToByteArray() }
    actual val writeNothing = { _: Any? -> ByteArray(0) }


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
            IoCharSeries -> writeCharSeries
            IoByteArray -> writeByteArray
            IoNothing -> writeNothing
        }
    }

    actual fun createDecoder(
        type: IOMemento,
        size: Int,
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
            IoCharSeries -> readCharSeries
            IoByteArray -> readByteArray
            IoNothing -> readNothing
        }
    }
}