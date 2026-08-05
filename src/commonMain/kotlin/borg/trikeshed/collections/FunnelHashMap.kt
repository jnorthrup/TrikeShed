package borg.trikeshed.collections

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max

/**
<<<<<<< ours
 * FunnelHashMap — greedy open-addressing map with Krapivin-shaped geometry.
 *
 * Inspired by Farach-Colton, Krapivin, Kuszmaul (arXiv:2501.02305) "funnel hashing":
 * - Levels A_1 … A_α of decreasing size (¾ decay here)
 * - Each level split into buckets of size β = max(8, ⌈4 ln(1/δ)⌉)
 * - Insert/get: hash → one bucket in A_i; linear probe within that bucket; else next level
 * - Final remainder level is overflow
 *
 * Parameters:
 * - [slack] = δ ∈ [0.05, 0.50]: free fraction. Load target = 1−δ (default δ=0.20 → load 0.80).
 * - β grows as tables run fuller (smaller δ). Default δ keeps β=8 (prior fixed constant).
=======
 * FunnelHashMap — greedy open-addressing hash map inspired by Krapivin et al. (2025)
 * "Optimal Bounds for Open Addressing Without Reordering".
>>>>>>> theirs
 *
 * Honesty vs the paper:
 * - Bound O(log² 1/δ) is not measured or proven for this implementation.
 * - remove()/tombstones are outside the paper's insert-focused setting.
 * - Paper elastic (non-greedy) hashing is not this type.
 * - Use [probeDistribution] to sample probe counts under load — do not cite CACM bounds from docs alone.
 *
<<<<<<< ours
 * Production consumer: couch.isam.Stringpool (memoized string → offset).
 */
