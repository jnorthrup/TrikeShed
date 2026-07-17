package borg.trikeshed.couch

import borg.trikeshed.lib.*
import borg.trikeshed.mutable.MutableSeries
import borg.trikeshed.mutable.mutableSeriesOf

/**
 * CouchChangesProjection - Maintains a strict monotonic sequence of committed frames.
 */
class CouchChangesProjection {

    // Strict monotonic sequence of committed frames
    private val frames = mutableSeriesOf<CouchCommittedFrame>()

    /**
     * Appends a newly committed frame to the changes sequence.
     */
    fun applyCommit(frame: CouchCommittedFrame) {
        frames.append(frame)
    }

    /**
     * Subscribe to new frames as they are appended.
     * Returns a cancellation function.
     */
    fun subscribe(observer: (Twin<Series<CouchCommittedFrame>>) -> Unit): () -> Unit {
        return frames.subscribe(observer)
    }

    /**
     * Replay equivalence - exposes the underlying series.
     */
    fun series(): Series<CouchCommittedFrame> = frames
}
