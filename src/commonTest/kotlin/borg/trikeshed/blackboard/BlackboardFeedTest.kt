package borg.trikeshed.blackboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlackboardFeedTest {

    @Test
    fun staleAtOrBelowCurrentRevision() {
        val feed = BlackboardFeed.linked(10, "epoch-a")!!
        assertEquals(FeedVerdict.STALE, feed.accept("epoch-a", 10))
        assertEquals(FeedVerdict.STALE, feed.accept("epoch-a", 3))
        assertEquals(FeedVerdict.STALE, feed.accept("epoch-b", 9))
    }

    @Test
    fun gapOnEpochMismatchOrSkippedSequence() {
        val feed = BlackboardFeed.linked(10, "epoch-a")!!
        assertEquals(FeedVerdict.GAP, feed.accept("epoch-b", 11))
        assertEquals(FeedVerdict.GAP, feed.accept("epoch-a", 12))
        assertEquals(FeedVerdict.GAP, feed.accept("epoch-a", 11 + BlackboardFeed.maxSafeRevision))
    }

    @Test
    fun onlyTheExactSuccessorApplies() {
        val feed = BlackboardFeed.linked(10, "epoch-a")!!
        assertEquals(FeedVerdict.APPLY, feed.accept("epoch-a", 11))
        assertEquals(11, feed.applied(11).seq)
    }

    @Test
    fun snapshotsMustBeRevisionLinked() {
        assertNull(BlackboardFeed.linked(null, "epoch-a"))
        assertNull(BlackboardFeed.linked(10, null))
        assertNull(BlackboardFeed.linked(10, ""))
        assertNull(BlackboardFeed.linked(-1, "epoch-a"))
        assertNull(BlackboardFeed.linked(BlackboardFeed.maxSafeRevision + 1, "epoch-a"))
        assertEquals(10, BlackboardFeed.linked(10, "epoch-a")!!.seq)
        assertEquals(BlackboardFeed.maxSafeRevision, BlackboardFeed.linked(BlackboardFeed.maxSafeRevision, "e")!!.seq)
    }
}
