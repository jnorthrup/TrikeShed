package borg.trikeshed.og1.shape

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.og1.state.CrmsPhase
import kotlin.math.sqrt

/**
 * EigenResult — result of eigensolve on a ShapeCursor projection.
 * eigenvalue = dominance (1.0 = dominant, 0.0 = subordinate)
 * gap = spectral gap (difference between top two eigenvalues)
 * components = eigenvector components per column
 * rank = 0 for most dominant
 */
data class CrmsEigenResult(
    val eigenvalue: Float,
    val gap: Float,
    val components: FloatArray,
    val rank: Int,
    val clusterOf: IntArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CrmsEigenResult
        return eigenvalue == other.eigenvalue &&
                gap == other.gap &&
                rank == other.rank &&
                components.contentEquals(other.components) &&
                clusterOf?.contentEquals(other.clusterOf) == true
    }

    override fun hashCode(): Int {
        var result = eigenvalue.hashCode()
        result = 31 * result + gap.hashCode()
        result = 31 * result + components.contentHashCode()
        result = 31 * result + rank
        return result
    }
}

/**
 * CrmsEigensolver — reads ShapeCursor.body, ranks by eigenvalue.
 * Confix re-join: ShapeCursorBox.body is re-ranked when watermark advances.
 */
class CrmsEigensolver {

    /**
     * Solve eigenvectors for all shapes in the blackboard at the given phase.
     * Phase determines which statistical path is used.
     */
    fun eigensolve(blackboard: Blackboard, phase: CrmsPhase): Map<Shape, CrmsEigenResult> {
        return blackboard.allShapes().associateWith { shape ->
            val sc = blackboard.fetch(shape) ?: return@associateWith emptyResult()
            eigensolve(sc, phase)
        }
    }

    /**
     * Eigensolve a single ShapeCursor at the given phase.
     * GAP: correlation matrix → eigenvectors
     * KMEANS: cluster assignment → dominant eigenvector
     * QUORUM: dominant eigenvalue only
     * DELIVER: eigenvalue = 1 (all pass)
     * MONITOR: eigenvalue = 0 (drift)
     */
    fun eigensolve(sc: ShapeCursor, phase: CrmsPhase): CrmsEigenResult {
        val cursor = sc.cursor
        if (cursor.a < 2) return emptyResult()

        return when (phase) {
            CrmsPhase.BRAINSTORM -> correlationEigen(cursor, sc.shape)
            CrmsPhase.GAP        -> gapEigen(cursor, sc.shape)
            CrmsPhase.KMEANS     -> kMeansEigen(cursor, sc.shape)
            CrmsPhase.QUORUM     -> dominantEigen(cursor, sc.shape)
            CrmsPhase.DELIVER    -> deliverEigen(cursor, sc.shape)
            CrmsPhase.MONITOR    -> monitorEigen(cursor, sc.shape)
            else                 -> emptyResult()
        }
    }

    private fun correlationEigen(cursor: Series<RowVec>, shape: Shape): CrmsEigenResult {
        val metricCols = shape.dropLast(5)  // last 5 = date axes
        val n = cursor.a
        val k = metricCols.size
        if (k < 1) return emptyResult()
        val matrix = Array(k) { FloatArray(k) }

        for (i in 0 until k) {
            for (j in i until k) {
                matrix[i][j] = cov(cursor, metricCols[i], metricCols[j])
                if (i != j) matrix[j][i] = matrix[i][j]
            }
        }

        return powerIteration(matrix)
    }

    private fun gapEigen(cursor: Series<RowVec>, shape: Shape): CrmsEigenResult {
        val corr = correlationEigen(cursor, shape)
        val sorted = corr.components.sortedDescending()
        val gap = if (sorted.size >= 2) sorted[0] - sorted[1] else sorted.firstOrNull() ?: 0f
        return corr.copy(gap = gap)
    }

    private fun kMeansEigen(cursor: Series<RowVec>, shape: Shape): CrmsEigenResult {
        val k = maxOf(2, minOf(sqrt(cursor.a.toDouble()).toInt(), 8))
        val clusters = kMeansAssign(cursor, shape, k)
        return dominantClusterEigen(cursor, clusters)
    }

    private fun dominantEigen(cursor: Series<RowVec>, shape: Shape): CrmsEigenResult {
        val corr = correlationEigen(cursor, shape)
        return CrmsEigenResult(
            eigenvalue = corr.components.maxOrNull() ?: 0f,
            gap = corr.gap,
            components = corr.components,
            rank = 0,
        )
    }

