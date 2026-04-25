package borg.trikeshed.couch.miniduck.objectstore

import borg.trikeshed.couch.miniduck.ObjectStoreProvider
import borg.trikeshed.couch.miniduck.ObjectStoreRowVec
import borg.trikeshed.lib.Series

/**
 * Result of a list operation: returns a [Series] of [ObjectStoreRowVec] shells.
 *
 * Each row carries metadata (bucket, key, size, etag, content-type …)
 * and a lazy [child][ObjectStoreRowVec.child] that resolves to a [BlobRowVec]
 * on demand.  No bytes are transferred until the child is accessed.
 */
data class ObjectListResult(
    val objects: Series<ObjectStoreRowVec>,
)

/**
 * JVM adapter interface for cloud object stores (GCS, AWS S3, Alibaba OSS …).
 *
 * Implementations live in jvmMain and wrap the vendor SDK.
 * The interface is platform-neutral so the same algebra works in tests
 * with [FakeObjectStoreAdapter] and in production with real SDK adapters.
 */
interface ObjectStoreAdapter {
    /** Cloud provider identity. */
    val provider: ObjectStoreProvider

    /**
     * List objects under [prefix] in [bucket], up to [maxKeys].
     *
     * Each returned row is a shell — access [ObjectStoreRowVec.child]
     * lazily to get the [BlobRowVec] bytes.
     */
    suspend fun list(bucket: String, prefix: String, maxKeys: Int): ObjectListResult

    /**
     * Get a single object by key.
     *
     * Returns null if the object does not exist.
     * The [ObjectStoreRowVec.child] carries the blob bytes.
     */
    suspend fun get(bucket: String, key: String): ObjectStoreRowVec?

    /**
     * Upload [bytes] to [bucket] under [key].
     *
     * [metadata] may carry content-type and custom headers.
     * Returns true on success.
     */
    suspend fun put(bucket: String, key: String, bytes: ByteArray, metadata: Map<String, String>?): Boolean

    /**
     * Delete the object at [bucket]/[key].
     *
     * Returns true on success.
     */
    suspend fun delete(bucket: String, key: String): Boolean
}

/** Shared S3 client interface — implemented by both real SDK wrapper and test fakes. */
interface S3Client {
    fun listObjectsV2(bucket: String, prefix: String): List<S3ObjectMetadata>
    fun getObject(bucket: String, key: String): S3ObjectContent?
    fun putObject(bucket: String, key: String, bytes: ByteArray)
    fun deleteObject(bucket: String, key: String)
}

/** Shared GCS storage client interface — implemented by both real SDK wrapper and test fakes. */
interface GcsStorageClient {
    fun listBlobs(bucket: String, prefix: String): List<GcsBlobMetadata>
    fun getBlob(bucket: String, key: String): GcsBlobContent?
    fun putBlob(bucket: String, key: String, bytes: ByteArray, contentType: String?)
    fun deleteBlob(bucket: String, key: String)
}

/** Shared Alibaba OSS client interface — implemented by both real SDK wrapper and test fakes. */
interface AlibabaOssClient {
    fun listObjects(prefix: String): List<AlibabaOssObjectMetadata>
    fun getObject(key: String): AlibabaOssObjectContent?
    fun putObject(key: String, bytes: ByteArray)
    fun deleteObject(key: String)
}

data class S3ObjectMetadata(
    val key: String,
    val size: Long,
    val etag: String?,
    val lastModified: String?,
    val metadata: Map<String, String> = emptyMap(),
)

data class S3ObjectContent(
    val bytes: ByteArray,
    val contentType: String?,
    val etag: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is S3ObjectContent) return false
        return bytes.contentEquals(other.bytes) && contentType == other.contentType
    }
    override fun hashCode(): Int = bytes.contentHashCode() + contentType.hashCode()
}

data class GcsBlobMetadata(
    val name: String,
    val size: Long,
    val contentType: String?,
    val etag: String?,
    val generation: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class GcsBlobContent(
    val bytes: ByteArray,
    val contentType: String?,
    val etag: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GcsBlobContent) return false
        return bytes.contentEquals(other.bytes) && contentType == other.contentType
    }
    override fun hashCode(): Int = bytes.contentHashCode() + contentType.hashCode()
}

data class AlibabaOssObjectMetadata(
    val key: String,
    val size: Long,
    val etag: String?,
    val lastModified: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class AlibabaOssObjectContent(
    val bytes: ByteArray,
    val contentType: String?,
    val etag: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AlibabaOssObjectContent) return false
        return bytes.contentEquals(other.bytes) && contentType == other.contentType
    }
    override fun hashCode(): Int = bytes.contentHashCode() + contentType.hashCode()
}
