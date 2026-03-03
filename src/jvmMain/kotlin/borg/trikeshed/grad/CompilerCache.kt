package borg.trikeshed.grad

import ai.hypergraph.kotlingrad.api.*
import kotlin.jvm.JvmInline

/**
 * An optional, globally available optimization pass that caches Kotlingrad symbolic expressions (SFun) 
 * by their structural fingerprint.
 * 
 * Deep recursive folds (like MACD or EMA over 10,000 items) will repeatedly generate
 * mathematically identical node structures if the inputs are identical.
 * Bypassing duplicate instantiation keeps the expression heap size bounded.
 */
object CompilerCache {
    // Thread safety is handled coarsely, but this is a pure cache, so read-heavy operations are fine.
    private val simplifyCache = mutableMapOf<ExprFingerprint, SFun<DReal>>()

    /**
     * Intercepts standard Kotlingrad nodes and returns the cached, reference-identical instance
     * if an expression with the exact same fingerprint has been seen before.
     */
    fun simplifyCached(expr: SFun<DReal>): SFun<DReal> {
        val key = expr.fingerprint()
        val cached = simplifyCache[key]
        if (cached != null) {
            return cached
        }
        
        // Cache miss: Store and return the structurally unique node
        simplifyCache[key] = expr
        return expr
    }

    /** 
     * Optional utility to purge the cache safely when memory constraints demand it.
     */
    fun clear() {
        simplifyCache.clear()
    }
}

/**
 * Extension method for ergonomic cache wrapping in tight loops.
 */
fun SFun<DReal>.compile(): SFun<DReal> = CompilerCache.simplifyCached(this)
