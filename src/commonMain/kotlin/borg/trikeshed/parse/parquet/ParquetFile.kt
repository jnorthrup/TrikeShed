package borg.trikeshed.parse.parquet

import borg.trikeshed.collections.SeriesBuffer
import borg.trikeshed.lib.*

/** A parquet file read through a byte range: the footer once, then any column by its path, row group after row group. */
class ParquetFile(size: Long, val range: ByteRange, val inflate: Inflate) {
    val footer: ParquetFooter = ParquetFooter.read(size, range)

    /** Every value of the column [path], nulls included, in row order: its chunks, and only its chunks, fetched, filled into one buffer and drained. */
    fun column(path: String): Series<Any?> {
        val chunks = footer.chunks.filter { it.column.path == path }
        require(chunks.size > 0) { "no column $path in the file" }
        val out = SeriesBuffer<Any?>(footer.rows.toInt())
        chunks.forEach { chunk -> ParquetPages.values(chunk, range(chunk.begin, chunk.length), inflate).forEach { out.add(it) } }
        return out.drain()
    }
}
