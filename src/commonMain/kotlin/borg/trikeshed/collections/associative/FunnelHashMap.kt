package borg.trikeshed.collections.associative

/**
 * FunnelHashMap — mutable multi-level open addressing (cousin of Krapivin funnel).
 *
 * Geometry (NOT the paper's β-bucket layout):
 *   Level ℓ has capacity = baseCap / 2^ℓ, probeBound = 2^ℓ.
 *   Insert greeds: try level 0 (bound 1), then level 1 (bound 2), …
 *   Final level has unbounded probes (Int.MAX_VALUE) to guarantee termination.
 *
 * Invariants:
 *   - Total live entries < baseCap * (1 - δ)  (δ = slack, default 0.25)
 *   - Resize when live >= baseCap * (1 - δ)
 *   - Tombstones per level; rebuild when tombstone ratio > 0.25
 *
 * Honesty vs Farach-Colton/Krapivin/Kuszmaul (arXiv:2501.02305):
 *   - Paper funnel uses per-level buckets of size β ~ log(1/δ). This type expands
 *     probeBound across the whole level — same spirit, different algorithm.
 *   - O(log² 1/δ) is not claimed or measured here.
 *   - Paper elastic hashing is not this type.
 *   - Deletes/tombstones are outside the paper's insert-focused setting.
 *
 * Prefer [borg.trikeshed.collections.FunnelHashMap] for the β-bucket geometry
 * (production: Stringpool). Prefer [FunnelHashIndex] for frozen membership.
 */
class FunnelHashMap<K : Any, V>(
    initialCapacity: Int = 16,
    private val slack: Double = 0.25,        // δ — fraction of baseCap kept free
    private val seed: Long = 0x9E3779B97F4A7C15UL.toLong(),
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
            val h = mix64(key, levelSeed)
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
            val h = mix64(key, levelSeed)
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
            val h = mix64(key, levelSeed)
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

    // ⚡ Bolt Optimization: Replaced expensive Sha256Pure cryptographic hash and string allocations
    // with a fast, deterministic SplitMix64-style bitwise mix based on key.hashCode() for
    // massive throughput improvements during probing.
    private fun mix64(key: K, levelSeed: Long): Long {
        var z = key.hashCode().toLong() + levelSeed
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecced115L
        z = z xor (z ushr 31)
        return z
    }
    }

    private fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }
}