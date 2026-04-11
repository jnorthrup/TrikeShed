package borg.literbike.ccek.keymux

/**
 * Model Mux + Keymux Unified Integration
 * literbike hosts both model mux and keymux with unified precedence
 */

/**
 * Unified MODEL MUX + KEYMUX State
 * literbike hosts both systems with unified decision making
 */
data class UnifiedMuxState(
    val envProfile: NormalizedEnvProfile,
    val lifecycle: ModelmuxMvpLifecycle? = null,
    val quotaSlots: List<QuotaInventorySlot> = emptyList(),
    val selectedProvider: String? = null,
    val precedence: PrecedenceMode = PrecedenceMode.EnvFirst
) {
    companion object {
        fun fromEnvPairs(envPairs: List<Pair<String, String>>): UnifiedMuxState {
            val envProfile = normalizeEnvPairs(envPairs)
            return UnifiedMuxState(envProfile = envProfile)
        }
    }

    fun withPrecedence(mode: PrecedenceMode): UnifiedMuxState {
        return copy(precedence = mode)
    }

    /**
     * Make routing decision based on precedence mode
     */
    fun makeDecision(): RoutingDecision? {
        return when (precedence) {
            is PrecedenceMode.EnvFirst -> decisionFromEnv()
            is PrecedenceMode.KeymuxFirst -> decisionFromKeymux()
            is PrecedenceMode.Balanced -> decisionBalanced(
                precedence.envWeight,
                precedence.keymuxWeight
            )
            is PrecedenceMode.Custom -> decisionCustom(precedence.rules)
        }
    }

    private fun decisionFromEnv(): RoutingDecision? {
        for (entry in envProfile.entries) {
            if (entry.key.endsWith("_API_KEY")) {
                val provider = extractProviderFromKey(entry.key)
                if (provider.isNotEmpty()) {
                    return RoutingDecision.fromEnv(provider, 0.9f)
                }
            }
        }
        return null
    }

    private fun decisionFromKeymux(): RoutingDecision? {
        selectedProvider?.let { provider ->
            return RoutingDecision.fromKeymux(provider, 0.95f)
        }
        return null
    }

    private fun decisionBalanced(envWeight: Float, keymuxWeight: Float): RoutingDecision? {
        val envDecision = decisionFromEnv()
        val keymuxDecision = decisionFromKeymux()

        return when {
            envDecision != null && keymuxDecision != null -> {
                if (envDecision.providerId == keymuxDecision.providerId) {
                    return RoutingDecision.balanced(envDecision.providerId, 0.95f)
                }
                val envScore = envDecision.confidence * envWeight
                val keymuxScore = keymuxDecision.confidence * keymuxWeight
                if (envScore > keymuxScore) {
                    RoutingDecision.balanced(envDecision.providerId, envScore)
                } else {
                    RoutingDecision.balanced(keymuxDecision.providerId, keymuxScore)
                }
            }
            envDecision != null -> envDecision
            keymuxDecision != null -> keymuxDecision
            else -> null
        }
    }

    private fun decisionCustom(rules: List<PrecedenceRule>): RoutingDecision? {
        for (entry in envProfile.entries) {
            if (entry.key.endsWith("_API_KEY")) {
                val provider = extractProviderFromKey(entry.key)
                val rule = rules.find { it.providerId == provider }
                if (rule != null) {
                    when (rule.precedence) {
                        ProviderPrecedence.EnvOnly -> {
                            return RoutingDecision.fromEnv(provider, 0.9f)
                        }
                        ProviderPrecedence.KeymuxOnly -> {
                            return RoutingDecision.fromKeymux(provider, 0.9f)
                        }
                        ProviderPrecedence.EnvFirst,
                        ProviderPrecedence.KeymuxFirst -> return makeDecision()
                    }
                }
            }
        }
        return makeDecision()
    }

    private fun extractProviderFromKey(key: String): String {
        return key
            .removeSuffix("_API_KEY")
            .removeSuffix("_AUTH_TOKEN")
            .lowercase()
    }
}

