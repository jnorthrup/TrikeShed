package borg.trikeshed.forge.server

import borg.trikeshed.collections.IntColumn
import borg.trikeshed.collections.SeriesBuffer
import borg.trikeshed.lib.*
import borg.trikeshed.parse.parquet.ParquetColumn
import borg.trikeshed.parse.parquet.ParquetFile
import borg.trikeshed.parse.parquet.ParquetFooter
import borg.trikeshed.parse.parquet.parquetInflate
import java.io.File
import java.io.RandomAccessFile

/** One `where:` condition: a column's values, read once, and the texts a row may hold there. */
typealias Where = Join<Series<Any?>, Series<String>>

fun Where.admits(row: Int): Boolean = (a[row]?.toString() ?: "") in b

/**
 * A parquet file as the markdown the curator reads: `# file`, then one `## heading` section per selected row with its text under it.
 * The columns and the rows are named by the conventions of the notes twin, not found in the data:
 *
 *   heading: column              the section heading (default: the file's first text column)
 *   text: column                 the section body (default: the text column of the most bytes, by the footer's sizes)
 *   where: column=a|b; column=c  keep the rows whose column is one of the values, every condition holding
 *
 * A selection larger than [ROWS] is refused, never cut. Only the columns named are read from the file.
 */
object ParquetText {
    const val ROWS = 5000

    /** The bytes a column's chunks hold once decompressed. */
    fun weight(footer: ParquetFooter, column: ParquetColumn): Long =
        footer.chunks.filter { it.column.path == column.path }.fold(0L) { sum, chunk -> sum + chunk.uncompressed }

    fun of(file: File, conventions: Map<String, String>): String = RandomAccessFile(file, "r").use { raf ->
        val parquet = ParquetFile(raf.length(), { offset, length -> ByteArray(length).also { raf.seek(offset); raf.readFully(it) } }, parquetInflate)
        val footer = parquet.footer
        val textual = footer.columns.filter { it.text }
        require(textual.size > 0) { "${file.name}: no text column" }
        val weighed = textual α { column -> column j weight(footer, column) }
        val widest = weighed.fold(weighed[0]) { best, next -> if (next.b > best.b) next else best }.a
        val heading = parquet.column(conventions["heading"] ?: textual[0].path)
        val body = parquet.column(conventions["text"] ?: widest.path)
        val conditions = SeriesBuffer<Where>()
        conventions["where"].orEmpty().split(';').toSeries().forEach { condition ->
            if (condition.isNotBlank())
                conditions.add(parquet.column(condition.substringBefore('=').trim()) j (condition.substringAfter('=').split('|').toSeries() α { it.trim() }))
        }
        val selected = IntColumn()
        (0 until footer.rows.toInt()).toSeries().forEach { row ->
            if (!body[row]?.toString().isNullOrBlank() && conditions.fold(true) { all, condition -> all && condition.admits(row) }) selected.add(row)
        }
        require(selected.n <= ROWS) { "${file.name}: ${selected.n} rows selected, over the $ROWS one document carries: narrow them with `where:` in the notes twin" }
        val out = StringBuilder("# ${file.name}\n\n")
        selected.series.forEach { row ->
            out.append("## ").append(heading[row]?.toString()?.replace('\n', ' ')?.trim() ?: "row $row").append("\n\n")
                .append(body[row].toString().trim()).append("\n\n")
        }
        out.toString()
    }
}
