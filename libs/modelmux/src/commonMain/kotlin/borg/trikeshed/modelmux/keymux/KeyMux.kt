package borg.trikeshed.modelmux.keymux

import borg.trikeshed.ccek.KeyedService
import kotlinx.coroutines.CoroutineContext

/**
 * ApiKey - Represents a single API key with quota tracking
 */
data class ApiKey(
    val id: String,
    val provider: String,
    val key: String,
    val quotaLimit: Long? = null,
    val quotaUsed: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val baseUrl: String? = null,
    val isFree: Boolean = false,
    val dailyLimit: Long? = null,
    val monthlyLimit: Long? = null,
) : KeyedService {

    companion object Key : CoroutineContext.Key<ApiKey>()

    override val key: CoroutineContext.Key<*> get() = Key

    /** Check if this key has quota available for the requested tokens */
    fun hasQuota(tokens: Long): Boolean {
        return quotaLimit == null || quotaUsed + tokens <= quotaLimit!!
    }

    /** Create a copy with updated quota usage */
    fun withQuotaUsed(newQuotaUsed: Long): ApiKey = copy(quotaUsed = newQuotaUsed)
}

/**
 * ProviderQuota - Tracks quota usage for a provider across timeframes
 */
data class ProviderQuota(
    val provider: String,
    var tokensUsedToday: Long = 0L,
    var tokensUsedThisHour: Long = 0L,
    var tokensUsedThisMonth: Long = 0L,
    var lastResetDay: Int = java.time.LocalDate.now().dayOfMonth,
    var lastResetHour: Int = java.time.LocalTime.now().hour,
    var lastResetMonth: Int = java.time.LocalDate.now().monthValue,
    val dailyLimit: Long? = null,
    val hourlyLimit: Long? = null,
    val monthlyLimit: Long? = null,
) {

    /** Check and reset timeframe counters if needed */
    fun checkAndReset() {
        val now = java.time.LocalDateTime.now()
        val currentDay = now.dayOfMonth
        val currentHour = now.hour
        val currentMonth = now.monthValue

        if (currentDay != lastResetDay) {
            tokensUsedToday = 0
            lastResetDay = currentDay
        }
        if (currentHour != lastResetHour) {
            tokensUsedThisHour = 0
            lastResetHour = currentHour
        }
        if (currentMonth != lastResetMonth) {
            tokensUsedThisMonth = 0
            lastResetMonth = currentMonth
        }
    }

    /** Check if provider has quota for tokens */
    fun hasQuota(tokens: Long): Boolean {
        checkAndReset()
        return (dailyLimit == null || tokensUsedToday + tokens <= dailyLimit!!) &&
               (hourlyLimit == null || tokensUsedThisHour + tokens <= hourlyLimit!!) &&
               (monthlyLimit == null || tokensUsedThisMonth + tokens <= monthlyLimit!!)
    }

    /** Record token usage */
    fun recordUsage(tokens: Long) {
        checkAndReset()
        tokensUsedToday += tokens
        tokensUsedThisHour += tokens
        tokensUsedThisMonth += tokens
    }

    /** Get estimated remaining quota for today */
    val estimatedRemainingToday: Long
        get() = dailyLimit?.let { max(0L, it - tokensUsedToday) } ?: Long.MAX_VALUE
}

/**
 * KeyStore - CCEK KeyedService managing all API keys
 */
