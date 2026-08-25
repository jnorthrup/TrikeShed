package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import kotlin.concurrent.Volatile
import kotlin.random.Random

/**
 * HijackBeliefBag — the attention store as an unsorted priority table with
 * stochastic replacement over a FUNNEL probe geometry. Two lineages compose:
 *
 *  - **narchy HijackBag** (automenta/narchy, jcog.pri.bag.impl.HijackBag):
 *    prioritized cells COMPETE on insert — match→merge, else the probe
 *    window's weakest is the victim; the roulette `rnd < new/(new+old)` decides
 *    the hijack; victim noise buys churn; **insertion can fail** (AIKR:
 *    bounded attention, not guaranteed admission).
 *
 *  - **Krapivin funnel hashing** (arXiv:2501.02305, same geometry as the
 *    in-tree FunnelHashIndex): the table splits into geometrically shrinking
 *    LEVELS; a key probes one β-cell bucket per level, top level first. An
 *    attention bag runs at full load BY DESIGN — exactly the regime where
 *    funnel probing dominates linear reprobes. **Primacy dominates**: the wide
 *    early levels hold the winners and are probed first; roulette losers
 *    cascade DOWN the funnel into smaller, more contested levels; rejection
 *    exists only at the bottom.
 *
 * Together: attention hierarchy as memory layout. A belief's funnel depth IS
 * its standing; sampling weights the early levels (primacy-weighted recall).
 *
 * Concurrency: the CCEK intake channel is the single writer, so no CAS
 * treadmill; readers cross [stamp] (volatile) before touching [slots].
 */
