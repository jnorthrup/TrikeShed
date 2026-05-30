package borg.trikeshed.cursor

import borg.trikeshed.lib.*

// ── Cache-Topology-Driven Materialization ───────────────────────
//
// Controls when staircase-shaped compositions (Series of Series)
// are flattened. The budget is how many levels of lazy composition
// fit in L1 before pointer-chasing exceeds copy cost.

/**
 * ReificationContext — the materialization policy.
 *
 * maxDepth = how many levels of lazy composition to allow before flattening.
 * Derived from L1 data cache topology.
 */
data class ReificationContext(val maxDepth: Int) {
    companion object {
        /** Always materialize — no lazy composition. */
        val EAGER = ReificationContext(0)

        /** Never materialize — fully lazy. */
        val LAZY = ReificationContext(Int.MAX_VALUE)

        /**
         * Derive from cache topology.
         * depth = log2(L1 / 4096) — the number of staircase levels
         * that fit in L1 before pointer-chasing exceeds copy cost.
         */
        fun from(topology: CacheTopology): ReificationContext {
            val l1 = topology.l1DataBytes ?: return LAZY
            if (l1 < 4096) return EAGER
            val depth = (kotlin.math.ln(l1.toDouble() / 4096.0) / kotlin.math.ln(2.0))
                .toInt().coerceIn(0, 16)
            return ReificationContext(depth)
        }
    }
}

/**
 * CacheTopology — L1/L2/L3 data cache sizes.
 * Platform expect/actual for actual detection.
 */
data class CacheTopology(
    val l1DataBytes: Int? = null,
    val l2Bytes: Int? = null,
    val l3Bytes: Int? = null,
) {
    companion object {
        /** Sensible default: 32KB L1 (Zen 3 / Ice Lake). */
        val DEFAULT = CacheTopology(l1DataBytes = 32768, l2Bytes = 262144, l3Bytes = 8388608)
    }
}

// ── Combine ─────────────────────────────────────────────────────
//
// TrikeShed's query execution engine — decides when to materialize
// stair-shaped compositions.

/**
 * Combine two Series with optional materialization.
 * If depth budget is exhausted, flatten to a single contiguous array.
 */
fun <T> combine(
    top: Series<T>,
    bottom: Series<T>,
    ctx: ReificationContext = ReificationContext.LAZY,
    currentDepth: Int = 0,
): Series<T> {
    val totalSize = top.size + bottom.size
    val lazyView: Series<T> = totalSize j { i ->
        if (i < top.size) top[i] else bottom[i - top.size]
    }
    return if (currentDepth >= ctx.maxDepth) {
        // Materialize — copy to flat array
        materialize(lazyView)
    } else {
        lazyView
    }
}

/** Materialize a Series to a flat Array-backed Series. */
@Suppress("UNCHECKED_CAST")
fun <T> materialize(series: Series<T>): Series<T> {
    val arr = Array<Any?>(series.size) { i -> series[i] }
    return arr.size j { i -> arr[i] as T }
}
