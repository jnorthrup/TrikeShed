@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.lib

/**
 * Controls how deeply lazy stair shapes are preserved before materialization.
 *
 * A "stair shape" is created by [combine] — a flat view of multiple sub-series
 * indexed through prefix-sum stairs.  If sub-series are themselves stair-shaped
 * (nested combines), the read path chains through multiple lazy index resolutions.
 *
 * [ReificationContext] lets callers cap this depth.  When [maxDepth] is exceeded
 * at any level, the sub-series is materialized ([materialize]) before being
 * combined.
 *
 * ## Platform cache hierarchy
 *
 * The optimal [maxDepth] depends on the hardware cache topology.  Smaller L1
 * caches favour shallower stairs (earlier materialization keeps working sets
 * cache-resident); larger caches tolerate deeper lazy nesting.  Use
 * [ReificationContext.from] to derive a sensible default from a
 * [CacheTopology] snapshot provided by the platform layer (posix/JVM/JS/WASM).
 *
 * @param maxDepth  maximum stair nesting depth before materialization.
 *                  0 = materialize everything immediately.
 *                  [Int.MAX_VALUE] = never materialize (full laziness, same as
 *                  calling [combine] without a context).
 *
 * No default for [maxDepth] — callers must explicitly choose their tradeoff.
 */
@JvmInline
value class ReificationContext(val maxDepth: Int) {
    init {
        require(maxDepth >= 0) { "maxDepth must be non-negative, got $maxDepth" }
    }

    /** Return a context for the next nesting level, or null if materialization is forced. */
    fun deeper(): ReificationContext? =
        if (maxDepth > 0) ReificationContext(maxDepth - 1) else null

    companion object {
        /**
         * Derive a default [ReificationContext] from the platform's [CacheTopology].
         *
         * Heuristic (subject to tuning):
         * - L1 ≤  32 KiB → maxDepth = 1  (shallow stairs, early materialization)
         * - L1 ≤ 256 KiB → maxDepth = 2
         * - L1  > 256 KiB → maxDepth = 3
         * - Unknown       → [Int.MAX_VALUE] (no materialization — full laziness)
         */
        fun from(topology: CacheTopology): ReificationContext {
            val l1 = topology.l1DataBytes ?: return ReificationContext(Int.MAX_VALUE)
            val depth = when {
                l1 <= 32_768 -> 1
                l1 <= 262_144 -> 2
                else -> 3
            }
            return ReificationContext(depth)
        }
    }
}
