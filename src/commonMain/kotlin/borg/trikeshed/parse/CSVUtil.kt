package borg.trikeshed.parse

import borg.trikeshed.common.*
import borg.trikeshed.cursor.*
import borg.trikeshed.io.*
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlin.collections.take

/** forward scanner of commas, quotes, and newlines
 */
object CSVUtil {
    /**
     * Represents the state of parsing a CSV line.
     *
     * @property segments The list of `DelimitRange` representing the segments in the parsed line.
     * @property currentStart The current start position in the file.
     * @property inQuote Indicates if the parser is currently inside a single quote.
     * @property inDoubleQuote Indicates if the parser is currently inside a double quote.
     * @property isEscaped Indicates if the current character is escaped.
     * @property evidence An optional mutable list of `TypeEvidence` to collect evidence about the types of columns.
     * @property currentOrdinal The current ordinal position in the file.
     */
    data class ParseState(
        val segments: List<DelimitRange> = emptyList(),
        val currentStart: Int = 0,
        val inQuote: Boolean = false,
        val inDoubleQuote: Boolean = false,
        val isEscaped: Boolean = false,
        val evidence: MutableList<TypeEvidence>? = null,
        val currentOrdinal: Int = 0,
    )


}