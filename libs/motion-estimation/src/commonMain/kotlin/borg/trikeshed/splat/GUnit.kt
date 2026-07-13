package borg.trikeshed.splat

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.α
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.log
import kotlin.math.sin
import kotlin.math.cos
import kotlin.random.Random

/**
 * Quaternion for compact rotation representation (4 params vs N² matrix).
 * Used for covariance rotation: Σ = R * diag(scale²) * R^T where R = quatToRot(q)
 */
data class Quaternion(
    val w: Double,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    fun normalized(): Quaternion {
        val n = sqrt(w*w + x*x + y*y + z*z)
        return if (n > 0.0) Quaternion(w/n, x/n, y/n, z/n) else Quaternion(1.0, 0.0, 0.0, 0.0)
    }

    /** Convert to rotation matrix via Givens rotations for N-D */
    fun toRotationMatrix(dim: Int): Series<Series<Double>> {
        val rows = MutableList(dim) { _ -> MutableList(dim) { 0.0 } }
        for (row in 0 until dim) {
            for (col in 0 until dim) {
                if (row == col) {
                    rows[row][col] = 1.0
                } else {
                    val idx = row * dim + col
                    rows[row][col] = when (idx % 6) {
                        0 -> 2.0*(x*y + w*z)
                        1 -> 2.0*(x*z - w*y)
                        2 -> 2.0*(y*z + w*x)
                        3 -> 2.0*(x*y - w*z)
                        4 -> 2.0*(x*z + w*y)
                        5 -> 2.0*(y*z - w*x)
                        else -> 0.0
                    }
                }
            }
        }
        // Build Series using explicit type ascription
        val d: Int = dim
        return d.j { row: Int ->
            d.j { col: Int -> rows[row][col] }
        }
    }

    fun times(other: Quaternion): Quaternion = Quaternion(
        w = w*other.w - x*other.x - y*other.y - z*other.z,
        x = w*other.x + x*other.w + y*other.z - z*other.y,
        y = w*other.y - x*other.z + y*other.w + z*other.x,
        z = w*other.z + x*other.y - y*other.x + z*other.w,
    )

    companion object {
        fun fromAxisAngle(axis: Series<Double>, angle: Double): Quaternion {
            val half = angle * 0.5
            val s = sin(half)
            return Quaternion(cos(half), axis[0]*s, axis[1]*s, axis[2]*s).normalized()
        }
        val identity = Quaternion(1.0, 0.0, 0.0, 0.0)
    }
}

/** NGS Gaussian Parameter Unit (G-Unit) — hybrid subspace architecture */
data class GUnit(
    val mean: Series<Double>,           // μ ∈ ℝ^{queryDim}
    val logScale: Series<Double>,       // log(scale) ∈ ℝ^{queryDim}
    val rotation: Quaternion,           // compact rotation
    val logitOpacity: Double,           // logit(α) ∈ ℝ
    val localTransform: LocalAffine,    // affine in transform space
    var visits: Int = 0,
    var gradVariance: Double = 0.0,
) {
    val queryDim: Int get() = mean.size
    val transformDim: Int get() = localTransform.transformDim

    /** Scale = exp(logScale) — always positive */
    val scaleVec: Series<Double> get() = logScale.α { exp(it) }

    /** Opacity = sigmoid(logitOpacity) ∈ (0,1) */
    val opacity: Double get() = 1.0 / (1.0 + exp(-logitOpacity))

    /** Covariance precision (inverse): Σ^{-1} = R * diag(1/scale²) * R^T */
    fun precision(): Series<Series<Double>> {
        val R = rotation.toRotationMatrix(queryDim)
        val invScaleSq = scaleVec.α { 1.0 / (it * it) }
        val qd: Int = queryDim
        return qd.j { i: Int ->
            qd.j { j: Int ->
                var sum = 0.0
                (0 until qd).forEach { k ->
                    sum += R[i][k] * invScaleSq[k] * R[j][k]
                }
                sum
            }
        }
    }

    fun mahalanobisSq(z: Series<Double>): Double {
        val qd: Int = queryDim
        val diff = qd.j { i: Int -> z[i] - mean[i] }
        val prec = precision()
        var sum = 0.0
        (0 until qd).forEach { i ->
            (0 until qd).forEach { j ->
                sum += diff[i] * prec[i][j] * diff[j]
            }
        }
        return sum
    }

    fun unnormalizedWeight(z: Series<Double>): Double = opacity * exp(-0.5 * mahalanobisSq(z))
    fun transform(zOrig: Series<Double>): Series<Double> = localTransform.apply(zOrig)

    fun mutable(): MutableGUnit = MutableGUnit(
        mean = mean.view.toMutableList(),
        logScale = logScale.view.toMutableList(),
        rotation = rotation,
        logitOpacity = logitOpacity,
        localTransform = localTransform.mutable(),
        visits = visits,
        gradVariance = gradVariance,
    )
}

