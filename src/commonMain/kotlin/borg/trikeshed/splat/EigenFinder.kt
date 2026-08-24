package borg.trikeshed.splat

import borg.trikeshed.lib.Shape
import borg.trikeshed.lib.Tensor
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.shapeOf
import borg.trikeshed.lib.size
import kotlin.math.abs
import kotlin.math.sqrt

/** Recovered from `accd8861c`; finds a compact eigen signature among probabilistic splat dimensions. */
interface EigenFinder<T> {
    /** Rank-1 tensor: principal eigenvalue followed by its normalized eigenvector. */
    fun extractSignature(splat: Splat<T>): Tensor<Double>
}

/** Pure Kotlin power iteration over the splat's diagonal probability covariance. */
class PowerIterationEigenFinder<T>(
    private val iterations: Int = 32,
    private val tolerance: Double = 1e-9,
) : EigenFinder<T> {
    init {
        require(iterations > 0)
        require(tolerance > 0.0)
    }

    override fun extractSignature(splat: Splat<T>): Tensor<Double> {
        val dimensions = splat.size
        if (dimensions == 0) return shapeOf(1) j { _: Shape -> 0.0 }
        val vector = DoubleArray(dimensions) { 1.0 / sqrt(dimensions.toDouble()) }
        var eigenvalue = 0.0
        for (iteration in 0 until iterations) {
            val next = DoubleArray(dimensions) { i -> vector[i] * splat[i].b }
            var rayleigh = 0.0
            var normSq = 0.0
            for (i in 0 until dimensions) {
                rayleigh += vector[i] * next[i]
                normSq += next[i] * next[i]
            }
            eigenvalue = rayleigh
            val norm = sqrt(normSq)
            if (norm <= tolerance) break
            var delta = 0.0
            for (i in 0 until dimensions) {
                val normalized = next[i] / norm
                delta += abs(normalized - vector[i])
                vector[i] = normalized
            }
            if (delta <= tolerance) break
        }
        return shapeOf(dimensions + 1) j { index: Shape ->
            val offset = index[0]
            if (offset == 0) eigenvalue else vector[offset - 1]
        }
    }
}
