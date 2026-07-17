package borg.trikeshed.collections.associative

import borg.trikeshed.job.Sha256Pure

/**
 * FunnelHashIndex — tiered linear probing with expanding probe bounds.
 *
 * Key properties:
 * - Append-only immutable segment (no delete/retract)
 * - Negative-query-heavy workloads (dedup, membership, frozen schema)
 * - Multi-level funnel: each level doubles probe bound, halves capacity
 * - Deterministic replay: probe entropy derived from canonical facet bytes + committed seed
 *
 * Usage:
 *   val idx = FunnelHashIndex.build(listOf("a", "b", "c"), seed)
 *   val pos = idx.get("b")  // returns Some(1) or null
 */
class FunnelHashIndex<K : Any> internal constructor(
    private val keys: List<K>,
    private val seed: Long,
    private val levels: List<Level>,
) {

    data class Level(
        val capacity: Int,
        val probeBound: Int,
        val keySlots: Array<Any?>,
        val valueSlots: IntArray,
    )

    companion object {
        private const val MIN_CAPACITY = 16

        /** Build a FunnelHashIndex from a list of keys. */
        fun <K : Any> build(keys: List<K>, seed: Long): FunnelHashIndex<K> {
            if (keys.isEmpty()) return FunnelHashIndex(keys, seed, emptyList())

            var baseCap = calculateBaseCapacity(keys.size)
            val builtLevels = mutableListOf<Level>()
            val placed = BooleanArray(keys.size) { false }
            var placedCount = 0
            var cap = baseCap
            var probeBound = 1

            // Build funnel levels: capacity halves, probe bound doubles each level
            while (cap >= MIN_CAPACITY && placedCount < keys.size) {
                val level = Level(
                    capacity = cap,
                    probeBound = probeBound,
                    keySlots = Array<Any?>(cap) { null },
                    valueSlots = IntArray(cap) { -1 },
                )

                // Try to place each UNPLACED key in this level
                for (j in keys.indices) {
                    if (placed[j]) continue
                    val key = keys[j]
                    val h = hash64(key, seed + probeBound.toLong())
                    var placedThis = false
                    var i = 0
                    while (i < probeBound && i < cap) {
                        val slot = ((h + i) and (cap.toLong() - 1)).toInt()
                        if (level.keySlots[slot] == null) {
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
                    }
                }

                builtLevels.add(level)
                cap = cap shr 1
                probeBound = probeBound shl 1
            }

            // Final level for remaining keys (unlimited probes)
            if (placedCount < keys.size) {
                val remaining = keys.size - placedCount
                var cap = calculateBaseCapacity(remaining * 2).coerceAtLeast(MIN_CAPACITY)
                val level = Level(
                    capacity = cap,
                    probeBound = Int.MAX_VALUE,
                    keySlots = Array<Any?>(cap) { null },
                    valueSlots = IntArray(cap) { -1 },
                )
                for (j in keys.indices) {
                    if (placed[j]) continue
                    val key = keys[j]
                    val h = hash64(key, seed + 0xdeadbeefL)
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

            return FunnelHashIndex(keys, seed, builtLevels)
        }

        private fun calculateBaseCapacity(n: Int): Int {
            var cap = MIN_CAPACITY
            while (cap < n) cap = cap shl 1
            return cap
        }

        private fun hash64(key: Any, seed: Long): Long {
            val bytes = Sha256Pure.digest(key.toString().encodeToByteArray())
            var z = seed + bytes.size.toLong()
            for (b in bytes) {
                z = (z + (b.toLong() and 0xFFL)) * -0x61c8864680b583ebL
            }
            z = (z xor (z shr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z shr 27)) * -0x6b2fb644ecced115L
            z = z xor (z shr 31)
            return z
        }
    }

    /** Returns the insertion index of the key, or null if not found. */
    fun get(key: K): Int? {
        for (level in levels) {
            val levelSeed = if (level.probeBound == Int.MAX_VALUE) seed + 0xdeadbeefL else seed + level.probeBound.toLong()
            val h = hash64(key, levelSeed)
            val bound = if (level.probeBound == Int.MAX_VALUE) level.capacity else level.probeBound
            var i = 0
            while (i < bound) {
                val slot = ((h + i) and (level.capacity.toLong() - 1)).toInt()
                val storedKey = level.keySlots[slot]
                if (storedKey == null) break
                if (storedKey == key) return level.valueSlots[slot]
                i++
            }
        }
        return null
    }

    /** Returns true if the key is present. */
    fun contains(key: K): Boolean = get(key) != null

    /** Returns the total capacity across all levels. */
    fun totalCapacity(): Int = levels.sumOf { it.capacity }

    /** Returns the number of keys stored. */
    fun size(): Int = keys.size

    /** Returns probe counts for all keys (for statistical analysis). */
    fun probeDistribution(): List<Int> {
        return keys.map { key ->
            var totalProbes = 0
            for (level in levels) {
                val levelSeed = if (level.probeBound == Int.MAX_VALUE) seed + 0xdeadbeefL else seed + level.probeBound.toLong()
                val h = hash64(key, levelSeed)
                val bound = if (level.probeBound == Int.MAX_VALUE) level.capacity else level.probeBound
                var i = 0
                while (i < bound) {
                    totalProbes++
                    val slot = ((h + i) and (level.capacity.toLong() - 1)).toInt()
                    val storedKey = level.keySlots[slot]
                    if (storedKey == null) break
                    if (storedKey == key) return@map totalProbes
                    i++
                }
            }
            totalProbes
        }
    }

    private fun hash64(key: Any, seed: Long): Long {
        val bytes = Sha256Pure.digest(key.toString().encodeToByteArray())
        var z = seed + bytes.size.toLong()
        for (b in bytes) {
            z = (z + (b.toLong() and 0xFFL)) * -0x61c8864680b583ebL
        }
        z = (z xor (z shr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z shr 27)) * -0x6b2fb644ecced115L
        z = z xor (z shr 31)
        return z
    }
}