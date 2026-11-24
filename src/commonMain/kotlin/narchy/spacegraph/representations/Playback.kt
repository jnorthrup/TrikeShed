package narchy.spacegraph.representations

// Port of upstream src/representations/playback.ts at 125b369 (P1.3). The library owns the clock, easing and
// stable ids of a representation swap; the host owns how one appearance interpolates into the next.

/** Curves matching the easing names hosts already pass to gsap; kept pure for headless playback. */
val REPRESENTATION_EASINGS: Map<String, (Double) -> Double> = mapOf(
    "linear" to { t: Double -> t },
    "power1.out" to { t: Double -> 1 - (1 - t) * (1 - t) },
    "power2.out" to { t: Double -> 1 - (1 - t) * (1 - t) * (1 - t) },
    "power2.inOut" to { t: Double ->
        if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2) * (-2 * t + 2) * (-2 * t + 2) / 2
    },
)

/** A swap as the host sees it: the id is stable from `from` to `to`. */
typealias RepresentationTween = RungChange

data class RepresentationPlaybackOptions(
    val ease: String = "power2.out",
    /** Reduced motion collapses every swap to its final frame. */
    val reducedMotion: Boolean = false,
)

internal class ActiveSwap(val change: RepresentationTween, var elapsed: Double)

class RepresentationPlayback internal constructor(
    private val active: MutableMap<String, ActiveSwap>,
    private val interpolate: (RepresentationTween, Double) -> Unit,
    private val ease: (Double) -> Double,
) {
    val done: Boolean get() = active.isEmpty()
    val activeIds: List<String> get() = active.keys.toList()

    /** Advance by `seconds` and emit the eased value of every active swap. */
    fun advance(seconds: Double) {
        val iterator = active.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.elapsed += maxOf(0.0, seconds)
            val t = minOf(1.0, entry.elapsed / entry.change.duration)
            interpolate(entry.change, ease(t))
            if (t == 1.0) iterator.remove()
        }
    }

    /** Jump to the final frame of every active swap. */
    fun finish() {
        for (entry in active.values) interpolate(entry.change, 1.0)
        active.clear()
    }
}

/**
 * Play stable-id swaps produced by `representationChanges`. `interpolate` receives the change and an
 * eased value in [0, 1]; the host decides which appearance channel that value drives.
 */
fun playRepresentationChanges(
    changes: List<RepresentationTween>,
    interpolate: (RepresentationTween, Double) -> Unit,
    options: RepresentationPlaybackOptions = RepresentationPlaybackOptions(),
): RepresentationPlayback {
    val ease = REPRESENTATION_EASINGS[options.ease] ?: error("unknown ease ${options.ease}")
    val active = LinkedHashMap<String, ActiveSwap>()
    if (options.reducedMotion) {
        for (change in changes) interpolate(change, 1.0)
    } else {
        for (change in changes) if (change.duration > 0) active[change.id] = ActiveSwap(change, 0.0)
    }
    return RepresentationPlayback(active, interpolate, ease)
}
