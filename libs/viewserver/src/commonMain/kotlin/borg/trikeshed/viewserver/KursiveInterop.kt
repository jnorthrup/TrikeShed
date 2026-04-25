package borg.trikeshed.viewserver

import borg.trikeshed.parse.kursive.KursiveCharSeries

/**
 * Minimal kursive-based validator used in the prototype pipeline.
 * Currently heuristically detects function-like sources and returns true if
 * the source looks like a JS function expression or arrow function.
 */
fun isLikelyJsFn(src: String): Boolean {
    val ks = KursiveCharSeries(src)
    ks.skipWhitespace()
    val first = ks.peek()
    return when (first) {
        'f' -> src.trimStart().startsWith("function")
        '(' -> true
        '{' -> true
        else -> src.trimStart().contains("=>")
    }
}
