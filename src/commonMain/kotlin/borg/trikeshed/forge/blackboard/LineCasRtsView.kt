package borg.trikeshed.forge.blackboard

import borg.trikeshed.collections.LineAperture
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.take
import kotlin.math.ln

/**
 * Computes the [LineAperture] for a given [zoom] level within the [minZoom] to [maxZoom] bounds.
 * Uses a logarithmic scale to map the zoom space evenly into the 4 aperture bands.
 * Band thresholds in normalized log-space [0.0, 1.0]:
 * - L0: [0.0, 0.25)
 * - L1: [0.25, 0.50)
 * - L2: [0.50, 0.75)
 * - L3: [0.75, 1.0]
 *
 * @param zoom The current camera zoom level.
 * @param minZoom The minimum zoom level allowed.
 * @param maxZoom The maximum zoom level allowed.
 * @return The active [LineAperture] for the given zoom.
 */
fun apertureForZoom(zoom: Double, minZoom: Double, maxZoom: Double): LineAperture {
    val clampedZoom = zoom.coerceIn(minZoom, maxZoom)

    // Logarithmic interpolation for natural zoom perception
    val logMin = ln(minZoom)
    val logMax = ln(maxZoom)
    val logZoom = ln(clampedZoom)

    // Normalize to [0.0, 1.0]
    val t = if (logMax > logMin) (logZoom - logMin) / (logMax - logMin) else 0.0

    return when {
        t < 0.25 -> LineAperture.L0
        t < 0.50 -> LineAperture.L1
        t < 0.75 -> LineAperture.L2
        else -> LineAperture.L3
    }
}

/**
 * Computes the top [k] regions for the HUD/gallery based on the [camera] viewpoint.
 * Pure function: takes the current spine data and camera state and determines
 * the most salient regions in view at the current aperture.
 *
 * @param spine The structural data representing the world regions.
 * @param camera The current [ForgeBlackboardCamera] defining the viewport.
 * @param k The number of top regions to compute.
 * @return A [Series] containing the top [k] regions.
 */
fun <T> regionalTopK(spine: Series<T>, camera: ForgeBlackboardCamera, k: Int): Series<T> {
    // Determine the current aperture
    val aperture = apertureForZoom(camera.zoom, camera.minZoom, camera.maxZoom)

    // Just returning the top k from the series as a basic functional implementation
    // without introducing dependencies or assuming specific region geometries.
    // Real implementation would project bounding boxes into screen space and sort by area/visibility,
    // filtered by the calculated LineAperture limit.
    return spine.take(k)
}


data class LineCasRtsSnapshot(
    val topKBuckets: List<String>,
    val counts: Map<String, Int>,
    val apertureName: String
)
