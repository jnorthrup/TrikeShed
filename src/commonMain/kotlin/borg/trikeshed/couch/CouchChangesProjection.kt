package borg.trikeshed.couch

import borg.trikeshed.lib.*
import borg.trikeshed.collections.mutableSeriesOf
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId

/**
 * CouchChangesProjection - Maintains a strict monotonic sequence of committed frames.
 */
class CouchChangesProjection(private val cas: CasStore? = null) {

    // Strict monotonic sequence of committed frames
    // CAS-backed history retains headers and body addresses; readers decode one revision at a time.
    private val frames = mutableSeriesOf<Join<CouchCommittedFrame, ContentId?>>()
    private var lastSequence: Long = -1L
    val lastCommittedSequence: Long get() = if (frames.size == 0) -1L else frames[frames.size - 1].a.sequence

    fun resolve(retained: Join<CouchCommittedFrame, ContentId?>): CouchCommittedFrame {
        val cid = retained.b ?: return retained.a
        val bytes = checkNotNull(cas).get(cid) ?: error("Missing Couch change body $cid")
        require(ContentId.of(bytes) == cid) { "Corrupt Couch change body $cid" }
        val doc = requireNotNull(CouchStoreFactory.documentFromBody(bytes)) { "Invalid Couch change body $cid" }
        require(doc.id == retained.a.docId) { "Couch change body identity mismatch" }
        return retained.a.copy(doc = doc)
    }

    /**
     * Appends a newly committed frame to the changes sequence.
     * Enforces strict monotonic sequence checks.
     */
    fun applyCommit(frame: CouchCommittedFrame) {
        require(frame.sequence > lastSequence) {
            "Frame sequence ${frame.sequence} must be strictly greater than last sequence $lastSequence"
        }
        val body = if (cas != null) frame.doc?.let { cas.put(CouchStoreFactory.canonicalBody(it)) } else null
        frames.append((if (body != null) frame.copy(doc = null) else frame) j body)
        lastSequence = frame.sequence
    }

    /**
     * Subscribe to new frames as they are appended.
     * Returns a cancellation function.
     */
    fun subscribe(observer: (Twin<Series<CouchCommittedFrame>>) -> Unit): () -> Unit {
        return frames.subscribe { twin -> observer((twin.a α ::resolve) j (twin.b α ::resolve)) }
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
            if (all.b(mid).a.sequence > sequence) hi = mid else lo = mid + 1
        }
        val start = lo
        return (all.a - start) j { resolve(all.b(start + it)) }
    }

    /**
     * Replay equivalence - exposes the underlying series.
     */
    fun series(): Series<CouchCommittedFrame> = object : Series<CouchCommittedFrame> {
        override val a: Int get() = frames.a
        override val b: (Int) -> CouchCommittedFrame = { resolve(frames[it]) }
    }
}
