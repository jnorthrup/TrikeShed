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
import borg.trikeshed.isam.meta.IOMemento.Companion.writeBoolean
import borg.trikeshed.isam.meta.IOMemento.Companion.writeByte
import borg.trikeshed.isam.meta.IOMemento.Companion.writeByteArray
import borg.trikeshed.isam.meta.IOMemento.Companion.writeCharSeries
import borg.trikeshed.isam.meta.IOMemento.Companion.writeInstant
import borg.trikeshed.isam.meta.IOMemento.Companion.writeLocalDate
import borg.trikeshed.isam.meta.IOMemento.Companion.writeNothing
import borg.trikeshed.isam.meta.IOMemento.Companion.writeString
import borg.trikeshed.lib.*
import java.nio.ByteBuffer

actual object PlatformCodec {

    actual fun createEncoder(type: IOMemento, size: Int): (Any?) -> ByteArray = when (type) {

        IoString -> writeString
        IoInstant -> writeInstant
        IoLocalDate -> writeLocalDate
        IoCharSeries -> writeCharSeries
        IoByteArray -> writeByteArray
        IoNothing -> writeNothing
        IoBoolean -> writeBoolean
        IoByte -> writeByte
        IoShort -> writeShort
        IoInt -> writeInt
        IoLong -> writeLong
        IoFloat -> writeFloat
        IoDouble -> writeDouble
    }

    actual fun createDecoder(
        type: IOMemento,
        size: Int,
    ): (ByteArray) -> Any? = when (type) {
        IoBoolean -> readBool
        IoByte -> readByte

        IoString -> readString
        IoLocalDate -> readLocalDate
        IoInstant -> readInstant
        IoCharSeries -> readCharSeries
        IoByteArray -> readByteArray
        IoNothing -> readNothing
        IoShort -> readShort
        IoInt -> readInt
        IoLong -> readLong
        IoFloat -> readFloat
        IoDouble -> readDouble
    }

    actual val readShort: (ByteArray) -> Short = { it.networkOrderGetShortAt(0) }
    actual val writeShort: (Any?) -> ByteArray =
        { v -> (ByteArray(2) { 0 }).apply { networkOrderSetShortAt(0, v as Short) } }
    actual val readInt: (ByteArray) -> Int = { it.networkOrderGetIntAt(0) }
    actual val writeInt: (Any?) -> ByteArray = { v: Any? ->
        (ByteArray(4) { 0 }).apply {
            networkOrderSetIntAt(0, v as Int)
        }.debug {
            val wrap = ByteBuffer.wrap(it)
            assert(wrap.int == v)
        } }
    actual val readLong: (ByteArray) -> Long = { z: ByteArray ->
        z.networkOrderGetLongAt(0).debug {
        val wrap = ByteBuffer.wrap(z).rewind()
        assert(wrap.long == it)
    } }
    actual val writeLong: (Any?) -> ByteArray =
        { v -> (ByteArray(8) { 0 }).apply { networkOrderSetLongAt(0, v as Long) } }
    actual val readFloat: (ByteArray) -> Float = { it.networkOrderGetFloatAt(0) }
    actual val writeFloat: (Any?) -> ByteArray =
        { v -> (ByteArray(4) { 0 }).apply { networkOrderSetFloatAt(0, v as Float) } }
    actual val readDouble: (ByteArray) -> Double = { it.networkOrderGetDoubleAt(0) }
    actual val writeDouble: (Any?) -> ByteArray =
        { v -> (ByteArray(8) { 0 }).apply { networkOrderSetDoubleAt(0, v as Double) } }
}