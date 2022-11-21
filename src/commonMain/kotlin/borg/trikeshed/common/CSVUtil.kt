@file:Suppress("DEPRECATION")

package borg.trikeshed.common

import borg.trikeshed.common.TypeEvidence.Companion.deduce
import borg.trikeshed.common.collections.s_
import borg.trikeshed.common.parser.simple.CharSeries
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IOMemento.IoCharBuffer
import borg.trikeshed.lib.*
import borg.trikeshed.parse.DelimitRange
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

//legal java symol name identifier characters are: 0-9, a-z, A-Z, $, _, and unicode characters in the general category "Letter" or "Number"


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
                        //test capacity first
                        if (ordinal >= lineEvidence.size)
                            lineEvidence.add(TypeEvidence())
                        if (x == since)
                            this[ordinal].empty++
                    }
                    rlist.add(DelimitRange.of(since.toUShort(), (++x).toUShort()).value)
                    since = x
                    lineEvidence?.apply {
                        this[ordinal].columnLength = (since - x).toUShort().also {
                            if (0U.toUShort() == it) {
                                //test capacity first
                                if (ordinal >= lineEvidence.size)
                                    lineEvidence.add(TypeEvidence())
                                this[ordinal].empty++
                            }
                        }
                        ordinal++
                    }
                    if (since != x) // add the last one
                        rlist.add(DelimitRange.of(since.toUShort(), x.toUShort().inc()).value)
                }

                char == '\r' || char == '\n' ->
                    if (!quote && !doubleQuote)
                        break
            }
            x++
        }
        lineEvidence?.apply {
            //test capacity first
            if (ordinal >= lineEvidence.size)
                lineEvidence.add(TypeEvidence())
            this[ordinal].columnLength =
                (since - x).toUShort().also {
                    if (0U.toUShort() == it) this[ordinal].empty++
                }
            ordinal++
        }
        if (since != x) // add the last one
            rlist.add(DelimitRange.of(since.toUShort(), x.toUShort().inc()).value)

        val compactArr = (rlist α { DelimitRange(it).value }).toArray()
        return compactArr α { DelimitRange(it) } //the b element of the last delim range is the end of the line
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

    /**
     * read a csv file into a series of segments and return the series of segments as
     * a series of byte arrays with Meta pointing to CharBuffer of the line
     */
    fun parseSegments(
        file: LongSeries<Byte>,
        /** if list is passed in here it will be the per-file deduce containing the max of a
         * given column classes found in the file
         */
        fileEvidence: MutableList<TypeEvidence>? = null,
    ): Cursor = file.size.let { upperBound ->
        //parse in the headers
        val header: Series<DelimitRange> = parseLine(file, 0, upperBound)
        val headerNames: Series<String> = header α { delimR: DelimitRange -> file[delimR.asIntRange].asString() }
        val lines = mutableListOf<Join<Long, IntArray>>()

        header.last().b.toLong().let { datazero2 ->
            var datazero1 = datazero2

            do {
                val line: Series<DelimitRange> =
                    parseLine(file.drop(datazero1), 0, file.drop(datazero1).size, fileEvidence)
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

            val conversionSegments = fileEvidence?.α { evidence ->
                val deduce: IOMemento = deduce(evidence)
                deduce j (deduce.networkSize ?: evidence.columnLength.toInt())
            }
            val convertedSegmentLengths =conversionSegments?.right?.toArray()

            val successorMeta: List<RecordMeta>? = convertedSegmentLengths?.let {
                it.indices.map { x ->
                    RecordMeta (
                        name = headerNames[x],
                        type = conversionSegments.left[x],
                        begin=if(x==0) 0 else it[x-1],
                        end=it[x]
                    )
                }
            }


            lines α { line ->
                //y axis here

                val lserr: Series<Byte> = file.drop(line.a  )[0 until line.b.size]
                line.b.withIndex() α { (x, b) ->
                    //x axis here

                    val delimitRange = DelimitRange(b)
                    CharSeries(lserr[delimitRange.asIntRange].decodeUtf8()) j {
                        RecordMeta(
                            headerNames[x],
                            IoCharBuffer,
                            delimitRange.a.toInt(),
                            delimitRange.b.toInt(),
                            child = successorMeta?.get(x)//this is an ISAM schema
                        )
                    }
                }
            }
        }
    }
}

