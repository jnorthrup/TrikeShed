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
     * Parses a CSV string into fields
     * @param text The CSV text to parse
     * @param collectEvidence Whether to collect type evidence for columns
     * @return List of DelimitRange representing the parsed fields
     */
    fun parse(text: String, collectEvidence: Boolean = false): List<DelimitRange> {
        var state = ParseState(evidence = if (collectEvidence) mutableListOf() else null)
        
        text.forEachIndexed { index, c ->
            state = when {
                state.isEscaped -> handleEscaped(state, index)
                c == '\\' -> state.copy(isEscaped = true)
                c == '"' -> handleDoubleQuote(state, index)
                c == '\'' -> handleSingleQuote(state, index)
                c == ',' && !state.inQuote && !state.inDoubleQuote -> 
                    handleComma(state, text, index)
                c == '\n' && !state.inQuote && !state.inDoubleQuote ->
                    handleNewline(state, text, index)
                else -> state
            }
        }
        
        // Handle final segment if exists
        return if (state.currentStart < text.length) {
            val finalRange = DelimitRange(state.currentStart, text.length)
            state.segments + finalRange
        } else state.segments
    }

    private fun handleEscaped(state: ParseState, index: Int): ParseState =
        state.copy(isEscaped = false)

    private fun handleDoubleQuote(state: ParseState, index: Int): ParseState =
        state.copy(inDoubleQuote = !state.inDoubleQuote)

    private fun handleSingleQuote(state: ParseState, index: Int): ParseState =
        state.copy(inQuote = !state.inQuote)

    private fun handleComma(state: ParseState, text: String, index: Int): ParseState {
        val range = DelimitRange(state.currentStart, index)
        collectEvidence(state, text, range)
        return state.copy(
            segments = state.segments + range,
            currentStart = index + 1,
            currentOrdinal = state.currentOrdinal + 1
        )
    }

    private fun handleNewline(state: ParseState, text: String, index: Int): ParseState {
        val range = DelimitRange(state.currentStart, index)
        collectEvidence(state, text, range)
        return ParseState(currentStart = index + 1)
    }

    private fun collectEvidence(state: ParseState, text: String, range: DelimitRange) {
        state.evidence?.let { evidence ->
            val field = text.substring(range.start, range.end).trim()
            if (evidence.size <= state.currentOrdinal) {
                evidence.add(TypeEvidence())
            }
            evidence[state.currentOrdinal].addEvidence(field)
        }
    }

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
