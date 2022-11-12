package borg.trikeshed.isam.meta

import borg.trikeshed.lib.α
import borg.trikeshed.placeholder.nars.CharBuffer

enum class IOMemento(override val networkSize: Int? = null) : TypeMemento {
    IoBoolean(1),
    IoByte(1),
    IoShort(2),
    IoInt(4),
    IoLong(8),
    IoFloat(4),
    IoDouble(8),
    IoLocalDate(8),
    /**
     * 12 bytes of storage, first epoch seconds Long , then nanos Int
     */
    IoInstant(12),
    IoString,
    IoCharBuffer,
    IoByteArray,
    IoNothing;
    companion object {
        val readCharbuffer: (ByteArray) -> CharBuffer = { value: ByteArray -> CharBuffer(value α { it.toChar() }) }
        val writeCharbuffer: (Any?) -> ByteArray =
            { value: Any? -> (value as CharBuffer).asString().encodeToByteArray() }
        val readByteArray: (ByteArray) -> ByteArray = { value: ByteArray -> value }
        val writeByteArray: (Any?) -> ByteArray = { value: Any? -> value as ByteArray }
    }

}