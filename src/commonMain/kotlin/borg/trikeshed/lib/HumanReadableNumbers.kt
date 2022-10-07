package borg.trikeshed.lib

import kotlin.math.ln
import kotlin.math.pow

/***
 * translate human readable numbers to Number values, converting to lowercase first, using 1024 by default unless specified otherwise
 */
fun String.readableUnitsToNumber(decimal: Boolean = false): Number {
    val value = this.trim().lowercase()
    val multiplier = if (decimal) 1000 else 1024
    val suffix = when {
        value.endsWith("b") -> 1
        value.endsWith("k") -> multiplier
        value.endsWith("m") -> multiplier * multiplier
        value.endsWith("g") -> multiplier * multiplier * multiplier
        value.endsWith("t") -> multiplier * multiplier * multiplier * multiplier
        value.endsWith("p") -> multiplier * multiplier * multiplier * multiplier * multiplier
        value.endsWith("e") -> multiplier * multiplier * multiplier * multiplier * multiplier * multiplier
        else -> 1
    }
    val number = value.removeSuffix("b").removeSuffix("k").removeSuffix("m").removeSuffix("g").removeSuffix("t")
        .removeSuffix("p").removeSuffix("e").trim().toDouble()
    return number * suffix
}

/**
 * human readable bytecounts using 1.xx format
 */
val Long.humanReadableByteCountIEC: String get() {
    val seriesConstant = 1024
    val seriesDoubleConstant = seriesConstant.toDouble()
        return unitizer(seriesConstant, seriesDoubleConstant)

}

/**
 * human readable bytecounts using 1.xx format
 */
val Long.humanReadableByteCountSI: String get() {

    val seriesConstant = 1000
    val seriesDoubleConstant = seriesConstant.toDouble()
    return unitizer(seriesConstant, seriesDoubleConstant)
}

private fun Long.unitizer(seriesConstant: Int, seriesDoubleConstant: Double): String {
    if (this == Long.MIN_VALUE) return (Long.MIN_VALUE + 1).humanReadableByteCountIEC
    if (this < 0) return "-" + (-this).humanReadableByteCountIEC
    if (this < seriesConstant) return this.toString() + " B"
    val exp = (ln(this.toDouble()) / ln(seriesDoubleConstant)).toInt()
    val pre = "KMGTPE"[exp - 1] + "i"
    //cannot use string formmatting in kotlin native/common/js

    //round  to x.xx

    val rounded = (this / seriesDoubleConstant.pow(exp.toDouble())).toString().substringBefore(".") + "." + (this / seriesDoubleConstant.pow(exp.toDouble())).toString().substringAfter(".").substring(0, 2)
    return rounded + " " + pre + "B"
}
