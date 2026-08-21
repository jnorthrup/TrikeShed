package borg.trikeshed.nlp.lemma

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cas.LineNode
import borg.trikeshed.cas.LineSpine
import borg.trikeshed.cas.MatchGrade
import borg.trikeshed.collections.associative.FunnelHashIndex
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries

/**
 * One observation from a reference lemmatizer (e.g. Stanford CoreNLP `lemma` annotator):
 * a surface [word] with the lemmas of its immediate neighbors and the lemma the reference assigned.
 * Neighbors are *lemmas*, not surface forms — the stamp is taken over the reference's own lemma space.
 */
data class LemmaObservation(
    val word: String,
    val prevLemma: String?,
    val nextLemma: String?,
    val lemma: String,
    /** How many times this exact observation occurred (aggregated dictionaries carry counts). */
    val weight: Int = 1,
)

/**
 * A suffix-stripping rule: drop the last [strip] chars of the word, append [append].
 * Derived from (word, lemma) by their common prefix — so `running→run` is (4, ""), `flies→fly` is (3, "y"),
 * `ran→run` is the degenerate whole-word rule (3, "run"). Prefix morphology is deliberately out of scope.
 */
data class SuffixRule(val strip: Int, val append: String) {
    fun applies(word: String): Boolean = strip <= word.length
    fun apply(word: String): String = word.substring(0, word.length - strip) + append

    val encoded: String get() = "$strip|$append"

    companion object {
        fun derive(word: String, lemma: String): SuffixRule {
            var p = 0
            val n = minOf(word.length, lemma.length)
            while (p < n && word[p] == lemma[p]) p++
            return SuffixRule(strip = word.length - p, append = lemma.substring(p))
        }

        fun decode(s: String): SuffixRule {
            val bar = s.indexOf('|')
            return SuffixRule(s.substring(0, bar).toInt(), s.substring(bar + 1))
        }
    }
}

/**
 * FunnelLemmatizer — frozen **suffix-rule** index under the Line CAS algebra.
 *
 * The spine is the sentence's words. Each word is a [LineNode] whose `contentCid` is a *suffix* of the
 * normalized surface form and whose [borg.trikeshed.cas.NeighborStamp] is built from the neighbor
 * *lemmas* (`LineCas.stamp(prevLemmaCid, nextLemmaCid)`). What the index stores per key is not a lemma but
 * a [SuffixRule]; lemmatizing is "find the most specific rule whose context matches, apply it".
 *
 * Specificity order at lookup (first hit wins):
 *
 *   k = word length   whole-word rule (irregulars: ran→run, is→be)
 *   k = MAX_SUFFIX…1  suffix of length k
 *     and within each k the [MatchGrade] ladder:
 *       LINKED (prev+next lemma stamp) → PARTIAL_PREV → PARTIAL_NEXT → CONTENT_ONLY (no context)
 *   identity          no rule applies — a funnel MISS is authoritative
 *
 * Four frozen [FunnelHashIndex] rungs back the four grades; each index slot is an insertion index into a
 * parallel rule array. Freezing is a weighted majority vote per key, so re-freezing the same observations
 * with the same seed is byte-identical. Suffix rungs (k < word length) require [minSupport] votes so a
 * one-off typo does not become a rule; whole-word rungs accept a single observation.
 *
 * Neighbor lemmas are unknown before lemmatizing, so [lemmatize] iterates: pass 1 is CONTENT_ONLY for
 * every token, later passes re-resolve with the neighbors' current lemmas until a fixpoint. That is the
 * word-scale "fractal"; [LemmaSpines] and [ResidualLemmaCache] carry the same algebra up to sentences
 * and paragraphs.
 */
