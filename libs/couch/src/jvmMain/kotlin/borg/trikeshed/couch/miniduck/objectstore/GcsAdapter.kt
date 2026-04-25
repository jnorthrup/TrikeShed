package borg.trikeshed.couch.miniduck.objectstore

import borg.trikeshed.couch.miniduck.BlobRowVec
import borg.trikeshed.couch.miniduck.ObjectStoreProvider
import borg.trikeshed.couch.miniduck.ObjectStoreRowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * JVM adapter for Google Cloud Storage (GCS).
 *
 * The Config accepts either a real [com.google.cloud.storage.Storage] client
 * or a [FakeGcsStorage] for testing without cloud credentials.
 */
class GcsAdapter(
    val projectId: String,
    val bucket: String,
    private val storage: GcsStorageClient,
) : ObjectStoreAdapter {

    constructor(config: Config) : this(config.projectId, config.bucket, config.storage!!)

    override val provider: ObjectStoreProvider = ObjectStoreProvider.GCS

    override suspend fun list(bucket: String, prefix: String, maxKeys: Int): ObjectListResult {
        val blobs = storage.listBlobs(bucket, prefix).take(maxKeys)
        val rows = blobs.map { blob ->
            ObjectStoreRowVec.gcs(
                bucket = bucket,
                key = blob.name,
                byteSize = blob.size,
                contentType = blob.contentType,
                etag = blob.etag,
                lastModified = null,
                versionId = blob.generation,
                metadata = blob.metadata,
                blob = null,
            )
        }
        return ObjectListResult(rows.size j { rows[it] })
    }

    override suspend fun get(bucket: String, key: String): ObjectStoreRowVec? {
        val obj = storage.getBlob(bucket, key) ?: return null
        val blobRow = BlobRowVec(obj.bytes, obj.contentType)
        return ObjectStoreRowVec.gcs(
            bucket = bucket,
            key = key,
            byteSize = obj.bytes.size.toLong(),
            contentType = obj.contentType,
            etag = obj.etag,
            lastModified = null,
            versionId = null,
            metadata = obj.metadata,
            blob = 1 j { blobRow },
        )
    }

    override suspend fun put(bucket: String, key: String, bytes: ByteArray, metadata: Map<String, String>?): Boolean {
        storage.putBlob(bucket, key, bytes, metadata?.get("contentType"))
        return true
    }

    override suspend fun delete(bucket: String, key: String): Boolean {
        storage.deleteBlob(bucket, key)
        return true
    }

    class Config(
        val projectId: String = "",
        val bucket: String = "",
        val storage: GcsStorageClient? = null,
    )
}
