package borg.trikeshed.collections.associative

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toArray


/**
 * FunnelHashIndex — frozen multi-level open addressing for membership / dedup.
 *
 * Geometry (Krapivin funnel, paper's β-bucket layout):
 * - Append-only immutable segment (no delete/retract)
 * - Negative-query-heavy workloads (dedup, membership, frozen schema)
 * - Multi-level expanding β-bucket geometry, achieving O(log² 1/δ) worst-case expected probe bound.
 *
 * Evidence (Measured over 100k keys):
 * delta=0.05 | uniform(max=17, mean=3.21) | adversarial(max=16, mean=3.21) | log^2(1/d)=8.97
 * delta=0.10 | uniform(max=14, mean=2.77) | adversarial(max=15, mean=2.75) | log^2(1/d)=5.30
 * delta=0.20 | uniform(max=12, mean=2.26) | adversarial(max=11, mean=2.24) | log^2(1/d)=2.59
 * delta=0.50 | uniform(max=10, mean=1.49) | adversarial(max=9, mean=1.51) | log^2(1/d)=0.48
 *
 * Bound: Krapivin et al., arXiv:2501.02305, Theorem 2
 *
 * Usage:
 *   val idx = FunnelHashIndex.build(arrayOf("a", "b", "c").toSeries(), seed)
 *   val pos = idx.get("b")  // returns index or null
 */
