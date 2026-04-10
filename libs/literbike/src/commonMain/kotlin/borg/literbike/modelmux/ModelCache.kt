package borg.literbike.modelmux

import kotlinx.serialization.Serializable
import kotlinx.datetime.Clock
import java.io.File

/**
 * Cached model information for ModelMux.
 * Ported from literbike/src/modelmux/cache.rs (CachedModel, CacheConfig, ModelCache).
 */
@Serializable
data class CachedModel(
    val id: String,
    val provider: String,
    val name: String,
    val contextWindow: Long,
    val maxTokens: Long,
    val inputCostPerMillion: Double,
    val outputCostPerMillion: Double,
    val isFree: Boolean,
    val supportsStreaming: Boolean = true,
    val supportsTools: Boolean = true,
    val cachedAt: Long,
    val expiresAt: Long? = null
) {
    fun isExpired(): Boolean {
        return expiresAt?.let { exp ->
            val now = nowSeconds()
            now > exp
        } ?: false
    }

    companion object {
        fun nowSeconds(): Long = Clock.System.now().epochSeconds
    }
}

/**
 * Model cache configuration.
 */
@Serializable
data class CacheConfig(
    val cacheDir: String,
    val maxAgeSecs: Long = 3600,
    val enableDiskCache: Boolean = true,
    val enableMemoryCache: Boolean = true
) {
    companion object {
        fun default(): CacheConfig {
            val home = System.getProperty("user.home") ?: "."
            return CacheConfig(
                cacheDir = "$home/.modelmux/cache",
                maxAgeSecs = 3600,
                enableDiskCache = true,
                enableMemoryCache = true
            )
        }
    }
}

/**
 * Model cache with memory and disk backing.
 * Port of ModelCache struct.
 */
class ModelCache(
    private val config: CacheConfig,
    private val memoryCache: MutableMap<String, CachedModel> = mutableMapOf(),
    private val modelsByProvider: MutableMap<String, MutableList<String>> = mutableMapOf()
) {
    companion object {
        fun withDefaults(): ModelCache = ModelCache(CacheConfig.default())

        fun empty(): ModelCache = ModelCache(
            config = CacheConfig.default(),
            memoryCache = mutableMapOf(),
            modelsByProvider = mutableMapOf()
        )
    }

    init {
        init()
    }

    private fun init() {
        if (config.enableDiskCache) {
            File(config.cacheDir).mkdirs()
            loadFromDisk()
        }
    }

    fun get(modelId: String): CachedModel? {
        return if (config.enableMemoryCache) {
            memoryCache[modelId]
        } else {
            loadFromDiskSingle(modelId)
        }
    }

    /** Find a model by exact id, or by suffix match */
    fun find(query: String): CachedModel? {
        get(query)?.let { return it }
        val suffix = "/$query"
        return memoryCache.values.find { m -> m.id.endsWith(suffix) || m.id == query }
    }

    fun getProviderModels(provider: String): List<CachedModel> {
        val modelIds = modelsByProvider[provider] ?: return emptyList()
        return modelIds.mapNotNull { id -> get(id) }
    }

    fun getAllModels(): List<CachedModel> = memoryCache.values.toList()

    fun cache(model: CachedModel) {
        val provider = model.provider
        val id = model.id

        if (config.enableMemoryCache) {
            memoryCache[id] = model
            modelsByProvider.getOrPut(provider) { mutableListOf() }.add(id)
        }

        if (config.enableDiskCache) {
            saveToDisk(id)
        }

        println("Cached model: $id")
    }

    fun cacheMany(models: List<CachedModel>) {
        models.forEach { cache(it) }
        println("Cached ${memoryCache.size} models")
    }

    fun clear() {
        memoryCache.clear()
        modelsByProvider.clear()
        if (config.enableDiskCache) {
            File(config.cacheDir).deleteRecursively()
            File(config.cacheDir).mkdirs()
        }
        println("Cleared model cache")
    }

    private fun cacheFilePath(modelId: String): String {
        val safeId = modelId.replace('/', '_').replace(':', '_')
        return "${config.cacheDir}/$safeId.json"
    }

    private fun saveToDisk(modelId: String) {
        memoryCache[modelId]?.let { model ->
            val path = cacheFilePath(modelId)
            File(path).writeText(kotlinx.serialization.json.Json.encodeToString(model))
        }
    }

    private fun loadFromDisk() {
        val cacheDir = File(config.cacheDir)
        if (!cacheDir.exists()) return

        cacheDir.listFiles()?.forEach { file ->
            if (file.extension == "json") {
                try {
                    val content = file.readText()
                    val model = kotlinx.serialization.json.Json.decodeFromString<CachedModel>(content)
                    if (!model.isExpired()) {
                        memoryCache[model.id] = model
                        modelsByProvider.getOrPut(model.provider) { mutableListOf() }.add(model.id)
                    }
                } catch (e: Exception) {
                    // Ignore corrupted cache entries
                }
            }
        }

        println("Loaded ${memoryCache.size} models from disk cache")
    }

    private fun loadFromDiskSingle(modelId: String): CachedModel? {
        val path = File(cacheFilePath(modelId))
        if (!path.exists()) return null
        return try {
            val content = path.readText()
            val model = kotlinx.serialization.json.Json.decodeFromString<CachedModel>(content)
            if (!model.isExpired()) model else null
        } catch (e: Exception) {
            null
        }
    }
}
