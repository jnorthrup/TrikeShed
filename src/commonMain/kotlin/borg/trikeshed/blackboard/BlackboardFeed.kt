package borg.trikeshed.blackboard

/**
 * BlackboardFeed — the revision-linked acceptance rule for the `/blackboard/facts`
 * event stream, ported clean-room from the excised `harness.js` (feb5af4db).
 *
 * The original's rule, verbatim in behavior: an event at or below the current
 * revision is old news (STALE); an event from another epoch, or anything but the
 * next sequence number in this epoch, is a GAP and the client must re-snapshot;
 * only the exact successor applies. A snapshot is admissible only when it is
 * revision-linked: a safe-integer revision and a non-empty epoch.
 */

enum class FeedVerdict { APPLY, STALE, GAP }

data class BlackboardFeed(val epoch: String, val seq: Long) {

    fun accept(eventEpoch: String, eventSeq: Long): FeedVerdict = when {
        eventSeq <= seq -> FeedVerdict.STALE
        eventEpoch != epoch || eventSeq != seq + 1 -> FeedVerdict.GAP
        else -> FeedVerdict.APPLY
    }

    /** the feed advanced by an applied event; call only after [accept] returned APPLY */
    fun applied(eventSeq: Long): BlackboardFeed = copy(seq = eventSeq)

    companion object {
        /** 2^53 − 1: the largest integer JSON/JS numbers carry exactly, per the source's Number.isSafeInteger guard */
        const val maxSafeRevision: Long = 9007199254740991L

        /** a snapshot is revision-linked or it is not a snapshot */
        fun linked(revision: Long?, epoch: String?): BlackboardFeed? =
            if (revision != null && epoch != null && epoch.isNotEmpty() &&
                revision in 0..maxSafeRevision
            ) BlackboardFeed(epoch, revision) else null
    }
}
