package borg.trikeshed.splat

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A single anisotropic Gaussian in the joint input-output feature space.
 *
 * In NGS terms, this represents one "parameter Gaussian" with:
 * - mean: center in ℝ^{d_in + d_out} (concatenated input features + output target)
 * - covariance: precision matrix factorized as scale * rotation for efficiency
 * - opacity: global importance weight α ∈ [0,1]
 * - localTransform: coefficients of low-order polynomial mapping within support
 */
data class ParameterGaussian(
    /** Mean vector μ_i ∈ ℝ^{featDim} where featDim = d_in + d_out */
    val mean: Series<Double>,

    /** Diagonal scale vector s_i ∈ ℝ^{featDim} (Σ = R * diag(s^2) * R^T) */
    val scale: Series<Double>,

    /** Rotation matrix R_i ∈ ℝ^{featDim × featDim} stored row-major as Series<Series<Double>> */
    val rotation: Series<Series<Double>>,

    /** Opacity/importance α_i ∈ [0,1] */
    val opacity: Double,

    /** Local transform coefficients T_i for polynomial mapping (degree ≤ 2) */
    val localTransform: LocalTransform,
) {
    val featDim: Int get() = mean.size

    /** Compute Mahalanobis distance squared: (x - μ)^T Σ^{-1} (x - μ) */
    fun mahalanobisSq(x: Series<Double>): Double {
        // whitened = R^T * (x - μ) ./ scale
        val diff = x.size.j { i -> x[i] - mean[i] }
        val rotated = rotation.size.j { row ->
            diff.size.j { col -> rotation[row][col] * diff[col] }.view.sum()
        }
        return rotated.size.j { i ->
            val s = scale[i]
            if (s > 0.0) (rotated[i] / s) * (rotated[i] / s) else 0.0
        }.view.sum()
    }

    /** Gaussian kernel value: α * exp(-0.5 * mahalanobisSq) */
    fun kernelValue(x: Series<Double>): Double =
        opacity * exp(-0.5 * mahalanobisSq(x))

    /** Evaluate local transform T_i(x) → output prediction */
    fun evaluateLocal(x: Series<Double>): Series<Double> =
        localTransform.evaluate(x)

    /** Create a deep copy with mutable buffers for gradient accumulation */
    fun mutableCopy(): MutableParameterGaussian =
        MutableParameterGaussian(
            mean = mean.view.toMutableList().toSeries(),
            scale = scale.view.toMutableList().toSeries(),
            rotation = rotation.view.map { it.view.toMutableList().toSeries() }.toSeries(),
            opacity = opacity,
            localTransform = localTransform.mutableCopy(),
        )
}

/** Mutable version for gradient accumulation during training */
class MutableParameterGaussian(
    var mean: Series<Double>,
    var scale: Series<Double>,
    var rotation: Series<Series<Double>>,
    var opacity: Double,
    var localTransform: LocalTransform.Mutable,
) {
    val featDim: Int get() = mean.size

    fun toImmutable(): ParameterGaussian =
        ParameterGaussian(mean, scale, rotation, opacity, localTransform.toImmutable())
}

/** Low-order polynomial local transform: T(x) = W_0 + W_1 x + x^T W_2 x */
data class LocalTransform(
    /** Bias term W_0 ∈ ℝ^{d_out} */
    val bias: Series<Double>,

    /** Linear term W_1 ∈ ℝ^{d_out × d_in} (row-major) */
    val linear: Series<Series<Double>>,

    /** Quadratic term W_2 ∈ ℝ^{d_out × d_in × d_in} — stored as symmetric matrices per output */
    val quadratic: Series<Series<Series<Double>>>,
) {
    val dOut: Int get() = bias.size
    val dIn: Int get() = linear.firstOrNull()?.size ?: 0

    fun evaluate(x: Series<Double>): Series<Double> =
        dOut.j { outIdx ->
            var acc = bias[outIdx]
            // Linear term
            acc += dIn.j { inIdx -> linear[outIdx][inIdx] * x[inIdx] }.view.sum()
            // Quadratic term (diagonal only for efficiency)
            acc += dIn.j { inIdx -> quadratic[outIdx][inIdx][inIdx] * x[inIdx] * x[inIdx] }.view.sum()
            acc
        }

    fun mutableCopy(): Mutable =
        Mutable(
            bias = bias.view.toMutableList().toSeries(),
            linear = linear.view.map { it.view.toMutableList().toSeries() }.toSeries(),
            quadratic = quadratic.view.map { it.view.map { it.view.toMutableList().toSeries() }.toSeries() }.toSeries(),
        )

    data class Mutable(
        var bias: Series<Double>,
        var linear: Series<Series<Double>>,
        var quadratic: Series<Series<Series<Double>>>,
    ) {
        fun toImmutable(): LocalTransform = LocalTransform(bias, linear, quadratic)
    }
}

