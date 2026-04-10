package borg.literbike.modelmux

import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

/**
 * Metamodel and BlobStore for model metadata and content-addressed storage.
 * Ported from literbike/src/modelmux/metamodel.rs.
 */

/**
 * Generic metadata about a model for routing/conversion.
 */
@Serializable
data class Metamodel(
    val id: String,
    val provider: String,
    val contextWindow: Long,
    val maxTokens: Long,
    val conversions: List<String> = emptyList(),
    val cachedAt: Long,
    val expiresAt: Long? = null
) {
    fun isExpired(): Boolean {
        return expiresAt?.let { exp ->
            CachedModel.nowSeconds() > exp
        } ?: false
    }
}

/**
 * Simple filesystem-backed blob store keyed by digest (filename-safe string).
 */
class BlobStore(private val baseDir: String) {
    init {
        File(baseDir).mkdirs()
    }

    private fun pathFor(key: String): String {
        val safe = key.replace('/', '_').replace(':', '_')
        return "$baseDir/$safe"
    }

    /** Write raw data into the store */
    fun put(key: String, data: ByteArray) {
        val path = File(pathFor(key))
        path.parentFile?.mkdirs()
        path.writeBytes(data)
    }

    /** Read raw data from the store */
    fun get(key: String): ByteArray? {
        val path = File(pathFor(key))
        return if (path.exists()) path.readBytes() else null
    }

    /** Write data under a content-addressed key. Returns the hex digest. */
    fun putCas(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(data)
        val key = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        put(key, data)
        return key
    }

    /** Delete an entry */
    fun delete(key: String) {
        File(pathFor(key)).delete()
    }
}

/**
 * Content-addressable cache for metamodels.
 */
class MetamodelCache(private val blobStore: BlobStore) {
    private val index = mutableMapOf<String, String>() // id -> digest

    companion object {
        fun new(baseDir: String): MetamodelCache {
            return MetamodelCache(BlobStore(baseDir))
        }
    }

    fun insert(meta: Metamodel): String {
        val bytes = kotlinx.serialization.json.Json.encodeToString(meta).toByteArray()
        val digest = blobStore.putCas(bytes)
        index[meta.id] = digest
        return digest
    }

    fun get(modelId: String): Metamodel? {
        val digest = index[modelId] ?: return null
        val data = blobStore.get(digest) ?: return null
        val m = kotlinx.serialization.json.Json.decodeFromString<Metamodel>(String(data))
        return if (!m.isExpired()) m else null
    }

    fun replicateAll() {
        // In a real implementation, this would replicate to IPFS/S3
        for ((modelId, digest) in index) {
            println("Replicating $modelId -> $digest")
        }
    }
}

/**
 * HuggingFace Model Card - enriched metadata.
 */
@Serializable
data class HfModelCard(
    val hfRepoId: String,
    val contextWindow: Long? = null,
    val paramCount: Long? = null,
    val tags: List<String> = emptyList(),
    val pipelineTag: String? = null,
    val downloads: Long = 0
)

/**
 * Disk-backed cache for HF model card results.
 */
class HfCardCache(private val dir: String) {
    init {
        File(dir).mkdirs()
    }

    private fun pathFor(modelId: String): String {
        val safe = modelId.replace('/', '_').replace(':', '_')
        return "$dir/$safe.json"
    }

    fun get(modelId: String): HfModelCard? {
        val path = File(pathFor(modelId))
        return if (path.exists()) {
            try {
                kotlinx.serialization.json.Json.decodeFromString(path.readText())
            } catch (e: Exception) {
                null
            }
        } else null
    }

    fun put(modelId: String, card: HfModelCard) {
        val json = kotlinx.serialization.json.Json.encodeToString(card)
        File(pathFor(modelId)).writeText(json)
    }

    fun has(modelId: String): Boolean = File(pathFor(modelId)).exists()
}

/**
 * Fetch HuggingFace model card metadata.
 * Simplified port - requires actual HTTP client in production.
 */
suspend fun fetchHfModelCard(
    client: Any, // placeholder for HTTP client
    modelName: String,
    timeoutMs: Long = 5000
): HfModelCard? {
    val shortName = stripProviderPrefix(modelName)
    if (shortName.isEmpty()) return null

    val hfToken = System.getenv("HUGGINGFACE_API_KEY")
        ?: System.getenv("HF_TOKEN")
        ?: ""

    // In a real implementation, this would use Ktor/OkHttp to fetch from HF API
    // For now, return null - the cache layer handles misses gracefully
    return null
}

/**
 * Strip provider prefixes from a model ID to get a searchable short name.
 */
fun stripProviderPrefix(modelName: String): String {
    return modelName.substringAfterLast('/', modelName)
}
