package borg.trikeshed.parse

import borg.trikeshed.common.*
import borg.trikeshed.common.collections._a
import borg.trikeshed.cursor.*
import borg.trikeshed.io.*
import borg.trikeshed.lib.*
import borg.trikeshed.lib.Join.Companion.emptySeriesOf



/** CSV parsing utilities */
object CSVUtil {
    /** Lexer state for building DelimitRange indices */
    sealed class LexState {
        object Field : LexState()
        object QuotedField : LexState()
        object Escaped : LexState()
        object QuotedEscaped : LexState()
        data class DelimiterFound(val pos: Long) : LexState()
        data class RecordEnd(val pos: Long) : LexState()
    }

    /** Index CSV data into DelimitRanges with escape handling */
    fun indexCsv(data: CharSeries): CowSeriesHandle<Join<Long, Series<DelimitRange>>> {
        val ranges: CowSeriesHandle<Join<Long, Series<DelimitRange>>> = emptySeriesOf<Join<Long, Series<DelimitRange>>>().cow
        
        var currentState: LexState = LexState.Field
        var rangeStart = 0L
        var currentRanges = mutableListOf<DelimitRange>()

        // Skip initial whitespace
        data.skipWs

        while(data.hasRemaining) {
            val pos = data.pos.toLong()
            val c = data.get
            
            currentState = when(currentState) {
                is LexState.Field -> when(c) {
                    '\\' -> LexState.Escaped
                    '"' -> LexState.QuotedField
                    '\'' -> LexState.QuotedField
                    ',' -> LexState.DelimiterFound(pos)
                    '\r' -> if (data.hasRemaining && data.get == '\n') 
                             LexState.RecordEnd(pos-1) 
                           else { data.dec(); LexState.Field }
                    '\n' -> LexState.RecordEnd(pos)
                    else -> LexState.Field
                }
                
                is LexState.Escaped -> {
                    // After escape, return to Field state
                    LexState.Field
                }
                
                is LexState.QuotedField -> when(c) {
                    '\\' -> LexState.QuotedEscaped
                    '"', '\'' -> {
                        // Check for double quote/single quote
                        if (data.hasRemaining && data.get == c) {
                            LexState.QuotedField // Escaped quote
                        } else {
                            data.dec() // Back up to process end quote
                            LexState.Field
                        }
                    }
                    else -> LexState.QuotedField
                }
                
                is LexState.QuotedEscaped -> {
                    // After escape in quoted field, return to QuotedField state
                    LexState.QuotedField
                }
                
                is LexState.DelimiterFound -> {
                    // Trim and add completed field range
                    val trimmedRange = trimRange(data, rangeStart, pos)
                    currentRanges.add(trimmedRange)
                    rangeStart = pos + 1
                    // Skip whitespace after delimiter
                    data.skipWs
                    LexState.Field
                }
                
                is LexState.RecordEnd -> {
                    // Trim and add final field of record
                    val trimmedRange = trimRange(data, rangeStart, pos)
                    currentRanges.add(trimmedRange)
                    
                    // Add record to COW series
                    ranges.mutate { it + (pos j currentRanges.toSeries()) }
                    
                    // Reset for next record
                    currentRanges = mutableListOf()
                    rangeStart = pos + 1
                    // Skip whitespace at start of next record
                    data.skipWs
                    LexState.Field
                }
            }
        }

        // Handle final field if any
        if (rangeStart < data.pos) {
            val trimmedRange = trimRange(data, rangeStart, data.pos)
            currentRanges.add(trimmedRange)
            ranges.mutate { it + (data.pos j currentRanges.toSeries()) }
        }

        return ranges
    }

    private fun trimRange(data: CharSeries, start: Long, end: Long): DelimitRange {
        var trimStart = start
        var trimEnd = end
        
        // Trim leading whitespace
        while (trimStart < trimEnd && data[trimStart.toInt()].isWhitespace()) 
            trimStart++
            
        // Trim trailing whitespace
        while (trimEnd > trimStart && data[(trimEnd - 1).toInt()].isWhitespace()) 
            trimEnd--
            
        // Handle quotes
        if (trimEnd - trimStart >= 2) {
            val firstChar = data[trimStart.toInt()]
            val lastChar = data[(trimEnd - 1).toInt()]
            if ((firstChar == '"' && lastChar == '"') || 
                (firstChar == '\'' && lastChar == '\'')) {
                trimStart++
                trimEnd--
            }
        }
        
        return DelimitRange(trimStart, trimEnd)
    }
}

private fun createCursor(data: LongSeries<Byte>, states: UByteArray, collectEvidence: Boolean): Cursor {
    val lines: CowSeriesHandle<Join<Long, Join<Int, (Int) -> Join<UShort, UShort>>>> =
        emptySeriesOf<Join<Long, Series<DelimitRange>>>().cow

    val evidence: Twin<TypeEvidence> = TypeEvidence() j TypeEvidence()
    var c = 0L
    while (data.a > c) {
        val line = CharSeries(data.toSeries().decodeUtf8())

/**
this is the begining of a new file, we need to gather colnames
 *///inline

