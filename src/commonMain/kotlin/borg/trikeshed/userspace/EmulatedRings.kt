package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.platform.spi.CacheTopology

/**
 * Emulated ring depth. A kernel ring is an exclusive channel and keeps the depth it asks for; an emulated ring
 * services its SQEs in this process, so its depth pays only while the buffers of its in-flight wide operations stay in
 * the data cache: past that, deeper entries hold buffers already evicted. The depth is the largest power of two in
 * 1..[requested] whose [opBytes] operations fit [cacheBytes]; an unknown cache or a zero-byte operation keeps [requested].
 */
fun emulatedRingDepth(requested: Int, opBytes: Int, cacheBytes: Long): Int {
    require(requested > 0)
    if (opBytes <= 0 || cacheBytes <= 0L) return requested
    val fit = (cacheBytes / opBytes).coerceIn(1L, requested.toLong()).toInt()
    return fit.takeHighestOneBit()
}

/** The data cache that bounds an emulated ring's in-flight buffers: L1d, else L2; 0 when the platform cannot tell. */
val CacheTopology.ringResidencyBytes: Long get() = l1DataBytes ?: l2Bytes ?: 0L

/**
 * Released emulated rings, by depth. Side-by-side channels (64k connections) share a few shallow rings instead of each
 * holding a deep pipe: [acquire] hands back the shallowest released ring at least [depth] deep, and opens a new one only
 * when none is. One owner thread; no locks.
 */
class EmulatedRingRecycler<R : Any>(private val depthOf: (R) -> Int, private val open: (Int) -> R) {
    private val released = ArrayList<R>()

    /** Rings held for reuse. */
    val held: Int get() = released.size

    fun acquire(depth: Int): R {
        var best = -1
        var bestDepth = Int.MAX_VALUE
        for (index in released.indices) {
            val d = depthOf(released[index])
            if (d in depth until bestDepth) { best = index; bestDepth = d }
        }
        if (best < 0) return open(depth)
        val ring = released[best]
        released[best] = released[released.size - 1]
        released.removeAt(released.size - 1)
        return ring
    }

    fun release(ring: R) { released += ring }
}

/**
 * A recycled block still holds its previous owner's bytes. A transfer that wrote [written] bytes from [start] into a
 * block ending at [end] leaves [start + written, end) stale: zero exactly that tail, never the whole block, and nothing
 * when the transfer filled it.
 */
fun ByteArray.zeroTail(start: Int, written: Int, end: Int) {
    val from = start + maxOf(written, 0)
    if (from < end) fill(0, from, end)
}
