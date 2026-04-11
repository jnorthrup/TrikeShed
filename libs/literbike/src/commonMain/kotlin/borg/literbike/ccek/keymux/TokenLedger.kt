package borg.literbike.ccek.keymux

/**
 * Token Ledger Manager for Provider API Tracking
 *
 * Tracks token usage for specific providers (kilo code, opencode, openrouter, nvidia)
 * with vague quota estimation and API checks.
 */

/**
 * Manager for tracking token usage across providers
 */
class TokenLedgerManager {
    private val ledgers = mutableMapOf<String, ProviderTokenLedger>()
    var providerConfigs = ProviderConfigs(
        kiloCode = null,
        opencode = null,
        openrouter = null,
        nvidia = null,
        moonshot = null,
        groq = null,
        xai = null,
        cerebras = null
    )

    companion object {
        private fun currentTimestamp(): Long = Clocks.System.now() / 1000
    }

    /**
     * Initialize provider configurations
     */
    fun initializeProviders(apiKeys: Map<String, String>) {
        // Kilo Code Configuration
        apiKeys["kilo_code"]?.let { apiKey ->
            providerConfigs = providerConfigs.copy(
                kiloCode = KiloCodeConfig(
                    apiKey = apiKey,
                    baseUrl = "https://api.kilocode.ai",
                    estimatedDailyLimit = 1_000_000uL,
                    apiCheckInterval = 3600uL,
                    lastApiCheck = 0,
                    currentLedger = ProviderTokenLedger(
                        providerName = "kilo_code",
                        totalTokensUsed = 0uL,
                        tokensUsedToday = 0uL,
                        tokensUsedThisHour = 0uL,
                        apiStatus = ProviderApiStatus.Unknown,
                        vagueQuotaRemaining = VagueQuota(
                            estimatedRemaining = 1_000_000uL,
                            confidence = 0.7,
                            lastUpdated = currentTimestamp(),
                            source = QuotaSource.ManualConfiguration
                        )
                    )
                )
            )
        }

        // OpenCode Configuration
        apiKeys["opencode"]?.let { apiKey ->
            providerConfigs = providerConfigs.copy(
                opencode = OpenCodeConfig(
                    apiKey = apiKey,
                    baseUrl = "https://api.opencode.ai",
                    estimatedDailyLimit = 500_000uL,
                    apiCheckInterval = 3600uL,
                    lastApiCheck = 0,
                    currentLedger = ProviderTokenLedger(
                        providerName = "opencode",
                        totalTokensUsed = 0uL,
                        tokensUsedToday = 0uL,
                        tokensUsedThisHour = 0uL,
                        apiStatus = ProviderApiStatus.Unknown,
                        vagueQuotaRemaining = VagueQuota(
                            estimatedRemaining = 500_000uL,
                            confidence = 0.6,
                            lastUpdated = currentTimestamp(),
                            source = QuotaSource.ManualConfiguration
                        )
                    )
                )
            )
        }

        // OpenRouter Configuration
        apiKeys["openrouter"]?.let { apiKey ->
            providerConfigs = providerConfigs.copy(
                openrouter = OpenRouterConfig(
                    apiKey = apiKey,
                    baseUrl = "https://openrouter.ai/api/v1",
                    estimatedDailyLimit = 2_000_000uL,
                    apiCheckInterval = 1800uL,
                    lastApiCheck = 0,
                    currentLedger = ProviderTokenLedger(
                        providerName = "openrouter",
                        totalTokensUsed = 0uL,
                        tokensUsedToday = 0uL,
                        tokensUsedThisHour = 0uL,
                        apiStatus = ProviderApiStatus.Unknown,
                        vagueQuotaRemaining = VagueQuota(
                            estimatedRemaining = 2_000_000uL,
                            confidence = 0.8,
                            lastUpdated = currentTimestamp(),
                            source = QuotaSource.ManualConfiguration
                        )
                    )
                )
            )
        }

        // NVIDIA Configuration
        apiKeys["nvidia"]?.let { apiKey ->
            providerConfigs = providerConfigs.copy(
                nvidia = NvidiaConfig(
                    apiKey = apiKey,
                    baseUrl = "https://api.nvidia.com/v1",
                    estimatedDailyLimit = 3_000_000uL,
                    apiCheckInterval = 7200uL,
                    lastApiCheck = 0,
                    currentLedger = ProviderTokenLedger(
                        providerName = "nvidia",
                        totalTokensUsed = 0uL,
                        tokensUsedToday = 0uL,
                        tokensUsedThisHour = 0uL,
                        apiStatus = ProviderApiStatus.Unknown,
                        vagueQuotaRemaining = VagueQuota(
                            estimatedRemaining = 3_000_000uL,
                            confidence = 0.9,
                            lastUpdated = currentTimestamp(),
                            source = QuotaSource.ManualConfiguration
                        )
                    )
                )
            )
        }

        // Moonshot (Kimi) Configuration
        apiKeys["moonshot"]?.let { apiKey ->
            providerConfigs = providerConfigs.copy(
                moonshot = MoonshotConfig(
                    apiKey = apiKey,
                    baseUrl = "https://api.moonshot.cn/v1",
                    estimatedDailyLimit = 1_500_000uL,
                    apiCheckInterval = 3600uL,
                    lastApiCheck = 0,
                    currentLedger = ProviderTokenLedger(
                        providerName = "moonshot",
                        totalTokensUsed = 0uL,
                        tokensUsedToday = 0uL,
                        tokensUsedThisHour = 0uL,
                        apiStatus = ProviderApiStatus.Unknown,
                        vagueQuotaRemaining = VagueQuota(
                            estimatedRemaining = 1_500_000uL,
                            confidence = 0.7,
                            lastUpdated = currentTimestamp(),
                            source = QuotaSource.ManualConfiguration
                        )
                    )
                )
            )
        }

        // Groq Configuration
        apiKeys["groq"]?.let { apiKey ->
            providerConfigs = providerConfigs.copy(
                groq = GroqConfig(
                    apiKey = apiKey,
                    baseUrl = "https://api.groq.com/openai/v1",
                    estimatedDailyLimit = 2_000_000uL,
                    apiCheckInterval = 3600uL,
                    lastApiCheck = 0,
                    currentLedger = ProviderTokenLedger(
                        providerName = "groq",
                        totalTokensUsed = 0uL,
                        tokensUsedToday = 0uL,
                        tokensUsedThisHour = 0uL,
                        apiStatus = ProviderApiStatus.Unknown,
                        vagueQuotaRemaining = VagueQuota(
                            estimatedRemaining = 2_000_000uL,
                            confidence = 0.8,
                            lastUpdated = currentTimestamp(),
                            source = QuotaSource.ManualConfiguration
                        )
                    )
                )
            )
        }

        // xAI (Grok) Configuration
        apiKeys["xai"]?.let { apiKey ->
            providerConfigs = providerConfigs.copy(
                xai = XAIConfig(
                    apiKey = apiKey,
                    baseUrl = "https://api.x.ai/v1",
                    estimatedDailyLimit = 1_500_000uL,
                    apiCheckInterval = 3600uL,
                    lastApiCheck = 0,
                    currentLedger = ProviderTokenLedger(
                        providerName = "xai",
                        totalTokensUsed = 0uL,
                        tokensUsedToday = 0uL,
                        tokensUsedThisHour = 0uL,
                        apiStatus = ProviderApiStatus.Unknown,
                        vagueQuotaRemaining = VagueQuota(
                            estimatedRemaining = 1_500_000uL,
                            confidence = 0.7,
                            lastUpdated = currentTimestamp(),
                            source = QuotaSource.ManualConfiguration
                        )
                    )
                )
            )
        }

        // Cerebras Configuration
        apiKeys["cerebras"]?.let { apiKey ->
            providerConfigs = providerConfigs.copy(
                cerebras = CerebrasConfig(
                    apiKey = apiKey,
                    baseUrl = "https://api.cerebras.ai/v1",
                    estimatedDailyLimit = 2_000_000uL,
                    apiCheckInterval = 3600uL,
                    lastApiCheck = 0,
                    currentLedger = ProviderTokenLedger(
                        providerName = "cerebras",
                        totalTokensUsed = 0uL,
                        tokensUsedToday = 0uL,
                        tokensUsedThisHour = 0uL,
                        apiStatus = ProviderApiStatus.Unknown,
                        vagueQuotaRemaining = VagueQuota(
                            estimatedRemaining = 2_000_000uL,
                            confidence = 0.8,
                            lastUpdated = currentTimestamp(),
                            source = QuotaSource.ManualConfiguration
                        )
                    )
                )
            )
        }
    }

