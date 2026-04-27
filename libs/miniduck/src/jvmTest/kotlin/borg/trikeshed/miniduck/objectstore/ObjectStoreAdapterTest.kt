package borg.trikeshed.miniduck.objectstore

import borg.trikeshed.miniduck.BlobRowVec
import borg.trikeshed.miniduck.ObjectStoreProvider
import borg.trikeshed.miniduck.ObjectStoreRowVec
import borg.trikeshed.miniduck.S3RowVec
import borg.trikeshed.miniduck.runBlockingCommon
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
fun <T> runBlocking(block: suspend () -> T): T = runBlockingCommon(block)

class FakeObjectStoreAdapter : ObjectStoreAdapter {
    override val provider: ObjectStoreProvider = ObjectStoreProvider.S3
   val stored = mutableMapOf<String, ByteArray>()
   val storedMeta = mutableMapOf<String, Map<String, String>>()

    override suspend fun list(bucket: String, prefix: String, maxKeys: Int): ObjectListResult {
        val objects = stored.keys
            .filter { it.startsWith("$bucket/$prefix") }
            .take(maxKeys)
            .map { fullKey ->
                val key = fullKey.removePrefix("$bucket/")
                val bytes = stored[fullKey]!!
                S3RowVec(
                    bucket = bucket,
                    key = key,
                    byteSize = bytes.size.toLong(),
                    contentType = storedMeta[fullKey]?.get("contentType") ?: "application/octet-stream",
                    etag = "\"etag\"",
                    metadata = storedMeta[fullKey],
                    blob = 1 j { BlobRowVec(bytes, storedMeta[fullKey]?.get("contentType")) },
                )
            }
        val objectSeries: Series<ObjectStoreRowVec> = objects.size j { i -> objects[i] }
        return ObjectListResult(objectSeries)
    }

    override suspend fun get(bucket: String, key: String): ObjectStoreRowVec? {
        val fullKey = "$bucket/$key"
        val bytes = stored[fullKey] ?: return null
        return S3RowVec(
            bucket = bucket,
            key = key,
            byteSize = bytes.size.toLong(),
            contentType = storedMeta[fullKey]?.get("contentType"),
            etag = "\"etag\"",
            metadata = storedMeta[fullKey],
            blob = 1 j { BlobRowVec(bytes, storedMeta[fullKey]?.get("contentType")) },
        )
    }

    override suspend fun put(bucket: String, key: String, bytes: ByteArray, metadata: Map<String, String>?): Boolean {
        stored["$bucket/$key"] = bytes
        storedMeta["$bucket/$key"] = metadata ?: emptyMap()
        return true
    }

    override suspend fun delete(bucket: String, key: String): Boolean {
        stored.remove("$bucket/$key")
        storedMeta.remove("$bucket/$key")
        return true
    }
}

class ObjectStoreAdapterTest {

    @Test
    fun `list returns rows with lazy blob child`() {
        runBlocking {
            val adapter = FakeObjectStoreAdapter()
            adapter.put("b", "a/1.txt", byteArrayOf(0x01.toByte(), 0x02.toByte()), mapOf("contentType" to "text/plain"))
            adapter.put("b", "a/2.txt", byteArrayOf(0x03.toByte(), 0x04.toByte()), mapOf("contentType" to "text/plain"))

            val result = adapter.list("b", "a/", 100)

            assertEquals(2, result.objects.size)
            assertTrue(result.objects[0].child != null)
            assertTrue(result.objects[1].child != null)
            val first = result.objects[0] as S3RowVec
            assertIs<S3RowVec>(first)
            assertEquals("a/1.txt", first.key)
            assertEquals(2L, first.byteSize)
        }
    }

    @Test
    fun `get returns row with blob child containing object bytes`() {
        runBlocking {
            val adapter = FakeObjectStoreAdapter()
            val bytes = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
            adapter.put("bucket", "obj/key", bytes, null)

            val result = adapter.get("bucket", "obj/key")

            assertTrue(result != null)
            val typed = result as S3RowVec
            val blob = typed.child!![0] as BlobRowVec
            assertTrue(bytes.contentEquals(blob.bytes))
        }
    }

    @Test
    fun `put stores bytes and get retrieves them`() {
        runBlocking {
            val adapter = FakeObjectStoreAdapter()
            val bytes = "hello world".toByteArray()

            val putOk = adapter.put("my-bucket", "hello.txt", bytes, null)
            assertTrue(putOk)

            val row = adapter.get("my-bucket", "hello.txt")
            assertTrue(row != null)
            val typedRow = row as S3RowVec
            val blob = typedRow.child!![0] as BlobRowVec
            assertTrue(bytes.contentEquals(blob.bytes))
        }
    }

    @Test
    fun `delete removes stored object`() {
        runBlocking {
            val adapter = FakeObjectStoreAdapter()
            adapter.put("b", "k", byteArrayOf(0x01.toByte()), null)

            val deleted = adapter.delete("b", "k")
            assertTrue(deleted)

            val gone = adapter.get("b", "k")
            assertTrue(gone == null)
        }
    }

    @Test
    fun `get returns null for missing object`() {
        runBlocking {
            val adapter = FakeObjectStoreAdapter()

            val result = adapter.get("nonexistent-bucket", "nonexistent-key")

            assertTrue(result == null)
        }
    }

    @Test
    fun `list returns empty when bucket has no objects`() {
        runBlocking {
            val adapter = FakeObjectStoreAdapter()

            val result = adapter.list("empty-bucket", "", 100)

            assertTrue(result.objects.isEmpty())
        }
    }

    @Test
    fun `list respects maxKeys limit`() {
        runBlocking {
            val adapter = FakeObjectStoreAdapter()
            repeat(10) { i ->
                adapter.put("b", "f$i.txt", byteArrayOf(i.toByte()), null)
            }

            val result = adapter.list("b", "", 3)

            assertEquals(3, result.objects.size)
        }
    }

    @Test
    fun `list filters by prefix`() {
        runBlocking {
            val adapter = FakeObjectStoreAdapter()
            adapter.put("b", "logs/2025-01.log", byteArrayOf(0x01.toByte()), null)
            adapter.put("b", "logs/2025-02.log", byteArrayOf(0x02.toByte()), null)
            adapter.put("b", "data/2025-01.json", byteArrayOf(0x03.toByte()), null)

            val result = adapter.list("b", "logs/", 100)

            assertEquals(2, result.objects.size)
            for (i in 0 until result.objects.size) {
                assertTrue(result.objects[i].key.startsWith("logs/"), "Object at index $i should start with logs/")
            }
        }
    }

    @Test
    fun `provider identity is set correctly`() {
        runBlocking {
            val adapter = FakeObjectStoreAdapter()
            assertEquals(ObjectStoreProvider.S3, adapter.provider)
        }
    }
}
