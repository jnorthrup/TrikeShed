package borg.literbike.modelmux

/**
 * Decision module - Unified model mux + keymux integration.
 * Ported from literbike/src/modelmux/decision.rs.
 */

/**
 * Unified Model Mux + Keymux State.
 */
data class UnifiedMuxState(
    val envProfile: NormalizedEnvProfile,
    val lifecycle: Any? = null, // ModelmuxMvpLifecycle placeholder
    val quotaSlots: List<QuotaInventorySlot> = emptyList(),
    val selectedProvider: String? = null,
    val precedence: PrecedenceMode = PrecedenceMode.EnvFirst
) {
    companion object {
        fun fromEnvPairs(envPairs: List<Pair<String, String>>): UnifiedMuxState {
            val envProfile = normalizeEnvPairs(envPairs)
            return UnifiedMuxState(
                envProfile = envProfile,
                lifecycle = null,
                quotaSlots = emptyList(),
                selectedProvider = null,
                precedence = PrecedenceMode.EnvFirst
            )
        }
    }

    /** Make routing decision based on precedence mode */
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
                    RoutingDecision.balanced(envDecision.providerId, 0.95f)
                } else {
                    val envScore = envDecision.confidence * envWeight
                    val keymuxScore = keymuxDecision.confidence * keymuxWeight
                    if (envScore > keymuxScore) {
                        RoutingDecision.balanced(envDecision.providerId, envScore)
                    } else {
                        RoutingDecision.balanced(keymuxDecision.providerId, keymuxScore)
                    }
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
                        ProviderPrecedence.EnvOnly -> return RoutingDecision.fromEnv(provider, 0.9f)
                        ProviderPrecedence.KeymuxOnly -> return RoutingDecision.fromKeymux(provider, 0.9f)
                        else -> return makeDecision()
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
 * Precedence mode for unified mux decision making.
 */
sealed class PrecedenceMode {
    data object EnvFirst : PrecedenceMode()
    data object KeymuxFirst : PrecedenceMode()
    data class Balanced(val envWeight: Float, val keymuxWeight: Float) : PrecedenceMode()
    data class Custom(val rules: List<PrecedenceRule>) : PrecedenceMode()
}

/**
 * Precedence rule for custom configuration.
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
 * Provider-specific precedence.
 */
enum class ProviderPrecedence {
    EnvOnly,
    KeymuxOnly,
    EnvFirst,
    KeymuxFirst
}

/**
 * Decision source.
 */
sealed class DecisionSource {
    data object EnvProjection : DecisionSource()
    data object Keymux : DecisionSource()
    data class Balanced(val confidence: Float) : DecisionSource()
}

/**
 * Routing decision result.
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
                providerId,
                DecisionSource.EnvProjection,
                confidence,
                "Selected via env projection (literbike host)"
            )
        }

        fun fromKeymux(providerId: String, confidence: Float): RoutingDecision {
            return RoutingDecision(
                providerId,
                DecisionSource.Keymux,
                confidence,
                "Selected via keymux (literbike host)"
            )
        }

        fun balanced(providerId: String, confidence: Float): RoutingDecision {
            return RoutingDecision(
                providerId,
                DecisionSource.Balanced(confidence),
                confidence,
                "Selected via balanced decision (literbike unified)"
            )
        }
    }
}

// Placeholder types for env profile normalization
data class NormalizedEnvProfile(
    val entries: List<EnvEntry>
)

data class EnvEntry(
    val key: String,
    val value: String
)

data class QuotaInventorySlot(
    val provider: String,
    val used: Long,
    val remaining: Long
)

fun normalizeEnvPairs(envPairs: List<Pair<String, String>>): NormalizedEnvProfile {
    return NormalizedEnvProfile(
        envPairs.map { (k, v) -> EnvEntry(k, v) }
    )
}
