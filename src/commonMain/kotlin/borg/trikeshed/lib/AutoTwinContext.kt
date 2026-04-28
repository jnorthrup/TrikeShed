@file:Suppress("UNCHECKED_CAST", "NonAsciiCharacters")

package borg.trikeshed.lib

/**
 * Accumulates strategy data for applying [autoTwin] across a [Series].
 *
 * Starts in a probing state.  The first call to [pack] inspects the runtime
 * type of its arguments and locks in the densest monomorphic packer.  All
 * subsequent calls use the locked packer — zero [is] checks, zero megamorphic
 * dispatch, even when called from generic contexts like [zipWithNext].
 *
 * If the runtime type is not one of the five dense-packed primitives
 * (Int/Short/Byte/Char/Float), the locked packer delegates to [autoTwin]
 * with per-element [is] checks — that case is already the cold path.
 *
 * Usage:
 * ```
 * val ctx = AutoTwinContext<Int>()
 * Series<Twin<Int>> = sourceSeries.zipWithNext(ctx)
 * ```
 *
 * Or shortcut from a sample:
 * ```
 * val ctx = AutoTwinContext.from(sampleElement)
 * ```
 */
class AutoTwinContext<T> {

    private var locked: ((T, T) -> Twin<T>)? = null

    companion object {
        /** Pre-seed the context from a sample pair — skips the probing phase entirely. */
        fun <T> from(sampleA: T, sampleB: T): AutoTwinContext<T> {
            val ctx = AutoTwinContext<T>()
            ctx.pack(sampleA, sampleB) // triggers lock-in
            return ctx
        }

        /** Pre-seed from an existing Twin (extracts .a and .b as sample). */
        fun <T> from(sample: Twin<T>): AutoTwinContext<T> =
            from(sample.a, sample.b)
    }

    /**
     * Pack two values into a [Twin].
     *
     * First call probes runtime types and locks in the optimal packer.
     * Subsequent calls use the locked packer directly — no [is] checks.
     */
    fun pack(a: T, b: T): Twin<T> {
        locked?.let { return it(a, b) }

        // ── Probe: determine runtime type and lock ──────────────────────
        locked = when {
            a is Int    && b is Int    -> intPacker()
            a is Short  && b is Short  -> shortPacker()
            a is Byte   && b is Byte   -> bytePacker()
            a is Char   && b is Char   -> charPacker()
            a is Float  && b is Float  -> floatPacker()
            else                       -> { x, y -> autoTwin(x, y) }
        }
        return locked!!(a, b)
    }

    /** True once the first [pack] call has locked in a strategy. */
    val isLocked: Boolean get() = locked != null

    // ── Monomorphic packers — capture the cast once, reuse per element ──

    private fun intPacker(): (T, T) -> Twin<T> {
        val cast: (T) -> Int = { it as Int }
        return { a, b -> TwInt(packInts(cast(a), cast(b))) as Twin<T> }
    }

    private fun shortPacker(): (T, T) -> Twin<T> {
        val cast: (T) -> Short = { it as Short }
        return { a, b -> Twhort(packShorts(cast(a), cast(b))) as Twin<T> }
    }

    private fun bytePacker(): (T, T) -> Twin<T> {
        val cast: (T) -> Byte = { it as Byte }
        return { a, b -> Twyte(packBytes(cast(a), cast(b))) as Twin<T> }
    }

    private fun charPacker(): (T, T) -> Twin<T> {
        val cast: (T) -> Char = { it as Char }
        return { a, b -> Twhar(packChars(cast(a), cast(b))) as Twin<T> }
    }

    private fun floatPacker(): (T, T) -> Twin<T> {
        val cast: (T) -> Float = { it as Float }
        return { a, b -> TwInt(packFloats(cast(a), cast(b))) as Twin<T> }
    }
}
