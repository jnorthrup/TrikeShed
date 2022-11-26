@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.isam.meta

import borg.trikeshed.common.parser.simple.CharSeries
import borg.trikeshed.lib.*
import kotlinx.datetime.Instant

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
    }
}
