package borg.trikeshed.narsese

import kotlin.jvm.JvmInline
import kotlin.math.abs
import kotlin.math.pow

/**
 * How far a work's English stands from the English the noun lexicon maps, read as the share of its lowercase nouns
 * the lexicon does not map. Law Latin, period spelling and terms of art raise it; so does OCR garble, whose words are
 * no more the lexicon's English than Latin is. [below] is each register's exclusive upper bound.
 */
enum class Register(val below: Float) {
    CURRENT(0.04f),
    ARCHAIC(0.10f),
    FOREIGN(Float.MAX_VALUE),
    ;

    companion object {
        /** The register of a text with [unmapped] of its [nouns] lowercase nouns outside the lexicon. */
        fun of(unmapped: Int, nouns: Int): Register {
            val share = if (nouns == 0) 0f else unmapped.toFloat() / nouns
            return entries.first { share < it.below }
        }
    }
}

/**
 * Where a text was crafted: the decade its work places itself in, and its [Register]. A word's sense is believed per
 * locality; evidence read in one locality counts in another by their [nearness]. Packed as decade in the high bits and
 * register ordinal in the low byte; decade 0 is a work that states no year.
 */
@JvmInline
value class Locality(val packed: Int) {
    val decade: Int get() = packed ushr 8
    val register: Register get() = Register.entries[packed and 0xFF]
    val placed: Boolean get() = decade > 0

    /**
     * The weight evidence read in [other] carries here: halved every [HALF_LIFE] decades apart (a side with no year
     * counts as [HALF_LIFE] apart) and halved again per register step.
     */
    fun nearness(other: Locality): Float {
        val decades = if (placed && other.placed) abs(decade - other.decade) else HALF_LIFE
        return 0.5f.pow(decades.toFloat() / HALF_LIFE + abs(register.ordinal - other.register.ordinal))
    }

    /** The locality as a NAL term: `1930s_current`, `unplaced_archaic`. */
    val term: String get() = (if (placed) "${decade * 10}s" else "unplaced") + "_" + register.name.lowercase()

    override fun toString(): String = term

    companion object {
        /** Decades apart at which evidence counts half. */
        const val HALF_LIFE = 5

        fun of(year: Int, register: Register): Locality = Locality(((if (year > 0) year / 10 else 0) shl 8) or register.ordinal)

        /** The locality a [term] names, or null. */
        fun parse(term: String): Locality? {
            val cut = term.lastIndexOf('_').takeIf { it > 0 } ?: return null
            val register = Register.entries.firstOrNull { it.name.lowercase() == term.substring(cut + 1) } ?: return null
            val era = term.substring(0, cut)
            val decade = if (era == "unplaced") 0 else era.removeSuffix("s").toIntOrNull()?.let { it / 10 } ?: return null
            return Locality((decade shl 8) or register.ordinal)
        }
    }
}

/**
 * A work's front matter and the years it states. Printers page front matter (title, imprint, preface, contents) in
 * lowercase roman numerals and begin the body at arabic page 1, so the first line holding only an arabic page number
 * after one holding only a roman one ends it; a text with no roman page mark gives its first [FRONT_CHARS] characters.
 * A title page states its year in digits or in uppercase roman numerals (MDCCCXCI); both are read.
 */
object FrontMatter {
    const val FRONT_CHARS = 6_000
    /** How far into a text a roman page mark may open its front matter. */
    const val FRONT_LIMIT = 120_000

    private val romanPage = Regex("""(?m)^[ \t]*[ivxlc]{1,7}[ \t]*$""")
    private val arabicPage = Regex("""(?m)^[ \t]*\d{1,4}[ \t]*$""")
    private val digits = Regex("""\b(1[4-9]\d\d|20\d\d)\b""")
    private val numeral = Regex("""\bM{1,2}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})\b""")

    /** [text]'s front matter. */
    fun of(text: String): String = paged(text) ?: text.take(FRONT_CHARS)

    /** [text]'s front matter where roman page marks delimit it; null when the text has none. */
    fun paged(text: String): String? {
        val head = text.take(FRONT_LIMIT)
        val roman = romanPage.find(head) ?: return null
        val body = arabicPage.find(head, roman.range.last + 1) ?: return null
        return head.substring(0, body.range.first)
    }

    /** Calendar years 1400–2099 [text] states as four digits, in order of appearance. */
    fun years(text: String): IntArray = digits.findAll(text).map { it.value.toInt() }.toList().toIntArray()

    /** Calendar years 1400–2099 [text] states as uppercase roman numerals of four or more letters (MD, MM are words). */
    fun romanYears(text: String): IntArray =
        numeral.findAll(text).filter { it.value.length >= 4 }.map { roman(it.value) }.filter { it in 1400..2099 }.toList().toIntArray()

    /** A word that numbers what follows it, so the four digits after it are an ordinal, not a year. */
    private val numbering = Regex("""(?i)\b(proclamation|no|nos|number|chapter|ch|section|sec|title|volume|vol|page|pp|p|l|order|bill|rule|form)[\s.#:-]*$""")

    /**
     * The year a work's [title] states: four digits not led by a numbering word ("Act of 1933", "Dictionary 1914",
     * "2nd ed 1907"; not "Proclamation 2040" or "P.L. 1914"); 0 when it states none. The latest such year when it
     * states several. Underscores read as spaces: a file name's `Dictionary_1914` is a word and a year.
     */
    fun titleYear(title: String): Int {
        val t = title.replace('_', ' ')
        return digits.findAll(t).filter { m -> !numbering.containsMatchIn(t.substring(0, m.range.first)) }
            .map { it.value.toInt() }.maxOrNull() ?: 0
    }

    /** The value of an uppercase roman numeral; subtractive pairs (CM, XL, IV) count as one. */
    fun roman(s: String): Int {
        fun v(c: Char) = when (c) { 'I' -> 1; 'V' -> 5; 'X' -> 10; 'L' -> 50; 'C' -> 100; 'D' -> 500; 'M' -> 1000; else -> 0 }
        var total = 0
        for (i in s.indices) {
            val x = v(s[i])
            total += if (i + 1 < s.length && x < v(s[i + 1])) -x else x
        }
        return total
    }
}
