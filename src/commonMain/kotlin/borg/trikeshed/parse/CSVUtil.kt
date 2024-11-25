package borg.trikeshed.parse

import borg.trikeshed.common.*
import borg.trikeshed.common.collections._a
import borg.trikeshed.cursor.*
import borg.trikeshed.io.*
import borg.trikeshed.lib.*
import borg.trikeshed.lib.Join.Companion.emptySeriesOf



/**bitmap indexer initial state*/
inline class State1(val charSeries: CharSeries) {

    /** state1 -  lowest level, 1 csv value */
    fun invoke() {            //utf8 state requires intext()
        var backslash = 0
        var utfInitiators = 0
        var utfContinuations = 0
        var dquotes = 0

        charSeries.get.toChar().let { c ->
            when {
                c == '\\' ->  apply { backslash++ }
                c == '"' && (backslash % 2) == 0 -> apply { dquotes++ }

                c == ',' || !charSeries.hasRemaining && (utfInitiators % 2 == 0 && utfContinuations % 2 == 0 && backslash % 2 == 0 && dquotes % 2 == 0) -> {
                    //we have a csv value

                    State2_field(charSeries )
                }
                c == '\n' || !charSeries.hasRemaining && (utfInitiators % 2 == 0 && utfContinuations % 2 == 0 && backslash % 2 == 0 && dquotes % 2 == 0) -> {
                    //we have a csv value

                    State3_record(charSeries )
                }

                else -> apply { backslash = 0 ; utfInitiators = 0 ; utfContinuations = 0 ; dquotes = 0 }


            }
        }
    }
}
/**  state2 -  csv value
 * we move from text lexing to recording a feld, or possibly a line.
 *
 * */
inline class State2_field(val charSeries: CharSeries  ) {
    fun invoke() {


    }
}


/** forward scanner of commas, quotes, and newlines
 */
object CSVUtil {


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

