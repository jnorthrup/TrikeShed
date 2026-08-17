package borg.trikeshed.couch

import borg.trikeshed.lib.*
import borg.trikeshed.lib.`▶`
import borg.trikeshed.collections.MutableSeries
import borg.trikeshed.collections.mutableSeriesOf
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.yield

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
    suspend fun afterSequence(sequence: Long): Series<CouchCommittedFrame> {
        val hasSupervisor = coroutineContext[NioSupervisor.Key] != null
        // Binary search could be used if series supported it, but simple scan works for now
        var startIdx = -1
        for ((i, frame) in frames.`▶`.withIndex()) {
            if (hasSupervisor) {
                if (i > 0 && i % 100 == 0) {
                    yield()
                }
            }
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
