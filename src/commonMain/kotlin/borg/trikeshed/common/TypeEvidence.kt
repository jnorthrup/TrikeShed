package borg.trikeshed.common

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.MapTypeMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.SeqTypeMemento
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.cursor.label
import borg.trikeshed.cursor.joins
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Join
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
    /**
     * Non-conditional character class determination via lookup table + bitmask extraction.
     * Each input char maps to exactly one single-bit category (XOR); the counter update
     * is a straight-line extract-and-add with no branches.
     */
    operator fun plus(char: Char): TypeEvidence = apply {
        val cat = if (char.code < CHAR_CATEGORY.size) CHAR_CATEGORY[char.code] else CAT_SPECIAL
        digits = (digits + (cat and CAT_DIGIT).toUInt()).toUShort()
        periods = (periods + ((cat and CAT_PERIOD) shr 1).toUInt()).toUShort()
        exponent = (exponent + ((cat and CAT_EXPONENT) shr 2).toUInt()).toUShort()
        signs = (signs + ((cat and CAT_SIGN) shr 3).toUInt()).toUShort()
        truefalse = (truefalse + ((cat and CAT_TRUEFALSE) shr 4).toUInt()).toUShort()
        alpha = (alpha + ((cat and CAT_ALPHA) shr 5).toUInt()).toUShort()
        dquotes = (dquotes + ((cat and CAT_DQUOTE) shr 6).toUInt()).toUShort()
        quotes = (quotes + ((cat and CAT_QUOTE) shr 7).toUInt()).toUShort()
        backslashes = (backslashes + ((cat and CAT_BACKSLASH) shr 8).toUInt()).toUShort()
        whitespaces = (whitespaces + ((cat and CAT_WHITESPACE) shr 9).toUInt()).toUShort()
        special = (special + ((cat and CAT_SPECIAL) shr 10).toUInt()).toUShort()
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
            val digits = typeEvidence.digits.toUInt()
            val periods = typeEvidence.periods.toUInt()
            val exponent = typeEvidence.exponent.toUInt()
            val signs = typeEvidence.signs.toUInt()
            val special = typeEvidence.special.toUInt()
            val maxColumnLength = typeEvidence.maxColumnLength.toUInt()

            return when {
                typeEvidence.dquotes > 0U || typeEvidence.quotes > 0U -> IOMemento.IoString
                typeEvidence.empty > 0U || typeEvidence.alpha > 0U -> IOMemento.IoString
                typeEvidence.truefalse > 0U -> IOMemento.IoBoolean
                digits == 0U -> IOMemento.IoString
                periods == 0U && exponent == 0U && signs <= 1U && special == 0U && maxColumnLength <= 3U + signs -> IOMemento.IoByte
                periods == 0U && exponent == 0U && signs <= 1U && special == 0U && maxColumnLength <= 5U + signs -> IOMemento.IoShort
                periods == 0U && exponent == 0U && signs <= 1U && special == 0U && maxColumnLength <= 10U + signs -> IOMemento.IoInt
                periods == 0U && exponent == 0U && signs <= 1U && special == 0U && maxColumnLength <= 19U + signs -> IOMemento.IoLong
                periods == 1U && exponent == 0U && signs <= 1U && special == 0U && maxColumnLength <= 34U + signs + exponent -> IOMemento.IoFloat
                periods == 1U && exponent <= 1U && signs <= 1U && special == 0U && maxColumnLength <= 66U + signs + exponent -> IOMemento.IoDouble
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

/** Non-conditional character class determination — single-bit (XOR) encoding */
private const val CAT_DIGIT = 1        // bit 0
private const val CAT_PERIOD = 2       // bit 1
private const val CAT_EXPONENT = 4     // bit 2
private const val CAT_SIGN = 8         // bit 3
private const val CAT_TRUEFALSE = 16   // bit 4
private const val CAT_ALPHA = 32       // bit 5
private const val CAT_DQUOTE = 64      // bit 6
private const val CAT_QUOTE = 128      // bit 7
private const val CAT_BACKSLASH = 256  // bit 8
private const val CAT_WHITESPACE = 512 // bit 9
private const val CAT_SPECIAL = 1024   // bit 10

/** Pre-computed lookup table: each ASCII char maps to exactly one single-bit category. */
private val CHAR_CATEGORY: IntArray = IntArray(128) { c ->
    when (c.toChar()) {
        in '0'..'9' -> CAT_DIGIT
        '.' -> CAT_PERIOD
        'e', 'E' -> CAT_EXPONENT
        '+', '-' -> CAT_SIGN
        't', 'r', 'u', 'f', 'a', 'l', 's', 'T', 'R', 'U', 'F', 'A', 'L', 'S' -> CAT_TRUEFALSE
        in 'a'..'z', in 'A'..'Z' -> CAT_ALPHA
        '"' -> CAT_DQUOTE
        '\'' -> CAT_QUOTE
        '\\' -> CAT_BACKSLASH
        ' ', '\t' -> CAT_WHITESPACE
        '\r', ',', '\n' -> 0
        else -> CAT_SPECIAL
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
    val meta: Series< () -> ColumnMeta> = TYPE_EVIDENCE_COLUMNS.size j { index: Int -> { TYPE_EVIDENCE_COLUMNS[index] } }
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
