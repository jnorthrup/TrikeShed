package borg.trikeshed.miniduck.objectstore

import borg.trikeshed.miniduck.BlobRowVec
import borg.trikeshed.miniduck.GcsRowVec
import borg.trikeshed.miniduck.ObjectStoreProvider
import borg.trikeshed.miniduck.runBlockingCommon
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
fun <T> runBlocking(block: suspend () -> T): T = runBlockingCommon(block)

class GcsAdapterTest {

    @Test
    fun `GcsAdapter implements ObjectStoreAdapter with GCS provider`() {
        val adapter: ObjectStoreAdapter = GcsAdapter(GcsAdapter.Config(
            projectId = "test-project",
            bucket = "test-bucket",
            storage = FakeGcsStorage(),
        ))
        assertEquals(ObjectStoreProvider.GCS, adapter.provider)
    }

    @Test
    fun `GcsAdapter list returns GcsRowVec with bucket and key fields`() {
        runBlocking {
            val adapter = GcsAdapter(GcsAdapter.Config(
                projectId = "test-project",
                bucket = "test-bucket",
                storage = FakeGcsStorage(
                    listResults = listOf(
                        listOf(
                            GcsBlobMetadata("prefix/file1.txt", 12L, "text/plain", "\"etag1\"", "1680000000000000"),
                            GcsBlobMetadata("prefix/file2.txt", 34L, "application/json", "\"etag2\"", "1680000000000001"),
                        )
                    )
                ),
            ))

            val result = adapter.list("test-bucket", "prefix/", 100)

            assertTrue(result.objects.isNotEmpty())
            val first = result.objects[0] as GcsRowVec
            assertIs<GcsRowVec>(first)
            assertEquals("test-bucket", first.bucket)
            assertTrue(first.key.startsWith("prefix/"))
            // list() returns shell objects — child is null until accessed via get()
            assertTrue(first.child == null || first.child!!.size == 0)
        }
    }

    @Test
    fun `GcsAdapter get returns GcsRowVec with BlobRowVec child`() {
        runBlocking {
            val adapter = GcsAdapter(GcsAdapter.Config(
                projectId = "test-project",
                bucket = "test-bucket",
                storage = FakeGcsStorage(
                    getResults = listOf(GcsBlobContent(byteArrayOf(0xFE.toByte(), 0xCA.toByte()), "image/png"))
                ),
            ))

            val result = adapter.get("test-bucket", "images/photo.png")

            assertTrue(result != null)
            val typedResult = result as GcsRowVec
            assertIs<GcsRowVec>(typedResult)
            assertEquals(ObjectStoreProvider.GCS, typedResult.provider)
            assertEquals("images/photo.png", typedResult.key)
            val blob = typedResult.child!![0] as BlobRowVec
            assertTrue(byteArrayOf(0xFE.toByte(), 0xCA.toByte()).contentEquals(blob.bytes))
        }
    }

    @Test
    fun `GcsAdapter put stores bytes and returns true`() {
        runBlocking {
            var putCalled = false
            var putArgs: Triple<String, String, ByteArray>? = null
            val adapter = GcsAdapter(GcsAdapter.Config(
                projectId = "test-project",
                bucket = "test-bucket",
                storage = FakeGcsStorage(
                    putHook = { bucket, key, bytes, contentType ->
                        putCalled = true
                        putArgs = Triple(bucket, key, bytes)
                    }
                ),
            ))
            val bytes = "hello gcs".toByteArray()

            val ok = adapter.put("test-bucket", "hello.txt", bytes, mapOf("contentType" to "text/plain"))

            assertTrue(ok)
            assertTrue(putCalled)
            assertEquals("test-bucket", putArgs?.first)
            assertEquals("hello.txt", putArgs?.second)
            assertTrue(bytes.contentEquals(putArgs!!.third))
        }
    }

    @Test
    fun `GcsAdapter delete calls storage delete and returns true`() {
        runBlocking {
            var deleteCalled = false
            var deleteArgs: Pair<String, String>? = null
            val adapter = GcsAdapter(GcsAdapter.Config(
                projectId = "test-project",
                bucket = "test-bucket",
                storage = FakeGcsStorage(
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
    fun `GcsAdapter list with empty result returns empty ObjectListResult`() {
        runBlocking {
            val adapter = GcsAdapter(GcsAdapter.Config(
                projectId = "test-project",
                bucket = "test-bucket",
                storage = FakeGcsStorage(listResults = listOf(emptyList())),
            ))

            val result = adapter.list("test-bucket", "nonexistent/", 100)

            assertTrue(result.objects.isEmpty())
        }
    }

    @Test
    fun `GcsAdapter get returns null for missing object`() {
        runBlocking {
            val adapter = GcsAdapter(GcsAdapter.Config(
                projectId = "test-project",
                bucket = "test-bucket",
                storage = FakeGcsStorage(getResults = listOf(null)),
            ))

            val result = adapter.get("test-bucket", "nonexistent.txt")

            assertTrue(result == null)
        }
    }
}

// --- Fake GCS SDK stubs for testing ---

class FakeGcsStorage(
    val listResults: List<List<GcsBlobMetadata>> = listOf(emptyList()),
    val getResults: List<GcsBlobContent?> = listOf(null),
    val putHook: ((bucket: String, key: String, bytes: ByteArray, contentType: String?) -> Unit)? = null,
    val deleteHook: ((bucket: String, key: String) -> Unit)? = null,
) : GcsStorageClient {
   var listIdx = 0
   var getIdx = 0

    override fun listBlobs(bucket: String, prefix: String): List<GcsBlobMetadata> {
        return if (listIdx < listResults.size) listResults[listIdx++] else emptyList()
    }

    override fun getBlob(bucket: String, key: String): GcsBlobContent? {
        return if (getIdx < getResults.size) getResults[getIdx++] else null
    }

    override fun putBlob(bucket: String, key: String, bytes: ByteArray, contentType: String?) {
        putHook?.invoke(bucket, key, bytes, contentType)
    }

    override fun deleteBlob(bucket: String, key: String) {
        deleteHook?.invoke(bucket, key)
    }
}
