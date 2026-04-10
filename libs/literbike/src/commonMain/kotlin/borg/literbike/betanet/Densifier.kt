package borg.literbike.betanet

/**
 * Densifier - register-packed two-tuple for common Indexed patterns.
 * Ported from literbike/src/betanet/densifier.rs.
 */

/**
 * Concrete register-packed Join for (u32, u32) stored as a single Long (64-bit).
 * Layout: high 32 bits = first (offset), low 32 bits = accessor index/id.
 */
@JvmInline
value class DensifiedJoinU32Fn(private val packed: Long) {
    companion object {
        fun new(first: Int, accessor: Int): DensifiedJoinU32Fn {
            val packed = (first.toLong() and 0xFFFFFFFFL) shl 32 or (accessor.toLong() and 0xFFFFFFFFL)
            return DensifiedJoinU32Fn(packed)
        }
    }

    /** Extract the first (offset) value */
    fun first(): Int = (packed ushr 32).toInt()

    /** Extract the accessor id/value */
    fun accessor(): Int = (packed and 0xFFFFFFFFL).toInt()

    /** Raw packed representation */
    fun asLong(): Long = packed
}
