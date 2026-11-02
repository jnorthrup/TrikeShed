package borg.trikeshed.finance

/** Explicit currency precision; decimal text never crosses a floating-point representation. */
data class CurrencyScale(val code: String, val scale: Int) {
    init { require(code.length == 3 && code.all { it in 'A'..'Z' }); require(scale in 0..9) }
}

fun addMinorUnits(a: Long, b: Long): Long {
    require(!((b > 0 && a > Long.MAX_VALUE - b) || (b < 0 && a < Long.MIN_VALUE - b))) { "minor-unit overflow" }
    return a + b
}

/** ASCII grammar: -?(0|[1-9][0-9]*)(.[0-9]+)?; no rounding or excess precision. */
fun parseMinorUnits(text: String, currency: CurrencyScale): Long {
    require(text.isNotEmpty() && text.length <= 32) { "decimal string required" }
    val negative = text[0] == '-'
    val start = if (negative) 1 else 0
    require(start < text.length)
    val point = text.indexOf('.')
    val end = if (point < 0) text.length else point
    require(end > start && (end - start == 1 || text[start] != '0')) { "noncanonical integer" }
    val fractional = if (point < 0) 0 else text.length - point - 1
    require(point < 0 || fractional > 0)
    require(fractional <= currency.scale) { "excess precision" }
    // Negative accumulation represents Long.MIN_VALUE without abs/negation overflow.
    val limit = if (negative) Long.MIN_VALUE else -Long.MAX_VALUE
    var result = 0L
    fun digit(value: Int) {
        require(result >= limit / 10) { "minor-unit overflow" }
        result *= 10
        require(result >= limit + value) { "minor-unit overflow" }
        result -= value
    }
    for (i in start until text.length) {
        if (i == point) continue
        require(text[i] in '0'..'9') { "ASCII decimal required" }
        digit(text[i] - '0')
    }
    repeat(currency.scale - fractional) { digit(0) }
    return if (negative) result else -result
}

fun renderMinorUnits(units: Long, currency: CurrencyScale): String {
    val digits = units.toString().removePrefix("-").padStart(currency.scale + 1, '0')
    val sign = if (units < 0) "-" else ""
    return if (currency.scale == 0) sign + digits
    else sign + digits.dropLast(currency.scale) + "." + digits.takeLast(currency.scale)
}
