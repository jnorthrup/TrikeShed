package borg.trikeshed.couch

import borg.trikeshed.lib.*
import borg.trikeshed.collections.mutableSeriesOf

/**
 * CouchChangesProjection - Maintains a strict monotonic sequence of committed frames.
 */
class CouchChangesProjection {

    // Strict monotonic sequence of committed frames
    private val frames = mutableSeriesOf<CouchCommittedFrame>()
    private var lastSequence: Long = -1L

    /**
     * Appends a newly committed frame to the changes sequence.
     * Enforces strict monotonic sequence checks.
     */
    fun applyCommit(frame: CouchCommittedFrame) {
        require(frame.sequence > lastSequence) {
            "Frame sequence ${frame.sequence} must be strictly greater than last sequence $lastSequence"
        }
        frames.append(frame)
        lastSequence = frame.sequence
    }

    /**
     * Subscribe to new frames as they are appended.
     * Returns a cancellation function.
     */
    fun subscribe(observer: (Twin<Series<CouchCommittedFrame>>) -> Unit): () -> Unit {
        return frames.subscribe(observer)
    }

    /**
     * Frames after [sequence], as of now. Sequences strictly rise (see [applyCommit]), so the first frame past
     * [sequence] is found by binary search over a snapshot, which appends never disturb.
     */
    fun afterSequence(sequence: Long): Series<CouchCommittedFrame> {
        val all = frames.snapshot()
        var lo = 0
        var hi = all.a
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (all.b(mid).sequence > sequence) hi = mid else lo = mid + 1
        }
        val start = lo
        return (all.a - start) j { all.b(start + it) }
    }

    /**
     * Replay equivalence - exposes the underlying series.
     */
    fun series(): Series<CouchCommittedFrame> = frames
}
