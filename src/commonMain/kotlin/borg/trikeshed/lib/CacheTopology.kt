@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.lib

/**
 * Hardware cache topology snapshot — provided by the platform layer.
 *
 * Used by [ReificationContext] to select a sensible default [maxDepth]:
 * smaller caches favour earlier materialization (shallower stairs),
 * larger caches tolerate deeper lazy nesting.
 *
 * @param l1DataBytes  L1 data cache size in bytes, or null if unknown
 * @param cacheLineBytes  cache line size in bytes (typically 64), or null
 */
class CacheTopology(
    val l1DataBytes: Long?,
    val cacheLineBytes: Int?,
) {
    companion object {
        /** Sentinel when no cache information is available (WASM, JS, unknown JVM). */
        val UNKNOWN = CacheTopology(null, null)
    }
}
