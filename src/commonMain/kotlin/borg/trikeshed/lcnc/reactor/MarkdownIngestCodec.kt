package borg.trikeshed.lcnc.reactor

import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

class MarkdownIngestCodec : IngestCodec {
    override val supportedFormats: Set<IngestFormat> = setOf(IngestFormat.MARKDOWN)

    companion object {
        private val HEADER_REGEX = Regex("^(#+)\\s+(.+)$")
        private val ID_CLEAN_REGEX = Regex("[^a-z0-9]+")
    }

    override suspend fun decodeText(text: String, format: IngestFormat): Series<LcncEntity> {
        val lines = text.lines()
        
        return lines.size j { i ->
            val line = lines[i]
            val match = HEADER_REGEX.matchEntire(line.trim())
            if (match != null) {
                val level = match.groupValues[1].length
                val title = match.groupValues[2].trim()
                val id = title.lowercase().replace(ID_CLEAN_REGEX, "-")
                
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
