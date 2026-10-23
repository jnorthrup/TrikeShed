package borg.trikeshed.cas

import borg.trikeshed.collections.FunnelHashMap
import kotlin.jvm.JvmInline

/**
 * Evoke's raw-text analyzer for `sae = false` indexes: word-break segmentation, then lowercase
 * (`text_lowercase`) and an optional stopword set (`text_stopwords`); no stemming or diacritic
 * folding (both default off in Evoke).
 *
 * Segmentation follows UAX #29 word boundaries as ICU's word iterator applies them to letters and
 * numbers: a word is a run of letters, digits, marks and `_`; MidLetter / MidNumLet characters
 * (`:` `.` `'` `’` …) join letters (`can't`, `e.g`, `a:b`) and MidNum / MidNumLet characters (`,`
 * `.` `'` …) join digits (`3.14`, `1,000`). Each ideograph is its own word (ICU segments CJK by
 * dictionary; this does not). Input is not NFC-normalized: commonMain has no normalizer
 * (`TextK.NormK.NFC` is an identity today), so composed and decomposed forms stay distinct.
 */
class Analyzer(val lowercase: Boolean = true, val stopwords: Set<String> = emptySet()) {
    fun tokens(text: CharSequence): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (ideograph(c)) { keep(c.toString(), out); i++; continue }
            if (!wordChar(c)) { i++; continue }
            val start = i
            i++
            while (i < n) {
                val d = text[i]
                if (wordChar(d) && !ideograph(d)) { i++; continue }
                if (i + 1 < n && joins(text[i - 1], d, text[i + 1])) { i += 2; continue }
                break
            }
            keep(text.subSequence(start, i).toString(), out)
        }
        return out
    }

    private fun keep(word: String, out: MutableList<String>) {
        val token = if (lowercase) word.lowercase() else word
        if (token !in stopwords) out += token
    }

    private fun wordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' ||
        c.category == CharCategory.NON_SPACING_MARK || c.category == CharCategory.COMBINING_SPACING_MARK ||
        c.category == CharCategory.ENCLOSING_MARK

    private fun ideograph(c: Char): Boolean = c in '\u3400'..'\u4DBF' || c in '\u4E00'..'\u9FFF' || c in '\uF900'..'\uFAFF'

    /** UAX #29 WB6/7 (letters across MidLetter, MidNumLet, ') and WB11/12 (digits across MidNum, MidNumLet, '). */
    private fun joins(before: Char, mid: Char, after: Char): Boolean = when {
        before.isLetter() && after.isLetter() -> mid in MID_LETTER || mid in MID_NUM_LET || mid == '\''
        before.isDigit() && after.isDigit() -> mid in MID_NUM || mid in MID_NUM_LET || mid == '\''
        else -> false
    }

    companion object {
        private const val MID_LETTER = ":\u00B7\u0387\u055F\u05F4\u2027\uFE13\uFE55\uFF1A"
        private const val MID_NUM_LET = ".\u2018\u2019\u2024\uFE52\uFF07\uFF0E"
        private const val MID_NUM = ",;\u037E\u0589\u060C\u060D\u066C\u07F8\u2044\uFE10\uFE14\uFE50\uFE54\uFF0C\uFF1B"
    }
}

/** An interned term: lexical atoms are numbered in first-seen order. */
@JvmInline
value class AtomId(val id: Int)

/**
 * Normalized term → stable [AtomId], interned once (the `FunnelHashMap` interning `SenseMemory`
 * uses). One lexicon numbers one field's lexical atoms; Evoke's field-aware and semantic ranges
 * (`f·L + tok`, `F·L + f·S + coord`) are offsets over these ids, added when those indexes are.
 */
class AtomLexicon {
    private val ids = FunnelHashMap<String, Int>()
    private val terms = ArrayList<String>()

    val size: Int get() = terms.size

    fun intern(term: String): AtomId = AtomId(ids.get(term) ?: terms.size.also { ids.put(term, it); terms += term })

    fun find(term: String): AtomId? = ids.get(term)?.let(::AtomId)

    fun term(atom: AtomId): String = terms[atom.id]
}
