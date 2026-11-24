package narchy.spacegraph.depth

// Port of upstream src/depth/continuum.ts at 125b369 (P4.3). Depth becomes a continuous policy: modes blend into one
// another and a host-chosen axis offsets each node within the scene. Existing mode treatments stay the endpoints, so
// nothing changes at t=0/1.

import narchy.spacegraph.util.clamp01

enum class DepthAxis { none, lane, turn, hierarchy }

data class DepthContinuum(
    val mode: DepthMode,
    val axis: DepthAxis,
    /** Strength of the treatment and fog, from 0 (flat) to 1 (full). */
    val strength: Double,
    /** Scene units the host axis maps across. */
    val spread: Double,
)

val DEFAULT_DEPTH_CONTINUUM = DepthContinuum(mode = DepthMode.flat, axis = DepthAxis.none, strength = 0.0, spread = 0.0)

data class DepthSample(
    val projection: DepthProjection,
    val phi: Double,
    val orbit: Boolean,
    val facing: CardFacing,
    val fade: Double,
    val offset: Double,
    val opacity: Double,
)

/** Blend two named treatments into one continuous treatment. Discrete modes remain the endpoints. */
fun blendDepth(from: DepthMode, to: DepthMode, t: Double): DepthTreatment {
    val a = depthTreatment(from)
    val b = depthTreatment(to)
    val value = clamp01(t)
    return DepthTreatment(
        projection = if (value < 0.5) a.projection else b.projection,
        phi = a.phi + (b.phi - a.phi) * value,
        orbit = if (value < 0.5) a.orbit else b.orbit,
        fade = a.fade + (b.fade - a.fade) * value,
        facing = if (value < 0.5) a.facing else b.facing,
    )
}

/**
 * Sample one node's depth. `axisValue` is host-normalized to [0, 1] (e.g. lane index, turn position or hierarchy
 * level); an `axis` of `none` keeps every node in the content plane.
 */
fun sampleDepth(continuum: DepthContinuum, axisValue: Double, cameraDistance: Double): DepthSample {
    val treatment = depthTreatment(continuum.mode)
    val offset = if (continuum.axis == DepthAxis.none) {
        0.0
    } else {
        (clamp01(axisValue) * 2 - 1) * continuum.spread * continuum.strength
    }
    return DepthSample(
        projection = treatment.projection,
        phi = treatment.phi,
        orbit = treatment.orbit,
        facing = treatment.facing,
        fade = treatment.fade * continuum.strength,
        offset = offset,
        opacity = depthFade(cameraDistance + offset, treatment.fade * continuum.strength),
    )
}
