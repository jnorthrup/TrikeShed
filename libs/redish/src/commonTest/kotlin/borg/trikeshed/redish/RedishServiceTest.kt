package borg.trikeshed.redish

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.BlobRowVec
import borg.trikeshed.miniduck.S3RowVec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedishServiceTest {
    @Test
    fun publishDelegatesToClientAndReturnsReceipt() {
        val expected = RedishWriteReceipt(
            subreddit = "reddit",
            postId = "p-1",
            commentId = "c-1",
            accepted = true,
            revision = "rev-1",
        )
        val service = RedishService(
            client = object : RedishClient {
                override fun upsert(envelope: RedishEnvelope): RedishWriteReceipt {
                    assertEquals("reddit", envelope.subreddit)
                    assertEquals("post body", envelope.body)
                    return expected
                }

                override fun query(query: RedishQuery) = 0 j { _: Int -> RedishEnvelope(
                    subreddit = query.subreddit,
                    postId = "unused-post",
                    title = "unused",
                    body = "unused",
                ) }
            },
        )

        val receipt = service.publish(
            RedishEnvelope(
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
            RedishEnvelope(
                subreddit = "reddit",
                postId = "p-$index",
                commentId = "c-$index",
                title = "title-$index",
                body = "body-$index",
                score = index,
            )
        }
        val service = RedishService(
            client = object : RedishClient {
                override fun upsert(envelope: RedishEnvelope): RedishWriteReceipt = error("unused")

                override fun query(query: RedishQuery) = expected.also {
                    assertEquals("reddit", query.subreddit)
                    assertEquals("new", query.listing)
                }
            },
        )

        val actual = service.sync(RedishQuery(subreddit = "reddit", listing = "new"))

        assertEquals(2, actual.size)
        assertEquals(1, actual[1].score)
    }

    @Test
    fun projectBuildsDocRowVecWithMediaChildren() {
        val service = RedishService(
            client = object : RedishClient {
                override fun upsert(envelope: RedishEnvelope): RedishWriteReceipt = error("unused")
                override fun query(query: RedishQuery) = 0 j { _: Int -> RedishEnvelope(
                    subreddit = query.subreddit,
                    postId = "unused-post",
                    title = "unused",
                    body = "unused",
                ) }
            },
        )
        val envelope = RedishEnvelope(
            subreddit = "reddit",
            postId = "p-42",
            commentId = "c-9",
            title = "s3 backed thread",
            body = "body",
            author = "bob",
            score = 17,
            tags = mapOf("bucket" to "s3", "region" to "us-west-2"),
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
        assertTrue(row.child != null)
        assertEquals(2, row.child!!.size)
        assertTrue(row.child!![0] is BlobRowVec)
        assertTrue(row.child!![1] is S3RowVec)
    }
}
