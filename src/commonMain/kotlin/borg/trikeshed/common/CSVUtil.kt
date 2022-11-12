package borg.trikeshed.common

import borg.trikeshed.common.collections._a
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.parse.DelimitRange
import borg.trikeshed.placeholder.nars.CharBuffer



data class /** This is a dragnet for a given line to record the coutners of character classes */ TypeDeduction (

    var digits:UShort=0U,
    var periods:UShort=0U,
    var exponent:UShort=0U,
    var signs:UShort=0U,
    var special:UShort=0U,
    var alpha:UShort=0U,
    /** the letters in true and false */var truefalse:UShort=0U,
    var empty:UShort=0U,
    var quotes:UShort=0U,
    var dquotes:UShort=0U,
    var whitespaces:UShort=0U,
    var backslashes:UShort=0U,
    var linefeed:UShort=0U,
)


/**
 * forward scanner of commas, quotes, and newlines
 */
object CSVUtil {


    /**
     * read a csv file into a series of segments
     */
    fun parseLine(
        /**the source media*/
        file: LongSeries<Byte>,
        /**the first byte offset inclusive*/
        start: Long,
        /**the last offset exclusive.  -1 has an undefined end. */
        end: Long = -1L,
        //this is 1 TypeDeduction per column, for one line. elsewhere, there should be a TypeDeduction holding maximum findings per file/column.
        deduce:List<TypeDeduction> ?=null
    ): Series<DelimitRange> {
        var quote = false
        var doubleQuote = false
        var escape = false

        var ordinal = 0
        var x = start
        var since = x
        val rlist = mutableListOf<Int>()
        val size = file.size
        while (x != end  && x < size) {
            val c = file.get(x)
            val char = c.toInt().toChar()
            deduce?.apply {
                deduce[ordinal].apply {
                    when (char) {
                        in '0'..'9' -> digits++
                        '.' -> periods++
                        'e', 'E' -> exponent++
                        '+', '-' -> signs++
                        't', 'r', 'u', 'f', 'a', 'l', 's', 'T', 'R', 'U', 'F', 'A', 'L', 'S' -> truefalse++
                        in 'a'..'z', in 'A'..'Z' -> alpha++
                        '"' -> dquotes++
                        '\'' -> quotes++
                        '\\' -> backslashes++
                        ' ', '\t' -> whitespaces++
                        '\r',-> linefeed++
                        else -> special++
                    }
                }
            }

            when {
                escape -> escape = false
                char == '"' -> doubleQuote = !doubleQuote
                char == '\'' -> quote = !quote
                char == '\\' -> escape = !escape
                char == ',' -> if (!quote || !doubleQuote) {
                    deduce?.apply {
                        if(x ==start) this[ordinal].empty++ }
                    rlist.add(DelimitRange.of(since.toUShort(), x.toUShort()).value)
                    since = x.inc()
                    ordinal++
                }

                char in _a['\r', '\n'] -> if (!quote || !doubleQuote) {
                    break
                }
            }
            x++
        }
        if (since != x) // add the last one
            rlist.add(DelimitRange.of(since.toUShort(), x.toUShort()).value)
        val toArray = (rlist α { DelimitRange(it).value }).toArray()

        return toArray α { DelimitRange(it) } //the b element of the last delim range is the end of the line

    }

    /**
     * read a csv file into a series of segments and return the series of segments as a series of byte arrays with Meta pointing to CharBuffer of the line
     */
    fun parseSegments(
        file: LongSeries<Byte>,
        /**
         * when not null the longest ranges are recorded inthis list */
        columnMaxLengths: MutableList<Int>? = null,
        /**
         * if list is passed in here it will be the per-file deduce containing the max of a given column classes found in the file
         */
        deduce :List<TypeDeduction>?=null
    ): Cursor {
        val size = file.size
        val headerLine: Join<Int, (Int) -> DelimitRange> = parseLine(file, 0, deduce = deduce)

        //for headers we want to extract Strings from the delimitted reanges.
        val headerNames: Series<String> =
            headerLine α { file.get(it.asIntRange) } α { CharBuffer(it α { it.toInt().toChar() }).asString() }

        //for untyped CSV data we want to produce a lambda to create RecordMeta of the correct ordinal and IoCharBuf type with the specific bounds


        var lineStart = headerLine.last().b.toLong()
        val lines: MutableList<IntArray> = mutableListOf()
        try {
            do {
                val line: Series<DelimitRange> = parseLine(file, lineStart)
                lineStart += line.last().b.toLong()
                lines += ((line α { it.value }).toArray())
            } while (lineStart < size)
        } catch (e: Exception) {
            //logDebug some state details here
            logDebug { "lineStart: $lineStart" }
            logDebug { "size: $size" }
            logDebug { "headerLine: $headerLine" }
            logDebug { "headerNames: $headerNames" }
            logDebug { "linesCount: $lines.size" }
            throw e
        }
        columnMaxLengths?.also {
            repeat(headerNames.size) { it ->
                columnMaxLengths[it] = 0
            }
        }
        lineStart = headerLine.last().b.toLong()
        val hdrCount = headerNames.size
        /* we cannot use alpha (lazy)conversion of the indices here because we need to use the lineStart variable which is not a parameter of the lambda */

        return lines.mapIndexed { y, segments ->
            require(segments.size == hdrCount) { "line $y has ${segments.size} segments but $hdrCount headers" }
            var range: DelimitRange = DelimitRange.of(0.toUShort(), 0.toUShort())
            val lazyLine: Lazy<LongSeries<Byte>> = lazy { file.drop(lineStart) }//row level lazy byteSeries
            val parsed = segments.indices.toList() α { x ->
                range = DelimitRange(segments[x])
                columnMaxLengths?.also { cmax ->
                    if ((range.b - range.a).toInt() > cmax[x]) cmax[x] = (range.b - range.a).toInt()
                }
                val subFile =
                    lazy { lazyLine.value[range.asIntRange]/*intrange downconverts the LongSeries to Series */ }
                val recordMeta: () -> RecordMeta = {
                    range = DelimitRange(segments[x])
                    RecordMeta(headerNames[x], IOMemento.IoCharBuffer, decoder = {
                        it α { byte: Byte -> byte.toInt().toChar() }
                    })
                }
                subFile j recordMeta
            }
            lineStart += range.b.toLong().inc()
            parsed α { (lazyArray: Lazy<Series<Byte>>, metaFunctor: () -> RecordMeta): Join<Lazy<Series<Byte>>, () -> RecordMeta> ->
                @Suppress("USELESS_CAST")
                (lazyArray.value.toArray() j metaFunctor) as Join<*, () -> RecordMeta>
            }
        }.toSeries()
    }

