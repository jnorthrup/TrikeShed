package borg.literbike.modelmux

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.io.File

/**
 * Model Proxy configuration and state.
 * Ported from literbike/src/modelmux/proxy.rs (ProxyConfig, ProxyRoute, ModelProxy subset).
 * Note: Full proxy requires HTTP client and async runtime - this provides config + core types.
 */

/**
 * Proxy configuration.
 */
@Serializable
data class ProxyConfig(
    val bindAddress: String = "0.0.0.0",
    val port: Int = 11434,
    val enableStreaming: Boolean = true,
    val enableCaching: Boolean = true,
    val defaultModel: String? = null,
    val fallbackModel: String? = null,
    val requestTimeoutSecs: Long = 120,
    val maxRetries: Int = 2
)

/**
 * Proxy route configuration.
 */
@Serializable
data class ProxyRoute(
    val path: String,
    val method: String,
    val handler: String,
    val providers: List<String>
)

/**
 * Proxy error types.
 */
sealed class ProxyError : Exception() {
    data class UpstreamError(val context: String) : ProxyError()
    data class ModelNotFound(val modelId: String) : ProxyError()
    data class ProviderUnavailable(val provider: String) : ProxyError()
    data class ConfigError(val message: String) : ProxyError()
}

/**
 * Model proxy - minimal configuration holder.
 * Full implementation requires Ktor/OkHttp client and async runtime.
 * Port of ModelProxy struct (config and initialization logic only).
 */
class ModelProxy(
    var config: ProxyConfig,
    val registry: ModelRegistry = ModelRegistry(),
    val cache: ModelCache = ModelCache.withDefaults()
) {
    companion object {
        fun new(config: ProxyConfig): ModelProxy = ModelProxy(config)
    }

    /** Initialize proxy from .env file and environment */
    suspend fun initFromEnv(envPath: String?): Result<Unit> {
        // Load .env file if specified
        envPath?.let { loadEnvFile(it) }

        // Load models from cache
        loadCachedModels()

        // Pick up default/fallback model from env
        if (config.defaultModel == null) {
            config = config.copy(
                defaultModel = envString("MODELMUX_DEFAULT_MODEL")
            )
        }
        if (config.fallbackModel == null) {
            config = config.copy(
                fallbackModel = envString("MODELMUX_FALLBACK_MODEL")
            )
        }

        println("ModelProxy initialized from env")
        config.defaultModel?.let { println("Default model: $it") }
        return Result.success(Unit)
    }

    private fun loadEnvFile(path: String): Result<Unit> {
        return runCatching {
            val content = File(path).readText()
            for (line in content.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
                val eq = trimmed.indexOf('=')
                if (eq < 0) continue
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim().trim('"', '\'')
                System.setProperty(key, value)
            }
        }
    }

    private fun loadCachedModels() {
        val count = cache.getAllModels().size
        if (count > 0) {
            println("Loaded $count models from disk cache")
        } else {
            println("Cache empty -- draw-through will fetch from providers on first request")
        }
    }

    private fun envString(key: String): String? {
        val value = System.getenv(key)
        return value?.takeIf { it.isNotBlank() }
    }

    /**
     * Estimate parameter size from model name.
     * Works across thousands of models by regex-matching common naming conventions.
     */
    fun estimateParamSize(name: String): String {
        val lower = name.lowercase()
        var best: Pair<Int, Long>? = null // (position, size)
        var i = 0
        while (i < lower.length) {
            if (lower[i].isDigit()) {
                val start = i
                while (i < lower.length && (lower[i].isDigit() || lower[i] == '.')) {
                    i++
                }
                if (i < lower.length && lower[i] == 'b' &&
                    (i + 1 >= lower.length || !lower[i + 1].isLetter())
                ) {
                    val n = lower.substring(start, i).toDoubleOrNull()
                    if (n != null && n > 0) {
                        val size = n.toLong()
                        if (best == null || size > best.second) {
                            best = start to size
                        }
                    }
                }
            }
            i++
        }
        return best?.let { "${it.second}B" } ?: "unknown"
    }
}
