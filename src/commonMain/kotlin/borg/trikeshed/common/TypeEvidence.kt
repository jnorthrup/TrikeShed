package borg.trikeshed.common

import borg.trikeshed.isam.meta.IOMemento

data class
/** This is a dragnet for a given line to record the coutners of character classes */
TypeEvidence(
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
    /**the length of the column */
    var columnLength: UShort = 0U,
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

    companion object {

        /**
         * based on the process of eliminating illegal and oversized counters we deduce the most specific numerical primitives specializations of IoMemento jvm-primitives
         *
         * maximum chars in a boolean range would be: 5 and limitted to true or false
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
                typeEvidence.empty > 0U || typeEvidence.alpha > 0U -> IOMemento.IoString
                typeEvidence.truefalse > 0U -> IOMemento.IoBoolean
                typeEvidence.digits.toUInt() == 0U -> IOMemento.IoString
                typeEvidence.periods.toUInt() == 0U
                        && typeEvidence.exponent.toUInt() == 0U
                        && typeEvidence.signs.toUInt() <= 1U
                        && typeEvidence.special.toUInt() == 0U
                        && typeEvidence.columnLength <= 3U +typeEvidence.signs-> IOMemento.IoByte

                typeEvidence.periods.toUInt() == 0U
                        && typeEvidence.exponent.toUInt() == 0U
                        && typeEvidence.signs.toUInt() <= 1U
                        && typeEvidence.special.toUInt() == 0U
                        && typeEvidence.columnLength <= 5U +typeEvidence.signs-> IOMemento.IoShort

                typeEvidence.periods.toUInt() == 0U
                        && typeEvidence.exponent.toUInt() == 0U
                        && typeEvidence.signs.toUInt() <= 1U
                        && typeEvidence.special.toUInt() == 0U
                        && typeEvidence.columnLength <= 10U +typeEvidence.signs-> IOMemento.IoInt

                typeEvidence.periods.toUInt() == 0U
                        && typeEvidence.exponent.toUInt() == 0U
                        && typeEvidence.signs.toUInt() <= 1U
                        && typeEvidence.special.toUInt() == 0U
                        && typeEvidence.columnLength <= 19U +typeEvidence.signs-> IOMemento.IoLong

                typeEvidence.periods.toUInt() == 1U
                        && typeEvidence.exponent.toUInt() == 0U
                        && typeEvidence.signs <= 1U
                        && typeEvidence.special.toUInt() == 0U
                        && typeEvidence.columnLength <= 34U +typeEvidence.signs+ typeEvidence.exponent.toUInt() -> IOMemento.IoFloat

                typeEvidence.periods.toUInt() == 1U
                        && typeEvidence.exponent <= 1U
                        && typeEvidence.signs <= 1U
                        && typeEvidence.special.toUInt() == 0U
                        && typeEvidence.columnLength <= 66U +typeEvidence.signs+ typeEvidence.exponent.toUInt() -> IOMemento.IoDouble

                else -> IOMemento.IoString
            }
        }

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
                        if (columnLength < typeDeduction.columnLength) columnLength = typeDeduction.columnLength
                    }
                }
            }
        }
    }
}
