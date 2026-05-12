package borg.trikeshed.collections.associative

import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.encodeToByteArray
import borg.trikeshed.lib.j

/**
 * CBOR codec for Item -- RFC 8949.
 *
 * Encode: Item -> ByteArray
 * Decode: ByteArray -> Item
 *
 * Supports: unsigned/negative int, byte string, text string,
 * definite-length array, definite-length map, tag, bool, null, float64.
 * Indefinite-length decode supported (converted to definite).
 * Half/float32 decoded to full Double precision.
 */
object Cbor {

    fun encode(item: Item): ByteArray {
        val buf = ByteBuf()
        item.encode(buf)
        return buf.toByteArray()
    }

   fun Item.encode(buf: ByteBuf) {
        when (this) {
            is Item.Num -> {
                if (value >= 0) encodeHead(buf, 0, value)
                else encodeHead(buf, 1, -(value + 1))
            }
            is Item.Str -> {
                val bytes = CharSeries(value).encodeToByteArray()
                encodeHead(buf, 3, bytes.size.toLong())
                buf.write(bytes)
            }
            is Item.Bin -> {
                encodeHead(buf, 2, value.size.toLong())
                buf.write(value)
            }
            is Item.Arr -> {
                encodeHead(buf, 4, items.a.toLong())
                for (i in 0 until items.a) items.b(i).encode(buf)
            }
            is Item.Map -> {
                encodeHead(buf, 5, entries.a.toLong())
                for (i in 0 until entries.a) {
                    Item.Str(entries.b(i).a).encode(buf)
                    entries.b(i).b.encode(buf)
                }
            }
            is Item.Bool -> buf.write(if (value) 0xF5 else 0xF4)
            is Item.Nil -> buf.write(0xF6)
            is Item.Flt -> {
                buf.write(0xFB)
                buf.writeDouble(value)
            }
            is Item.Tag -> {
                encodeHead(buf, 6, tag.toLong())
                item.encode(buf)
            }
        }
    }

   fun encodeHead(buf: ByteBuf, major: Int, value: Long) {
        val mt = major shl 5
        when {
            value < 24 -> buf.write(mt or value.toInt())
            value <= 0xFF -> { buf.write(mt or 24); buf.write(value.toInt()) }
            value <= 0xFFFF -> { buf.write(mt or 25); buf.writeShort(value.toInt()) }
            value <= 0xFFFFFFFFL -> { buf.write(mt or 26); buf.writeInt(value.toInt()) }
            else -> { buf.write(mt or 27); buf.writeLong(value) }
        }
    }

    fun decode(bytes: ByteArray): Item = Reader(bytes, 0).readItem()

   class Reader(val data: ByteArray, var pos: Int) {

        fun readItem(): Item {
            val head = u8()
            val major = head shr 5
            val additional = head and 0x1F

            if (additional == 31) return when (major) {
                2 -> readIndefBytes()
                3 -> readIndefText()
                4 -> readIndefArray()
                5 -> readIndefMap()
                else -> error("Indefinite length not supported for major type $major")
            }

            val argument = readArgument(additional)

            return when (major) {
                0 -> Item.Num(argument)
                1 -> Item.Num(-(argument + 1))
                2 -> Item.Bin(readBytes(argument.toInt()))
                3 -> Item.Str(readUtf8(argument.toInt()))
                4 -> readArray(argument.toInt())
                5 -> readMap(argument.toInt())
                6 -> Item.Tag(argument.toUInt(), readItem())
                7 -> readSimple(additional, argument)
                else -> error("Invalid CBOR major type: $major at pos ${pos - 1}")
            }
        }

       fun readArgument(additional: Int): Long = when (additional) {
            in 0..23 -> additional.toLong()
            24 -> u8().toLong()
            25 -> u16().toLong()
            26 -> u32().toLong()
            27 -> u64()
            else -> error("Invalid CBOR additional info: $additional")
        }

       fun readArray(count: Int): Item.Arr {
            val items = ArrayList<Item>(count)
            for (i in 0 until count) items.add(readItem())
            return Item.Arr(items.size j { items[it] })
        }

       fun readMap(count: Int): Item.Map {
            val keys = ArrayList<CharSequence>(count)
            val vals = ArrayList<Item>(count)
            for (i in 0 until count) {
                val keyItem = readItem()
                keys.add((keyItem as? Item.Str)?.value ?: keyItem.toString())
                vals.add(readItem())
            }
            return Item.Map(keys.size j { keys[it] j vals[it] })
        }