/**
 * Precedence mode for unified mux decision making
 */
sealed class PrecedenceMode {
    data object EnvFirst : PrecedenceMode()
    data object KeymuxFirst : PrecedenceMode()
    data class Balanced(val envWeight: Float, val keymuxWeight: Float) : PrecedenceMode()
    data class Custom(val rules: List<PrecedenceRule>) : PrecedenceMode()
}

/**
 * Precedence rule for custom configuration
 */
data class PrecedenceRule(
    val providerId: String,
    val precedence: ProviderPrecedence
) {
    companion object {
        fun new(providerId: String, precedence: ProviderPrecedence): PrecedenceRule {
            return PrecedenceRule(providerId, precedence)
        }
    }
}

/**
 * Provider-specific precedence
 */
enum class ProviderPrecedence {
    EnvOnly, KeymuxOnly, EnvFirst, KeymuxFirst
}

/**
 * Decision source
 */
sealed class DecisionSource {
    data object EnvProjection : DecisionSource()
    data object Keymux : DecisionSource()
    data class Balanced(val confidence: Float) : DecisionSource()
}

/**
 * Routing decision result
 */
data class RoutingDecision(
    val providerId: String,
    val source: DecisionSource,
    val confidence: Float,
    val reason: String
) {
    companion object {
        fun fromEnv(providerId: String, confidence: Float): RoutingDecision {
            return RoutingDecision(
                providerId = providerId,
                source = DecisionSource.EnvProjection,
                confidence = confidence,
                reason = "Selected via env projection (literbike host)"
            )
        }

        fun fromKeymux(providerId: String, confidence: Float): RoutingDecision {
            return RoutingDecision(
                providerId = providerId,
                source = DecisionSource.Keymux,
                confidence = confidence,
                reason = "Selected via keymux (literbike host)"
            )
        }

        fun balanced(providerId: String, confidence: Float): RoutingDecision {
            return RoutingDecision(
                providerId = providerId,
                source = DecisionSource.Balanced(confidence),
                confidence = confidence,
                reason = "Selected via balanced decision (literbike unified)"
            )
        }
    }
}

// ============================================================================
// Env facade parity types (simplified port from Rust env_facade_parity)
// ============================================================================

/**
 * Normalized environment profile from env projection
 */
data class NormalizedEnvProfile(
    val entries: List<EnvEntry> = emptyList(),
    val providerKeys: Map<String, String> = emptyMap(),
    val modelMappings: Map<String, String> = emptyMap()
)

data class EnvEntry(
    val key: String,
    val value: String
)

/**
 * Modelmux lifecycle state
 */
enum class ModelmuxMvpLifecycle {
    Initializing, Active, Degraded, ShuttingDown
}

/**
 * Quota inventory slot
 */
data class QuotaInventorySlot(
    val providerId: String,
    val tokensRemaining: ULong,
    val confidence: Double,
    val lastUpdated: Long
)

/**
 * Normalize environment variable pairs into a profile
 */
fun normalizeEnvPairs(envPairs: List<Pair<String, String>>): NormalizedEnvProfile {
    val entries = envPairs.map { (key, value) -> EnvEntry(key, value) }
    val providerKeys = mutableMapOf<String, String>()
    val modelMappings = mutableMapOf<String, String>()

    for ((key, value) in envPairs) {
        if (key.endsWith("_API_KEY") || key.endsWith("_AUTH_TOKEN")) {
            val provider = key
                .removeSuffix("_API_KEY")
                .removeSuffix("_AUTH_TOKEN")
                .lowercase()
            providerKeys[provider] = value
        }
        if (key.startsWith("MODEL_") && key.endsWith("_MAP")) {
            modelMappings[key] = value
        }
    }

    return NormalizedEnvProfile(
        entries = entries,
        providerKeys = providerKeys,
        modelMappings = modelMappings
    )
}
