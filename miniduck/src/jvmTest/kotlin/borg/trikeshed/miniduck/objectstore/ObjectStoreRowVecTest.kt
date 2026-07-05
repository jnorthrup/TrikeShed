package borg.trikeshed.miniduck.objectstore

import borg.trikeshed.miniduck.AlibabaRowVec
import borg.trikeshed.miniduck.BlobRowVec
import borg.trikeshed.miniduck.GcsRowVec
import borg.trikeshed.miniduck.ObjectStoreProvider
import borg.trikeshed.miniduck.S3RowVec
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


/**
 * RED tests: ObjectStoreRowVec sealed family (GcsRowVec, S3RowVec, AlibabaRowVec).
 * Run: ./gradlew :libs:couch:jvmTest --tests "borg.trikeshed.miniduck.objectstore.ObjectStoreRowVecTest"
 */
class ObjectStoreRowVecTest {

    @Test
    fun `GcsRowVec carries correct provider identity`() {
        val row = GcsRowVec(
            bucket = "my-bucket",
            key = "prefix/blob.json",
            byteSize = 42L,
            contentType = "application/json",
        )
        assertEquals(ObjectStoreProvider.GCS, row.provider)
        assertEquals("my-bucket", row.bucket)
        assertEquals("prefix/blob.json", row.key)
        assertEquals(42L, row.byteSize)
        assertEquals("application/json", row.contentType)
        assertTrue(row.isShell) // no scalar cells — content is in child
    }

    @Test
    fun `S3RowVec carries correct provider identity`() {
        val row = S3RowVec(
            bucket = "s3-bucket",
            key = "data/parquet/file.parquet",
            byteSize = 1024L,
            contentType = null,
            etag = "\"abc123\"",
        )
        assertEquals(ObjectStoreProvider.S3, row.provider)
        assertEquals("s3-bucket", row.bucket)
        assertEquals("data/parquet/file.parquet", row.key)
        assertEquals(1024L, row.byteSize)
        assertEquals("\"abc123\"", row.etag)
        assertTrue(row.isShell)
    }

    @Test
    fun `AlibabaRowVec carries correct provider identity`() {
        val row = AlibabaRowVec(
            bucket = "oss-bucket",
            key = "uploads/img.png",
            byteSize = 8192L,
            contentType = "image/png",
        )
        assertEquals(ObjectStoreProvider.ALIBABA, row.provider)
        assertEquals("oss-bucket", row.bucket)
        assertEquals("uploads/img.png", row.key)
        assertEquals(8192L, row.byteSize)
        assertEquals("image/png", row.contentType)
        assertTrue(row.isShell)
    }

    @Test
    fun `ObjectStoreRowVec child contains BlobRowVec with object bytes`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val blob = BlobRowVec(bytes, mimeType = "application/octet-stream")
        val gcsRow: GcsRowVec = GcsRowVec(
            bucket = "b",
            key = "k",
            byteSize = bytes.size.toLong(),
            contentType = "application/octet-stream",
            blob = 1 j { blob },
        )
        assertNotNull(gcsRow.child)
        val childSeries = gcsRow.child!!
        assertEquals(1, childSeries.size)
        val loaded = childSeries[0] as BlobRowVec
        assertTrue(bytes.contentEquals(loaded.bytes))
    }

    @Test
    fun `GcsRowVec with versionId and lastModified`() {
        val row = GcsRowVec(
            bucket = "b",
            key = "k",
            byteSize = 100L,
            contentType = "text/plain",
            etag = "\"v1etag\"",
            lastModified = "2025-04-01T12:00:00.000Z",
            versionId = "1680000000000000",
            metadata = mapOf("x-goog-meta-author" to "alice"),
        )
        assertEquals("\"v1etag\"", row.etag)
        assertEquals("1680000000000000", row.versionId)
        assertEquals("2025-04-01T12:00:00.000Z", row.lastModified)
        assertEquals("alice", row.metadata?.get("x-goog-meta-author"))
    }

    @Test
    fun `S3RowVec with versionId and lastModified`() {
        val row = S3RowVec(
            bucket = "b",
            key = "k",
            byteSize = 256L,
            contentType = "application/json",
            etag = "\"s3etag\"",
            lastModified = "2025-04-02T10:30:00.000Z",
            versionId = "version-abc",
            metadata = mapOf("x-amz-meta-team" to "platform"),
        )
        assertEquals("\"s3etag\"", row.etag)
        assertEquals("version-abc", row.versionId)
        assertEquals("2025-04-02T10:30:00.000Z", row.lastModified)
        assertEquals("platform", row.metadata?.get("x-amz-meta-team"))
    }

    @Test
    fun `AlibabaRowVec with all metadata fields`() {
        val row = AlibabaRowVec(
            bucket = "b",
            key = "k",
            byteSize = 2048L,
            contentType = "application/gzip",
            etag = "\"ossetag\"",
            lastModified = "2025-04-03T08:00:00.000Z",
            metadata = mapOf("x-oss-meta-location" to "us-west-1"),
        )
        assertEquals("\"ossetag\"", row.etag)
        assertEquals(null as String?, row.versionId)
        assertEquals("2025-04-03T08:00:00.000Z", row.lastModified)
        assertEquals("us-west-1", row.metadata?.get("x-oss-meta-location"))
    }

    @Test
    fun `all three rows are shells (byteSize 0)`() {
        val gcs = GcsRowVec(bucket = "b", key = "k", byteSize = 0L, contentType = null)
        val s3 = S3RowVec(bucket = "b", key = "k", byteSize = 0L, contentType = null)
        val alibaba = AlibabaRowVec(bucket = "b", key = "k", byteSize = 0L, contentType = null)
        assertEquals(0, gcs.byteSize)
        assertEquals(0, s3.byteSize)
        assertEquals(0, alibaba.byteSize)
        assertTrue(gcs.isShell)
        assertTrue(s3.isShell)
        assertTrue(alibaba.isShell)
    }

    @Test
    fun `child is nullable — row may have no blob yet`() {
        val row = GcsRowVec(bucket = "b", key = "k", byteSize = 0L, contentType = null, blob = null)
        assertEquals(null, row.child)
    }
}
