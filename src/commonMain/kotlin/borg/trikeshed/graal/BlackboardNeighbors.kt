package borg.trikeshed.graal

import borg.trikeshed.collections.bits.IntAccumulator
import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoClassId
import borg.trikeshed.ontology.SumoClassifier
import borg.trikeshed.ontology.SumoMask
import kotlin.math.ln
import kotlin.math.sqrt

/** A neighboring key paired with its evidence and source metadata. */
typealias BlackboardNeighbor = Join<String, FacetedRow<NeighborK<*>>>

sealed class NeighborK<out R> : OpK<R>() {
    data object Score : NeighborK<Double>()
    data object Concepts : NeighborK<Series<String>>()
    data object Terms : NeighborK<Series<String>>()
    data object References : NeighborK<Series<Twin<String>>>()
    data object Provenance : NeighborK<ConfixBlackboard.ProvenanceEntry?>()
}

@Suppress("UNCHECKED_CAST")
operator fun <R> FacetedRow<NeighborK<*>>.get(key: NeighborK<R>): R = b(key) as R

/**
 * One revision of the whole board, without namespace or actor filters. SUMO closure
 * supplies conceptual overlap; lexical overlap and exact key references remain
 * separate evidence. Nothing here asserts new facts or requires a model session.
 */
class BlackboardNeighbors(val snapshot: ConfixBlackboard.Snapshot, val sumo: SumoClassifier) {
    val terms = HashMap<String, Set<String>>()
    val concepts = HashMap<String, RoaringSeries>()
    val references = HashMap<String, MutableSet<String>>()
    val postings = HashMap<String, MutableSet<String>>()
    val weights = HashMap<String, Double>()
    val norms = HashMap<String, Double>()
    /** Keys whose values contain an opaque function/object or a cyclic/deep structure. */
    val opaque = HashSet<String>()
    val aliases = HashMap<String, String>()
    var phraseLength = 1

    init {
        for (term in sumo.terms.view) {
            val words = words(term)
            aliases[words.view.joinToString(" ")] = term
            phraseLength = maxOf(phraseLength, words.size)
        }
        for ((key, value) in snapshot.values) {
            val tokens = HashSet<String>()
            val ids = IntAccumulator()
            val ancestry = mutableListOf<Any>()
            fun text(text: String) {
                if (text != key && snapshot.values.containsKey(text))
                    references.getOrPut(key) { HashSet() }.add(text)
                val words = words(text)
                for (word in words.view) if (word.length > 1 && word !in STOP) tokens.add(word)
                for (i in 0 until words.size) {
                    val phrase = StringBuilder()
                    for (end in i until minOf(words.size, i + phraseLength)) {
                        if (end > i) phrase.append(' ')
                        phrase.append(words[end])
                        val term = aliases[phrase.toString()] ?: continue
                        val cls = sumo.classId(term)
                        if (cls != null) {
                            ids.add(cls.value)
                            ids.addAll(sumo.mask(term, SumoMask.ANCESTORS))
                        } else ids.addAll(sumo.mask(term, SumoMask.INSTANCES))
                    }
                }
            }
            fun visit(value: Any?) {
                when (value) {
                    null, is Number, is Boolean -> return
                    is CharSequence -> { text(value.toString()); return }
                    is Char -> { text(value.toString()); return }
                }
                if (ancestry.size >= 128 || ancestry.any { it === value }) { opaque.add(key); return }
                ancestry.add(value!!)
                when (value) {
                    is Map<*, *> -> value.forEach { (k, v) -> visit(k); visit(v) }
                    is Iterable<*> -> value.forEach { visit(it) }
                    is Array<*> -> value.forEach { visit(it) }
                    is Join<*, *> -> {
                        if (value.a is Int && value.b is Function1<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val series = value as Series<Any?>
                            if (series.size > 0 && series[0] is Char) text(series.view.joinToString(""))
                            else for (item in series.view) visit(item)
                        } else if (value.b is Function1<*, *>) {
                            // An arbitrary MetaSeries has no enumerable key domain.
                            opaque.add(key)
                        } else { visit(value.a); visit(value.b) }
                    }
                    else -> opaque.add(key)
                }
                ancestry.removeAt(ancestry.lastIndex)
            }
            text(key.substringAfterLast('/'))
            visit(value)
            terms[key] = tokens
            concepts[key] = ids.toRoaring()
            for (token in tokens) postings.getOrPut("t:$token") { HashSet() }.add(key)
            concepts.getValue(key).forEach { id -> postings.getOrPut("c:$id") { HashSet() }.add(key) }
        }
        for ((feature, keys) in postings) {
            val specificity = if (feature.startsWith("c:")) {
                val name = sumo.className(SumoClassId(feature.substring(2).toInt()))
                ln(sumo.classCount.toDouble() / (sumo.mask(name, SumoMask.DESCENDANTS).cardinality + 1.0))
            } else 1.0
            val weight = specificity * ln(1.0 + snapshot.values.size.toDouble() / keys.size)
            weights[feature] = weight
            for (key in keys) norms[key] = (norms[key] ?: 0.0) + weight * weight
        }
    }

