@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.lib
import kotlin.math.log2
import kotlin.math.roundToInt
@JvmInline
value class ReificationContext(val maxDepth: Int) {
    init { require(maxDepth >= 0) { "maxDepth must be non-negative" } }
    fun deeper(): ReificationContext? = if (maxDepth > 0) ReificationContext(maxDepth - 1) else null
    companion object {
        fun from(topology: CacheTopology): ReificationContext {
            val l1 = topology.l1DataBytes ?: return ReificationContext(Int.MAX_VALUE)
            if (l1 < 4096) return ReificationContext(0)
            return ReificationContext(log2(l1.toDouble() / 4096.0).roundToInt().coerceIn(0, 16))
        }
    }
}
