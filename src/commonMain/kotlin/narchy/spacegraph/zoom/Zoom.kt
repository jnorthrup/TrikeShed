package narchy.spacegraph.zoom

// Port of upstream src/core/zoom.ts at 125b369.
// Camera zoom telemetry helpers. Pure math so hosts and tests share one definition of "zoom".
// A normalized zoom is 0..1 where 1 is closest. The raw value is perspective distance (larger is farther) or
// orthographic zoom (larger is closer); `closerIsLarger` selects the mapping.

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

import narchy.spacegraph.util.clamp

data class ZoomRange(val min: Double, val max: Double, val closerIsLarger: Boolean)

private fun logLerp(from: Double, to: Double, t: Double): Double =
    exp(ln(from) + t * (ln(to) - ln(from)))

fun normalizeZoom(raw: Double, range: ZoomRange): Double {
    val lo = min(range.min, range.max)
    val hi = max(range.min, range.max)
    if (raw <= lo) return if (range.closerIsLarger) 0.0 else 1.0
    if (raw >= hi) return if (range.closerIsLarger) 1.0 else 0.0
    val t = (ln(raw) - ln(lo)) / (ln(hi) - ln(lo))
    return if (range.closerIsLarger) t else 1.0 - t
}

fun denormalizeZoom(value: Double, range: ZoomRange): Double {
    val lo = min(range.min, range.max)
    val hi = max(range.min, range.max)
    val t = if (range.closerIsLarger) value else 1.0 - value
    return logLerp(lo, hi, clamp(t, 0.0, 1.0))
}
