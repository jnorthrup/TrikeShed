package borg.trikeshed.miniduck.objectstore

import borg.trikeshed.miniduck.BlobRowVec
import borg.trikeshed.miniduck.ObjectStoreProvider
import borg.trikeshed.miniduck.ObjectStoreRowVec
import borg.trikeshed.lib.j

/**
 * JVM adapter for Alibaba Cloud OSS (Object Storage Service).
 *
 * The Config accepts either a real OSS client or a [FakeAlibabaOssClient] for testing.
 */
class AlibabaAdapter(
    val endpoint: String,
    val bucket: String,
   val ossClient: AlibabaOssClient,
) : ObjectStoreAdapter {

    constructor(config: Config) : this(config.endpoint, config.bucket, config.ossClient!!)

    override val provider: ObjectStoreProvider = ObjectStoreProvider.ALIBABA

    override suspend fun list(bucket: String, prefix: String, maxKeys: Int): ObjectListResult {
        val objects = ossClient.listObjects(prefix).take(maxKeys)
        val rows = objects.map { obj ->
            ObjectStoreRowVec.alibaba(
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
        val obj = ossClient.getObject(key) ?: return null
        val blob = BlobRowVec(obj.bytes, obj.contentType)
        return ObjectStoreRowVec.alibaba(
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
        ossClient.putObject(key, bytes)
        return true
    }

    override suspend fun delete(bucket: String, key: String): Boolean {
        ossClient.deleteObject(key)
        return true
    }

    class Config(
        val endpoint: String = "",
        val bucket: String = "",
        val ossClient: AlibabaOssClient? = null,
    )
}
