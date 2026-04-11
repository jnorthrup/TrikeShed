package borg.literbike.ccek.keymux

/**
 * Universal Model Facade implementation
 * Ported from facade.rs
 */

/**
 * Model facade - unified interface across providers
 * Aggregates models from all providers, checks quotas via DSEL, and enriches with metadata
 */
class ModelFacade(
    private val modelCards: ModelCardStore = ModelCardStore(),
    ruleEngine: RuleEngine = RuleEngine()
) {
    private val ruleEngine: RuleEngine
    private val providers = mutableMapOf<String, ProviderDef>()

    init {
        // Initialize DSEL engine with default quotas for active providers
        val initialEngine = DSELBuilder()
            .withQuota("global_pool", 10_000_000)
            .withProvider("openai", 2_000_000, 1u, 10.0, false)
            .withProvider("anthropic", 2_000_000, 1u, 15.0, false)
            .withProvider("google", 5_000_000, 2u, 5.0, false)
            .withFreeProvider("kilo_code", 1_000_000, 3u, 100_000, 3_000_000, 0u)
            .buildWithRuleEngine()
            .getOrNull()

        this.ruleEngine = initialEngine ?: RuleEngine()
    }

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
     * Aggregate models from all providers, check quotas via DSEL, and enrich with metadata.
     * Ported from handle_models() in facade.rs.
     */
    fun handleModels(activeProviders: List<String>): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        val allKnownModels = modelCards.getAllModels()

        // If tags should be ignored, return every cached model without prefix filtering
        val ignoreTags = Platform.getenv("MODELMUX_IGNORE_TAGS")
            ?.let { it.lowercase() in listOf("1", "true", "yes", "on") }
            ?: false

        // 1. Quota-Aware Selection & Priority Routing
        // Filter out providers that are out of quota
        val eligibleProviders = activeProviders.filter { provider ->
            ruleEngine.hasSufficientQuota(provider, 100uL)
        }

        // If we have no eligible providers or tags are ignored, include all models
        if (eligibleProviders.isEmpty() || ignoreTags) {
            for (mId in allKnownModels) {
                val provider = mId.split('/').firstOrNull() ?: "unknown"
                val metadata = modelCards.getCard(mId)
                models.add(ModelInfo(
                    id = mId,
                    ownedBy = provider,
                    metadata = metadata
                ))
            }
            return models
        }

        // 2. DSEL-Driven Discovery
        // Dynamically discover models that start with the provider prefix from ModelCardStore
        for (provider in eligibleProviders) {
            val prefix = "$provider/"
            val providerModels = allKnownModels.filter { it.startsWith(prefix) }

            for (mId in providerModels) {
                val metadata = modelCards.getCard(mId)
                models.add(ModelInfo(
                    id = mId,
                    ownedBy = provider,
                    metadata = metadata
                ))
            }
        }

        return models
    }
}

/**
 * Provider menu with quota information
 * Ported from menu.rs
 */
data class MuxMenu(
    val activeProviders: List<ProviderDef> = emptyList(),
    val selectedProvider: String? = null,
    val quotas: Map<String, ProviderQuotaInfo> = emptyMap(),
    val lastError: String? = null
) {
    fun providerNames(): List<String> = activeProviders.map { it.name }

    fun selectProvider(name: String): Boolean {
        if (activeProviders.any { it.name == name }) {
            return true
        }
        return false
    }

    fun routeModel(modelRef: String): Triple<String, String, String>? {
        if ('/' in modelRef) {
            return route(modelRef)
        }
        val provider = selectedProvider ?: activeProviders.firstOrNull()?.name ?: return null
        return route("$provider/$modelRef")
    }

    companion object {
        fun new(): MuxMenu {
            val activeProviders = discoverProviders()
            return MuxMenu(activeProviders = activeProviders)
        }
    }
}

/**
 * Quota information for a provider
 */
data class ProviderQuotaInfo(
    val provider: String,
    val usedTokens: ULong,
    val remainingTokens: ULong,
    val confidence: Double
)