class MutableGUnit(
    var mean: MutableList<Double>,
    var logScale: MutableList<Double>,
    var rotation: Quaternion,
    var logitOpacity: Double,
    var localTransform: LocalAffine.Mutable,
    var visits: Int,
    var gradVariance: Double,
) {
    val queryDim: Int get() = mean.size
    val transformDim: Int get() = localTransform.transformDim

    fun toImmutable(): GUnit = GUnit(
        mean = mean.toSeries(),
        logScale = logScale.toSeries(),
        rotation = rotation,
        logitOpacity = logitOpacity,
        localTransform = localTransform.toImmutable(),
    ).also { it.visits = visits; it.gradVariance = gradVariance }
}

/** Local affine: y = W * z_orig + b with diagonal + low-rank W */
data class LocalAffine(
    val weightDiag: Series<Double>,
    val weightLowRankU: Series<Series<Double>>,
    val weightLowRankV: Series<Series<Double>>,
    val bias: Series<Double>,
) {
    val transformDim: Int get() = bias.size
    val rank: Int get() = weightLowRankU.firstOrNull()?.size ?: 0

    fun apply(z: Series<Double>): Series<Double> =
        transformDim.j { i ->
            var acc = bias[i]
            acc += weightDiag[i] * z[i]
            rank.forEach { r ->
                val vDotZ = transformDim.j { weightLowRankV[it][r] * z[it] }.view.sum()
                acc += weightLowRankU[i][r] * vDotZ
            }
            acc
        }

    fun mutable(): Mutable = Mutable(
        weightDiag = weightDiag.view.toMutableList(),
        weightLowRankU = weightLowRankU.view.map { it.view.toMutableList() }.toMutableList(),
        weightLowRankV = weightLowRankV.view.map { it.view.toMutableList() }.toMutableList(),
        bias = bias.view.toMutableList(),
    )

    data class Mutable(
        var weightDiag: MutableList<Double>,
        var weightLowRankU: MutableList<MutableList<Double>>,
        var weightLowRankV: MutableList<MutableList<Double>>,
        var bias: MutableList<Double>,
    ) {
        val transformDim: Int get() = bias.size
        val rank: Int get() = weightLowRankU.firstOrNull()?.size ?: 0

        fun toImmutable(): LocalAffine = LocalAffine(
            weightDiag = weightDiag.toSeries(),
            weightLowRankU = weightLowRankU.map { it.toSeries() }.toSeries(),
            weightLowRankV = weightLowRankV.map { it.toSeries() }.toSeries(),
            bias = bias.toSeries(),
        )
    }
}

/** Helper to convert List to Series */
fun <T> List<T>.toSeries(): Series<T> =
    size.j { this[it] }

/** Helper to convert MutableList to Series */
fun <T> MutableList<T>.toSeries(): Series<T> =
    size.j { this[it] }