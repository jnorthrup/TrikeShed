package borg.trikeshed.parse.parquet

import borg.trikeshed.collections.SeriesBuffer
import borg.trikeshed.lib.*
import borg.trikeshed.tilting.zran.le32

/** `[offset, offset + length)` of a file: a local file, a CAS blob or an HTTP range request all serve. */
typealias ByteRange = (offset: Long, length: Int) -> ByteArray

/**
 * A leaf column of the schema: the dotted [path] of its names, its physical [type] (parquet.thrift `Type`), whether a BYTE_ARRAY is [text],
 * the [width] of a FIXED_LEN_BYTE_ARRAY, and the definition and repetition levels its path adds.
 */
class ParquetColumn(val path: String, val type: Int, val text: Boolean, val width: Int, val maxDefinition: Int, val maxRepetition: Int)

/**
 * One column chunk of one row group, and the bytes it occupies in the file: [begin] and [length] are the file's byte-range index, so a reader
 * fetches the columns it wants and nothing else. [values] counts nulls; [uncompressed] is the chunk's size once its pages are inflated.
 */
class ParquetChunk(
    val group: Int,
    val column: ParquetColumn,
    val codec: Int,
    val values: Long,
    val begin: Long,
    val length: Int,
    val uncompressed: Long,
)

/** The footer of a parquet file: its row count, its leaf columns and the byte-range index of every column chunk. */
class ParquetFooter(val rows: Long, val columns: Series<ParquetColumn>, val chunks: Series<ParquetChunk>) {
    companion object {
        /** Reads the footer of a file of [size] bytes: the last 8 bytes name the footer's length, and the footer is one more range. */
        fun read(size: Long, range: ByteRange): ParquetFooter {
            require(size >= 12) { "a parquet file is at least 12 bytes, this one is $size" }
            val tail = range(size - 8, 8)
            require(tail.decodeToString(4, 8) == "PAR1") { "no PAR1 at the end of the file" }
            val length = tail.le32(0)
            val meta = ThriftCompact(range(size - 8 - length, length)).struct()
            val schema = meta.structs(2)
            val leaves = SeriesBuffer<ParquetColumn>()
            var next = 1

            fun walk(path: String, definition: Int, repetition: Int, count: Int) {
                repeat(count) {
                    val element = schema[next++]
                    val mode = element.longOrNull(3)?.toInt() ?: 0
                    val defined = definition + if (mode != 0) 1 else 0
                    val repeated = repetition + if (mode == 2) 1 else 0
                    val name = element.text(4)
                    val here = if (path.isEmpty()) name else "$path.$name"
                    val children = element.longOrNull(5)?.toInt() ?: 0
                    if (children > 0) walk(here, defined, repeated, children)
                    else {
                        val converted = element.longOrNull(6)
                        val logical = element.structOrNull(10)
                        val text = converted == 0L || converted == 4L || converted == 19L ||
                            logical != null && (logical.containsKey(1) || logical.containsKey(4) || logical.containsKey(12))
                        leaves.add(ParquetColumn(here, element.long(1).toInt(), text, element.longOrNull(2)?.toInt() ?: 0, defined, repeated))
                    }
                }
            }
            walk("", 0, 0, schema[0].longOrNull(5)?.toInt() ?: 0)

            val columns = leaves.drain()
            val chunks = SeriesBuffer<ParquetChunk>()
            meta.structs(4).forEachIndexed { group, rowGroup ->
                rowGroup.structs(1).forEachIndexed { column, chunk ->
                    val m = chunk.struct(3)
                    val data = m.long(9)
                    val dictionary = m.longOrNull(11)
                    val begin = if (dictionary != null && dictionary > 0 && dictionary < data) dictionary else data
                    chunks.add(ParquetChunk(group, columns[column], m.long(4).toInt(), m.long(5), begin, m.long(7).toInt(), m.long(6)))
                }
            }
            return ParquetFooter(meta.long(3), columns, chunks.drain())
        }
    }
}
