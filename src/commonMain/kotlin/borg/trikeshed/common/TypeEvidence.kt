package borg.trikeshed.common

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.MapTypeMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.SeqTypeMemento
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.cursor.label
import borg.trikeshed.cursor.joins
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.j
import borg.trikeshed.lib.get
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.size

data class
/** This is a dragnet for a given line to record the counters of character classes */
TypeEvidence(
    var confix: String = "",
    var structuralMemento: TypeMemento? = null,
    var digits: UShort = 0U,
    var periods: UShort = 0U,
    var exponent: UShort = 0U,
    var signs: UShort = 0U,
    var special: UShort = 0U,
    var alpha: UShort = 0U,
    /** the letters in true and false */
    var truefalse: UShort = 0U,
    var empty: UShort = 0U,
    var quotes: UShort = 0U,
    var dquotes: UShort = 0U,
    var whitespaces: UShort = 0U,
    var backslashes: UShort = 0U,
    var linefeed: UShort = 0U,
    /**maximum length of the column observed across the file */
    var maxColumnLength: UShort = 0U,
    /**minimum length of the column observed across the file */
    var minColumnLength: UShort = UShort.MAX_VALUE,
) {
    operator fun plus(char: Char): TypeEvidence = apply {
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
            '\r', ',', '\n' -> {}
            else -> special++
        }
    }

    /**
     * Update the column length tracking. Call this when a column value is fully parsed.
     */
    fun recordColumnLength(length: Int) {
        val len = length.toUShort()
        if (len > maxColumnLength) maxColumnLength = len
        if (len < minColumnLength) minColumnLength = len
    }

    companion object {
        fun sample(src: Series<Char>): TypeEvidence =
            TypeEvidence().apply {
                confix = detectConfix(src)
                structuralMemento = detectStructuralMemento(confix)
                for (index in 0 until src.size) {
                    this + src[index]
                }
                recordColumnLength(src.size)
            }

        private fun detectConfix(src: Series<Char>): String {
            if (src.size < 2) return ""
            val first = src[0]
            val last = src[src.size - 1]
            return when {
                first == '{' && last == '}' -> "{}"
                first == '[' && last == ']' -> "[]"
                first == '"' && last == '"' -> "\"\""
                first == '\'' && last == '\'' -> "''"
                else -> ""
            }
        }

        private fun detectStructuralMemento(confix: String): TypeMemento? =
            when (confix) {
                "{}" -> MapTypeMemento
                "[]" -> SeqTypeMemento
                else -> null
            }

        /**
         * based on the process of eliminating illegal and oversized counters we deduce the most specific numerical primitives specializations of IoMemento jvm-primitives
         *
         * maximum chars in a boolean range would be: 5 and limited to true or false
         * presence of alpha would eliminate many types
         * maximum digits in a binary range would be: 1 (0 or 1)
         * maximum digits in a float range would be: 1+1+8+1+23 = 34
         * maximum digits in a double range would be: 1+1+11+1+52 = 66
         * maximum digits in a long range would be: 19
         * maximum digits in a int range would be: 10
         * maximum digits in a short range would be: 5
         * maximum digits in a byte range would be: 3
         */
        fun deduce(typeEvidence: TypeEvidence): IOMemento {

            return when {
                typeEvidence.dquotes > 0U || typeEvidence.quotes > 0U -> IOMemento.IoString
                typeEvidence.empty > 0U || typeEvidence.alpha > 0U -> IOMemento.IoString
                typeEvidence.truefalse > 0U -> IOMemento.IoBoolean
                typeEvidence.digits.toUInt() == 0U -> IOMemento.IoString
                typeEvidence.periods.toUInt() == 0U
                        && typeEvidence.exponent.toUInt() == 0U
                        && typeEvidence.signs.toUInt() <= 1U
                        && typeEvidence.special.toUInt() == 0U
                        && typeEvidence.maxColumnLength <= 3U +typeEvidence.signs-> IOMemento.IoByte

                typeEvidence.periods.toUInt() == 0U
                        && typeEvidence.exponent.toUInt() == 0U
                        && typeEvidence.signs.toUInt() <= 1U
                        && typeEvidence.special.toUInt() == 0U
                        && typeEvidence.maxColumnLength <= 5U +typeEvidence.signs-> IOMemento.IoShort

                typeEvidence.periods.toUInt() == 0U
                        && typeEvidence.exponent.toUInt() == 0U
                        && typeEvidence.signs.toUInt() <= 1U
                        && typeEvidence.special.toUInt() == 0U
                        && typeEvidence.maxColumnLength <= 10U +typeEvidence.signs-> IOMemento.IoInt

                typeEvidence.periods.toUInt() == 0U
                        && typeEvidence.exponent.toUInt() == 0U
                        && typeEvidence.signs.toUInt() <= 1U
                        && typeEvidence.special.toUInt() == 0U
                        && typeEvidence.maxColumnLength <= 19U +typeEvidence.signs-> IOMemento.IoLong

                typeEvidence.periods.toUInt() == 1U
                        && typeEvidence.exponent.toUInt() == 0U
                        && typeEvidence.signs <= 1U
                        && typeEvidence.special.toUInt() == 0U
                        && typeEvidence.maxColumnLength <= 34U +typeEvidence.signs+ typeEvidence.exponent.toUInt() -> IOMemento.IoFloat

                typeEvidence.periods.toUInt() == 1U
                        && typeEvidence.exponent <= 1U
                        && typeEvidence.signs <= 1U
                        && typeEvidence.special.toUInt() == 0U
                        && typeEvidence.maxColumnLength <= 66U +typeEvidence.signs+ typeEvidence.exponent.toUInt() -> IOMemento.IoDouble

                else -> IOMemento.IoString
            }
        }

        fun deduceMemento(typeEvidence: TypeEvidence): TypeMemento = typeEvidence.structuralMemento ?: deduce(typeEvidence)

        fun MutableList<TypeEvidence>.update(
            lineEvidence: MutableList<TypeEvidence>,
        ) {
            apply {
                //update the fileDeduce with the max of the lineDeduce
                lineEvidence.forEachIndexed { index, typeDeduction ->
                    while (index >= size) add(TypeEvidence())
                    this[index].apply {
                        if (digits < typeDeduction.digits) digits = typeDeduction.digits
                        if (periods < typeDeduction.periods) periods = typeDeduction.periods
                        if (exponent < typeDeduction.exponent) exponent = typeDeduction.exponent
                        if (signs < typeDeduction.signs) signs = typeDeduction.signs
                        if (special < typeDeduction.special) special = typeDeduction.special
                        if (alpha < typeDeduction.alpha) alpha = typeDeduction.alpha
                        if (truefalse < typeDeduction.truefalse) truefalse = typeDeduction.truefalse
                        if (empty < typeDeduction.empty) empty = typeDeduction.empty
                        if (quotes < typeDeduction.quotes) quotes = typeDeduction.quotes
                        if (dquotes < typeDeduction.dquotes) dquotes = typeDeduction.dquotes
                        if (whitespaces < typeDeduction.whitespaces) whitespaces = typeDeduction.whitespaces
                        if (backslashes < typeDeduction.backslashes) backslashes = typeDeduction.backslashes
                        if (linefeed < typeDeduction.linefeed) linefeed = typeDeduction.linefeed
                        if (maxColumnLength < typeDeduction.maxColumnLength) maxColumnLength = typeDeduction.maxColumnLength
            if (maxColumnLength < typeDeduction.maxColumnLength) maxColumnLength = typeDeduction.maxColumnLength
            if (minColumnLength > typeDeduction.minColumnLength) minColumnLength = typeDeduction.minColumnLength
                    }
                }
            }
        }
    }
}

