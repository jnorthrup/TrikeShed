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
)

/**
 * FunnelLemmatizer — a frozen word→lemma index under the Line CAS algebra.
 *
 * The spine is the sentence's words; each word is a [LineNode] whose `contentCid` is the normalized
 * surface form and whose [borg.trikeshed.cas.NeighborStamp] is built from the neighbor *lemmas*
 * (`LineCas.stamp(prevLemmaCid, nextLemmaCid)`). Lookup walks the [MatchGrade] ladder:
 *
 *   LINKED        stamp + word      ("saw" between "i" and "the"  → see)
 *   PARTIAL_PREV  prev + word
 *   PARTIAL_NEXT  word + next
 *   CONTENT_ONLY  word              (majority lemma for the word, context-free)
 *   identity      word itself       (unseen: a funnel MISS is authoritative)
 *
 * Four frozen [FunnelHashIndex] generations back the four rungs; each index slot is an insertion index
 * into a parallel lemma array. Freezing is a majority vote per key over the observations, so refreezing
 * the same observations with the same seed yields byte-identical tables.
 *
 * Neighbor lemmas are not known before lemmatizing, so [lemmatize] is iterative: pass 1 is
 * CONTENT_ONLY for every token; later passes re-resolve each token with its neighbors' current lemmas
 * until a fixpoint (typically 2 passes). That iteration is the word-scale "fractal"; [LemmaSpines] and
 * [ResidualLemmaCache] carry the same algebra up to sentences and paragraphs.
 */
class FunnelLemmatizer private constructor(
    private val linked: Rung,
    private val prevOnly: Rung,
    private val nextOnly: Rung,
    private val content: Rung,
    val seed: Long,
) {
    /** A frozen funnel plus the lemma each slot resolves to. */
    class Rung internal constructor(val index: FunnelHashIndex<String>, val lemmas: Array<String>) {
        val size: Int get() = lemmas.size
        fun lookup(key: String): String? = index.get(key)?.let { lemmas[it] }
    }

    data class Resolution(val lemma: String, val grade: MatchGrade?)

    /** Distinct normalized surface forms known context-free. */
    val vocabulary: Int get() = content.size

    /** Distinct (prevLemma, word, nextLemma) contexts frozen. */
    val linkedContexts: Int get() = linked.size

    fun resolve(word: String, prevLemma: String?, nextLemma: String?): Resolution {
        linked.lookup(linkedKey(word, prevLemma, nextLemma))?.let { return Resolution(it, MatchGrade.LINKED) }
        prevOnly.lookup(prevKey(word, prevLemma))?.let { return Resolution(it, MatchGrade.PARTIAL_PREV) }
        nextOnly.lookup(nextKey(word, nextLemma))?.let { return Resolution(it, MatchGrade.PARTIAL_NEXT) }
        content.lookup(contentKey(word))?.let { return Resolution(it, MatchGrade.CONTENT_ONLY) }
        return Resolution(normalize(word), null)
    }

    /** Lemmatize one sentence. [maxPasses] bounds the neighbor-refinement iteration. */
    fun lemmatize(words: List<String>, maxPasses: Int = 3): List<String> {
        if (words.isEmpty()) return emptyList()
        var lemmas = words.map { content.lookup(contentKey(it)) ?: normalize(it) }
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

    /** Per-grade histogram for one sentence at its fixpoint — how much context the index actually used. */
    fun gradeHistogram(words: List<String>): Map<MatchGrade?, Int> {
        val lemmas = lemmatize(words)
        val counts = mutableMapOf<MatchGrade?, Int>()
        for (i in words.indices) {
            val prev = if (i > 0) lemmas[i - 1] else null
            val nxt = if (i < words.lastIndex) lemmas[i + 1] else null
            val g = resolve(words[i], prev, nxt).grade
            counts[g] = (counts[g] ?: 0) + 1
        }
        return counts
    }

    companion object {
        fun normalize(word: String): String = word.trim().lowercase()

        fun wordCid(word: String): ContentId = ContentId.of(normalize(word).encodeToByteArray())
        fun lemmaCid(lemma: String?): ContentId? = lemma?.let { ContentId.of(normalize(it).encodeToByteArray()) }

        fun linkedKey(word: String, prevLemma: String?, nextLemma: String?): String =
            LineCas.stamp(lemmaCid(prevLemma), lemmaCid(nextLemma)).hex + ":" + wordCid(word).hex

        fun prevKey(word: String, prevLemma: String?): String =
            LineCas.neighborHex(lemmaCid(prevLemma)) + "**:" + wordCid(word).hex

        fun nextKey(word: String, nextLemma: String?): String =
            "**" + LineCas.neighborHex(lemmaCid(nextLemma)) + ":" + wordCid(word).hex

        fun contentKey(word: String): String = wordCid(word).hex

        /**
         * Freeze a reference corpus into the four rungs. Majority vote per key; ties resolve to the
         * first-seen lemma so the result is deterministic for a given observation order.
         */
        fun freeze(observations: List<LemmaObservation>, seed: Long = 0L, slack: Double = 0.20): FunnelLemmatizer =
            FunnelLemmatizer(
                linked = rung(observations, seed, slack) { linkedKey(it.word, it.prevLemma, it.nextLemma) },
                prevOnly = rung(observations, seed, slack) { prevKey(it.word, it.prevLemma) },
                nextOnly = rung(observations, seed, slack) { nextKey(it.word, it.nextLemma) },
                content = rung(observations, seed, slack) { contentKey(it.word) },
                seed = seed,
            )

        private fun rung(
            observations: List<LemmaObservation>,
            seed: Long,
            slack: Double,
            keyOf: (LemmaObservation) -> String,
        ): Rung {
            val votes = LinkedHashMap<String, LinkedHashMap<String, Int>>()
            for (o in observations) {
                val perKey = votes.getOrPut(keyOf(o)) { LinkedHashMap() }
                val lemma = normalize(o.lemma)
                perKey[lemma] = (perKey[lemma] ?: 0) + 1
            }
            val keys = votes.keys.toList()
            val lemmas = Array(keys.size) { i ->
                var best: String? = null
                var bestCount = -1
                for ((lemma, count) in votes.getValue(keys[i])) {
                    if (count > bestCount) { best = lemma; bestCount = count }
                }
                best ?: keys[i]
            }
            return Rung(FunnelHashIndex.build(keys.toSeries(), seed, slack), lemmas)
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
