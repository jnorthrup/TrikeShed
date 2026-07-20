package borg.trikeshed.collections.associative

import borg.trikeshed.job.Sha256Pure

/**
 * FunnelHashMap — mutable open-addressing hash map with tiered funnel geometry.
 *
 * Based on "Optimal Bounds for Open Addressing Without Reordering"
 * (Farach-Colton, Krapivin, Kuszmaul; arXiv:2501.02305).
 *
 * Structure:
 *   Level ℓ has capacity = baseCap / 2^ℓ, probeBound = 2^ℓ.
 *   Insert greeds: try level 0 (bound 1), then level 1 (bound 2), …
 *   Final level has unbounded probes (Int.MAX_VALUE) to guarantee termination.
 *
 * Invariants:
 *   - Total live entries < baseCap * (1 - δ)  (δ = slack, default 0.25)
 *   - Resize when live >= baseCap * (1 - δ)
 *   - Tombstones per level; rebuild when tombstone ratio > 0.25
 *
 * Complexity (from paper):
 *   - Expected probes per get/put: O(log²(1/δ)) worst-case
 *   - Amortized O(1) expected probes
 */
class FunnelHashMap<K : Any, V>(
    initialCapacity: Int = 16,
    private val slack: Double = 0.25,        // δ — fraction of baseCap kept free
    private val seed: Long = System.nanoTime()
) {

    private data class Level(
        val capacity: Int,
        val probeBound: Int,
        val keySlots: Array<Any?>,
        val valueSlots: Array<Any?>,
        var tombstones: Int = 0,
    ) {
        fun hasFreeSlot(): Boolean = keySlots.any { it === ABSENT }
    }

    private companion object {
        private val DELETED = Any()
        private val ABSENT  = Any()
        private const val MIN_CAPACITY = 16
        private const val MAX_LEVELS = 20          // supports baseCap up to 2^20
        private const val REBUILD_TOMBSTONE_RATIO = 0.25
    }

    // ── state ──────────────────────────────────────────────────────────────
    private var baseCapacity: Int = nextPowerOfTwo(initialCapacity.coerceAtLeast(MIN_CAPACITY))
    private var levels: List<Level> = buildLevels(baseCapacity, slack)
    private var size: Int = 0
    private var totalTombstones: Int = 0

    // ── public API ─────────────────────────────────────────────────────────
    val count: Int get() = size
    val capacity: Int get() = baseCapacity
    val loadFactor: Double get() = size.toDouble() / baseCapacity

    operator fun get(key: K): V? {
        for (level in levels) {
            val levelSeed = if (level.probeBound == Int.MAX_VALUE) seed + 0xdeadbeefL else seed + level.probeBound.toLong()
            val h = hash64(key, levelSeed)
            val bound = if (level.probeBound == Int.MAX_VALUE) level.capacity else level.probeBound
            var i = 0
            while (i < bound) {
                val slot = ((h + i) and (level.capacity.toLong() - 1)).toInt()
                val k = level.keySlots[slot]
                if (k === ABSENT) return null
                if (k !== DELETED && k == key) return level.valueSlots[slot] as V
                i++
            }
        }
        return null
    }

    operator fun set(key: K, value: V): V? {
        if (size + totalTombstones >= (baseCapacity * (1 - slack)).toInt()) resize()
        return putInternal(key, value)
    }

    private fun putInternal(key: K, value: V): V? {
        for (level in levels) {
            val levelSeed = if (level.probeBound == Int.MAX_VALUE) seed + 0xdeadbeefL else seed + level.probeBound.toLong()
            val h = hash64(key, levelSeed)
            val bound = if (level.probeBound == Int.MAX_VALUE) level.capacity else level.probeBound
            var firstTomb = -1
            var i = 0
            while (i < bound) {
                val slot = ((h + i) and (level.capacity.toLong() - 1)).toInt()
                val k = level.keySlots[slot]
                when {
                    k === ABSENT -> {
                        val ins = if (firstTomb >= 0) firstTomb else slot
                        level.keySlots[ins] = key
                        level.valueSlots[ins] = value
                        if (firstTomb >= 0) {
                            level.tombstones--
                            totalTombstones--
                        }
                        size++
                        return null
                    }
                    k === DELETED -> if (firstTomb < 0) firstTomb = slot
                    else if (k == key) {
                        val old = level.valueSlots[slot] as V
                        level.valueSlots[slot] = value
                        return old
                    }
                }
                i++
            }
        }
        // Should never reach here if resize logic is correct
        resize()
        return putInternal(key, value)
    }

    fun remove(key: K): V? {
        for (level in levels) {
            val levelSeed = if (level.probeBound == Int.MAX_VALUE) seed + 0xdeadbeefL else seed + level.probeBound.toLong()
            val h = hash64(key, levelSeed)
            val bound = if (level.probeBound == Int.MAX_VALUE) level.capacity else level.probeBound
            var i = 0
            while (i < bound) {
                val slot = ((h + i) and (level.capacity.toLong() - 1)).toInt()
                val k = level.keySlots[slot]
                if (k === ABSENT) return null
                if (k !== DELETED && k == key) {
                    val old = level.valueSlots[slot] as V
                    level.keySlots[slot] = DELETED
                    level.valueSlots[slot] = ABSENT
                    level.tombstones++
                    totalTombstones++
                    size--
                    maybeRebuild()
                    return old
                }
                i++
            }
        }
        return null
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun maybeRebuild() {
        if (totalTombstones.toDouble() / (size + totalTombstones) > REBUILD_TOMBSTONE_RATIO) {
            rebuild()
        }
    }

    private fun rebuild() {
        val entries = mutableListOf<Pair<K, V>>()
        for (level in levels) {
            for (i in 0 until level.capacity) {
                val k = level.keySlots[i]
                if (k !== ABSENT && k !== DELETED) {
                    entries.add(k as K to level.valueSlots[i] as V)
                }
            }
        }
        baseCapacity = nextPowerOfTwo((entries.size / (1 - slack)).toInt().coerceAtLeast(MIN_CAPACITY))
        levels = buildLevels(baseCapacity, slack)
        size = 0
        totalTombstones = 0
        for ((k, v) in entries) putInternal(k, v)
    }

    private fun resize() {
        val entries = mutableListOf<Pair<K, V>>()
        for (level in levels) {
            for (i in 0 until level.capacity) {
                val k = level.keySlots[i]
                if (k !== ABSENT && k !== DELETED) {
                    entries.add(k as K to level.valueSlots[i] as V)
                }
            }
        }
        baseCapacity = nextPowerOfTwo(baseCapacity * 2)
        levels = buildLevels(baseCapacity, slack)
        size = 0
        totalTombstones = 0
        for ((k, v) in entries) putInternal(k, v)
    }

    private fun buildLevels(cap: Int, slack: Double): List<Level> {
        val built = mutableListOf<Level>()
        var capLevel = cap
        var probeBound = 1
        while (capLevel >= MIN_CAPACITY && built.size < MAX_LEVELS - 1) {
            built.add(Level(capLevel, probeBound, Array(capLevel) { ABSENT }, Array(capLevel) { ABSENT }))
            capLevel = capLevel shr 1
            probeBound = probeBound shl 1
        }
        // Final level: unbounded probes
        val finalCap = capLevel.coerceAtLeast(MIN_CAPACITY)
        built.add(Level(finalCap, Int.MAX_VALUE, Array(finalCap) { ABSENT }, Array(finalCap) { ABSENT }))
        return built
    }

    private fun hash64(key: K, levelSeed: Long): Long {
        val bytes = Sha256Pure.digest(key.toString().encodeToByteArray())
        var z = levelSeed + bytes.size.toLong()
        for (b in bytes) {
            z = (z + (b.toLong() and 0xFFL)) * -0x61c8864680b583ebL
        }
        z = (z xor (z shr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z shr 27)) * -0x6b2fb644ecced115L
        z = z xor (z shr 31)
        return z
    }

    private fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }
}