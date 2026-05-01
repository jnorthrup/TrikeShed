package borg.trikeshed.miniduck.objectstore

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.miniduck.BlobRowVec
import borg.trikeshed.miniduck.ObjectStoreProvider
import borg.trikeshed.miniduck.ObjectStoreRowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * JVM adapter for AWS S3.
 *
 * The Config accepts either a real S3Client or a [FakeS3Client] for testing.
 */
class S3Adapter(
    val region: String,
    val bucket: String,
   val s3Client: S3Client,
) : ObjectStoreAdapter {

    constructor(config: Config) : this(config.region, config.bucket, config.s3Client)

    override val provider: ObjectStoreProvider = ObjectStoreProvider.S3

    override suspend fun list(bucket: String, prefix: String, maxKeys: Int): ObjectListResult {
        val objects = s3Client.listObjectsV2(bucket, prefix).take(maxKeys)
        val rows = objects.map { obj ->
            ObjectStoreRowVec.s3(
                bucket = bucket,
                key = obj.key,
                byteSize = obj.size,
                contentType = null,
                etag = obj.etag,
                lastModified = obj.lastModified,
                versionId = null,
                metadata = obj.metadata,
                blob = null,
            )
        }
        return ObjectListResult(rows.size j { rows[it] })
    }

    override suspend fun get(bucket: String, key: String): ObjectStoreRowVec? {
        val obj = s3Client.getObject(bucket, key) ?: return null
        val blob = BlobRowVec(obj.bytes, obj.contentType)
        return ObjectStoreRowVec.s3(
            bucket = bucket,
            key = key,
            byteSize = obj.bytes.size.toLong(),
            contentType = obj.contentType,
            etag = obj.etag,
            lastModified = null,
            versionId = null,
            metadata = obj.metadata,
            blob = 1 j { blob },
        )
    }

    override suspend fun put(bucket: String, key: String, bytes: ByteArray, metadata: Map<String, String>?): Boolean {
        s3Client.putObject(bucket, key, bytes)
        return true
    }

    override suspend fun delete(bucket: String, key: String): Boolean {
        s3Client.deleteObject(bucket, key)
        return true
    }

    class Config(
        val region: String = "us-east-1",
        val bucket: String = "",
        val s3Client: S3Client,
    )

}
