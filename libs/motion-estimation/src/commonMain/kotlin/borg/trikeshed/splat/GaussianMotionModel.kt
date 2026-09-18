package borg.trikeshed.splat

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.log
import kotlin.random.Random

/** Main NGS Motion Model implementing SplatModel interface */
class GaussianMotionModel<Context, T>(
    private val queryProjector: (Context) -> Series<Double>,      // Context → z ∈ ℝ^{queryDim}
    private val transformProjector: (Context) -> Series<Double>,   // Context → z_orig ∈ ℝ^{transformDim}
    private val targetProjector: (T) -> Series<Double>,            // Target → y ∈ ℝ^{transformDim}
    private val config: Config = Config(),
) : SplatModel<Context, T> {

    data class Config(
        val queryDim: Int = 8,
        val transformDim: Int = 64,
        val lowRankDim: Int = 4,
        val kNearest: Int = 16,
        val initialUnits: Int = 32,
        val cellSize: Double = 1.0,

        val lrMean: Double = 1e-3,
        val lrLogScale: Double = 1e-3,
        val lrRotation: Double = 1e-4,
        val lrOpacity: Double = 1e-2,
        val lrTransform: Double = 1e-3,

        val splitGradVarianceThresh: Double = 0.1,
        val cloneScaleThresh: Double = 0.3,
        val cloneGradMagThresh: Double = 0.05,
        val pruneOpacityThresh: Double = 1e-3,
        val minVisitsForSplit: Int = 10,
        val minVisitsForClone: Int = 5,

        val scaleReg: Double = 1e-4,
        val determinantReg: Double = 1e-3,
        val weightDecay: Double = 1e-5,

        val temperature: Double = 1.0,
        val eps: Double = 1e-8,
    )

    private val units = mutableListOf<GUnit>()
    private val gradAccum = mutableListOf<GUnitGradients>()
    private val spatialHash = SpatialHashGrid(config.cellSize, config.queryDim)
    private var stepCount = 0L

    init { initializeUnits() }

    private fun initializeUnits() {
        repeat(config.initialUnits) { i ->
            val mean = config.queryDim.j { dim ->
                val gridPos = (i / (2.0.pow((dim / config.queryDim).floor().toInt()))) % 2
                (gridPos * 2 - 1) * 0.7
            }
            val logScale = config.queryDim.j { log(0.5) }
            val rotation = Quaternion.identity
            val logitOpacity = log(0.1 / 0.9)

            val localTransform = LocalAffine(
                weightDiag = config.transformDim.j { 0.01 },
                weightLowRankU = config.transformDim.j { config.lowRankDim.j { 0.0 } },
                weightLowRankV = config.transformDim.j { config.lowRankDim.j { 0.0 } },
                bias = config.transformDim.j { 0.0 },
            )

            val unit = GUnit(mean, logScale, rotation, logitOpacity, localTransform)
            units.add(unit)
            gradAccum.add(GUnitGradients.zero(config.queryDim, config.transformDim, config.lowRankDim))
            spatialHash.insert(units.lastIndex, unit)
        }
    }

    override fun predict(context: Context): Splat<T> {
        val z = queryProjector(context)
        val zOrig = transformProjector(context)

        val nearest = spatialHash.kNearest(z, units, config.kNearest)
        if (nearest.isEmpty()) return emptySplat()

        val unnormWeights = nearest.α { units[it.a].unnormalizedWeight(z) }
        val weightSum = unnormWeights.view.sum() + config.eps
        val weights = unnormWeights.α { it / weightSum }

        val prediction = config.transformDim.j { outIdx ->
            var acc = 0.0
            nearest.forEachIndexed { j, _ ->
                val idx = nearest[j].a
                val w = weights[j]
                acc += w * units[idx].transform(zOrig)[outIdx]
            }
            acc
        }

        // Placeholder: proper Splat<T> needs T-specific implementation
        return emptySplat()
    }

    /** Observe (context, target) and accumulate gradients */
    fun observe(context: Context, target: T) {
        val z = queryProjector(context)
        val zOrig = transformProjector(context)
        val y = targetProjector(target)

        val nearest = spatialHash.kNearest(z, units, config.kNearest)
        if (nearest.isEmpty()) return

        val unnormWeights = nearest.α { units[it.a].unnormalizedWeight(z) }
        val weightSum = unnormWeights.view.sum() + config.eps
        val weights = unnormWeights.α { it / weightSum }

        val prediction = config.transformDim.j { outIdx ->
            var acc = 0.0
            nearest.forEachIndexed { j, _ ->
                val idx = nearest[j].a
                val w = weights[j]
                acc += w * units[idx].transform(zOrig)[outIdx]
            }
            acc
        }

        val error = config.transformDim.j { prediction[it] - y[it] }
        val loss = error.α { it * it }.view.sum() * 0.5

        nearest.forEachIndexed { j, _ ->
            val idx = nearest[j].a
            val unit = units[idx]
            val w = weights[j]
            units[idx] = unit.copy(visits = unit.visits + 1)

            // ∇_T: error * w
            val dT = LocalAffine(
                weightDiag = config.transformDim.j { error[it] * zOrig[it] * w },
                weightLowRankU = config.transformDim.j { r ->
                    config.lowRankDim.j { c -> error[it] * w * zOrig[c] }
                },
                weightLowRankV = config.transformDim.j { r ->
                    config.lowRankDim.j { c -> error[it] * w * zOrig[c] }
                },
                bias = error.α { it * w },
            )

            // ∇_μ: w * Σ^{-1} * (z - μ)
            val prec = unit.precision()
            val diff = config.queryDim.j { z[it] - unit.mean[it] }
            val dMean = config.queryDim.j { i ->
                var sum = 0.0
                config.queryDim.forEach { j -> sum += prec[i][j] * diff[j] }
                w * sum
            }

            // ∇_logScale: w * (diff_i^2 / scale_i^2 - 1)
            val dLogScale = config.queryDim.j { i ->
                val d = diff[i] / unit.scale[i]
                w * (d * d - 1.0)
            }

            // ∇_logitOpacity: w * (1 - w) from softmax
            val dLogitOpacity = w * (1.0 - w)

            val grads = GUnitGradients(
                dMean = dMean,
                dLogScale = dLogScale,
                dRotation = Quaternion.identity,
                dLogitOpacity = dLogitOpacity,
                dLocalTransform = dT,
            )
            gradAccum[idx] = gradAccum[idx].add(grads)

            // Update running gradient variance for split
            val gradVar = dMean.α { it * it }.view.sum() / config.queryDim
            units[idx] = units[idx].copy(gradVariance = 0.9 * units[idx].gradVariance + 0.1 * gradVar)
        }

        stepCount++
        if (stepCount % 100 == 0) {
            applyDensityControl()
            applyGradients()
            clearGradients()
        }
    }

    private fun applyGradients() {
        units.indices.forEach { idx ->
            val unit = units[idx]
            val grad = gradAccum[idx]
            val mut = unit.mutable()

            mut.mean = mut.mean.mapIndexed { i, v -> v - config.lrMean * grad.dMean[i] }
            mut.logScale = mut.logScale.mapIndexed { i, v -> v - config.lrLogScale * (grad.dLogScale[i] + config.scaleReg) }
            mut.logitOpacity += config.lrOpacity * grad.dLogitOpacity

            mut.localTransform = LocalAffine.Mutable(
                weightDiag = mut.localTransform.weightDiag.mapIndexed { i, v ->
                    v - config.lrTransform * (grad.dLocalTransform.weightDiag[i] + config.weightDecay * v)
                },
                weightLowRankU = mut.localTransform.weightLowRankU.mapIndexed { r, row ->
                    row.mapIndexed { c, v ->
                        v - config.lrTransform * (grad.dLocalTransform.weightLowRankU[r][c] + config.weightDecay * v)
                    }
                },
                weightLowRankV = mut.localTransform.weightLowRankV.mapIndexed { r, row ->
                    row.mapIndexed { c, v ->
                        v - config.lrTransform * (grad.dLocalTransform.weightLowRankV[r][c] + config.weightDecay * v)
                    }
                },
                bias = mut.localTransform.bias.mapIndexed { i, v ->
                    v - config.lrTransform * (grad.dLocalTransform.bias[i] + config.weightDecay * v)
                },
            )

            spatialHash.remove(idx, unit)
            val newUnit = mut.toImmutable()
            units[idx] = newUnit
            spatialHash.insert(idx, newUnit)
        }
    }

    private fun clearGradients() {
        gradAccum.indices.forEach { i ->
            gradAccum[i] = GUnitGradients.zero(config.queryDim, config.transformDim, config.lowRankDim)
        }
    }

    private fun applyDensityControl() {
        val toSplit = mutableListOf<Int>()
        val toClone = mutableListOf<Int>()
        val toPrune = mutableListOf<Int>()

        units.indices.forEach { idx ->
            val u = units[idx]
            if (u.gradVariance > config.splitGradVarianceThresh && u.visits > config.minVisitsForSplit) toSplit.add(idx)
            val avgScale = u.scale.view.sum() / config.queryDim
            val gradMag = sqrt(u.gradVariance * config.queryDim)
            if (avgScale > config.cloneScaleThresh && gradMag > config.cloneGradMagThresh && u.visits > config.minVisitsForClone) toClone.add(idx)
            if (u.opacity < config.pruneOpacityThresh) toPrune.add(idx)
        }

        toSplit.sortedDescending().forEach { splitUnit(it) }
        toClone.sortedDescending().forEach { cloneUnit(it) }
        toPrune.sortedDescending().forEach { pruneUnit(it) }
    }

    private fun splitUnit(idx: Int) {
        val u = units[idx]
        val mut = u.mutable()

        mut.logScale = mut.logScale.map { it + log(0.5) }
        val maxDim = mut.scale.withIndex().maxByOrNull { it.value }?.index ?: 0
        val offset = mut.scale[maxDim] * 0.5
        mut.mean[maxDim] += offset
        val mean1 = mut.mean.toSeries()
        mut.mean[maxDim] -= 2 * offset
        val mean2 = mut.mean.toSeries()
        mut.mean[maxDim] += offset

        val logitOpacityHalf = mut.logitOpacity + log(0.5)

        val u1 = GUnit(mean1, mut.logScale.toSeries(), mut.rotation, logitOpacityHalf, mut.localTransform.toImmutable())
        val u2 = GUnit(mean2, mut.logScale.toSeries(), mut.rotation, logitOpacityHalf, mut.localTransform.toImmutable())

        spatialHash.remove(idx, u)
        units[idx] = u1
        spatialHash.insert(idx, u1)

        val newIdx = units.size
        units.add(u2)
        gradAccum.add(GUnitGradients.zero(config.queryDim, config.transformDim, config.lowRankDim))
        spatialHash.insert(newIdx, u2)
    }

    private fun cloneUnit(idx: Int) {
        val u = units[idx]
        val mut = u.mutable()

        mut.mean = mut.mean.mapIndexed { i, v -> v + (Random.nextDouble() - 0.5) * mut.scale[i] * 0.1 }
        mut.logitOpacity += log(0.7)

        val uClone = mut.toImmutable()

        spatialHash.remove(idx, u)
        units[idx] = uClone
        spatialHash.insert(idx, uClone)

        units.add(u)
        gradAccum.add(GUnitGradients.zero(config.queryDim, config.transformDim, config.lowRankDim))
        spatialHash.insert(units.lastIndex, u)
    }

    private fun pruneUnit(idx: Int) {
        val u = units[idx]
        spatialHash.remove(idx, u)
        units.removeAt(idx)
        gradAccum.removeAt(idx)
    }

    private fun <T> emptySplat(): Splat<T> = 0.j { _ -> error("empty") }

    val size: Int get() = units.size
    fun getUnits(): List<GUnit> = units.toList()
}