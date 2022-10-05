package borg.trikeshed.common.isam.meta

import borg.trikeshed.lib.*
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

enum class IOMemento(override val networkSize: Int? = null) : TypeMemento {
    IoBoolean(1),
    IoByte(1),
    IoInt(4),
    IoLong(8),
    IoFloat(4),
    IoDouble(8),
    IoString,
    IoLocalDate(8),

    /**
     * 12 bytes of storage, first epoch seconds Long , then nanos Int
     */
    IoInstant(12),
    IoNothing
    ;

    //the reasons for the functions being in the companion object instead of in the enum are unending type inference issues
    companion object {

        val readBool = { value: ByteArray -> value[0] == 1.toByte() }
        val readByte = { value: ByteArray -> value[0] }
        val readInt = { value: ByteArray -> value.networkOrderGetIntAt(0) }
        val readLong = { value: ByteArray -> value.networkOrderGetLongAt(0) }
        val readFloat = { value: ByteArray -> value.networkOrderGetFloatAt(0) }
        val readDouble = { value: ByteArray -> value.networkOrderGetDoubleAt(0) }
        val readInstant = { value: ByteArray ->
            Instant.fromEpochSeconds(
                value.networkOrderGetLongAt(0),
                value.networkOrderGetIntAt(0)
            )
        }
        private val readLocalDate = { value: ByteArray ->
            LocalDate.fromEpochDays(
                value.networkOrderGetLongAt(0).toLong().toInt()
            )
        }
        val readString = { value: ByteArray -> value.decodeToString() }
        val readNothing = { _: ByteArray -> null }

        val writeBool = { value: Any? -> byteArrayOf(if (value as Boolean) 1 else 0) }
        val writeByte = { value: Any? -> byteArrayOf(value as Byte) }
        val writeInt = { value: Any? -> ByteArray(4).apply { networkOrderSetIntAt(0, value as Int) } }
        val writeLong = { value: Any? -> ByteArray(8).apply { networkOrderSetLongAt(0, value as Long) } }
        val writeFloat = { value: Any? -> ByteArray(4).apply { networkOrderSetFloatAt(0, value as Float) } }
        val writeDouble = { value: Any? -> ByteArray(8).apply { networkOrderSetDoubleAt(0, value as Double) } }
        val writeInstant = { value: Any? ->
            ByteArray(12).apply {
                networkOrderSetLongAt(
                    0,
                    (value as Instant).epochSeconds
                ); networkOrderSetIntAt(0, value.nanosecondsOfSecond)
            }
        }

        private val writeLocalDate = { value: Any? ->
            ByteArray(8).apply<ByteArray> {
                ->
                networkOrderSetLongAt(
                    0,
                    (value as LocalDate).toEpochDays().toLong()
                )
            }
        }
        private val writeString = { value: Any? -> (value as String).encodeToByteArray() }
        private val writeNothing = { _: Any? -> ByteArray(0) }
        fun createEncoder(type: IOMemento, size: Int): (Any?) -> ByteArray {
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
                IoNothing -> writeNothing
            }
        }

        fun createDecoder(
            type: IOMemento,
            size: Int
        ): (ByteArray) -> Any? {
            return when (type) {
                // all values must be read and written in network endian order
                // we must call the marshalling functions inside the NetworkOrder ByteArray extension functions to ensure this

                IoBoolean -> readBool
                IoByte -> readByte
                IoInt -> readInt
                IoLong -> readLong
                IoFloat -> readFloat
                IoDouble -> readDouble
                IoInstant -> readInstant
                IoLocalDate -> readLocalDate
                IoString -> readString
                IoNothing -> readNothing
            }
        }
    }
}
