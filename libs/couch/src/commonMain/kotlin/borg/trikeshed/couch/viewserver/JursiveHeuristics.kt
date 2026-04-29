package borg.trikeshed.couch.viewserver

/** Returns true if [code] looks like a JavaScript function (arrow, function keyword, or { return }). */
fun isLikelyJsFn(code: String): Boolean = code.isNotBlank() && (
    code.trimStart().startsWith("function(") ||
    code.trimStart().startsWith("function ") ||
    code.contains("=>") ||
    code.contains("{ return ")
    )
