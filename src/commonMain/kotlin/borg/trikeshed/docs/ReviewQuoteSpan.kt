package borg.trikeshed.docs

/** An exact UTF-16 span in retained extracted text, with an exclusive end. */
data class ReviewQuoteSpan(val begin: Int, val end: Int, val quote: String)

/** Map a selection within the unchanged quotation to its original extracted-text offsets. */
fun reviewQuoteSpan(sourceBegin: Int, sourceEnd: Int, quote: String, localBegin: Int, localEnd: Int): ReviewQuoteSpan? {
    if (sourceBegin < 0 || sourceEnd <= sourceBegin || sourceEnd.toLong() - sourceBegin.toLong() != quote.length.toLong() ||
        quote.encodeToByteArray().decodeToString(throwOnInvalidSequence = true) != quote ||
        localBegin < 0 || localEnd <= localBegin || localEnd > quote.length) return null
    fun splits(at: Int): Boolean = at > 0 && at < quote.length &&
        quote[at - 1] in '\uD800'..'\uDBFF' && quote[at] in '\uDC00'..'\uDFFF'
    if (splits(localBegin) || splits(localEnd)) return null
    val selected = quote.substring(localBegin, localEnd)
    if (selected.isBlank()) return null
    return ReviewQuoteSpan(sourceBegin + localBegin, sourceBegin + localEnd, selected)
}