    /**
     * Track token usage for a provider
     */
    fun trackUsage(provider: String, tokens: ULong): Result<Unit> {
        val ledger = ledgers.getOrPut(provider) {
            ProviderTokenLedger(
                providerName = provider,
                totalTokensUsed = 0uL,
                tokensUsedToday = 0uL,
                tokensUsedThisHour = 0uL,
                apiStatus = ProviderApiStatus.Unknown,
                vagueQuotaRemaining = null
            )
        }

        // Update ledger - since it's a data class, re-insert
        val updated = ledger.copy(
            totalTokensUsed = ledger.totalTokensUsed + tokens,
            tokensUsedToday = ledger.tokensUsedToday + tokens,
            tokensUsedThisHour = ledger.tokensUsedThisHour + tokens,
            vagueQuotaRemaining = ledger.vagueQuotaRemaining?.let { vq ->
                if (tokens <= vq.estimatedRemaining) {
                    vq.copy(
                        estimatedRemaining = vq.estimatedRemaining - tokens,
                        confidence = vq.confidence * 0.99,
                        lastUpdated = currentTimestamp()
                    )
                } else {
                    vq.copy(
                        estimatedRemaining = 0uL,
                        confidence = 0.0,
                        lastUpdated = currentTimestamp()
                    )
                }
            }
        )
        ledgers[provider] = updated

        return Result.success(Unit)
    }

