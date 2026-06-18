@file:Suppress("unused")

package borg.trikeshed.forge.gateway

import borg.trikeshed.forge.platform.platformUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

interface ModelKeyGateway {
    val protocol: GatewayProtocol
    val endpoint: String
    suspend fun fetchModels(provider: String): List<ModelEntry>
    suspend fun fetchKeys(): List<KeyEntry>
    suspend fun fetchUsage(): UsageMetrics?
    suspend fun health(): GatewayHealth
}

enum class GatewayProtocol { HTTP, WS, IPC, IPFS, GIT_CAS, ENV_FILE }

@Serializable data class ModelEntry(val id: String, val provider: String, val name: String, val contextWindow: Int = 0, val available: Boolean = true)
@Serializable data class KeyEntry(val keyId: String, val provider: String, val baseUrl: String = "", val label: String = "", val free: Boolean = false, val priority: Int = 0)
@Serializable data class UsageMetrics(val requestsByProvider:Map<String,Long>=emptyMap(), val errorsByProvider:Map<String,Long>=emptyMap(), val avgLatencyByProvider:Map<String,Double>=emptyMap(), val timestampMs:Long=platformUtils.currentTimeMillis())
@Serializable data class GatewayHealth(val alive:Boolean, val protocol:String, val latencyMs:Long, val message:String?=null)

interface ContentAddressedStore {
    suspend fun put(data:ByteArray): String
    suspend fun get(cid:String): ByteArray?
    suspend fun has(cid:String): Boolean
    suspend fun pin(cid:String): Boolean
    suspend fun pinned(): List<String>
}

class MemoryCAS : ContentAddressedStore {
    private val store = mutableMapOf<String, ByteArray>(); private val pinnedSet = mutableSetOf<String>()
    private fun hash(data:ByteArray): String { var h=0L; for(b in data){ h = h * 31L + (b.toInt() and 0xFF) }; return "bafy${h.toString(16).padStart(32,'0')}" }
    override suspend fun put(data:ByteArray): String { val cid=hash(data); store[cid]=data; return cid }
    override suspend fun get(cid:String): ByteArray? = store[cid]
    override suspend fun has(cid:String): Boolean = cid in store
    override suspend fun pin(cid:String): Boolean { pinnedSet.add(cid); return cid in store }
    override suspend fun pinned(): List<String> = pinnedSet.toList()
}

class EnvFileGateway(private val envPath: String) : ModelKeyGateway {
    override val protocol = GatewayProtocol.ENV_FILE; override val endpoint = envPath
    private var cache: Pair<Long, List<KeyEntry>>? = null

    override suspend fun fetchKeys(): List<KeyEntry> {
        val now = platformUtils.currentTimeMillis()
        cache?.let { (ts, keys) -> if (now - ts < 60_000) return keys }
        val keys = mutableListOf<KeyEntry>()
        try {
            for (line in readEnvFile(envPath)) {
                val t = line.trim(); if (t.isEmpty() || t.startsWith("#")) continue
                val eq = t.indexOf('='); if (eq < 0) continue
                val key = t.substring(0, eq).trim(); val value = t.substring(eq+1).trim().trim('"').trim('\'')
                if (key.endsWith("_API_KEY") || key.endsWith("_KEY")) {
                    val provider = inferProvider(key)
                    keys.add(KeyEntry(key.lowercase(), provider, "", key, isFreeProvider(provider), keys.size + 1))
                }
            }
        } catch (_: Exception) {}
        cache = now to keys; return keys
    }
    override suspend fun fetchModels(provider:String): List<ModelEntry> = emptyList()
    override suspend fun fetchUsage(): UsageMetrics? = null
    override suspend fun health(): GatewayHealth = GatewayHealth(cache != null, "ENV_FILE", 0)
}

private fun inferProvider(envKey:String): String {
    val upper = envKey.uppercase()
    for ((pattern, provider) in PROVIDER_PATTERNS) if (upper.contains(pattern)) return provider
    return envKey.substringBefore("_").lowercase()
}

private val PROVIDER_PATTERNS = listOf(
    "OPENAI" to "openai", "OPENROUTER" to "openrouter", "ANTHROPIC" to "anthropic",
    "GROQ" to "groq", "NVIDIA" to "nvidia", "XAI" to "xai", "GEMINI" to "gemini",
    "GOOGLE" to "gemini", "DEEPSEEK" to "deepseek", "MOONSHOT" to "moonshot",
    "CEREBRAS" to "cerebras", "MINIMAX" to "minimax", "PERPLEXITY" to "perplexity",
    "ZAI" to "zai", "ZENMUX" to "zenmux", "KILO" to "kilo_code", "OPENCODE" to "opencode",
)
private val FREE_PROVIDERS = setOf("kilo_code","moonshot","deepseek","nvidia","zenmux","opencode")
private fun isFreeProvider(p:String) = p in FREE_PROVIDERS

internal expect fun readEnvFile(path:String): List<String>