class FunnelLemmatizer private constructor(
    private val linked: Rung,
    private val prevOnly: Rung,
    private val nextOnly: Rung,
    private val content: Rung,
    val seed: Long,
    val maxSuffix: Int,
    val minSupport: Int,
) {
    /** A frozen funnel plus the rule each slot resolves to. */
    class Rung internal constructor(val index: FunnelHashIndex<String>, val rules: Array<SuffixRule>) {
        val size: Int get() = rules.size
        fun lookup(key: String): SuffixRule? = index.get(key)?.let { rules[it] }
    }

    data class Resolution(val lemma: String, val grade: MatchGrade?, val suffixLength: Int, val rule: SuffixRule?)

    /** Distinct keys in the context-free rung (whole-word + suffix keys). */
    val vocabulary: Int get() = content.size

    /** Distinct (prevLemma, key, nextLemma) contexts frozen. */
    val linkedContexts: Int get() = linked.size

    fun resolve(word: String, prevLemma: String?, nextLemma: String?): Resolution {
        val w = normalize(word)
        if (w.isEmpty()) return Resolution(w, null, 0, null)
        val lengths = suffixLengths(w)
        for (k in lengths) {
            val suffix = w.takeLast(k)
            val keyed = suffixKey(k, suffix)
            linked.lookup(stampKey(keyed, prevLemma, nextLemma))?.takeIf { it.applies(w) }
                ?.let { return Resolution(it.apply(w), MatchGrade.LINKED, k, it) }
            prevOnly.lookup(prevKey(keyed, prevLemma))?.takeIf { it.applies(w) }
                ?.let { return Resolution(it.apply(w), MatchGrade.PARTIAL_PREV, k, it) }
            nextOnly.lookup(nextKey(keyed, nextLemma))?.takeIf { it.applies(w) }
                ?.let { return Resolution(it.apply(w), MatchGrade.PARTIAL_NEXT, k, it) }
            content.lookup(keyed)?.takeIf { it.applies(w) }
                ?.let { return Resolution(it.apply(w), MatchGrade.CONTENT_ONLY, k, it) }
        }
        return Resolution(w, null, 0, null)
    }

    /** Context-free resolution (pass 1 of [lemmatize]). */
    fun resolveContentOnly(word: String): String {
        val w = normalize(word)
        if (w.isEmpty()) return w
        for (k in suffixLengths(w)) {
            content.lookup(suffixKey(k, w.takeLast(k)))?.takeIf { it.applies(w) }?.let { return it.apply(w) }
        }
        return w
    }

    /** Lemmatize one sentence. [maxPasses] bounds the neighbor-refinement iteration. */
    fun lemmatize(words: List<String>, maxPasses: Int = 3): List<String> {
        if (words.isEmpty()) return emptyList()
        var lemmas = words.map(::resolveContentOnly)
        var pass = 1
        while (pass < maxPasses) {
            val next = List(words.size) { i ->
                val prev = if (i > 0) lemmas[i - 1] else null
                val nxt = if (i < words.lastIndex) lemmas[i + 1] else null
                resolve(words[i], prev, nxt).lemma
            }
            if (next == lemmas) break
            lemmas = next
            pass++
        }
        return lemmas
    }

    /** Per-(grade, suffixLength) histogram for one sentence at its fixpoint. */
    fun resolutionHistogram(words: List<String>): Map<Pair<MatchGrade?, Int>, Int> {
        val lemmas = lemmatize(words)
        val counts = mutableMapOf<Pair<MatchGrade?, Int>, Int>()
        for (i in words.indices) {
            val prev = if (i > 0) lemmas[i - 1] else null
            val nxt = if (i < words.lastIndex) lemmas[i + 1] else null
            val r = resolve(words[i], prev, nxt)
            val key = r.grade to r.suffixLength
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts
    }

    /** Whole word first, then suffixes from [maxSuffix] down to 1 (only those shorter than the word). */
    private fun suffixLengths(w: String): List<Int> {
        val out = ArrayList<Int>(maxSuffix + 1)
        out += w.length
        var k = minOf(maxSuffix, w.length - 1)
        while (k >= 1) { out += k; k-- }
        return out
    }

    companion object {
        const val DEFAULT_MAX_SUFFIX: Int = 5
        const val DEFAULT_MIN_SUPPORT: Int = 2

        fun normalize(word: String): String = word.trim().lowercase()

        fun lemmaCid(lemma: String?): ContentId? = lemma?.let { ContentId.of(normalize(it).encodeToByteArray()) }

        /** Key for a suffix of length [k]; `k == word.length` marks the whole-word (irregular) rung. */
        fun suffixKey(k: Int, suffix: String): String =
            "$k:" + ContentId.of(suffix.encodeToByteArray()).hex

        fun stampKey(keyed: String, prevLemma: String?, nextLemma: String?): String =
            LineCas.stamp(lemmaCid(prevLemma), lemmaCid(nextLemma)).hex + ":" + keyed

        fun prevKey(keyed: String, prevLemma: String?): String =
            LineCas.neighborHex(lemmaCid(prevLemma)) + "**:" + keyed

        fun nextKey(keyed: String, nextLemma: String?): String =
            "**" + LineCas.neighborHex(lemmaCid(nextLemma)) + ":" + keyed

        /**
         * Freeze a reference corpus into the four rungs. For each observation, one vote per suffix length
         * (whole word, then [maxSuffix]..1) for the [SuffixRule] derived from (word, lemma). Weighted
         * majority per key; ties resolve to the first-seen rule so the result is deterministic.
         */
        fun freeze(
            observations: List<LemmaObservation>,
            seed: Long = 0L,
            slack: Double = 0.20,
            maxSuffix: Int = DEFAULT_MAX_SUFFIX,
            minSupport: Int = DEFAULT_MIN_SUPPORT,
        ): FunnelLemmatizer {
            val votes = Votes(maxSuffix, minSupport)
            for (o in observations) votes.add(o)
            return FunnelLemmatizer(
                linked = votes.linked.freeze(seed, slack),
                prevOnly = votes.prevOnly.freeze(seed, slack),
                nextOnly = votes.nextOnly.freeze(seed, slack),
                content = votes.content.freeze(seed, slack),
                seed = seed,
                maxSuffix = maxSuffix,
                minSupport = minSupport,
            )
        }

        private class Table(private val minSupport: Int) {
            // key → (ruleEncoded → weight), insertion-ordered for determinism
            private val byKey = LinkedHashMap<String, LinkedHashMap<String, Int>>()
            private val wholeWord = HashSet<String>()

            fun vote(key: String, rule: SuffixRule, weight: Int, isWholeWord: Boolean) {
                val perKey = byKey.getOrPut(key) { LinkedHashMap() }
                perKey[rule.encoded] = (perKey[rule.encoded] ?: 0) + weight
                if (isWholeWord) wholeWord += key
            }

            fun freeze(seed: Long, slack: Double): Rung {
                val keys = ArrayList<String>()
                val rules = ArrayList<SuffixRule>()
                for ((key, perKey) in byKey) {
                    var best: String? = null
                    var bestCount = -1
                    var total = 0
                    for ((rule, count) in perKey) {
                        total += count
                        if (count > bestCount) { best = rule; bestCount = count }
                    }
                    if (best == null) continue
                    if (key !in wholeWord && total < minSupport) continue
                    keys += key
                    rules += SuffixRule.decode(best)
                }
                return Rung(FunnelHashIndex.build(keys.toSeries(), seed, slack), rules.toTypedArray())
            }
        }

        private class Votes(private val maxSuffix: Int, minSupport: Int) {
            val linked = Table(minSupport)
            val prevOnly = Table(minSupport)
            val nextOnly = Table(minSupport)
            val content = Table(minSupport)

            fun add(o: LemmaObservation) {
                val w = normalize(o.word)
                if (w.isEmpty()) return
                val rule = SuffixRule.derive(w, normalize(o.lemma))
                val lengths = ArrayList<Int>().apply {
                    add(w.length)
                    var k = minOf(maxSuffix, w.length - 1)
                    while (k >= 1) { add(k); k-- }
                }
                for (k in lengths) {
                    // a suffix shorter than the strip cannot carry this rule: skip (prevents "s"→"run")
                    if (k < w.length && rule.strip > k) continue
                    val keyed = suffixKey(k, w.takeLast(k))
                    val whole = k == w.length
                    linked.vote(stampKey(keyed, o.prevLemma, o.nextLemma), rule, o.weight, whole)
                    prevOnly.vote(prevKey(keyed, o.prevLemma), rule, o.weight, whole)
                    nextOnly.vote(nextKey(keyed, o.nextLemma), rule, o.weight, whole)
                    content.vote(keyed, rule, o.weight, whole)
                }
            }
        }
    }
}

/**
 * The same algebra one and two scales up: a sentence is a spine of words, a paragraph a spine of
 * sentence CIDs, a document a spine of paragraph CIDs. Each level's `spineCid` is content-addressed
 * over its children's linked keys, so a repeated sentence (or paragraph) has the same CID wherever it
 * recurs — which is what lets [ResidualLemmaCache] skip it wholesale.
 */
object LemmaSpines {
    fun wordSpine(words: List<String>): LineSpine =
        spineOf(words.map(FunnelLemmatizer::normalize))

    fun sentenceCid(words: List<String>): ContentId = LineCas.spineCid(wordSpine(words))

    fun paragraphCid(sentenceCids: List<ContentId>): ContentId =
        LineCas.spineCid(spineOf(sentenceCids.map { it.hex }))

    fun documentCid(paragraphCids: List<ContentId>): ContentId =
        LineCas.spineCid(spineOf(paragraphCids.map { it.hex }))

    private fun spineOf(items: List<String>): LineSpine {
        if (items.isEmpty()) return 0 j { error("empty spine") }
        val cids = Array(items.size) { i -> ContentId.of(items[i].encodeToByteArray()) }
        return items.size j { i: Int ->
            val prev = if (i > 0) cids[i - 1] else null
            val next = if (i < cids.lastIndex) cids[i + 1] else null
            LineNode(cids[i], LineCas.stamp(prev, next), i)
        }
    }
}

/**
 * Residual memo over frozen generations: units (sentences, paragraphs) already lemmatized are found by
 * CID in a ring of frozen funnels and served from cache; only the residual is lemmatized. Staging is a
 * small mutable map that freezes into a new generation every [freezeAt] entries — never mutate a
 * generation, push a new one.
 */
class ResidualLemmaCache(
    private val seed: Long = 0L,
    private val freezeAt: Int = 256,
    private val maxGenerations: Int = 8,
) {
    private class Generation(val index: FunnelHashIndex<String>, val values: Array<List<String>>)

    private val generations = ArrayDeque<Generation>()
    private val staging = LinkedHashMap<String, List<String>>()

    val generationCount: Int get() = generations.size
    val stagingSize: Int get() = staging.size

    fun lookup(cidHex: String): List<String>? {
        staging[cidHex]?.let { return it }
        for (g in generations.asReversed()) {
            g.index.get(cidHex)?.let { return g.values[it] }
        }
        return null
    }

    fun remember(cidHex: String, lemmas: List<String>) {
        if (lookup(cidHex) != null) return
        staging[cidHex] = lemmas
        if (staging.size >= freezeAt) freeze()
    }

    /** Freeze staging into a new generation; oldest generation falls off past [maxGenerations]. */
    fun freeze() {
        if (staging.isEmpty()) return
        val keys = staging.keys.toList()
        val values = Array(keys.size) { i -> staging.getValue(keys[i]) }
        generations.addLast(Generation(FunnelHashIndex.build(keys.toSeries(), seed + generations.size, 0.20), values))
        staging.clear()
        while (generations.size > maxGenerations) generations.removeFirst()
    }
}

/** Counters for one document run: how much work the fractal residual actually skipped. */
data class FractalStats(
    val paragraphs: Int,
    val paragraphsSkipped: Int,
    val sentences: Int,
    val sentencesSkipped: Int,
    val tokens: Int,
    val tokensLemmatized: Int,
) {
    val tokenReduction: Double get() = if (tokens == 0) 0.0 else 1.0 - tokensLemmatized.toDouble() / tokens
}

/**
 * Lemmatize a document given as paragraphs of sentences of words, skipping any paragraph or sentence
 * whose CID is already in [cache]. Returns lemmas in the same shape plus [FractalStats].
 */
fun FunnelLemmatizer.lemmatizeDocument(
    paragraphs: List<List<List<String>>>,
    cache: ResidualLemmaCache,
): Pair<List<List<List<String>>>, FractalStats> {
    var pSkipped = 0
    var sTotal = 0
    var sSkipped = 0
    var tokens = 0
    var tokensDone = 0

    val out = paragraphs.map { paragraph ->
        val sentenceCids = paragraph.map { LemmaSpines.sentenceCid(it) }
        val pKey = "p:" + LemmaSpines.paragraphCid(sentenceCids).hex
        sTotal += paragraph.size
        tokens += paragraph.sumOf { it.size }
        cache.lookup(pKey)?.let { flat ->
            pSkipped++
            sSkipped += paragraph.size
            return@map unflatten(flat, paragraph)
        }
        val lemmatized = paragraph.mapIndexed { i, sentence ->
            val sKey = "s:" + sentenceCids[i].hex
            cache.lookup(sKey)?.also { sSkipped++ } ?: run {
                tokensDone += sentence.size
                lemmatize(sentence).also { cache.remember(sKey, it) }
            }
        }
        cache.remember(pKey, lemmatized.flatten())
        lemmatized
    }
    return out to FractalStats(paragraphs.size, pSkipped, sTotal, sSkipped, tokens, tokensDone)
}

private fun unflatten(flat: List<String>, shape: List<List<String>>): List<List<String>> {
    var cursor = 0
    return shape.map { sentence -> flat.subList(cursor, cursor + sentence.size).also { cursor += sentence.size } }
}