    /** Weighted cosine over postings, followed by a stable key tie-break; score is not confidence. */
    fun neighbors(key: String, limit: Int = 32): Series<BlackboardNeighbor> {
        require(snapshot.values.containsKey(key)) { "No such blackboard key: $key" }
        require(limit >= 0)
        val dot = HashMap<String, Double>()
        fun accumulate(feature: String) {
            val weight = weights[feature] ?: return
            if (weight == 0.0) return
            for (other in postings.getValue(feature)) if (other != key)
                dot[other] = (dot[other] ?: 0.0) + weight * weight
        }
        for (term in terms.getValue(key)) accumulate("t:$term")
        concepts.getValue(key).forEach { accumulate("c:$it") }
        val refs = HashMap<String, SeriesBuffer<Twin<String>>>()
        for (target in references[key].orEmpty()) refs.getOrPut(target) { SeriesBuffer() }.add(key j target)
        for ((source, targets) in references) if (key in targets)
            refs.getOrPut(source) { SeriesBuffer() }.add(source j key)
        for (target in refs.keys) dot.getOrPut(target) { 0.0 }
        val result = SeriesBuffer<BlackboardNeighbor>()
        val classifier = sumo
        for ((other, product) in dot) {
            val norm = sqrt((norms[key] ?: 0.0) * (norms[other] ?: 0.0))
            val score = if (norm > 0.0) (product / norm).coerceIn(0.0, 1.0) else 0.0
            val shared = concepts.getValue(key) and concepts.getValue(other)
            val sharedConcepts = shared.members.filter { (weights["c:$it"] ?: 0.0) > 0.0 } α { classifier.className(SumoClassId(it)) }
            val sharedTerms = SeriesBuffer<String>().apply {
                for (term in terms.getValue(key)) if (term in terms.getValue(other)) add(term)
                sortWith(compareBy { it })
            }.drain()
            val edges = refs[other]?.drain() ?: emptySeriesOf()
            val provenance = snapshot.provenance[other]
            val row: FacetedRow<NeighborK<*>> = NeighborK.Score j { facet: NeighborK<*> ->
                when (facet) {
                    NeighborK.Score -> score
                    NeighborK.Concepts -> sharedConcepts
                    NeighborK.Terms -> sharedTerms
                    NeighborK.References -> edges
                    NeighborK.Provenance -> provenance
                }
            }
            result.add(other j row)
        }
        result.sortWith(compareByDescending<BlackboardNeighbor> { it.b[NeighborK.References].size > 0 }
            .thenByDescending { it.b[NeighborK.Score] }.thenBy { it.a })
        return result.drain().take(limit)
    }

    companion object {
        val CAMEL = Regex("([a-z])([A-Z])")
        val WORD = Regex("[\\p{L}][\\p{L}\\p{N}]*")
        val STOP = setOf("a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it", "of", "on", "or", "that", "the", "this", "to", "was", "with")
        fun words(text: String): Series<String> = SeriesBuffer<String>().apply {
            for (match in WORD.findAll(CAMEL.replace(text, "$1 $2").lowercase())) add(match.value)
        }.drain()
    }
}
