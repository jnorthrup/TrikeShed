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
import borg.trikeshed.lib.*

actual object PlatformCodec {


    actual val readInt = { value: ByteArray -> value.networkOrderGetIntAt(0) }
    actual val readLong = { value: ByteArray -> value.networkOrderGetLongAt(0) }
    actual val readFloat = { value: ByteArray -> value.networkOrderGetFloatAt(0) }
    actual val readDouble = { value: ByteArray -> value.networkOrderGetDoubleAt(0) }

    actual val writeBool = { value: Any? -> byteArrayOf(if (value as Boolean) 1 else 0) }
    actual val writeByte = { value: Any? -> byteArrayOf(value as Byte) }
    actual val writeInt = { value: Any? -> ByteArray(4).apply { networkOrderSetIntAt(0, value as Int) } }
    actual val writeShort = { value: Any? -> ByteArray(2).apply { networkOrderSetShortAt(0, value as Short) } }
    actual val writeLong = { value: Any? -> ByteArray(8).apply { networkOrderSetLongAt(0, value as Long) } }
    actual val writeFloat = { value: Any? -> ByteArray(4).apply { networkOrderSetFloatAt(0, value as Float) } }
    actual val writeDouble = { value: Any? -> ByteArray(8).apply { networkOrderSetDoubleAt(0, value as Double) } }


    actual fun createEncoder(type: IOMemento, size: Int): (Any?) -> ByteArray {
        // must use corresponding  networkOrderSetXXX functions to set the bytes in the ByteArray
        return when (type) {
            IoBoolean -> writeBool
            IoByte -> writeByte

            IoInt -> writeInt
            IoLong -> writeLong
            IoFloat -> writeFloat
            IoDouble -> writeDouble
            IoString -> writeString
            IoInstant -> writeInstant
            IoLocalDate -> writeLocalDate
            IoShort -> writeShort
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
            IoInstant -> readInstant
            IoLocalDate -> readLocalDate
            IoString -> readString
            IoCharSeries -> readCharSeries
            IoByteArray -> readByteArray
            IoNothing -> readNothing
        }
    }

    actual val readShort: (ByteArray) -> Short = { value: ByteArray -> value.networkOrderGetShortAt(0) }
}