class HijackBeliefBag(
    val capacity: Int,
    /** Cells per level-bucket (the local competition window). */
    val beta: Int = 4,
    private val rng: Random = Random(0x5EED),
) {
    class Slot(
        val angular: Long,
        val budget: BudgetCoord,
        val signal: SemanticSignal,
    ) {
        /** score = truth expectation × priority — the cell's fighting weight. */
        val pri: Float = Nal.truthOf(signal.evidence).expectation() * budget.pf
    }

    sealed class Put {
        /** Landed in a free cell, or merged with its existing cell. */
        data class Placed(val slot: Slot, val merged: Boolean, val level: Int) : Put()

        /** Landed by displacing a weaker cell; the victim FELL OUT of every deeper level too — spill it. */
        data class Hijacked(val slot: Slot, val victim: Slot, val level: Int) : Put()

        /** Lost the roulette at every level of the funnel. Spill the incoming. */
        data class Rejected(val incoming: Slot) : Put()
    }

    // ── funnel geometry: level sizes halve until they reach one bucket ──
    private val slots: Array<Slot?>
    private val levelOffset: IntArray
    private val levelBuckets: IntArray   // bucket count per level

    // ── the vector plane: flat primitive mirrors of every cell, updated at
    // write time — a frontier addition is sweep-visible the instant it lands
    // (no reindex, no dropout lag). commonMain vectorization = autovec only:
    // the sweeps below are plain loops over primitive arrays, boxing-free.
    private lateinit var angularVec: LongArray
    private lateinit var priVec: FloatArray    // -1 = empty cell sentinel
    private lateinit var freqVec: FloatArray   // NARS frequency of the cell's evidence

    init {
        val total = maxOf(capacity, beta)
        val offsets = ArrayList<Int>()
        val buckets = ArrayList<Int>()
        var remaining = total
        var offset = 0
        var levelCells = maxOf(beta, ((total + 1) / 2 / beta) * beta)
        while (remaining >= beta) {
            levelCells = levelCells.coerceAtMost((remaining / beta) * beta).coerceAtLeast(beta)
            offsets.add(offset)
            buckets.add(levelCells / beta)
            offset += levelCells
            remaining -= levelCells
            levelCells = maxOf(beta, (levelCells / 2 / beta) * beta)
        }
        slots = arrayOfNulls(offset)
        levelOffset = offsets.toIntArray()
        levelBuckets = buckets.toIntArray()
        angularVec = LongArray(offset)
        priVec = FloatArray(offset) { -1f }
        freqVec = FloatArray(offset)
    }

    private fun setSlot(i: Int, v: Slot?) {
        slots[i] = v
        if (v == null) {
            angularVec[i] = 0L; priVec[i] = -1f; freqVec[i] = 0f
        } else {
            angularVec[i] = v.angular
            priVec[i] = v.pri
            freqVec[i] = Nal.truthOf(v.signal.evidence).frequency
        }
    }

    val levels: Int get() = levelOffset.size
    val space: Int get() = slots.size

    @Volatile
    private var stamp: Long = 0L

    @Volatile
    private var sizeV: Int = 0

    val size: Int get() = readFence { sizeV }

    private inline fun <T> readFence(body: () -> T): T {
        @Suppress("UNUSED_VARIABLE") val s = stamp // volatile read: happens-before edge
        return body()
    }

    private fun publish() {
        stamp++
    }

    /** Per-level bucket start for a key: an independent mix per level. */
    private fun bucketStart(angular: Long, level: Int): Int {
        var h = (angular + level * -0x7ee3623a03d3c383L) * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        val b = levelBuckets[level]
        val m = (h % b).toInt()
        return levelOffset[level] + (if (m < 0) m + b else m) * beta
    }

    fun get(angular: Long): Slot? = readFence {
        for (level in 0 until levels) {
            val start = bucketStart(angular, level)
            for (i in start until start + beta) {
                val v = slots[i]
                if (v != null && v.angular == angular) return@readFence v
            }
        }
        null
    }

    fun remove(angular: Long): Slot? {
        for (level in 0 until levels) {
            val start = bucketStart(angular, level)
            for (i in start until start + beta) {
                val v = slots[i]
                if (v != null && v.angular == angular) {
                    setSlot(i, null)
                    sizeV--
                    publish()
                    return v
                }
            }
        }
        return null
    }

    /**
     * Funnel-hijack PUT: per level, match→[merge]; free cell→place (primacy:
     * earliest level wins); else roulette against the bucket's weakest — win
     * places here and the victim cascades one level deeper (then spills);
     * lose descends. Rejection is the bottom of the funnel.
     */
    fun put(incoming: Slot, merge: (Slot, Slot) -> Slot): Put {
        for (level in 0 until levels) {
            val start = bucketStart(incoming.angular, level)
            var freeIndex = -1
            var weakestIndex = -1
            var weakestPri = Float.POSITIVE_INFINITY

            for (i in start until start + beta) {
                val v = slots[i]
                when {
                    v == null -> if (freeIndex < 0) freeIndex = i
                    v.angular == incoming.angular -> {
                        val next = merge(v, incoming)
                        setSlot(i, next)
                        publish()
                        return Put.Placed(next, merged = true, level = level)
                    }
                    else -> {
                        // victim noise (narchy): slight underestimate buys churn
                        val noisy = v.pri * (1f - rng.nextFloat() * (VICTIM_NOISE / (beta * beta)))
                        if (noisy < weakestPri) { weakestPri = noisy; weakestIndex = i }
                    }
                }
            }
            if (freeIndex >= 0) {
                setSlot(freeIndex, incoming)
                sizeV++
                publish()
                return Put.Placed(incoming, merged = false, level = level)
            }
            val victim = slots[weakestIndex]!!
            if (hijackFair(incoming.pri, victim.pri)) {
                setSlot(weakestIndex, incoming)
                publish()
                // the victim cascades one chance down the funnel (free cells only)
                if (!cascade(victim, fromLevel = level + 1)) {
                    return Put.Hijacked(incoming, victim = victim, level = level)
                }
                return Put.Placed(incoming, merged = false, level = level)
            }
            // roulette lost: descend the funnel
        }
        return Put.Rejected(incoming)
    }

    /** A displaced slot falls down the funnel into the first free cell below; no further violence. */
    private fun cascade(victim: Slot, fromLevel: Int): Boolean {
        for (level in fromLevel until levels) {
            val start = bucketStart(victim.angular, level)
            for (i in start until start + beta) {
                if (slots[i] == null) {
                    setSlot(i, victim)
                    publish()
                    return true
                }
            }
        }
        return false
    }

    /** Roulette fair (narchy hijackFair): `rnd < new/(new+old)`; zero-vs-zero is a coin flip. */
    private fun hijackFair(newPri: Float, oldPri: Float): Boolean {
        val total = newPri + oldPri
        if (total <= 0f) return rng.nextBoolean()
        return rng.nextFloat() < newPri / total
    }

    /** In-place transform of every live cell (DecayTick / narchy updateEach). Null evicts. */
    fun updateEach(f: (Slot) -> Slot?): List<Slot> {
        val evicted = ArrayList<Slot>()
        for (i in slots.indices) {
            val v = slots[i] ?: continue
            val next = f(v)
            if (next == null) {
                setSlot(i, null)
                sizeV--
                evicted.add(v)
            } else if (next !== v) {
                setSlot(i, next)
            }
        }
        publish()
        return evicted
    }

    /**
     * Primacy-weighted stochastic sample: geometric level choice (level 0 with
     * probability ~1/2, then 1/4, …) then roulette within a random bucket.
     * Attention IS the address: early levels dominate recall.
     */
    fun sample(k: Int): List<Slot> = readFence {
        if (sizeV == 0 || k <= 0) return@readFence emptyList()
        val out = ArrayList<Slot>(k)
        var attempts = 0
        val maxAttempts = k * 8
        while (out.size < k && attempts++ < maxAttempts) {
            var level = 0
            while (level < levels - 1 && rng.nextBoolean()) level++
            level = levels - 1 - level // deepest is rarest; invert so level 0 is most probable
            val start = levelOffset[level] + rng.nextInt(levelBuckets[level]) * beta
            var total = 0f
            for (i in start until start + beta) slots[i]?.let { total += it.pri + EPSILON }
            if (total <= 0f) continue
            var spin = rng.nextFloat() * total
            var chosen: Slot? = null
            for (i in start until start + beta) {
                val v = slots[i] ?: continue
                spin -= v.pri + EPSILON
                if (spin <= 0f) { chosen = v; break }
                chosen = v
            }
            val c = chosen ?: continue
            if (out.none { it.angular == c.angular }) out.add(c)
        }
        out
    }

    /** Full live view (rare paths: render ranking, snapshot, hamming recall). */
    fun forEach(f: (Slot) -> Unit): Unit = readFence {
        for (v in slots) if (v != null) f(v)
    }

    /**
     * Deterministic force-place for WAL replay: own/free cell at the earliest
     * possible level, else the weakest cell in the BOTTOM level's bucket —
     * no roulette. Returns any displaced slot.
     */
    fun place(slot: Slot): Slot? {
        for (level in 0 until levels) {
            val start = bucketStart(slot.angular, level)
            for (i in start until start + beta) {
                val v = slots[i]
                if (v == null) { setSlot(i, slot); sizeV++; publish(); return null }
                if (v.angular == slot.angular) { setSlot(i, slot); publish(); return null }
            }
        }
        val level = levels - 1
        val start = bucketStart(slot.angular, level)
        var weakestIndex = start
        var weakestPri = Float.POSITIVE_INFINITY
        for (i in start until start + beta) {
            val p = slots[i]!!.pri
            if (p < weakestPri) { weakestPri = p; weakestIndex = i }
        }
        val victim = slots[weakestIndex]
        setSlot(weakestIndex, slot)
        publish()
        return victim
    }

    /**
     * The resonance of a solver proposal against EVERY action potential in the
     * bag, one flat sweep: activation = similarity² × pri, similarity from
     * hamming over the coordinate plane. Peaks split by evidence polarity —
     * [Resonance.synonyms] = positive-frequency neighbors (the support front),
     * [Resonance.antonyms] = negative-frequency neighbors (the refutation
     * front). Frontier additions participate immediately (vector plane is
     * write-time mirrored). Hot loop: primitives only, autovec-friendly.
     */
    fun resonate(centroid: Long, k: Int = 8): Resonance = readFence {
        val n = slots.size
        val act = FloatArray(n)
        val av = angularVec; val pv = priVec
        for (i in 0 until n) {
            val h = (av[i] xor centroid).countOneBits()
            val s = 1f - h * 0.015625f // /64
            val s2 = s * s
            // s⁴: PEAK-seeking, not soft blending — shared relation/taxonomy bits keep
            // unrelated terms hamming-close; quartic contrast separates true neighbors.
            act[i] = s2 * s2 * (pv[i] + 0.01f) // pv=-1 sentinel drives empties negative
        }
        val syn = topK(act, k) { i -> priVec[i] >= 0f && freqVec[i] >= 0.5f }
        val ant = topK(act, k) { i -> priVec[i] >= 0f && freqVec[i] < 0.5f }
        Resonance(collect(syn), collect(ant))
    }

    private fun collect(indices: IntArray): List<Slot> {
        val out = ArrayList<Slot>(indices.size)
        for (i in indices) slots[i]?.let(out::add)
        return out
    }

    class Resonance(val synonyms: List<Slot>, val antonyms: List<Slot>)

    private inline fun topK(act: FloatArray, k: Int, admit: (Int) -> Boolean): IntArray {
        val idx = IntArray(k) { -1 }
        val best = FloatArray(k) { Float.NEGATIVE_INFINITY }
        for (i in act.indices) {
            if (!admit(i)) continue
            val a = act[i]
            if (a <= best[k - 1]) continue
            var j = k - 1
            while (j > 0 && best[j - 1] < a) {
                best[j] = best[j - 1]; idx[j] = idx[j - 1]; j--
            }
            best[j] = a; idx[j] = i
        }
        return idx.filterTo(ArrayList()) { it >= 0 }.toIntArray()
    }

    /** Funnel depth of a live belief, or -1 — the belief's standing, physically. */
    fun levelOf(angular: Long): Int = readFence {
        for (level in 0 until levels) {
            val start = bucketStart(angular, level)
            for (i in start until start + beta) {
                val v = slots[i]
                if (v != null && v.angular == angular) return@readFence level
            }
        }
        -1
    }

    // ── strata exposure: the funnel levels are attention primacy made
    // physical — level 0 holds the roulette winners, deeper = more contested.
    // Consumers accumulate per-level moments (covariance divergence, the
    // frontier-drift detector) with ZERO storage held here: ranges index the
    // existing [slots], counts are computed per call. ──

    /**
     * Slot-index range of one funnel level: `offset until offset + buckets*beta`.
     * The levels partition [0, [space]) — disjoint, contiguous, exhaustive —
     * so a range IS the stratum. Intended consumer: per-level covariance
     * divergence over the cells' coordinates (frontier drift), no new storage.
     */
    fun levelRange(level: Int): IntRange =
        levelOffset[level] until levelOffset[level] + levelBuckets[level] * beta

    /**
     * Live cells within [range], crossing the volatile read fence the way
     * [forEach] does (readers see writes published up to the fenced [stamp]).
     * Paired with [levelRange] this streams one stratum's population; moment
     * accumulation stays in the caller — the bag stores nothing for it.
     */
    fun forEachIn(range: IntRange, f: (Slot) -> Unit): Unit = readFence {
        for (i in range) slots[i]?.let(f)
    }

    /**
     * Live-cell count per level, one pass over [slots]. Index 0 = widest,
     * most-primal stratum. These are the denominators for per-level moments;
     * invariant: `levelSizes.sum() == size` under the same fence.
     */
    val levelSizes: IntArray
        get() = readFence {
            val out = IntArray(levels)
            var level = 0
            for (i in slots.indices) {
                while (level + 1 < levels && i >= levelOffset[level + 1]) level++
                if (slots[i] != null) out[level]++
            }
            out
        }

    companion object {
        const val VICTIM_NOISE = 0.15f
        private const val EPSILON = 1e-6f
    }
}
