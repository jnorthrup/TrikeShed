package borg.trikeshed

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * human readable bytecounts using 1.xx format
 */
val Long.humanReadableByteCountSI: String get() {
    // allocate a buffer for the string

    val absB = if (this == Long.MIN_VALUE) Long.MAX_VALUE else abs(this)
    if (absB < 1000) return "$this B"
    val value = absB.toDouble()
    val e = (ln(value) / ln(1000.0)).toInt()
    val suffix = "kMGTPE"[e - 1]
    val truncated = (value / 1000.0.pow(e.toDouble())).toLong()
    val hasDecimal = truncated < 100L && truncated * 10.0.pow(2.0).toLong() % 10L != 0L
    return (if (this < 0) "-" else "") + if (hasDecimal) {
        "$truncated.${truncated * 10.0.pow(2.0) % 10}${suffix}B"
    } else {
        "$truncated$suffix"
    }
}
