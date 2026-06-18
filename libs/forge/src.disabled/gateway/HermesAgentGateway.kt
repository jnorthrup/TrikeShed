@file:Suppress("unused")

package borg.trikeshed.forge.gateway

import borg.trikeshed.forge.platform.platformUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

/**
 * Model-Key Gateway -- borrows ONLY model data and credential keys from external
 * sources (Hermes .env, cloud provider APIs, etc.).
 *
 * This is NOT a task sync interface. The kanban board owns its own cards entirely.
 * This gateway exposes:
 *   - which models exist per provider
 *   - which API keys are available and their status
 *   - usage metrics (optional, for GEPA optimization)
 *
 * No task data crosses this boundary.
 */
interface ModelKeyGateway {
    val protocol: GatewayProtocol
    val endpoint: String

    /** Fetch available models for a provider. */
    suspend fun fetchModels(provider: String): List<ModelEntry>

    /** Fetch credential keys (flat data, read from .env or equivalent). */
    suspend fun fetchKeys(): List<KeyEntry>

    /** Fetch usage metrics for optimization (optional). */
    suspend fun fetchUsage(): UsageMetrics?

    /** Check gateway health. */
    suspend fun health(): GatewayHealth
}

enum class GatewayProtocol { HTTP, WS, IPC, IPFS, GIT_CAS, ENV_FILE }

@Serializable
data class ModelEntry(
    val id: String,
    val provider: String,
    val name: String,
    val contextWindow: Int = 0,
    val available: Boolean = true,
)

@Serializable
data class KeyEntry(
    val keyId: String,
    val provider: String,
    val baseUrl: String = "",
    val label: String = "",
    val free: Boolean = false,
    val priority: Int = 0,
)

@Serializable
data class UsageMetrics(
    val requestsByProvider: Map<String, Long> = emptyMap(),
    val errorsByProvider: Map<String, Long> = emptyMap(),
    val avgLatencyByProvider: Map<String, Double> = emptyMap(),
    val timestampMs: Long = platformUtils.currentTimeMillis(),
)

@Serializable
data class GatewayHealth(
    val alive: Boolean,
    val protocol: String,
    val latencyMs: Long,
    val message: String? = null,
)

// ---------------------------------------------------------------------------
// Content-addressed storage (IPFS / git CAS)
// ---------------------------------------------------------------------------

/**
 * Content-addressed store for kanban board checkpoints.
 * Board state is serialized and put into CAS; the CID becomes
 * the board's distributed identity across nodes.
 */
interface ContentAddressedStore {
    suspend fun put(data: ByteArray): String
    suspend fun get(cid: String): ByteArray?
    suspend fun has(cid: String): Boolean
    suspend fun pin(cid: String): Boolean
    suspend fun pinned(): List<String>
}

class MemoryCAS : ContentAddressedStore {
    private val store = mutableMapOf<String, ByteArray>()
    private val pinnedSet = mutableSetOf<String>()

    private fun hash(data: ByteArray): String {
        var h = 0L
        for (b in data) { h = h * 31L + (b.toInt() and 0xFF) }
        return "bafy${h.toString(16).padStart(32, '0')}"
    }

    override suspend fun put(data: ByteArray): String {
        val cid = hash(data)
        store[cid] = data
        return cid
    }
    override suspend fun get(cid: String): ByteArray? = store[cid]
    override suspend fun has(cid: String): Boolean = cid in store
    override suspend fun pin(cid: String): Boolean { pinnedSet.add(cid); return cid in store }
    override suspend fun pinned(): List<String> = pinnedSet.toList()
}

// ---------------------------------------------------------------------------
// EnvFile gateway -- reads Hermes .env as flat key/model data
// ---------------------------------------------------------------------------

/**
 * Reads provider keys from a flat env file (e.g. Hermes config .env).
 * Parses KEY=value lines into [KeyEntry] list. No Hermes code imported.
 */
class EnvFileGateway(
    private val envPath: String,
) : ModelKeyGateway {
    override val protocol = GatewayProtocol.ENV_FILE
    override val endpoint = envPath

    private var cache: Pair<Long, List<KeyEntry>>? = null

    override suspend fun fetchKeys(): List<KeyEntry> {
        val now = platformUtils.currentTimeMillis()
        cache?.let { (ts, keys) -> if (now - ts < 60_000) return keys }

        val keys = mutableListOf<KeyEntry>()
        val providerMap = mutableMapOf<String, String>()  // env prefix -> provider

        try {
            val lines = readEnvFile(envPath)
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val eq = trimmed.indexOf('=')
                if (eq < 0) continue
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim().trim('"').trim('\'')

                // Match patterns like: OPENAI_API_KEY=..., OPENROUTER_API_KEY=...
                if (key.endsWith("_API_KEY") || key.endsWith("_KEY")) {
                    val provider = inferProvider(key)
                    keys.add(KeyEntry(
                        keyId = key.lowercase(),
                        provider = provider,
                        baseUrl = "",
                        label = key,
                        free = isFreeProvider(provider),
                        priority = keys.size + 1,
                    ))
                    providerMap[key] = provider
                }
            }
        } catch (_: Exception) {}

        cache = now to keys
        return keys
    }

    override suspend fun fetchModels(provider: String): List<ModelEntry> {
        // Models are fetched lazily via the provider's /models endpoint
        // at runtime by the kanban. The gateway provides key data only.
        return emptyList()
    }

    override suspend fun fetchUsage(): UsageMetrics? = null

    override suspend fun health(): GatewayHealth = GatewayHealth(
        alive = cache != null,
        protocol = "ENV_FILE",
        latencyMs = 0,
    )
}

private fun inferProvider(envKey: String): String {
    val upper = envKey.uppercase()
    for (p in PROVIDER_PATTERNS) {
        if (upper.contains(p.first)) return p.second
    }
    return envKey.substringBefore("_").lowercase()
}

private val PROVIDER_PATTERNS = listOf(
    "OPENAI" to "openai",
    "OPENROUTER" to "openrouter",
    "ANTHROPIC" to "anthropic",
    "GROQ" to "groq",
    "NVIDIA" to "nvidia",
    "XAI" to "xai",
    "GEMINI" to "gemini",
    "GOOGLE" to "gemini",
    "DEEPSEEK" to "deepseek",
    "MOONSHOT" to "moonshot",
    "CEREBRAS" to "cerebras",
    "MINIMAX" to "minimax",
    "PERPLEXITY" to "perplexity",
    "ZAI" to "zai",
    "ZENMUX" to "zenmux",
    "KILO" to "kilo_code",
    "OPENCODE" to "opencode",
)

private fun isFreeProvider(provider: String): Boolean = provider in FREE_PROVIDERS

private val FREE_PROVIDERS = setOf(
    "kilo_code", "moonshot", "deepseek", "nvidia", "zenmux", "opencode"
)

/** expect/actual for file reading -- JVM reads filesystem, JS uses fetch, etc. */
internal expect fun readEnvFile(path: String): List<String>
