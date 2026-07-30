package borg.trikeshed.couch

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CouchChangesProjectionResumeTest {

    @Test
    fun testResumeAfterSequence() {
        val changes = CouchChangesProjection()

        changes.applyCommit(CouchCommittedFrame(0, "a", "1-a", false, Document("a", emptyList())))
        changes.applyCommit(CouchCommittedFrame(1, "b", "1-b", false, Document("b", emptyList())))
        changes.applyCommit(CouchCommittedFrame(2, "c", "1-c", false, Document("c", emptyList())))

        val frames = changes.series()
        assertEquals(3, frames.size)

        val afterSeq1 = changes.afterSequence(0)
        assertEquals(2, afterSeq1.size)
        assertEquals("b", afterSeq1[0].docId)
        assertEquals("c", afterSeq1[1].docId)

        val afterSeq2 = changes.afterSequence(1)
        assertEquals(1, afterSeq2.size)
        assertEquals("c", afterSeq2[0].docId)

        val afterSeq3 = changes.afterSequence(2)
        assertEquals(0, afterSeq3.size)
    }

    @Test
    fun testMonotonicRejection() {
        val changes = CouchChangesProjection()

        changes.applyCommit(CouchCommittedFrame(1, "a", "1-a", false, Document("a", emptyList())))

        assertFailsWith<IllegalArgumentException> {
            changes.applyCommit(CouchCommittedFrame(1, "b", "1-b", false, Document("b", emptyList())))
        }

        assertFailsWith<IllegalArgumentException> {
            changes.applyCommit(CouchCommittedFrame(0, "c", "1-c", false, Document("c", emptyList())))
        }
    }
}
