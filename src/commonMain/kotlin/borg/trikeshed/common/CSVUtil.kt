package borg.trikeshed.common

import borg.trikeshed.common.TypeEvidence.Companion.update
import borg.trikeshed.common.parser.simple.CharSeries
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.PlatformCodec
import borg.trikeshed.lib.*
import borg.trikeshed.parse.DelimitRange
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic


/** forward scanner of commas, quotes, and newlines
 */
object CSVUtil {


    /**
     * read a csv file into a series of segments
     */
    @JvmStatic
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
    ): Series<DelimitRange> {
        var quote = false
        var doubleQuote = false
        var escape = false
        var ordinal = 0
        var x = start
        while (x != end && file[x].toInt().toChar().isWhitespace()) x++ //trim
        var since = x

        val rlist = mutableListOf<Int>()
        val size = file.size
        while (x != end && x < size) {
            val c = file[x]
            val char = c.toInt().toChar()
            lineEvidence?.apply {
                //test deduce length and add if needed
                if (ordinal >= lineEvidence.size) lineEvidence.add(TypeEvidence())
                lineEvidence[ordinal].apply {
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
                        '\r' -> linefeed++
                        else -> special++
                    }
                }
            }
            when {
                escape -> escape = false
                char == '"' -> doubleQuote = !doubleQuote
                char == '\'' -> quote = !quote
                char == '\\' -> escape = !escape
                char == ',' -> if (!quote && !doubleQuote) {
                    lineEvidence?.apply {
                        if (x == since) this[ordinal].empty++
                    }
                    rlist.add(DelimitRange.of(since.toUShort(), (++x).toUShort()).value)
                    since = x
                    ++ordinal
                }

                char == '\r' || char == '\n' -> if (!quote && !doubleQuote) break
            }
            x++
        }
        lineEvidence?.apply {
            this[ordinal].columnLength =
                (since - x).toUShort().also { if (0U.toUShort() == it) this[ordinal].empty++ }
            if (since != x) // add the last one
                rlist.add(DelimitRange.of(since.toUShort(), x.toUShort().inc()).value)
        }

        val compactArr = (rlist α { DelimitRange(it).value }).toArray()
        return compactArr α
                { DelimitRange(it) } //the b element of the last delim range is the end of the line
    }

    /**
     * read a csv file into a series of segments and return the series of segments as a series of byte arrays with Meta pointing to CharBuffer of the line
     */
    fun parseSegments(
        file: LongSeries<Byte>,

        /**
         * if list is passed in here it will be the per-file deduce containing the max of a given column classes found in the file
         */
        fileEvidence: MutableList<TypeEvidence>? = null,
    ): Cursor {
        val size = file.size
        val headerLine: Join<Int, (Int) -> DelimitRange> = parseLine(file, 0)

        debug {
            var c = 0
            for (delimitRange in headerLine) {
                logDebug {
                    "col ${c.also { c++ }} " + file[delimitRange.start.toInt()..delimitRange.endInclusive.toInt()].toArray()
                        .decodeToString()
                }
            }
        }


        //for headers we want to extract Strings from the delimitted reanges.
        val headerNames =
            headerLine α { delim: DelimitRange -> file.get(delim.asIntRange) } α { buf: Series<Byte> ->
                lazy {
                    CharSeries(buf α { theByte: Byte ->
                        theByte.toInt().toChar()
                    }).asString()
                }
            }

        //for untyped CSV data we want to produce a lambda to create RecordMeta of the correct ordinal and IoCharBuf type with the specific bounds
        var relativeLast = headerLine.last().b

        relativeLast.toLong().let { lineStart1: Long ->
            var lineStart: Long = lineStart1
            val lines: MutableList<IntArray> = mutableListOf() //the list of lines
            return try {

                //first pass is to obtain segments for each line into a list
                do {
                    val lineEvidence =
                        fileEvidence?.let { mutableListOf<TypeEvidence>() } //the evidence for this line
                    val line = parseLine(file, lineStart.inc(), lineEvidence = lineEvidence) //the line
                    relativeLast = line.last().b
                    fileEvidence?.apply {
                        update(
                            this,
                            lineEvidence!!
                        )
                    } // update the fileDeduce with the line evidence
                    lineStart += relativeLast.toLong()
                    lines += (line α { it.value }).toArray() //add the line to the list of lines as an array of ints
                } while (lineStart < size) //while there are more lines

                lineStart = lineStart1
                lines α { ints: IntArray ->
                    val lazyLine: Lazy<LongSeries<Byte>> =
                        lineStart.let { beginning -> lazy { file.drop(beginning) } }
                    (ints.toList() α ::DelimitRange).`▶`.withIndex()
                        .toList() α { (x: Int, seg: DelimitRange): IndexedValue<DelimitRange> ->
                        val dec: (ByteArray) -> Any? =
                            PlatformCodec.createDecoder(IOMemento.IoCharBuffer, (seg.b - seg.a).toInt())
                        val value: LongSeries<Byte> = lazyLine.value
                        val bArr: ByteArray = (value[seg.asIntRange]).toArray()
                        dec(bArr)!! j {
                            RecordMeta(
                                headerNames[x].value,
                                IOMemento.IoCharBuffer,
                                seg.a.toInt(),
                                seg.b.toInt(),
                                decoder = dec
                            )
                        }

                    }.also {
                        lineStart += relativeLast.toLong()
                    }
                }
            } catch (e: Exception) {
                //logDebug some state details here
                logDebug { "lineStart: $lineStart" }
                logDebug { "size: $size" }
                logDebug { "headerLine: $headerLine" }
                logDebug { "headerNames: $headerNames" }
                logDebug { "linesCount: $lines.size" }
                throw e
            }
        }

    }


    /**
     * this will do a best-attempt at using the parseSegments output to marshal the types of newMeta passed in.
     *  the meta encode functions of the newMeta must be aligned with CharBuf input of the parseSegments output to
     *  utilize String-ish conversions implied by CSV data
     */
    @OptIn(ExperimentalStdlibApi::class)
    fun parseConformant(
        file: LongSeries<Byte>,
        newMeta: MutableList<RecordMeta>? = null,
        fileEvidence: MutableList<TypeEvidence>? = null,
    ): Cursor {

        val parseSegments = parseSegments(file, fileEvidence)

        //if columnMaxLengths is not null,
        // we need to layout all the newMeta fields, and for the variable length IoMementos of IoString, and IoCharBuffer we need to set the max size; these will be the child meta of the newMeta

        newMeta?.also { newRecordMeta: MutableList<RecordMeta> ->
            fileEvidence?.map { evidence ->
                evidence.columnLength
            }?.also { cmaxLens: List<UShort> ->
                //create an array of networksizes for each column
                val networkSizes = newMeta.mapIndexed { index: Int, recordMeta: RecordMeta ->
                    (recordMeta.child?.type?.networkSize?.toUShort() ?: recordMeta.type.networkSize?.toUShort()
                    ?: cmaxLens[index])
                }

                //accumulate networksizes to create begin,endExclusive  ranges for each column
                val ranges: MutableList<IntRange> =
                    networkSizes.foldIndexed(mutableListOf()) { index, acc: MutableList<IntRange>, i: UShort ->
                        acc += when {
                            0 != index -> {
                                val newStart = acc.last().endExclusive
                                newStart until newStart + i.toInt()
                            }

                            else -> 0 until i.toInt()
                        }
                        acc
                    }

                //install or update the child meta of the newMeta with the new ranges
                newMeta.forEachIndexed { index, recordMeta ->
                    val range: IntRange = ranges[index]
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
                    val bArrDecoder = oldMetaRecord.decoder
                    val cbufEncoder = newMetaRecord.encoder
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

