package borg.trikeshed.miniduck.objectstore

import borg.trikeshed.miniduck.objectstore.ObjectStoreAdapter.S3Client
import borg.trikeshed.miniduck.objectstore.ObjectStoreAdapter.GcsStorageClient
import borg.trikeshed.miniduck.objectstore.ObjectStoreAdapter.AlibabaOssClient
import software.amazon.awssdk.services.s3.S3Client as AwsS3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.ResponseBytes
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.aliyun.oss.OSS
import com.aliyun.oss.OSSClientBuilder
import com.aliyun.oss.model.OSSObject
import com.aliyun.oss.model.ListObjectsRequest
import com.aliyun.oss.model.OSSObjectSummary
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** AWS S3 Adapter — wraps AWS SDK v2 */
class S3Adapter(
    private val client: AwsS3Client,
    private val defaultBucket: String,
) : ObjectStoreAdapter {

    override val provider: ObjectStoreProvider = ObjectStoreProvider.S3

    override suspend fun list(bucket: String, prefix: String, maxKeys: Int): ObjectListResult {
        return withContext(Dispatchers.IO) {
            val req = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .maxKeys(maxKeys)
                .build()

            val objects = mutableListOf<ObjectStoreRowVec>()
            val iterable = ListObjectsV2Iterable.create(client, req)
            for (page in iterable) {
                for (obj in page.contents()) {
                    objects.add(toRowVec(bucket, obj.key(), obj.size(), obj.eTag()))
                    if (objects.size >= maxKeys) break
                }
                if (objects.size >= maxKeys) break
            }
            ObjectListResult(objects.asSeries())
        }
    }

    override suspend fun get(bucket: String, key: String): ObjectStoreRowVec? {
        return withContext(Dispatchers.IO) {
            val req = GetObjectRequest.builder().bucket(bucket).key(key).build()
            val response: ResponseBytes<GetObjectResponse> = client.getObject(req).await()
            toRowVec(bucket, key, response.asByteArray().toLong(), response.sdkHttpResponse().eTag())
        }
    }

    override suspend fun put(bucket: String, key: String, bytes: ByteArray, metadata: Map<String, String>?): Boolean {
        return withContext(Dispatchers.IO) {
            val req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build()
            client.putObject(req, RequestBody.fromBytes(bytes)).await()
            true
        }
    }

    override suspend fun delete(bucket: String, key: String): Boolean {
        return withContext(Dispatchers.IO) {
            val req = DeleteObjectRequest.builder().bucket(bucket).key(key).build()
            client.deleteObject(req).await()
            true
        }
    }

    private fun toRowVec(bucket: String, key: String, size: Long, etag: String?): ObjectStoreRowVec {
        return ObjectStoreRowVec(
            bucket = bucket,
            key = key,
            byteSize = size,
            contentType = metadata?.get("content-type"),
            etag = etag?.trim { it == '"' },
            lastModified = null,
            versionId = null,
            metadata = metadata,
            blob = s_ [BlobRowVec(ByteArray(0), metadata?.get("content-type"))],
        )
    }
}

/** Google Cloud Storage Adapter */
class GcsAdapter(
    private val storage: Storage = StorageOptions.getDefaultInstance().service,
) : ObjectStoreAdapter {

    override val provider: ObjectStoreProvider = ObjectStoreProvider.GCS

    override suspend fun list(bucket: String, prefix: String, maxKeys: Int): ObjectListResult {
        return withContext(Dispatchers.IO) {
            val page = storage.list(bucket, Storage.BlobListOption.prefix(prefix), Storage.BlobListOption.maxResults(maxKeys))
            val objects = page.iterateAll().map { blob ->
                toRowVec(bucket, blob)
            }.take(maxKeys)
            ObjectListResult(objects.asSeries())
        }
    }

    override suspend fun get(bucket: String, key: String): ObjectStoreRowVec? {
        return withContext(Dispatchers.IO) {
            val blob = storage.get(BlobId.of(bucket, key)) ?: return@withContext null
            toRowVec(bucket, blob)
        }
    }

    override suspend fun put(bucket: String, key: String, bytes: ByteArray, metadata: Map<String, String>?): Boolean {
        return withContext(Dispatchers.IO) {
            val blobId = BlobId.of(bucket, key)
            var blobInfo = storage.create(blobId, bytes)
            metadata?.let { m ->
                blobInfo = blobInfo.toBuilder().setMetadata(m).build()
                storage.update(blobInfo)
            }
            true
        }
    }

    override suspend fun delete(bucket: String, key: String): Boolean {
        return withContext(Dispatchers.IO) {
            storage.delete(bucket, key)
            true
        }
    }

    private fun toRowVec(bucket: String, blob: Blob): ObjectStoreRowVec {
        val content = blob.content ?: blob.content()
        return ObjectStoreRowVec(
            bucket = bucket,
            key = blob.name,
            byteSize = blob.size,
            contentType = blob.contentType,
            etag = blob.etag?.trim { it == '"' },
            lastModified = blob.updateTime?.toString(),
            versionId = blob.generation?.toString(),
            metadata = blob.metadata,
            blob = s_ [BlobRowVec(content, blob.contentType)],
        )
    }
}

