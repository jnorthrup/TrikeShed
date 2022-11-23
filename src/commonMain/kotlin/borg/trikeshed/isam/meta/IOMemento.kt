package borg.trikeshed.isam.meta

import borg.trikeshed.common.parser.simple.CharSeries
import borg.trikeshed.lib.α
import borg.trikeshed.placeholder.nars.CharBuffer
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

enum class IOMemento(override val networkSize: Int? = null, val fromString: (String) -> Any) : TypeMemento {
    IoBoolean(1, { it.lowercase() == "true" }),
    IoByte(1, { (it.toIntOrNull() ?: 0).toByte() }),
    IoShort(2, { (it.toIntOrNull() ?: 0).toShort() }),
    IoInt(4, { it.toIntOrNull() ?: 0 }),
    IoLong(8, { it.toLongOrNull() ?: 0L }),
    IoFloat(4, { it.toFloatOrNull() ?: 0.0f }),
    IoDouble(8, { it.toDoubleOrNull() ?: 0.0 }),
    IoLocalDate(8, LocalDate.Companion::parse),

    /**
     * 12 bytes of storage, first epoch seconds Long , then nanos Int
     */
    IoInstant(12,Instant::parse),
    IoString (null,String::toString ) ,
    IoCharSeries(null, ::CharSeries),
    IoByteArray(null, {it.encodeToByteArray()}),
    IoNothing(null, {""}),;

    companion object {
        val readCharbuffer: (ByteArray) -> CharBuffer = { value: ByteArray -> CharBuffer(value α { it.toInt().toChar() }) }
        val writeCharbuffer: (Any?) -> ByteArray =
            { value: Any? -> (value as CharBuffer).asString().encodeToByteArray() }
        val readByteArray: (ByteArray) -> ByteArray = { value: ByteArray -> value }
        val writeByteArray: (Any?) -> ByteArray = { value: Any? -> value as ByteArray }
    }

}