       fun readIndefBytes(): Item.Bin {
            val chunks = ArrayList<ByteArray>()
            while (true) {
                if (u8() == 0xFF) break
                pos--
                val item = readItem()
                chunks.add((item as Item.Bin).value)
            }
            return Item.Bin(concat(chunks))
        }

       fun readIndefText(): Item.Str {
            val sb = StringBuilder()
            while (true) {
                if (u8() == 0xFF) break
                pos--
                val item = readItem()
                sb.append((item as Item.Str).value)
            }
            return Item.Str(sb.toString())
        }

       fun readIndefArray(): Item.Arr {
            val items = ArrayList<Item>()
            while (true) {
                if (u8() == 0xFF) break
                pos--
                items.add(readItem())
            }
            return Item.Arr(items.size j { items[it] })
        }

       fun readIndefMap(): Item.Map {
            val keys = ArrayList<CharSequence>()
            val vals = ArrayList<Item>()
            while (true) {
                if (u8() == 0xFF) break
                pos--
                val keyItem = readItem()
                keys.add((keyItem as? Item.Str)?.value ?: keyItem.toString())
                vals.add(readItem())
            }
            return Item.Map(keys.size j { keys[it] j vals[it] })
        }

       fun readSimple(additional: Int, argument: Long): Item = when (additional) {
            20 -> Item.Bool(false)
            21 -> Item.Bool(true)
            22 -> Item.Nil
            23 -> Item.Nil
            25 -> Item.Flt(readFloat16())
            26 -> Item.Flt(Float.fromBits(argument.toInt()).toDouble())
            27 -> Item.Flt(Double.fromBits(argument))
            else -> error("Unknown CBOR simple value: $additional")
        }

       fun u8(): Int = data[pos++].toInt() and 0xFF
       fun u16(): Int = (u8() shl 8) or u8()
       fun u32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
       fun u64(): Long = (u32().toLong() shl 32) or (u32().toLong() and 0xFFFFFFFFL)

       fun readBytes(n: Int): ByteArray {
            val result = data.copyOfRange(pos, pos + n)
            pos += n
            return result
        }

       fun readUtf8(n: Int): CharSequence {
            val result = data.decodeToString(pos, pos + n)
            pos += n
            return result
        }

       fun readFloat16(): Double {
            val bits = u16()
            val sign = (bits shr 15) and 1
            val exp = (bits shr 10) and 0x1F
            val frac = bits and 0x3FF
            return when (exp) {
                0 -> (if (sign != 0) -1.0 else 1.0) * pow(2.0, -14) * (frac / 1024.0)
                31 -> {
                    if (frac == 0) {
                        if (sign != 0) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
                    } else Double.NaN
                }
                else -> (if (sign != 0) -1.0 else 1.0) * pow(2.0, exp - 15) * (1.0 + frac / 1024.0)
            }
        }
    }
}
class ByteBuf {
   val buf = ArrayList<Byte>(256)
    fun write(b: Int) { buf.add(b.toByte()) }
    fun write(b: Byte) { buf.add(b) }
    fun write(bytes: ByteArray) { bytes.forEach { buf.add(it) } }
    fun writeShort(v: Int) { write((v shr 8) and 0xFF); write(v and 0xFF) }
    fun writeInt(v: Int) {
        write((v shr 24) and 0xFF); write((v shr 16) and 0xFF)
        write((v shr 8) and 0xFF); write(v and 0xFF)
    }
    fun writeLong(v: Long) { writeInt((v shr 32).toInt()); writeInt(v.toInt()) }
    fun writeDouble(v: Double) { writeLong(v.toRawBits()) }
    fun toByteArray(): ByteArray = buf.toByteArray()
}
fun concat(chunks: List<ByteArray>): ByteArray {
    val size = chunks.sumOf { it.size }
    val result = ByteArray(size)
    var off = 0
    for (c in chunks) { c.copyInto(result, off); off += c.size }
    return result
}
fun pow(base: Double, exp: Int): Double {
    var result = 1.0
    var b = base
    var e = exp
    while (e > 0) {
        if (e and 1 == 1) result *= b
        b *= b
        e = e shr 1
    }
    return result
}
