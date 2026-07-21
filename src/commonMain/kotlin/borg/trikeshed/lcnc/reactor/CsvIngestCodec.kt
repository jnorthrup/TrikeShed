package borg.trikeshed.lcnc.reactor

import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lcnc.isam.LcncPage
import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.emptySeriesOf

class CsvIngestCodec : IngestCodec {
    override val supportedFormats: Set<IngestFormat> = setOf(IngestFormat.CSV, IngestFormat.TSV)

    override suspend fun decodeText(text: String, format: IngestFormat): Series<LcncEntity> {
        val delimiter = if (format == IngestFormat.CSV) "," else "\t"
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptySeriesOf()
        
        val headers = lines.first().split(delimiter).map { it.trim() }
        val inferredColumns = headers.joinToString(", ")
        
        val databaseId = "db-${text.hashCode()}"

        val pages: Series<LcncPage> = (lines.size - 1) j { i: Int ->
            val values = lines[i + 1].split(delimiter).map { it.trim() }
            val props = headers.zip(values).toMap()
            val id = props["id"] ?: props["title"] ?: "row-$i"
            LcncPage(
                id = id,
                title = props["title"] ?: id,
                parentId = databaseId,
                contentBlocks = emptySeriesOf<LcncBlock>()
            )
        }
        
        val db = LcncDatabase(
            id = databaseId,
            title = "Imported Database (Columns: $inferredColumns)",
            parentId = null,
            pages = pages
        )

        return 1 j { db }
    }
}
