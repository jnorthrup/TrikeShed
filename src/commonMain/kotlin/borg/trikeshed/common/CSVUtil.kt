@file:Suppress("DEPRECATION", "UNCHECKED_CAST")

package borg.trikeshed.common

import borg.trikeshed.cursor.*
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.lib.*


/**
 * a versatile range of two unsigned shorts, now using Twint for consistency
 */
typealias DelimitRange = Twin<Int> //beginInclusive, endInclusive


/** forward scanner of commas, quotes, and newlines
 */
object CSVUtil {
    data class ParseState(
        val segments: List<DelimitRange> = emptyList(),
        val currentStart: Int = 0,
        val inQuote: Boolean = false,
        val inDoubleQuote: Boolean = false,
        val isEscaped: Boolean = false,
        val evidence: MutableList<TypeEvidence>? = null,
        val currentOrdinal: Int = 0,
    )

    fun parseLine(
        file: LongSeries<Byte>,
        start: Long,
        end: Long = -1L,
        lineEvidence: MutableList<TypeEvidence>? = null,
    ): List<DelimitRange> {
        val effectiveEnd = if (end == -1L) file.size else end
        val initialStart = file.drop(start)./*toSeries().*/`▶`.takeWhile { it.toInt().toChar().isWhitespace() }.count()
            .let { start + it }.toInt()

        return file.drop(start).`▶`.take((effectiveEnd - start).toInt()).`▶`.foldIndexed(
            ParseState(
                currentStart = initialStart, evidence = lineEvidence
            )
        ) { index, state, byte ->
            val char = byte.toInt().toChar()

            // Update evidence if needed
            state.evidence?.let { evidence ->
                if (state.currentOrdinal >= evidence.size) {
                    evidence.add(TypeEvidence())
                }
                evidence[state.currentOrdinal] + char
            }

            when {
                state.isEscaped -> state.copy(isEscaped = false)
                char == '"' -> state.copy(inDoubleQuote = !state.inDoubleQuote)
                char == '\'' -> state.copy(inQuote = !state.inQuote)
                char == '\\' -> state.copy(isEscaped = true)
                char == ',' && !state.inQuote && !state.inDoubleQuote -> {
                    val currentIndex = (start + index).toInt()
                    val newSegment = Twin(state.currentStart, currentIndex)

                    state.evidence?.let { evidence ->
                        if (state.currentStart == currentIndex) {
                            evidence[state.currentOrdinal].empty++
                        } else {
                            evidence[state.currentOrdinal].columnLength =
                                (currentIndex - state.currentStart).toUShort()
                        }
                    }

                    state.copy(
                        segments = state.segments + newSegment,
                        currentStart = currentIndex + 1,
                        currentOrdinal = state.currentOrdinal + 1
                    )
                }

                char == '\r' || char == '\n' || index == (effectiveEnd - start - 1).toInt() -> {
                    val currentIndex = (start + index).toInt()
                    val newSegment = state.currentStart j currentIndex

                    state.evidence?.let { evidence ->
                        if (state.currentStart == currentIndex) {
                            evidence[state.currentOrdinal].empty++
                        } else {
                            evidence[state.currentOrdinal].columnLength =
                                (currentIndex - state.currentStart).toUShort()
                        }
                    }

                    state.copy(segments = state.segments + newSegment)
                }

                else -> state
            }
        }.segments
    }


    /**
     * this will do a best-attempt at using the parseSegments output to marshal the types of newMeta passed in.
     *  the meta encode functions of the newMeta must be aligned with CharBuf input of the parseSegments output to
     *  utilize String-ish conversions implied by CSV data
     */
    fun parseConformant(
        file: LongSeries<Byte>,
        newMeta: Series<RecordMeta>? = null,
        fileEvidence: MutableList<TypeEvidence>? = mutableListOf(),
    ): Cursor {
        //first we call parseSegments with our fileEvidence then we trap the RecordMeta child types as a separate meta,
        // then we use the CharSeries cursor features to create a String marshaller per column
        val segments = parseSegments(file, fileEvidence)
        val meta = (newMeta ?: (segments.meta α { (it as RecordMeta).child!! })).debug {
            val l = it.toList()
            logDebug { "parseConformantmeta: $l" }
        }
        return segments.size j { y: Int ->
            segments.row(y).let { rv: RowVec ->
                rv.size j { x: Int ->
                    val recordMeta = meta[x]
                    val type = recordMeta.type
                    val any = rv[x].a
                    try {
                        val fromChars = type.fromChars(any as CharSeries)
                        val function = recordMeta.`↺`
                        fromChars j function
                    } catch (e: Exception) {
                        log { "parseConformant: $e col $x row $y " }
                        throw e
                    }
                }
            }
        }
    }


    /**
     * read a csv file into a series of segments and return the series of segments as
     * a series of byte arrays with Meta pointing to CharBuffer of the line.
     *
     * this trades 64 bit indexes for seek
     *
     */
    fun parseSegments(
        file: LongSeries<Byte>,
        /** if list is passed in here it will be the per-file deduce containing the max of a
         * given column classes found in the file
         */
        fileEvidence: MutableList<TypeEvidence>? = null,
    ): Cursor {
        CharSeries.unbrace()