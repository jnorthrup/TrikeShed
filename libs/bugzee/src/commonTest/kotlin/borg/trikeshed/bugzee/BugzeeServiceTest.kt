package borg.trikeshed.bugzee

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.miniduck.DocRowVec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BugzeeServiceTest {
    @Test
    fun publishDelegatesToClientAndReturnsReceipt() {
        val expected = BugzeeWriteReceipt(
            product = "bugzee",
            bugId = "BUG-1",
            commentId = "C-1",
            accepted = true,
            revision = "rev-1",
        )
        val service = BugzeeService(
            client = object : BugzeeClient {
                override fun upsert(envelope: BugzeeEnvelope): BugzeeWriteReceipt {
                    assertEquals("bugzee", envelope.product)
                    assertEquals("body description", envelope.description)
                    return expected
                }

                override fun query(query: BugzeeQuery) = 0 j { _: Int -> BugzeeEnvelope(
                    product = query.product,
                    bugId = "unused-bug",
                    summary = "unused",
                    description = "unused",
                ) }
            },
        )

        val receipt = service.publish(
            BugzeeEnvelope(
                product = "bugzee",
                bugId = "BUG-1",
                commentId = "C-1",
                summary = "thread",
                description = "body description",
            ),
        )

        assertEquals(expected, receipt)
    }

    @Test
    fun syncDelegatesQueryAndReturnsSeries() {
        val expected = 2 j { index: Int ->
            BugzeeEnvelope(
                product = "bugzee",
                bugId = "BUG-$index",
                commentId = "C-$index",
                summary = "summary-$index",
                description = "body-$index",
                severity = index,
            )
        }
        val service = BugzeeService(
            client = object : BugzeeClient {
                override fun upsert(envelope: BugzeeEnvelope): BugzeeWriteReceipt = error("unused")

                override fun query(query: BugzeeQuery) = expected.also {
                    assertEquals("bugzee", query.product)
                    assertEquals("open", query.listing)
                }
            },
        )

        val actual = service.sync(BugzeeQuery(product = "bugzee", listing = "open"))

        assertEquals(2, actual.size)
        assertEquals(1, actual[1].severity)
    }

    @Test
    fun projectBuildsDocRowVecWithAttachmentChildren() {
        val service = BugzeeService(
            client = object : BugzeeClient {
                override fun upsert(envelope: BugzeeEnvelope): BugzeeWriteReceipt = error("unused")
                override fun query(query: BugzeeQuery) = 0 j { _: Int -> BugzeeEnvelope(
                    product = query.product,
                    bugId = "unused-bug",
                    summary = "unused",
                    description = "unused",
                ) }
            },
        )
        val child1 = DocRowVec(listOf("col1"), listOf(1))
        val child2 = DocRowVec(listOf("col2"), listOf(2))
        val envelope = BugzeeEnvelope(
            product = "bugzee",
            bugId = "BUG-42",
            commentId = "C-9",
            summary = "s3 backed thread",
            description = "body",
            assignee = "bob",
            severity = 17,
            metadata = mapOf("bucket" to "s3"),
            attachments = 2 j { index: Int ->
                when (index) {
                    0 -> child1
                    else -> child2
                }
            },
        )

        val row = service.project(envelope)

        assertEquals("bugzee", row[0])
        assertEquals("BUG-42", row[1])
        assertEquals(17, row[6])
        assertEquals(2, row[7])
        assertEquals(2, row["attachmentCount"])
        assertTrue(row["assignee"] == null || row["assignee"] == "bob")
        assertTrue(row.child != null)
        assertSame(envelope.attachments, row.child)
        assertEquals(2, row.child!!.size)
    }

    @Test
    fun envelopeCanProjectWithoutServiceWrapper() {
        val attachment = DocRowVec(listOf("col1"), listOf(1))
        val envelope = BugzeeEnvelope(
            product = "bugzee",
            bugId = "BUG-5",
            summary = "direct rowvec",
            description = "projection",
            metadata = mapOf("source" to "idmg", "kind" to "bug"),
            attachments = 1 j { index: Int -> attachment },
        )

        val row = envelope.toRowVec()

        assertEquals("bugzee", row["product"])
        assertEquals(1, row["attachmentCount"])
        assertTrue(row.child != null)
        assertSame(envelope.attachments, row.child)
        assertSame(attachment, row.child!![0])
    }
}