/** Gradient accumulators for one Gaussian (matching NGS: ∇μ, ∇Σ, ∇α, ∇T) */
data class GaussianGradients(
    /** ∇_μ ∈ ℝ^{featDim} — positional gradient */
    val dMean: Series<Double>,

    /** ∇_scale ∈ ℝ^{featDim} — scale gradient (diagonal of ∇_Σ) */
    val dScale: Series<Double>,

    /** ∇_rotation ∈ ℝ^{featDim × featDim} — rotation gradient */
    val dRotation: Series<Series<Double>>,

    /** ∇_α ∈ ℝ — opacity gradient */
    val dOpacity: Double,

    /** ∇_T — local transform gradients */
    val dLocalTransform: LocalTransform,
) {
    /** Zero gradients */
    companion object {
        fun zero(featDim: Int, dOut: Int, dIn: Int): GaussianGradients =
            GaussianGradients(
                dMean = featDim.j { 0.0 },
                dScale = featDim.j { 0.0 },
                dRotation = featDim.j { featDim.j { 0.0 } },
                dOpacity = 0.0,
                dLocalTransform = LocalTransform(
                    bias = dOut.j { 0.0 },
                    linear = dOut.j { dIn.j { 0.0 } },
                    quadratic = dOut.j { dIn.j { dIn.j { 0.0 } } },
                ),
            )
    }

    /** In-place addition for accumulation */
    fun add(other: GaussianGradients): GaussianGradients = GaussianGradients(
        dMean = dMean.size.j { i -> dMean[i] + other.dMean[i] },
        dScale = dScale.size.j { i -> dScale[i] + other.dScale[i] },
        dRotation = dRotation.size.j { r -> dRotation[r].size.j { c -> dRotation[r][c] + other.dRotation[r][c] } },
        dOpacity = dOpacity + other.dOpacity,
        dLocalTransform = LocalTransform(
            bias = dLocalTransform.bias.size.j { i -> dLocalTransform.bias[i] + other.dLocalTransform.bias[i] },
            linear = dLocalTransform.linear.size.j { r -> dLocalTransform.linear[r].size.j { c -> dLocalTransform.linear[r][c] + other.dLocalTransform.linear[r][c] } },
            quadratic = dLocalTransform.quadratic.size.j { r -> dLocalTransform.quadrant[r].size.j { c -> dLocalTransform.quadratic[r][c].size.j { d -> dLocalTransform.quadratic[r][c][d] + other.dLocalTransform.quadratic[r][c][d] } } },
        ),
    )
}

/** Spatial hash grid for k-nearest neighbor search in feature space */
class SpatialHashGrid(
    private val cellSize: Double,
    private val featDim: Int,
) {
    /** Hash a point to grid cell coordinates */
    private fun hashPoint(x: Series<Double>): Series<Int> =
        featDim.j { i -> (x[i] / cellSize).floor().toInt() }

    /** Hash cell coordinates to a single integer key */
    private fun cellKey(cell: Series<Int>): Long {
        var h = 0L
        cell.view.forEach { h = h * 31 + it.toLong() }
        return h
    }

    /** Internal storage: cellKey → list of Gaussian indices */
    private val grid = mutableMapOf<Long, MutableList<Int>>()

    /** Insert a Gaussian at its mean position */
    fun insert(gaussianIndex: Int, gaussian: ParameterGaussian) {
        val cell = hashPoint(gaussian.mean)
        val key = cellKey(cell)
        grid.getOrPut(key) { mutableListOf() }.add(gaussianIndex)
    }

    /** Remove a Gaussian (for pruning) */
    fun remove(gaussianIndex: Int, gaussian: ParameterGaussian) {
        val cell = hashPoint(gaussian.mean)
        val key = cellKey(cell)
        grid[key]?.remove(gaussianIndex)
        if (grid[key]?.isEmpty() == true) grid.remove(key)
    }

    /** Find k-nearest Gaussians to query point x */
    fun kNearest(
        x: Series<Double>,
        gaussians: Series<ParameterGaussian>,
        k: Int,
        maxSearchRadius: Int = 3,
    ): Series<Join<Int, Double>> { // Join<gaussianIndex, distanceSq>
        val queryCell = hashPoint(x)
        val candidates = mutableListOf<Join<Int, Double>>()

        // Expand search radius until we have enough candidates or hit max
        var radius = 0
        while (candidates.size < k && radius <= maxSearchRadius) {
            // Iterate over cells in Chebyshev radius
            val offsets = generateOffsets(featDim, radius)
            for (offset in offsets) {
                val neighborCell = queryCell.size.j { i -> queryCell[i] + offset[i] }
                val key = cellKey(neighborCell)
                grid[key]?.forEach { idx ->
                    val distSq = gaussians[idx].mahalanobisSq(x)
                    candidates.add(idx.j(distSq))
                }
            }
            radius++
        }

        // Sort by distance and take top-k
        return candidates.sortedBy { it.b }.take(k).toSeries()
    }

    /** Generate all offset vectors within Chebyshev radius */
    private fun generateOffsets(dim: Int, radius: Int): Series<Series<Int>> {
        if (radius == 0) return 1.j { _ -> dim.j { 0 } }
        val offsets = mutableListOf<Series<Int>>()
        fun recurse(depth: Int, current: MutableList<Int>) {
            if (depth == dim) {
                offsets.add(current.toSeries())
                return
            }
            for (d in -radius..radius) {
                current.add(d)
                recurse(depth + 1, current)
                current.removeAt(current.lastIndex)
            }
        }
        recurse(0, mutableListOf())
        return offsets.toSeries()
    }

    /** Clear all entries */
    fun clear() = grid.clear()
}

extension Series<Double> {
    fun sum(): Double = view.sum()
}

extension Series<Int> {
    fun floor(): Series<Int> = this.α { it.floor().toInt() }
}

/** Helper to convert List to Series */
fun <T> List<T>.toSeries(): Series<T> =
    size.j { i -> this[i] }