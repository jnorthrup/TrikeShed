package borg.trikeshed.lcnc.reactor

import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

class MarkdownIngestCodec : IngestCodec {
    override val supportedFormats: Set<IngestFormat> = setOf(IngestFormat.MARKDOWN)

    override suspend fun decodeText(text: String, format: IngestFormat): Series<LcncEntity> {
        val lines = text.lines()
        val headerRegex = Regex("^(#+)\\s+(.+)$")
        
        return lines.size j { i ->
            val line = lines[i]
            val match = headerRegex.matchEntire(line.trim())
            if (match != null) {
                val level = match.groupValues[1].length
                val title = match.groupValues[2].trim()
                val id = title.lowercase().replace(Regex("[^a-z0-9]+"), "-")
                
                LcncBlock(
                    id = id,
                    type = "heading_$level",
                    parentId = "root",
                    content = title
                )
            } else {
                LcncBlock(
                    id = "p-$i",
                    type = "paragraph",
                    parentId = "root",
                    content = line
                )
            }
        }
    }
}
