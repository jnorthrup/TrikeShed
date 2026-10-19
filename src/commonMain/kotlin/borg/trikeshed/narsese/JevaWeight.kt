package borg.trikeshed.narsese

import borg.trikeshed.lib.*

/** Optional row-major bulk access on a tensor's existing scalar oracle; storage stays with its owner. */
interface JevaWeight : MetaSeries<Tensor, Float> {
    val size: Int

    /** Copy a contiguous span into caller-owned storage. Failure may leave a partial destination. */
    fun read(offset: Int, dst: FloatArray, start: Int = 0, length: Int = dst.size - start,
             check: () -> Unit = {})
}
