@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.isam.meta

import borg.trikeshed.common.parser.simple.CharSeries
import borg.trikeshed.lib.*
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

enum class IOMemento(override val networkSize: Int? = null, val fromChars: (Series<Char>) -> Any) : TypeMemento {
    IoBoolean(1, {
        when (it[0]) {
            't' -> true
            'f' -> false
            else -> throw IllegalArgumentException("invalid boolean: $it")
        }
    }),
    IoByte(1, {
        it.parseLong().toByte()
    }),
    IoShort(2, {
        it.parseLong().toShort()
    }),
    IoInt(4, { it.parseLong().toInt() }),
    IoLong(8, Series<Char>::parseLong),
    IoFloat(4, { it.parseDouble().toFloat() }),
    IoDouble(8, { it.parseDouble() }),
    IoLocalDate(8, { it.parseIsoDateTime() }),

    /**
     * 12 bytes of storage, first epoch seconds Long , then nanos Int
     */
    IoInstant(12, { Instant.parse(it.toString()) }),
    IoString(null, { it.asString() }),
    IoCharSeries(null, ::CharSeries),
    IoByteArray(null, { it.encodeToByteArray() }),
    IoNothing(null, { "" }),
    ;

    companion object {
        val readCharSeries: (ByteArray) -> Series<Char> =
            { value: ByteArray -> (value α { it.toInt().toChar() }) }
        val writeCharSeries: (Any?) -> ByteArray =
            { value: Any? -> (value as Series<Char>).asString().encodeToByteArray() }
        val readByteArray: (ByteArray) -> ByteArray = { value: ByteArray -> value }
        val writeByteArray: (Any?) -> ByteArray = { value: Any? -> value as ByteArray }

        //        val readLocalDate: (ByteArray) -> LocalDate
//        val writeLocalDate: (Any?) -> ByteArray           
//        val writeString: (Any?) -> ByteArray 
//        val writeNothing: (Any?) -> ByteArray 
//        val readBool: (ByteArray) -> Boolean       
//        val readByte: (ByteArray) -> Byte         
//        val readInstant: (ByteArray) -> Instant             
//        val readString: (ByteArray) -> String
//        val readNothing: (ByteArray) -> Nothing?
//        val writeInstant: (Any?) -> ByteArray        
        val readInstant = { value: ByteArray ->
            Instant.fromEpochSeconds(
                value.networkOrderGetLongAt(0),
                value.networkOrderGetIntAt(8)
            )
        }
        val readLocalDate = { value: ByteArray ->
            LocalDate.fromEpochDays(
                value.networkOrderGetLongAt(0).toInt()
            )
        }
        val readString = { value: ByteArray -> value.decodeToString() }
        val readNothing = { _: ByteArray -> null }
        val writeLocalDate = { value: Any? ->
            ByteArray(8).apply<ByteArray> {
                ->
                networkOrderSetLongAt(
                    0,
                    (value as LocalDate).toEpochDays().toLong()
                )
            }
        }
        val writeString = { value: Any? -> (value as String).encodeToByteArray() }
        val writeNothing = { _: Any? -> ByteArray(0) }


        val writeInstant = { value: Any? ->
            ByteArray(12).apply {
                networkOrderSetLongAt(
                    0,
                    (value as Instant).epochSeconds
                ); networkOrderSetIntAt(8, value.nanosecondsOfSecond)
            }
        }
        val readBool = { value: ByteArray -> value[0] == 1.toByte() }
        val readByte = { value: ByteArray -> value[0] }
    }

}