    private fun deliverEigen(cursor: Series<RowVec>, shape: Shape): CrmsEigenResult =
        CrmsEigenResult(1f, 1f, floatArrayOf(1f), 0)

    private fun monitorEigen(cursor: Series<RowVec>, shape: Shape): CrmsEigenResult =
        CrmsEigenResult(0f, 0f, floatArrayOf(0f), -1)

    private fun emptyResult(): CrmsEigenResult = CrmsEigenResult(0f, 0f, floatArrayOf(), -1)

    // ── Statistics helpers ──────────────────────────────────────

    /** Column value from a RowVec at column index. */
    private fun colValue(row: RowVec, col: Int): Float {
        val cell: Any? = row.b(col).a
        return (cell as? Number)?.toFloat() ?: 0f
    }

    private fun cov(cursor: Series<RowVec>, colA: Int, colB: Int): Float {
        val n = cursor.a
        if (n < 2) return 0f
        var sumA = 0.0
        var sumB = 0.0
        var sumAB = 0.0
        for (i in 0 until n) {
            val row = cursor.b(i)
            val a = colValue(row, colA)
            val b = colValue(row, colB)
            sumA += a
            sumB += b
            sumAB += a * b
        }
        val meanA = sumA / n
        val meanB = sumB / n
        return ((sumAB / n) - meanA * meanB).toFloat()
    }

    private fun kMeansAssign(cursor: Series<RowVec>, shape: Shape, k: Int): IntArray {
        val n = cursor.a
        if (n == 0) return IntArray(0)
        val centroids = FloatArray(k) { it.toFloat() / k }
        val assignments = IntArray(n)

        for (round in 0 until 20) {
            // Assign rows to nearest centroid
            for (i in 0 until n) {
                val v = metricSum(cursor.b(i), shape)
                var best = 0
                var bestDist = Float.MAX_VALUE
                for (c in 0 until k) {
                    val dist = kotlin.math.abs(v - centroids[c])
                    if (dist < bestDist) {
                        bestDist = dist
                        best = c
                    }
                }
                assignments[i] = best
            }

            // Update centroids
            val newCentroids = FloatArray(k)
            val counts = IntArray(k)
            for (i in 0 until n) {
                val c = assignments[i]
                newCentroids[c] += metricSum(cursor.b(i), shape)
                counts[c]++
            }
            for (c in 0 until k) {
                if (counts[c] > 0) centroids[c] = newCentroids[c] / counts[c]
            }
        }
        return assignments
    }

    /** Sum of metric columns for a row (all except key and date axes). */
    private fun metricSum(row: RowVec, shape: Shape): Float {
        val dateOffset = shape.size - 5.coerceAtMost(shape.size)
        var sum = 0f
        for (i in 0 until dateOffset.coerceAtLeast(0)) {
            sum += colValue(row, shape[i])
        }
        return sum
    }

    private fun dominantClusterEigen(cursor: Series<RowVec>, clusters: IntArray): CrmsEigenResult {
        val n = cursor.a
        if (n == 0) return emptyResult()
        val clusterCount = clusters.groupBy { it }.mapValues { it.value.size }
        val dominant = clusterCount.maxByOrNull { it.value }?.key ?: 0
        val count = clusterCount[dominant] ?: 0
        val eigenvalue = count.toFloat() / n
        return CrmsEigenResult(eigenvalue, eigenvalue, floatArrayOf(eigenvalue), dominant)
    }

    private fun powerIteration(matrix: Array<FloatArray>): CrmsEigenResult {
        val k = matrix.size
        if (k == 0) return emptyResult()
        var v = FloatArray(k) { 1f }

        for (iter in 0 until 100) {
            val Av = FloatArray(k)
            for (i in 0 until k) {
                for (j in 0 until k) Av[i] += matrix[i][j] * v[j]
            }
            val norm = Av.fold(0f) { s, x -> s + x * x }.let { sqrt(it.toDouble()).toFloat() }
            if (norm < 1e-10f) break
            for (i in 0 until k) v[i] = Av[i] / norm
        }

        val sorted = v.sortedDescending()
        val gap = if (sorted.size >= 2) sorted[0] - sorted[1] else sorted.firstOrNull() ?: 0f
        val eigenvalue = sorted.firstOrNull() ?: 0f

        return CrmsEigenResult(eigenvalue, gap, v, 0, null)
    }
}
