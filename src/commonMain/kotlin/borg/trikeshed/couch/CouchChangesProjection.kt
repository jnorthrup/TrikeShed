package borg.trikeshed.couch

import borg.trikeshed.lib.*
import borg.trikeshed.lib.`▶`
import borg.trikeshed.mutable.MutableSeries
import borg.trikeshed.mutable.mutableSeriesOf

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
     * Resume after sequence - provides an iterator or stream of frames after a sequence.
     */
    fun afterSequence(sequence: Long): Series<CouchCommittedFrame> {
        // Binary search could be used if series supported it, but simple scan works for now
        var startIdx = -1
        for ((i, frame) in frames.`▶`.withIndex()) {
            if (frame.sequence > sequence) {
                startIdx = i
                break
            }
        }
        if (startIdx == -1) {
            return 0 j { error("empty") }
        }
        val size = frames.size - startIdx
        return size j { frames[startIdx + it] }
    }

    /**
     * Replay equivalence - exposes the underlying series.
     */
    fun series(): Series<CouchCommittedFrame> = frames
}
