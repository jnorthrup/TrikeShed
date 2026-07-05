package borg.trikeshed.splat

import borg.trikeshed.lib.Tensor
import borg.trikeshed.lib.j
import borg.trikeshed.lib.shapeOf
import borg.trikeshed.lib.size
import kotlin.math.sqrt

/**
 * A simple implementation of EigenFinder using the Power Iteration method.
 * We treat the input splat as defining a covariance matrix based on
 * the probability (weight) of each dimension.
 * Since a Splat<T> has `size` elements and probabilities, we can treat this
 * as a diagonal covariance matrix (or simple weighted representation)
 * where we want to find the principal eigenvector/eigenvalue.
 */
class PowerIterationEigenFinder<T>(
    private val iterations: Int = 10,
    private val tolerance: Double = 1e-6
) : EigenFinder<T> {

    override fun extractSignature(splat: Splat<T>): Tensor<Double> {
        val n = splat.a
        if (n == 0) {
            return shapeOf(1) j { _ : borg.trikeshed.lib.Shape -> 0.0 }
        }

        // Initialize a random vector (here we just use uniform 1.0)
        val v = DoubleArray(n) { 1.0 }

        // Normalize initial vector
        var norm = sqrt(v.sumOf { it * it })
        if (norm > 0) {
            for (i in 0 until n) {
                v[i] /= norm
            }
        }

        var eigenvalue = 0.0

        for (iter in 0 until iterations) {
            // Apply covariance matrix. For a splat, we assume each dimension i
            // is independent with variance = prob_i. So multiply v by prob.
            val vNext = DoubleArray(n)
            for (i in 0 until n) {
                val prob = splat.b(i).b
                vNext[i] = v[i] * prob
            }

            // Calculate eigenvalue approximation (Rayleigh quotient): v^T * Cov * v
            // Since v is normalized, it's just v^T * vNext
            eigenvalue = 0.0
            for (i in 0 until n) {
                eigenvalue += v[i] * vNext[i]
            }

            // Normalize vNext
            norm = sqrt(vNext.sumOf { it * it })
            if (norm < tolerance) {
                break
            }

            var diff = 0.0
            for (i in 0 until n) {
                val newVal = vNext[i] / norm
                diff += kotlin.math.abs(newVal - v[i])
                v[i] = newVal
            }

            if (diff < tolerance) {
                break
            }
        }

        // Return as a rank-1 Tensor
        // Shape is [n + 1] to store eigenvalue as the first element, and eigenvector as the rest.
        val shape = shapeOf(n + 1)
        return shape j { idx : borg.trikeshed.lib.Shape ->
            val index = idx.b(0)
            if (index == 0) eigenvalue else v[index - 1]
        }
    }
}
