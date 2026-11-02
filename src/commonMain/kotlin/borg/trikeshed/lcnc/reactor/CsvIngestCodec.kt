package borg.trikeshed.lcnc.reactor

import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.collections.associative.PropertyType
import borg.trikeshed.lcnc.collections.associative.PropertyValue
import borg.trikeshed.lcnc.isam.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.csv.CSVUtil

/** Source coordinates are one-based record/column, half-open UTF-16 offsets. */
data class CsvCellSource(
    val sourceCid: String,
    val format: String,
    val parserVersion: Int,
    val record: Int,
    val column: Int,
    val recordBegin: Int,
    val recordEnd: Int,
    val begin: Int,
    val end: Int,
    val headerBegin: Int,
    val headerEnd: Int,
)

class CsvIngestCodec(
    private val maxRows: Int = 100_000,
    private val maxFieldChars: Int = 1_048_576,
    private val maxColumns: Int = 1024,
    private val maxInputChars: Int = 16_777_216,
) : IngestCodec {
    override val supportedFormats: Set<IngestFormat> = _s[IngestFormat.CSV, IngestFormat.TSV]

    override suspend fun decodeText(text: String, format: IngestFormat): Series<LcncEntity> {
        require(format in supportedFormats) { "Unsupported delimited format: $format" }
        require(maxRows in 1 until Int.MAX_VALUE)
        val rows = CSVUtil.records(text, if (format == IngestFormat.CSV) ',' else '\t',
            maxRows + 1, maxFieldChars, maxColumns, maxInputChars)
        if (rows.a == 0) return emptySeriesOf()
        val headers = rows[0].cells
        val names = mutableSetOf<String>()
        for (header in headers.view) {
            require(header.value.isNotBlank()) { "Missing CSV header at ${header.begin}" }
            require(names.add(header.value)) { "Duplicate CSV header: ${header.value}" }
        }
        val sourceCid = ContentId.of(text.encodeToByteArray()).value
        // Format participates in import identity; sourceCid always identifies the original text bytes.
        val databaseId = "db-${ContentId.of((format.name + "\n" + sourceCid).encodeToByteArray()).value}"
        val ids = mutableSetOf<String>()
        val pages = mutableListOf<LcncPage>()
        for (i in 1 until rows.a) {
            val row = rows[i]
            require(row.cells.a == headers.a) { "CSV record ${i + 1}: expected ${headers.a} cells, got ${row.cells.a}" }
            fun cell(name: String): String? {
                for (column in 0 until headers.a) if (headers[column].value == name) return row.cells[column].value
                return null
            }
            val id = cell("id") ?: cell("title") ?: "$databaseId/row-$i"
            require(id.isNotBlank()) { "Empty CSV page id at record ${i + 1}" }
            require(ids.add(id)) { "Duplicate CSV page id: $id" }
            require(id != databaseId) { "CSV page id collides with database id" }
            val blocks = headers.a j { column: Int ->
                val value = row.cells[column]
                val blockId = "$databaseId/row-$i/column-$column"
                val source = CsvCellSource(sourceCid, format.name, 1, i + 1, column + 1,
                    row.begin, row.end, value.begin, value.end, headers[column].begin, headers[column].end)
                LcncBlock(blockId, "property", id,
                    children = 1 j { LcncBlock("$blockId/source", "csv_source", blockId, content = source) },
                    content = PropertyValue(headers[column].value, PropertyType.TEXT, value.value))
            }
            pages.add(LcncPage(id, cell("title") ?: id, databaseId, blocks))
        }
        val db = LcncDatabase(databaseId,
            "Imported Database (Columns: ${headers.view.joinToString(", ") { it.value }})", null, pages.toSeries())
        return 1 j { db }
    }
}