/** Alibaba Cloud OSS Adapter */
class AlibabaAdapter(
    private val client: OSS,
    private val defaultBucket: String,
) : ObjectStoreAdapter {

    override val provider: ObjectStoreProvider = ObjectStoreProvider.ALIBABA

    override suspend fun list(bucket: String, prefix: String, maxKeys: Int): ObjectListResult {
        return withContext(Dispatchers.IO) {
            val req = ListObjectsRequest(bucket).apply { this.prefix = prefix; this.maxResults = maxKeys }
            val result = client.listObjects(req)
            val objects = result.objectSummaries.map { summary ->
                toRowVec(bucket, summary)
            }
            ObjectListResult(objects.asSeries())
        }
    }

    override suspend fun get(bucket: String, key: String): ObjectStoreRowVec? {
        return withContext(Dispatchers.IO) {
            val obj: OSSObject? = client.getObject(bucket, key) ?: return@withContext null
            val baos = ByteArrayOutputStream()
            obj.objectContent.readAllBytes().also { baos.write(it) }
            toRowVec(bucket, key, baos.toByteArray(), obj.objectMetadata)
        }
    }

    override suspend fun put(bucket: String, key: String, bytes: ByteArray, metadata: Map<String, String>?): Boolean {
        return withContext(Dispatchers.IO) {
            val input = ByteArrayInputStream(bytes)
            val meta = com.aliyun.oss.model.ObjectMetadata()
            metadata?.forEach { (k, v) -> meta.addUserMetadata(k, v) }
            client.putObject(bucket, key, input, meta)
            true
        }
    }

    override suspend fun delete(bucket: String, key: String): Boolean {
        return withContext(Dispatchers.IO) {
            client.deleteObject(bucket, key)
            true
        }
    }

    private fun toRowVec(bucket: String, summary: OSSObjectSummary): ObjectStoreRowVec {
        return ObjectStoreRowVec(
            bucket = bucket,
            key = summary.key,
            byteSize = summary.size,
            contentType = null,
            etag = summary.eTag?.trim { it == '"' },
            lastModified = summary.lastModified?.toString(),
            versionId = null,
            metadata = emptyMap(),
            blob = s_ [BlobRowVec(ByteArray(0), null)],
        )
    }

    private fun toRowVec(bucket: String, key: String, bytes: ByteArray, meta: com.aliyun.oss.model.ObjectMetadata?): ObjectStoreRowVec {
        val metadata = mutableMapOf<String, String>()
        meta?.userMetadata?.forEach { (k, v) -> metadata[k] = v }
        return ObjectStoreRowVec(
            bucket = bucket,
            key = key,
            byteSize = bytes.size.toLong(),
            contentType = meta?.contentType,
            etag = meta?.eTag?.trim { it == '"' },
            lastModified = meta?.lastModified?.toString(),
            versionId = null,
            metadata = metadata,
            blob = s_ [BlobRowVec(bytes, meta?.contentType)],
        )
    }
}

/** Factory for creating ObjectStorageBlockStore with real adapters */
object ObjectStorageBlockStoreFactory {

    @JvmStatic
    fun createS3(bucket: String, region: String = "us-east-1", ccekBus: CCEKBus = CCEKBus.NoOp): ObjectStorageBlockStore {
        val client = AwsS3Client.builder().region(software.amazon.awssdk.regions.Region.of(region)).build()
        val adapter = S3Adapter(client, bucket)
        return ObjectStorageBlockStore(adapter, bucket, ccekBus)
    }

    @JvmStatic
    fun createGcs(bucket: String, ccekBus: CCEKBus = CCEKBus.NoOp): ObjectStorageBlockStore {
        val adapter = GcsAdapter()
        return ObjectStorageBlockStore(adapter, bucket, ccekBus)
    }

    @JvmStatic
    fun createAlibaba(bucket: String, endpoint: String, accessKey: String, secretKey: String, ccekBus: CCEKBus = CCEKBus.NoOp): ObjectStorageBlockStore {
        val client = OSSClientBuilder().build(endpoint, accessKey, secretKey)
        val adapter = AlibabaAdapter(client, bucket)
        return ObjectStorageBlockStore(adapter, bucket, ccekBus)
    }
}

/** Series.asSeries() extension — converts ObjectStoreRowVec list to lazy Series */
fun List<ObjectStoreRowVec>.asSeries(): Series<ObjectStoreRowVec> = size j { this[it] }