package borg.literbike.rbcursive

/**
 * Small densifier shim: register-packed Join and Indexed types.
 * Ported from literbike/src/rbcursive/densifier.rs.
 */

/**
 * A tiny register-packed pair stored in a Long when possible.
 */
@JvmInline
value class JoinU32Pair(val packed: Long) {
    companion object {
        fun pack(a: Int, b: Int): JoinU32Pair {
            val packed = ((a.toLong() and 0xFFFFFFFFL) shl 32) or (b.toLong() and 0xFFFFFFFFL)
            return JoinU32Pair(packed)
        }
    }

    fun unpack(): Pair<Int, Int> {
        val a = ((packed ushr 32) and 0xFFFFFFFFL).toInt()
        val b = (packed and 0xFFFFFFFFL).toInt()
        return a to b
    }
}

/**
 * Indexed<T> = offset + slice access for the common case of indexing into a list.
 */
class Indexed<T>(
    private val offset: Int,
    private val slice: List<T>
) {
    constructor(offset: Int, slice: Array<T>) : this(offset, slice.asList())

    fun get(): T? = slice.getOrNull(offset)
}

/**
 * Projection type - demonstrates API for transforming a domain value.
 */
class Projection<X, T>(
    private val domain: X,
    private val projector: (X) -> T
) {
    fun project(): T = projector(domain)
}