    /**
     * Get current token ledger for provider
     */
    fun getLedger(provider: String): ProviderTokenLedger? = ledgers[provider]

    /**
     * Get all ledgers
     */
    fun getAllLedgers(): Map<String, ProviderTokenLedger> = ledgers.toMap()

    /**
     * Check if provider has sufficient quota
     */
    fun hasSufficientQuota(provider: String, tokensNeeded: ULong): Boolean {
        ledgers[provider]?.let { ledger ->
            ledger.vagueQuotaRemaining?.let { vq ->
                return vq.estimatedRemaining >= tokensNeeded
            }
        }
        return false
    }

    /**
     * Reset hourly tracking (call this hourly)
     */
    fun resetHourlyUsage() {
        for ((key, ledger) in ledgers) {
            ledgers[key] = ledger.copy(tokensUsedThisHour = 0uL)
        }
    }

    /**
     * Reset daily tracking (call this daily)
     */
    fun resetDailyUsage() {
        for ((key, ledger) in ledgers) {
            val initialQuota = when (ledger.providerName) {
                "kilo_code" -> 1_000_000uL
                "opencode" -> 500_000uL
                "openrouter" -> 2_000_000uL
                "nvidia" -> 3_000_000uL
                "moonshot" -> 1_500_000uL
                "groq" -> 2_000_000uL
                "xai" -> 1_500_000uL
                "cerebras" -> 2_000_000uL
                else -> ledger.vagueQuotaRemaining?.estimatedRemaining ?: 100_000uL
            }
            ledgers[key] = ledger.copy(
                tokensUsedToday = 0uL,
                vagueQuotaRemaining = ledger.vagueQuotaRemaining?.copy(
                    estimatedRemaining = initialQuota,
                    confidence = 0.8,
                    lastUpdated = currentTimestamp()
                )
            )
        }
    }
}

/**
 * Container for all provider configs
 */
data class ProviderConfigs(
    val kiloCode: KiloCodeConfig?,
    val opencode: OpenCodeConfig?,
    val openrouter: OpenRouterConfig?,
    val nvidia: NvidiaConfig?,
    val moonshot: MoonshotConfig?,
    val groq: GroqConfig?,
    val xai: XAIConfig?,
    val cerebras: CerebrasConfig?
)
