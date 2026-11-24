package narchy.spacegraph.depth

// Port of upstream src/depth/DepthCues.ts at 125b369 (LE.15). `DepthMode` is a spectrum (not a 2D/3D toggle): each
// mode is a camera projection/pose plus a fog density, and `depthFade` is the attenuation curve a host can apply to
// per-node opacity. Nothing here reads host semantics.
// Deviation: upstream types the projection as renderer `Projection` ('2d' | '3d') but its values are
// 'orthographic' | 'perspective'; this port uses `DepthProjection` with the values the treatments actually take.

import narchy.spacegraph.util.clamp01
import kotlin.math.PI
import kotlin.math.exp

enum class DepthMode(val wire: String) {
    flat("flat"),
    layered("layered"),
    depthStack("depth-stack"),
    orbit("orbit"),
}

enum class DepthProjection { orthographic, perspective }

/** How the DOM cards face the camera. */
enum class CardFacing { plane, camera }

/** Upstream `DepthTreatment`: projection, pose (`phi` from +Y), orbit enablement, fade strength and card facing. */
data class DepthTreatment(
    val projection: DepthProjection,
    val phi: Double,
    val orbit: Boolean,
    val fade: Double,
    val facing: CardFacing,
)

/** The perspective modes stay shallow enough to keep cards readable; orbit is the labelled experiment. */
const val MAX_READABLE_TILT = 0.3

private val TREATMENTS: Map<DepthMode, DepthTreatment> = mapOf(
    DepthMode.flat to DepthTreatment(DepthProjection.orthographic, PI / 2, orbit = false, fade = 0.0, facing = CardFacing.plane),
    DepthMode.layered to DepthTreatment(DepthProjection.perspective, PI / 2 - 0.15, orbit = false, fade = 0.5, facing = CardFacing.camera),
    DepthMode.depthStack to DepthTreatment(DepthProjection.perspective, PI / 2 - MAX_READABLE_TILT, orbit = false, fade = 0.9, facing = CardFacing.camera),
    DepthMode.orbit to DepthTreatment(DepthProjection.perspective, PI / 2 - 0.5, orbit = true, fade = 0.0, facing = CardFacing.plane),
)

fun depthTreatment(mode: DepthMode): DepthTreatment = TREATMENTS.getValue(mode)

/** Exponential depth attenuation in (0, 1]: 1 at the camera, approaching 0 far away. `density <= 0` is no fade. */
fun depthFade(distance: Double, density: Double): Double {
    if (density <= 0) return 1.0
    val d = maxOf(distance, 0.0)
    return clamp01(exp(-((density * d) * (density * d))))
}

/**
 * Fog density for a scene-relative fade. Scaling by the camera-to-content span keeps the visible attenuation stable
 * across fits and zoom levels instead of making a distant fit fade the whole scene.
 */
fun sceneFadeDensity(strength: Double, cameraDistance: Double, sceneDepth: Double): Double {
    if (strength <= 0) return 0.0
    return strength / maxOf(cameraDistance + sceneDepth, 1.0)
}