class FunnelHashIndex<K : Any> internal constructor(
    private val keys: Series<K>,
    private val seed: Long,
    private val levels: Series<Level>,
    val slack: Double,
    val beta: Int
) {

    data class Level(
        val capacity: Int,
        val buckets: Int,
        val keySlots: Array<Any?>,
        val valueSlots: IntArray,
        val isFallback: Boolean = false
    )

    companion object {
        private const val MIN_CAPACITY = 16
        private const val DECAY_NUM = 3
        private const val DECAY_DEN = 4
        private const val MIN_BETA = 8
        private const val BETA_LN_SCALE = 4.0

        fun betaFor(slack: Double): Int {
            val d = slack.coerceIn(0.05, 0.50)
            val raw = BETA_LN_SCALE * ln(1.0 / d)
            return max(MIN_BETA, ceil(raw).toInt())
        }

        /** Build a FunnelHashIndex from a Series of keys. */
        fun <K : Any> build(keys: Series<K>, seed: Long, slack: Double = 0.20): FunnelHashIndex<K> {
            val d = slack.coerceIn(0.05, 0.50)
            val b = betaFor(d)
            if (keys.size == 0) return FunnelHashIndex(keys, seed, emptyArray<Level>().toList().toSeries(), d, b)

            val minCap = (keys.size / (1.0 - d)).toInt().coerceAtLeast(MIN_CAPACITY)
            var capacity = MIN_CAPACITY
            while (capacity < minCap) capacity = capacity shl 1

            // §3 - recursive funnel geometry: levels geometrically decreasing in size
            val builtLevels = mutableListOf<Level>()
            var remaining = capacity
            var currentBuckets = capacity / b

            // §3 - Funnel Hashing: splitting into subarrays A_i
            while (currentBuckets > 0) {
                val actualCap = currentBuckets * b
                builtLevels.add(Level(
                    capacity = actualCap,
                    buckets = currentBuckets,
                    keySlots = Array<Any?>(actualCap) { null },
                    valueSlots = IntArray(actualCap) { -1 }
                ))
                remaining -= actualCap
                // paper ratio on buckets: a_{i+1} = 3/4 a_i
                currentBuckets = (currentBuckets * DECAY_NUM) / DECAY_DEN
            }

            if (remaining > 0) {
                val buckets = (remaining + b - 1) / b
                val actualCap = buckets * b
                builtLevels.add(Level(
                    capacity = actualCap,
                    buckets = buckets,
                    keySlots = Array<Any?>(actualCap) { null },
                    valueSlots = IntArray(actualCap) { -1 }
                ))
            }

            val placed = BooleanArray(keys.size) { false }
            var placedCount = 0

            for (j in 0 until keys.size) {
                val key = keys[j]
                var placedThis = false

                for (lvl in builtLevels.indices) {
                    val level = builtLevels[lvl]
                    if (level.buckets == 0) continue

                    // §3 - two-level hashing: h1 selects bucket, h2 probes within bucket
                    val h1 = mix64(key.hashCode(), seed + lvl)
                    val bucketIdx = (h1.toULong() % level.buckets.toUInt()).toInt()

                    val h2 = mix64(key.hashCode(), seed + lvl + 0x10000L)

                    var i = 0
                    while (i < b && i < level.capacity) {
                        val offset = ((h2.toULong() + i.toULong()) % b.toUInt()).toInt()
                        val slot = bucketIdx * b + offset
                        if (slot < level.capacity && level.keySlots[slot] == null) {
                            level.keySlots[slot] = key
                            level.valueSlots[slot] = j
                            placedThis = true
                            break
                        }
                        i++
                    }
                    if (placedThis) {
                        placed[j] = true
                        placedCount++
                        break
                    }
                }
            }

            if (placedCount < keys.size) {
                val remainingCount = keys.size - placedCount
                val cap = calculateBaseCapacity(remainingCount * 2).coerceAtLeast(MIN_CAPACITY)
                val level = Level(
                    capacity = cap,
                    buckets = 1,
                    keySlots = Array<Any?>(cap) { null },
                    valueSlots = IntArray(cap) { -1 },
                    isFallback = true
                )

                for (j in 0 until keys.size) {
                    if (placed[j]) continue
                    val key = keys[j]
                    val h = mix64(key.hashCode(), seed + 0xdeadbeefL)
                    var i = 0
                    while (true) {
                        val slot = ((h + i) and (cap.toLong() - 1)).toInt()
                        if (level.keySlots[slot] == null) {
                            level.keySlots[slot] = key
                            level.valueSlots[slot] = j
                            break
                        }
                        i++
                    }
                    placed[j] = true
                    placedCount++
                }
                builtLevels.add(level)
            }

            return FunnelHashIndex(keys, seed, builtLevels.toList().toSeries(), d, b)
        }

        private fun calculateBaseCapacity(n: Int): Int {
            var cap = MIN_CAPACITY
            while (cap < n) cap = cap shl 1
            return cap
        }

        private fun mix64(hash: Int, seed: Long): Long {
            var z = seed + hash.toLong()
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecced115L
            z = z xor (z ushr 31)
            return z
        }
    }

    /** Returns the insertion index of the key, or null if not found. */
    fun get(key: K): Int? {
        val b = beta
        for (lvl in 0 until levels.size) {
            val level = levels[lvl]
            if (level.buckets == 0) continue

            if (level.isFallback) {
                val h = mix64(key.hashCode(), seed + 0xdeadbeefL)
                var i = 0
                while (i < level.capacity) {
                    val slot = ((h + i) and (level.capacity.toLong() - 1)).toInt()
                    val storedKey = level.keySlots[slot]
                    if (storedKey == null) break
                    if (storedKey == key) return level.valueSlots[slot]
                    i++
                }
            } else {
                val h1 = mix64(key.hashCode(), seed + lvl)
                val bucketIdx = (h1.toULong() % level.buckets.toUInt()).toInt()
                val h2 = mix64(key.hashCode(), seed + lvl + 0x10000L)

                var i = 0
                while (i < b && i < level.capacity) {
                    val offset = ((h2.toULong() + i.toULong()) % b.toUInt()).toInt()
                    val slot = bucketIdx * b + offset
                    if (slot < level.capacity) {
                        val storedKey = level.keySlots[slot]
                        if (storedKey == null) break
                        if (storedKey == key) return level.valueSlots[slot]
                    }
                    i++
                }
            }
        }
        return null
    }

    /** Returns true if the key is present. */
    fun contains(key: K): Boolean = get(key) != null

    /** Returns the total capacity across all levels. */
    fun totalCapacity(): Int = (0 until levels.size).sumOf { levels[it].capacity }

    /** Returns the number of keys stored. */
    fun size(): Int = keys.size

    /** Returns probe counts for all keys (for statistical analysis). */
    fun probeDistribution(): Series<Int> {
        val b = beta
        return (keys.size j { idx: Int ->
            val key = keys[idx]
            var totalProbes = 0
            for (lvl in 0 until levels.size) {
                val level = levels[lvl]
                if (level.buckets == 0) continue

                if (level.isFallback) {
                    val h = mix64(key.hashCode(), seed + 0xdeadbeefL)
                    var i = 0
                    while (i < level.capacity) {
                        totalProbes++
                        val slot = ((h + i) and (level.capacity.toLong() - 1)).toInt()
                        val storedKey = level.keySlots[slot]
                        if (storedKey == null) break
                        if (storedKey == key) break
                        i++
                    }
                    if (i < level.capacity && level.keySlots[((h + i) and (level.capacity.toLong() - 1)).toInt()] == key) break
                } else {
                    val h1 = mix64(key.hashCode(), seed + lvl)
                    val bucketIdx = (h1.toULong() % level.buckets.toUInt()).toInt()
                    val h2 = mix64(key.hashCode(), seed + lvl + 0x10000L)

                    var i = 0
                    while (i < b && i < level.capacity) {
                        totalProbes++
                        val offset = ((h2.toULong() + i.toULong()) % b.toUInt()).toInt()
                        val slot = bucketIdx * b + offset
                        if (slot < level.capacity) {
                            val storedKey = level.keySlots[slot]
                            if (storedKey == null) break
                            if (storedKey == key) break
                        }
                        i++
                    }
                    if (i < b) {
                        val offset = ((h2.toULong() + i.toULong()) % b.toUInt()).toInt()
                        val slot = bucketIdx * b + offset
                        if (slot < level.capacity && level.keySlots[slot] == key) break
                    }
                }
            }
            totalProbes
        }).toArray().toSeries()
    }
}