class FunnelHashMap<K : Any, V>(
    initialCapacity: Int = 32,
    slack: Double = 0.20,
) {
=======
 * Note: remove/tombstones are extensions outside the original paper's scope.
 * Used by Stringpool.
 */
class FunnelHashMap<K : Any, V>(initialCapacity: Int = 32, slack: Double = 0.20) {
>>>>>>> theirs
    private var size = 0
    private var capacity = 0

    private var keys: Array<Any?> = emptyArray()
    private var values: Array<Any?> = emptyArray()

    private var levels: Array<Level> = emptyArray()

<<<<<<< ours
    /** Free-fraction δ used for load target and β sizing. */
    val slack: Double = slack.coerceIn(0.05, 0.50)

    /** Bucket size β derived from slack (recomputed on each resize). */
    var beta: Int = betaFor(this.slack)
        private set
=======
    private val actualSlack = slack.coerceIn(0.05, 0.50)
    private val beta = maxOf(8, kotlin.math.ceil(4 * kotlin.math.ln(1.0 / actualSlack)).toInt())
>>>>>>> theirs

    companion object {
        private val ABSENT = Any()
        private val DELETED = Any()

        private const val DECAY_NUM = 3
        private const val DECAY_DEN = 4 // A_{i+1} = 3/4 A_i
        private const val MIN_BETA = 8
        /** Scale so δ=0.20 stays at floor 8; tighter δ grows β (δ=0.10→10, δ=0.05→12). */
        private const val BETA_LN_SCALE = 4.0

        /**
         * β ≈ Θ(log 1/δ). Floor 8 matches the prior fixed constant (Stringpool default-safe).
         */
        fun betaFor(slack: Double): Int {
            val d = slack.coerceIn(0.05, 0.50)
            val raw = BETA_LN_SCALE * ln(1.0 / d)
            return max(MIN_BETA, ceil(raw).toInt())
        }
    }

    class Level(
        val offset: Int,
        val capacity: Int,
        val buckets: Int,
    )

    init {
        resize(initialCapacity.coerceAtLeast(32))
    }

    val count: Int get() = size
    val tableCapacity: Int get() = capacity
    val loadFactor: Double get() = if (capacity == 0) 0.0 else size.toDouble() / capacity
    val levelCount: Int get() = levels.size

    /** Max live entries before resize: floor(capacity * (1 − δ)). */
    private fun liveCap(): Int = (capacity * (1.0 - slack)).toInt().coerceAtLeast(1)

    private fun resize(newCap: Int) {
        val oldKeys = keys
        val oldValues = values

        capacity = newCap
        beta = betaFor(slack)
        keys = Array(capacity) { ABSENT }
        values = Array(capacity) { ABSENT }

        val newLevels = mutableListOf<Level>()
        var remaining = capacity
        var currentLevelCap = remaining / 2
        var offset = 0
        val b = beta

<<<<<<< ours
        while (currentLevelCap >= b) {
            val buckets = currentLevelCap / b
            val actualCap = buckets * b
=======
        while (currentLevelCap >= beta) {
            val buckets = currentLevelCap / beta
            val actualCap = buckets * beta
>>>>>>> theirs
            newLevels.add(Level(offset, actualCap, buckets))
            offset += actualCap
            remaining -= actualCap
            currentLevelCap = (actualCap * DECAY_NUM) / DECAY_DEN
        }

        if (remaining > 0) {
<<<<<<< ours
            newLevels.add(Level(offset, remaining, (remaining + b - 1) / b))
=======
            newLevels.add(Level(offset, remaining, (remaining + beta - 1) / beta))
>>>>>>> theirs
        }

        levels = newLevels.toTypedArray()
        size = 0

        for (i in oldKeys.indices) {
            val k = oldKeys[i]
            if (k !== ABSENT && k !== DELETED) {
                @Suppress("UNCHECKED_CAST")
                put(k as K, oldValues[i] as V)
            }
        }
    }

    private fun hash(key: K, levelIndex: Int): Int {
        var h = key.hashCode()
        h = h xor (h ushr 16)
        h = h * -0x7a143595
        h = h xor (h ushr 13)
        h = h * -0x512548cb
        h = h xor (h ushr 16)
        return h xor (levelIndex * 0x9e3779b9.toInt())
    }

    fun put(key: K, value: V): V? {
<<<<<<< ours
        if (size >= liveCap()) {
=======
        // Target max load factor based on slack
        if (size >= (capacity * (1.0 - actualSlack)).toInt()) {
>>>>>>> theirs
            resize(capacity * 2)
        }

        val b = beta

        // Fast path: insert into first free slot in funnel order when key is new.
        for (lvl in levels.indices) {
            val level = levels[lvl]
            if (level.buckets == 0) continue
            val h = hash(key, lvl)
            val bucketIdx = (h.toUInt() % level.buckets.toUInt()).toInt()
<<<<<<< ours
            val startIdx = level.offset + bucketIdx * b
            val bound = minOf(startIdx + b, level.offset + level.capacity, keys.size)
=======

            // Linear probe within the bucket (or up to beta elements)
            val startIdx = level.offset + bucketIdx * beta
            val bound = minOf(startIdx + beta, level.offset + level.capacity, keys.size)
>>>>>>> theirs

            var firstTombstone = -1
            for (i in startIdx until bound) {
                val k = keys[i]
                if (k === ABSENT) {
                    val insIdx = if (firstTombstone != -1) firstTombstone else i
                    keys[insIdx] = key
                    values[insIdx] = value
                    size++
                    return null
                } else if (k === DELETED) {
                    if (firstTombstone == -1) firstTombstone = i
                } else if (k == key) {
                    @Suppress("UNCHECKED_CAST")
                    val old = values[i] as V?
                    values[i] = value
                    return old
                }
            }
        }

        // Tombstone / dense path: find existing first, then first free.
        for (lvl in levels.indices) {
            val level = levels[lvl]
            if (level.buckets == 0) continue
            val h = hash(key, lvl)
            val bucketIdx = (h.toUInt() % level.buckets.toUInt()).toInt()
<<<<<<< ours
            val startIdx = level.offset + bucketIdx * b
            val bound = minOf(startIdx + b, level.offset + level.capacity, keys.size)
=======
            val startIdx = level.offset + bucketIdx * beta
            val bound = minOf(startIdx + beta, level.offset + level.capacity, keys.size)
>>>>>>> theirs
            for (i in startIdx until bound) {
                val k = keys[i]
                if (k === ABSENT) break
                if (k == key) {
                    @Suppress("UNCHECKED_CAST")
                    val old = values[i] as V?
                    values[i] = value
                    return old
                }
            }
        }

        for (lvl in levels.indices) {
            val level = levels[lvl]
            if (level.buckets == 0) continue
            val h = hash(key, lvl)
            val bucketIdx = (h.toUInt() % level.buckets.toUInt()).toInt()
<<<<<<< ours
            val startIdx = level.offset + bucketIdx * b
            val bound = minOf(startIdx + b, level.offset + level.capacity, keys.size)
=======
            val startIdx = level.offset + bucketIdx * beta
            val bound = minOf(startIdx + beta, level.offset + level.capacity, keys.size)
>>>>>>> theirs
            for (i in startIdx until bound) {
                val k = keys[i]
                if (k === ABSENT || k === DELETED) {
                    keys[i] = key
                    values[i] = value
                    size++
                    return null
                }
            }
        }

        resize(capacity * 2)
        return put(key, value)
    }

    fun get(key: K): V? {
        val b = beta
        for (lvl in levels.indices) {
            val level = levels[lvl]
            if (level.buckets == 0) continue
            val h = hash(key, lvl)
            val bucketIdx = (h.toUInt() % level.buckets.toUInt()).toInt()
<<<<<<< ours
            val startIdx = level.offset + bucketIdx * b
            val bound = minOf(startIdx + b, level.offset + level.capacity, keys.size)
=======

            val startIdx = level.offset + bucketIdx * beta
            val bound = minOf(startIdx + beta, level.offset + level.capacity, keys.size)
>>>>>>> theirs

            for (i in startIdx until bound) {
                val k = keys[i]
                if (k === ABSENT) {
                    return null
                } else if (k == key) {
                    @Suppress("UNCHECKED_CAST")
                    return values[i] as V?
                }
            }
        }
        return null
    }

    fun contains(key: K): Boolean = get(key) != null

    fun remove(key: K): V? {
        val b = beta
        for (lvl in levels.indices) {
            val level = levels[lvl]
            if (level.buckets == 0) continue
            val h = hash(key, lvl)
            val bucketIdx = (h.toUInt() % level.buckets.toUInt()).toInt()
<<<<<<< ours
            val startIdx = level.offset + bucketIdx * b
            val bound = minOf(startIdx + b, level.offset + level.capacity, keys.size)
=======

            val startIdx = level.offset + bucketIdx * beta
            val bound = minOf(startIdx + beta, level.offset + level.capacity, keys.size)
>>>>>>> theirs

            for (i in startIdx until bound) {
                val k = keys[i]
                if (k === ABSENT) {
                    return null
                } else if (k == key) {
                    @Suppress("UNCHECKED_CAST")
                    val old = values[i] as V?
                    keys[i] = DELETED
                    values[i] = ABSENT
                    size--
                    return old
                }
            }
        }
        return null
    }

<<<<<<< ours
    /**
     * Probe counts for present [sample] keys (or empty → no-op).
     * Each entry is probes until found (or 0 if missing). For load experiments.
     */
    fun probeDistribution(sample: List<K>): List<Int> {
        val b = beta
        return sample.map { key ->
            var probes = 0
=======
    fun probeDistribution(sample: List<K>): List<Int> {
        val result = mutableListOf<Int>()
        for (key in sample) {
            var probes = 0
            var done = false
>>>>>>> theirs
            for (lvl in levels.indices) {
                val level = levels[lvl]
                if (level.buckets == 0) continue
                val h = hash(key, lvl)
                val bucketIdx = (h.toUInt() % level.buckets.toUInt()).toInt()
<<<<<<< ours
                val startIdx = level.offset + bucketIdx * b
                val bound = minOf(startIdx + b, level.offset + level.capacity, keys.size)
                for (i in startIdx until bound) {
                    probes++
                    val k = keys[i]
                    if (k === ABSENT) return@map probes
                    if (k == key) return@map probes
                }
            }
            probes
        }
=======

                val startIdx = level.offset + bucketIdx * beta
                val bound = minOf(startIdx + beta, level.offset + level.capacity, keys.size)

                for (i in startIdx until bound) {
                    probes++
                    val k = keys[i]
                    if (k === ABSENT || k == key) {
                        done = true
                        break
                    }
                }
                if (done) break
            }
            result.add(probes)
        }
        return result
>>>>>>> theirs
    }
}
