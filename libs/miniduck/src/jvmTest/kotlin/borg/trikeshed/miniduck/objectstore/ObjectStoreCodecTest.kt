package borg.trikeshed.miniduck.objectstore

import borg.trikeshed.miniduck.*
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RED tests: ObjectStoreRowVec round-trip through MiniDuckBlockCodec.
 * Run: ./gradlew :libs:couch:jvmTest --tests "borg.trikeshed.miniduck.objectstore.ObjectStoreCodecTest"
 */
class ObjectStoreCodecTest {

    @Test
    fun `GcsRowVec round-trips through MiniDuckBlockCodec`() {
        val bytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val blob = BlobRowVec(bytes, mimeType = "image/png")
        val row = GcsRowVec(
            bucket = "gcs-test-bucket",
            key = "images/photo.png",
            byteSize = 4L,
            contentType = "image/png",
            etag = "\"etag123\"",
            metadata = mapOf("x-goog-meta-foo" to "bar"),
            blob = 1 j { blob },
        )
        val block = BlockRowVec.mutable().apply { append(row) }.seal()

        val text = MiniDuckBlockCodec.encode(block)
        val decoded = MiniDuckBlockCodec.decode(text)

        assertEquals(1, decoded.rowCount)
        val result = decoded.child[0] as GcsRowVec
        assertEquals(ObjectStoreProvider.GCS, result.provider)
        assertEquals("gcs-test-bucket", result.bucket)
        assertEquals("images/photo.png", result.key)
        assertEquals(4L, result.byteSize)
        assertEquals("image/png", result.contentType)
        assertEquals("\"etag123\"", result.etag)
        assertEquals("bar", result.metadata?.get("x-goog-meta-foo"))
        val blobChild = result.child!!
        val loadedBlob = blobChild[0] as BlobRowVec
        assertTrue(bytes.contentEquals(loadedBlob.bytes))
    }

    @Test
    fun `S3RowVec round-trips through MiniDuckBlockCodec`() {
        val bytes = byteArrayOf(0x01.toByte(), 0x02.toByte())
        val blob = BlobRowVec(bytes, mimeType = "text/csv")
        val row = S3RowVec(
            bucket = "my-s3-bucket",
            key = "data/csv/report.csv",
            byteSize = 2L,
            contentType = "text/csv",
            etag = "\"s3etag\"",
            metadata = mapOf("x-amz-meta-author" to "test"),
            blob = 1 j { blob },
        )
        val block = BlockRowVec.mutable().apply { append(row) }.seal()

        val text = MiniDuckBlockCodec.encode(block)
        val decoded = MiniDuckBlockCodec.decode(text)

        assertEquals(1, decoded.rowCount)
        val result = decoded.child[0] as S3RowVec
        assertEquals(ObjectStoreProvider.S3, result.provider)
        assertEquals("my-s3-bucket", result.bucket)
        assertEquals("data/csv/report.csv", result.key)
        assertEquals("text/csv", result.contentType)
        assertEquals("\"s3etag\"", result.etag)
        val blobChild = result.child!!
        val loadedBlob = blobChild[0] as BlobRowVec
        assertTrue(bytes.contentEquals(loadedBlob.bytes))
    }

    @Test
    fun `AlibabaRowVec round-trips through MiniDuckBlockCodec`() {
        val bytes = byteArrayOf(0xFF.toByte())
        val blob = BlobRowVec(bytes, mimeType = "application/gzip")
        val row = AlibabaRowVec(
            bucket = "oss-project",
            key = "backups/backup.tar.gz",
            byteSize = 1L,
            contentType = "application/gzip",
            metadata = mapOf("x-oss-meta-location" to "us-west-1"),
            blob = 1 j { blob },
        )
        val block = BlockRowVec.mutable().apply { append(row) }.seal()

        val text = MiniDuckBlockCodec.encode(block)
        val decoded = MiniDuckBlockCodec.decode(text)

        assertEquals(1, decoded.rowCount)
        val result = decoded.child[0] as AlibabaRowVec
        assertEquals(ObjectStoreProvider.ALIBABA, result.provider)
        assertEquals("oss-project", result.bucket)
        assertEquals("backups/backup.tar.gz", result.key)
        assertEquals("application/gzip", result.contentType)
        assertEquals("us-west-1", result.metadata?.get("x-oss-meta-location"))
        val blobChild = result.child!!
        val loadedBlob = blobChild[0] as BlobRowVec
        assertTrue(bytes.contentEquals(loadedBlob.bytes))
    }

    @Test
    fun `GcsRowVec without blob (deferred) round-trips`() {
        val row = GcsRowVec(
            bucket = "b",
            key = "k",
            byteSize = 0L,
            contentType = null,
            blob = null,
        )
        val block = BlockRowVec.mutable().apply { append(row) }.seal()

        val text = MiniDuckBlockCodec.encode(block)
        val decoded = MiniDuckBlockCodec.decode(text)

        assertEquals(1, decoded.rowCount)
        val result = decoded.child[0] as GcsRowVec
        assertEquals("b", result.bucket)
        assertEquals("k", result.key)
        assertTrue(result.child == null)
    }

    @Test
    fun `multiple object store rows in one block`() {
        val blob1 = BlobRowVec(byteArrayOf(1), mimeType = "text/plain")
        val blob2 = BlobRowVec(byteArrayOf(2), mimeType = "text/plain")
        val gcs = GcsRowVec("b1", "k1", 1L, "text/plain", blob = 1 j { blob1 })
        val s3 = S3RowVec("b2", "k2", 1L, "text/plain", blob = 1 j { blob2 })
        val alibaba = AlibabaRowVec("b3", "k3", 1L, "text/plain", blob = 1 j { BlobRowVec(byteArrayOf(3), "text/plain") })
        val block = BlockRowVec.mutable().apply {
            append(gcs)
            append(s3)
            append(alibaba)
        }.seal()

        val text = MiniDuckBlockCodec.encode(block)
        val decoded = MiniDuckBlockCodec.decode(text)

        assertEquals(3, decoded.rowCount)
        assertTrue(decoded.child[0] is GcsRowVec)
        assertTrue(decoded.child[1] is S3RowVec)
        assertTrue(decoded.child[2] is AlibabaRowVec)
    }
}
