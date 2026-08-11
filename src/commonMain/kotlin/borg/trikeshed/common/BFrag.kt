package borg.trikeshed.common

// Canonical BFrag lives in borg.trikeshed.lib.accum.BFrag (same Join + ops).
// Only `len` vs `size` differ; canonical name is `size` to match PRELOAD's Series algebra.
typealias BFrag = borg.trikeshed.lib.accum.BFrag

val BFrag.size: Int
    get() {
        val (bounds) = this
        val (beg, end) = bounds
        return end - beg
    }
