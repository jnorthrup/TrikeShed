@file:Suppress("DEPRECATION", "UNCHECKED_CAST")

package borg.trikeshed.common

import borg.trikeshed.common.TypeEvidence.Companion.deduce
import borg.trikeshed.common.TypeEvidence.Companion.update
import borg.trikeshed.cursor.*
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.lib.*
import kotlin.jvm.JvmOverloads

/**
 * a versatile range of two unsigned shorts
 */
typealias DelimitRange = Twin<UShort>


/** forward scanner of commas, quotes, and newlines
 */
object CSVUtil {
    data class ParseState(
        val segments: List<DelimitRange> = emptyList(),
        val currentStart: UShort = 0u,
        val inQuote: Boolean = false,
        val inDoubleQuote: Boolean = false,
        val isEscaped: Boolean = false,
        val evidence: MutableList<TypeEvidence>? = null,
        val currentOrdinal: Int = 0
    )
    fun parseLine(
        file: LongSeries<Byte>,
        start: Long,
        end: Long = -1L,
        lineEvidence: MutableList<TypeEvidence>? = null
    ): List<DelimitRange> {
        val effectiveEnd = if (end == -1L) file.size else end
        val initialStart = file.drop(start)
            .takeWhile { it.toInt().toChar().isWhitespace() }
            .count()
            .let { start + it }
            .toUShort()

        return file.asSequence()
            .drop(start.toInt())
            .take((effectiveEnd - start).toInt())
            .foldIndexed(
                ParseState(
                    currentStart = initialStart,
                    evidence = lineEvidence
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
                        val currentIndex = (start + index).toUShort()
                        val newSegment = DelimitRange(state.currentStart, currentIndex)
                        
                        state.evidence?.let { evidence ->
                            if (state.currentStart.toInt() == currentIndex.toInt()) {
                                evidence[state.currentOrdinal].empty++
                            } else {
                                evidence[state.currentOrdinal].columnLength = 
                                    (currentIndex.toInt() - state.currentStart.toInt()).toUShort()
                            }
                        }

                        state.copy(
                            segments = state.segments + newSegment,
                            currentStart = (currentIndex + 1u).toUShort(),
                            currentOrdinal = state.currentOrdinal + 1
                        )
                    }
                    char == '\r' || char == '\n' || index == (effectiveEnd - start - 1).toInt() -> {
                        val currentIndex = (start + index).toUShort()
                        val newSegment = DelimitRange(state.currentStart, currentIndex)
                        
                        state.evidence?.let { evidence ->
                            if (state.currentStart.toInt() == currentIndex.toInt()) {
                                evidence[state.currentOrdinal].empty++
                            } else {
                                evidence[state.currentOrdinal].columnLength = 
                                    (currentIndex.toInt() - state.currentStart.toInt()).toUShort()
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
        return file.size.let { upperBound: Long ->
            //parse in the headers
            val hdrParsRes: Series<Join<UShort, UShort>> = parseLine(file, 0, upperBound).toSeries()
            val headerNames =
                hdrParsRes α { delimR: DelimitRange ->
                    val a1 = delimR.a.toLong()
                    val inc = delimR.b.inc().toLong()
                    val join: LongSeries<Byte> = file[a1 until inc]
                    CharSeries(join.toSeries().decodeUtf8()).asString()
                }
            logDebug { "headerNames: ${headerNames.toList()}" }
            val lines: MutableList<Join<Long, Series<DelimitRange>>> = mutableListOf()
            val last1: DelimitRange = hdrParsRes.last();
            var datazero1: Long = last1.b.toUInt().toLong();

            do {
                val file1: LongSeries<Byte> = file.drop(datazero1)
                if (file1.size < headerNames.size) break  // we can parse n commas as n+1 default fields but no less
                val lineEvidence: MutableList<TypeEvidence>? = fileEvidence?.let { mutableListOf() }
                val parsRes = parseLine(file1, 0, file1.size, lineEvidence)
                lineEvidence?.apply { fileEvidence.update(lineEvidence) }
                val dstart: Long = datazero1
                datazero1 += parsRes.last().b.toLong()
                if (parsRes.size != hdrParsRes.size) {
                    logDebug { "line.size: ${parsRes.size}" }
                    logDebug { "header.size: ${hdrParsRes.size}" }
                    logDebug { "headerNames: ${headerNames.toList()}" }
                    logDebug { "line: ${parsRes α DelimitRange::pair}" }
                    logDebug { "fileStart/End: $datazero1/${file.size}" }
                    throw Exception("line segments does not match header count")
                }
                val joinMutableList = parsRes
                lines.add(/*dstart j joinMutableList.toSeries()*/ (dstart j parsRes.toSeries()) as Join<Long, Series<DelimitRange>>)


            } while (datazero1 < file.size)

            val conversionSegments = (fileEvidence?.α { evidence ->
                val deduce: IOMemento = deduce(evidence)
                deduce j (deduce.networkSize ?: evidence.columnLength.toInt())
            })?.toList()?.toSeries()
            val convertedSegmentLengths = conversionSegments?.right?.toArray()

            //perform the length additions of the segment lengths to arrive at DelimitRanges
val convertedSegments = convertedSegmentLengths?.fold(mutableListOf()) { acc: MutableList<DelimitRange>, length  ->
    val last = acc.lastOrNull()?.b ?: 0.toUShort()
    acc.add(DelimitRange(last, (last + length ).toUShort() ))
    acc
}

            /**this meta will be the child layout for an ISAM promotion of the Cursor*/
            val successorMeta: List<RecordMeta>? = convertedSegmentLengths?.let {
                it.indices.map { x ->
                    RecordMeta(
                        name = headerNames[x],
                        type = conversionSegments.left[x],
                        //begin,end from convertedSegments
                        begin = convertedSegments?.get(x)?.a?.toInt() ?: -1,
                        end = convertedSegments?.get(x)?.b?.toInt() ?: -1,
                    )
                }
            }

            var reporter: FibonacciReporter? = null
            debug { reporter = FibonacciReporter(lines.size) }


            lines α { line ->
                //y axis here

                val lserr: Series<Byte> = file.drop(line.a)[0 until line.b.size]

                (0L until line.a) α { x: Long ->
                    //x axis here


                    val delimitRange = b
                    CharSeries(
                        lserr[delimitRange.first.toInt() until delimitRange.endInclusive.inc().toInt()].decodeUtf8()
                    ) j {
                        val air = delimitRange.asIntRange

                        RecordMeta(
                            headerNames[x],
                            IoCharSeries,
                            air.first,
                            air.last.inc(),
                            child = successorMeta?.get(x)//this is an ISAM schema
                        )
                    }
                }.debug { reporter?.report()?.let { rep -> logDebug { rep } } }
            }

        } as Cursor
    }
}
}


/** list<String>  -> CSV Cursor of strings
 * */
@OptIn(ExperimentalUnsignedTypes::class)
fun simpelCsvCursor(lineList: List<String>): Cursor {
    //take line11 as headers.  the split by ','
    val headerNames = lineList[0].split(",").map { it.trim() }
    val hdrMeta = headerNames.map { RecordMeta(it, IoString) }
    //count of fields
    val fieldCount = headerNames.size
    val lines = lineList.drop(1)
    val lineSegments = arrayOfNulls<UShortArray>(lines.size)

    return lines.size j { y ->
        val line = lines[y]
        //lazily create linesegs
        val lineSegs = lineSegments[y] ?: UShortArray(headerNames.size).also { proto ->
            lineSegments[y] = proto
            var f = 0
            for ((x, c) in line.withIndex()) if (c == ',')
                proto[f++] = x.toUShort()
        }

        fieldCount j { x: Int ->
            val start = if (x == 0) 0 else lineSegs[x - 1].toInt() + 1
            val end = if (x == fieldCount - 1) line.length else lineSegs[x].toInt()
            line.substring(start, end) j hdrMeta[x].`↺`
        }
    }
}
