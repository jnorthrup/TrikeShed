package borg.trikeshed.agent.modelmux

import borg.trikeshed.agent.modelmux.breadcrumbKey
import borg.trikeshed.agent.modelmux.breadcrumbPrefix
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Word counts over one trail. A record's particles are its words the rest of the trail uses least: the naive Bayes
 * log odds of a word in the record against the other records, add-one smoothed over the trail's vocabulary.
 */
class BreadcrumbLexicon(records: Iterable<borg.trikeshed.agent.modelmux.BreadcrumbRecord>) {
    val counts = HashMap<String, Int>()
    var total = 0

    init {
        for (record in records) for (word in borg.trikeshed.agent.modelmux.breadcrumbWords(record.content)) {
            val key = borg.trikeshed.agent.modelmux.breadcrumbKey(word) ?: continue
            counts[key] = (counts[key] ?: 0) + 1
            total++
        }
    }
}

fun breadcrumbWords(content: String): List<String> = content.split(' ', '\n', '\t', '\r').filter { it.isNotEmpty() }

/** Letters and digits, case-folded; punctuation-only words carry no key and are held last. */
fun breadcrumbKey(word: String): String? = word.filter { it.isLetterOrDigit() }.lowercase().ifEmpty { null }

const val BREADCRUMB_GAP = "…"

/**
 * The particulate summary at [presence]: within round(presence × length) characters, the most distinct words in their
 * original order, with [borg.trikeshed.agent.modelmux.BREADCRUMB_GAP] where words were left out; a record too short in words for that budget
 * (one long token) holds its most distinct word's prefix. Presence 1 is the original exactly; 0 is empty.
 */
fun borg.trikeshed.agent.modelmux.BreadcrumbRecord.particles(presence: Double, lexicon: borg.trikeshed.agent.modelmux.BreadcrumbLexicon? = null): String {
    if (presence >= 1.0) return content
    val budget = (content.length * presence).roundToInt()
    if (budget >= content.length) return content
    if (budget <= 0) return ""
    val words = borg.trikeshed.agent.modelmux.breadcrumbWords(content)
    if (words.isEmpty()) return ""
    val keys = words.map(::breadcrumbKey)
    val own = HashMap<String, Int>()
    for (key in keys) if (key != null) own[key] = (own[key] ?: 0) + 1
    val ownTotal = own.values.sum()
    val vocabulary = (lexicon?.counts?.size ?: own.size) + 1
    val othersTotal = (lexicon?.total ?: ownTotal) - ownTotal
    val score = DoubleArray(words.size) { i ->
        val key = keys[i] ?: return@DoubleArray Double.NEGATIVE_INFINITY
        val mine = own.getValue(key)
        val others = maxOf(0, (lexicon?.counts?.get(key) ?: mine) - mine)
        ln((mine + 1.0) / (ownTotal + vocabulary)) - ln((others + 1.0) / (maxOf(0, othersTotal) + vocabulary))
    }
    val order = words.indices.sortedWith(compareByDescending<Int> { score[it] }.thenBy { it })
    // The rendering's length: kept words and one gap per dropped run, single-space separated.
    // An empty selection renders one gap; keeping a word adds it and a gap on each side its neighbours were dropped.
    val keep = BooleanArray(words.size)
    var used = borg.trikeshed.agent.modelmux.BREADCRUMB_GAP.length
    var kept = 0
    for (i in order) {
        val gaps = (if (i > 0 && !keep[i - 1]) 1 else 0) + (if (i < words.size - 1 && !keep[i + 1]) 1 else 0)
        val cost = words[i].length + gaps * (borg.trikeshed.agent.modelmux.BREADCRUMB_GAP.length + 1) - borg.trikeshed.agent.modelmux.BREADCRUMB_GAP.length
        if (used + cost <= budget) { keep[i] = true; used += cost; kept++ }
    }
    if (kept == 0) return words[order.first()].breadcrumbPrefix(budget)
    return buildString {
        var gap = false
        for (i in words.indices) {
            if (!keep[i]) { gap = true; continue }
            if (gap) { if (isNotEmpty()) append(' '); append(borg.trikeshed.agent.modelmux.BREADCRUMB_GAP) }
            if (isNotEmpty()) append(' ')
            append(words[i])
            gap = false
        }
        if (gap) append(' ').append(borg.trikeshed.agent.modelmux.BREADCRUMB_GAP)
    }
}
