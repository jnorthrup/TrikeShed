package narchy.spacegraph.zoom

// Port of upstream src/zoom/ZoomBands.ts at 125b369.
// Headless zoom-band controller. Maps a normalized camera zoom to a named band and reports changes.
// It owns no rendering and no semantic policy: the host supplies contiguous bands and reacts to changes.
// Deviation: upstream ConfigurationError (src/utils/error.ts) is a small local class here; the rest of
// upstream's error hierarchy is not ported.
// Deviation: the snapshot timestamp is epoch milliseconds from kotlinx.datetime, as upstream's Date.now().

import kotlinx.datetime.Clock

import narchy.spacegraph.util.clamp

class ConfigurationError(message: String) : IllegalArgumentException(message)

data class ZoomBand(
    val id: String,
    /** Lower bound of the band in normalized (0..1) zoom, inclusive. */
    val min: Double,
    /** Upper bound, exclusive except for the last band, which is inclusive of 1. */
    val max: Double,
    val label: String? = null,
)

data class ZoomBandsConfig(
    /** Contiguous bands covering [0, 1], ordered or sortable by `min`. */
    val bands: List<ZoomBand>,
    /** Normalized dead-zone around a boundary before switching bands; dampens wheel bursts. */
    val hysteresis: Double = 0.0,
)

data class ZoomBandSnapshot(
    val zoom: Double,
    val normalized: Double,
    val band: String,
    val label: String?,
    val timestamp: Long,
)

data class ZoomBandChange(
    val zoom: Double,
    val normalized: Double,
    val band: String,
    val label: String?,
    val timestamp: Long,
    val from: String,
    val previous: ZoomBandSnapshot,
)

typealias ZoomBandListener = (ZoomBandChange) -> Unit

class ZoomBands(config: ZoomBandsConfig) {
    val bands: List<ZoomBand>
    private val hysteresis: Double = config.hysteresis
    private val listeners = mutableSetOf<ZoomBandListener>()
    var band: String
        private set
    var normalized: Double = 0.0
        private set
    private var zoom: Double = 0.0
    private var timestamp: Long = 0L

    init {
        val sorted = config.bands.sortedBy { it.min }
        if (sorted.isEmpty()) throw ConfigurationError("ZoomBands requires at least one band")
        if (sorted.first().min != 0.0 || sorted.last().max != 1.0) {
            throw ConfigurationError("ZoomBands: bands must cover the full [0, 1] range")
        }
        sorted.forEachIndexed { i, b ->
            if (b.min >= b.max) {
                throw ConfigurationError("ZoomBands: invalid range for band \"${b.id}\" [${b.min}, ${b.max}]")
            }
            val previous = sorted.getOrNull(i - 1)
            if (previous != null && previous.max != b.min) {
                throw ConfigurationError("ZoomBands: gap or overlap before band \"${b.id}\"")
            }
        }
        bands = sorted
        band = resolve(0.0)
    }

    fun indexOf(band: String): Int = bands.indexOfFirst { it.id == band }

    fun snapshot(): ZoomBandSnapshot = ZoomBandSnapshot(
        zoom = zoom,
        normalized = normalized,
        band = band,
        label = bands.find { it.id == band }?.label,
        timestamp = timestamp,
    )

    /** Feed a raw camera zoom and the range that produced it. */
    fun feed(raw: Double, range: ZoomRange): ZoomBandSnapshot =
        feedNormalized(normalizeZoom(raw, range), raw)

    /** Feed an already-normalized zoom; `raw` is only carried into the snapshot. */
    fun feedNormalized(normalizedZoom: Double, raw: Double = normalizedZoom): ZoomBandSnapshot {
        val value = clamp(normalizedZoom, 0.0, 1.0)
        val candidate = resolve(value)
        val next = if (candidate == band) candidate else applyHysteresis(value, candidate)
        val previous = snapshot()
        normalized = value
        zoom = raw
        timestamp = Clock.System.now().toEpochMilliseconds()
        band = next
        val snap = snapshot()
        if (next != previous.band) {
            val change = ZoomBandChange(
                zoom = snap.zoom,
                normalized = snap.normalized,
                band = snap.band,
                label = snap.label,
                timestamp = snap.timestamp,
                from = previous.band,
                previous = previous,
            )
            for (listener in listeners.toList()) listener(change)
        }
        return snap
    }

    /** Returns an unsubscribe function. */
    fun onChange(listener: ZoomBandListener): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun resolve(value: Double): String {
        val band = bands.find { value >= it.min && value < it.max }
        return (band ?: bands.last()).id
    }

    private fun applyHysteresis(value: Double, candidate: String): String {
        val current = bands.find { it.id == band } ?: return candidate
        if (hysteresis == 0.0) return candidate
        val rising = value >= current.max
        if (rising && value < current.max + hysteresis) return band
        if (!rising && value > current.min - hysteresis) return band
        return candidate
    }
}