fun TypeEvidence.toRowVec(): RowVec {
    val values = arrayOf<Any?>(
        confix,
        digits.toInt(),
        periods.toInt(),
        exponent.toInt(),
        signs.toInt(),
        special.toInt(),
        alpha.toInt(),
        truefalse.toInt(),
        empty.toInt(),
        quotes.toInt(),
        dquotes.toInt(),
        whitespaces.toInt(),
        backslashes.toInt(),
        linefeed.toInt(),
        maxColumnLength.toInt(),
        if (minColumnLength == UShort.MAX_VALUE) 0 else minColumnLength.toInt(),
        TypeEvidence.deduceMemento(this).label,
    )
    val meta = TYPE_EVIDENCE_COLUMNS.size j { index: Int -> { TYPE_EVIDENCE_COLUMNS[index] } }
    return values.size j { index: Int -> values[index] } joins meta
}

private val TYPE_EVIDENCE_COLUMNS = arrayOf(
    ColumnMeta("confix", IOMemento.IoString),
    ColumnMeta("digits", IOMemento.IoInt),
    ColumnMeta("periods", IOMemento.IoInt),
    ColumnMeta("exponent", IOMemento.IoInt),
    ColumnMeta("signs", IOMemento.IoInt),
    ColumnMeta("special", IOMemento.IoInt),
    ColumnMeta("alpha", IOMemento.IoInt),
    ColumnMeta("truefalse", IOMemento.IoInt),
    ColumnMeta("empty", IOMemento.IoInt),
    ColumnMeta("quotes", IOMemento.IoInt),
    ColumnMeta("dquotes", IOMemento.IoInt),
    ColumnMeta("whitespaces", IOMemento.IoInt),
    ColumnMeta("backslashes", IOMemento.IoInt),
    ColumnMeta("linefeed", IOMemento.IoInt),
    ColumnMeta("maxColumnLength", IOMemento.IoInt),
    ColumnMeta("minColumnLength", IOMemento.IoInt),
    ColumnMeta("deducedType", IOMemento.IoString),
)
