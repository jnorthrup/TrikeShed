package borg.trikeshed.parse

import borg.trikeshed.common.collections.Stack
import borg.trikeshed.cursor.*
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
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

    lateinit var data: ByteSeries

    /** Index CSV data into DelimitRanges with escape handling */
    fun indexCsv(file: LongSeries<Byte>): Cursor {

        data = ByteSeries(file.toSeries(0, file.size.toInt()))

        val ranges: CowSeriesHandle<Join<Long, Series<DelimitRange>>> =
            emptySeriesOf<Join<Long, Series<DelimitRange>>>().cow

        var currentState: LexState = LexState.Field
        val openQuotes = Stack<Char>()
        var rangeStart = 0L
        var curLine = 0L
        val currentRanges = emptySeriesOf<DelimitRange>().cow

        // Skip initial whitespace
        data.skipWs

        while (data.hasRemaining) {
            data = ByteSeries(file.toSeries(data.pos.toLong()))
            val pos = data.pos.toLong()
            val c = data.get
            currentState = when (currentState) {
                is LexState.Field -> when (c.toInt().toChar()) {
                    '\\' -> LexState.Escaped
                    '"' -> LexState.QuotedField
                    '\'' -> LexState.QuotedField
                    ',' -> LexState.DelimiterFound(pos)
                    '\r' -> if (data.hasRemaining && data.get.toChar() == '\n')
                        LexState.RecordEnd(pos - 1)
                    else {
                        data.dec(); LexState.Field
                    }

                    '\n' -> LexState.RecordEnd(pos)
                    else -> LexState.Field
                }

                is LexState.Escaped -> {
                    // After escape, return to Field state
                    LexState.Field
                }

                is LexState.QuotedField -> when (c.toChar()) {
                    '\\' -> LexState.QuotedEscaped
                    '"', '\'' -> {
                        if (openQuotes.isEmpty() || openQuotes.peek().code.toByte() != c) {
                            openQuotes.push(c.toInt().toChar()) // Push to stack when opening a quote
                            LexState.QuotedField
                        } else {
                            openQuotes.pop() // Pop from stack when closing a quote
                            if (data.hasRemaining && data.get == c) {
                                LexState.QuotedField // Escaped quote
                            } else {
                                data.dec() // Back up to process end quote
                                LexState.Field
                            }
                        }
                    }

                    else -> LexState.QuotedField
                }

                is LexState.QuotedEscaped -> {
                    // After escape in quoted field, return to QuotedField state
                    LexState.QuotedField
                }

                is LexState.RecordEnd -> {
                    currentRanges += ((rangeStart - curLine).toUShort() j (pos - rangeStart).toUShort()) // as DelimitRange

                    // Add record to COW series
                    ranges += (pos j currentRanges)

                    // Reset for next record
                    currentRanges.clear()
                    rangeStart = pos + 1
                    // Skip whitespace at start of next record
                    data.skipWs
                    curLine = pos.dec()
                    LexState.Field
                }

                else -> LexState.Field
            }
        }

        // Add final record
        currentRanges += ((rangeStart - curLine).toUShort() j (data.pos - rangeStart).toUShort()) // as DelimitRange

        ranges += (curLine j currentRanges)

        val cMeta: Series<ColumnMeta> by lazy {
            ranges.first().let { (lineStart, delim): Join<Long, Series<DelimitRange>> ->
                val innermetas = delim.firstOrNull()?.let { (start, end): Join<UShort, UShort> ->
                    val (line, range) = ranges.first()
                    val data = file.toSeries(lineStart + start.toInt(), end.toInt())
                    range α {
                        val (start1, len) = it
                        val field = data[start1.toInt() until len.toInt()].asString()
                        RecordMeta(field, IOMemento.IoCharSeries) as ColumnMeta
                    }
                }
                innermetas
            } ?: emptySeriesOf()
        }


        val theC = ranges.a j { y: Int ->
            val (line, delim) = ranges[y]
            delim.a j { x: Int ->
                val (start, len) = delim[x]
                val data = file.toSeries(line + start.toInt(), len.toInt())
                data.decodeUtf8() as Any? j { cMeta[x] as ColumnMeta }
            }
        }
        return theC
    }
}
