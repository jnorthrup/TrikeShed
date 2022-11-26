package borg.trikeshed.isam.meta

import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.isam.meta.IOMemento.Companion.readBool
import borg.trikeshed.isam.meta.IOMemento.Companion.readByte
import borg.trikeshed.isam.meta.IOMemento.Companion.readByteArray
import borg.trikeshed.isam.meta.IOMemento.Companion.readCharSeries
import borg.trikeshed.isam.meta.IOMemento.Companion.readInstant
import borg.trikeshed.isam.meta.IOMemento.Companion.readLocalDate
import borg.trikeshed.isam.meta.IOMemento.Companion.readNothing
import borg.trikeshed.isam.meta.IOMemento.Companion.readString
import borg.trikeshed.isam.meta.IOMemento.Companion.writeByteArray
import borg.trikeshed.isam.meta.IOMemento.Companion.writeCharSeries
import borg.trikeshed.isam.meta.IOMemento.Companion.writeInstant
import borg.trikeshed.isam.meta.IOMemento.Companion.writeLocalDate
import borg.trikeshed.isam.meta.IOMemento.Companion.writeNothing
import borg.trikeshed.isam.meta.IOMemento.Companion.writeString
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

actual object PlatformCodec {

    actual val readShort = { value: ByteArray -> ((value[0].toInt() shl 8) or (value[1].toInt() and 0xff)).toShort() }
    actual val readInt =
        { value: ByteArray -> (value[0].toInt() shl 24) or (value[1].toInt() and 0xff shl 16) or (value[2].toInt() and 0xff shl 8) or (value[3].toInt() and 0xff) }
    actual val readLong =
        { value: ByteArray -> (value[0].toLong() shl 56) or (value[1].toLong() and 0xff shl 48) or (value[2].toLong() and 0xff shl 40) or (value[3].toLong() and 0xff shl 32) or (value[4].toLong() and 0xff shl 24) or (value[5].toLong() and 0xff shl 16) or (value[6].toLong() and 0xff shl 8) or (value[7].toLong() and 0xff) }
    actual val readFloat = { value: ByteArray -> java.lang.Float.intBitsToFloat(readInt(value)) }
    actual val readDouble = { value: ByteArray -> java.lang.Double.longBitsToDouble(readLong(value)) }
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