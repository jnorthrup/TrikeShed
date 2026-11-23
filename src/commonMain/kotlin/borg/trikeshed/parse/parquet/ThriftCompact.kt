@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.parse.parquet

import borg.trikeshed.collections.SeriesBuffer
import borg.trikeshed.lib.*
import borg.trikeshed.tilting.zran.le64

/**
 * A thrift struct read by field id (parquet.thrift names the ids): a list is a [Series], every integer a `Long`, a string or binary a
 * `ByteArray`, a bool a `Boolean`, a double a `Double`, a struct another [ThriftStruct].
 */
typealias ThriftStruct = Map<Int, Any?>

fun ThriftStruct.long(id: Int): Long = this[id] as Long? ?: error("thrift field $id is missing")
fun ThriftStruct.longOrNull(id: Int): Long? = this[id] as Long?
fun ThriftStruct.struct(id: Int): ThriftStruct = this[id] as ThriftStruct? ?: error("thrift field $id is missing")
fun ThriftStruct.structOrNull(id: Int): ThriftStruct? = this[id] as ThriftStruct?
fun ThriftStruct.list(id: Int): Series<Any?> = this[id] as Series<Any?>? ?: error("thrift field $id is missing")
fun ThriftStruct.structs(id: Int): Series<ThriftStruct> = list(id) α { it as ThriftStruct }
fun ThriftStruct.text(id: Int): String = (this[id] as ByteArray? ?: error("thrift field $id is missing")).decodeToString()

/** Thrift compact protocol, read from [at] in [bytes]. Parquet's footer and page headers are the only structs read. */
class ThriftCompact(val bytes: ByteArray, var at: Int = 0) {
    fun byte(): Int = bytes[at++].toInt() and 0xFF

    fun varint(): Long {
        var shift = 0
        var v = 0L
        while (true) {
            val b = byte()
            v = v or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return v
            shift += 7
        }
    }

    fun zigzag(): Long {
        val v = varint()
        return (v ushr 1) xor -(v and 1L)
    }

    /** One struct, up to its stop byte; a field id is the delta from the last one, or a full zigzag id when the delta is 0. */
    fun struct(): ThriftStruct {
        val fields = mutableMapOf<Int, Any?>()
        var id = 0
        while (true) {
            val head = byte()
            if (head == 0) return fields
            id = if (head ushr 4 == 0) zigzag().toInt() else id + (head ushr 4)
            fields[id] = value(head and 0x0F)
        }
    }

    fun value(type: Int): Any? = when (type) {
        1 -> true
        2 -> false
        3 -> bytes[at++].toLong()
        4, 5, 6 -> zigzag()
        7 -> {
            val bits = bytes.le64(at)
            at += 8
            Double.fromBits(bits)
        }
        8 -> {
            val n = varint().toInt()
            bytes.copyOfRange(at, at + n).also { at += n }
        }
        9, 10 -> list()
        12 -> struct()
        else -> error("thrift compact type $type is not read")
    }

    /** A list or set: filled element by element, then drained as a [Series]. */
    fun list(): Series<Any?> {
        val head = byte()
        val n = if (head ushr 4 == 15) varint().toInt() else head ushr 4
        val type = head and 0x0F
        val items = SeriesBuffer<Any?>(n)
        repeat(n) { items.add(if (type == 1 || type == 2) byte() == 1 else value(type)) }
        return items.drain()
    }
}
