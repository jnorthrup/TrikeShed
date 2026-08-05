package borg.trikeshed.collections

import kotlin.math.ln
import kotlin.math.max

/**
 * FunnelHashMap — greedy open-addressing hash map inspired by Krapivin et al. (2025)
 * "Optimal Bounds for Open Addressing Without Reordering".
 *
 * Implements "Funnel Hashing":
 * - Levels A_1, A_2, ..., A_alpha of decreasing size (¾ decay here)
 * - Each level split into buckets of size β = max(8, ⌈4 ln(1/δ)⌉)
 * - Insert/get: hash → one bucket in A_i; linear probe within that bucket; else next level
 * - Final remainder level handles overflows
 *
 * Note: remove/tombstones are extensions outside the original paper's scope.
 * Used by Stringpool.
 */
<<<<<<< HEAD
class FunnelHashMap<K : Any, V>(
    initialCapacity: Int = 32,
    slack: Double = 0.20,
) {
=======
class FunnelHashMap<K : Any, V>(initialCapacity: Int = 32, slack: Double = 0.20) {
>>>>>>> origin/feature-funnel-slack-beta-15875415286018948826
    private var size = 0
    private var capacity = 0

    @Suppress("UNCHECKED_CAST")
    private var keys: Array<Any?> = emptyArray()
    @Suppress("UNCHECKED_CAST")
    private var values: Array<Any?> = emptyArray()

    private var levels: Array<Level> = emptyArray()

<<<<<<< HEAD
    /** Free-fraction δ used for load target and β sizing. */
    val slack: Double = slack.coerceIn(0.05, 0.50)

    /** Bucket size β derived from slack. */
    var beta: Int = betaFor(this.slack)
        private set
=======
    private val actualSlack = slack.coerceIn(0.05, 0.50)
    private val beta = maxOf(8, kotlin.math.ceil(4 * kotlin.math.ln(1.0 / actualSlack)).toInt())
>>>>>>> origin/feature-funnel-slack-beta-15875415286018948826

    companion object {
        private val ABSENT = Any()
        private val DELETED = Any()

        private const val DECAY_NUM = 3
        private const val DECAY_DEN = 4 // A_{i+1} = 3/4 A_i
        private const val MIN_BETA = 8
        private const val BETA_LN_SCALE = 4.0

        fun betaFor(slack: Double): Int {
            val d = slack.coerceIn(0.05, 0.50)
            val raw = BETA_LN_SCALE * ln(1.0 / d)
            return max(MIN_BETA, raw.toInt())
        }
    }

    private data class Level(
        val offset: Int,
        val capacity: Int,
        val buckets: Int,
    )

    init {
        resize(maxOf(initialCapacity, 16))
    }

    val count: Int get() = size
    val totalCapacity: Int get() = capacity
    val loadFactor: Double get() = if (capacity == 0) 0.0 else size.toDouble() / capacity
    val levelCount: Int get() = levels.size

    private fun liveCap(): Int = (capacity * (1.0 - slack)).toInt().coerceAtLeast(1)

    private fun resize(newCap: Int) {
        val oldKeys = keys
        val oldValues = values

        capacity = newCap
        keys = Array(capacity) { ABSENT }
        values = Array(capacity) { ABSENT }

        val newLevels = mutableListOf<Level>()
        var remaining = capacity
        var currentLevelCap = capacity
        var offset = 0
        val b = beta

<<<<<<< HEAD
        while (currentLevelCap >= b) {
            val buckets = currentLevelCap / b
            val actualCap = buckets * b
=======
        while (currentLevelCap >= beta) {
            val buckets = currentLevelCap / beta
            val actualCap = buckets * beta
>>>>>>> origin/feature-funnel-slack-beta-15875415286018948826
            newLevels.add(Level(offset, actualCap, buckets))
            offset += actualCap
            remaining -= actualCap
            currentLevelCap = (currentLevelCap * DECAY_NUM) / DECAY_DEN
        }

        if (remaining > 0) {
<<<<<<< HEAD
            newLevels.add(Level(offset, remaining, (remaining + b - 1) / b))
=======
            newLevels.add(Level(offset, remaining, (remaining + beta - 1) / beta))
>>>>>>> origin/feature-funnel-slack-beta-15875415286018948826
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

    private fun hash(key: K, level: Int): Int {
        var h = key.hashCode() xor (level * 0x9E3779B9.toInt())
        h = h xor (h ushr 16)
        h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35.toInt()
        return h xor (h ushr 16)
    }

    fun put(key: K, value: V): V? {
<<<<<<< HEAD
        if (size >= liveCap()) {
=======
        // Target max load factor based on slack
        if (size >= (capacity * (1.0 - actualSlack)).toInt()) {
>>>>>>> origin/feature-funnel-slack-beta-15875415286018948826
            resize(capacity * 2)
        }

        val b = beta

        // Fast path: find existing first or insert into first free
        for (lvl in levels.indices) {
            val level = levels[lvl]
            if (level.buckets == 0) continue
            val h = hash(key, lvl)
            val bucketIdx = (h.toUInt() % level.buckets.toUInt()).toInt()
<<<<<<< HEAD
            val startIdx = level.offset + bucketIdx * b
            val bound = minOf(startIdx + b, level.offset + level.capacity, keys.size)
=======

            // Linear probe within the bucket (or up to beta elements)
            val startIdx = level.offset + bucketIdx * beta
            val bound = minOf(startIdx + beta, level.offset + level.capacity, keys.size)

            var firstTombstone = -1
>>>>>>> origin/feature-funnel-slack-beta-15875415286018948826

            for (i in startIdx until bound) {
                val k = keys[i]
                if (k === ABSENT) {
                    keys[i] = key
                    values[i] = value
                    size++
                    return null
                } else if (k == key) {
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
<<<<<<< HEAD
            val startIdx = level.offset + bucketIdx * b
            val bound = minOf(startIdx + b, level.offset + level.capacity, keys.size)
=======
            val startIdx = level.offset + bucketIdx * beta
            val bound = minOf(startIdx + beta, level.offset + level.capacity, keys.size)
>>>>>>> origin/feature-funnel-slack-beta-15875415286018948826
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
<<<<<<< HEAD
            val startIdx = level.offset + bucketIdx * b
            val bound = minOf(startIdx + b, level.offset + level.capacity, keys.size)
=======
            val startIdx = level.offset + bucketIdx * beta
            val bound = minOf(startIdx + beta, level.offset + level.capacity, keys.size)
>>>>>>> origin/feature-funnel-slack-beta-15875415286018948826
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

        return null
    }

    fun get(key: K): V? {
        val b = beta
        for (lvl in levels.indices) {
            val level = levels[lvl]
            if (level.buckets == 0) continue
            val h = hash(key, lvl)
            val bucketIdx = (h.toUInt() % level.buckets.toUInt()).toInt()
<<<<<<< HEAD
            val startIdx = level.offset + bucketIdx * b
            val bound = minOf(startIdx + b, level.offset + level.capacity, keys.size)
=======

            val startIdx = level.offset + bucketIdx * beta
            val bound = minOf(startIdx + beta, level.offset + level.capacity, keys.size)
>>>>>>> origin/feature-funnel-slack-beta-15875415286018948826

            for (i in startIdx until bound) {
                val k = keys[i]
                if (k === ABSENT) return null
                if (k == key) {
                    @Suppress("UNCHECKED_CAST")
                    return values[i] as V?
                }
            }
        }
        return null
    }

    fun remove(key: K): V? {
        val b = beta
        for (lvl in levels.indices) {
            val level = levels[lvl]
            if (level.buckets == 0) continue
            val h = hash(key, lvl)
            val bucketIdx = (h.toUInt() % level.buckets.toUInt()).toInt()
<<<<<<< HEAD
            val startIdx = level.offset + bucketIdx * b
            val bound = minOf(startIdx + b, level.offset + level.capacity, keys.size)
=======

            val startIdx = level.offset + bucketIdx * beta
            val bound = minOf(startIdx + beta, level.offset + level.capacity, keys.size)
>>>>>>> origin/feature-funnel-slack-beta-15875415286018948826

            for (i in startIdx until bound) {
                val k = keys[i]
                if (k === ABSENT) return null
                if (k == key) {
                    keys[i] = DELETED
                    @Suppress("UNCHECKED_CAST")
                    val old = values[i] as V?
                    values[i] = ABSENT
                    size--
                    return old
                }
            }
        }
        return null
    }

    fun probeDistribution(sample: List<K>): List<Int> {
<<<<<<< HEAD
        val b = beta
        return sample.map { key ->
            var probes = 0
=======
        val result = mutableListOf<Int>()
        for (key in sample) {
            var probes = 0
            var done = false
>>>>>>> origin/feature-funnel-slack-beta-15875415286018948826
            for (lvl in levels.indices) {
                val level = levels[lvl]
                if (level.buckets == 0) continue
                val h = hash(key, lvl)
                val bucketIdx = (h.toUInt() % level.buckets.toUInt()).toInt()
<<<<<<< HEAD
                val startIdx = level.offset + bucketIdx * b
                val bound = minOf(startIdx + b, level.offset + level.capacity, keys.size)
                for (i in startIdx until bound) {
                    probes++
                    val k = keys[i]
                    if (k === ABSENT || k == key) return@map probes
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
>>>>>>> origin/feature-funnel-slack-beta-15875415286018948826
    }
}