class KeyStore(
    parentJob: kotlinx.coroutines.CompletableJob? = null
) : borg.trikeshed.context.AsyncContextElement(
    borg.trikeshed.context.ElementState.CREATED,
    parentJob
) {

    companion object Key : borg.trikeshed.context.AsyncContextKey<KeyStore>()

    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key

    private val keys = mutableMapOf<String, ApiKey>()
    private val quotas = mutableMapOf<String, ProviderQuota>()
    private val providerPriorities = mutableMapOf<String, Int>()
    private var initialized = false

    /** Initialize KeyStore from ModelMuxConfig */
    suspend fun initialize(config: borg.trikeshed.modelmux.config.ModelMuxConfig): KeyStore {
        requireState(borg.trikeshed.context.ElementState.CREATED)
        state = borg.trikeshed.context.ElementState.OPEN

        // Define provider specifications with aliases, priorities, and defaults
        val providerSpecs = listOf(
            ProviderSpec("kilo_code", 1, listOf("KILOCODE_API_KEY", "KILOAI_API_KEY", "KILO_CODE_API_KEY", "KILO_API_KEY"), "https://api.kilo.ai/api/gateway", true, 1_000_000L, 100_000L),
            ProviderSpec("moonshot", 2, listOf("MOONSHOTAI_API_KEY", "KIMI_API_KEY", "MOONSHOT_API_KEY"), "https://api.moonshot.cn/v1", true, 500_000L, 50_000L),
            ProviderSpec("deepseek", 3, listOf("DEEPSEEK_API_KEY"), "https://api.deepseek.com/v1", true, 500_000L, 50_000L),
            ProviderSpec("nvidia", 4, listOf("NVIDIA_API_KEY"), "https://integrate.api.nvidia.com/v1", true, 500_000L, 50_000L),
            ProviderSpec("zenmux", 5, listOf("ZENMUX_API_KEY"), "https://zenmux.ai/api/v1", true, 500_000L, 50_000L),
            ProviderSpec("opencode", 6, listOf("OPENCODE_API_KEY"), "https://api.opencode.ai", true, 250_000L, 25_000L),
            ProviderSpec("groq", 7, listOf("GROQ_API_KEY"), "https://api.groq.com/openai/v1", false, null, null),
            ProviderSpec("openai", 8, listOf("OPENAI_API_KEY"), "https://api.openai.com/v1", false, null, null),
            ProviderSpec("anthropic", 9, listOf("ANTHROPIC_API_KEY"), "https://api.anthropic.com/v1", false, null, null),
            ProviderSpec("openrouter", 10, listOf("OPENROUTER_API_KEY"), "https://openrouter.ai/api/v1", false, null, null),
            ProviderSpec("cerebras", 11, listOf("CEREBRAS_API_KEY"), "https://api.cerebras.ai/v1", false, null, null),
            ProviderSpec("xai", 12, listOf("XAI_API_KEY", "GROK_API_KEY"), "https://api.x.ai/v1", false, null, null),
            ProviderSpec("gemini", 13, listOf("GEMINI_API_KEY"), "https://generativelanguage.googleapis.com/v1beta", false, null, null),
            ProviderSpec("perplexity", 14, listOf("PERPLEXITY_API_KEY"), "https://api.perplexity.ai", false, null, null),
        )

        // Load keys from config
        for (spec in providerSpecs) {
            val keyEnv = spec.keyEnvs.firstOrNull { config.hasRealValue(it) }
            if (keyEnv != null) {
                val apiKey = config.getRequiredString(keyEnv)
                val baseUrl = config.getString("${spec.name.uppercase()}_BASE_URL") ?: spec.defaultBaseUrl

                val apiKeyObj = ApiKey(
                    id = "env-${spec.name}-1",
                    provider = spec.name,
                    key = apiKey,
                    quotaLimit = spec.dailyLimit,
                    baseUrl = baseUrl,
                    isFree = spec.isFree,
                    dailyLimit = spec.dailyLimit,
                    monthlyLimit = spec.monthlyLimit,
                )
                keys[spec.name] = apiKeyObj
                providerPriorities[spec.name] = spec.priority

                // Initialize quota tracking
                quotas[spec.name] = ProviderQuota(
                    provider = spec.name,
                    dailyLimit = spec.dailyLimit,
                    hourlyLimit = spec.hourlyLimit,
                    monthlyLimit = spec.monthlyLimit,
                )

                println("KeyStore: Loaded provider '${spec.name}' from $keyEnv")
            }
        }

        initialized = true
        state = borg.trikeshed.context.ElementState.ACTIVE
        return this
    }

    /** Get API key for a provider */
    fun getKey(provider: String): ApiKey? = keys[provider]

    /** Get all loaded providers */
    fun getProviders(): List<String> = keys.keys.toList()

    /** Get provider quota tracking */
    fun getQuota(provider: String): ProviderQuota? = quotas[provider]

    /** Get all quotas */
    fun getAllQuotas(): Map<String, ProviderQuota> = quotas.toMap()

    /** Check if provider has quota for tokens */
    fun hasQuota(provider: String, tokens: Long): Boolean {
        return quotas[provider]?.hasQuota(tokens) ?: false
    }

    /** Record token usage for a provider */
    fun recordUsage(provider: String, tokens: Long) {
        quotas[provider]?.recordUsage(tokens)
        keys[provider]?.let { key ->
            keys[provider] = key.withQuotaUsed(key.quotaUsed + tokens)
        }
    }

    /** Get best available provider for a request (lowest priority number = highest priority) */
    fun selectProvider(tokens: Long = 1000): String? {
        return keys.entries
            .filter { (provider, key) -> hasQuota(provider, tokens) }
            .minByOrNull { (provider, _) -> providerPriorities[provider] ?: Int.MAX_VALUE }
            ?.key
    }

    /** Get all providers sorted by priority */
    fun getProvidersByPriority(): List<String> {
        return keys.keys.sortedBy { providerPriorities[it] ?: Int.MAX_VALUE }
    }

    /** Export all keys (for backup/debug) */
    fun export(): Map<String, ApiKey> = keys.toMap()

    /** Get total number of loaded keys */
    val keyCount: Int get() = keys.size

    /** Check if initialized */
    val isInitialized: Boolean get() = initialized
}

