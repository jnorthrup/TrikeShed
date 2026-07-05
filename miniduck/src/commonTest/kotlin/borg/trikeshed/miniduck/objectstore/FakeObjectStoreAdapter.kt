package borg.trikeshed.miniduck.objectstore

import borg.trikeshed.lib.Series
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fake ObjectStoreAdapter for testing — in-memory, no network.
 * Implements all three client interfaces for unified testing.
 */
class FakeObjectStoreAdapter(
    val provider: ObjectStoreProvider = ObjectStoreProvider.S3,
) : ObjectStoreAdapter, ObjectStoreAdapter.S3Client, ObjectStoreAdapter.GcsStorageClient, ObjectStoreAdapter.AlibabaOssClient {

    private val store = mutableMapOf<String, ByteArray>()
    private val metadata = mutableMapOf<String, Map<String, String>>()
    private val mutex = Mutex()

    override suspend fun list(bucket: String, prefix: String, maxKeys: Int): ObjectListResult {
        return mutex.withLock {
            val objects = store.entries
                .filter { (key, _) -> key.startsWith("$bucket/$prefix") }
                .take(maxKeys)
                .map { (key, bytes) ->
                    val parts = key.split("/")
                    val collection = if (parts.size >= 2) parts[0] else ""
                    val cid = if (parts.size >= 2) parts[1] else key
                    ObjectStoreRowVec(
                        bucket = bucket,
                        key = key.removePrefix("$bucket/"),
                        byteSize = bytes.size.toLong(),
                        contentType = metadata[key]?.get("content-type"),
                        etag = bytes.contentHashCode().toString(),
                        lastModified = null,
                        versionId = null,
                        metadata = metadata[key] ?: emptyMap(),
                        blob = s_ [BlobRowVec(bytes, metadata[key]?.get("content-type"))],
                    )
                }
            ObjectListResult(objects.asSeries())
        }
    }

    override suspend fun get(bucket: String, key: String): ObjectStoreRowVec? {
        return mutex.withLock {
            val fullKey = "$bucket/$key"
            store[fullKey]?.let { bytes ->
                ObjectStoreRowVec(
                    bucket = bucket,
                    key = key,
                    byteSize = bytes.size.toLong(),
                    contentType = metadata[fullKey]?.get("content-type"),
                    etag = bytes.contentHashCode().toString(),
                    lastModified = null,
                    versionId = null,
                    metadata = metadata[fullKey] ?: emptyMap(),
                    blob = s_ [BlobRowVec(bytes, metadata[fullKey]?.get("content-type"))],
                )
            }
        }
    }

    override suspend fun put(bucket: String, key: String, bytes: ByteArray, metadata: Map<String, String>?): Boolean {
        mutex.withLock {
            store["$bucket/$key"] = bytes
            if (metadata != null) this.metadata["$bucket/$key"] = metadata
        }
        return true
    }

    override suspend fun delete(bucket: String, key: String): Boolean {
        return mutex.withLock {
            store.remove("$bucket/$key") != null
        }
    }

    // S3Client interface
    override fun listObjectsV2(bucket: String, prefix: String): List<S3ObjectMetadata> = runBlocking {
        list(bucket, prefix, Int.MAX_VALUE).objects.map { row ->
            S3ObjectMetadata(
                key = row.key,
                size = row.byteSize,
                etag = row.etag,
                lastModified = row.lastModified,
                metadata = row.metadata,
            )
        }.toList()
    }

    override fun getObject(bucket: String, key: String): S3ObjectContent? = runBlocking {
        get(bucket, key)?.let { row ->
            S3ObjectContent(
                bytes = row.blob?.bytes ?: ByteArray(0),
                contentType = row.contentType,
                etag = row.etag,
                metadata = row.metadata,
            )
        }
    }

    override fun putObject(bucket: String, key: String, bytes: ByteArray) = runBlocking {
        put(bucket, key, bytes, null)
    }

    override fun deleteObject(bucket: String, key: String) = runBlocking {
        delete(bucket, key)
    }

    // GcsStorageClient interface
    override fun listBlobs(bucket: String, prefix: String): List<GcsBlobMetadata> = runBlocking {
        list(bucket, prefix, Int.MAX_VALUE).objects.map { row ->
            GcsBlobMetadata(
                name = row.key,
                size = row.byteSize,
                contentType = row.contentType,
                etag = row.etag,
                generation = row.versionId,
                metadata = row.metadata,
            )
        }.toList()
    }

    override fun getBlob(bucket: String, key: String): GcsBlobContent? = runBlocking {
        get(bucket, key)?.let { row ->
            GcsBlobContent(
                bytes = row.blob?.bytes ?: ByteArray(0),
                contentType = row.contentType,
                etag = row.etag,
                metadata = row.metadata,
            )
        }
    }

    override fun putBlob(bucket: String, key: String, bytes: ByteArray, contentType: String?) = runBlocking {
        put(bucket, key, bytes, mapOf("content-type" to contentType ?: "application/octet-stream"))
    }

    override fun deleteBlob(bucket: String, key: String) = runBlocking {
        delete(bucket, key)
    }

    // AlibabaOssClient interface
    override fun listObjects(prefix: String): List<AlibabaOssObjectMetadata> = runBlocking {
        list(defaultBucket, prefix, Int.MAX_VALUE).objects.map { row ->
            AlibabaOssObjectMetadata(
                key = row.key,
                size = row.byteSize,
                etag = row.etag,
                lastModified = row.lastModified,
                metadata = row.metadata,
            )
        }.toList()
    }

    override fun getObject(key: String): AlibabaOssObjectContent? = runBlocking {
        get(defaultBucket, key)?.let { row ->
            AlibabaOssObjectContent(
                bytes = row.blob?.bytes ?: ByteArray(0),
                contentType = row.contentType,
                etag = row.etag,
                metadata = row.metadata,
            )
        }
    }

    override fun putObject(key: String, bytes: ByteArray) = runBlocking {
        put(defaultBucket, key, bytes, null)
    }

    override fun deleteObject(key: String) = runBlocking {
        delete(defaultBucket, key)
    }

    private val defaultBucket: String get() = "test-bucket"
}

/** Test helper to create ObjectStorageBlockStore with fake adapter */
object FakeObjectStorageBlockStore {
    fun create(provider: ObjectStoreProvider = ObjectStoreProvider.S3): ObjectStorageBlockStore {
        return ObjectStorageBlockStore(FakeObjectStoreAdapter(provider), "test-bucket")
    }
}