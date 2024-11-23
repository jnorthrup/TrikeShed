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
    fun parseLongSeries(series: Series<Long>, collectEvidence: Boolean = true): Cursor {
        val lines = mutableListOf<List<DelimitRange>>()
        val evidence = if (collectEvidence) mutableListOf<TypeEvidence>() else null
        
        var currentLine = mutableListOf<DelimitRange>()
        var state = ParseState(evidence = evidence)
        
        series.forEach { value ->
            val c = value.toInt().toChar()
            state = when {
                state.isEscaped -> handleEscaped(state, state.currentStart)
                c == '\\' -> state.copy(isEscaped = true)
                c == '"' -> handleDoubleQuote(state, state.currentStart) 
                c == '\'' -> handleSingleQuote(state, state.currentStart)
                c == ',' && !state.inQuote && !state.inDoubleQuote -> {
                    val range = DelimitRange(state.currentStart, state.currentStart)
                    collectEvidence(state, value.toString(), range)
                    state.copy(
                        currentStart = state.currentStart + 1,
                        currentOrdinal = state.currentOrdinal + 1
                    )
                }
                c == '\n' && !state.inQuote && !state.inDoubleQuote -> {
                    if (state.currentStart < series.size) {
                        val range = DelimitRange(state.currentStart, state.currentStart)
                        collectEvidence(state, value.toString(), range)
                    }
                    lines.add(currentLine.toList())
                    currentLine = mutableListOf()
                    ParseState(currentStart = state.currentStart + 1, evidence = evidence)
                }
                else -> {
                    // Add character evidence
                    evidence?.let { ev ->
                        while (ev.size <= state.currentOrdinal) ev.add(TypeEvidence())
                        ev[state.currentOrdinal] += c
                    }
                    state
                }
            }
        }

        // Convert collected evidence into column metadata
        val columnMetas = evidence?.map { ev ->
            val type = TypeEvidence.deduce(ev)
            Join("Column${ev.hashCode()}", type.`↺`) 
        } ?: emptyList()

        // Create cursor from lines and metadata
        return size j { y ->
            columnMetas.size j { x ->
                lines[y][x] j columnMetas[x]
            }
        }
    }
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
            while (evidence.size <= state.currentOrdinal) {
                evidence.add(TypeEvidence())
            }
            text.substring(range.start, range.end).forEach { c ->
                evidence[state.currentOrdinal] += c
            }
        }
    }

    fun Series<Long>.toCursor(collectEvidence: Boolean = true): Cursor =
        parseLongSeries(this, collectEvidence)

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
