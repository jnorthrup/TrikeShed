package borg.trikeshed.parse.parquet

import borg.trikeshed.collections.IntColumn
import borg.trikeshed.collections.SeriesBuffer
import borg.trikeshed.lib.*
import borg.trikeshed.tilting.zran.le32
import borg.trikeshed.tilting.zran.le64
import borg.trikeshed.tilting.zran.zstdDecompress

/** Decompresses one page body for the codecs commonMain does not carry (all but UNCOMPRESSED, SNAPPY and ZSTD): `(codec, bytes, size once decompressed)`. */
typealias Inflate = (codec: Int, bytes: ByteArray, size: Int) -> ByteArray

/**
 * The values of one flat column chunk, a null where the definition level says so. Each page is decoded and its rows are added to one
 * [SeriesBuffer], drained as the chunk's [Series]; levels and dictionary indices fill an [IntColumn]. Plain, dictionary and (booleans) RLE pages,
 * data pages v1 and v2. A chunk of a repeated column (list, map) is refused, as are an encoding or a physical type the files in use do not carry.
 */
object ParquetPages {
    fun values(chunk: ParquetChunk, bytes: ByteArray, inflate: Inflate): Series<Any?> {
        val column = chunk.column
        require(column.maxRepetition == 0) { "${column.path}: repeated columns are not read" }
        val out = SeriesBuffer<Any?>(chunk.values.toInt())
        var dictionary: Series<Any?>? = null
        var at = 0
        while (out.size < chunk.values) {
            val reader = ThriftCompact(bytes, at)
            val header = reader.struct()
            val body = reader.at
            val size = header.long(3).toInt()
            val raw = header.long(2).toInt()
            when (header.long(1).toInt()) {
                2 -> dictionary = plain(column, decompress(chunk.codec, bytes.copyOfRange(body, body + size), raw, inflate), 0, header.struct(7).long(1).toInt())
                0 -> {
                    val h = header.struct(5)
                    val n = h.long(1).toInt()
                    val page = decompress(chunk.codec, bytes.copyOfRange(body, body + size), raw, inflate)
                    var from = 0
                    val levels = if (column.maxDefinition > 0) {
                        from = 4 + page.le32(0)
                        hybrid(page, 4, from, width(column.maxDefinition), n)
                    } else null
                    decode(column, h.long(2).toInt(), page, from, n, levels, dictionary, out)
                }
                3 -> {
                    val h = header.struct(8)
                    val n = h.long(1).toInt()
                    val definitions = h.long(5).toInt()
                    val repetitions = h.long(6).toInt()
                    val levels = if (column.maxDefinition > 0)
                        hybrid(bytes, body + repetitions, body + repetitions + definitions, width(column.maxDefinition), n) else null
                    val rest = bytes.copyOfRange(body + repetitions + definitions, body + size)
                    val page = if (h.getOrElse(7) { true } as Boolean) decompress(chunk.codec, rest, raw - repetitions - definitions, inflate) else rest
                    decode(column, h.long(4).toInt(), page, 0, n, levels, dictionary, out)
                }
            }
            at = body + size
        }
        return out.drain()
    }

    fun decompress(codec: Int, body: ByteArray, size: Int, inflate: Inflate): ByteArray = when (codec) {
        0 -> body
        1 -> Snappy.decode(body)
        6 -> zstdDecompress(body)
        else -> inflate(codec, body, size)
    }

    /** Bits that hold a level up to [maxLevel]. */
    fun width(maxLevel: Int): Int = 32 - maxLevel.countLeadingZeroBits()

    /** Adds the [n] rows of one page to [out]: a null for each row whose level is below the maximum, else the next value. */
    fun decode(
        column: ParquetColumn, encoding: Int, page: ByteArray, from: Int, n: Int,
        levels: IntColumn?, dictionary: Series<Any?>?, out: SeriesBuffer<Any?>,
    ) {
        val present = levels?.series?.fold(0) { sum, level -> if (level == column.maxDefinition) sum + 1 else sum } ?: n
        val values: Series<Any?> = when {
            present == 0 -> emptySeriesOf()
            encoding == 0 -> plain(column, page, from, present)
            encoding == 2 || encoding == 8 -> {
                val entries = requireNotNull(dictionary) { "${column.path}: a dictionary-encoded page before any dictionary page" }
                hybrid(page, from + 1, page.size, page[from].toInt() and 0xFF, present).series α { entries[it] }
            }
            encoding == 3 && column.type == 0 -> hybrid(page, from + 4, from + 4 + page.le32(from), 1, present).series α { it == 1 }
            else -> error("${column.path}: encoding $encoding is not read")
        }
        var next = 0
        if (levels == null) values.forEach { out.add(it) }
        else levels.series.forEach { level -> out.add(if (level == column.maxDefinition) values[next++] else null) }
    }

    /** [count] plain-encoded values from [from], filled and drained as a [Series]. */
    fun plain(column: ParquetColumn, page: ByteArray, from: Int, count: Int): Series<Any?> {
        val out = SeriesBuffer<Any?>(count)
        var p = from
        when (column.type) {
            0 -> repeat(count) { out.add(((page[from + (it ushr 3)].toInt() ushr (it and 7)) and 1) == 1) }
            1 -> repeat(count) { out.add(page.le32(p).toLong()); p += 4 }
            2 -> repeat(count) { out.add(page.le64(p)); p += 8 }
            4 -> repeat(count) { out.add(Float.fromBits(page.le32(p)).toDouble()); p += 4 }
            5 -> repeat(count) { out.add(Double.fromBits(page.le64(p))); p += 8 }
            6 -> repeat(count) {
                val n = page.le32(p)
                p += 4
                out.add(if (column.text) page.decodeToString(p, p + n) else page.copyOfRange(p, p + n))
                p += n
            }
            7 -> repeat(count) { out.add(page.copyOfRange(p, p + column.width)); p += column.width }
            else -> error("${column.path}: physical type ${column.type} is not read")
        }
        return out.drain()
    }

    /** The RLE / bit-packed hybrid of parquet: [count] values of [width] bits from `data[from, to)`, filled into an [IntColumn]. */
    fun hybrid(data: ByteArray, from: Int, to: Int, width: Int, count: Int): IntColumn {
        val out = IntColumn(count)
        if (width == 0) {
            repeat(count) { out.add(0) }
            return out
        }
        var p = from
        while (out.n < count) {
            check(p < to) { "parquet: a level or index run ends at ${out.n} of $count values" }
            var head = 0
            var shift = 0
            while (true) {
                val b = data[p++].toInt() and 0xFF
                head = head or ((b and 0x7F) shl shift)
                if (b < 0x80) break
                shift += 7
            }
            if (head and 1 == 0) {
                var v = 0
                for (k in 0 until (width + 7) / 8) v = v or ((data[p++].toInt() and 0xFF) shl (8 * k))
                repeat(minOf(head ushr 1, count - out.n)) { out.add(v) }
            } else {
                val groups = head ushr 1
                val origin = p * 8
                repeat(minOf(groups * 8, count - out.n)) { i ->
                    var v = 0
                    for (k in 0 until width) {
                        val bit = origin + i * width + k
                        v = v or (((data[bit ushr 3].toInt() ushr (bit and 7)) and 1) shl k)
                    }
                    out.add(v)
                }
                p += groups * width
            }
        }
        return out
    }
}