/** Internal provider specification */
private data class ProviderSpec(
    val name: String,
    val priority: Int,
    val keyEnvs: List<String>,
    val defaultBaseUrl: String,
    val isFree: Boolean,
    val dailyLimit: Long?,
    val hourlyLimit: Long?,
)

/**
 * DSEL (Dynamic Service Eligibility Logic) - Quota-aware provider routing
 */
class DselRouter(
    parentJob: kotlinx.coroutines.CompletableJob? = null
) : borg.trikeshed.context.AsyncContextElement(
    borg.trikeshed.context.ElementState.CREATED,
    parentJob
) {

    companion object Key : borg.trikeshed.context.AsyncContextKey<DselRouter>()

    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key

    private val keyStore: KeyStore? = null
    private var initialized = false

    /** Initialize with KeyStore */
    suspend fun initialize(keyStore: KeyStore): DselRouter {
        requireState(borg.trikeshed.context.ElementState.CREATED)
        this.keyStore = keyStore
        initialized = true
        state = borg.trikeshed.context.ElementState.ACTIVE
        return this
    }

    /** Route a model ID to its provider */
    fun route(model: String): RouteResult? {
        checkInitialized()
        val prefix = model.split("/").firstOrNull() ?: return null

        // Exact provider match
        if (keyStore?.getKey(prefix) != null) {
            val key = keyStore!.getKey(prefix)!!
            return RouteResult(prefix, key.baseUrl ?: getDefaultBaseUrl(prefix), key.key)
        }

        // Slashed model but unknown prefix
        if (model.contains("/")) {
            return null
        }

        // Plain model name - use best available provider
        val bestProvider = keyStore?.selectProvider()
        if (bestProvider != null) {
            val key = keyStore!.getKey(bestProvider)!!
            return RouteResult(bestProvider, key.baseUrl ?: getDefaultBaseUrl(bestProvider), key.key)
        }

        return null
    }

    /** Get all available providers with quota status */
    fun getProviderStatus(): List<ProviderStatus> {
        checkInitialized()
        return keyStore?.keys.entries.map { (name, key) ->
            val quota = keyStore!.getQuota(name)
            ProviderStatus(
                name = name,
                baseUrl = key.baseUrl ?: getDefaultBaseUrl(name),
                hasKey = true,
                priority = keyStore?.providerPriorities[name] ?: Int.MAX_VALUE,
                isFree = key.isFree,
                quotaUsedToday = quota?.tokensUsedToday ?: 0L,
                quotaRemainingToday = quota?.estimatedRemainingToday ?: Long.MAX_VALUE,
            )
        }.sortedBy { it.priority } ?: emptyList()
    }

    private fun checkInitialized() {
        if (!initialized) {
            throw IllegalStateException("DselRouter not initialized. Call initialize() first.")
        }
    }

    private fun getDefaultBaseUrl(name: String): String = when (name) {
        "kilo_code" -> "https://api.kilo.ai/api/gateway"
        "moonshot" -> "https://api.moonshot.cn/v1"
        "deepseek" -> "https://api.deepseek.com/v1"
        "openai" -> "https://api.openai.com/v1"
        "anthropic" -> "https://api.anthropic.com/v1"
        "openrouter" -> "https://openrouter.ai/api/v1"
        "groq" -> "https://api.groq.com/openai/v1"
        "xai" -> "https://api.x.ai/v1"
        "cerebras" -> "https://api.cerebras.ai/v1"
        "nvidia" -> "https://integrate.api.nvidia.com/v1"
        "opencode" -> "https://api.opencode.ai"
        "zenmux" -> "https://zenmux.ai/api/v1"
        "perplexity" -> "https://api.perplexity.ai"
        "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
        else -> ""
    }

    companion object {
        fun create(keyStore: KeyStore, parentJob: kotlinx.coroutines.CompletableJob? = null): DselRouter {
            return DselRouter(parentJob)
        }
    }
}

/** Route result for a model request */
data class RouteResult(
    val provider: String,
    val baseUrl: String,
    val apiKey: String,
)

/** Provider status for monitoring */
data class ProviderStatus(
    val name: String,
    val baseUrl: String,
    val hasKey: Boolean,
    val priority: Int,
    val isFree: Boolean,
    val quotaUsedToday: Long,
    val quotaRemainingToday: Long,
)