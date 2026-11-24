package narchy.spacegraph.zoom

// Port of upstream src/zoom/ScaleDomain.ts at 125b369.
// One continuous scale domain. ZoomBands still owns band resolution and hysteresis; this layer adds continuous
// position, interpolated semantic importance and anchored zoom on top of it.
// Upstream has no test file for ScaleDomain: nothing checks this port beyond the gate compile.

import kotlin.math.max
import kotlin.math.min

import narchy.spacegraph.Vec3
import narchy.spacegraph.util.clamp
import narchy.spacegraph.util.lerp

data class ScaleStop(
    /** Normalized position in [0, 1] where this stop begins. */
    val at: Double,
    val id: String,
    val label: String? = null,
    /** Semantic importance 0..1, interpolated between stops. */
    val importance: Double? = null,
    /** Seconds to cross into the next stop. */
    val transitionDuration: Double? = null,
)

data class ScaleDomainConfig(
    /** Host-defined stops covering [0, 1], ordered by `at`. */
    val stops: List<ScaleStop>,
    val range: ZoomRange,
    val hysteresis: Double = 0.0,
)

data class ScaleSample(
    val normalized: Double,
    val raw: Double,
    val stop: String,
    val label: String?,
    /** Continuous position along the stop ladder, in [0, stops.size - 1]. */
    val position: Double,
    /** Interpolated semantic importance in [0, 1]. */
    val importance: Double,
    /** Transition duration for entering the current stop, in seconds. */
    val transitionDuration: Double,
)

data class ScaleAnchor(
    /** World point held under the same screen position. */
    val world: Vec3,
    /** Current camera target. */
    val target: Vec3,
)

class ScaleDomain(config: ScaleDomainConfig) {
    val stops: List<ScaleStop>
    val range: ZoomRange
    private val bands: ZoomBands

    init {
        val sorted = config.stops.sortedBy { it.at }
        if (sorted.size < 2) throw ConfigurationError("ScaleDomain requires at least two stops")
        if (sorted.first().at != 0.0 || sorted.last().at != 1.0) {
            throw ConfigurationError("ScaleDomain: stops must cover the full [0, 1] range")
        }
        sorted.forEachIndexed { index, stop ->
            val next = sorted.getOrNull(index + 1)
            if (next != null && stop.at >= next.at) {
                throw ConfigurationError("ScaleDomain: invalid position for stop \"${stop.id}\"")
            }
        }
        stops = sorted
        range = config.range
        bands = ZoomBands(
            ZoomBandsConfig(
                bands = sorted.dropLast(1).mapIndexed { index, stop ->
                    ZoomBand(id = stop.id, min = stop.at, max = sorted[index + 1].at, label = stop.label)
                },
                hysteresis = config.hysteresis,
            ),
        )
    }

    val band: String get() = bands.band

    val bandList: List<ZoomBand> get() = bands.bands

    fun onChange(listener: ZoomBandListener): () -> Unit = bands.onChange(listener)

    /** Continuous sample at a normalized zoom. Pure: no clock, no camera, no renderer. */
    fun sample(
        normalized: Double,
        raw: Double = denormalizeZoom(clamp(normalized, 0.0, 1.0), range),
        band: String? = null,
    ): ScaleSample {
        val value = clamp(normalized, 0.0, 1.0)
        val computedIndex = stops.withIndex().indexOfFirst { (i, stop) -> i > 0 && value < stop.at }
        val index = if (band != null) {
            max(0, stops.indexOfFirst { it.id == band })
        } else if (computedIndex == -1) {
            stops.size - 1
        } else {
            computedIndex - 1
        }
        val from = stops[index]
        val to = stops[min(index + 1, stops.size - 1)]
        val span = to.at - from.at
        val progress = if (span > 0 && index < stops.size - 1) clamp((value - from.at) / span, 0.0, 1.0) else 0.0
        val fromImportance = from.importance ?: 0.0
        val toImportance = if (index < stops.size - 1) (to.importance ?: fromImportance) else fromImportance
        return ScaleSample(
            normalized = value,
            raw = raw,
            stop = from.id,
            label = from.label,
            position = index + progress,
            importance = clamp(lerp(fromImportance, toImportance, progress), 0.0, 1.0),
            transitionDuration = from.transitionDuration ?: 0.0,
        )
    }

    fun feedNormalized(normalized: Double, raw: Double = normalized): ScaleSample {
        val snapshot = bands.feedNormalized(normalized, raw)
        return sample(snapshot.normalized, snapshot.zoom, snapshot.band)
    }

    fun feed(raw: Double): ScaleSample {
        val snapshot = bands.feed(raw, range)
        return sample(snapshot.normalized, snapshot.zoom, snapshot.band)
    }

    /** Camera target that keeps `anchor.world` at the same screen position after the zoom. */
    fun anchoredTarget(from: ScaleSample, to: ScaleSample, anchor: ScaleAnchor): Vec3 {
        val factor = if (range.closerIsLarger) from.raw / to.raw else to.raw / from.raw
        val world = anchor.world
        val target = anchor.target
        return Vec3(
            world.x - (world.x - target.x) * factor,
            world.y - (world.y - target.y) * factor,
            world.z - (world.z - target.z) * factor,
        )
    }

    /** Round-trip a zoom to a normalized value and back to its raw camera zoom. */
    fun rawAt(normalized: Double): Double = denormalizeZoom(clamp(normalized, 0.0, 1.0), range)

    fun normalizedAt(raw: Double): Double = normalizeZoom(raw, range)
}
