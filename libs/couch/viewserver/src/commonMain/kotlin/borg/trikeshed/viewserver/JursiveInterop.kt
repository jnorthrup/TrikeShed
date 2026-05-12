package borg.trikeshed.viewserver

import borg.trikeshed.parse.kursive.JursiveCharSeries

/**
 * Minimal jursive-based validator used in the prototype pipeline.
 * Currently heuristically detects function-like sources and returns true if
 * the source looks like a JS function expression or arrow function.
 */
fun isLikelyJsFn(src: CharSequence): Boolean {
    val trimmed = src.trimStart()
    if (trimmed.isEmpty()) return false
    return when (trimmed.first()) {
        'f' -> trimmed.startsWith("function")
        '(' -> true
        '{' -> {
            // Distinguish JS block (starts with return/var/let/const) from JSON object
            val afterBrace = trimmed.drop(1).trimStart()
            afterBrace.startsWith("return ") || afterBrace.startsWith("var ") ||
                afterBrace.startsWith("let ") || afterBrace.startsWith("const ") ||
                afterBrace.startsWith("if ") || afterBrace.startsWith("for ") ||
                afterBrace.startsWith("while ") || afterBrace.startsWith("switch ")
        }
        else -> trimmed.contains("=>")
    }
}
