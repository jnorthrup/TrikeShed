package borg.trikeshed.splat

import borg.trikeshed.lib.Series

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import kotlin.math.exp

/** N-dimensional matrix, row-major through nested lazy Series projections. */
typealias SplatMatrix = Series<Series<Double>>

fun identitySplatMatrix(dimensions: Int): SplatMatrix {
    require(dimensions > 0) { "dimensions must be positive" }
    return dimensions j { row: Int -> dimensions j { column: Int -> if (row == column) 1.0 else 0.0 } }
}

/**
 * Recovered from historical `libs/motion-estimation` (`a999113f9`, final combined tree
 * `b4ce9a494^`) and closed over the PRELOAD algebra.
 * One anisotropic Gaussian in an arbitrary-dimensional feature space.
 */
data class ParameterGaussian(
    val mean: Series<Double>,
    val scale: Series<Double>,
    val rotation: SplatMatrix = identitySplatMatrix(mean.size),
    val opacity: Double = 1.0,
    val localTransform: LocalTransform = LocalTransform.identity(mean.size),
) {
    val dimensions: Int get() = mean.size

    init {
        require(dimensions > 0) { "Gaussian requires at least one dimension" }
        require(scale.size == dimensions) { "scale dimension ${scale.size} != $dimensions" }
        require(rotation.size == dimensions) { "rotation row count ${rotation.size} != $dimensions" }
        require(rotation.view.all { it.size == dimensions }) { "rotation must be square" }
        require(scale.view.all { it > 0.0 }) { "scale must be positive" }
        require(opacity in 0.0..1.0) { "opacity must be in [0,1]" }
    }

    /** (x-μ)ᵀ R diag(scale⁻²) Rᵀ (x-μ). */
    fun mahalanobisSq(x: Series<Double>): Double {
        require(x.size == dimensions) { "point dimension ${x.size} != $dimensions" }
        val centered: Series<Double> = dimensions j { i: Int -> x[i] - mean[i] }
        val rotated: Series<Double> = dimensions j { axis: Int ->
            var sum = 0.0
            centered.view.forEachIndexed { row, value -> sum += rotation[row][axis] * value }
            sum
        }
        var distance = 0.0
        rotated.view.forEachIndexed { axis, value ->
            val normalized = value / scale[axis]
            distance += normalized * normalized
        }
        return distance
    }

    fun kernelValue(x: Series<Double>): Double = opacity * exp(-0.5 * mahalanobisSq(x))

    fun evaluateLocal(x: Series<Double>): Series<Double> = localTransform.evaluate(x)
}

/** Low-order local transform T(x) = bias + linear·x + diag(quadratic)·x². */
data class LocalTransform(
    val bias: Series<Double>,
    val linear: SplatMatrix,
    val quadratic: Series<SplatMatrix>,
) {
    val outputDimensions: Int get() = bias.size
    val inputDimensions: Int get() = if (linear.size == 0) 0 else linear[0].size

    init {
        require(linear.size == outputDimensions)
        require(quadratic.size == outputDimensions)
        require(linear.view.all { it.size == inputDimensions })
        require(quadratic.view.all { matrix ->
            matrix.size == inputDimensions && matrix.view.all { it.size == inputDimensions }
        })
    }

    fun evaluate(x: Series<Double>): Series<Double> {
        require(x.size == inputDimensions)
        return outputDimensions j { output: Int ->
            var value = bias[output]
            x.view.forEachIndexed { input, coordinate ->
                value += linear[output][input] * coordinate
                value += quadratic[output][input][input] * coordinate * coordinate
            }
            value
        }
    }

    companion object {
        fun identity(dimensions: Int): LocalTransform = LocalTransform(
            bias = dimensions j { _: Int -> 0.0 },
            linear = identitySplatMatrix(dimensions),
            quadratic = dimensions j { _: Int -> dimensions j { _: Int -> dimensions j { _: Int -> 0.0 } } },
        )
    }
}

data class GaussianGradients(
    val mean: Series<Double>,
    val scale: Series<Double>,
    val rotation: SplatMatrix,
    val opacity: Double,
) {
    fun add(other: GaussianGradients): GaussianGradients {
        require(mean.size == other.mean.size)
        return GaussianGradients(
            mean = mean.size j { i: Int -> mean[i] + other.mean[i] },
            scale = scale.size j { i: Int -> scale[i] + other.scale[i] },
            rotation = rotation.size j { row: Int ->
                rotation[row].size j { column: Int -> rotation[row][column] + other.rotation[row][column] }
            },
            opacity = opacity + other.opacity,
        )
    }

    companion object {
        fun zero(dimensions: Int): GaussianGradients = GaussianGradients(
            mean = dimensions j { _: Int -> 0.0 },
            scale = dimensions j { _: Int -> 0.0 },
            rotation = dimensions j { _: Int -> dimensions j { _: Int -> 0.0 } },
            opacity = 0.0,
        )
    }
}

/** Small, deterministic n-dimensional Gaussian outcome model. */
class GaussianMotionModel<Context, T>(
    private val projector: (Context) -> Series<Double>,
    private val initialScale: Double = 1.0,
    private val minimumWeight: Double = 1e-12,
) : SplatModel<Context, T> {
    private data class Unit<T>(val gaussian: ParameterGaussian, val outcome: T)
    private val observations = mutableListOf<Unit<T>>()

    fun observe(context: Context, outcome: T, opacity: Double = 1.0) {
        val point = projector(context)
        val gaussian = ParameterGaussian(
            mean = point,
            scale = point.size j { _: Int -> initialScale },
            opacity = opacity,
        )
        observations += Unit(gaussian, outcome)
    }

    override fun predict(context: Context): Splat<T> {
        if (observations.isEmpty()) return 0 j { _: Int -> error("empty splat") }
        val point = projector(context)
        val weights = LinkedHashMap<T, Double>()
        for (observation in observations) {
            val weight = observation.gaussian.kernelValue(point)
            if (weight > minimumWeight) weights[observation.outcome] = (weights[observation.outcome] ?: 0.0) + weight
        }
        if (weights.isEmpty()) return 0 j { _: Int -> error("empty splat") }
        val outcomes = arrayOfNulls<Any?>(weights.size)
        var outcomeIndex = 0
        for (outcome in weights.keys) outcomes[outcomeIndex++] = outcome
        outcomes.sortBy { it.toString() }
        var total = 0.0
        for (weight in weights.values) total += weight
        return outcomes.size j { i: Int ->
            @Suppress("UNCHECKED_CAST") val outcome = outcomes[i] as T
            outcome j ((weights[outcome] ?: 0.0) / total)
        }
    }

    val size: Int get() = observations.size
    val gaussians: Series<ParameterGaussian> get() = observations.size j { i: Int -> observations[i].gaussian }
}
