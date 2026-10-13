package borg.trikeshed.couch

import borg.trikeshed.lib.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CouchChangesProjectionResumeTest {

    @Test
    fun casHistoryReadsSelectedBodiesAndIsolatesMutableValues() {
        val reads = mutableListOf<ContentId>()
        val cas = object : CasStore() {
            override fun get(cid: ContentId): ByteArray? {
                reads.add(cid)
                return super.get(cid)
            }
        }
        val changes = CouchChangesProjection(cas)
        val nested = mutableMapOf<String, Any>("items" to listOf("original"))
        val source = Document("a", listOf(Field("nested", nested)))
        val bodyCid = ContentId.of(CouchStoreFactory.canonicalBody(source))
        changes.applyCommit(CouchCommittedFrame(0, "a", "1-a", false, source))
        changes.applyCommit(CouchCommittedFrame(1, "b", "1-b", false, Document("b", emptyList())))
        changes.applyCommit(CouchCommittedFrame(2, "c", "1-c", false, Document("c", emptyList())))

        val all = changes.series()
        val resumed = changes.afterSequence(0)
        assertEquals(3, all.size)
        assertEquals(2, resumed.size)
        assertEquals(2L, changes.lastCommittedSequence)
        assertEquals(emptyList(), reads)
        nested["items"] = listOf("source mutation")
        @Suppress("UNCHECKED_CAST")
        val returned = assertNotNull(all[0].doc).fields.single().value as MutableMap<String, Any>
        assertEquals(listOf("original"), returned["items"])
        assertEquals(listOf(bodyCid), reads)
        returned["items"] = listOf("reader mutation")
        val reread = assertNotNull(all[0].doc).fields.single().value as Map<*, *>
        assertEquals(listOf("original"), reread["items"])
        assertEquals(listOf(bodyCid, bodyCid), reads)
        cas.corrupt(bodyCid)
        assertFailsWith<IllegalStateException> { all[0] }
    }

    @Test
    fun casCommitNotificationsPublishMonotonicHeadersWithoutReadingBodies() {
        var reads = 0
        val cas = object : CasStore() {
            override fun get(cid: ContentId): ByteArray? { reads++; return super.get(cid) }
        }
        val changes = CouchChangesProjection(cas)
        val notifications = mutableListOf<Triple<Int, Int, Long>>()
        val dispose = changes.subscribe { states ->
            notifications.add(Triple(states.a.size, states.b.size, changes.lastCommittedSequence))
        }
        try {
            changes.applyCommit(CouchCommittedFrame(0, "a", "1-a", false, Document("a", emptyList())))
            changes.applyCommit(CouchCommittedFrame(1, "b", "1-b", false, Document("b", emptyList())))
            assertFailsWith<IllegalArgumentException> {
                changes.applyCommit(CouchCommittedFrame(1, "c", "1-c", false, Document("c", emptyList())))
            }
            assertEquals(listOf(Triple(0, 1, 0L), Triple(1, 2, 1L)), notifications)
            assertEquals(0, reads)
        } finally { dispose() }
    }

    @Test
    fun testResumeAfterSequence() = kotlinx.coroutines.test.runTest {
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
