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

// ── XOR scan layer ──────────────────────────────────────────────────────────
// Slot layout: each character-class counter maps to one slot.
// XOR prefix scan is deterministic, nonreversionary, non-conditional.

/** Number of evidence slots — one per character-class counter */
const val EVIDENCE_SLOTS = 14

/** Slot names for column metadata */
val EVIDENCE_SLOT_NAMES = arrayOf(
    "digits", "periods", "exponent", "signs", "special", "alpha",
    "truefalse", "empty", "quotes", "dquotes", "whitespaces", "backslashes",
    "linefeed", "maxColumnLength",
)

/**
 * Pack evidence into a ShortArray using XOR prefix scan.
 * Each slot is the running XOR of the character-class counter encoded as UShort.
 * Nonreversionary: slot[i] = slot[i-1] xor counter[i].
 * Non-conditional: straight-line core loop, no branches.
 */
fun TypeEvidence.toShortArray(): ShortArray {
    val raw = intArrayOf(
        digits.toInt(), periods.toInt(), exponent.toInt(), signs.toInt(),
        special.toInt(), alpha.toInt(), truefalse.toInt(), empty.toInt(),
        quotes.toInt(), dquotes.toInt(), whitespaces.toInt(), backslashes.toInt(),
        linefeed.toInt(), maxColumnLength.toInt(),
    )
    val out = ShortArray(EVIDENCE_SLOTS)
    var acc = 0
    for (i in 0 until EVIDENCE_SLOTS) {
        acc = acc xor raw[i]
        out[i] = acc.toShort()
    }
    return out
}

/**
 * Recover the raw counter at slot [index] from an XOR-scanned array.
 * Inverse of prefix scan: raw[i] = out[i] xor out[i-1].
 */
fun ShortArray.xorSlotAt(index: Int): UShort {
    val prev = if (index == 0) 0 else this[index - 1].toInt()
    return (this[index].toInt() xor prev).toUShort()
}

/**
 * Deduce IOMemento from an XOR-scanned evidence array.
 * Uses [xorSlotAt] to recover counters then applies the same narrowing rules
 * as [TypeEvidence.deduce].
 */
fun ShortArray.deduceFromEvidence(): IOMemento {
    // Recover counters as UInt for safe unsigned arithmetic and comparisons
    val digits = xorSlotAt(0).toUInt()
    val periods = xorSlotAt(1).toUInt()
    val exponent = xorSlotAt(2).toUInt()
    val signs = xorSlotAt(3).toUInt()
    val special = xorSlotAt(4).toUInt()
    val alpha = xorSlotAt(5).toUInt()
    val truefalse = xorSlotAt(6).toUInt()
    val empty = xorSlotAt(7).toUInt()
    val quotes = xorSlotAt(8).toUInt()
    val dquotes = xorSlotAt(9).toUInt()
    val maxColumnLength = xorSlotAt(13).toUInt()

    return when {
        dquotes > 0U || quotes > 0U -> IOMemento.IoString
        empty > 0U || alpha > 0U -> IOMemento.IoString
        truefalse > 0U -> IOMemento.IoBoolean
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

/**
 * Deduce the structural TypeMemento from a scanned evidence array.
 * Checks confix from the source TypeEvidence (structural memento takes precedence).
 */
fun TypeEvidence.deduceMementoFromScan(): TypeMemento =
    structuralMemento ?: toShortArray().deduceFromEvidence()

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
