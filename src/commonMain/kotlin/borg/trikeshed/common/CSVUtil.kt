@file:Suppress("DEPRECATION", "UNCHECKED_CAST")

package borg.trikeshed.common

import borg.trikeshed.common.TypeEvidence.Companion.deduce
import borg.trikeshed.common.TypeEvidence.Companion.update
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.meta
import borg.trikeshed.cursor.row
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IOMemento.IoCharSeries
import borg.trikeshed.isam.meta.IOMemento.IoString
import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.FibonacciReporter
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.assert
import borg.trikeshed.lib.debug
import borg.trikeshed.lib.decodeUtf8
import borg.trikeshed.lib.first
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.last
import borg.trikeshed.lib.left
import borg.trikeshed.lib.log
import borg.trikeshed.lib.logDebug
import borg.trikeshed.lib.right
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toArray
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.α
import borg.trikeshed.lib.`↺`
import kotlin.jvm.JvmInline
import kotlin.jvm.JvmOverloads

/**
 * a versatile range of two unsigned shorts stored as a 32 bit Int value as Inline class
 */
@JvmInline
value class DelimitRange(val value: Int) : Twin<UShort>, ClosedRange<UShort> {
    //emulates a pair of UShorts using 16 bits for two UShorts
    override val a: UShort get() = (value ushr 16).toUShort()
    override val b: UShort get() = (value and 0xFFFF).toUShort()

    companion object {
        fun of(a: UShort, b: UShort): DelimitRange = DelimitRange((a.toInt() shl 16) or b.toInt())
    }

    override val start: UShort
        get() = a
    override val endInclusive: UShort
        get() = b.dec()

    /**this range is end-exclusive, he UShort range end is inclusive. */
    val asIntRange: IntRange
        get() {
            val endExclusive = b.toInt().inc()
            return (a.toInt() until b.inc().toInt())
        }
}


/** forward scanner of commas, quotes, and newlines
 */
object CSVUtil {
    /**
     * read a csv file into a series of segments
     */
//    @JvmStatic
    @JvmOverloads
    fun parseLine(
        /**the source media*/
        file: LongSeries<Byte>,
        /**the first byte offset inclusive*/
        start: Long,
        /**the last offset exclusive.  -1 has an undefined end. */
        end: Long = -1L,
        //this is 1 TypeDeduction per column, for one line. elsewhere, there should be a TypeDeduction holding maximum findings per file/column.
        lineEvidence: MutableList<TypeEvidence>? = null,
    ):
            /** compressed DelimitRange array as IntArr*/
            IntArray {
        var quote = false
        var doubleQuote = false
        var escape = false
        var ordinal = 0
        var x = start
        while (x != end && file[x].toInt().toChar().isWhitespace()) x++ //trim
        var since = x

        val rlist = mutableListOf<DelimitRange>()
        val size = file.size
        while (x != end && x < size) {
            val c = file[x]
            val char = c.toInt().toChar()
            lineEvidence?.apply {
                //test deduce length and add if needed
                if (ordinal >= lineEvidence.size)
                    lineEvidence.add(TypeEvidence())
                lineEvidence[ordinal] + char
            }
            when {
                escape -> escape = false
                char == '"' -> doubleQuote = !doubleQuote
                char == '\'' -> quote = !quote
                char == '\\' -> escape = !escape
                char == ',' -> if (!quote && !doubleQuote) {
                    val element = DelimitRange.of(since.toUShort(), x.toUShort())
                    rlist.add(element)
// these check out                    logDebug { "val${element.pair}: "+ CharSeries(file[ element.asIntRange ].decodeUtf8()).asString() }
                    lineEvidence?.apply {
                        if (since == x)
                            lineEvidence[ordinal].empty++
                        else lineEvidence[ordinal].columnLength = (x - since).toUShort()
                    }
                    ordinal++
                    since = x + 1
                }

                char == '\r' || char == '\n' || end == x.inc() -> {
                    val element = DelimitRange.of(since.toUShort(), x.toUShort())
                    rlist.add(element)
                    lineEvidence?.apply {
//                        logDebug { "bookend val${element.pair}: " + CharSeries(file[element.asIntRange].decodeUtf8()).asString() }
                        if (since == x)
                            lineEvidence[ordinal].empty++
                        else lineEvidence[ordinal].columnLength = (x - since).toUShort()
                    }
                    break
                }
            }
            x++
        }
        assert(rlist.size > 0)
        return IntArray(rlist.size) { rlist[it].value }
        // what happens specifically in the above code when we pass in a line with no cr/lf in the above code:

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
        val segments: Cursor = parseSegments(file, fileEvidence)
        val meta = (newMeta ?: (segments.meta α { (it as RecordMeta).child!! })).debug {
            logDebug { "parseConformantmeta: ${it.toList()}" }
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
    ): Cursor = file.size.let { upperBound ->
        //parse in the headers
        val hdrParsRes = parseLine(file, 0, upperBound)
        val header = hdrParsRes α ::DelimitRange
        val headerNames =
            header α { delimR: DelimitRange ->
                val join: Series<Byte> = file[delimR.a.toInt() until delimR.b.inc().toInt()]
                CharSeries(join.decodeUtf8()).asString()
            }
        logDebug { "headerNames: ${headerNames.toList()}" }
        val lines: MutableList<Join<Long, IntArray>> = mutableListOf()

        val last1: DelimitRange = header.last()
        val (a, b) = last1
        b.toLong().j{ datazero2:Long ->
            var datazero1 = datazero2

            do {
                val file1: LongSeries<Byte> = file.drop(datazero1)
                if (file1.size < headerNames.size) break  // we can parse n commas as n+1 default fields but no less
                val lineEvidence =
                    fileEvidence?.let<MutableList<TypeEvidence>, MutableList<TypeEvidence>> { mutableListOf() }
                val parsRes = parseLine(file1, 0, file1.size, lineEvidence)
                lineEvidence?.apply { fileEvidence.update(lineEvidence) }
                val line = parsRes α ::DelimitRange
                val dstart: Long = datazero1
                datazero1 += line.last().b.toLong()
                if (line.size != header.size) {
                    logDebug { "line.size: ${line.size}" }
                    logDebug { "header.size: ${header.size}" }
                    logDebug { "headerNames: ${headerNames.toList()}" }
                    logDebug { "line: ${line α DelimitRange::pair}" }
                    logDebug { "fileStart/End: $datazero1/${file.size}" }
                    throw Exception("line segments does not match header count")
                }
                val toArray = (line α { it.value }).toArray()
                lines.add(dstart j toArray)
            } while (datazero1 < file.size)

            val conversionSegments = (fileEvidence?.α { evidence ->
                val deduce: IOMemento = deduce(evidence)
                deduce j (deduce.networkSize ?: evidence.columnLength.toInt())
            })?.toList()?.toSeries()
            val convertedSegmentLengths = conversionSegments?.right?.toArray()

            //perform the length additions of the segment lengths to arrive at DelimitRanges
            val convertedSegments = convertedSegmentLengths?.fold(mutableListOf<DelimitRange>()) { acc, length ->
                val last = acc.lastOrNull()?.b ?: 0.toUShort()
                acc.add(DelimitRange.of(last, (last + length.toUInt()).toUShort()))
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
                line.b.withIndex() α { (x, b): IndexedValue<Int> ->
                    //x axis here

                    val delimitRange = DelimitRange(b)
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