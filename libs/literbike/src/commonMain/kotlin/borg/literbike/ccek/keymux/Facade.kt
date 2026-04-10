package borg.literbike.ccek.keymux

/**
 * Model Facade - Universal interface for AI model providers
 */

/**
 * Model facade - unified interface across providers
 */
class ModelFacade(
    private val cardStore: ModelCardStore = ModelCardStore(),
    private val ruleEngine: RuleEngine = RuleEngine(),
    private val tokenLedger: TokenLedger = TokenLedger()
) {
    private val providers = mutableMapOf<String, ProviderDef>()
    private val quotas = mutableMapOf<String, QuotaContainer>()

    companion object {
        fun new(): ModelFacade = ModelFacade()
    }

    /**
     * Register a provider
     */
    fun registerProvider(provider: ProviderDef) {
        providers[provider.name] = provider
    }

    /**
     * Get provider by name
     */
    fun getProvider(name: String): ProviderDef? = providers[name]

    /**
     * Get all registered providers
     */
    fun allProviders(): List<ProviderDef> = providers.values.toList()

    /**
     * Set quota for a provider
     */
    fun setQuota(provider: String, total: Double, used: Double, resetAt: Long = 0L) {
        quotas[provider] = QuotaContainer(
            provider = provider,
            total = total,
            used = used,
            remaining = total - used,
            resetAt = resetAt
        )
    }

    /**
     * Get quota for a provider
     */
    fun getQuota(provider: String): QuotaContainer? = quotas[provider]

    /**
     * Get all provider quotas
     */
    fun allProviderQuotas(): Map<String, QuotaContainer> = quotas.toMap()

    /**
     * Get provider potential candidates
     */
    fun discoverProviders(): List<ProviderPotential> {
        return providers.map { (_, p) ->
            ProviderPotential(
                provider = p.name,
                score = if (p.isApiKeyValid) 1.0 else 0.0,
                available = p.isApiKeyValid,
                latencyMs = 0L
            )
        }
    }

    /**
     * Route request to best provider
     */
    fun routeRequest(modelId: String): String? {
        val candidates = discoverProviders()
        val parsedModel = ModelId.parse(modelId)

        if (parsedModel != null) {
            val provider = providers[parsedModel.provider]
            if (provider != null && provider.isApiKeyValid) {
                return parsedModel.provider
            }
        }

        return ruleEngine.selectProvider(candidates)
    }

    /**
     * Track token usage
     */
    fun trackTokens(provider: String, model: String, tokens: ULong) {
        tokenLedger.record(provider, model, tokens)
    }

    /**
     * Check if API key looks valid (not obviously fake)
     */
    fun isRealKeyPub(key: String): Boolean {
        // Basic validation - real keys are typically long enough
        return key.length >= 20 && !key.startsWith("sk-test-") && !key.contains("example")
    }

    /**
     * Add a model to a provider's store
     */
    fun addModel(provider: String, model: ModelInfo) {
        cardStore.addModel(provider, model)
    }

    /**
     * Get models for a provider
     */
    fun getModels(provider: String): List<ModelInfo> = cardStore.getModels(provider)

    /**
     * Get all models across providers
     */
    fun getAllModels(): List<ModelInfo> = cardStore.getAllModels()
}

/**
 * Provider menu with quota information
 */
data class MuxMenu(
    val providers: List<ProviderQuota> = emptyList()
) {
    companion object {
        fun fromFacade(facade: ModelFacade): MuxMenu {
            return MuxMenu(
                providers = facade.allProviders().map { p ->
                    ProviderQuota(
                        name = p.name,
                        baseUrl = p.baseUrl,
                        quota = facade.getQuota(p.name),
                        available = p.isApiKeyValid
                    )
                }
            )
        }
    }
}

/**
 * Provider quota summary
 */
data class ProviderQuota(
    val name: String,
    val baseUrl: String,
    val quota: QuotaContainer? = null,
    val available: Boolean = false
)

/**
 * DSEL builder for configuring provider routing
 */
class DSELBuilder {
    private val rules = mutableListOf<ProviderSelectionRule>()

    fun route(rule: ProviderSelectionRule): DSELBuilder {
        rules.add(rule)
        return this
    }

    fun fallback(vararg providers: String): DSELBuilder {
        rules.add(ProviderSelectionRule.Fallback(providers.toList()))
        return this
    }

    fun roundRobin(vararg providers: String): DSELBuilder {
        rules.add(ProviderSelectionRule.RoundRobin(providers.toList()))
        return this
    }

    fun costOptimized(vararg providers: String): DSELBuilder {
        rules.add(ProviderSelectionRule.CostOptimized(providers.toList()))
        return this
    }

    fun qualityFirst(vararg providers: String): DSELBuilder {
        rules.add(ProviderSelectionRule.QualityFirst(providers.toList()))
        return this
    }

    fun fixed(provider: String): DSELBuilder {
        rules.add(ProviderSelectionRule.Fixed(provider))
        return this
    }

    fun build(): List<ProviderSelectionRule> = rules.toList()
}

/**
 * Model mapping between protocol types
 */
object ModelMapping {
    /**
     * Map a model name from one provider format to another
     */
    fun mapModelName(model: String, sourceProvider: String, targetProvider: String): String {
        // Basic mapping - in reality this would have provider-specific mappings
        return when {
            sourceProvider == "anthropic" && targetProvider == "openai" -> {
                // Anthropic models to OpenAI equivalent
                when {
                    model.startsWith("claude-3-opus") -> "gpt-4-turbo"
                    model.startsWith("claude-3-sonnet") -> "gpt-4"
                    model.startsWith("claude-3-haiku") -> "gpt-3.5-turbo"
                    else -> "gpt-3.5-turbo"
                }
            }
            sourceProvider == "openai" && targetProvider == "anthropic" -> {
                when {
                    model.startsWith("gpt-4-turbo") -> "claude-3-opus-20240229"
                    model.startsWith("gpt-4") -> "claude-3-sonnet-20240229"
                    model.startsWith("gpt-3.5") -> "claude-3-haiku-20240307"
                    else -> "claude-3-haiku-20240307"
                }
            }
            else -> model
        }
    }
}
