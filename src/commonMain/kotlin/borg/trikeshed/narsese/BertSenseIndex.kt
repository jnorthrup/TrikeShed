package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.math.sqrt

/** An earlier label and its source, never a new independent judgment made by BERT. */
data class BertSenseEvidence(val source: String, val sense: String, val vector: BertVector, val table: String? = null)
data class BertSenseNeighbor(val evidence: BertSenseEvidence, val cosine: Double)
enum class BertSenseStatus { Supported, NoVector, InsufficientEvidence, Disagreement, OutsideMenu, Unavailable }
data class BertSense(val sense: String?, val status: BertSenseStatus, val neighbors: Series<BertSenseNeighbor>)

/** Same-lemma seven-neighbour propagation recovered from bert_vs_caps.py; all seven must agree. */
class BertSenseIndex(val model: String, private val evidence: Series<BertSenseEvidence>) {
    private val vectors = Array(evidence.size) { i -> evidence[i].vector.coordinates?.let(::normalize) }
    init {
        require(model.isNotBlank())
        require(evidence.view.all { it.source.isNotBlank() && it.sense.isNotBlank() })
        require(evidence.view.map { it.source to it.vector.token.lemma }.distinct().size == evidence.size) {
            "A source may supply only one independent label for a lemma"
        }
        val dimensions = vectors.filterNotNull().map { it.size }.distinct()
        require(dimensions.size <= 1) { "BERT evidence has incompatible dimensions" }
    }

    /** [source] is excluded to prevent the query voting for itself. No menu filtering before the vote. */
    fun sense(query: BertVector, source: String, menu: Series<String>): BertSense {
        val q = query.coordinates?.let(::normalize)
            ?: return BertSense(null, BertSenseStatus.NoVector, emptySeriesOf())
        val selected = IntArray(7) { -1 }
        val scores = DoubleArray(7) { Double.NEGATIVE_INFINITY }
        var count = 0
        for (i in 0 until evidence.size) {
            val row = evidence[i]
            if (row.source == source || row.vector.token.lemma != query.token.lemma ||
                row.vector.token.sentence == query.token.sentence) continue
            val vector = vectors[i] ?: continue
            require(vector.size == q.size) { "BERT query and evidence dimensions differ" }
            var cosine = 0.0
            for (d in q.indices) cosine += q[d] * vector[d]
            var position = minOf(count, 7)
            while (position > 0 && cosine > scores[position - 1]) position--
            if (position == 7) continue
            for (j in 6 downTo position + 1) { selected[j] = selected[j - 1]; scores[j] = scores[j - 1] }
            selected[position] = i; scores[position] = cosine
            count = minOf(count + 1, 7)
        }
        // A receipt retains seven neighbors, not the whole index and its vector matrix.
        val neighbors = Array(count) { i -> BertSenseNeighbor(evidence[selected[i]], scores[i]) }.toSeries()
        if (count < 7) return BertSense(null, BertSenseStatus.InsufficientEvidence, neighbors)
        val sense = neighbors[0].evidence.sense
        if (neighbors.view.any { it.evidence.sense != sense }) return BertSense(null, BertSenseStatus.Disagreement, neighbors)
        if (menu.view.none { it == sense }) return BertSense(null, BertSenseStatus.OutsideMenu, neighbors)
        return BertSense(sense, BertSenseStatus.Supported, neighbors)
    }

    private fun normalize(values: Series<Float>): DoubleArray? {
        require(values.size > 0 && values.view.all { it.isFinite() }) { "Invalid BERT vector" }
        val length = sqrt(values.view.sumOf { it.toDouble() * it })
        return if (length == 0.0) null else DoubleArray(values.size) { values[it] / length }
    }
}
