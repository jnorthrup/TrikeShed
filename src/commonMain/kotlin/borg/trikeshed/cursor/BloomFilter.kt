package borg.trikeshed.cursor

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * Minimal BloomFilter for cursor index operations.
 * Ported from columnar/vec/util/BloomFilter.kt — pure Kotlin, no JVM deps.
 *
 * @param n Expected number of elements
 * @param m Desired size of the container in bits (default: n * 11, good for 31-bit ints)
 */
class BloomFilter(private val n: Int, private val m: Int = n * 11) {
    internal val k: Int = ((LN2 * m / n) + 0.5).toInt().let { if (it <= 0) 1 else it }
    internal var bits = IntArray((m + 31) / 32)
    private val prng = RandomInRange(m, k)

    fun add(o: Any) {
        prng.init(o)
        while (prng.hasNext()) {
            val idx = prng.nextValue()
            bits[idx / 32] = bits[idx / 32] or (1 shl (idx % 32))
        }
    }

    operator fun contains(o: Any): Boolean {
        prng.init(o)
        while (prng.hasNext()) {
            val idx = prng.nextValue()
            if ((bits[idx / 32] and (1 shl (idx % 32))) == 0) return false
        }
        return true
    }

    fun clear() { bits.fill(0) }

    fun clone(): BloomFilter = BloomFilter(n, m).also { copy ->
        copy.bits = bits.copyOf()
    }

    override fun hashCode(): Int = bits.contentHashCode() xor k

    fun equals(other: BloomFilter): Boolean = bits.contentEquals(other.bits) && k == other.k

    fun merge(other: BloomFilter) {
        require(other.k == k && other.bits.size == bits.size) { "Incompatible bloom filters" }
        for (i in bits.indices) bits[i] = bits[i] or other.bits[i]
    }

    class RandomInRange(
        private val max: Int,
        private val count: Int,
        private var seed: Long = 42L,
    ) : MutableIterator<BloomFilter.RandomInRange> {
        private var i = 0
        private var _value = 0

        fun init(o: Any) { seed = o.hashCode().toLong() }

        override fun hasNext(): Boolean = i < count
        override fun next(): BloomFilter.RandomInRange {
            i++
            // Simple LCG pseudo-random
            seed = (seed * 6364136223846793005L + 1442695040888963407L) ushr 33
            _value = (seed % max).toInt()
            if (_value < 0) _value = -_value
            return this
        }
        fun nextValue(): Int = _value

        override fun remove() { throw UnsupportedOperationException() }
    }

    companion object {
        private const val LN2 = 0.6931471805599453
    }
}

/**
 * Build bloom filters from index clusters — used to accelerate key lookups.
 * Returns a Series of Join<BloomFilter, IntArray> for each cluster.
 */
fun bloomAccess(groupClusters: Series<Series<Int>>): Series<Join<BloomFilter, Series<Int>>> {
    val n = groupClusters.size
    return n j { ix: Int ->
        val cluster: Series<Int> = groupClusters[ix]
        val bf = BloomFilter(cluster.size)
        var i = 0
        while (i < cluster.size) {
            bf.add(cluster[i])
            i++
        }
        Join(bf, cluster)
    }
}