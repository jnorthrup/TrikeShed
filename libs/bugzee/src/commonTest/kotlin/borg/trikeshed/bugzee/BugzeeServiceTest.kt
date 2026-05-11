package borg.trikeshed.bugzee

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.BlobRowVec
import borg.trikeshed.miniduck.S3RowVec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BugzeeServiceTest {
    @Test
    fun publishDelegatesToClientAndReturnsReceipt() {
        val expected = BugzeeWriteReceipt(
            subreddit = "reddit",
            postId = "p-1",
            commentId = "c-1",
            accepted = true,
            revision = "rev-1",
        )
        val service = BugzeeService(
            client = object : BugzeeClient {
                override fun upsert(envelope: BugzeeEnvelope): BugzeeWriteReceipt {
                    assertEquals("reddit", envelope.subreddit)
                    assertEquals("post body", envelope.body)
                    return expected
                }

                override fun query(query: BugzeeQuery) = 0 j { _: Int -> BugzeeEnvelope(
                    subreddit = query.subreddit,
                    postId = "unused-post",
                    title = "unused",
                    body = "unused",
                ) }
            },
        )

        val receipt = service.publish(
            BugzeeEnvelope(
                subreddit = "reddit",
                postId = "p-1",
                commentId = "c-1",
                title = "thread",
                body = "post body",
            ),
        )

        assertEquals(expected, receipt)
    }

    @Test
    fun syncDelegatesQueryAndReturnsSeries() {
        val expected = 2 j { index: Int ->
            BugzeeEnvelope(
                subreddit = "reddit",
                postId = "p-$index",
                commentId = "c-$index",
                title = "title-$index",
                body = "body-$index",
                score = index,
            )
        }
        val service = BugzeeService(
            client = object : BugzeeClient {
                override fun upsert(envelope: BugzeeEnvelope): BugzeeWriteReceipt = error("unused")

                override fun query(query: BugzeeQuery) = expected.also {
                    assertEquals("reddit", query.subreddit)
                    assertEquals("new", query.listing)
                }
            },
        )

        val actual = service.sync(BugzeeQuery(subreddit = "reddit", listing = "new"))

        assertEquals(2, actual.size)
        assertEquals(1, actual[1].score)
    }

    @Test
    fun projectBuildsDocRowVecWithMediaChildren() {
        val service = BugzeeService(
            client = object : BugzeeClient {
                override fun upsert(envelope: BugzeeEnvelope): BugzeeWriteReceipt = error("unused")
                override fun query(query: BugzeeQuery) = 0 j { _: Int -> BugzeeEnvelope(
                    subreddit = query.subreddit,
                    postId = "unused-post",
                    title = "unused",
                    body = "unused",
                ) }
            },
        )
        val envelope = BugzeeEnvelope(
            subreddit = "reddit",
            postId = "p-42",
            commentId = "c-9",
            title = "s3 backed thread",
            body = "body",
            author = "bob",
            score = 17,
            tags = mapOf("bucket" to "s3"),
            media = 2 j { index: Int ->
                when (index) {
                    0 -> BlobRowVec(byteArrayOf((index + 1).toByte()), mimeType = "image/png")
                    else -> S3RowVec(bucket = "reddit-media", key = "posts/p-42/preview.png", byteSize = 4096L, contentType = "image/png")
                }
            },
        )

        val row = service.project(envelope)

        assertEquals("reddit", row[0])
        assertEquals("p-42", row[1])
        assertEquals(17, row[6])
        assertEquals(2, row[7])
        assertEquals(2, row["mediaCount"])
        assertTrue(row["tagCount"] == null)
        assertTrue(row.child != null)
        assertSame(envelope.media, row.child)
        assertEquals(2, row.child!!.size)
        assertTrue(row.child!![0] is BlobRowVec)
        assertTrue(row.child!![1] is S3RowVec)
    }

    @Test
    fun envelopeCanProjectWithoutServiceWrapper() {
        val media = S3RowVec(bucket = "reddit-media", key = "posts/p-5/body.json", byteSize = 64L, contentType = "application/json")
        val envelope = BugzeeEnvelope(
            subreddit = "reddit",
            postId = "p-5",
            title = "direct rowvec",
            body = "projection",
            tags = mapOf("source" to "idmg", "kind" to "post"),
            media = 1 j { media },
        )

        val row = envelope.toRowVec()

        assertEquals("reddit", row["subreddit"])
        assertEquals(1, row["mediaCount"])
        assertTrue(row["tagCount"] == null)
        assertSame(envelope.media, row.child)
        assertSame(media, row.child!![0])
    }
}
