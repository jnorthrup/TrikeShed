package borg.trikeshed.collections

import borg.trikeshed.job.Sha256Pure

/**
 * ElasticHashIndex — double-hashing open-addressing hash table.
 *
 * Key properties:
 * - Append-only immutable segment (no delete/retract)
 * - Positive-query-heavy workloads
 * - Load factor ≤ 0.5
 * - Probe sequence: h1 + i * h2  (ordinary double hashing)
 * - Deterministic replay: probe entropy derived from canonical facet bytes + committed seed
 *
 * Usage:
 *   val idx = ElasticHashIndex.build(listOf("a", "b", "c"), seed)
 *   val pos = idx.get("b")  // returns Some(1) or null
 */
class ElasticHashIndex<K : Any> internal constructor(
    private val keys: List<K>,
    private val seed: Long,
    private val table: Array<Any?>,
    private val positions: IntArray,
) {

    companion object {
        private const val MIN_CAPACITY = 16

        /** Build an ElasticHashIndex from a list of keys. */
        fun <K : Any> build(keys: List<K>, seed: Long): ElasticHashIndex<K> {
            val capacity = calculateCapacity(keys.size)
            val table = Array<Any?>(capacity) { null }
            val positions = IntArray(capacity) { -1 }

            var i = 0
            for (key in keys) {
                val (h1, h2) = doubleHash(key, seed)
                var probe = 0
                while (true) {
                    val slot = ((h1.toLong() + probe.toLong() * h2.toLong()) and (capacity.toLong() - 1)).toInt()
                    if (table[slot] == null) {
                        table[slot] = key
                        positions[slot] = i
                        break
                    }
                    probe++
                }
                i++
            }

            return ElasticHashIndex(keys, seed, table, positions)
        }

        private fun calculateCapacity(n: Int): Int {
            var cap = MIN_CAPACITY
            while (cap < n * 2) cap = cap shl 1  // load factor ≤ 0.5
            return cap
        }

        private fun doubleHash(key: Any, seed: Long): Pair<Int, Int> {
            val bytes = Sha256Pure.digest(key.toString().encodeToByteArray())
            var z = seed + bytes.size.toLong()
            for (b in bytes) {
                z = (z + (b.toLong() and 0xFFL)) * -0x61c8864680b583ebL
            }
            z = (z xor (z shr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z shr 27)) * -0x6b2fb644ecced115L
            z = z xor (z shr 31)
            val h1 = (z and 0xFFFFFFFFL).toInt()
            val h2 = (z shr 32).toInt() or 1  // ensure odd for coprime to power-of-2
            return h1 to h2
        }
    }

    /** Returns the insertion index of the key, or null if not found. */
    fun get(key: K): Int? {
        val (h1, h2) = doubleHash(key, seed)
        val capacity = table.size
        var probe = 0
        while (probe < capacity) {
            val slot = ((h1.toLong() + probe.toLong() * h2.toLong()) and (capacity.toLong() - 1)).toInt()
            val stored = table[slot]
            if (stored == null) return null
            if (stored == key) return positions[slot]
            probe++
        }
        return null
    }

    /** Returns true if the key is present. */
    fun contains(key: K): Boolean = get(key) != null

    /** Returns the table capacity (power of 2). */
    fun capacity(): Int = table.size

    /** Returns the number of stored keys. */
    fun size(): Int = keys.size

    /** Returns the load factor (size / capacity). */
    fun loadFactor(): Double = size().toDouble() / capacity()

    /** Returns probe counts for all keys (for statistical analysis). */
    fun probeDistribution(): List<Int> {
        return keys.map { key ->
            val (h1, h2) = doubleHash(key, seed)
            val capacity = table.size
            var probe = 0
            while (probe < capacity) {
                val slot = ((h1.toLong() + probe.toLong() * h2.toLong()) and (capacity.toLong() - 1)).toInt()
                val stored = table[slot]
                if (stored == null) break
                if (stored == key) return@map probe + 1
                probe++
            }
            probe + 1
        }
    }

    private fun doubleHash(key: Any, seed: Long): Pair<Int, Int> {
        val bytes = Sha256Pure.digest(key.toString().encodeToByteArray())
        var z = seed + bytes.size.toLong()
        for (b in bytes) {
            z = (z + (b.toLong() and 0xFFL)) * -0x61c8864680b583ebL
        }
        z = (z xor (z shr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z shr 27)) * -0x6b2fb644ecced115L
        z = z xor (z shr 31)
        val h1 = (z and 0xFFFFFFFFL).toInt()
        val h2 = (z shr 32).toInt() or 1
        return h1 to h2
    }
}