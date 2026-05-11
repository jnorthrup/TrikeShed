package borg.trikeshed.hazelnut

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.AlibabaRowVec
import borg.trikeshed.miniduck.BlobRowVec
import borg.trikeshed.miniduck.S3RowVec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HazelnutServiceTest {
    @Test
    fun publishDelegatesToClientAndReturnsReceipt() {
        val expected = HazelnutWriteReceipt(
            forum = "idmg",
            threadId = "t-1",
            commentId = "c-1",
            accepted = true,
            revision = "r1",
        )
        val service = HazelnutService(
            client = object : HazelnutClient {
                override fun upsert(envelope: HazelnutEnvelope): HazelnutWriteReceipt {
                    assertEquals("idmg", envelope.forum)
                    assertEquals("hello hazelnut", envelope.body)
                    return expected
                }

                override fun query(query: HazelnutQuery) = 0 j { _: Int -> HazelnutEnvelope(
                    forum = query.forum,
                    threadId = "unused-thread",
                    commentId = "unused-comment",
                    body = "unused",
                ) }
            },
        )

        val receipt = service.publish(
            HazelnutEnvelope(
                forum = "idmg",
                threadId = "t-1",
                commentId = "c-1",
                body = "hello hazelnut",
            ),
        )

        assertEquals(expected, receipt)
    }

    @Test
    fun syncDelegatesQueryAndReturnsSeries() {
        val expected = 2 j { index: Int ->
            HazelnutEnvelope(
                forum = "idmg",
                threadId = "t-$index",
                commentId = "c-$index",
                title = "title-$index",
                body = "body-$index",
            )
        }
        val service = HazelnutService(
            client = object : HazelnutClient {
                override fun upsert(envelope: HazelnutEnvelope): HazelnutWriteReceipt = error("unused")

                override fun query(query: HazelnutQuery) = expected.also {
                    assertEquals("idmg", query.forum)
                    assertEquals("cursor-1", query.cursor)
                }
            },
        )

        val actual = service.sync(HazelnutQuery(forum = "idmg", cursor = "cursor-1"))

        assertEquals(2, actual.size)
        assertEquals("body-1", actual[1].body)
    }

    @Test
    fun projectBuildsDocRowVecWithBlobChildren() {
        val service = HazelnutService(
            client = object : HazelnutClient {
                override fun upsert(envelope: HazelnutEnvelope): HazelnutWriteReceipt = error("unused")
                override fun query(query: HazelnutQuery) = 0 j { _: Int -> HazelnutEnvelope(
                    forum = query.forum,
                    threadId = "unused-thread",
                    commentId = "unused-comment",
                    body = "unused",
                ) }
            },
        )
        val envelope = HazelnutEnvelope(
            forum = "idmg",
            threadId = "t-42",
            commentId = "c-9",
            title = "bucket-backed thread",
            body = "body",
            author = "alice",
            labels = mapOf("region" to "us-east-1", "bucket" to "aliyun"),
            attachments = 3 j { index: Int ->
                when (index) {
                    0 -> BlobRowVec(byteArrayOf(index.toByte()), mimeType = "application/octet-stream")
                    1 -> AlibabaRowVec(bucket = "oss-bucket", key = "threads/t-42/comment-c-9.bin", byteSize = 12L, contentType = "application/octet-stream")
                    else -> S3RowVec(bucket = "fallback-bucket", key = "threads/t-42/comment-c-9.json", byteSize = 24L, contentType = "application/json")
                }
            },
        )

        val row = service.project(envelope)

        assertEquals("idmg", row[0])
        assertEquals("t-42", row[1])
        assertEquals(3, row[6])
        assertTrue(row.child != null)
        assertEquals(3, row.child!!.size)
        assertTrue(row.child!![0] is BlobRowVec)
        assertTrue(row.child!![1] is AlibabaRowVec)
        assertTrue(row.child!![2] is S3RowVec)
    }
}