    /**
     * this will do a best-attempt at using the parseSegments output to marshal the types of newMeta passed in.
     *  the meta encode functions of the newMeta must be aligned with CharBuf input of the parseSegments output to
     *  utilize String-ish conversions implied by CSV data
     */
    @OptIn(ExperimentalStdlibApi::class)
    fun parseConformant(
        file: LongSeries<Byte>,
        newMeta: List<RecordMeta>? = null,
        maxColSizes: Boolean = false,
    ): Cursor {
        val columnMaxLengths: MutableList<Int>? = if (maxColSizes) mutableListOf() else null
        val parseSegments = parseSegments(file, columnMaxLengths)

        //if columnMaxLengths is not null,
        // we need to layout all the newMeta fields, and for the variable length IoMementos of IoString, and IoCharBuffer we need to set the max size; these will be the child meta of the newMeta
        newMeta?.also {
            columnMaxLengths?.also { cmaxLens ->
                //create an array of networksizes for each column
                val networkSizes = newMeta.mapIndexed { index: Int, recordMeta: RecordMeta ->
                    recordMeta.child?.type?.networkSize ?: recordMeta.type.networkSize ?: cmaxLens[index]
                }

                //accumulate networksizes to create begin,endExclusive  ranges for each column
                val ranges: MutableList<IntRange> =
                    networkSizes.foldIndexed(mutableListOf()) { index: Int, acc: MutableList<IntRange>, i: Int ->
                        acc += if (index == 0) 0 until i
                        else {
                            val newStart = acc.last().endExclusive
                            newStart until newStart + i
                        }
                        acc
                    }

                //install or update the child meta of the newMeta with the new ranges
                newMeta.forEachIndexed { index, recordMeta ->
                    val range = ranges[index]
                    recordMeta.child = recordMeta.child?.copy(begin = range.first, end = range.endExclusive)
                        ?: recordMeta.copy(begin = range.first, end = range.endExclusive)
                }
            }
        }
        return parseSegments.`▶`.withIndex() α { (lineNo, segments): IndexedValue<Join<Int, (Int) -> Join<*, () -> RecordMeta>>> ->
            segments.`▶`.withIndex() α { (x: Int, segment: Join<*, () -> RecordMeta>): IndexedValue<Join<*, () -> RecordMeta>> ->
                //newMeta presides over the output of this regment
                val oldMetaRecord: RecordMeta = segment.b()
                val newMetaRecord: RecordMeta = newMeta?.get(x) ?: oldMetaRecord
                try {
                    /*this is an assertion inline with the type system*/
                    val bArr = segment.a as ByteArray

                    @Suppress("UNCHECKED_CAST")
                    val bArrDecoder = oldMetaRecord.decoder as (ByteArray) -> CharBuffer
                    val cbufEncoder = newMetaRecord.encoder as (CharBuffer) -> ByteArray
                    val xlated = cbufEncoder(bArrDecoder(bArr))
                    xlated j (newMetaRecord.child ?: newMetaRecord).`↺`
                } catch (e: Exception) {
                    logDebug { "lineNo: $lineNo" }
                    logDebug { "x: $x" }
                    logDebug { "newMetaRecord: $newMetaRecord" }
                    logDebug { "oldMetaRecord: $oldMetaRecord" }
                    throw e
                }
            }
        }
    }
}
