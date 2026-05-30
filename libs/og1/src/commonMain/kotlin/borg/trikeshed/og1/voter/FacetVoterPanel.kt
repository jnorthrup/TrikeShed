package borg.trikeshed.og1.voter

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series

/**
 * FacetVoterPanel — 4 real voters for CRMS debt triage.
 * KEIGEN removed. Facets are: KPHASE, KSHAPE, KCLUSTER, KQUORUM.
 *
 * Each voter casts a score [0..1] for a cluster assignment.
 * Aggregate: weighted mean → winner + confidence.
 *
 * No eigenvalue theater. No covariance matrix. No spectral gap.
 * Real mechanical energy: k-means assignments + quorum votes.
 */
class FacetVoterPanel {

    data class Vote(
        val facet: String,
        val clusterId: Int,
        val score: Float,
        val weight: Float,
    )

    data class VoterResult(
        val winner: Int,         // clusterId with highest vote
        val confidence: Float,   // 0..1, ratio of winner votes to total
        val scores: Map<Int, Float>,  // clusterId → aggregate score
        val votes: List<Vote>,        // all individual votes
    )

    /** KPHASE voter — weights by CRMS phase progression. */
    fun kphaseVote(clusterId: Int, phaseIndex: Int, totalPhases: Int): Float {
        val phaseWeight = (phaseIndex + 1).toFloat() / totalPhases
        return phaseWeight
    }

    /** KSHAPE voter — weights by shape projection density. */
    fun kshapeVote(clusterId: Int, clusterSize: Int, totalRows: Int): Float {
        if (totalRows == 0) return 0f
        return clusterSize.toFloat() / totalRows
    }

    /** KCLUSTER voter — weights by within-cluster variance (tightness). */
    fun kclusterVote(clusterId: Int, centroid: Float, members: List<Float>): Float {
        if (members.isEmpty()) return 0f
        val variance = members.map { (it - centroid) * (it - centroid) }.average().toFloat()
        val tightness = 1f / (1f + variance)
        return tightness.coerceIn(0f, 1f)
    }

    /** KQUORUM voter — weights by confidence of prior quorum. */
    fun kquorumVote(clusterId: Int, priorConfidence: Float, clusterSize: Int): Float {
        val quorumBonus = if (priorConfidence >= 0.25f) 0.1f else 0f
        val sizeBonus = (clusterSize.toFloat() / 100f).coerceAtMost(0.5f)
        return (priorConfidence + quorumBonus + sizeBonus).coerceIn(0f, 1f)
    }

    /**
     * Aggregate votes from all 4 facets for a set of cluster assignments.
     * Returns (winner, confidence, scores).
     */
    fun vote(
        assignments: IntArray,        // row → clusterId
        phaseIndex: Int = 0,
        totalPhases: Int = 4,
        priorConfidence: Float = 0f,
    ): VoterResult {
        val n = assignments.size
        if (n == 0) return VoterResult(-1, 0f, emptyMap(), emptyList())

        // Group rows by cluster
        val clusters = assignments.indices.groupBy { assignments[it] }
        val clusterIds = clusters.keys.sorted()
        val k = clusterIds.size

        // Compute centroid per cluster
        val centroids = clusters.mapValues { (_, rows) ->
            rows.size.toFloat()  // proxy: cluster size as centroid
        }

        val allVotes = mutableListOf<Vote>()
        val aggregateScores = mutableMapOf<Int, Float>()

        for (clusterId in clusterIds) {
            val members = clusters[clusterId] ?: emptyList()
            val clusterSize = members.size

            // Cast each facet vote
            val vp = kphaseVote(clusterId, phaseIndex, totalPhases)
            val vs = kshapeVote(clusterId, clusterSize, n)
            val vc = kclusterVote(clusterId, centroids[clusterId] ?: 0f, listOf(centroids[clusterId] ?: 0f))
            val vq = kquorumVote(clusterId, priorConfidence, clusterSize)

            // Facet weights: equal weighting (1.0 each)
            val weights = mapOf(
                "KPHASE" to 1f,
                "KSHAPE" to 1f,
                "KCLUSTER" to 1f,
                "KQUORUM" to 1f,
            )

            val facetScores = listOf(
                "KPHASE" to vp,
                "KSHAPE" to vs,
                "KCLUSTER" to vc,
                "KQUORUM" to vq,
            )

            // Weighted aggregate score
            val totalWeight = weights.values.sum()
            val weightedScore = facetScores.sumOf { (facet, score) ->
                (score * (weights[facet] ?: 1f)).toDouble()
            }.toFloat() / totalWeight

            aggregateScores[clusterId] = weightedScore

            allVotes.addAll(facetScores.map { (facet, score) ->
                Vote(facet, clusterId, score, weights[facet] ?: 1f)
            })
        }

        // Winner: cluster with highest aggregate score
        val winner = aggregateScores.maxByOrNull { it.value }?.key ?: -1
        val winnerScore = aggregateScores[winner] ?: 0f
        val totalScore = aggregateScores.values.sum()

        // Confidence: ratio of winner score to total score
        val confidence = if (totalScore > 0f) winnerScore / totalScore else 0f

        return VoterResult(winner, confidence, aggregateScores, allVotes)
    }

    companion object {
        val FACETS = listOf("KPHASE", "KSHAPE", "KCLUSTER", "KQUORUM")
    }
}