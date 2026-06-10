package borg.trikeshed.splat

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α

/** Gradient accumulators for one G-Unit (∇μ, ∇logScale, ∇rotation, ∇logitOpacity, ∇W, ∇b) */
data class GUnitGradients(
    val dMean: Series<Double>,
    val dLogScale: Series<Double>,
    val dRotation: Quaternion,
    val dLogitOpacity: Double,
    val dLocalTransform: LocalAffine,
) {
    companion object {
        fun zero(queryDim: Int, transformDim: Int, rank: Int): GUnitGradients =
            GUnitGradients(
                dMean = queryDim.j { 0.0 },
                dLogScale = queryDim.j { 0.0 },
                dRotation = Quaternion.identity,
                dLogitOpacity = 0.0,
                dLocalTransform = LocalAffine(
                    weightDiag = transformDim.j { 0.0 },
                    weightLowRankU = transformDim.j { rank.j { 0.0 } },
                    weightLowRankV = transformDim.j { rank.j { 0.0 } },
                    bias = transformDim.j { 0.0 },
                ),
            )
    }

    fun add(other: GUnitGradients): GUnitGradients = GUnitGradients(
        dMean = dMean.size.j { dMean[it] + other.dMean[it] },
        dLogScale = dLogScale.size.j { dLogScale[it] + other.dLogScale[it] },
        dRotation = dRotation.times(other.dRotation),
        dLogitOpacity = dLogitOpacity + other.dLogitOpacity,
        dLocalTransform = LocalAffine(
            weightDiag = dLocalTransform.weightDiag.size.j { dLocalTransform.weightDiag[it] + other.dLocalTransform.weightDiag[it] },
            weightLowRankU = dLocalTransform.weightLowRankU.size.j { r ->
                dLocalTransform.weightLowRankU[r].size.j { c ->
                    dLocalTransform.weightLowRankU[r][c] + other.dLocalTransform.weightLowRankU[r][c]
                }
            },
            weightLowRankV = dLocalTransform.weightLowRankV.size.j { r ->
                dLocalTransform.weightLowRankV[r].size.j { c ->
                    dLocalTransform.weightLowRankV[r][c] + other.dLocalTransform.weightLowRankV[r][c]
                }
            },
            bias = dLocalTransform.bias.size.j { dLocalTransform.bias[it] + other.dLocalTransform.bias[it] },
        ),
    )
}