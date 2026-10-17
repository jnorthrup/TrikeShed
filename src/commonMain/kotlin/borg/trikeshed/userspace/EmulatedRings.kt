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

/** The block emulated staging is sized in: one page. */
const val EMULATED_BLOCK_BYTES: Int = 4096

/**
 * Emulation-owned blocks: the staging of a fixed-buffer transfer that the backend cannot address in place. A new array
 * is a memzero on every runtime (the JVM, JS, Wasm and Kotlin/Native all clear one) that the transfer then overwrites,
 * so blocks are recycled instead, by power-of-two size from one [EMULATED_BLOCK_BYTES] page up, last released first
 * (the block most likely still in cache), and never zeroed on release or reuse.
 *
 * No reused byte leaves a block. A write fills [0, len) from the caller's memory before the backend reads it; a read's
 * block goes back to the caller only up to its completion, so the one block a short read leaves partially written keeps
 * its stale remainder inside the emulation. Caller windows are never zeroed either: past the completion they keep what
 * the caller left there, as the kernel ring leaves them (UringFileConformance). One owner thread; no locks; at most
 * [hold] blocks of a size are kept.
 */
class EmulatedBlocks(private val hold: Int = 8) {
    private val shelves = arrayOfNulls<ArrayList<ByteArray>>(31)

    /** Blocks kept for reuse. */
    val held: Int get() = shelves.sumOf { it?.size ?: 0 }

    /** A block of at least [length] bytes, as its last owner left it. */
    fun acquire(length: Int): ByteArray {
        require(length >= 0)
        if (length > 1 shl 30) return ByteArray(length)
        val shelf = 32 - (maxOf(length, EMULATED_BLOCK_BYTES) - 1).countLeadingZeroBits()
        val kept = shelves[shelf]
        return if (kept.isNullOrEmpty()) ByteArray(1 shl shelf) else kept.removeAt(kept.size - 1)
    }

    /** Takes [block] back uncleared; one [acquire] did not size, or one past [hold], is left to the collector. */
    fun release(block: ByteArray) {
        val size = block.size
        if (size < EMULATED_BLOCK_BYTES || size and (size - 1) != 0) return
        val shelf = size.countTrailingZeroBits()
        val kept = shelves[shelf] ?: ArrayList<ByteArray>().also { shelves[shelf] = it }
        if (kept.size < hold) kept += block
    }
}
