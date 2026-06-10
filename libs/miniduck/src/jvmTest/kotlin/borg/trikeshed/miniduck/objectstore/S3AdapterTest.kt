package borg.trikeshed.miniduck.objectstore

import borg.trikeshed.miniduck.BlobRowVec
import borg.trikeshed.miniduck.ObjectStoreProvider
import borg.trikeshed.miniduck.S3RowVec
import borg.trikeshed.miniduck.runBlockingCommon
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
@Suppress("unused")
private fun <T> rb(block: suspend () -> T): T = runBlockingCommon(block)

class S3AdapterTest {

    @Test
    fun `S3Adapter implements ObjectStoreAdapter with S3 provider`() {
        val adapter: ObjectStoreAdapter = S3Adapter(S3Adapter.Config(
            region = "us-east-1",
            bucket = "test-bucket",
            s3Client = FakeS3Client(),
        ))
        assertEquals(ObjectStoreProvider.S3, adapter.provider)
    }

    @Test
    fun `S3Adapter list returns S3RowVec with bucket and key fields`() {
        rb {
            val adapter = S3Adapter(S3Adapter.Config(
                region = "us-east-1",
                bucket = "test-bucket",
                s3Client = FakeS3Client(
                    listResults = listOf(
                        listOf(
                            S3ObjectMetadata("prefix/file1.txt", 12L, "\"etag1\"", null),
                            S3ObjectMetadata("prefix/file2.txt", 34L, "\"etag2\"", null),
                        )
                    )
                ),
            ))

            val result = adapter.list("test-bucket", "prefix/", 100)

            assertTrue(result.objects.isNotEmpty())
            val first = result.objects[0] as S3RowVec
            assertIs<S3RowVec>(first)
            assertEquals("test-bucket", first.bucket)
            assertTrue(first.key.startsWith("prefix/"))
            // list() returns shell objects — child is null until accessed via get()
            assertTrue(first.child == null || first.child!!.size == 0)
        }
    }

    @Test
    fun `S3Adapter get returns S3RowVec with BlobRowVec child`() {
        rb {
            val adapter = S3Adapter(S3Adapter.Config(
                region = "us-east-1",
                bucket = "test-bucket",
                s3Client = FakeS3Client(
                    getResults = listOf(S3ObjectContent(byteArrayOf(0xFE.toByte(), 0xCA.toByte()), "image/png"))
                ),
            ))

            val result = adapter.get("test-bucket", "images/photo.png")

            assertTrue(result != null)
            val typedResult = result as S3RowVec
            assertIs<S3RowVec>(typedResult)
            assertEquals(ObjectStoreProvider.S3, typedResult.provider)
            assertEquals("images/photo.png", typedResult.key)
            val blob = typedResult.child!![0] as BlobRowVec
            assertTrue(byteArrayOf(0xFE.toByte(), 0xCA.toByte()).contentEquals(blob.bytes))
        }
    }

    @Test
    fun `S3Adapter put stores bytes and returns true`() {
        rb {
            var putCalled = false
            var putArgs: Triple<String, String, ByteArray>? = null
            val adapter = S3Adapter(S3Adapter.Config(
                region = "us-east-1",
                bucket = "test-bucket",
                s3Client = FakeS3Client(
                    putHook = { bucket, key, bytes ->
                        putCalled = true
                        putArgs = Triple(bucket, key, bytes)
                    }
                ),
            ))
            val bytes = "hello s3".toByteArray()

            val ok = adapter.put("test-bucket", "hello.txt", bytes, mapOf("contentType" to "text/plain"))

            assertTrue(ok)
            assertTrue(putCalled)
            assertEquals("test-bucket", putArgs?.first)
            assertEquals("hello.txt", putArgs?.second)
            assertTrue(bytes.contentEquals(putArgs!!.third))
        }
    }

    @Test
    fun `S3Adapter delete calls client delete and returns true`() {
        rb {
            var deleteCalled = false
            var deleteArgs: Pair<String, String>? = null
            val adapter = S3Adapter(S3Adapter.Config(
                region = "us-east-1",
                bucket = "test-bucket",
                s3Client = FakeS3Client(
                    deleteHook = { bucket, key ->
                        deleteCalled = true
                        deleteArgs = Pair(bucket, key)
                    }
                ),
            ))

            val ok = adapter.delete("test-bucket", "prefix/file.txt")

            assertTrue(ok)
            assertTrue(deleteCalled)
            assertEquals("test-bucket", deleteArgs?.first)
            assertEquals("prefix/file.txt", deleteArgs?.second)
        }
    }

    @Test
    fun `S3Adapter list with empty result returns empty ObjectListResult`() {
        rb {
            val adapter = S3Adapter(S3Adapter.Config(
                region = "us-east-1",
                bucket = "test-bucket",
                s3Client = FakeS3Client(listResults = listOf(emptyList())),
            ))

            val result = adapter.list("test-bucket", "nonexistent/", 100)

            assertTrue(result.objects.isEmpty())
        }
    }

    @Test
    fun `S3Adapter get returns null for missing object`() {
        rb {
            val adapter = S3Adapter(S3Adapter.Config(
                region = "us-east-1",
                bucket = "test-bucket",
                s3Client = FakeS3Client(getResults = listOf(null)),
            ))

            val result = adapter.get("test-bucket", "nonexistent.txt")

            assertTrue(result == null)
        }
    }
}

// --- Fake S3 SDK stubs for testing ---

class FakeS3Client(
    val listResults: List<List<S3ObjectMetadata>> = listOf(emptyList()),
    val getResults: List<S3ObjectContent?> = listOf(null),
    val putHook: ((bucket: String, key: String, bytes: ByteArray) -> Unit)? = null,
    val deleteHook: ((bucket: String, key: String) -> Unit)? = null,
) : S3Client {
   var listIdx = 0
   var getIdx = 0

    override fun listObjectsV2(bucket: String, prefix: String): List<S3ObjectMetadata> {
        return if (listIdx < listResults.size) listResults[listIdx++] else emptyList()
    }

    override fun getObject(bucket: String, key: String): S3ObjectContent? {
        return if (getIdx < getResults.size) getResults[getIdx++] else null
    }

    override fun putObject(bucket: String, key: String, bytes: ByteArray) {
        putHook?.invoke(bucket, key, bytes)
    }

    override fun deleteObject(bucket: String, key: String) {
        deleteHook?.invoke(bucket, key)
    }
}
