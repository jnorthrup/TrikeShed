package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries

/**
 * Lossless evidence store as columns over an open-addressed slot table. Slot s holds a packed
 * `keys[s]` (e.g. packInts(class id, term id)), `evidence[s]` ([EvidenceCoord.packed]) and the exact
 * Roaring set of source ids it was drawn from. Nothing is evicted; attention is a separate concern.
 * Revision is exact over the basis: a source already in it contributes nothing, so the result is
 * independent of arrival order and no source counts twice.
 */
class EvidenceLedger(initialCapacity: Int = 64) {
    private var cap = ((initialCapacity * 2).coerceAtLeast(16) - 1).takeHighestOneBit() shl 1
    private var keys = LongArray(cap)
    private var evidence = LongArray(cap)
    private var basis = arrayOfNulls<RoaringSeries>(cap)
    var size = 0
        private set

    private fun home(key: Long, mask: Int): Int = ((key * -0x61c8864680b583ebL) ushr 33).toInt() and mask

    /** Slot of [key], or -1. */
    fun slot(key: Long): Int {
        val mask = cap - 1
        var s = home(key, mask)
        while (basis[s] != null) {
            if (keys[s] == key) return s
            s = (s + 1) and mask
        }
        return -1
    }

    fun key(slot: Int): Long = keys[slot]
    fun evidence(slot: Int): EvidenceCoord = EvidenceCoord(evidence[slot])
    fun basis(slot: Int): RoaringSeries = basis[slot]!!

    /** Add [evidence] observed from [source] under [key]; returns the slot. A source already in the basis is ignored. */
    fun observe(key: Long, source: Int, evidence: EvidenceCoord): Int {
        if ((size + 1) * 2 > cap) grow()
        val mask = cap - 1
        var s = home(key, mask)
        while (true) {
            val b = basis[s]
            if (b == null) {
                keys[s] = key; this.evidence[s] = evidence.packed; basis[s] = RoaringSeries.singleton(source); size++
                return s
            }
            if (keys[s] == key) {
                if (b.contains(source)) return s
                this.evidence[s] = revise(EvidenceCoord(this.evidence[s]), evidence).packed
                basis[s] = b or RoaringSeries.singleton(source)
                return s
            }
            s = (s + 1) and mask
        }
    }

    /** Occupied slots whose NAL confidence reaches [threshold] and frequency exceeds [minFrequency], in slot order. */
    fun eternal(threshold: Float, minFrequency: Float = -1f): IntArray {
        var n = 0
        val out = IntArray(size)
        for (s in 0 until cap) if (basis[s] != null) {
            val t = Nal.truthOf(EvidenceCoord(evidence[s]))
            if (t.confidence >= threshold && t.frequency > minFrequency) out[n++] = s
        }
        return out.copyOf(n)
    }

    private fun grow() {
        val oldKeys = keys; val oldEv = evidence; val oldBasis = basis
        cap = cap shl 1
        keys = LongArray(cap); evidence = LongArray(cap); basis = arrayOfNulls(cap)
        val mask = cap - 1
        for (i in oldKeys.indices) {
            val b = oldBasis[i] ?: continue
            var s = home(oldKeys[i], mask)
            while (basis[s] != null) s = (s + 1) and mask
            keys[s] = oldKeys[i]; evidence[s] = oldEv[i]; basis[s] = b
        }
    }
}
