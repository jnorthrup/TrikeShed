@file:Suppress("unused")

package borg.trikeshed.og1.voter

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.og1.shape.Blackboard
import borg.trikeshed.og1.shape.ShapeCursor
import borg.trikeshed.og1.state.VoterFacet
import kotlin.math.sqrt

/* ── FacetVoterPanel — faceted k-means-seated voter panel ─────────────
 *
 *  Each voter is a VoterFacet (data class with id, cluster, weight, vote).
 *  k-means determines cluster membership in eigenvector space.
 *  QUORUM collapses remaining uncertainty to O(1) per cluster.
 * ──────────────────────────────────────────────────────────────────── */

/* ── VoterVerdict — outcome of one vote cycle ─────────────────────────── */

data class VoterVerdict(
    val winner: Int,
    val clusterOf: IntArray,
    val observations: FloatArray,
    val quorumConfidence: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoterVerdict) return false
        return winner == other.winner &&
                quorumConfidence == other.quorumConfidence &&
                clusterOf.contentEquals(other.clusterOf) &&
                observations.contentEquals(other.observations)
    }

    override fun hashCode(): Int {
        var result = winner
        result = 31 * result + clusterOf.contentHashCode()
        result = 31 * result + observations.contentHashCode()
        result = 31 * result + quorumConfidence.hashCode()
        return result
    }
}

/* ── QuorumAccumulator — collapses to dominant outcome ────────────────── */

class QuorumAccumulator {
    private var winner: Int = -1
    private var winnerVotes: Int = 0
    private var totalVotes: Int = 0

    fun accumulate(observations: FloatArray, clusterOf: IntArray): Int {
        totalVotes += observations.size
        val clusterVotes = IntArray(observations.size) { c ->
            clusterOf.count { it == c }
        }
        val dominant = clusterVotes.indices.maxByOrNull { clusterVotes[it] } ?: -1
        if (dominant >= 0 && clusterVotes[dominant] > winnerVotes) {
            winner = dominant
            winnerVotes = clusterVotes[dominant]
        }
        return winner
    }

    fun confidence(): Float = if (totalVotes > 0) winnerVotes.toFloat() / totalVotes else 0f
}

/* ── FacetVoterPanel ──────────────────────────────────────────────────── */

class FacetVoterPanel(
    val blackboard: Blackboard,
    val facets: List<VoterFacet> = defaultFacets(),
    val nVoters: Int = facets.size.coerceAtLeast(1),
) {
    private val quorumState = QuorumAccumulator()

    /** Run one vote cycle. */
    fun vote(): VoterVerdict {
        val observations = FloatArray(nVoters) { i ->
            val facet = facets.getOrElse(i) { VoterFacet("v$i", 0) }
            val sc = blackboard.fetch(shapeFor(facet)) ?: emptyCursor()
            eigenvectorComponent(sc.cursor)
        }

        val k = maxOf(2, sqrt(nVoters.toDouble()).toInt())
        val clusters = kMeansAssign(observations, k)

        val winner = quorumState.accumulate(observations, clusters)

        return VoterVerdict(
            winner = winner,
            clusterOf = clusters.copyOf(),
            observations = observations.copyOf(),
            quorumConfidence = quorumState.confidence(),
        )
    }

    private fun shapeFor(facet: VoterFacet): borg.trikeshed.og1.shape.Shape =
        facet.id.hashCode().let { h ->
            when (h % 5) {
                0 -> borg.trikeshed.og1.shape.ShapeSchema.Cascade.byEntity
                1 -> borg.trikeshed.og1.shape.ShapeSchema.Cascade.byGroup3
                2 -> borg.trikeshed.og1.shape.ShapeSchema.Cascade.byGroup2
                3 -> borg.trikeshed.og1.shape.ShapeSchema.Cascade.byGroup1
                else -> borg.trikeshed.og1.shape.ShapeSchema.Cascade.byGroup0
            }
        }

    private fun emptyCursor(): ShapeCursor = ShapeCursor(
        shape = intArrayOf(),
        cursor = emptySeries(),
        version = 0L,
    )

    private fun emptySeries(): Series<RowVec> = 0 j { throw IndexOutOfBoundsException("empty") }

    /** Average value across all rows as a proxy eigenvector component. */
    private fun eigenvectorComponent(cursor: Series<RowVec>): Float {
        val n = cursor.a
        if (n == 0) return 0f
        var sum = 0f
        for (i in 0 until n) {
            val row = cursor.b(i)
            // Sum all numeric cells as the eigenvector proxy
            for (col in 0 until row.a) {
                val cell = row.b(col).a
                sum += (cell as? Number)?.toFloat() ?: 0f
            }
        }
        return sum / n
    }

    /** Simple 1-D k-means assignment. */
    private fun kMeansAssign(obs: FloatArray, k: Int): IntArray {
        if (obs.isEmpty()) return IntArray(0)
        val n = obs.size
        val result = IntArray(n)
        val sorted = obs.sorted()
        val step = maxOf(1, n / k)
        val centroids = FloatArray(k) { i -> sorted.getOrElse(i * step) { sorted.last() } }

        for (i in 0 until n) {
            var best = 0
            var bestDist = Float.MAX_VALUE
            for (j in 0 until k) {
                val d = (obs[i] - centroids[j]).let { it * it }
                if (d < bestDist) {
                    bestDist = d
                    best = j
                }
            }
            result[i] = best
        }
        return result
    }

    companion object {
        /** Default facets derived from CrmsPhase transitions. */
        fun defaultFacets(): List<VoterFacet> = listOf(
            VoterFacet("BRAINSTORM", 0),
            VoterFacet("GAP", 1),
            VoterFacet("KMEANS", 2),
            VoterFacet("QUORUM", 3),
            VoterFacet("DELIVER", 4),
        )
    }
}
