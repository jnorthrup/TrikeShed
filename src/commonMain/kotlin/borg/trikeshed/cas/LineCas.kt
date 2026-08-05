package borg.trikeshed.cas

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

data class LineNode(
    val contentCid: ContentId,
    val ordinal: Int
)

fun contentOf(line: String): ContentId =
    ContentId.of(line.trim().encodeToByteArray())

fun spine(text: String): Series<LineNode> {
    val validLines = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    return validLines.size j { i ->
        LineNode(contentOf(validLines[i]), i)
    }
}
